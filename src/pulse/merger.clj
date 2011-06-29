(ns pulse.merger
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [pulse.io :as io])
  (:require [pulse.stat :as stat])
  (:require [pulse.def :as def]))

(defn log [msg & args]
  (apply util/log (str "merger " msg) args))

(defn init-stats [stat-defs]
  (reduce
    (fn [stats-map stat-def]
      (assoc stats-map (:name stat-def) [stat-def (stat/merge-init stat-def)]))
    {}
    stat-defs))

(defn init-emitter [stats-map publish-queue]
  (log "init_emitter")
  (util/spawn-tick 500 (fn []
    (doseq [[stat-name [stat-def stat-state]] stats-map]
      (let [pub (stat/merge-emit stat-def stat-state)]
        (queue/offer publish-queue [stat-name pub]))))))

(defn init-appliers [stats-map apply-queue]
  (log "init_appliers")
  (dotimes [i 2]
     (log "init_applier index=%d" i)
     (util/spawn-loop (fn []
       (let [[stat-name pub] (queue/take apply-queue)
             [stat-def stat-state] (get stats-map stat-name)]
         (stat/merge-apply stat-def stat-state pub))))))

(defn -main []
  (log "init event=start")
  (let [apply-queue (queue/init 1000)
        publish-queue (queue/init 100)
        stats-states (init-stats def/all)]
    (queue/init-watcher apply-queue "apply")
    (queue/init-watcher publish-queue "publish")
    (io/init-publishers publish-queue (conf/redis-url) "stats.merged" 8)
    (init-emitter stats-states publish-queue)
    (init-appliers stats-states apply-queue)
    (io/init-subscriber (conf/redis-url) "stats.received" apply-queue))
  (log "init event=finish"))
