(ns pulse.gen
  (:require [clojure.string :as str])
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.config :as config])
  (:require [pulse.util :as util])
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse])
  (:require [pulse.esper :as esper]))

(set! *warn-on-reflection* true)

(def rd
  (redis/init {:url config/redis-url}))

(defn publish [k v]
  (util/log "publish pub_key=stats stats_key=%s" k)
  (redis/publish rd "stats" (json/generate-string [k v])))

(defn add-sec-count-query [service name conds]
  (esper/add-query service
    (str "select count(*)
          from hevent.win:time(10 sec)
          where " conds "
          output every 1 second")
    (fn [[evt] _]
      (publish name (long (/ (get evt "count(*)") 10.0))))))

(defn add-min-count-query [service name conds]
  (esper/add-query service
    (str "select count(*)
          from hevent.win:time(60 sec)
          where " conds "
          output every 1 second")
    (fn [[evt] _]
      (publish name (get evt "count(*)")))))

(defn add-last-count-query [service name conds attr]
  (esper/add-query service
    (str "select cast(lastever(" attr "?),long) as count
          from hevent
          where " conds "
          output first every 1 second")
      (fn [[evt] _]
        (publish name (get evt "count")))))

(defn add-min-top-count-query [service name conds attr]
  (esper/add-query service
    (str "select " attr "? as attr, count(*) as count from hevent.win:time(60 sec)
          where " conds "
          group by " attr "?
          output snapshot every 1 second
          order by count desc
          limit 5")
    (fn [evts _]
      (publish name
        (map (fn [evt] [(get evt "attr") (get evt "count")]) evts)))))

(defn add-sec-top-count-query [service name conds attr]
  (esper/add-query service
    (str "select " attr "? as attr, count(*) as count from hevent.win:time(10 sec)
          where " conds "
          group by " attr "?
          output snapshot every 1 second
          order by count desc
          limit 5")
    (fn [evts _]
      (publish name
        (map (fn [evt] [(get evt "attr") (long (/ (get evt "count") 10.0))]) evts)))))

(defn add-queries [service]
  (util/log "add_queries")

  (add-sec-count-query service "events_per_second"
    "true")

  (add-sec-count-query service "events_internal_per_second"
    "((parsed? = true) and (cloud? = 'heroku.com'))")

  (add-sec-count-query service "events_external_per_second"
    "((parsed? = true) and (cloud? != 'heroku.com'))")

  (add-sec-count-query service "events_unparsed_per_second"
    "(parsed? = false)")

  (add-sec-count-query service "nginx_requests_per_second"
    "((event_type? = 'nginx_access') and (http_host? != '127.0.0.1'))")

  (add-sec-top-count-query service "nginx_requests_by_domain_per_second"
    "((event_type? = 'nginx_access') and (http_host? != '127.0.0.1'))"
    "http_domain")

  (add-min-top-count-query service "nginx_errors_by_domain_per_minute"
    "((event_type? = 'nginx_access') and
      (http_host? != '127.0.0.1') and
      (cast(http_status?,long) >= 500))"
    "http_domain")

  (add-min-count-query service "nginx_errors_per_minute"
    "(event_type? = 'nginx_error')")

  (doseq [s ["500" "502" "503" "504"]]
    (add-min-count-query service (str "nginx_" s "_per_minute")
      (str "((event_type? = 'nginx_access') and
             (cast(http_status?,long) = " s "))")))

  (add-sec-count-query service "varnish_requests_per_second"
    "(event_type? = 'varnish_access')")

  (add-sec-count-query service "hermes_requests_per_second"
    "((event_type? = 'hermes_access') and exists(domain?))")

  (doseq [e ["H10" "H11" "H12" "H13" "H99"]]
    (add-min-count-query service (str "hermes_" e "_per_minute")
      (str "((event_type? = 'hermes_access') and
             (Error? = true) and
             (" e "? = true))")))

  (add-sec-count-query service "ps_converges_per_second"
    "(converge_service? = true)")

  (add-min-count-query service "ps_run_requests_per_minute"
    "((amqp_publish? = true) and
      (cast(exchange?,string) regexp '(ps\\.run|service\\.needed).*'))")

  (add-min-count-query service "ps_stop_requests_per_minute"
    "((amqp_publish? = true) and
      (cast(exchange?,string) regexp 'ps\\.kill.*'))")

  (add-min-count-query service "ps_kill_requests_per_minute"
    "((railgun_service? = true) and
      (ps_kill? = true) and
      (reason? = 'load'))")

  (add-min-count-query service "ps_runs_per_minute"
    "((railgun_ps_watch? = true) and (invoke_ps_run? = true))")

  (add-min-count-query service "ps_returns_per_minute"
    "((railgun_ps_watch? = true) and (handle_ps_return? = true))")

  (add-min-count-query service "ps_traps_per_minute"
    "((railgun_ps_watch? = true) and (trap_exit? = true))")

  (add-last-count-query service "ps_lost"
    "(process_lost? = true)"
    "total_count")

  (doseq [[k p] {"invokes" "(invoke? = true)"
                 "fails"   "((compile_error? = true) or (locked_error? = true))"
                 "errors"  "((publish_error? = true) or (unexpected_error? = true))"}]
    (add-min-count-query service (str "slugc_" k "_per_minute")
      (str "((slugc_bin? = true) and " p ")")))

  (add-min-top-count-query service "amqp_publishes_by_exchange_per_minute"
    "(amqp_publish? = true)"
    "exchange"))

(defn add-tails [service forwarders]
  (util/log "add_tails")
  (doseq [forwarder forwarders]
    (pipe/spawn (fn []
      (util/log "add_tail forwarder=%s" forwarder)
       (pipe/shell-lines ["ssh" (str "ubuntu@" forwarder) "sudo" "tail" "-f" "/var/log/heroku/US/Pacific/log"]
         (fn [line]
           (if-let [evt (parse/parse-line line)]
             (esper/send-event service (assoc evt "line" line "parsed" true "forwarder" forwarder))
             (esper/send-event service {"line" line "parsed" false "forwarder" forwarder}))))))))

(defn -main [& forwarders]
  (let [service (esper/init-service)]
    (add-queries service)
    (add-tails service forwarders)))
