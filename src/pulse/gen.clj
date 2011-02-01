(ns pulse.gen
  (:require [clojure.string :as str])
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.config :as config])
  (:require [pulse.util :as util])
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse])
  (:require [pulse.esper :as esper])
  (:import java.util.concurrent.ArrayBlockingQueue))

(set! *warn-on-reflection* true)

(def service
  (esper/init-service))

(def rd
  (redis/init {:url config/redis-url}))

(def publish-queue
  (ArrayBlockingQueue. 100))

(def publish-dropped
  (atom 0))

(defn init-publisher []
  (util/spawn
    (fn []
      (util/log "gen init_publisher")
      (loop []
        (let [[k v] (.take ^ArrayBlockingQueue publish-queue)]
           (util/log "gen publish pub_key=stats stats_key=%s stats_val='%s'" k v)
           (redis/publish rd "stats" (json/generate-string [k v])))
        (recur)))))

(defn publish [k v]
  (if-not (.offer ^ArrayBlockingQueue publish-queue [k v])
    (swap! publish-dropped inc)))

(defn add-sec-count-query [name conds]
  (esper/add-query service
    (str "select rate(10) as rt
          from hevent
          where " conds "
          output snapshot every 1 second")
    (fn [[evt] _]
      (if-let [rt (get evt "rt")]
        (publish name (long rt))))))

(defn add-min-count-query [name conds]
  (esper/add-query service
    (str "select rate(60) as rt
          from hevent
          where " conds "
          output snapshot every 1 second")
    (fn [[evt] _]
      (if-let [rt (get evt "rt")]
        (publish name (long (* rt 60.0)))))))

(defn add-last-count-query [name conds attr]
  (esper/add-query service
    (str "select cast(lastever(" attr "?),long) as count
          from hevent
          where " conds "
          output first every 1 second")
      (fn [[evt] _]
        (publish name (get evt "count")))))

(defn add-queries []
  (util/log "gen add_queries")

  (add-sec-count-query "events_per_second"
    "true")

  (add-sec-count-query "events_internal_per_second"
    "((parsed? = true) and (cloud? = 'heroku.com'))")

  (add-sec-count-query "events_external_per_second"
    "((parsed? = true) and (cast(cloud?,string) != 'heroku.com'))")

  (add-sec-count-query "events_unparsed_per_second"
    "(parsed? = false)")

  (add-sec-count-query "nginx_requests_per_second"
    "((event_type? = 'nginx_access') and
      (cast(http_host?,string) != '127.0.0.1'))")

  (add-min-count-query "nginx_errors_per_minute"
    "(event_type? = 'nginx_error')")

  (doseq [s ["500" "502" "503" "504"]]
    (add-min-count-query (str "nginx_" s "_per_minute")
      (str "((event_type? = 'nginx_access') and
             (cast(http_status?,long) = " s "))")))

  (add-sec-count-query "varnish_requests_per_second"
    "(event_type? = 'varnish_access')")

  (add-sec-count-query "hermes_requests_per_second"
    "((event_type? = 'hermes_access') and exists(domain?))")

  (doseq [e ["H10" "H11" "H12" "H13" "H99"]]
    (add-min-count-query (str "hermes_" e "_per_minute")
      (str "((event_type? = 'hermes_access') and
             (Error? = true) and
             (" e "? = true))")))

  (add-sec-count-query "ps_converges_per_second"
    "(converge_service? = true)")

  (add-min-count-query "ps_run_requests_per_minute"
    "((amqp_publish? = true) and
      (cast(exchange?,string) regexp '(ps\\.run|service\\.needed).*'))")

  (add-min-count-query "ps_stop_requests_per_minute"
    "((amqp_publish? = true) and
      (cast(exchange?,string) regexp 'ps\\.kill.*'))")

  (add-min-count-query "ps_kill_requests_per_minute"
    "((railgun_service? = true) and
      (ps_kill? = true) and
      (reason? = 'load'))")

  (add-min-count-query "ps_runs_per_minute"
    "((railgun_ps_watch? = true) and (invoke_ps_run? = true))")

  (add-min-count-query "ps_returns_per_minute"
    "((railgun_ps_watch? = true) and (handle_ps_return? = true))")

  (add-min-count-query "ps_traps_per_minute"
    "((railgun_ps_watch? = true) and (trap_exit? = true))")

  (add-last-count-query "ps_lost"
    "(process_lost? = true)"
    "total_count")

  (doseq [[k p] {"invokes" "(invoke? = true)"
                 "fails"   "((compile_error? = true) or (locked_error? = true))"
                 "errors"  "((publish_error? = true) or (unexpected_error? = true))"}]
    (add-min-count-query (str "slugc_" k "_per_minute")
      (str "((slugc_bin? = true) and " p ")"))))

(def submit-queue
  (ArrayBlockingQueue. 20000))

(def submit-dropped
  (atom 0))

(defn init-submitter []
  (dotimes [i 2]
    (util/spawn
      (fn []
        (util/log "gen init_submitter index=%d" i)
        (loop []
          (let [line (.take ^ArrayBlockingQueue submit-queue)]
            (if-let [evt (parse/parse-line line)]
              (esper/send-event service (assoc evt "line" line "parsed" true))
              (esper/send-event service {"line" line "parsed" false}))
            (recur)))))))

(def tail-read
  (atom 0))

(defn add-tails []
  (util/log "gen add_tails")
  (doseq [forwarder config/forwarders]
    (util/spawn (fn []
      (util/log "gen add_tail forwarder=%s" forwarder)
       (pipe/shell-lines ["ssh" (str "ubuntu@" forwarder) "sudo" "tail" "-f" "/var/log/heroku/US/Pacific/log"]
         (fn [line]
           (swap! tail-read inc)
           (if-not (.offer ^ArrayBlockingQueue submit-queue line)
             (swap! submit-dropped inc))))))))

(defn init-watcher []
  (let [start (System/currentTimeMillis)]
    (util/spawn
      (fn []
        (util/log "gen init_watcher")
        (loop []
          (Thread/sleep 1000)
          (let [elapsed (/ (- (System/currentTimeMillis) start) 1000.0)
                submit-depth  (.size ^ArrayBlockingQueue submit-queue)
                publish-depth (.size ^ArrayBlockingQueue publish-queue)]
            (util/log "gen watcher elapsed=%.3f tail_read=%d submit_depth=%d submit_dropped=%d publish_depth=%d publish_dropped=%d"
              elapsed @tail-read submit-depth @submit-dropped publish-depth @publish-dropped)
            (recur)))))))

(defn -main []
  (init-watcher)
  (init-publisher)
  (init-submitter)
  (add-queries)
  (add-tails))
