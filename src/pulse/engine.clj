(ns pulse.engine
  (:require [clojure.string :as str])
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse]))

(set! *warn-on-reflection* true)

(defn log [msg & args]
  (apply util/log (str "engine " msg) args))

(def rd
  (redis/init {:url conf/redis-url}))

(def publish-queue
  (queue/init 100))

(def process-queue
  (queue/init 10000))

(defn safe-inc [n]
  (inc (or n 0)))

(def calcs
  (atom []))

(def ticks
  (atom []))

(defn init-stat [s-key calc tick]
  (log "init_stat stat-key=%s" s-key)
  (swap! calcs conj calc)
  (swap! ticks conj tick))

(defn init-count-stat [s-key v-time b-time t-fn]
  (let [sec-counts-a (atom [0 {}])]
    (init-stat s-key
      (fn [evt]
        (if (t-fn evt)
          (swap! sec-counts-a
            (fn [[sec sec-counts]]
              [sec (util/update sec-counts sec safe-inc)]))))
      (fn []
        (let [[sec sec-counts] @sec-counts-a
              count (reduce + (vals sec-counts))
              normed (long (/ count (/ b-time v-time)))]
          (queue/offer publish-queue ["stats" [s-key normed]])
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
          (queue/offer publish-queue ["stats" [s-key normed]])
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
          (queue/offer publish-queue ["stats" [s-key normed]])
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
          (queue/offer publish-queue ["stats" [s-key v]]))))))

(defn init-stats []
  (log "init_stats")

  (init-count-sec-stat "events_per_second"
    (fn [evt] true))

  (init-count-top-stat "events_by_aorta_host_per_second" 1 10 14
    (fn [evt] true)
    (fn [evt] (:aorta_host evt)))

  (init-count-top-sec-stat "events_by_level_per_second"
    (fn [evt] (and (= (:cloud evt) "heroku.com") (:level evt)))
    (fn [evt] (:level evt)))

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
                   (:ps_watch evt)
                   (= (:event evt) "start"))))

  (init-count-min-stat "ps_returns_per_minute"
    (fn [evt] (and (= (:cloud evt) "heroku.com")
                   (:ps_watch evt)
                   (= (:event evt) "return"))))

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

(defn parse [line aorta-host]
  (if-let [evt (parse/parse-line line)]
    (assoc evt :line line :aorta_host aorta-host :parsed true)
    {:line line :aorta_host aorta-host :parsed false}))

(defn calc [evt]
  (doseq [calc @calcs]
    (calc evt)))

(defn init-ticker []
  (log "init_ticker")
  (let [start (util/millis)]
    (util/spawn-tick 1000
      (fn []
        (doseq [tick @ticks] (tick))))))

(defn init-processors []
  (log "init_processors")
  (dotimes [i 2]
    (log "init_processor index=%d" i)
    (util/spawn-loop
      (fn []
        (let [[line forwarder] (queue/take process-queue)]
          (calc (parse line forwarder)))))))

(defn init-publishers []
  (log "init_publishers")
  (dotimes [i 8]
    (log "init_publisher index=%d" i)
    (util/spawn-loop
      (fn []
        (let [[ch msg] (queue/take publish-queue)]
          (redis/publish rd ch (json/generate-string msg)))))))

(defn init-watcher []
  (log "init_watcher")
  (let [start (util/millis)
        process-popped-prev (atom 0)
        publish-popped-prev (atom 0)]
    (util/spawn-tick 1000
      (fn []
        (let [elapsed (-> (util/millis) (- start) (/ 1000.0))
              [process-depth process-pushed process-popped process-dropped] (queue/stats process-queue)
              [publish-depth publish-pushed publish-popped publish-dropped] (queue/stats publish-queue)
              process-rate (- process-popped @process-popped-prev)
              publish-rate (- publish-popped @publish-popped-prev)]
          (swap! process-popped-prev (constantly process-popped))
          (swap! publish-popped-prev (constantly publish-popped))
          (log "watch elapsed=%.3f process_depth=%d process_pushed=%d process_popped=%d process_dropped=%d process_rate=%d publish_depth=%d publish_pushed=%d publish_popped=%d publish_dropped=%d publish_rate=%d"
            elapsed process-depth process-pushed process-popped process-dropped process-rate
                    publish-depth publish-pushed publish-popped publish-dropped publish-rate))))))

(defn init-bleeders []
  (log "init_bleeders")
  (doseq [aorta-url conf/aorta-urls]
    (let [{aorta-host :host} (util/url-parse aorta-url)]
      (log "init_bleeder aorta_host=%s" aorta-host)
      (util/spawn (fn []
         (pipe/bleed-lines aorta-url
           (fn [line]
             (queue/offer process-queue [line aorta-host]))))))))

(defn -main []
  (init-stats)
  (init-ticker)
  (init-watcher)
  (init-publishers)
  (init-processors)
  (init-bleeders))
