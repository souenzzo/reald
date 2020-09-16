(ns reald.main
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.trace :as pt]
            [hiccup2.core :as h]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [reald.instance :as instance]
            [ring.util.mime-type :as mime]
            [clojure.core.async :as async]
            [clojure.string :as string])
  (:import (java.util UUID)
           (java.io PushbackReader IOException InputStream)
           (java.net Socket)))

(set! *warn-on-reflection* true)

;; TODO: A daemon to:
;; - Remove dead process
;; - Check and update ::instance/alive?
;; - Create [:out :values] from [:out :val]
(defonce instances
         (atom {}))

(defonce terminals
         (atom {}))

(defn ->value
  [{:keys [tag val id]
    :as   this}]
  (assoc this
    :reald.value/id id
    :reald.value/tag tag
    :reald.value/val val))


(defn watch!
  [^InputStream is in]
  (with-open [rdr (PushbackReader. (io/reader is))]
    (loop []
      (let [val (edn/read {:eof rdr} rdr)]
        (when-not (= rdr val)
          (async/put! in val))
        (recur)))))

(def register
  [(pc/mutation `reald.rt/remove-project
                {::pc/params [:reald.project/path]}
                (fn [_ _]
                  {}))
   (pc/mutation `reald.rt/add-new-project
                {::pc/params [:reald.project/path]}
                (fn [_ _]
                  {}))
   (pc/mutation `reald.rt/launch-instance
                {::pc/params [:reald.project/path]
                 ::pc/output [:reald.project/path]}
                (fn [_ {:reald.project/keys [path]}]
                  (swap! instances
                         (fn [is]
                           (let [{::instance/keys [pid]
                                  :as             instance} (instance/start {:reald.project/path path
                                                                             ::instance/path     path})]
                             (assoc is pid instance))))
                  {:reald.project/path path}))
   (pc/mutation `reald.instance/destroy
                {::pc/params [::instance/pid]
                 ::pc/output [::instance/pid]}
                (fn [_ {::instance/keys [pid]}]
                  (some-> instances
                          deref
                          (get pid)
                          instance/stop)
                  {::instance/pid pid}))
   (pc/mutation `reald.instance/input
                {::pc/params [::instance/pid
                              ::instance/form]
                 ::pc/output [::instance/pid]}
                (fn [_ {::instance/keys [pid form]}]
                  (when-let [{::instance/keys [in]
                              :as             instance} (some-> instances
                                                                deref
                                                                (get pid))]
                    (async/put! in {:tag :in
                                    :val (str (string/trim-newline form)
                                              "\n")})
                    instance)))

   (pc/mutation `reald.instance/connect-terminal
                {::pc/params [::instance/pid
                              ::instance/port]
                 ::pc/output [:reald.terminal/id]}
                (fn [_ {::instance/keys [pid port]}]
                  (let [{::instance/keys [path]} (get @instances pid)
                        id (UUID/randomUUID)
                        socket (Socket. "127.0.0.1" (int (edn/read-string port)))
                        io (atom [])
                        in (async/chan 1e2
                                       (comp
                                         (map #(doto % (prn pid)))
                                         (map #(assoc % :id (UUID/randomUUID)))
                                         (map #(assoc % :values (instance/read-all-strings %)))
                                         (map #(do
                                                 (swap! io conj %)
                                                 %))))
                        pubsub (async/pub in :tag)
                        stdin (io/writer (.getOutputStream socket))
                        stdout (.getInputStream socket)
                        terminal {:reald.terminal/port   port
                                  :reald.terminal/socket socket
                                  :reald.terminal/stdin  stdin
                                  :reald.terminal/io     io
                                  :reald.terminal/id     id
                                  :reald.terminal/path   path
                                  :reald.terminal/pid    pid
                                  :reald.terminal/pubsub pubsub
                                  :reald.terminal/in     in}
                        appends (async/chan)]
                    (async/sub pubsub :in appends)
                    (async/thread
                      (loop []
                        (when-let [{:keys [val]} (async/<!! appends)]
                          (.append stdin (str val))
                          (.flush stdin)
                          (recur))))
                    (async/thread
                      (try
                        (watch! stdout in)
                        (catch IOException _)
                        (finally
                          (try
                            (.close stdout)
                            (catch IOException _)))))
                    (swap! terminals assoc id terminal)
                    (assoc terminal
                      :reald.instance/pid pid))))
   (pc/resolver `pid->path
                {::pc/input  #{::instance/pid}
                 ::pc/output [:reald.project/path]}
                (fn [_ {::instance/keys [pid]}]
                  (get @instances pid)))
   (pc/resolver `pid->path2
                {::pc/input  #{:reald.terminal/pid}
                 ::pc/output [::instance/pid]}
                (fn [_ {:reald.terminal/keys [pid]}]
                  {::instance/pid pid}))
   (pc/mutation `reald.terminal/input
                {::pc/params [:reald.terminal/id
                              :reald.terminal/form]}
                (fn [_ {:reald.terminal/keys [id form]}]
                  (when-let [{:reald.terminal/keys [in]
                              :as                  terminal} (some-> @terminals (get id))]
                    (async/put! in {:tag :in
                                    :val (str (string/trim-newline form)
                                              "\n")})
                    terminal)))
   (pc/resolver `terminal->instance
                {::pc/input  #{:reald.terminal/id}
                 ::pc/output [:reald.instance/pid]}
                (fn [_ {:reald.terminal/keys [id]}]
                  (some-> @terminals (get id))))
   (pc/resolver `out->values
                {::pc/input  #{::instance/io}
                 ::pc/output [::instance/values]}
                (fn [_ {::instance/keys [io]}]
                  {::instance/values (map ->value @io)}))
   (pc/resolver `out->valuesx
                {::pc/input  #{:reald.terminal/io}
                 ::pc/output [:reald.terminal/values]}
                (fn [_ {:reald.terminal/keys [io]}]
                  {:reald.terminal/values (map ->value @io)}))
   (pc/resolver `alive?
                {::pc/input  #{::instance/pid}
                 ::pc/output [::instance/alive?]}
                (fn [_ {::instance/keys [pid]}]
                  (some-> instances
                          deref
                          (get pid)
                          instance/alive?
                          (->> (hash-map ::instance/alive?)))))

   (pc/resolver `project-instances
                {::pc/input  #{:reald.project/path}
                 ::pc/output [:reald.project/active-instances]}
                (fn [_ {:reald.project/keys [path]}]
                  {:reald.project/active-instances (filter
                                                     (comp #{path} :reald.project/path)
                                                     (vals @instances))}))

   (pc/resolver `active-terminals
                {::pc/input  #{:reald.instance/pid}
                 ::pc/output [:reald.instance/active-terminals]}
                (fn [_ {:reald.instance/keys [pid]}]
                  {:reald.instance/active-terminals (filter
                                                      (comp #{pid} :reald.terminal/pid)
                                                      (vals @terminals))}))

   (pc/resolver `active-projects
                {::pc/output [:reald.rt/active-projects]}
                (fn [_ _]
                  {:reald.rt/active-projects [{:reald.project/path (.getCanonicalPath (io/file "."))}]}))])

(def parser
  (p/parser {::p/plugins [(pc/connect-plugin {::pc/register register})
                          pt/trace-plugin
                          p/error-handler-plugin]
             ::p/mutate  pc/mutate
             ::p/env     {::p/reader               [p/map-reader
                                                    pc/reader3
                                                    pc/open-ident-reader
                                                    p/env-placeholder-reader]
                          ::p/placeholder-prefixes #{">"}}}))

(defn service
  [& _]
  {::http/routes #{["/api" :post (fn [{:keys [body]
                                       :as   env}]
                                   (let [tx (some-> body
                                                    io/input-stream
                                                    (transit/reader :json)
                                                    transit/read)
                                         _ (log/info :tx tx)
                                         result (parser env tx)]
                                     (log/info :tx tx :restul result)
                                     {:body   (fn [w]
                                                (try
                                                  (-> w
                                                      io/output-stream
                                                      (transit/writer :json)
                                                      (transit/write result))))
                                      :status 200}))
                    :route-name ::api]
                   ["/" :get (fn [req]
                               (let [body [:html
                                           [:head
                                            [:title "reald"]]
                                           [:body
                                            {:onload "reald.ui.main('target')"}
                                            [:div {:id "target"}]
                                            [:script {:src "/reald/ui.js"}]]]]
                                 {:headers {"Content-Type" (get mime/default-mime-types "html")}
                                  :body    (str (h/html
                                                  {:mode :html}
                                                  (h/raw "<!DOCTYPE html>")
                                                  body))
                                  :status  200}))
                    :route-name ::index]
                   ["/workspaces" :get (fn [req]
                                         (let [body [:html
                                                     [:head
                                                      [:title "workspaces"]]
                                                     [:body
                                                      [:section {:id "app"}]
                                                      [:script {:src "workspaces/main.js"}]]]]
                                           {:headers {"Content-Type" (get mime/default-mime-types "html")}
                                            :body    (str (h/html
                                                            {:mode :html}
                                                            (h/raw "<!DOCTYPE html>")
                                                            body))
                                            :status  200}))
                    :route-name ::workspaces]}})

(defonce http-state
         (atom nil))

(defn -main
  [& _]
  (swap! http-state
         (fn [st]
           (when st
             (http/stop st))
           (-> (service)
               (assoc ::http/type :jetty
                      ::http/join? false
                      ::http/secure-headers {:content-security-policy-settings ""}
                      ::http/file-path "target/public"
                      ::http/port 1111)
               http/default-interceptors
               http/dev-interceptors
               http/create-server
               http/start))))
