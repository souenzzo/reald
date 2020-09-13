(ns reald.instance
  (:require [clojure.java.shell :as sh]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import (java.io IOException InputStream)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn classpath
  [path]
  (binding [sh/*sh-dir* path]
    (first (string/split-lines (:out (sh/sh "clojure" "-Spath"))))))

(defn watch!
  [^InputStream rdr out tag]
  (binding [*in* (io/reader rdr)]
    (loop []
      (when-let [val (read-line)]
        (swap! out conj {:tag tag :val val :id (UUID/randomUUID)})
        (recur)))))

(defn alive?
  [{::keys [^Process p]}]
  (.isAlive p))

(defn start
  [{::keys [path]
    :as    this}]
  (let [cp (classpath path)
        cmd ["java" "-cp" cp "clojure.main"]
        pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array cmd))
        p (.start pb)
        pid (.pid p)
        out (atom [])
        stdin (.getOutputStream p)
        stdout (.getInputStream p)
        stderr (.getErrorStream p)]
    (async/thread
      (try
        (watch! stdout out :out)
        (catch IOException _)
        (finally
          (try
            (.close stdout)
            (catch IOException _)))))
    (async/thread
      (try
        (watch! stderr out :err)
        (catch IOException _)
        (finally
          (try
            (.close stderr)
            (catch IOException _)))))
    (assoc this
      ::stdin stdin
      ::out out
      ::pid pid
      ::cmd cmd
      ::pb pb
      ::p p
      ::cp cp)))

(defn stop
  [{::keys [^Process p]
    :as    this}]
  (.destroy p)
  this)
