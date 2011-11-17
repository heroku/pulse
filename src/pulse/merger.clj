(ns pulse.merger
  (:require [pulse.conf :as conf]
            [pulse.util :as util]
            [pulse.log :as log]
            [pulse.queue :as queue]
            [pulse.io :as io]
            [pulse.stat :as stat]
            [pulse.def :as def]
            [clj-json.core :as json]))

(defn log [& data]
  (apply log/log :ns "merger" data))

(defn init-stats [stat-defs]
  (reduce
    (fn [stats-map stat-def]
      (assoc stats-map (:name stat-def) [stat-def (stat/merge-init stat-def)]))
    {}
    stat-defs))

(defn init-emitter [stats-map publish-queue]
  (log :fn "init-emitter" :at "start")
  (util/spawn-tick 1000 (fn []
    (log :fn "init-emitter" :at "tick")
    (doseq [[stat-name [stat-def stat-state]] stats-map]
      (log :fn "init-emitter" :at "emit" :name stat-name)
      (let [pub (stat/merge-emit stat-def stat-state)]
        (queue/offer publish-queue [stat-name pub]))))))

(defn init-appliers [stats-map apply-queue]
  (log :fn "init-appliers" :at "start")
  (dotimes [i 2]
     (log :fn "init-appliers" :at "spawn" :index i)
     (util/spawn-loop (fn []
       (let [[stat-name pub] (queue/take apply-queue)
             [stat-def stat-state] (get stats-map stat-name)]
         (stat/merge-apply stat-def stat-state pub))))))

(defn -main []
  (log :fn "main" :at "start")
  (let [apply-queue (queue/init 2000)
        publish-queue (queue/init 100)
        stats-states (init-stats def/all)]
    (queue/init-watcher apply-queue "apply")
    (queue/init-watcher publish-queue "publish")
    (io/init-publishers publish-queue (conf/redis-url) "stats.merged" json/generate-string 4)
    (init-emitter stats-states publish-queue)
    (init-appliers stats-states apply-queue)
    (io/init-subscriber (conf/redis-url) "stats.received" read-string apply-queue))
  (log :fn "main" :at "finish"))
