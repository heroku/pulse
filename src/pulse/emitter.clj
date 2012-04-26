(ns pulse.emitter
  (:require [clj-json.core :as json]
            [clj-redis.client :as redis]
            [clj-http.client :as http]
            [pulse.conf :as conf]
            [pulse.util :as util]
            [pulse.log :as log]))

(defn log [& data]
  (apply log/log :ns "emitter" data))

(defonce stats-buff-a
  (atom '()))

(defn post [metrics-url stats]
  (let [{:keys [host]} (util/url-parse metrics-url)]
    (log :fn "post" :at "start" :host host)
    (http/post metrics-url
      {:body (json/generate-string stats)
       :socket-timeout 10000
       :conn-timeout 10000
       :content-type :json})
    (log :fn "post" :at "finish" :host host)))

(defn init-emitter []
  (log :fn "init-emitter" :at "start")
  (loop []
    (let [stats-size (count @stats-buff-a)
          stats (take-last stats-size @stats-buff-a)]
      (if-not (zero? stats-size)
        (do
          (log :fn "init-emitter" :at "emit" :stats-size stats-size)
          (swap! stats-buff-a #(drop-last stats-size %))
          (doseq [metrics-url (conf/metrics-urls)]
            (util/spawn #(post metrics-url stats)))
          (log :fn "init-emitter" :at "emitted" :stats-size stats-size))
        (log :fn "init-emitter" :at "empty")))
    (util/sleep 500)
    (recur)))
            
(defn init-buffer []
  (log :fn "init-buffer" :at "start")
  (let [rd (redis/init {:url (conf/redis-url)})]
    (redis/subscribe rd ["stats.merged"] (fn [_ stat-json]
      (let [[metric value] (json/parse-string stat-json)
            time (util/epoch)
            period 60
            buff-size (count @stats-buff-a)]
        (if (< buff-size 1000)
          (swap! stats-buff-a conj {"metric" metric "value" value "measure_time" time "measure_period" period})
          (log :fn "init-buffer" :at "drop" :buffer-size buff-size)))))))

(defn -main []
  (log :fn "main" :at "start")
  (util/spawn init-buffer)
  (util/spawn init-emitter)
  (log :fn "main" :at "finish"))
