(ns reald.user
  (:require [reald.ui :as ui]
            [com.fulcrologic.fulcro.application :as fa]))

(defn after-load
  []
  (fa/force-root-render! @ui/SPA))
