(ns pulse.stat
  (:import (java.util.concurrent.atomic AtomicReference)))

; :receive-init  (fn []) -> receive-accum
; :receive-apply (fn [receive-accum event]) -> receive-accum
; :receive-emit  (fn [receive-accum]) -> received-publish
; :merge-init    (fn []) -> merge-accum
; :merge-apply   (fn [merge-accum received]) -> merge-accum
; :merge-emit    (fn [merge-accum]) -> merge-accum, merged-publish

(defn receive-init [{init-fn :receive-init}]
  (AtomicReference. (init-fn)))

(defn receive-apply [{apply-fn :receive-apply} stat-state event]
  (loop []
    (let [current (.get stat-state)
          proposed (apply-fn current event)]
      (when-not (.compareAndSet stat-state current proposed)
        (recur)))))

(defn receive-emit [{init-fn :receive-init emit-fn :receive-emit} stat-state]
  (emit-fn (.getAndSet stat-state (init-fn))))

(defn merge-init [{init-fn :merge-init}]
  (AtomicReference. (init-fn)))

(defn merge-apply [{apply-fn :merge-apply} stat-state pub]
  (loop []
    (let [current (.get stat-state)
          proposed (apply-fn current pub)]
      (when-not (.compareAndSet stat-state current proposed)
        (recur)))))

(defn merge-emit [{emit-fn :merge-emit} stat-state]
  (loop []
    (let [current (.get stat-state)
          [proposed pub] (emit-fn current)]
      (if (.compareAndSet stat-state current proposed)
        pub
        (recur)))))
