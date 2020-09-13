(ns reald.main
  (:require [io.pedestal.http :as http]
            [hiccup2.core :as h]
            [ring.util.mime-type :as mime]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.trace :as pt]
            [reald.instance :as instance]
            [io.pedestal.log :as log]))
(set! *warn-on-reflection* true)
;; TODO: A daemon to:
;; - Remove dead process
;; - Check and update ::instance/alive?
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
   (pc/resolver `pid->path
                {::pc/input  #{::instance/pid}
                 ::pc/output [:reald.project/path
                              ::instance/out]}
                (fn [_ {::instance/keys [pid]}]
                  (get @instances pid)))
   (pc/resolver `out->values
                {::pc/input  #{::instance/out}
                 ::pc/output [::instance/values]}
                (fn [_ {::instance/keys [out]}]
                  {::instance/values (map ->value @out)}))
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
