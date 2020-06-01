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
            [clojure.string :as string]))

(defsc LiProject [this {:reald.project/keys [name dir]}]
  {:query [:reald.project/name
           :reald.project/dir]
   :ident :reald.project/dir}
  (dom/li
    (dom/div name)
    (dom/button {:onClick #(dr/change-route this ["project" dir])}
                dir)))

(def ui-li-project (comp/factory LiProject {:keyfn :reald.project/dir}))

(defsc Index [this {:reald.root/keys [projects]}]
  {:ident         (fn [] [:component/id ::index])
   :query         [{:reald.root/projects (comp/get-query LiProject)}]
   :route-segment ["index"]
   :will-enter    (fn [app route-params]
                    (dr/route-deferred [:component/id ::index]
                                       #(df/load app [:component/id ::index] Index
                                                 {:post-mutation        `dr/target-ready
                                                  :post-mutation-params {:target [:component/id ::index]}})))}
  (dom/ul
    (map ui-li-project projects)))

(defsc LiRunConfig [this {:reald.run-config/keys [ident aliases]}]
  {:query [:reald.run-config/ident
           :reald.run-config/aliases]}
  (dom/div (pr-str ident aliases)))

(def li-run-config (comp/factory LiRunConfig {:keyfn :reald.run-config/ident}))

(defsc LiProcess [this {:reald.process/keys [pid]}]
  {:query [:reald.process/pid]}
  (dom/li
    (pr-str [pid])))

(def ui-li-process (comp/factory LiProcess {:keyfn :reald.process/pid}))

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

(dr/defrouter TopRouter [this {:keys [current-state]}]
  {:router-targets [Index Project]}
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
