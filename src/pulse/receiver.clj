(ns pulse.receiver
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [pulse.io :as io])
  (:require [pulse.parse :as parse])
  (:require [pulse.stat :as stat])
  (:require [pulse.def :as def]))

(set! *warn-on-reflection* true)

(defn log [msg & args]
  (apply util/log (str "receiver " msg) args))

(defn init-stats [stat-defs]
  (map
    (fn [stat-def]
      [stat-def (stat/receive-init stat-def)])
    stat-defs))

(defn init-emitter [stats publish-queue]
  (log "init-emitter")
  (util/spawn-tick 100 (fn []
    (doseq [[stat-def stat-state] stats]
      (let [pub (stat/receive-emit stat-def stat-state)]
        (queue/offer publish-queue [(:name stat-def) pub]))))))

(defn parse [aorta-host line]
  (if-let [event (parse/parse-line line)]
    (assoc event :line line :aorta_host aorta-host :parsed true)
    {:line line :aorta_host aorta-host :parsed false}))

(defn init-appliers [stats apply-queue]
  (log "init_appliers")
  (dotimes [i 2]
     (log "init_applier index=%d" i)
     (util/spawn-loop (fn []
       (let [[aorta-host line] (queue/take apply-queue)
             event (parse aorta-host line)]
         (doseq [[stat-def stat-state] stats]
           (stat/receive-apply stat-def stat-state event)))))))

(defn -main []
  (log "init event=start")
  (let [apply-queue (queue/init 10000)
        publish-queue (queue/init 1000)
        stats-states (init-stats def/all)]
    (queue/init-watcher apply-queue "apply")
    (queue/init-watcher publish-queue "publish")
    (io/init-publishers publish-queue conf/redis-url "stats.received" 8)
    (init-emitter stats-states publish-queue)
    (init-appliers stats-states apply-queue)
    (io/init-bleeders conf/aorta-urls apply-queue)
  (log "init event=finish")))
