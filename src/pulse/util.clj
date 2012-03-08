(ns pulse.util
  (:require [cheshire.custom :as chesh])
  (:import (java.util.concurrent Executors TimeUnit)
           (java.net URI)
           (java.util UUID)))

(defn ^Runnable crashing [f]
  (fn []
    (try
      (f)
      (catch Exception e
        (.printStackTrace e)
        (System/exit 1)))))

(defn spawn [f]
  (let [t (Thread. (crashing f))]
    (.start t)
    t))

(defn spawn-loop [f]
  (spawn
    (fn []
      (loop []
        (f)
        (recur)))))

(defn spawn-tick [t f]
  (let [e (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate e (crashing f) 0 t TimeUnit/MILLISECONDS)))

(defn url-parse [url]
  (let [u (URI. url)]
    {:host (.getHost u)
     :port (.getPort u)
     :auth (.getRawUserInfo u)}))

(defn millis []
  (System/currentTimeMillis))

(defn update [m k f]
  (assoc m k (f (get m k))))

(chesh/add-encoder clojure.lang.PersistentQueue chesh/encode-seq)