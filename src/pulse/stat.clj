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

(defn receive-apply [{apply-fn :receive-apply
                      stat :name} ^AtomicReference stat-state event]
  (locking stat-state
    (let [current (.get stat-state)
          applied (try (apply-fn current event)
                       (catch Exception e
                         (println "Problem in apply for" stat "receiver.")
                         (throw e)))]
      (.compareAndSet stat-state current applied))))

(defn receive-emit [{init-fn :receive-init emit-fn :receive-emit stat :name}
                    ^AtomicReference stat-state]
  (try (emit-fn (.getAndSet stat-state (init-fn)))
       (catch Exception e
         (println "Problem in emit for" stat "receiver.")
         (throw e))))

(defn merge-init [{init-fn :merge-init}]
  (AtomicReference. (init-fn)))

(defn merge-apply [{apply-fn :merge-apply stat :name}
                   ^AtomicReference stat-state pub]
  (locking stat-state
    (let [current (.get stat-state)
          applied (try (apply-fn current pub)
                       (catch Exception e
                         (println "Problem in apply for" stat "applier.")
                         (throw e)))]
      (.compareAndSet stat-state current applied))))

(defn merge-emit [{emit-fn :merge-emit stat :name} ^AtomicReference stat-state]
  (locking stat-state
    (let [current (.get stat-state)
          [applied pub] (try (emit-fn current)
                             (catch Exception e
                               (println "Problem in emit for" stat "applier.")
                               (throw e)))]
      (.compareAndSet stat-state current applied)
      pub)))
