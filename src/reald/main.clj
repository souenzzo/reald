(ns reald.main
  (:require [io.pedestal.http :as http]
            [hiccup2.core :as h]
            [ring.util.mime-type :as mime]
            [clojure.java.io :as io]
            [reald.process :as process]
            [taoensso.timbre :as log]
            [cognitect.transit :as transit]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [datascript.core :as ds]
            [clojure.string :as string]
            [datascript.core :as d]
            [sci.core :as sci])
  (:import (java.io PushbackReader File)
           (java.util Date)))
(set! *warn-on-reflection* true)

(defn generate-classpath!
  [{:reald.project/keys [dir aliases]}]
  (let [cmd (filter string? ["clojure"
                             (when-not (empty? aliases)
                               (str "-A" (string/join aliases)))
                             "-Spath"])]
    (binding [sh/*sh-dir* dir]
      (-> (apply sh/sh cmd)
          :out
          string/split-lines
          first))))

(def register
  [(pc/resolver `processes
                {::pc/output [:reald.root/processes]}
                (fn [{:keys [conn]} _]
                  {:reald.root/processes (d/q '[:find [(pull ?e [*]) ...]
                                                :where
                                                [?e ::process/pid]]
                                              (d/db conn))}))
   (pc/mutation `reald.root/project-script
                {}
                (fn [{:keys [conn]} {:reald.root/keys [project-script]}]
                  (d/transact! conn [{::root                     ::root
                                      :reald.root/project-script project-script}])
                  {}))
   (pc/resolver `project-script
                {::pc/output [:reald.root/project-script]}
                (fn [{:keys [conn]} _]
                  {:reald.root/project-script (-> '[:find ?project-script
                                                    :where
                                                    [_ :reald.root/project-script ?project-script]]
                                                  (d/q (d/db conn))
                                                  ffirst
                                                  (or "(.listFiles (io/file HOME \"src\"))"))}))
   (pc/resolver `pull
                {::pc/output [::process/instance]
                 ::pc/input  #{::process/pid}}
                (fn [{:keys [conn]} {::process/keys [pid]}]
                  (ds/pull (d/db conn)
                           '[*]
                           [::process/pid (Long/parseLong (str "0" pid))])))
   (pc/resolver `alive?
                {::pc/input  #{::process/instance}
                 ::pc/output [::process/alive?]}
                (fn [_ {::process/keys [instance]}]
                  {::process/alive? (process/alive? instance)}))
   (pc/mutation `stop
                {::pc/sym    `process/stop
                 ::pc/params #{::process/pid}
                 ::pc/output [::process/alive?]}
                (fn [{:keys [conn]} {::process/keys [pid]}]
                  (-> (ds/pull (d/db conn)
                               '[*]
                               [::process/pid (Long/parseLong (str "0" pid))])
                      ::process/instance
                      process/destroy)
                  {::process/pid pid}))
   (pc/mutation `reald.project/create-repl
                {}
                (fn [{:keys [parser conn]
                      :as   env} {:reald.project/keys [dir aliases]}]
                  (let [cp (generate-classpath! {:reald.project/dir     dir
                                                 :reald.project/aliases aliases})
                        command ["java" "-cp" cp "clojure.main"]
                        ref-pid (promise)
                        on-stdout (fn [text]
                                    (ds/transact! conn
                                                  [{:reald.text-fragment/text    text
                                                    :reald.text-fragment/inst    (new Date)
                                                    :reald.text-fragment/process [::process/pid @ref-pid]}]))
                        on-stdin (fn []
                                   (let [{:keys [reald.input-text/text db/id]} (->> (ds/q '[:find [(pull ?e [:db/id
                                                                                                             :reald.input-text/text
                                                                                                             :reald.input-text/inst])
                                                                                                   ...]
                                                                                            :in $ ?pid
                                                                                            :where

                                                                                            [?process ::process/pid ?pid]
                                                                                            [?e :reald.input-text/pending-input true]
                                                                                            [?e :reald.input-text/process ?process]]
                                                                                          (ds/db conn) @ref-pid)
                                                                                    (sort-by :reald.input-text/inst)
                                                                                    first)]
                                     (when id
                                       (d/transact! conn [[:db.fn/retractEntity id]])
                                       text)))
                        process (process/execute {::process/command   command
                                                  ::process/directory (io/file dir)
                                                  ::process/on-stdout on-stdout
                                                  ::process/on-stdin  on-stdin})
                        pid (process/pid process)]
                    (ds/transact! conn [{::process/pid      pid
                                         ::process/instance process
                                         :reald.project/dir dir}])
                    (deliver ref-pid pid)
                    {:reald.project/dir dir})))
   (pc/resolver `script-output
                {::pc/input  #{:reald.root/project-script}
                 ::pc/output [:reald.root/project-script-result]}
                (fn [env {:reald.root/keys [project-script]}]
                  (let [result (try (sci/eval-string project-script
                                                     {:bindings   (into {}
                                                                        (map (juxt (comp symbol key)
                                                                                   val))
                                                                        (System/getenv))
                                                      :classes    {'java.io.File java.io.File}
                                                      :namespaces {'io {'file io/file}}})
                                    (catch Throwable ex
                                      ex))]
                    {:reald.root/project-script-result result})))
   (pc/resolver `valid?
                {::pc/input  #{:reald.root/project-script-result}
                 ::pc/output [:reald.root/project-script-result-valid?]}
                (fn [env {:reald.root/keys [project-script-result]}]
                  {:reald.root/project-script-result-valid? (boolean (when (seqable? project-script-result)
                                                                       (every? #(instance? java.io.File %)
                                                                               project-script-result)))}))
   (pc/resolver `script-output-str
                {::pc/input  #{:reald.root/project-script-result}
                 ::pc/output [:reald.root/project-script-result-str]}
                (fn [env {:reald.root/keys [project-script-result]}]
                  {:reald.root/project-script-result-str (pr-str project-script-result)}))
   (pc/resolver `projects
                {::pc/input  #{:reald.root/project-script-result
                               :reald.root/project-script-result-valid?}
                 ::pc/output [:reald.root/projects]}
                (fn [_ {:reald.root/keys [project-script-result-valid?
                                          project-script-result]}]
                  (let [projects (for [file (when project-script-result-valid?
                                              project-script-result)]
                                   {:reald.project/dir-file file})]
                    {:reald.root/projects projects})))
   (pc/resolver `active-processes
                {::pc/input  #{:reald.project/dir}
                 ::pc/output [:reald.project/active-processes]}
                (fn [{:keys [conn]} {:reald.project/keys [dir]}]
                  (let []
                    {:reald.project/active-processes (d/q '[:find [(pull ?e [*]) ...]
                                                            :in $ ?dir
                                                            :where
                                                            [?e ::process/instance]
                                                            [?e :reald.project/dir ?dir]]
                                                          (d/db conn) dir)})))
   (pc/resolver `dir-file->dir
                {::pc/input  #{:reald.project/dir-file}
                 ::pc/output [:reald.project/dir]}
                (fn [_ {:reald.project/keys [^File dir-file]}]
                  {:reald.project/dir (.getCanonicalPath dir-file)}))
   (pc/resolver `dir->dir-file
                {::pc/input  #{:reald.project/dir}
                 ::pc/output [:reald.project/dir-file]}
                (fn [_ {:reald.project/keys [dir]}]
                  {:reald.project/dir-file (io/file dir)}))
   (pc/resolver `foo
                {::pc/output [:reald.process/repl-io]}
                (fn [_ _]
                  {:reald.process/repl-io (vec (for [i (range 10)]
                                                 {:reald.repl-io/line      (str "line " i)
                                                  :reald.repl-io/origin    (rand-nth ["stdin"
                                                                                      "stdout"
                                                                                      "stderr"])
                                                  :reald.repl-io/inst      (new Date)
                                                  :reald.repl-io/direction "abc"}))}))
   (pc/resolver `dir-file->run-configs
                {::pc/input  #{:reald.project/dir-file}
                 ::pc/output [:reald.project/run-configs]}
                (fn [_ {:reald.project/keys [dir-file]}]
                  (let [f (io/file dir-file ".repl-configs")]
                    {:reald.project/run-configs (vec (when (.isFile f)
                                                       (->>
                                                         (io/reader f)
                                                         (PushbackReader.)
                                                         (edn/read)
                                                         (map (fn [{:keys [description profiles]}]
                                                                {:reald.run-config/ident   description
                                                                 :reald.project/dir-file   dir-file
                                                                 :reald.run-config/aliases profiles})))))})))
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
                (fn [_ {:reald.project/keys [^File dir-file]}]
                  {:reald.project/name (.getName dir-file)}))])


(def schema {::process/pid             {:db/unique :db.unique/identity}
             ::root                    {:db/unique :db.unique/identity}
             :reald.input-text/process {:db/unique :db.unique/identity}})
(defonce entity-conn (ds/create-conn schema))


(def parser
  (p/parser {::p/plugins [(pc/connect-plugin {::pc/register register})]
             ::p/mutate  pc/mutate
             ::p/env     {::p/reader               [p/map-reader
                                                    pc/reader3
                                                    pc/open-ident-reader
                                                    p/env-placeholder-reader]
                          :conn                    entity-conn
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
               http/dev-interceptors
               http/create-server
               http/start))))
