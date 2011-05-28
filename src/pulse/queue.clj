(ns pulse.queue
  (:refer-clojure :exclude (take))
  (:import java.util.concurrent.ArrayBlockingQueue)
  (:import java.util.concurrent.atomic.AtomicLong))

(set! *warn-on-reflection* true)

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
