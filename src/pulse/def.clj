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

(def ^:dynamic *cloud* nil)

(defn cloud-scoped-pred [pred-fn cloud]
  (if cloud
    (fn [evt]
      (and (= (:cloud evt) cloud) (pred-fn evt)))
    pred-fn))

(defn max [time-buffer pred-fn val-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
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
         [recent-windows recent-max]))}))

(defn mean [time-buffer pred-fn val-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
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
         [recent-windows recent-mean]))}))

(defn rate [time-unit time-buffer pred-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
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
         [recent-windows complete-rate]))}))

(defn per-second [pred-fn]
  (rate 1 10 pred-fn))

(defn per-minute [pred-fn]
  (rate 60 70 pred-fn))

(defn rate-by-key [time-unit time-buffer pred-fn key-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
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
         [recent-windows complete-rates]))}))

(defn per-second-by-key [pred-fn key-fn]
  (rate-by-key 1 10 pred-fn key-fn))

(defn per-minute-by-key [pred-fn key-fn]
  (rate-by-key 60 70 pred-fn key-fn))

(defn rate-unique [time-buffer pred-fn key-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
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
         [recent-windows recent-count]))}))

(defn per-minute-unique [pred-fn key-fn]
  (rate-unique 60 pred-fn key-fn))

(defn last [pred-fn val-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
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
       [last-val last-val])}))

(defn last-agg [recent-interval pred-fn part-fn val-fn agg-fn]
  (let [pred-fn (cloud-scoped-pred pred-fn *cloud*)]
    {:receive-init
     (fn [] {})
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
         [recent-timed-vals recent-agg]))}))

(defn last-mean [recent-interval pred-fn part-fn val-fn]
  (last-agg recent-interval pred-fn part-fn val-fn coll-mean))

(defn last-sum [pred-fn part-fn val-fn & [recent-interval]]
  (let [recent-interval (or recent-interval 300)]
    (last-agg recent-interval pred-fn part-fn val-fn coll-sum)))

(defn last-count [pred-fn part-fn cnt-fn & [recent-interval]]
  (let [recent-interval (or recent-interval 300)]
    (last-sum pred-fn part-fn (fn [evt] (if (cnt-fn evt) 1 0)) recent-interval)))

;; all stats
(def all (atom []))

;; for stats which apply across clouds
(defmacro defstat-single [stat-name stat-body]
  `(swap! all conj (merge ~stat-body {:name (name '~stat-name)})))

(defn scope-stat [cloud stat-name]
  (if (= cloud (conf/default-cloud))
    (name stat-name)
    (str (string/replace cloud #"\.com" "") "." (name stat-name))))

(defmacro defstat [stat-name stat-body]
  `(do
     ~@(for [cloud (conf/clouds)
             :let [scoped-name (scope-stat cloud stat-name)
                   scoped-var-name (symbol (string/replace scoped-name "." "-"))]]
         `(swap! all conj (assoc (binding [*cloud* ~cloud] ~stat-body)
                            :name ~(str (conf/graphite-prefix) scoped-name))))))

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

;; (defstat hermes-elevated-route-lookups-per-minute
;;   (per-minute
;;     (fn [evt] (and (hermes-request? evt) (kv? evt :code "OK") (>=? evt :route 2.0)))))

;; (defstat hermes-slow-route-lookups-per-minute
;;   (per-minute
;;     (fn [evt] (and (hermes-request? evt) (kv? evt :code "OK") (>=? evt :route 10.0)))))

;; (defstat hermes-catastrophic-route-lookups-per-minute
;;   (per-minute
;;     (fn [evt] (and (hermes-request? evt) (kv? evt :code "OK") (>=? evt :route 100.0)))))

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

; internal

(defstat-single pulse-events-per-second
  (per-second
    (fn [evt] (and (kv? evt :app "pulse") (kv? evt :deploy (conf/deploy))))))
