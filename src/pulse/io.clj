(ns pulse.io
  (:require [pulse.util :as util]
            [pulse.conf :as conf]
            [pulse.log :as log]
            [pulse.queue :as queue]
            [clj-redis.client :as redis])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io InputStreamReader BufferedReader PrintWriter)
           (java.net Socket SocketException ConnectException)))

(defn log [& data]
  (apply log/log :ns "io" data))

(defn shard-for [stat-name]
  (mod (hash stat-name) (conf/merger-count)))

(defn shard-channel [[stat-name]]
  (str "stats.received." (shard-for stat-name)))

(defn init-publishers [publish-queue redis-url chan ser workers]
  (let [redis (redis/init {:url redis-url})]
    (log :fn "init-publishers" :at "start" :chan chan)
    (dotimes [i workers]
      (log :fn "init-publishers" :at "spawn" :chan chan :index i)
      (util/spawn-loop (fn []
        (let [data (queue/take publish-queue)
              chan (if (ifn? chan)
                     (chan data)
                     chan)
              data-str (try
                         (ser data)
                         (catch Exception e
                           (log :fn "init-publishers" :at "exception" :data (pr-str data))
                           (throw e)))]
          (redis/publish redis chan data-str)))))))

(defn init-subscriber [redis-url chan deser apply-queue]
  (let [redis (redis/init {:url redis-url})]
    (log :fn "init-subscriber" :at "start" :chan chan)
    (redis/subscribe redis [chan]
      (fn [_ data-json]
        (queue/offer apply-queue (deser data-json))))))
