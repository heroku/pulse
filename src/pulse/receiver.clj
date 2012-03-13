(ns pulse.receiver
  (:require [pulse.conf :as conf]
            [pulse.log :as log]
            [pulse.util :as util]
            [pulse.queue :as queue]
            [pulse.io :as io]
            [pulse.parse :as parse]
            [pulse.stat :as stat]
            [pulse.def :as def]
            [drain.adapter :as drain]))

(defn log [& data]
  (apply log/log :ns "receiver" data))

(defn init-stats [stat-defs]
  (map
   (fn [stat-def]
     [stat-def (stat/receive-init stat-def)])
   stat-defs))

(defn emitter [stats publish-queue]
  (doseq [[stat-def stat-state] stats]
    (let [pub (stat/receive-emit stat-def stat-state)]
      (queue/offer publish-queue [(:name stat-def) pub]))))

(defn init-emitter [stats publish-queue]
  (log :fn "init-emitter" :at "start")
  (util/spawn-tick 1250 (partial #'emitter stats publish-queue)))

(defn applier [stats apply-queue]
  (let [evt (queue/take apply-queue)]
    (doseq [[stat-def stat-state] stats]
      (stat/receive-apply stat-def stat-state evt))))

(defn init-applier [stats apply-queue]
  (log :fn "init-applier" :at "start")
  (util/spawn-loop (partial #'applier stats apply-queue)))

(defn init-drain [port apply-queue]
  (log :fn "init-drain" :at "start")
  (drain/server port (partial queue/offer apply-queue)))

(defn -main []
  (log :fn "main" :at "start")
  (let [apply-queue (queue/init 10000)
        publish-queue (queue/init 1000)
        stats-states (init-stats def/all)]
    (queue/init-watcher apply-queue "apply")
    (queue/init-watcher publish-queue "publish")
    (io/init-publishers publish-queue (conf/redis-url) io/shard-channel
                        pr-str (conf/publish-threads))
    (init-emitter stats-states publish-queue)
    (init-applier stats-states apply-queue)
    (init-drain (conf/port) apply-queue)
    (log :fn "main" :at "finish")))
