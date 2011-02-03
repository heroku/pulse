(ns pulse.queue
  (:refer-clojure :exclude (take))
  (:import java.util.concurrent.ArrayBlockingQueue))

(defn init [size]
  [(ArrayBlockingQueue. size) (atom 0) (atom 0)])

(defn offer [[^ArrayBlockingQueue q p d] e]
  (if (.offer q e)
    (swap! p inc)
    (swap! d inc)))

(defn take [[^ArrayBlockingQueue q _ _]]
  (.take q))

(defn stats [[^ArrayBlockingQueue q p d]]
  [@p @d (.size q)])
