(ns pulse.debugger
  (:require [pulse.conf :as conf]
            [pulse.log :as log]
            [pulse.util :as util]
            [pulse.queue :as queue]
            [pulse.io :as io]))

(defn log [& data]
  (apply log/log :ns "debugger" data))


(defn init-debugger [debug-queue]
  (log :fn "init-debugger" :at "start")
  (util/spawn-loop (fn []
    (let [line (queue/take debug-queue)]
      (locking *out*
        (prn line))))))

(defn -main []
  (log :fn "main" :at "start")
  (let [debug-queue (queue/init 10000)]
    (queue/init-watcher debug-queue "debug")
    (init-debugger debug-queue)
    (io/init-bleeders (conf/aorta-urls) debug-queue)
  (log :fn "main" :at "finish")))
