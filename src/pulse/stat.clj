(ns pulse.stat
  (:require [clojure.string :as str])
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse]))

(set! *warn-on-reflection* true)

(def rd
  (redis/init {:url conf/redis-url}))

(def publish-queue
  (queue/init 100))

(def process-queue
  (queue/init 20000))

(defn update [m k f]
  (assoc m k (f (get m k))))

(defn safe-inc [n]
  (inc (or n 0)))

(def calcs
  (atom []))

(def ticks
  (atom []))

(defn init-stat [s-key calc tick]
  (util/log "stat init_stat stat-key=%s" s-key)
  (swap! calcs conj calc)
  (swap! ticks conj tick))

(defn init-count-stat [s-key v-time b-time t-fn]
  (let [sec-counts-a (atom [0 {}])]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (swap! sec-counts-a
            (fn [[sec sec-counts]]
              [sec (update sec-counts sec safe-inc)]))))
      (fn []
        (let [[sec sec-counts] @sec-counts-a
              count (reduce + (vals sec-counts))
              normed (long (/ count (/ b-time v-time)))]
          (queue/offer publish-queue [s-key normed])
          (swap! sec-counts-a
            (fn [[sec sec-counts]]
              [(inc sec) (dissoc sec-counts (- sec b-time))])))))))

(defn init-count-sec-stat [s-key t-fn]
  (init-count-stat s-key 1 10 t-fn))

(defn init-count-min-stat [s-key t-fn]
  (init-count-stat s-key 60 70 t-fn))

(defn init-count-top-stat [s-key v-time b-time k-size t-fn k-fn]
  (let [sec-key-counts-a (atom [0 {}])]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (let [k (k-fn evt)]
            (swap! sec-key-counts-a
              (fn [[sec sec-key-counts]]
                [sec (update-in sec-key-counts [sec k] safe-inc)])))))
      (fn []
        (let [[sec sec-key-counts] @sec-key-counts-a
              counts (apply merge-with + (vals sec-key-counts))
              sorted (sort-by (fn [[k kc]] (- kc)) counts)
              highs  (take k-size sorted)
              normed (map (fn [[k kc]] [k (long (/ kc (/ b-time v-time)))]) highs)]
          (queue/offer publish-queue [s-key normed])
          (swap! sec-key-counts-a
            (fn [[sec sec-key-counts]]
              [(inc sec) (dissoc sec-key-counts (- sec b-time))])))))))

(defn init-count-top-sec-stat [s-key t-fn k-fn]
  (init-count-top-stat s-key 1 10 5 t-fn k-fn))

(defn init-count-top-min-stat [s-key t-fn k-fn]
  (init-count-top-stat s-key 60 70 5 t-fn k-fn))

(defn init-sum-top-stat [s-key v-time b-time k-size t-fn k-fn c-fn]
  (let [sec-key-counts-a (atom [0 {}])]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (let [k (k-fn evt)]
            (swap! sec-key-counts-a
              (fn [[sec sec-key-counts]]
                [sec (update-in sec-key-counts [sec k]
                       (fn [c] (+ (or c 0) (c-fn evt))))])))))
      (fn []
        (let [[sec sec-key-counts] @sec-key-counts-a
              counts (apply merge-with + (vals sec-key-counts))
              sorted (sort-by (fn [[k kc]] (- kc)) counts)
              highs  (take k-size sorted)
              normed (map (fn [[k kc]] [k (long (/ kc (/ b-time v-time)))]) highs)]
          (queue/offer publish-queue [s-key normed])
          (swap! sec-key-counts-a
            (fn [[sec sec-key-counts]]
              [(inc sec) (dissoc sec-key-counts (- sec b-time))])))))))

(defn init-last-stat [s-key t-fn v-fn]
  (let [last-a (atom nil)]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (let [v (v-fn evt)]
            (swap! last-a (constantly v)))))
      (fn []
        (let [v @last-a]
          (queue/offer publish-queue [s-key v]))))))

(defn init-stats []
  (util/log "stat init_stats")

  (init-count-sec-stat "events_per_second"
    (fn [evt] true))

 (init-count-top-stat "events_by_tail_host_per_second" 1 10 14
    (fn [evt] true)
    (fn [evt] (:tail_host evt)))

  (init-count-sec-stat "events_internal_per_second"
    (fn [evt] (= (:cloud evt) "heroku.com")))

  (init-count-sec-stat "events_external_per_second"
    (fn [evt] (and (:cloud evt) (not= (:cloud evt) "heroku.com"))))

  (init-count-sec-stat "events_unparsed_per_second"
    (fn [evt] (not (:parsed evt))))

  (init-count-sec-stat "nginx_requests_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "nginx_access")
                   (not= (:http_host evt) "127.0.0.1"))))

  (init-count-top-sec-stat "nginx_requests_by_domain_per_second"
    (fn [evt]  (and (= (:cloud evt) "heroku.com")
                    (= (:event_type evt) "nginx_access")
                    (not= (:http_host evt) "127.0.0.1")))
    (fn [evt] (:http_domain evt)))

  (init-count-min-stat "nginx_errors_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "nginx_error"))))

  (init-count-top-min-stat "nginx_50x_by_domain_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "nginx_access")
                   (not= (:http_host evt) "127.0.0.1")
                   (>= (:http_status evt) 500)))
    (fn [evt] (:http_domain evt)))

 (doseq [s [500 502 503 504]]
    (init-count-min-stat (str "nginx_" s "_per_minute")
      (fn [evt] (and (= (:cloud evt) "heroku.com")
                     (= (:event_type evt) "nginx_access")
                     (not= (:http_host evt) "127.0.0.1")
                     (= (:http_status evt) s)))))

  (init-count-sec-stat "varnish_requests_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "varnish_access"))))

  (init-count-sec-stat "hermes_requests_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (= (:event_type evt) "hermes_access")
                   (:domain evt))))

  (doseq [e ["H10" "H11" "H12" "H13" "H99"]]
    (init-count-min-stat (str "hermes_" e "_per_minute")
      (fn [evt] (and (= (:cloud evt) "heroku.com")
                     (= (:event_type evt) "hermes_access")
                     (:Error evt)
                     (get evt (keyword e))))))

  (init-count-sec-stat "ps_converges_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:converge_service evt))))

  (init-count-min-stat "ps_run_requests_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt)
                   (:exchange evt)
                   (re-find #"(ps\.run)|(service\.needed)" (:exchange evt)))))

  (init-count-min-stat "ps_stop_requests_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt)
                   (:exchange evt)
                   (re-find #"ps\.kill\.\d+" (:exchange evt)))))

  (init-count-min-stat "ps_kll_requests_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_service evt)
                   (:ps_kill evt)
                   (= (:reason evt) "load"))))

  (init-count-min-stat "ps_runs_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_ps_watch evt)
                   (:invoke_ps_run evt))))

  (init-count-min-stat "ps_returns_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_ps_watch evt)
                   (:handle_ps_return evt))))

  (init-count-min-stat "ps_traps_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:railgun_ps_watch evt)
                   (:trap_exit evt))))

  (init-last-stat "ps_lost"
    (fn [evt] (:process_lost evt))
    (fn [evt] (:total_count evt)))

  (doseq [[k p] [["invokes" (fn [evt] (:invoke evt))]
                 ["fails"   (fn [evt] (or (:compile_error evt)
                                          (:locked_error evt)))]
                 ["errors"  (fn [evt] (or (:publish_error evt)
                                          (:unexpected_error evt)))]]]
    (init-count-min-stat (str "slugc_" k "_per_minute")
      (fn [evt] (and (= (:cloud evt) "heroku.com")
                     (:slugc_bin evt)
                     (p evt)))))

  (init-count-sec-stat "amqp_publishes_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt))))

  (init-count-sec-stat "amqp_receives_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "received"))))

  (init-count-min-stat "amqp_timeouts_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "timeout"))))

  (init-count-top-sec-stat "amqp_publishes_by_exchange_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_publish evt)))
    (fn [evt] (:exchange evt)))

  (init-count-top-sec-stat "amqp_receives_by_exchange_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "received")))
    (fn [evt] (:exchange evt)))

  (init-count-top-min-stat "amqp_timeout_by_exchange_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:amqp_message evt)
                   (= (:action evt) "timeout")))
    (fn [evt] (:exchange evt)))

  (init-sum-top-stat "logs_by_app_per_second" 1 70 5
    (fn [evt] (and (= (:event_type evt) "logplex")
                   (:logplex_channel_stats evt)))
    (fn [evt] (:app_id evt))
    (fn [evt] (:message_processed evt))))

(defn parse [line tail-host]
  (if-let [evt (parse/parse-line line)]
    (assoc evt :line line :tail_host tail-host :parsed true)
    {:line line :tail_host tail-host :parsed false}))

(defn calc [evt]
  (doseq [calc @calcs]
    (calc evt)))

(defn init-ticker []
  (util/log "stat init_ticker")
  (let [start (System/currentTimeMillis)]
    (util/spawn-tick 1000
      (fn []
        (util/log "stat tick elapsed=%d" (- (System/currentTimeMillis) start))
        (doseq [tick @ticks]
          (tick))
        (queue/offer publish-queue ["render" true])))))

(defn init-tailers []
  (util/log "stat init_tailers")
  (doseq [tail-host conf/forwarder-hosts]
    (util/log "stat init_tailer type=syslog tail_host=%s" tail-host)
    (util/spawn (fn []
       (pipe/shell-lines ["ssh" (str "ubuntu@" tail-host) "sudo" "tail" "-n" "0" "-f" "/var/log/heroku/US/Pacific/log"]
         (fn [line]
           (queue/offer process-queue [line tail-host]))))))
  (doseq [tail-host conf/logplex-hosts]
    (util/log "stat init_tailer type=logplex tail_host=%s" tail-host)
    (util/spawn (fn []
       (pipe/shell-lines ["ssh" (str "root@" tail-host) "tail" "-n" "0" "-f" "/var/log/logplex"]
         (fn [line]
           (queue/offer process-queue [line tail-host])))))))

(defn init-processors []
  (util/log "stat init_processors")
  (dotimes [i 2]
    (util/log "stat init_processor index=%d" i)
    (util/spawn-loop
      (fn []
        (let [[line forwarder] (queue/take process-queue)]
          (calc (parse line forwarder)))))))

(defn init-publishers []
  (util/log "stat init_publishers")
  (dotimes [i 8]
    (util/log "stat init_publisher index=%d" i)
    (util/spawn-loop
      (fn []
        (let [[k v] (queue/take publish-queue)]
           (util/log "stat publish pub_key=stats stat-key=%s" k)
           (redis/publish rd "stats" (json/generate-string [k v])))))))

(defn init-watcher []
  (util/log "stat init_watcher")
  (let [start (System/currentTimeMillis)]
    (util/spawn-tick 1000
      (fn []
        (let [elapsed (/ (- (System/currentTimeMillis) start) 1000.0)
              [r-pushed r-dropped r-depth] (queue/stats process-queue)
              [u-pushed u-dropped u-depth] (queue/stats publish-queue)]
          (util/log "stat watch elapsed=%.3f process_pushed=%d process_dropped=%d process_depth=%d publish_pushed=%d publish_dropped=%d publish_depth=%d"
            elapsed r-pushed r-dropped r-depth u-pushed u-dropped u-depth)
          (queue/offer publish-queue ["depth_process" r-depth])
          (queue/offer publish-queue ["depth_publish" u-depth]))))))

(defn -main []
  (init-stats)
  (init-ticker)
  (init-watcher)
  (init-publishers)
  (init-processors)
  (init-tailers))
