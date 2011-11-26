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
  (util/spawn-tick 1000 (fn []
    (doseq [[stat-def stat-state] stats]
      (let [pub (stat/receive-emit stat-def stat-state)]
        (queue/offer publish-queue [(:name stat-def) pub]))))))

(defn parse [aorta-host line]
  (if-let [event (parse/parse-line line)]
    (assoc event :line line :aorta_host aorta-host)
    {:line line :aorta_host aorta-host :unparsed true}))

(defn init-appliers [stats apply-queue n]
  (log :fn "init-appliers" :at "start")
  (dotimes [i n]
     (log :fn "init-appliers" :at "spawn" :index i)
     (util/spawn-loop (fn []
       (let [[aorta-host line] (queue/take apply-queue)
             event (parse aorta-host line)]
         (doseq [[stat-def stat-state] stats]
           (stat/receive-apply stat-def stat-state event)))))))

(defn -main []
  (log :fn "main" :at "start")
  (let [apply-queue (queue/init 10000)
        publish-queue (queue/init 1000)
        stats-states (init-stats def/all)]
    (queue/init-watcher apply-queue "apply")
    (queue/init-watcher publish-queue "publish")
    (io/init-publishers publish-queue (conf/redis-url) "stats.received" pr-str (conf/publish-threads))
    (init-emitter stats-states publish-queue)
    (init-appliers stats-states apply-queue (conf/apply-threads))
    (io/init-bleeders (conf/aorta-urls) apply-queue)
  (log :fn "main" :at "finish")))
