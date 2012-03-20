(ns pulse.queue
  (:require [pulse.util :as util]
            [pulse.log :as log]
            [pulse.conf :as conf])
  (:refer-clojure :exclude (take))
  (:import java.util.concurrent.ArrayBlockingQueue
           java.util.concurrent.atomic.AtomicLong))

(defn init [size]
  [(ArrayBlockingQueue. size) (AtomicLong. 0) (AtomicLong. 0) (AtomicLong. 0)])

(defn offer [[^ArrayBlockingQueue queue ^AtomicLong pushed _
              ^AtomicLong dropped] elem]
  (if (.offer queue elem)
    (.getAndIncrement pushed)
    (.getAndIncrement dropped)))

(defn take [[^ArrayBlockingQueue queue _ ^AtomicLong popped _]]
  (let [elem (.take queue)]
    (.getAndIncrement popped)
    elem))

(defn stats [[^ArrayBlockingQueue queue ^AtomicLong pushed
              ^AtomicLong popped ^AtomicLong dropped]]
  [(.size queue) (.get pushed) (.get popped) (.get dropped)])

(defn log [& data]
  (apply log/log :ns "queue" data))

(defn watcher [queue queue-name start pushed-prev popped-prev dropped-prev]
  (let [elapsed (- (util/millis) start)
        [depth pushed popped dropped] (stats queue)
        push-rate (- pushed  @pushed-prev)
        pop-rate  (- popped  @popped-prev)
        drop-rate (- dropped @dropped-prev)]
    (swap! pushed-prev  (constantly pushed))
    (swap! popped-prev  (constantly popped))
    (swap! dropped-prev (constantly dropped))
    (log :fn "init-watcher" :name queue-name :depth depth
         :push-rate push-rate :pushed pushed
         :pop-rate pop-rate :popped popped
         :drop-rate drop-rate :dropped dropped)))

(defn init-watcher [queue queue-name]
  (log :fn "init-watcher" :name queue-name)
  (let [start (util/millis)
        pushed-prev  (atom 0)
        popped-prev  (atom 0)
        dropped-prev (atom 0)]
    (util/spawn-tick (conf/watcher-interval)
                     (partial #'watcher queue queue-name start
                              pushed-prev popped-prev dropped-prev))))
