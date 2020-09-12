(ns reald.ui
  (:require [com.fulcrologic.fulcro.application :as fa]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [com.fulcrologic.fulcro.dom :as dom]
            [com.fulcrologic.fulcro.networking.http-remote :as http]))

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


(defsc Project [this props]
  {:query         [:reald.project/path
                   {:reald.project/active-terminals (comp/get-query LiTerminal)}
                   {:reald.project/active-instances (comp/get-query LiInstance)}]
   :ident         :reald.project/path
   :route-segment ["project" :reald.project/path]}
  (dom/div "Project"))

(defsc LiProject [this props]
  {:query [:reald.project/path]
   :ident :reald.project/path}
  (dom/div "LiProject"))

(def ui-li-project (comp/factory LiProject {:keyfn :reald.project/path}))

(defsc Home [this props]
  {:query         [{:reald.rt/active-projects (comp/get-query LiProject)}]
   :ident         (fn []
                    [:component/id ::index])
   :route-segment ["home"]}
  (dom/div "Home"))

(dr/defrouter Router [this props]
  {:router-targets [Home Project Instance Terminal]})

(def ui-router (comp/factory Router))

(defsc Root [this {:>/keys [router]}]
  {:query         [{:>/router (comp/get-query Router)}]
   :initial-state (fn [_]
                    {:>/router {}})}
  (ui-router router))

(defonce SPA (atom nil))

(defn ^:export main
  [target]
  (-> (fa/fulcro-app {:client-did-mount (fn [app]
                                          (dr/change-route app ["home"]))
                      :remotes          {:remote (http/fulcro-http-remote {})}})
      (->> (reset! SPA))
      (fa/mount! Root target)))
