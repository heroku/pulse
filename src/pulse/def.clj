(ns pulse.def
  (:refer-clojure :exclude [last])
  (:require [pulse.util :as util]))

(defn update [m k f]
  (assoc m k (f (get m k))))

(defn safe-inc [n]
  (inc (or n 0)))

(defn rate [pred-fn]
  {:receive-init
     (fn []
       [(util/millis) 0])
   :receive-apply
     (fn [[window-start window-count] event]
       [window-start (if (pred-fn event) (inc window-count) window-count)])
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

(defn rate-by-key [pred-fn key-fn]
  {:receive-init
     (fn []
       [(util/millis) {}])
   :receive-apply
     (fn [[window-start window-counts] event]
       [window-start (if (pred-fn event) (update window-counts (str (key-fn event)) safe-inc) window-counts)])
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

(defn last [pred-fn val-fn]
  {:receive-init
     (fn []
       nil)
   :receive-apply
     (fn [last-val event]
       (if (pred-fn event)
         (val-fn event)
         last-val))
   :receive-emit
     (fn [last-val]
       last-val)
   :merge-init
     (fn []
       nil)
   :merge-apply
     (fn [last-val received]
       (or received last-val))
   :merge-emit
     (fn [last-val]
       [last-val last-val])})


(defn heroku? [evt]
  (= (:cloud evt) "heroku.com"))

(def events-per-second
  (rate
    (fn [evt] true)))

(def events-per-second-by-parsed
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (:parsed evt))))

(def events-per-second-by-aorta-host
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (:aorta_host evt))))

(def events-per-second-by-event-type
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (or (:event_type evt) "none"))))

(def events-per-second-by-level
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (or (:level evt) "none"))))

(def events-per-second-by-cloud
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (or (:cloud evt) "none"))))

(def nginx-requests-per-second
  (rate
    (fn [evt] (and heroku? evt) (= (:event_type evt) "nginx_access"))))

(def nginx-requests-per-second-by-domain
  (rate-by-key
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "nginx_access")))
    (fn [evt] (:http_domain evt))))

(def ps-converges-per-second
  (rate
    (fn [evt] (and (heroku? evt) (:service evt) (:transition evt)))))

(def ps-lost-last
  (last
    (fn [evt] (and (heroku? evt) (:process_lost evt)))
    (fn [evt] (:total_count evt))))

(def all
  [["events_per_second" events-per-second]
   ["events_per_second_by_parsed" events-per-second-by-parsed]
   ["events_per_second_by_aorta_host" events-per-second-by-aorta-host]
   ["events_per_second_by_event_type" events-per-second-by-event-type]
   ["events_per_second_by_level" events-per-second-by-level]
   ["events_per_second_by_cloud" events-per-second-by-cloud]
   ["nginx_requests_per_second" nginx-requests-per-second]
   ["nginx_requests_per_second_by_domain" nginx-requests-per-second-by-domain]
   ["ps_converges_per_second" ps-converges-per-second]
   ["ps_lost_last" ps-lost-last]])
