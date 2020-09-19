(ns reald.instance
  (:require [clojure.java.shell :as sh]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.edn :as edn])
  (:import (java.io IOException InputStream PushbackReader)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn classpath
  [path]
  (binding [sh/*sh-dir* path]
    (first (string/split-lines (:out (sh/sh "clojure" "-A:test" "-Spath"))))))

(defn watch!
  [^InputStream rdr in tag]
  (binding [*in* (io/reader rdr)]
    (loop []
      (when-let [val (read-line)]
        (async/put! in {:tag tag :val val})
        (recur)))))

(defn alive?
  [{::keys [^Process p]}]
  (.isAlive p))


(defn read-all-strings
  [{:keys [val]}]
  (when (string? val)
    (with-open [rdr (PushbackReader. (io/reader (.getBytes ^String val)))]
      (vec (take-while #(not (= rdr %))
                       (repeatedly #(try
                                      (edn/read {:eof rdr} rdr)
                                      (catch Throwable ex
                                        (doto ex
                                          (.setStackTrace (into-array java.lang.StackTraceElement
                                                                      [])))))))))))



(defn start
  [{::keys [path]
    :as    this}]
  (let [cp (classpath path)
        cmd ["java" "-cp" cp "clojure.main"]
        pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array cmd))
        p (.start pb)
        pid (.pid p)
        io (atom [])
        in (async/chan 1e2
                       (comp
                         (map #(doto % (prn pid)))
                         (map #(assoc % :id (UUID/randomUUID)))
                         (map #(assoc % :values (read-all-strings %)))
                         (map #(do
                                 (swap! io conj %)
                                 %))))
        pubsub (async/pub in :tag)
        stdin (io/writer (.getOutputStream p))
        stdout (.getInputStream p)
        stderr (.getErrorStream p)
        appends (async/chan)]
    (async/sub pubsub :in appends)
    (async/thread
      (loop []
        (when-let [{:keys [val] } (async/<!! appends)]
          (.append stdin (str val))
          (.flush stdin)
          (recur))))
    (async/thread
      (try
        (watch! stdout in :out)
        (catch IOException _)
        (finally
          (try
            (.close stdout)
            (catch IOException _)))))
    (async/thread
      (try
        (watch! stderr in :err)
        (catch IOException _)
        (finally
          (try
            (.close stderr)
            (catch IOException _)))))
    (assoc this
      ::io io
      ::pubsub pubsub
      ::in in
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
