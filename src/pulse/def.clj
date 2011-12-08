(ns pulse.def
  (:refer-clojure :exclude [last max])
  (:require [clojure.set :as set]
            [pulse.util :as util]
            [pulse.conf :as conf]))

(defn safe-inc [n]
  (inc (or n 0)))

(defn sum [c]
  (reduce + c))

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
     (fn [[window-start window-count window-sum :as receive-buffer] event]
       (if (pred-fn event)
         [window-start (inc window-count) (+ window-sum (val-fn event))]
         receive-buffer))
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
             recent-count (sum (map (fn [[_ window-count _]] window-count) recent-windows))
             recent-sum (sum (map (fn [[_ _ window-sum]] window-sum) recent-windows))
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
             complete-count (sum (map (fn [[_ _ window-count]] window-count) complete-windows))
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

(defn last-sum [pred-fn part-fn val-fn & [recent-interval]]
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
             recent-timed-vals (into {} (filter (fn [[_ [last-time _]]] (< (- now last-time) (* (or recent-interval 300) 1000))) last-timed-vals))
             recent-sum (sum (map (fn [[_ [_ last-val]]] last-val) recent-timed-vals))]
         [recent-timed-vals recent-sum]))})

(defn last-count [pred-fn part-fn cnt-fn & [recent-interval]]
  (last-sum pred-fn part-fn (fn [evt] (if (cnt-fn evt) 1 0)) recent-interval))

(defmacro defstat [stat-name stat-body]
  (let [stat-name-str (name stat-name)]
    `(def ~stat-name (merge ~stat-body {:name (name ~stat-name-str)}))))

(defn kv? [m k v]
  (= (k m) v))

(defn k? [evt k]
  (contains? evt k))

(defn cont? [evt k v]
  (.contains (or (k evt) "") v))

(defn >=? [evt k v]
  (>= (k evt) v))

(defn cloud? [evt]
  (kv? evt :cloud (conf/cloud)))

; global

(defstat events-per-second
  (per-second (constantly true)))

(defstat events-per-second-by-source
  (per-second-by-key (constantly true) :source))

(defstat events-per-second-unparsed
  (per-second :unparsed))

(defstat amqp-publishes-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (or (k? evt :amqp_publish) (and (k? evt :amqp_message) (kv? evt :action "publish")))))))

(defstat amqp-receives-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (k? evt :amqp_message) (kv? evt :action "received")))))

(defstat amqp-timeouts-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :amqp_message) (kv? evt :action "timeout")))))

(defstat amqp-publishes-per-second-by-exchange
  (per-second-by-key
    (fn [evt] (and (cloud? evt) (k? evt :amqp_publish)))
    :exchange))

(defstat amqp-receives-per-second-by-exchange
  (per-second-by-key
    (fn [evt] (and (cloud? evt) (k? evt :amqp_message) (kv? evt :action "received")))
    :exchange))

(defstat amqp-timeouts-per-minute-by-exchange
  (per-minute-by-key
    (fn [evt] (and (cloud? evt) (k? evt :amqp_message) (kv? evt :action "timeout")))
    :exchange))

; routing

(defn nginx-request? [evt]
  (and (and (cloud? evt) (kv? evt :source "nginx") (k? evt :http_status))))

(defstat nginx-requests-per-second
  (per-second nginx-request?))

(defstat nginx-requests-per-second-by-domain
  (per-second-by-key nginx-request? :http_domain))

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
  (and (cloud? evt)
       (kv? evt :source "nginx")
       (kv? evt :level "crit")
       (cont? evt :msg "[error]")))

(defstat nginx-errors-per-minute
  (per-minute nginx-error?))

(defstat nginx-errors-instances-per-minute
  (per-minute-unique nginx-error? :instance_id))

(defn varnish-request? [evt]
  (and (cloud? evt) (kv? evt :source "varnish") (k? evt :http_status)))

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
     (fn [evt] (and (cloud? evt) (k? evt :cache_purge)))))

(defstat rendezvous-joins-per-minute
  (per-minute
    (fn [evt] (and (kv? evt :app "rendezvous") (kv? evt :fn "join") (kv? evt :at "start")))))

(defstat rendezvous-rendezvous-per-minute
  (per-minute
    (fn [evt] (and (kv? evt :app "rendezvous") (kv? evt :fn "join") (kv? evt :at "rendezvous")))))

(defn hermes-request? [evt]
  (and (cloud? evt) (k? evt :hermes_proxy)))

(defstat hermes-requests-per-second
  (per-second hermes-request?))

(defstat hermes-requests-apps-per-minute
  (per-minute-unique hermes-request? :app_id))

(defstat hermes-requests-per-second-by-app-id
  (per-second-by-key hermes-request? :app_id))

(defstat hermes-requests-per-second-by-instance-id
  (per-second-by-key hermes-request? :app_id))

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

(defstat hermes-h99-apps-per-minute
  (hermes-apps-per-minute "H99"))

(defstat hermes-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :level "err") (kv? evt :source "hermes")))))

(defstat hermes-lockstep-updates-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :services_callback) (k? evt :txid)))))

(defstat hermes-lockstep-connections-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :services_callback) (kv? evt :event "connect")))))

(defstat hermes-lockstep-disconnections-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :services_callback ) (kv? evt :event "disconnect")))))

(defstat hermes-lockstep-mean-latency
  (mean 70
    (fn [evt] (and (cloud? evt) (k? evt :hermes_clock) (k? evt :STATS) (k? evt :last_service_latency)))
    :last_service_latency))

(defstat hermes-lockstep-max-latency
  (max 70
    (fn [evt] (and (cloud? evt) (k? evt :hermes_clock) (k? evt :STATS) (k? evt :last_service_latency)))
    :last_service_latency))

(defstat hermes-lockstep-mean-stillness
  (mean 70
    (fn [evt] (and (cloud? evt) (k? evt :hermes_clock) (k? evt :STATS) (k? evt :last_service_update)))
    :last_service_update))

(defstat hermes-lockstep-max-stillness
  (max 70
    (fn [evt] (and (cloud? evt) (k? evt :hermes_clock) (k? evt :STATS) (k? evt :last_service_update)))
    :last_service_update))

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
    (fn [evt] (and (hermes-request? evt) (k? evt :redis) (>=? evt :redis 10.0)))))

(defstat hermes-catastrophic-redis-lookups-per-minute
  (per-minute
    (fn [evt] (and (hermes-request? evt) (k? evt :redis) (>=? evt :redis 25.0)))))

(defstat hermes-processes-last
  (last-sum
    (fn [evt] (and (cloud? evt) (k? evt :hermes_clock) (k? evt :STATS)))
    :instance_id
    :processes))

(defstat hermes-ports-last
  (last-sum
    (fn [evt] (and (cloud? evt) (k? evt :hermes_clock) (k? evt :STATS)))
    :instance_id
    :ports))

; runtime

(defstat railgun-unhandled-exceptions-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :railgun) (k? evt :exception) (not (k? evt :site)) (not (k? evt :reraise))))))

(defstat ps-up-total-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :up))

(defstat ps-up-web-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :web))

(defstat ps-up-worker-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :worker))

(defstat ps-up-other-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :other))

(defstat ps-created-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :created))

(defstat ps-starting-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :starting))

(defstat ps-idles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "up_to_up") (kv? evt :event "idle")))))

(defstat ps-unidles-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "unidle") (kv? evt :block "begin")))))

(defstat ps-crashed-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :crashed))

(defstat ps-running-total-last
  (last-sum
    (fn [evt] (and (cloud? evt) (k? evt :railgun) (k? evt :counts) (kv? evt :key "total")))
    :instance_id
    :num))

(defstat ps-running-web-last
  (last-sum
    (fn [evt] (and (cloud? evt) (k? evt :railgun) (k? evt :counts) (kv? evt :key "process_type") (kv? evt :process_type "web")))
    :instance_id
    :num))

(defstat ps-running-worker-last
  (last-sum
    (fn [evt] (and (cloud? evt) (k? evt :railgun) (k? evt :counts) (kv? evt :key "process_type") (kv? evt :process_type "worker")))
    :instance_id
    :num))

(defstat ps-running-other-last
  (last-sum
    (fn [evt] (and (cloud? evt) (k? evt :railgun) (k? evt :counts) (kv? evt :key "process_type") (kv? evt :process_type "other")))
    :instance_id
    :num))

(defstat ps-run-requests-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :amqp_message) (kv? evt :action "publish") (kv? evt :exchange "ps.run")))))

(defstat ps-runs-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :ps_watch) (k? evt :ps_run) (kv? evt :at "start")))))

(defstat ps-returns-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :ps_watch) (k? evt :ps_run) (kv? evt :at "exit")))))

(defstat ps-stop-requests-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :amqp_message) (kv? evt :action "publish") (k? evt :exchange) (cont? evt :exchange "ps.kill.")))))

(defstat ps-stops-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :ps_watch) (k? evt :trap_exit)))))

(defstat ps-converges-per-second
  (per-second
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "transition") (kv? evt :block "begin")))))

(defstat ps-timeouts-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :monitor_boot) (kv? evt :at "timeout")))))

(defstat ps-launch-time-mean
  (mean 60
    (fn [evt] (and (cloud? evt) (k? evt :monitor_boot) (kv? evt :at "responsive")))
    :age))

(defstat ps-lost-last
  (last
    (fn [evt] (and (cloud? evt) (kv? evt :source "psmgr") (kv? evt :function "counts") (kv? evt :event "emit")))
    :lost))

(defstat psmgr-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :level "err") (kv? evt :source "psmgr")))))

; packaging

(defstat gitproxy-connections-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :run) (kv? evt :at "start")))))

(defstat gitproxy-invalids-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :run) (kv? evt :at "invalid")))))

(defstat gitproxy-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :run) (kv? evt :at "exception") (not (k? evt :reraise))))))

(defstat gitproxy-successes-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :run) (kv? evt :at "success")))))

(defstat gitproxy-mean-metadata-time
  (mean 60
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :fetch_push_metadata) (kv? evt :at "finish")))
    :elapsed))

(defstat gitproxy-mean-provision-time
  (mean 60
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :fetch_ssh_info) (kv? evt :backend "codon") (kv? evt :at "finish")))
    :elapsed))

(defstat gitproxy-mean-service-time
  (mean 60
    (fn [evt] (and (cloud? evt) (k? evt :gitproxy) (k? evt :run) (kv? evt :at "finish")))
    :elapsed))

(defstat codon-launches-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :monitor_processes) (kv? evt :at "launch")))))

(defstat codon-receives-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :receive) (kv? evt :at "dequeue") (not (kv? evt :timeout true))))))

(defstat codon-exits-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :receive) (kv? evt :at "exit")))))

(defstat codon-cycles-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :receive) (kv? evt :at "cycle")))))

(defstat codon-up-last
  (last-count
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :spawn_heartbeat) (kv? evt :at "emit")))
    :hostname
    (constantly true)
    3))

(defstat codon-busy-last
  (last-count
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :spawn_heartbeat) (kv? evt :at "emit")))
    :hostname
    (fn [evt] (kv? evt :busy true))
    3))

(defstat codon-compiling-last
  (last-count
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :spawn_heartbeat) (kv? evt :at "emit")))
    :hostname
    (fn [evt] (kv? evt :compiling true))
    3))

(defstat codon-mean-fetch-time
  (mean 60
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :fetch_repo) (kv? evt :at "finish")))
    :elapsed))

(defstat codon-mean-stow-time
  (mean 60
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :stow_repo) (kv? evt :at "finish")))
    :elapsed))

(defstat codon-fetch-errors-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :fetch_repo) (kv? evt :at "error")))))

(defstat codon-stow-errors-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :stow_repo) (kv? evt :at "finish") (not (kv? evt :exit_status 0)) (not (kv? evt :out "200"))))))

(defstat codon-mean-service-time
  (mean 60
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :await) (kv? evt :at "finish") (k? evt :service_elapsed)))
    :service_elapsed))

(defstat codon-mean-age
  (mean 60
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :spawn_heartbeat) (kv? evt :at "emit")))
    :age))

(defstat codon-unhandled-exceptions-per-minute
  (per-minute
    (fn [evt] (and (k? evt :codon) (k? evt :production) (k? evt :exception) (not (k? evt :site)) (not (k? evt :reraise))))))

(defn slugc? [evt]
  (and (cloud? evt) (k? evt :slugc)))

(defstat slugc-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "start")))))

(defstat slugc-aspen-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "start") (kv? evt :major_stack "aspen")))))

(defstat slugc-bamboo-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "start") (kv? evt :major_stack "bamboo")))))

(defstat slugc-cedar-compiles-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "start") (kv? evt :major_stack "cedar")))))

(defstat slugc-failures-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "fail")))))

(defstat slugc-errors-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "error")))))

(defstat slugc-successes-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "finish")))))

(defstat slugc-mean-stow-time
  (mean 60
    (fn [evt] (and (slugc? evt) (k? evt :store_in_s3) (kv? evt :event "finish")))
    :elapsed))

(defstat slugc-mean-release-time
  (mean 60
    (fn [evt] (and (slugc? evt) (k? evt :post_release) (kv? evt :event "finish")))
    :elapsed))

(defstat slugc-stow-errors-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :store_in_s3) (kv? evt :event "error")))))

(defstat slugc-release-errors-per-minute
  (per-minute
    (fn [evt] (and (slugc? evt) (k? evt :post_release) (kv? evt :event "error")))))

(defstat slugc-mean-compile-time
  (mean 60
    (fn [evt] (and (slugc? evt) (k? evt :bin) (kv? evt :event "finish")))
    :elapsed))

(defstat codex-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :level "err") (kv? evt :source "codex")))))

; api

(defstat releases-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (k? evt :capture_release)))))

(defstat api-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :source "core") (k? evt :api_error)))))

; data

(defstat shen-errors-per-minute
  (per-minute
    (fn [evt] (and (cloud? evt) (kv? evt :level "err") (kv? evt :source "shen")))))

(def all
  [
  ; global
   events-per-second
   events-per-second-by-source
   events-per-second-unparsed
   amqp-publishes-per-second
   amqp-receives-per-second
   amqp-timeouts-per-minute
   amqp-publishes-per-second-by-exchange
   amqp-receives-per-second-by-exchange
   amqp-timeouts-per-minute-by-exchange

   ; routing
   nginx-requests-per-second
   nginx-requests-per-second-by-domain
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
   hermes-requests-per-second-by-app-id
   hermes-requests-per-second-by-instance-id
   hermes-h10-per-minute
   hermes-h11-per-minute
   hermes-h12-per-minute
   hermes-h13-per-minute
   hermes-h14-per-minute
   hermes-h99-per-minute
   hermes-h10-apps-per-minute
   hermes-h11-apps-per-minute
   hermes-h12-apps-per-minute
   hermes-h13-apps-per-minute
   hermes-h14-apps-per-minute
   hermes-h99-apps-per-minute
   hermes-errors-per-minute
   hermes-lockstep-updates-per-minute
   hermes-lockstep-connections-per-minute
   hermes-lockstep-disconnections-per-minute
   hermes-lockstep-mean-latency
   hermes-lockstep-max-latency
   hermes-lockstep-mean-stillness
   hermes-lockstep-max-stillness
   hermes-elevated-route-lookups-per-minute
   hermes-slow-route-lookups-per-minute
   hermes-catastrophic-route-lookups-per-minute
   hermes-slow-redis-lookups-per-minute
   hermes-catastrophic-redis-lookups-per-minute
   hermes-processes-last
   hermes-ports-last

   ; runtime
   railgun-unhandled-exceptions-per-minute
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
   ps-launch-time-mean
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
   ps-lost-last
   psmgr-errors-per-minute

   ; packaging
   gitproxy-connections-per-minute
   gitproxy-invalids-per-minute
   gitproxy-errors-per-minute
   gitproxy-successes-per-minute
   gitproxy-mean-metadata-time
   gitproxy-mean-provision-time
   gitproxy-mean-service-time
   codon-launches-per-minute
   codon-receives-per-minute
   codon-exits-per-minute
   codon-cycles-per-minute
   codon-up-last
   codon-busy-last
   codon-compiling-last
   codon-mean-fetch-time
   codon-mean-stow-time
   codon-fetch-errors-per-minute
   codon-stow-errors-per-minute
   codon-mean-service-time
   codon-mean-age
   codon-unhandled-exceptions-per-minute
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
   codex-errors-per-minute

   ; api
   releases-per-minute
   api-errors-per-minute

   ; data
   shen-errors-per-minute
   ])
