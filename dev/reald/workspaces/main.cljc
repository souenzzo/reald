(ns reald.workspaces.main
  (:require [nubank.workspaces.core :as ws]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.mutations :as m]
            #?@(:cljs    [[com.fulcrologic.fulcro.dom :as dom]
                          [com.wsscode.pathom.viz.index-explorer :as iex]
                          [nubank.workspaces.card-types.fulcro3 :as ctf]]
                :default [[com.fulcrologic.fulcro.dom-server :as dom]
                          [clojure.core :as ctf]])))

(defsc FulcroDemo
  [this {:keys [counter]}]
  {:initial-state (fn [_] {:counter 0})
   :ident         (fn [] [::id "singleton"])
   :query         [:counter]}
  (dom/div
    (str "Fulcro counter demo [" counter "]")
    (dom/button {:onClick #(m/set-value! this :counter (inc counter))} "+")))

#?(:cljs
   (ws/defcard fulcro-demo-card
     (ctf/fulcro-card
       {::ctf/root FulcroDemo})))

(defn after-load
  []
  #?(:cljs (ws/after-load)))

#?(:cljs (defonce init (ws/mount)))
