(ns reald.process
  (:require [clojure.core.async :as async])
  (:import (java.io IOException)
           (java.nio.charset StandardCharsets)))
(set! *warn-on-reflection* true)

(defprotocol IProcess
  (pid [this])
  (destroy [this])
  (alive? [this]))

(extend-protocol IProcess
  Process
  (pid [this] (.pid this))
  (destroy [this] (.destroy this))
  (alive? [this] (.isAlive this)))

(defn execute
  [{::keys [command
            timeout
            directory
            buffer-size
            on-stdout
            on-stdin
            on-stderr]
    :or    {timeout     1000
            buffer-size 64}}]
  (let [pb (ProcessBuilder. ^"[Ljava.lang.String;" (into-array command))
        pb (if directory
             (.directory pb directory)
             pb)
        p (.start pb)]
    (when on-stdin
      (let [stdin (.getOutputStream p)]
        (async/thread
          (loop []
            (if-let [input (on-stdin)]
              (let [bs (.getBytes (str input))]
                (.write stdin bs)
                (.flush stdin))
              (async/<!! (async/timeout timeout)))
            (when (alive? p)
              (recur))))))
    (when on-stdout
      (let [stdout (.getInputStream p)
            stdout-buffer (byte-array buffer-size)]
        (async/thread
          (try
            (loop []
              (let [len (.read stdout stdout-buffer 0 buffer-size)]
                (when (pos? len)
                  (on-stdout (String. stdout-buffer 0 len StandardCharsets/UTF_8)))
                (when (< len buffer-size)
                  (async/<!! (async/timeout timeout)))
                (when-not (neg? len)
                  (recur))))
            (catch IOException _)))))
    (when on-stderr
      (async/thread
        (let [stderr (.getErrorStream p)
              stderr-buffer (byte-array buffer-size)]
          (loop []
            (let [len (.read stderr stderr-buffer 0 buffer-size)]
              (when (pos? len)
                (on-stderr (slurp stderr-buffer))
                (when (< len buffer-size)
                  (async/<!! (async/timeout timeout)))
                (recur)))))))
    p))
