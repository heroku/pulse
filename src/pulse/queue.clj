(ns pulse.queue
  (:require [pulse.util :as util])
  (:refer-clojure :exclude (take))
  (:import java.util.concurrent.ArrayBlockingQueue)
  (:import java.util.concurrent.atomic.AtomicLong))

(defn init [size]
  [(ArrayBlockingQueue. size) (AtomicLong. 0) (AtomicLong. 0) (AtomicLong. 0)])

(defn offer [[^ArrayBlockingQueue queue ^AtomicLong pushed _ ^AtomicLong dropped] elem]
  (if (.offer queue elem)
    (.getAndIncrement pushed)
    (.getAndIncrement dropped)))

(defn take [[^ArrayBlockingQueue queue _ ^AtomicLong popped _]]
  (let [elem (.take queue)]
    (.getAndIncrement popped)
    elem))

(defn stats [[^ArrayBlockingQueue queue ^AtomicLong pushed ^AtomicLong popped ^AtomicLong dropped]]
  [(.size queue) (.get pushed) (.get popped) (.get dropped)])

(defn log [msg & args]
  (apply util/log (str "queue " msg) args))

(defn init-watcher [queue queue-name]
  (log "init_watcher name=%s" queue-name)
  (let [start (util/millis)
        popped-prev (atom 0)]
    (util/spawn-tick 1000 (fn []
      (let [elapsed (- (util/millis) start)
            [depth pushed popped dropped] (stats queue)
            rate (- popped @popped-prev)]
        (swap! popped-prev (constantly popped))
        (log "watch name=%s depth=%d pushed=%d popped=%d dropped=%d rate=%d"
          queue-name depth pushed popped dropped rate))))))
