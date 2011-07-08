(ns pulse.def
  (:refer-clojure :exclude [last])
  (:require [pulse.util :as util]))

(defn safe-inc [n]
  (inc (or n 0)))

(defn sum [c]
  (reduce + c))

(defn rate [pred-fn time-unit time-buffer]
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
             recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now (* 1000 time-buffer) 1000))) windows)
             complete-windows (filter (fn [[window-start _ _]] (< window-start (- now 1000))) recent-windows)
             complete-count (sum (map (fn [[_ _ window-count]] window-count) complete-windows))
             complete-rate (double (/ complete-count (/ time-buffer time-unit)))]
         [recent-windows complete-rate]))})

(defn per-second [pred-fn]
  (rate pred-fn 1 10))

(defn per-minute [pred-fn]
  (rate pred-fn 60 70))

(defn rate-by-key [pred-fn key-fn time-unit time-buffer]
  {:receive-init
     (fn []
       [(util/millis) {}])
   :receive-apply
     (fn [[window-start window-counts] event]
       [window-start (if (pred-fn event) (util/update window-counts (str (key-fn event)) safe-inc) window-counts)])
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
              recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now (* 1000 time-buffer) 1000))) windows)
              complete-windows (filter (fn [[window-start _ _]] (< window-start (- now 1000))) recent-windows)
              complete-counts (apply merge-with + (map (fn [[_ _ window-counts]] window-counts) complete-windows))
              complete-sorted-counts (sort-by (fn [[k kc]] (- kc)) complete-counts)
              complete-high-counts (take 10 complete-sorted-counts)
              complete-rates (map (fn [[k kc]] [k (double (/ kc (/ time-buffer time-unit)))]) complete-high-counts)]
          [recent-windows complete-rates]))})

(defn per-second-by-key [pred-fn key-fn]
  (rate-by-key pred-fn key-fn 1 10))

(defn per-minute-by-key [pred-fn key-fn]
  (rate-by-key pred-fn key-fn 60 70))

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

(defn last-sum [pred-fn part-fn val-fn]
  {:receive-init
     (fn []
       {})
   :receive-apply
     (fn [last-timed-vals event]
       (if (pred-fn event)
         (assoc last-timed-vals (part-fn event) [(util/millis) (val-fn event)])
         last-timed-vals))
   :receive-emit
     (fn [last-timed-vals]
       last-timed-vals)
   :merge-init
     (fn []
       {})
   :merge-apply
     (fn [last-timed-vals received]
       (merge last-timed-vals received))
   :merge-emit
     (fn [last-timed-vals]
       (let [now (util/millis)
             recent-timed-vals (into {} (filter (fn [[_ [last-time _]]] (< (- now last-time) (* 300 1000))) last-timed-vals))
             recent-sum (sum (map (fn [[_ [_ last-val]]] last-val) recent-timed-vals))]
         [recent-timed-vals recent-sum]))})

(defmacro defstat [stat-name stat-body]
  (let [stat-name-str (name stat-name)]
    `(def ~stat-name (merge ~stat-body {:name (name ~stat-name-str)}))))

(defn heroku? [evt]
  (= (:cloud evt) "heroku.com"))

(defstat events-per-second
  (per-second
    (fn [evt] true)))

(defstat events-per-second-by-parsed
  (per-second-by-key
    (fn [evt] true)
    (fn [evt] (:parsed evt))))

(defstat events-per-second-by-aorta-host
  (per-second-by-key
    (fn [evt] true)
    (fn [evt] (:aorta_host evt))))

(defstat events-per-second-by-event-type
  (per-second-by-key
    (fn [evt] true)
    (fn [evt] (or (:event_type evt) "none"))))

(defstat events-per-second-by-level
  (per-second-by-key
    (fn [evt] true)
    (fn [evt] (or (:level evt) "none"))))

(defstat events-per-second-by-cloud
  (per-second-by-key
    (fn [evt] true)
    (fn [evt] (or (:cloud evt) "none"))))

(defstat nginx-requests-per-second
  (per-second
    (fn [evt] (and heroku? evt) (= (:event_type evt) "nginx_access"))))

(defstat nginx-requests-per-second-by-domain
  (per-second-by-key
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "nginx_access")))
    (fn [evt] (:http_domain evt))))

(defn nginx-per-minute [status]
  (per-minute
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "nginx_access")
                   (not= (:http_host evt) "127.0.0.1") (= (:http_status evt) status)))))

(defstat nginx-500-per-minute
  (nginx-per-minute 500))

(defstat nginx-502-per-minute
  (nginx-per-minute 502))

(defstat nginx-503-per-minute
  (nginx-per-minute 503))

(defstat nginx-504-per-minute
  (nginx-per-minute 504))

(defstat nginx-errors-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "nginx_error")))))

(defstat nginx-errors-per-minute-by-host
  (per-minute-by-key
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "nginx_error")))
    (fn [evt] (:host evt))))

(defstat varnish-requests-per-second
  (per-second
    (fn [evt] (and heroku? evt) (= (:event_type evt) "varnish_access"))))

(defn varnish-per-minute [status]
  (per-minute
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "varnish_access")
                   (= (:http_status evt) status)))))

(defstat varnish-500-per-minute
  (varnish-per-minute 500))

(defstat varnish-502-per-minute
  (varnish-per-minute 502))

(defstat varnish-503-per-minute
  (varnish-per-minute 503))

(defstat varnish-504-per-minute
  (varnish-per-minute 504))

(defstat hermes-requests-per-second
  (per-second
    (fn [evt] (and (heroku? evt) (:hermes_proxy evt)))))

(defstat hermes-requests-per-second-by-app-id
  (per-second-by-key
    (fn [evt] (and (heroku? evt) (:hermes_proxy evt)))
    (fn [evt] (:app_id evt))))

(defn hermes-per-minute [code]
  (per-minute
    (fn [evt] (and (heroku? evt) (= (:event_type evt) "standard")
                   (:hermes_proxy evt) (:Error evt) (= (:code evt) code)))))

(defstat hermes-h10-per-minute
  (hermes-per-minute "H10"))

(defstat hermes-h11-per-minute
  (hermes-per-minute "H11"))

(defstat hermes-h12-per-minute
  (hermes-per-minute "H12"))

(defstat hermes-h13-per-minute
  (hermes-per-minute "H13"))

(defstat hermes-h14-per-minute
  (hermes-per-minute "H14"))

(defstat hermes-h99-per-minute
  (hermes-per-minute "H99"))

(defstat amqp-publishes-per-second
  (per-second
    (fn [evt] (and (heroku? evt) (:amqp_publish evt)))))

(defstat amqp-receives-per-second
  (per-second
    (fn [evt] (and (heroku? evt) (:amqp_message evt) (= (:action evt) "received")))))

(defstat amqp-timeouts-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:amqp_message evt) (= (:action evt) "timeout")))))

(defstat amqp-publishes-per-second-by-exchange
  (per-second-by-key
    (fn [evt] (and (heroku? evt) (:amqp_publish evt)))
    (fn [evt] (:exchange evt))))

(defstat amqp-receives-per-second-by-exchange
  (per-second-by-key
    (fn [evt] (and (heroku? evt) (:amqp_message evt) (= (:action evt) "received")))
    (fn [evt] (:exchange evt))))

(defstat amqp-timeouts-per-minute-by-exchange
  (per-minute-by-key
    (fn [evt] (and (heroku? evt) (:amqp_message evt) (= (:action evt) "timeout")))
    (fn [evt] (:exchange evt))))

(defstat ps-up-total-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:up evt))))

(defstat ps-up-web-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:web evt))))

(defstat ps-up-worker-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:worker evt))))

(defstat ps-up-other-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:other evt))))

(defstat ps-created-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:created evt))))

(defstat ps-starting-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:starting evt))))

(defstat ps-idles-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:up_to_up evt) (= (:event evt) "idle")))))

(defstat ps-unidles-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:unidle evt) (= (:event evt) "begin")))))

(defstat ps-crashed-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:crashed evt))))

(defstat ps-running-total-last
  (last-sum
    (fn [evt] (and (heroku? evt) (:railgun evt) (:counts evt) (= (:key evt) "total")))
    (fn [evt] (:ion_id evt))
    (fn [evt] (:num evt))))

(defstat ps-running-web-last
  (last-sum
    (fn [evt] (and (heroku? evt) (:railgun evt) (:counts evt) (= (:key evt) "process_type") (= (:process_type evt) "web")))
    (fn [evt] (:ion_id evt))
    (fn [evt] (:num evt))))

(defstat ps-running-worker-last
  (last-sum
    (fn [evt] (and (heroku? evt) (:railgun evt) (:counts evt) (= (:key evt) "process_type") (= (:process_type evt) "worker")))
    (fn [evt] (:ion_id evt))
    (fn [evt] (:num evt))))

(defstat ps-running-other-last
  (last-sum
    (fn [evt] (and (heroku? evt) (:railgun evt) (:counts evt) (= (:key evt) "process_type") (= (:process_type evt) "other")))
    (fn [evt] (:ion_id evt))
    (fn [evt] (:num evt))))

(defstat ps-run-requests-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:amqp_publish evt) (= (:exchange evt) "ps.run")))))

(defstat ps-runs-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:ps_watch evt) (:ps_run evt) (= (:event evt) "start")))))

(defstat ps-returns-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:ps_watch evt) (:ps_run evt) (= (:event evt) "exit")))))

(defstat ps-stop-requests-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:amqp_publish evt) (:exchange evt) (re-find #"ps\.kill\.\d+" (:exchange evt))))))

(defstat ps-stops-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:ps_watch evt) (:trap_exit evt)))))

(defstat ps-converges-per-second
  (per-second
    (fn [evt] (and (heroku? evt) (:service evt) (:transition evt)))))

(defstat ps-timeouts-per-minute
  (per-minute
    (fn [evt] (and (heroku? evt) (:monitor_boot evt) (= (:event evt) "timeout")))))

(defstat ps-lost-last
  (last
    (fn [evt] (and (heroku? evt) (:psmgr evt) (:counts evt) (= (:event evt) "emit")))
    (fn [evt] (:lost evt))))

(def all
  [events-per-second
   events-per-second-by-parsed
   events-per-second-by-aorta-host
   events-per-second-by-event-type
   events-per-second-by-level
   events-per-second-by-cloud
   nginx-requests-per-second
   nginx-requests-per-second-by-domain
   nginx-500-per-minute
   nginx-502-per-minute
   nginx-503-per-minute
   nginx-504-per-minute
   nginx-errors-per-minute
   nginx-errors-per-minute-by-host
   varnish-requests-per-second
   varnish-500-per-minute
   varnish-502-per-minute
   varnish-503-per-minute
   varnish-504-per-minute
   hermes-requests-per-second
   hermes-requests-per-second-by-app-id
   hermes-h10-per-minute
   hermes-h11-per-minute
   hermes-h12-per-minute
   hermes-h13-per-minute
   hermes-h14-per-minute
   hermes-h99-per-minute
   amqp-publishes-per-second
   amqp-receives-per-second
   amqp-timeouts-per-minute
   amqp-publishes-per-second-by-exchange
   amqp-receives-per-second-by-exchange
   amqp-timeouts-per-minute-by-exchange
   ps-up-total-last
   ps-up-web-last
   ps-up-worker-last
   ps-up-other-last
   ps-created-last
   ps-starting-last
   ps-idles-per-minute
   ps-unidles-per-minute
   ps-crashed-last
   ps-timeouts-per-minute
   ps-running-total-last
   ps-running-web-last
   ps-running-worker-last
   ps-running-other-last
   ps-run-requests-per-minute
   ps-runs-per-minute
   ps-returns-per-minute
   ps-stop-requests-per-minute
   ps-stops-per-minute
   ps-converges-per-second
   ps-lost-last])
