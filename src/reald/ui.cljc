(ns reald.ui
  (:require [com.fulcrologic.fulcro.application :as fa]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [com.fulcrologic.fulcro.data-fetch :as df]
            #?@(:cljs    [[com.fulcrologic.fulcro.dom :as dom]
                          [com.fulcrologic.fulcro.networking.http-remote :as http]
                          [goog.dom :as gdom]]
                :default [[com.fulcrologic.fulcro.dom-server :as dom]])
            [com.fulcrologic.fulcro.mutations :as m]
            [clojure.string :as string]
            [clojure.edn :as edn]))


(defsc LiProcess [this {:reald.process/keys [pid alive?]
                        :reald.project/keys [dir]}]
  {:query [:reald.process/pid
           :reald.process/alive?
           :reald.project/dir]
   :ident :reald.process/pid}
  (dom/li
    (dom/button
      {:style   {:background-color (if alive?
                                     "green" "red")}
       :onClick #(dr/change-route this ["process" pid])}
      (str dir "@" pid))))



(def ui-li-process (comp/factory LiProcess {:keyfn :reald.process/pid}))


(defsc LiProject [this {:reald.project/keys [name dir]}]
  {:query [:reald.project/name
           :reald.project/dir]
   :ident :reald.project/dir}
  (dom/li
    (dom/div name)
    (dom/button {:onClick #(dr/change-route this ["project" dir])}
                dir)))


(def ui-li-project (comp/factory LiProject {:keyfn :reald.project/dir}))

(defsc Index [this {:reald.root/keys [projects processes
                                      project-script-result-valid?
                                      project-script project-script-result-str]}]
  {:ident         (fn [] [:component/id ::index])
   :query         [{:reald.root/projects (comp/get-query LiProject)}
                   :reald.root/project-script
                   :reald.root/project-script-result-str
                   :reald.root/project-script-result-valid?
                   {:reald.root/processes (comp/get-query LiProcess)}]
   :route-segment ["index"]
   :will-enter    (fn [app route-params]
                    (dr/route-deferred [:component/id ::index]
                                       #(df/load app [:component/id ::index] Index
                                                 {:post-mutation        `dr/target-ready
                                                  :post-mutation-params {:target [:component/id ::index]}})))}
  (dom/ul
    (dom/li
      "Processes"
      (dom/ul (map ui-li-process processes)))
    (dom/li
      "Projects"
      (dom/ul (map ui-li-project projects)))
    (dom/li
      {:style {:display        "flex"
               :flex-direction "column"}}
      "project-script"
      (dom/textarea {:value    project-script
                     :onChange #(m/set-string!! this :reald.root/project-script :event %)})
      (dom/code {} project-script-result-str)
      (dom/code {} (pr-str [:valid? project-script-result-valid?]))
      (dom/button {:onClick #(comp/transact! this `[(reald.root/project-script ~{:reald.root/project-script project-script})])}
                  "setar"))))

(m/defmutation reald.root/project-script
  [{:reald.root/keys [project-script]}]
  (action [{:keys [state]}]
          (swap! state (fn [st]
                         (-> st))))
  (remote [env]
          (m/returning env Index)))


(defsc LiRunConfig [this {:reald.run-config/keys [ident aliases]}]
  {:query [:reald.run-config/ident
           :reald.run-config/aliases]}
  (dom/div (pr-str ident aliases)))

(def li-run-config (comp/factory LiRunConfig {:keyfn :reald.run-config/ident}))


(defsc ReplIo [this {:reald.repl-io/keys [line origin direction inst]}]
  {:query [:reald.repl-io/line
           :reald.repl-io/origin
           :reald.repl-io/inst
           :reald.repl-io/direction]
   :ident :reald.repl-io/inst}
  (dom/div (pr-str [line origin direction inst])))


(def ui-repl-io (comp/factory ReplIo {:keyfn :reald.repl-io/line}))
(defsc Proc [this {:reald.process/keys [pid alive? repl-io]}]
  {:query         [:reald.process/pid
                   {:reald.process/repl-io (comp/get-query ReplIo)}
                   :reald.process/alive?]
   :ident         :reald.process/pid
   :route-segment ["process" :reald.process/pid]
   :will-enter    (fn [app {:reald.process/keys [pid]}]
                    (let [pid (edn/read-string pid)]
                      (dr/route-deferred [:reald.process/pid pid]
                                         #(df/load app [:reald.process/pid pid] Proc
                                                   {:post-mutation        `dr/target-ready
                                                    :post-mutation-params {:target [:reald.process/pid pid]}}))))}
  (dom/div
    (dom/button
      {:disabled (not alive?)
       :onClick  #(comp/transact! this `[(reald.process/stop ~{:reald.process/pid pid})])}
      "stop")
    (dom/button
      {:onClick #(df/load! this [:reald.process/pid pid] this)}
      "♋")
    (pr-str [pid])
    (dom/div
      (map ui-repl-io repl-io))
    (dom/textarea)))

(defsc Project [this {:ui/keys            [selected-aliases]
                      :reald.project/keys [name dir run-configs aliases active-processes]}]
  {:query         [:reald.project/name
                   {:reald.project/run-configs (comp/get-query LiRunConfig)}
                   {:reald.project/active-processes (comp/get-query LiProcess)}
                   :reald.project/aliases
                   :ui/selected-aliases
                   :reald.project/dir]
   :ident         :reald.project/dir
   :route-segment ["project" :reald.project/dir]
   :will-enter    (fn [app {:reald.project/keys [dir]}]
                    (dr/route-deferred [:reald.project/dir dir]
                                       #(df/load app [:reald.project/dir dir] Project
                                                 {:post-mutation        `dr/target-ready
                                                  :post-mutation-params {:target [:reald.project/dir dir]}})))}
  (let [selected-aliases (set selected-aliases)]
    (dom/div
      (pr-str [name dir aliases])
      (dom/div
        (for [alias aliases]
          (dom/label
            {:key (pr-str alias)}
            (pr-str alias)
            (if (contains? selected-aliases alias)
              (dom/input {:type     "checkbox"
                          :checked  true
                          :onChange (fn [_]
                                      (m/set-value! this :ui/selected-aliases (disj selected-aliases alias)))})
              (dom/input {:type     "checkbox"
                          :checked  false
                          :onChange (fn [_]
                                      (m/set-value! this :ui/selected-aliases (conj selected-aliases alias)))})))))
      (dom/div
        (map ui-li-process active-processes))
      (dom/div
        (dom/button
          {:onClick #(comp/transact! this `[(reald.project/create-repl ~{:reald.project/dir     dir
                                                                         :reald.project/aliases aliases})])}
          (str "Create a REPL" (when-not (empty? selected-aliases)
                                 (str " with aliases: " (string/join ", " selected-aliases))))))
      (dom/div
        (map li-run-config run-configs)))))

(m/defmutation reald.project/create-repl
  [{:reald.project/keys [dir aliases]}]
  (action [{:keys [state]}]
          (swap! state (fn [st]
                         (-> st))))
  (remote [env]
          (m/returning env Project)))

(m/defmutation reald.process/stop
  [{:reald.process/keys [pid]}]
  (action [{:keys [state]}]
          (swap! state (fn [st]
                         (-> st))))
  (remote [env]
          (m/returning env Proc)))

(dr/defrouter TopRouter [this {:keys [current-state]}]
  {:router-targets [Index Project Proc]}
  (case current-state
    :pending (dom/div "Loading...")
    :failed (dom/div "Loading seems to have failed. Try another route.")
    (dom/div "Unknown route")))

(def ui-top-router (comp/factory TopRouter))

(defsc Root [this {:root/keys [router]}]
  {:query         [{:root/router (comp/get-query TopRouter)}]
   :initial-state {:root/router {}}}
  (comp/fragment
    (dom/button {:onClick #(dr/change-route this ["index"])} "Go to index")
    (ui-top-router router)))

(defonce SPA (atom nil))

(defn ^:export main
  [target-id]
  (let [node #?(:cljs (gdom/getElement target-id)
                :default nil)
        app (fa/fulcro-app {:client-did-mount (fn [app]
                                                (dr/change-route app ["index"]))
                            :remotes          {:remote #?(:cljs    (http/fulcro-http-remote {})
                                                          :default nil)}})]
    (fa/mount! app Root node)
    (reset! SPA app)))
