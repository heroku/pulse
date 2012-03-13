(ns pulse.merger
  (:require [pulse.conf :as conf]
            [pulse.util :as util]
            [pulse.log :as log]
            [pulse.queue :as queue]
            [pulse.io :as io]
            [pulse.stat :as stat]
            [pulse.def :as def]
            [cheshire.core :as json]))

(defn log [& data]
  (apply log/log :ns "merger" data))

(defn init-stats [stat-defs shard]
  (reduce
   (fn [stats-map stat-def]
     (if (= shard (str (io/shard-for (:name stat-def))))
       (assoc stats-map (:name stat-def) [stat-def (stat/merge-init stat-def)])
       stats-map))
   {}
   stat-defs))

(defn emitter [stats-map publish-queue]
  (log :fn "init-emitter" :at "tick")
  (doseq [[stat-name [stat-def stat-state]] stats-map]
    (let [pub (stat/merge-emit stat-def stat-state)]
      (log :fn "init-emitter" :at "emit" :name stat-name :value (and (number? pub) pub))
      (queue/offer publish-queue [stat-name pub]))))

(defn init-emitter [stats-map publish-queue]
  (log :fn "init-emitter" :at "start")
  (util/spawn-tick 1000 (partial #'emitter stats-map publish-queue)))

(defn applier [stats-map apply-queue]
  (let [[stat-name pub] (queue/take apply-queue)
        [stat-def stat-state] (get stats-map stat-name)]
    (stat/merge-apply stat-def stat-state pub)))

(defn init-applier [stats-map apply-queue]
  (log :fn "init-applier" :at "start")
  (util/spawn-loop (partial #'applier stats-map apply-queue)))

(defn -main [shard]
  (log :fn "main" :at "start")
  (let [apply-queue (queue/init 2000)
        publish-queue (queue/init 100)
        stats-map (init-stats def/all shard)]
    (queue/init-watcher apply-queue "apply")
    (queue/init-watcher publish-queue "publish")
    (io/init-publishers publish-queue (conf/redis-url) "stats.merged"
                        json/generate-string (conf/publish-threads))
    (init-emitter stats-map publish-queue)
    (init-applier stats-map apply-queue)
    (io/init-subscriber (conf/redis-url) (str "stats.received." shard)
                        read-string apply-queue))
  (log :fn "main" :at "finish"))
