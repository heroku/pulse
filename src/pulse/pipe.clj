(ns pulse.pipe
  (:require [pulse.util :as util])
  (:import (clojure.lang LineNumberingPushbackReader))
  (:import (java.io InputStreamReader BufferedReader PrintWriter))
  (:import (java.net Socket)))

(set! *warn-on-reflection* true)

(defn stdin-lines [handler]
  (loop []
    (when-let [line (.readLine ^LineNumberingPushbackReader *in*)]
      (handler line)
      (recur))))

(defn bleed-lines [aorta-url handler]
  (let [{:keys [^String host ^Integer port auth]} (util/url-parse aorta-url)]
    (loop []
      (util/log "pipe connect host=%s" host)
      (with-open [socket (Socket. host port)
                  in     (-> (.getInputStream socket) (InputStreamReader.) (BufferedReader.))
                  out    (-> (.getOutputStream socket) (PrintWriter.))]
        (.println out auth)
        (.flush out)
        (loop []
          (if-let [line (.readLine in)]
            (do
              (handler line)
              (recur))
            (do
              (util/log "pipe disconnect host=%s" host)
              (Thread/sleep 1000))))))))

(defn shell-lines [cmd-list handler]
  (let [rt (Runtime/getRuntime)
       proc (.exec rt ^"[Ljava.lang.String;" (into-array cmd-list))]
    (with-open [in (-> (.getInputStream proc) (InputStreamReader.) (BufferedReader.))]
      (loop []
        (when-let [line (.readLine in)]
          (handler line)
          (recur))))))
