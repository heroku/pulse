(ns pulse.receiver
  (:require [pulse.conf :as conf]
            [pulse.log :as log]
            [pulse.util :as util]
            [pulse.queue :as queue]
            [pulse.io :as io]
            [pulse.parse :as parse]
            [pulse.stat :as stat]
            [pulse.def :as def]))

(defn log [& data]
  (apply log/log :ns "receiver" data))

(defn init-stats [stat-defs]
  (map
    (fn [stat-def]
      [stat-def (stat/receive-init stat-def)])
    stat-defs))

(defn init-emitter [stats publish-queue]
  (log :fn "init-emitter" :at "start")
  (util/spawn-tick 1250 (fn []
    (doseq [[stat-def stat-state] stats]
      (let [pub (stat/receive-emit stat-def stat-state)]
        (queue/offer publish-queue [(:name stat-def) pub]))))))

(defn init-applier [stats apply-queue]
  (log :fn "init-applier" :at "start")
  (util/spawn-loop (fn []
    (let [line (queue/take apply-queue)
          evt (or (parse/parse-line line) {:line line :unparsed true})]
      (doseq [[stat-def stat-state] stats]
        (stat/receive-apply stat-def stat-state evt))))))

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
    (io/init-bleeders (conf/aorta-urls) apply-queue)
  (log :fn "main" :at "finish")))
