(ns pulse.queue
  (:refer-clojure :exclude (take))
  (:import java.util.concurrent.ArrayBlockingQueue)
  (:import java.util.concurrent.atomic.AtomicLong))

(set! *warn-on-reflection* true)

(defn init [size]
  [(ArrayBlockingQueue. size) (AtomicLong. 0) (AtomicLong. 0)])

(defn offer [[^ArrayBlockingQueue q ^AtomicLong p ^AtomicLong d] e]
  (if (.offer q e)
    (.getAndIncrement p)
    (.getAndIncrement d)))

(defn take [[^ArrayBlockingQueue q]]
  (.take q))

(defn stats [[^ArrayBlockingQueue q ^AtomicLong p ^AtomicLong d]]
  [(.get p) (.get d) (.size q)])
