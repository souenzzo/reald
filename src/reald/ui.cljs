(ns reald.ui
  (:require [com.fulcrologic.fulcro.application :as fa]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [com.fulcrologic.fulcro.dom :as dom]
            [goog.object :as gobj]
            [com.fulcrologic.fulcro.networking.http-remote :as http]
            [edn-query-language.core :as eql]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.mutations :as fm]
            [clojure.string :as string]
            [goog.events :as events]
            [goog.history.EventType :as et])
  (:import (goog.history Html5History Event)))

;; utilities


(defn form
  [{::keys [params
            on-submit
            submit-label]}]
  (let [nodes (->> params eql/query->ast :children)
        attributes (map :dispatch-key nodes)
        disabled? (not on-submit)]
    (dom/form
      {:onSubmit (fn [e]
                   (.preventDefault e)
                   (when-not disabled?
                     (let [form (-> e .-target)
                           params (into {}
                                        (map (juxt identity #(->> % str (gobj/get form) .-value)))
                                        attributes)]
                       (on-submit params))))}
      (for [{:keys [dispatch-key params]} nodes
            :let [attrs {:name         (str dispatch-key)
                         :key          (str dispatch-key)
                         :defaultValue (::default-value params "")
                         :placeholder  (::placeholder params (name dispatch-key))}]]
        (if (::multiline params)
          (dom/textarea
            (assoc attrs :rows 8))
          (dom/input
            (assoc attrs :type (::type params "text")))))
      (dom/button
        {:disabled disabled?}
        submit-label))))

;;


(defsc LiValue [this props]
  {:query [:reald.value/id
           :reald.value/tag
           :reald.value/val]
   :ident :reald.value/id}
  (dom/div "LiValue"))

(def ui-li-value (comp/factory LiValue {:keyfn :reald.value/id}))

(defsc Terminal [this props]
  {:query         [:reald.terminal/id
                   :reald.instance/pid
                   :reald.project/path
                   {:reald.terminal/values (comp/get-query LiValue)}]
   :ident         :reald.terminal/id
   :route-segment ["terminal" :reald.terminal/id]}
  (dom/div "Terminal"))

(defsc LiTerminal [this props]
  {:query [:reald.terminal/id
           :reald.project/path
           :reald.instance/pid]
   :ident :reald.terminal/id}
  (dom/div "LiTerminal"))


(def ui-li-terminal (comp/factory LiTerminal {:keyfn :reald.terminal/pid}))


(defsc Instance [this props]
  {:query         [:reald.instance/pid
                   :reald.project/path
                   {:reald.instance/values (comp/get-query LiValue)}
                   {:reald.instance/active-terminals (comp/get-query LiTerminal)}]
   :ident         :reald.instance/pid
   :route-segment ["instance" :reald.instance/pid]})

(defsc LiInstance [this props]
  {:query [:reald.instance/pid
           :reald.project/path]
   :ident :reald.instance/pid}
  (dom/div "LiInstance"))


(def ui-li-instance (comp/factory LiInstance {:keyfn :reald.instance/pid}))


(defsc Project [this {:reald.project/keys [path active-instances]}]
  {:query         [:reald.project/path
                   #_{:reald.project/active-terminals (comp/get-query LiTerminal)}
                   {:reald.project/active-instances (comp/get-query LiInstance)}]
   :ident         :reald.project/path
   :will-enter    (fn [app {:reald.project/keys [path]}]
                    (let [ref [:reald.project/path path]]
                      (dr/route-deferred ref
                                         #(df/load! app ref Project
                                                    {:post-mutation        `dr/target-ready
                                                     :post-mutation-params {:target ref}}))))
   :route-segment ["project" :reald.project/path]}
  (dom/main
    (dom/h1 "Project")
    (dom/code path)
    (dom/h2 "Active instances: ")
    (dom/ul
      (map ui-li-instance active-instances))
    (form
      {::on-submit    #(comp/transact! this `[(reald.rt/launch-instance ~{:reald.project/path path})])
       ::submit-label "launch a new instance"})))


(fm/defmutation reald.rt/launch-instance
  [_]
  (action [{:keys [state]}]
          (swap! state (fn [st]
                         (-> st))))
  (remote [env]
          (fm/returning env Project)))


(defsc LiProject [this {:reald.project/keys [path]}]
  {:query [:reald.project/path]
   :ident :reald.project/path}
  (dom/li
    (dom/a
      {:href (str "#/project/" (js/encodeURIComponent path))}
      (dom/code path))
    (form
      {::on-submit    #(comp/transact! this `[(reald.rt/remove-project ~{:reald.project/path path})])
       ::submit-label "♲"})))

(def ui-li-project (comp/factory LiProject {:keyfn :reald.project/path}))

(defsc Home [this {:reald.rt/keys [active-projects]}]
  {:query         [{:reald.rt/active-projects (comp/get-query LiProject)}]
   :ident         (fn []
                    [:component/id ::home])
   :will-enter    (fn [app _]
                    (dr/route-deferred [:component/id ::home]
                                       #(df/load! app [:component/id ::home] Home
                                                  {:post-mutation        `dr/target-ready
                                                   :post-mutation-params {:target [:component/id ::home]}})))
   :route-segment ["home"]}
  (dom/main
    (dom/p "Active projects")
    (dom/ul
      (map ui-li-project active-projects))
    (form
      {::params       `[:reald.project/path]
       ::on-submit    #(comp/transact! this `[(reald.rt/add-new-project ~%)])
       ::submit-label "add project"})))

(fm/defmutation reald.rt/remove-project
  [_]
  (action [{:keys [state]}]
          (swap! state (fn [st]
                         (-> st))))
  (remote [env]
          (fm/returning env Home)))


(fm/defmutation reald.rt/add-new-project
  [_]
  (action [{:keys [state]}]
          (swap! state (fn [st]
                         (-> st))))
  (remote [env]
          (fm/returning env Home)))


(dr/defrouter Router [this props]
  {:router-targets [Home Project Instance Terminal]})

(def ui-router (comp/factory Router))

(defsc Root [this {:>/keys [router]}]
  {:query         [{:>/router (comp/get-query Router)}]
   :initial-state (fn [_]
                    {:>/router (comp/get-initial-state Router _)})}
  (comp/fragment
    (dom/header
      (dom/nav
        (dom/a {:href "#/"}
               "home")))
    (ui-router router)
    (dom/footer)))

(defonce SPA (atom nil))


(defn client-did-mount
  "Must be used as :client-did-mount parameter of app creation, or called just after you mount the app."
  [app]
  (let [{::keys [history]} (comp/shared app)]
    (doto history
      (events/listen et/NAVIGATE (fn [^Event e]
                                   (let [token (.-token e)
                                         path (mapv
                                                js/decodeURIComponent
                                                (rest (string/split (first (string/split token #"\?"))
                                                                    #"/")))
                                         path (cond
                                                (empty? path) ["home"]
                                                :else path)]
                                     (prn [token path])
                                     (dr/change-route! app path))))
      (.setEnabled true))))

(defn ^:export main
  [target]
  (-> (fa/fulcro-app {:client-did-mount client-did-mount
                      :shared           {::history (Html5History.)}
                      :remotes          {:remote (http/fulcro-http-remote {})}})
      (->> (reset! SPA))
      (fa/mount! Root target)))
