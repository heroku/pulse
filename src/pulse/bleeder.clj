(ns pulse.bleeder
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [pulse.io :as io])
  (:require [pulse.parse :as parse])
  (:require [pulse.stat :as stat])
  (:require [pulse.def :as def]))

(defn log [msg & args]
  (apply util/log (str "bleeder " msg) args))

(defn init-writer [write-queue]
  (log "init_writer")
  (util/spawn-loop (fn []
    (let [[_ line] (queue/take write-queue)]
      (locking *out*
        (prn line))))))

(defn -main []
  (log "init event=start")
  (let [write-queue (queue/init 10000)]
    (queue/init-watcher write-queue "write")
    (io/init-bleeders (conf/aorta-urls) write-queue)
    (init-writer write-queue))
  (log "init event=finish"))
