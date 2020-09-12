(ns reald.dev
  (:require [shadow.cljs.devtools.server :as shadow.server]
            [reald.main :as reald]
            [shadow.cljs.devtools.api :as shadow.api]))

(defn -main
  []
  (shadow.server/start!)
  (shadow.api/watch :reald)
  (shadow.api/watch :workspaces)
  (reald/-main))
