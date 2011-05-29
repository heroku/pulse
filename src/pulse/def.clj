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


(defmacro defstat [stat-name stat-body]
  (let [stat-name-str (name stat-name)]
    `(def ~stat-name (merge ~stat-body {:name (name ~stat-name-str)}))))

(defn heroku? [evt]
  (= (:cloud evt) "heroku.com"))

(defstat events-per-second
  (rate
    (fn [evt] true)))

(defstat events-per-second-by-parsed
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (:parsed evt))))

(defstat events-per-second-by-aorta-host
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (:aorta_host evt))))

(defstat events-per-second-by-event-type
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (or (:event_type evt) "none"))))

(defstat events-per-second-by-level
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (or (:level evt) "none"))))

(defstat events-per-second-by-cloud
  (rate-by-key
    (fn [evt] true)
    (fn [evt] (or (:cloud evt) "none"))))

(defstat nginx-requests-per-second
  (rate
    (fn [evt] (and heroku? evt) (= (:event_type evt) "nginx_access"))))

(defstat nginx-requests-per-second-by-domain
  (rate-by-key
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "nginx_access")))
    (fn [evt] (:http_domain evt))))

(defstat amqp-publishes-per-second
  (rate
    (fn [evt] (and (heroku? evt) (:amqp_publish evt)))))

(defstat amqp-receives-per-second
  (rate
    (fn [evt] (and (heroku? evt) (:amqp_action evt) (= (:action evt) "received")))))

(defstat amqp-publishes-per-second-by-exchange
  (rate-by-key
    (fn [evt] (and (heroku? evt) (:amqp_publish evt)))
    (fn [evt] (:exchange evt))))

(defstat amqp-receives-per-second-by-exchange
  (rate-by-key
    (fn [evt] (and (heroku? evt) (:amqp_action evt) (= (:action evt) "received")))
    (fn [evt] (:exchange evt))))

(defstat ps-converges-per-second
  (rate
    (fn [evt] (and (heroku? evt) (:service evt) (:transition evt)))))

(defstat ps-lost-last
  (last
    (fn [evt] (and (heroku? evt) (:process_lost evt)))
    (fn [evt] (:total_count evt))))

(def all
  [events-per-second
   events-per-second-by-parsed
   events-per-second-by-aorta-host
   events-per-second-by-event-type
   events-per-second-by-level
   events-per-second-by-cloud
   nginx-requests-per-second
   nginx-requests-per-second-by-domain
   amqp-publishes-per-second
   amqp-receives-per-second
   amqp-publishes-per-second-by-exchange
   amqp-receives-per-second-by-exchange
   ps-converges-per-second
   ps-lost-last])
