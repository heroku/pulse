(ns pulse.queue
  (:require [pulse.util :as util]
            [pulse.log :as log])
  (:refer-clojure :exclude (take))
  (:import java.util.concurrent.ArrayBlockingQueue
           java.util.concurrent.atomic.AtomicLong))

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

(defn log [& data]
  (apply log/log :ns "queue" data))

(defn init-watcher [queue queue-name]
  (log :fn "init-watcher" :name queue-name)
  (let [start (util/millis)
        popped-prev (atom 0)]
    (util/spawn-tick 1000 (fn []
      (let [elapsed (- (util/millis) start)
            [depth pushed popped dropped] (stats queue)
            rate (- popped @popped-prev)]
        (swap! popped-prev (constantly popped))
        (log :fn "init-watcher" :name queue-name :depth depth :pushed pushed
             :popped popped :dropped dropped :rate rate))))))
