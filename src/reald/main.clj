(ns reald.main
  (:require [io.pedestal.http :as http]
            [hiccup2.core :as h]
            [ring.util.mime-type :as mime]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [cognitect.transit :as transit]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [clojure.edn :as edn])
  (:import (java.io PushbackReader)))

(def register
  [(pc/resolver `projects
                {::pc/output [:reald.root/projects]}
                (fn [{::keys [project-roots]} _]
                  (let [projects (for [root project-roots
                                       file (.listFiles (io/file root))
                                       :when (.isDirectory file)]
                                   {:reald.project/dir-file file})]
                    {:reald.root/projects projects})))
   (pc/resolver `dir-file->dir
                {::pc/input  #{:reald.project/dir-file}
                 ::pc/output [:reald.project/dir]}
                (fn [_ {:reald.project/keys [dir-file]}]
                  {:reald.project/dir (.getCanonicalPath dir-file)}))
   (pc/resolver `dir->dir-file
                {::pc/input  #{:reald.project/dir}
                 ::pc/output [:reald.project/dir-file]}
                (fn [_ {:reald.project/keys [dir]}]
                  {:reald.project/dir-file (io/file dir)}))
   (pc/resolver `dir-file->run-configs
                {::pc/input  #{:reald.project/dir-file}
                 ::pc/output [:reald.project/run-configs]}
                (fn [_ {:reald.project/keys [dir-file]}]
                  {:reald.project/run-configs (->> (io/file dir-file ".repl-configs")
                                                   (io/reader)
                                                   (PushbackReader.)
                                                   (edn/read)
                                                   (map (fn [{:keys [description profiles] :as x}]
                                                          {:reald.run-config/ident   description
                                                           :reald.project/dir-file   dir-file
                                                           :reald.run-config/aliases profiles})))}))
   (pc/resolver `dir-file->aliases
                {::pc/input  #{:reald.project/dir-file}
                 ::pc/output [:reald.project/aliases]}
                (fn [_ {:reald.project/keys [dir-file]}]
                  {:reald.project/aliases (-> (io/file dir-file "deps.edn")
                                              (io/reader)
                                              (PushbackReader.)
                                              (edn/read)
                                              (:aliases)
                                              (keys))}))
   (pc/resolver `dir-file->name
                {::pc/input  #{:reald.project/dir-file}
                 ::pc/output [:reald.project/name]}
                (fn [_ {:reald.project/keys [dir-file]}]
                  {:reald.project/name (.getName dir-file)}))])

(def parser
  (p/parser {::p/plugins [(pc/connect-plugin {::pc/register register})]
             ::p/env     {::project-roots          [(str (System/getProperty "user.home") "/src")]
                          ::p/reader               [p/map-reader
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
                                            [:script {:src "/ui.js"}]]]]
                                 {:headers {"Content-Type" (get mime/default-mime-types "html")}
                                  :body    (str (h/html
                                                  {:mode :html}
                                                  (h/raw "<!DOCTYPE html>")
                                                  body))
                                  :status  200}))
                    :route-name ::index]}})

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
               http/create-server
               http/start))))
