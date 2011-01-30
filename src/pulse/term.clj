(ns pulse.term
  (:require [clojure.string :as str])
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse])
  (:require [pulse.engine :as engine]))

(set! *warn-on-reflection* true)

(defn redraw [snap]
  (printf "\u001B[2J\u001B[f")
  (printf "events/sec       %d\n" (get snap "events_per_second" 0))
  (printf "nginx req/sec    %d\n" (get snap "nginx_requests_per_second" 0))
  (printf "nginx err/min    %d\n" (get snap "nginx_errors_per_minute" 0))
  (printf "nginx 500/min    %d\n" (get snap "nginx_500_per_minute" 0))
  (printf "nginx 502/min    %d\n" (get snap "nginx_502_per_minute" 0))
  (printf "nginx 503/min    %d\n" (get snap "nginx_503_per_minute" 0))
  (printf "nginx 504/min    %d\n" (get snap "nginx_504_per_minute" 0))
  (printf "hermes prx/sec   %d\n" (get snap "hermes_proxies_per_second" 0))
  (printf "hermes H10/min   %d\n" (get snap "hermes_H10_per_minute" 0))
  (printf "hermes H11/min   %d\n" (get snap "hermes_H11_per_minute" 0))
  (printf "hermes H12/min   %d\n" (get snap "hermes_H12_per_minute" 0))
  (printf "hermes H13/min   %d\n" (get snap "hermes_H13_per_minute" 0))
  (printf "hermes H99/min   %d\n" (get snap "hermes_H99_per_minute" 0))
  (printf "ps converge/sec  %d\n" (get snap "ps_converges_per_second" 0))
  (printf "ps run req/min   %d\n" (get snap "ps_run_requests_per_minute" 0))
  (printf "ps stop req/min  %d\n" (get snap "ps_stop_requests_per_minute" 0))
  (printf "ps kill req/min  %d\n" (get snap "ps_kill_requests_per_minute" 0))
  (printf "ps run/min       %d\n" (get snap "ps_runs_per_minute" 0))
  (printf "ps return/min    %d\n" (get snap "ps_returns_per_minute" 0))
  (printf "ps trap/min      %d\n" (get snap "ps_traps_per_minute" 0))
  (printf "ps lost          %d\n" (get snap "ps_lost" 0))
  (printf "slugc inv/min    %d\n" (get snap "slugc_invokes_per_minute" 0))
  (printf "slugc fail/min   %d\n" (get snap "slugc_fails_per_minute" 0))
  (printf "slugc err/min    %d\n" (get snap "slugc_errors_per_minute" 0))
  (printf "\n")
  (printf "req/s   domain\n")
  (printf "-----   -------------\n")
  (doseq [[d r] (get snap "nginx_requests_per_second_top_domains" [])]
    (printf "%5d   %s\n" r d))
  (printf "\n")
  (printf "err/m   domain\n")
  (printf "-----   -------------\n")
  (doseq [[d r] (get snap "nginx_errors_per_minute_top_domains" [])]
    (printf "%5d   %s\n" r d))
  (printf "\n")
  (printf "pub/m   exchange\n")
  (printf "-----   -------------\n")
  (doseq [[e r] (get snap "amqp_publishes_per_minute_top_exchanges" [])]
    (printf "%5d   %s\n" r e))
  (flush))

(def snap-a
  (atom {}))

(defn log [& args]
  (locking *out*
    (apply println args)))

(defn show-rate [snap]
  (log (get snap "events_per_second")))

(defn publish [k v]
  (swap! snap-a assoc k v)
  (show-rate @snap-a))

(defn add-queries [service]
  (engine/add-query service
    "select count(*) from devent.win:time(10 sec)
       output every 1 second"
    (fn [[evt] _]
      (publish "events_per_second" (long (/ (get evt "count(*)") 10.0)))))

  (engine/add-query service
    "select count(*) from devent.win:time(10 sec)
       where ((event_type? = 'nginx_access') and (http_host? != '127.0.0.1'))
       output every 1 second"
    (fn [[evt] _]
      (publish "nginx_requests_per_second" (long (/ (get evt "count(*)") 10.0)))))

  (engine/add-query service
    "select http_domain?, count(*) from devent.win:time(10 sec)
       where ((event_type? = 'nginx_access') and (http_host? != '127.0.0.1'))
       group by http_domain?
       output snapshot every 1 second
       order by count(*) desc
       limit 5"
    (fn [evts _]
      (publish "nginx_requests_per_second_top_domains"
        (map (fn [evt] [(get evt "http_domain?") (long (/ (get evt "count(*)") 10.0))]) evts))))

  (engine/add-query service
    "select http_domain?, count(*) from devent.win:time(10 sec)
       where ((event_type? = 'nginx_access') and
              (http_host? != '127.0.0.1') and
              (cast(http_status?,long) >= 500))
       group by http_domain?
       output snapshot every 1 second
       order by count(*) desc
       limit 5"
    (fn [evts _]
      (publish "nginx_errors_per_minute_top_domains"
        (map (fn [evt] [(get evt "http_domain?") (get evt "count(*)")]) evts))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec)
       where (event_type? = 'nginx_error')
       output every 1 second"
    (fn [[evt] _]
      (publish "nginx_errors_per_minute" (get evt "count(*)"))))

  (doseq [s ["500" "502" "503" "504"]]
    (engine/add-query service
      (str "select count(*) from devent.win:time(60 sec)
              where ((event_type? = 'nginx_access') and
                     (cast(http_status?,long) = " s "))
              output every 1 second")
      (fn [[evt] _]
        (publish (str "nginx_" s "_per_minute") (get evt "count(*)")))))

  (engine/add-query service
    (str "select count(*) from devent.win:time(10 sec)
            where ((event_type? = 'hermes_access') and exists(domain?))
            output every 1 second")
    (fn [[evt] _]
      (publish "hermes_proxies_per_second" (long (/ (get evt "count(*)") 10.0)))))

  (doseq [e ["H10" "H11" "H12" "H13" "H99"]]
    (engine/add-query service
      (str "select count(*) from devent.win:time(60 sec)
              where ((event_type? = 'hermes_access') and
                     (Error? = true) and
                     ("e "? = true))
              output every 1 second")
      (fn [[evt] _]
        (publish (str "hermes_" e "_per_minute") (get evt "count(*)")))))

  (engine/add-query service
    (str "select count(*) from devent.win:time(10 sec)
            where (converge_service? = true)
            output every 1 second")
    (fn [[evt] _]
      (publish "ps_converges_per_second" (long (/ (get evt "count(*)") 10.0)))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec)
       where ((amqp_publish? = true) and
              (cast(exchange?,string) regexp '(ps\\.run|service\\.needed).*'))
       output every 1 second"
    (fn [[evt] _]
      (publish "ps_run_requests_per_minute" (get evt "count(*)"))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec)
       where ((amqp_publish? = true) and
              (cast(exchange?,string) regexp 'ps\\.kill.*'))
       output every 1 second"
    (fn [[evt] _]
      (publish "ps_stop_requests_per_minute" (get evt "count(*)"))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec)
       where ((railgun_service? = true) and
              (ps_kill? = true) and
              (reason? = 'load'))
       output every 1 second"
    (fn [[evt] _]
      (publish "ps_kill_requests_per_minute" (get evt "count(*)"))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec)
       where ((railgun_ps_watch? = true) and (invoke_ps_run? = true))
       output every 1 second"
    (fn [[evt] _]
      (publish "ps_runs_per_minute" (get evt "count(*)"))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec)
       where ((railgun_ps_watch? = true) and (handle_ps_return? = true))
       output every 1 second"
      (fn [[evt] _]
        (publish "ps_returns_per_minute" (get evt "count(*)"))))

  (engine/add-query service
    "select count(*) from devent.win:time(60 sec) where
       ((railgun_ps_watch? = true) and (trap_exit? = true))
       output every 1 second"
    (fn [[evt] _]
      (publish "ps_traps_per_minute" (get evt "count(*)"))))

  (engine/add-query service
    (str "select cast(lastever(total_count?),long) as count from devent
            where (process_lost? = true)
            output first every 1 second")
      (fn [[evt] _]
        (publish "ps_lost" (get evt "count"))))

  (doseq [[k p] {"invokes" "(invoke? = true)"
                 "fails"   "((compile_error? = true) or (locked_error? = true))"
                 "errors"  "((publish_error? = true) or (unexpected_error? = true))"}]
    (engine/add-query service
      (str "select count(*) from devent.win:time(60 sec)
              where ((slugc_bin? = true) and
                     " p ")
              output every 1 second")
      (fn [[evt] _]
        (publish (str "slugc_" k "_per_minute") (get evt "count(*)")))))

  (engine/add-query service
    "select exchange?, count(*) from devent.win:time(60 sec)
       where (amqp_publish? = true)
       group by exchange?
       output snapshot every 1 second
       order by count(*) desc
       limit 5"
    (fn [evts _]
      (publish "amqp_publishes_per_minute_top_exchanges"
        (map (fn [evt] [(get evt "exchange?") (get evt "count(*)")]) evts)))))

(defn tail-file [slot]
  (if (= slot "logplex") "/var/log/logplex" "/var/log/messages"))

(defn parse-tails [path]
  (->> path
    (slurp)
    (str/split-lines)
    (map (fn [l] (str/split l #"\s+")))
    (map (fn [[_ s _ h & _]] [s h (tail-file s)]))))

(def count-a
  (atom 0))

(defn add-tails [service tails]
  (doseq [[slot host file] tails]
    (if (#{"hermes" "face"} slot)
      (pipe/spawn (fn []
        (log "add-tail" slot host file)
         (pipe/shell-lines ["ssh" (str "root@" host) "tail" "-f" file]
           (fn [line]
             (engine/send-event service {"tick" true}))))))))

             ; (if-let [evt (parse/parse-line line)]
             ;   (let [evt-h (assoc evt "host" host)]
             ;     (engine/send-event service evt-h))))))))))

(defn -main [docbrown-path]
  (let [service (engine/init-service)
        tails (parse-tails docbrown-path)]
    (add-queries service)
    (add-tails service tails)))
