(ns pulse.def
  (:require [pulse.util :as util]))

(defn update [m k f]
  (assoc m k (f (get m k))))

(defn safe-inc [n]
  (inc (or n 0)))

(def ps-lost
  {:receive-init
     (fn []
       nil)
   :receive-apply
     (fn [last-lost event]
       (if (and (= (:cloud event) "heroku.com") (:process_lost event))
         (:total_count event)
         last-lost))
   :receive-emit
     (fn [last-lost]
       last-lost)
   :merge-init
     (fn []
       nil)
   :merge-apply
     (fn [last-lost received]
       (or received last-lost))
   :merge-emit
     (fn [last-lost]
       [last-lost last-lost])})

(def events
  {:receive-init
     (fn []
       [(util/millis) 0])
   :receive-apply
     (fn [[window-start window-count] event]
       [window-start (inc window-count)])
   :receive-emit
     (fn [[window-start window-count]]
       [window-start (util/millis) window-count])
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
       (let [now (util/millis)
             recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now 11000))) windows)
             complete-windows (filter (fn [[window-start _ _]] (< window-start (- now 1000))) recent-windows)
             complete-count (reduce + (map (fn [[_ _ window-count]] window-count) complete-windows))
             complete-rate (/ complete-count 10.0)]
         [recent-windows complete-rate]))})

(def events-by-aorta-host
  {:receive-init
     (fn []
       [(util/millis) {}])
   :receive-apply
     (fn [[window-start window-counts] event]
       [window-start (update window-counts (:aorta_host event) safe-inc)])
   :receive-emit
     (fn [[window-start window-counts]]
       [window-start (util/millis) window-counts])
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
        (let [now (util/millis)
              recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now 11000))) windows)
              complete-windows (filter (fn [[window-start _ _]] (< window-start (- now 1000))) recent-windows)
              complete-counts (apply merge-with + (map (fn [[_ _ window-counts]] window-counts) complete-windows))
              complete-sorted-counts (sort-by (fn [[k kc]] (- kc)) complete-counts)
              complete-high-counts (take 10 complete-sorted-counts)
              complete-rates (map (fn [[k kc]] [k (/ kc 10.0)]) complete-high-counts)]
          [recent-windows complete-rates]))})

(def all
  [["ps_lost" ps-lost]
   ["events" events]
   ["events_by_aorta_host" events-by-aorta-host]])
