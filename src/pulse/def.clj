(ns pulse.def
  (:refer-clojure :exclude [last max])
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [pulse.util :as util]
            [pulse.conf :as conf]
            [pulse.log :as log]))

(defn log [& data]
  (apply log/log :ns "def" data))

(defn safe-inc [n]
  (inc (or n 0)))

(defn coll-sum [c]
  (reduce + c))

(defn coll-mean [c]
  (let [n (count c)]
    (if (zero? n)
      0
      (float (/ (coll-sum c) n)))))

(defn max [time-buffer pred-fn val-fn]
  {:receive-init
     (fn []
       [(util/millis) nil])
   :receive-apply
     (fn [[window-start window-max :as window] evt]
       (if-not (pred-fn evt)
         window
         (let [val (val-fn evt)]
           (cond
             (nil? window-max)  [window-start val]
             (> val window-max) [window-start val]
             :else              window))))
   :receive-emit
     (fn [receive-buffer]
       receive-buffer)
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows [window-start window-max :as window]]
       (conj windows window))
   :merge-emit
     (fn [windows]
       (let [now (util/millis)
             recent-windows (filter (fn [[window-start _]] (>= window-start (- now (* 1000 time-buffer)))) windows)
             recent-values (->> windows (map (fn [[_ window-max]] window-max)) (filter #(not (nil? %))))
             recent-max (or (and (seq recent-values) (apply clojure.core/max recent-values)) 0)]
         [recent-windows recent-max]))})

(defn mean [time-buffer pred-fn val-fn]
  {:receive-init
     (fn []
       [(util/millis) 0 0])
   :receive-apply
     (fn [[window-start window-count window-sum :as receive-buffer] evt]
       (if-not (pred-fn evt)
         receive-buffer
         (let [val (val-fn evt)]
           (if (or (nil? val) (not (number? val)))
             (do
               (prn :fn "mean" :at "nil-val" :msg (:msg evt))
               receive-buffer)
             [window-start (inc window-count) (+ window-sum val)]))))
   :receive-emit
     (fn [receive-buffer]
       receive-buffer)
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
       (let [now (util/millis)
             recent-windows (filter (fn [[window-start _ _]] (>= window-start (- now (* 1000 time-buffer)))) windows)
             recent-count (coll-sum (map (fn [[_ window-count _]] window-count) recent-windows))
             recent-sum (coll-sum (map (fn [[_ _ window-sum]] window-sum) recent-windows))
             recent-mean (double (if (zero? recent-count) 0 (/ recent-sum recent-count)))]
         [recent-windows recent-mean]))})

(defn rate [time-unit time-buffer pred-fn]
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
             complete-count (coll-sum (map (fn [[_ _ window-count]] window-count) complete-windows))
             complete-rate (double (/ complete-count (/ time-buffer time-unit)))]
         [recent-windows complete-rate]))})

(defn per-second [pred-fn]
  (rate 1 10 pred-fn))

(defn per-minute [pred-fn]
  (rate 60 70 pred-fn))

(defn rate-by-key [time-unit time-buffer pred-fn key-fn]
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
  (rate-by-key 1 10 pred-fn key-fn))

(defn per-minute-by-key [pred-fn key-fn]
  (rate-by-key 60 70 pred-fn key-fn))

(defn rate-unique [time-buffer pred-fn key-fn]
  {:receive-init
     (fn []
       [(util/millis) #{}])
   :receive-apply
     (fn [[window-start window-hits] event]
       [window-start (if (pred-fn event) (conj window-hits (key-fn event)) window-hits)])
   :receive-emit
     (fn [window]
       window)
   :merge-init
     (fn []
       [])
   :merge-apply
     (fn [windows window]
       (conj windows window))
   :merge-emit
     (fn [windows]
        (let [now (util/millis)
              recent-windows (filter (fn [[window-start _]] (>= window-start (- now (* 1000 time-buffer)))) windows)
              recent-hits (apply set/union (map (fn [[_ window-hits]] window-hits) recent-windows))
              recent-count (count recent-hits)]
          [recent-windows recent-count]))})

(defn per-minute-unique [pred-fn key-fn]
  (rate-unique 60 pred-fn key-fn))

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

(defn last-agg [recent-interval pred-fn part-fn val-fn agg-fn]
  {:receive-init
     (fn []
       {})
   :receive-apply
     (fn [last-timed-vals evt]
       (if (pred-fn evt)
         (assoc last-timed-vals (part-fn evt) [(util/millis) (val-fn evt)])
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
             recent-timed-vals (into {} (filter (fn [[_ [last-time _]]] (< (- now last-time) (* (or recent-interval 300) 1000))) last-timed-vals))
             recent-agg (agg-fn (map (fn [[_ [_ last-val]]] last-val) recent-timed-vals))]
         [recent-timed-vals recent-agg]))})

(defn last-mean [recent-interval pred-fn part-fn val-fn]
  (last-agg recent-interval pred-fn part-fn val-fn coll-mean))

(defn last-sum [pred-fn part-fn val-fn & [recent-interval]]
  (let [recent-interval (or recent-interval 300)]
    (last-agg recent-interval pred-fn part-fn val-fn coll-sum)))

(defn last-count [pred-fn part-fn cnt-fn & [recent-interval]]
  (let [recent-interval (or recent-interval 300)]
    (last-sum pred-fn part-fn (fn [evt] (if (cnt-fn evt) 1 0)) recent-interval)))

;; for stats which apply across clouds
(defmacro defstat-single [stat-name stat-body]
  `(def ~stat-name (merge ~stat-body {:name (name '~stat-name)})))

(defn scope-stat [cloud stat-name]
  (if (= cloud (conf/default-cloud))
    (name stat-name)
    (str cloud "." (name stat-name))))

(defmacro defstat [stat-name stat-body]
  (let [body-sym (gensym)]
    `(let [~body-sym ~stat-body]
       ~@(for [cloud (conf/clouds)
               :let [scoped-name (scope-stat cloud stat-name)
                     scoped-var-name (symbol (string/replace scoped-name "." "-"))]]
           `(def ~scoped-var-name (assoc ~body-sym
                                    :name ~scoped-name
                                    :pred-fn (fn [evt#]
                                               (and (= (:cloud evt#) ~cloud)
                                                    ((:pred-fn ~body-sym) evt#)))))))))

(defn kv? [m k v]
  (= (k m) v))

(defn k? [evt k]
  (contains? evt k))

(defn cont? [evt k v]
  (let [^String s (or (k evt) "")]
    (.contains s v)))

(defn >? [evt k v]
  (> (k evt) v))

(defn >=? [evt k v]
  (>= (k evt) v))

(defn start? [evt]
  (kv? evt :at "start"))

(defn finish? [evt]
  (kv? evt :at "finish"))

(defn error? [evt]
  (kv? evt :at "error"))

(defn emit? [evt]
  (kv? evt :at "emit"))

; global

(defstat-single events-per-second
  (per-second (constantly true)))

(defstat-single events-per-second-unparsed
  (per-second :unparsed))

(defstat amqp-publishes-per-second
  (per-second
   (fn [evt] (or (k? evt :amqp_publish)
                (and (k? evt :amqp_message)
                     (kv? evt :action "publish"))))))

(defstat amqp-receives-per-second
  (per-second
   (fn [evt] (and (k? evt :amqp_message) (kv? evt :action "received")))))

(defstat amqp-timeouts-per-minute
  (per-minute
   (fn [evt] (and (k? evt :amqp_message) (kv? evt :action "timeout")))))

; routing

(defn nginx-request? [evt]
  (and (kv? evt :source "nginx") (k? evt :http_status)))

(defstat nginx-requests-per-second
  (per-second nginx-request?))

(defstat nginx-requests-domains-per-minute
  (per-minute-unique nginx-request? :http_domain))

(defn nginx-per-minute [status]
  (per-minute
   (fn [evt] (and (nginx-request? evt)
                 (not (kv? evt :http_host "127.0.0.1"))
                 (kv? evt :http_status status)))))

(defstat nginx-500-per-minute
  (nginx-per-minute 500))

(defstat nginx-502-per-minute
  (nginx-per-minute 502))

(defstat nginx-503-per-minute
  (nginx-per-minute 503))

(defstat nginx-504-per-minute
  (nginx-per-minute 504))

(defn nginx-domains-per-minute [status]
  (per-minute-unique
   (fn [evt] (and (nginx-request? evt)
                 (not (kv? evt :http_host "127.0.0.1"))
                 (kv? evt :http_status status)))
    :http_domain))

(defstat nginx-500-domains-per-minute
  (nginx-domains-per-minute 500))

(defstat nginx-502-domains-per-minute
  (nginx-domains-per-minute 502))

(defstat nginx-503-domains-per-minute
  (nginx-domains-per-minute 503))

(defstat nginx-504-domains-per-minute
  (nginx-domains-per-minute 504))

(defn nginx-error? [evt]
  (and (kv? evt :source "nginx")
       (kv? evt :level "crit")
       (cont? evt :msg "[error]")))

(defstat nginx-errors-per-minute
  (per-minute nginx-error?))

(defstat nginx-errors-instances-per-minute
  (per-minute-unique nginx-error? :instance_id))

(defn varnish-request? [evt]
  (and (kv? evt :source "varnish") (k? evt :http_status)))

(defstat varnish-requests-per-second
  (per-second varnish-request?))

(defn varnish-per-minute [status]
  (per-minute
   (fn [evt] (and (varnish-request? evt) (kv? evt :http_status status)))))

(defstat varnish-500-per-minute
  (varnish-per-minute 500))

(defstat varnish-502-per-minute
  (varnish-per-minute 502))

(defstat varnish-503-per-minute
  (varnish-per-minute 503))

(defstat varnish-504-per-minute
  (varnish-per-minute 504))

(defstat varnish-purges-per-minute
  (per-minute
   (fn [evt] (k? evt :cache_purge))))

;; we only run a single rendezvous service for multiple clouds
(defstat-single rendezvous-joins-per-minute
  (per-minute
    (fn [evt] (and (kv? evt :app "rendezvous") (kv? evt :fn "join") (kv? evt :at "start")))))

(defstat-single rendezvous-rendezvous-per-minute
  (per-minute
    (fn [evt] (and (kv? evt :app "rendezvous") (kv? evt :fn "join") (kv? evt :at "rendezvous")))))

(defn hermes-request? [evt]
  (kv? evt :mod "hermes_proxy"))

(defstat hermes-requests-per-second
  (per-second
    (fn [evt] (and (hermes-request? evt) (k? evt :code)))))

(defstat hermes-requests-apps-per-minute
  (per-minute-unique hermes-request? :app_id))

(defn hermes-per-minute [code]
  (per-minute
   (fn [evt] (and (hermes-request? evt) (kv? evt :code code)))))

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

(defstat hermes-h15-per-minute
  (hermes-per-minute "H15"))

(defstat hermes-h16-per-minute
  (hermes-per-minute "H16"))

(defstat hermes-h17-per-minute
  (hermes-per-minute "H17"))

(defstat hermes-h18-per-minute
  (hermes-per-minute "H18"))

(defstat hermes-h19-per-minute
  (hermes-per-minute "H19"))

(defstat hermes-h20-per-minute
  (hermes-per-minute "H20"))

(defstat hermes-h80-per-minute
  (hermes-per-minute "H80"))

(defstat hermes-h99-per-minute
  (hermes-per-minute "H99"))

(defn hermes-apps-per-minute [code]
  (per-minute-unique
    (fn [evt] (and (hermes-request? evt) (kv? evt :code code)))
    :app_id))

(defstat hermes-h10-apps-per-minute
  (hermes-apps-per-minute "H10"))

(defstat hermes-h11-apps-per-minute
  (hermes-apps-per-minute "H11"))

(defstat hermes-h12-apps-per-minute
  (hermes-apps-per-minute "H12"))

(defstat hermes-h13-apps-per-minute
  (hermes-apps-per-minute "H13"))

(defstat hermes-h14-apps-per-minute
  (hermes-apps-per-minute "H14"))

(defstat hermes-h15-apps-per-minute
  (hermes-apps-per-minute "H15"))

(defstat hermes-h16-apps-per-minute
  (hermes-apps-per-minute "H16"))

(defstat hermes-h17-apps-per-minute
  (hermes-apps-per-minute "H17"))

(defstat hermes-h18-apps-per-minute
  (hermes-apps-per-minute "H18"))

(defstat hermes-h19-apps-per-minute
  (hermes-apps-per-minute "H19"))

(defstat hermes-h20-apps-per-minute
  (hermes-apps-per-minute "H20"))

(defstat hermes-h80-apps-per-minute
  (hermes-apps-per-minute "H80"))

(defstat hermes-h99-apps-per-minute
  (hermes-apps-per-minute "H99"))

(defstat hermes-econns-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :error_type "econn") (kv? evt :mod "hermes_proxy")))))

(defstat hermes-econns-apps-per-minute
  (per-minute-unique
   (fn [evt] (and (kv? evt :error_type "econn") (kv? evt :mod "hermes_proxy")))
   :app_id))

(defstat hermes-errors-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :level "err") (kv? evt :source "hermes")))))

(defstat hermes-services-lockstep-updates-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "services_callback") (kv? evt :at "msg")))))

(defstat hermes-services-lockstep-connections-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "services_callback") (kv? evt :at "connect")))))

(defstat hermes-services-lockstep-disconnects-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "services_callback") (kv? evt :at "disconnect")))))

(defstat hermes-services-lockstep-mean-latency
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "services_callback")
                      (k? evt :latency)))
        :latency))

(defstat hermes-services-lockstep-mean-staleness
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "services_callback")
                      (k? evt :staleness)))
        :staleness))

(defstat hermes-procs-lockstep-updates-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "procs_callback")
                 (kv? evt :at "msg")))))

(defstat hermes-procs-lockstep-connections-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "procs_callback")
                 (kv? evt :at "connect")))))

(defstat hermes-procs-lockstep-disconnects-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "procs_callback")
                 (kv? evt :at "disconnect")))))

(defstat hermes-procs-lockstep-mean-latency
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "procs_callback")
                      (k? evt :latency)))
        :latency))

(defstat hermes-procs-lockstep-mean-staleness
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "procs_callback")
                      (k? evt :staleness)))
        :staleness))

(defstat hermes-domains-lockstep-updates-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "domains_callback")
                 (kv? evt :at "msg")))))

(defstat hermes-domains-lockstep-connections-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "domains_callback")
                 (kv? evt :at "connect")))))

(defstat hermes-domains-lockstep-disconnects-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "domains_callback")
                 (kv? evt :at "disconnect")))))

(defstat hermes-domains-lockstep-mean-latency
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "domains_callback")
                      (k? evt :latency)))
        :latency))

(defstat hermes-domains-lockstep-mean-staleness
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "domains_callback")
                      (k? evt :staleness)))
        :staleness))

(defstat hermes-domain-groups-lockstep-updates-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "domain_groups_callback")
                 (kv? evt :at "msg")))))

(defstat hermes-domain-groups-lockstep-connections-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "domain_groups_callback")
                 (kv? evt :at "connect")))))

(defstat hermes-domain-groups-lockstep-disconnects-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :mod "domain_groups_callback")
                 (kv? evt :at "disconnect")))))

(defstat hermes-domain-groups-lockstep-mean-latency
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "domain_groups_callback")
                      (k? evt :latency)))
        :latency))

(defstat hermes-domain-groups-lockstep-mean-staleness
  (mean 70
        (fn [evt] (and (kv? evt :mod "hermes_clock")
                      (kv? evt :callback "domain_groups_callback")
                      (k? evt :staleness)))
        :staleness))

(defstat hermes-elevated-route-lookups-per-minute
  (per-minute
    (fn [evt] (and (hermes-request? evt) (kv? evt :code "OK") (>=? evt :route 2.0)))))

(defstat hermes-slow-route-lookups-per-minute
  (per-minute
    (fn [evt] (and (hermes-request? evt) (kv? evt :code "OK") (>=? evt :route 10.0)))))

(defstat hermes-catastrophic-route-lookups-per-minute
  (per-minute
    (fn [evt] (and (hermes-request? evt) (kv? evt :code "OK") (>=? evt :route 100.0)))))

(defstat hermes-slow-redis-lookups-per-minute
  (per-minute
    (fn [evt] (and (hermes-request? evt) (number? (:redis evt)) (>=? evt :redis 10.0)))))

(defstat hermes-catastrophic-redis-lookups-per-minute
  (per-minute
    (fn [evt] (and (hermes-request? evt) (number? (:redis evt)) (>=? evt :redis 25.0)))))

(defstat hermes-processes-last
  (last-sum
   (fn [evt] (and (kv? evt :mod "hermes_clock") (kv? evt :at "stats")))
   :instance_id
   :processes))

(defstat hermes-ports-last
  (last-sum
   (fn [evt] (and (kv? evt :mod "hermes_clock") (kv? evt :at "stats")))
   :instance_id
   :ports))

(defstat logplex-msg-processed
  (last-sum
   (fn [evt] (and (k? evt :logplex_stats) (k? evt :message_processed)))
   :instance_id
   :message_processed))

(defstat logplex-drain-delivered
  (last-sum
   (fn [evt] (and (k? evt :logplex_stats) (k? evt :drain_delivered)))
   (fn [evt] [(:instance_id evt) (:drain_id evt)])
   :drain_delivered))

; railgun

(defn railgun? [evt]
  (k? evt :railgun))

(defstat railgun-running-count
  (last-count
    (fn [evt] (and (railgun? evt) (k? evt :heartbeat)))
    :instance_id
    (constantly true)
    40))

(defstat railgun-denied-count
  (last-count
    (fn [evt] (and (railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    :deny
    40))

(defstat railgun-packed-count
  (last-count
    (fn [evt] (and (railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    :packed
    40))

(defstat railgun-loaded-count
  (last-count
    (fn [evt] (and (railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    (fn [evt] (kv? evt :load_status "loaded"))
    40))

(defstat railgun-critical-count
  (last-count
    (fn [evt] (and (railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    (fn [evt] (kv? evt :load_status "critical"))
    40))

(defstat railgun-accepting-count
  (last-count
    (fn [evt] (and (railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    (fn [evt] (kv? evt :run_factor 1))
    40))

(defstat railgun-load-avg-15m-mean
  (last-mean 90
    (fn [evt] (and (railgun? evt) (k? evt :check_load_status) (kv? evt :at "report")))
    :instance_id
    :load_avg_fifteen))

(defstat railgun-ps-running-total-last
  (last-sum
    (fn [evt] (and (railgun? evt) (k? evt :counts) (kv? evt :key "total")))
    :instance_id
    :num))

(defn last-sum-process-type [t]
  (last-sum
    (fn [evt] (and (railgun? evt) (k? evt :counts) (kv? evt :key "process_type") (kv? evt :process_type t)))
    :instance_id
    :num))

(defstat railgun-ps-running-web-last
  (last-sum-process-type "web"))

(defstat railgun-ps-running-worker-last
  (last-sum-process-type "worker"))

(defstat railgun-ps-running-clock-last
  (last-sum-process-type "clock"))

(defstat railgun-ps-running-console-last
  (last-sum-process-type "console"))

(defstat railgun-ps-running-rake-last
  (last-sum-process-type "rake"))

(defstat railgun-ps-running-other-last
  (last-sum-process-type "other"))

(defstat railgun-runs-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :ps_watch) (k? evt :ps_run) (kv? evt :at "start")))))

(defstat railgun-returns-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :ps_watch) (k? evt :ps_run) (kv? evt :at "exit")))))

(defstat railgun-kills-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :ps_terminate) (k? evt :term_pid) (start? evt)))))

(defstat railgun-subscribes-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :converge_queues) (kv? evt :at "subscribe")))))

(defstat railgun-unsubscribes-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :converge_queues) (kv? evt :at "unsubscribe")))))

(defstat railgun-status-batches-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :publish_batch_status) (finish? evt)))))

(defstat railgun-gcs-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :gc_one) (finish? evt)))))

(defstat railgun-kill-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :kill) (finish? evt)))
    :elapsed))

(defstat railgun-save-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :save_slug) (finish? evt)))
    :elapsed))

(defstat railgun-unpack-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :unpack_slug) (kv? evt :slug_url true) (finish? evt)))
    :elapsed))

(defstat railgun-setup-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :start_boot) (finish? evt)))
    :age))

(defstat railgun-launch-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :monitor_boot) (kv? evt :at "responsive")))
    :age))

(defstat railgun-status-batch-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :publish_batch_status) (finish? evt)))
    :elapsed))

(defstat railgun-gc-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :gc_one) (finish? evt)))
    :elapsed))

(defstat railgun-s3-requests-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :save_slug_attempt) (start? evt)))))

(defstat railgun-s3-errors-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :save_slug_attempt) (error? evt)))))

(defstat railgun-s3-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :save_slug_attempt) (finish? evt)))
    :elapsed))

(defstat railgun-slug-download-fails-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :save_slug) (kv? evt :at "failed")))))

(defstat railgun-s3-canary-requests-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :check_s3) (start? evt)))))

(defstat railgun-s3-canary-errors-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :check_s3) (error? evt)))))

(defstat railgun-s3-canary-time-mean
  (mean 70
    (fn [evt] (and (railgun? evt) (k? evt :check_s3) (finish? evt)))
    :elapsed))

(defstat railgun-r10-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :monitor_boot) (kv? evt :at "timeout")))))

(defstat railgun-r11-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :monitor_boot) (kv? evt :at "bad_bind")))))

(defstat railgun-r12-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :kill_pids) (kv? evt :at "timeout")))))

(defstat railgun-r10-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :monitor_boot) (kv? evt :at "timeout")))
    :app_id))

(defstat railgun-r11-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :monitor_boot) (kv? evt :at "bad_bind")))
    :app_id))

(defstat railgun-r12-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :kill_pids) (kv? evt :at "timeout")))
    :app_id))

(defstat railgun-r14-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "memory") (kv? evt :at "warn")))))

(defstat railgun-r15-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "memory") (kv? evt :at "kill")))))

(defstat railgun-r16-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :sighup_user_pids) (kv? evt :at "detached")))))

(defstat railgun-r17-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "cpu") (kv? evt :at "warn")))))

(defstat railgun-r18-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "cpu") (kv? evt :at "kill")))))

(defstat railgun-r14-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "memory") (kv? evt :at "warn")))
    :app_id))

(defstat railgun-r15-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "memory") (kv? evt :at "kill")))
    :app_id))

(defstat railgun-r17-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "cpu") (kv? evt :at "warn")))
    :app_id))

(defstat railgun-r18-apps-per-minute
  (per-minute-unique
    (fn [evt] (and (railgun? evt) (k? evt :check_usage)
                   (kv? evt :resource "cpu") (kv? evt :at "kill")))
    :app_id))

(defstat railgun-inits-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :init_railgun) (start? evt)))))

(defstat railgun-traps-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :trap)))))

(defstat railgun-exits-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :init_railgun) (finish? evt)))))

(defstat railgun-unhandled-exceptions-per-minute
  (per-minute
   (fn [evt] (and (k? evt :railgun) (k? evt :exception)
                 (not (k? evt :site)) (not (k? evt :reraise))
                 (not (k? evt :trapping))))))

(defstat railgun-pings-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :server) (k? evt :request) (finish? evt)))))

(defstat railgun-heartbeats-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :heartbeat)))))

(defstat railgun-runtime-bus-publishes-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :bus) (k? evt :queue) (kv? evt :at "publish")))))

(defstat railgun-runtime-bus-processing-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :bus) (k? evt :queue) (kv? evt :at "processing")))))

(defstat railgun-runtime-bus-expired-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :bus) (k? evt :message) (kv? evt :status "expired")))))

(defstat railgun-runtime-bus-invalid-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :bus) (k? evt :message) (kv? evt :status "invalid")))))

(defstat railgun-runtime-bus-failed-pushes-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :bus) (k? evt :pool) (k? evt :skipping_failed_connection)))))

(defstat railgun-runtime-bus-failed-lpops-per-minute
  (per-minute
    (fn [evt] (and (railgun? evt) (k? evt :bus) (k? evt :pool) (kv? evt :operation "blpop_single") (kv? evt :at "exception")))))

(defstat railgun-events-per-second
  (per-second railgun?))

; railgun build cluster

(defn build-railgun? [evt]
  (and (railgun? evt) (kv? evt :cluster "build")))

(defstat build-railgun-running-count
  (last-count
    (fn [evt] (and (build-railgun? evt) (k? evt :heartbeat)))
    :instance_id
    (constantly true)
    40))

(defstat build-railgun-denied-count
  (last-count
    (fn [evt] (and (build-railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    :deny
    40))

(defstat build-railgun-packed-count
  (last-count
    (fn [evt] (and (build-railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    :packed
    40))

(defstat build-railgun-loaded-count
  (last-count
    (fn [evt] (and (build-railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    (fn [evt] (kv? evt :load_status "loaded"))
    40))

(defstat build-railgun-critical-count
  (last-count
    (fn [evt] (and (build-railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    (fn [evt] (kv? evt :load_status "critical"))
    40))

(defstat build-railgun-accepting-count
  (last-count
    (fn [evt] (and (build-railgun? evt) (k? evt :stats) (emit? evt)))
    :instance_id
    (fn [evt] (kv? evt :run_factor 1))
    40))

(defstat build-railgun-load-avg-15m-mean
  (last-mean 90
    (fn [evt] (and (build-railgun? evt) (k? evt :check_load_status) (kv? evt :at "report")))
    :instance_id
    :load_avg_fifteen))

(defstat build-railgun-ps-running-total-last
  (last-sum
    (fn [evt] (and (build-railgun? evt) (k? evt :counts) (kv? evt :key "total")))
    :instance_id
    :num))

; psmgr

(defstat psmgr-idles-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :function "up_to_up")
                 (kv? evt :event "idle")))))

(defstat psmgr-unidles-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :function "unidle")
                 (kv? evt :block "begin")))))

(defstat psmgr-run-requests-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :file "runtime")
                 (kv? evt :key "ps.run")
                 (kv? evt :event "published")))))

(defstat psmgr-kill-requests-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :file "runtime")
                 (cont? evt :key "ps.kill.")
                 (kv? evt :event "published")))))

(defstat psmgr-converges-per-second
  (per-second
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :function "transition")
                 (kv? evt :block "begin")))))

(defstat psmgr-unhandled-exceptions-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :level "err")
                 (kv? evt :source "psmgr")))))

(defstat psmgr-runtime-bus-receives-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :file "runtime")
                 (k? evt :queue)
                 (kv? evt :event "received")))))

(defstat psmgr-runtime-bus-timeouts-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :file "runtime")
                 (k? evt :queue)
                 (kv? evt :event "timeout")))))

(defstat psmgr-runtime-bus-published-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :file "runtime")
                 (k? evt :key)
                 (kv? evt :event "published")))))

(defstat psmgr-events-per-second
  (per-second
   (fn [evt] (kv? evt :source "psmgr"))))

(defstat psmgr-api-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :function "post")
                 (kv? evt :block "finish")))))

(defstat psmgr-api-time
  (mean 60
        (fn [evt] (and (kv? evt :source "psmgr")
                      (kv? evt :function "post")
                      (kv? evt :block "finish")))
        :elapsed))

(defstat psmgr-runs-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :event "run")))))

(defstat psmgr-cycles-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :event "cycle")))))

(defstat psmgr-lost-runs-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :event "lost_run")))))

(defstat psmgr-foregrounds-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :fn "foreground")))))

(defstat psmgr-backgrounds-per-minute
  (per-minute
   (fn [evt] (and (kv? evt :source "psmgr")
                 (kv? evt :fn "background")))))

; packaging

(defstat gitproxy-connections-per-minute
  (per-minute
   (fn [evt] (and (k? evt :gitproxy)
                 (k? evt :run)
                 (kv? evt :at "start")))))

(defstat gitproxy-invalids-per-minute
  (per-minute
   (fn [evt] (and (k? evt :gitproxy)
                 (k? evt :run)
                 (kv? evt :at "invalid")))))

(defstat gitproxy-errors-per-minute
  (per-minute
   (fn [evt] (and (k? evt :gitproxy)
                 (k? evt :run)
                 (kv? evt :at "exception")
                 (not (k? evt :reraise))))))

(defstat gitproxy-successes-per-minute
  (per-minute
   (fn [evt] (and (k? evt :gitproxy)
                 (k? evt :run)
                 (kv? evt :at "success")))))

(defstat gitproxy-mean-metadata-time
  (mean 60
        (fn [evt] (and (k? evt :gitproxy)
                      (k? evt :fetch_push_metadata)
                      (finish? evt)))
        :elapsed))

(defstat gitproxy-mean-provision-time
  (mean 60
        (fn [evt] (and (k? evt :gitproxy)
                      (k? evt :fetch_ssh_info)
                      (finish? evt)))
        :elapsed))

(defstat gitproxy-mean-service-time
  (mean 60
        (fn [evt] (and (k? evt :gitproxy)
                      (k? evt :run)
                      (finish? evt)))
        :elapsed))

(defn slugc? [evt]
  (k? evt :slugc))

(defstat slugc-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (start? evt) (kv? evt :event "start"))))))

(defstat slugc-aspen-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (start? evt) (kv? evt :event "start")) (kv? evt :major_stack "aspen")))))

(defstat slugc-bamboo-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (start? evt) (kv? evt :event "start")) (kv? evt :major_stack "bamboo")))))

(defstat slugc-cedar-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (start? evt) (kv? evt :event "start")) (kv? evt :major_stack "cedar")))))

(defstat slugc-failures-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (kv? evt :at "fail") (kv? evt :event "fail"))))))

(defstat slugc-errors-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (error? evt) (kv? evt :event "error"))))))

(defstat slugc-successes-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (finish? evt) (kv? evt :event "finish"))))))

(defstat slugc-mean-stow-time
  (mean 60
    (fn [evt] (and (slugc? evt) (k? evt :store_in_s3) (or (finish? evt) (kv? evt :event "finish"))))
    :elapsed))

(defstat slugc-mean-release-time
  (mean 60
    (fn [evt] (and (slugc? evt) (k? evt :post_release) (or (finish? evt) (kv? evt :event "finish"))))
    :elapsed))

(defstat slugc-stow-errors-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :store_in_s3) (or (error? evt) (kv? evt :event "error"))))))

(defstat slugc-release-errors-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :post_release) (or (error? evt) (kv? evt :event "error"))))))

(defstat slugc-mean-compile-time
  (mean 60
    (fn [evt] (and (slugc? evt) (k? evt :bin) (or (finish? evt) (kv? evt :event "finish"))))
    :elapsed))

; data

(defstat shen-errors-per-minute
  (per-minute
    (fn [evt] (and (kv? evt :level "err") (kv? evt :source "shen")))))

; internal

(defstat-single pulse-events-per-second
  (per-second
    (fn [evt] (and (kv? evt :app "pulse") (kv? evt :deploy (conf/deploy))))))

(def defaults
  [
  ; global
   events-per-second
   events-per-second-unparsed
   amqp-publishes-per-second
   amqp-receives-per-second
   amqp-timeouts-per-minute

   ; routing
   nginx-requests-per-second
   nginx-requests-domains-per-minute
   nginx-500-per-minute
   nginx-502-per-minute
   nginx-503-per-minute
   nginx-504-per-minute
   nginx-500-domains-per-minute
   nginx-502-domains-per-minute
   nginx-503-domains-per-minute
   nginx-504-domains-per-minute
   nginx-errors-per-minute
   nginx-errors-instances-per-minute
   varnish-requests-per-second
   varnish-500-per-minute
   varnish-502-per-minute
   varnish-503-per-minute
   varnish-504-per-minute
   varnish-purges-per-minute
   rendezvous-joins-per-minute
   rendezvous-rendezvous-per-minute
   hermes-requests-per-second
   hermes-requests-apps-per-minute
   hermes-h10-per-minute
   hermes-h11-per-minute
   hermes-h12-per-minute
   hermes-h13-per-minute
   hermes-h14-per-minute
   hermes-h15-per-minute
   hermes-h16-per-minute
   hermes-h17-per-minute
   hermes-h18-per-minute
   hermes-h19-per-minute
   hermes-h20-per-minute
   hermes-h80-per-minute
   hermes-h99-per-minute
   hermes-h10-apps-per-minute
   hermes-h11-apps-per-minute
   hermes-h12-apps-per-minute
   hermes-h13-apps-per-minute
   hermes-h14-apps-per-minute
   hermes-h15-apps-per-minute
   hermes-h16-apps-per-minute
   hermes-h17-apps-per-minute
   hermes-h18-apps-per-minute
   hermes-h19-apps-per-minute
   hermes-h20-apps-per-minute
   hermes-h80-apps-per-minute
   hermes-h99-apps-per-minute
   hermes-econns-per-minute
   hermes-econns-apps-per-minute
   hermes-errors-per-minute
   hermes-services-lockstep-updates-per-minute
   hermes-services-lockstep-connections-per-minute
   hermes-services-lockstep-disconnects-per-minute
   hermes-services-lockstep-mean-latency
   hermes-services-lockstep-mean-staleness
   hermes-procs-lockstep-updates-per-minute
   hermes-procs-lockstep-connections-per-minute
   hermes-procs-lockstep-disconnects-per-minute
   hermes-procs-lockstep-mean-latency
   hermes-procs-lockstep-mean-staleness
   hermes-domains-lockstep-updates-per-minute
   hermes-domains-lockstep-connections-per-minute
   hermes-domains-lockstep-disconnects-per-minute
   hermes-domains-lockstep-mean-latency
   hermes-domains-lockstep-mean-staleness
   hermes-domain-groups-lockstep-updates-per-minute
   hermes-domain-groups-lockstep-connections-per-minute
   hermes-domain-groups-lockstep-disconnects-per-minute
   hermes-domain-groups-lockstep-mean-latency
   hermes-domain-groups-lockstep-mean-staleness
;  hermes-elevated-route-lookups-per-minute
;  hermes-slow-route-lookups-per-minute
;  hermes-catastrophic-route-lookups-per-minute
   hermes-slow-redis-lookups-per-minute
   hermes-catastrophic-redis-lookups-per-minute
   hermes-processes-last
   hermes-ports-last
   logplex-msg-processed
   logplex-drain-delivered

   ; railgun
   railgun-running-count
   railgun-denied-count
   railgun-packed-count
   railgun-loaded-count
   railgun-critical-count
   railgun-accepting-count
   railgun-load-avg-15m-mean
   railgun-ps-running-total-last
   railgun-ps-running-web-last
   railgun-ps-running-worker-last
   railgun-ps-running-clock-last
   railgun-ps-running-console-last
   railgun-ps-running-rake-last
   railgun-ps-running-other-last
   railgun-runs-per-minute
   railgun-returns-per-minute
   railgun-kills-per-minute
   railgun-subscribes-per-minute
   railgun-unsubscribes-per-minute
   railgun-status-batches-per-minute
   railgun-gcs-per-minute
   railgun-kill-time-mean
   railgun-save-time-mean
   railgun-unpack-time-mean
   railgun-setup-time-mean
   railgun-launch-time-mean
   railgun-status-batch-time-mean
   railgun-gc-time-mean
   railgun-s3-requests-per-minute
   railgun-s3-errors-per-minute
   railgun-s3-time-mean
   railgun-slug-download-fails-per-minute
   railgun-s3-canary-requests-per-minute
   railgun-s3-canary-errors-per-minute
   railgun-s3-canary-time-mean
   railgun-r10-per-minute
   railgun-r11-per-minute
   railgun-r12-per-minute
   railgun-r14-per-minute
   railgun-r15-per-minute
   railgun-r16-per-minute
   railgun-r17-per-minute
   railgun-r18-per-minute
   railgun-r10-apps-per-minute
   railgun-r11-apps-per-minute
   railgun-r12-apps-per-minute
   railgun-r14-apps-per-minute
   railgun-r15-apps-per-minute
   railgun-r17-apps-per-minute
   railgun-r18-apps-per-minute
   railgun-inits-per-minute
   railgun-traps-per-minute
   railgun-exits-per-minute
   railgun-unhandled-exceptions-per-minute
   railgun-pings-per-minute
   railgun-heartbeats-per-minute
   railgun-events-per-second
   railgun-runtime-bus-publishes-per-minute
   railgun-runtime-bus-processing-per-minute
   railgun-runtime-bus-expired-per-minute
   railgun-runtime-bus-invalid-per-minute
   railgun-runtime-bus-failed-pushes-per-minute
   railgun-runtime-bus-failed-lpops-per-minute

   build-railgun-running-count
   build-railgun-denied-count
   build-railgun-packed-count
   build-railgun-loaded-count
   build-railgun-critical-count
   build-railgun-accepting-count
   build-railgun-load-avg-15m-mean
   build-railgun-ps-running-total-last

   ; psmgr
   psmgr-idles-per-minute
   psmgr-unidles-per-minute
   psmgr-run-requests-per-minute
   psmgr-kill-requests-per-minute
   psmgr-converges-per-second
   psmgr-unhandled-exceptions-per-minute
   psmgr-runtime-bus-receives-per-minute
   psmgr-runtime-bus-timeouts-per-minute
   psmgr-runtime-bus-published-per-minute
   psmgr-events-per-second
   psmgr-api-per-minute
   psmgr-api-time
   psmgr-runs-per-minute
   psmgr-cycles-per-minute
   psmgr-lost-runs-per-minute
   psmgr-foregrounds-per-minute
   psmgr-backgrounds-per-minute

   ; packaging
   gitproxy-connections-per-minute
   gitproxy-invalids-per-minute
   gitproxy-errors-per-minute
   gitproxy-successes-per-minute
   gitproxy-mean-metadata-time
   gitproxy-mean-provision-time
   gitproxy-mean-service-time
   slugc-compiles-per-minute
   slugc-failures-per-minute
   slugc-errors-per-minute
   slugc-successes-per-minute
   slugc-aspen-compiles-per-minute
   slugc-bamboo-compiles-per-minute
   slugc-cedar-compiles-per-minute
   slugc-mean-stow-time
   slugc-mean-release-time
   slugc-stow-errors-per-minute
   slugc-release-errors-per-minute
   slugc-mean-compile-time

   ; data
   shen-errors-per-minute

   ; internal
   pulse-events-per-second
   ])

(defn non-defaults [stat]
  (for [cloud (conf/clouds)
        :when (not= cloud (conf/default-cloud))
        :let [scoped-name (scope-stat cloud (:name stat))
              stat-sym (symbol (string/replace scoped-name "." "-"))]
        :when (ns-resolve 'pulse.def stat-sym)]
    @(ns-resolve 'pulse.def stat-sym)))

(def all (apply concat defaults (map non-defaults defaults)))
