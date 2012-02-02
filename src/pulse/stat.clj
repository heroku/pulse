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

(defn receive-apply [{apply-fn :receive-apply} ^AtomicReference stat-state event]
  (locking stat-state
    (let [current (.get stat-state)
          applied (apply-fn current event)]
      (.compareAndSet stat-state current applied))))

(defn receive-emit [{init-fn :receive-init emit-fn :receive-emit}
                    ^AtomicReference stat-state]
  (emit-fn (.getAndSet stat-state (init-fn))))

(defn merge-init [{init-fn :merge-init}]
  (AtomicReference. (init-fn)))

(defn merge-apply [{apply-fn :merge-apply} ^AtomicReference stat-state pub]
  (locking stat-state
    (let [current (.get stat-state)
          applied (apply-fn current pub)]
      (.compareAndSet stat-state current applied))))

(defn merge-emit [{emit-fn :merge-emit} ^AtomicReference stat-state]
  (locking stat-state
    (let [current (.get stat-state)
          [applied pub] (emit-fn current)]
      (.compareAndSet stat-state current applied)
      pub)))
