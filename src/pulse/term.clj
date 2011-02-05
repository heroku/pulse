(ns pulse.term
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util]))

(def scalars
  [["events/sec"      "events_per_second"]
   ["internal/sec"    "events_internal_per_second"]
   ["external/sec"    "events_external_per_second"]
   ["unparsed/sec"    "events_unparsed_per_second"]
   ["nginx req/sec"   "nginx_requests_per_second"]
   ["nginx_err/min"   "nginx_errors_per_minute"]
   ["nginx 500/min"   "nginx_500_per_minute"]
   ["nginx 502/min"   "nginx_502_per_minute"]
   ["nginx 503/min"   "nginx_503_per_minute"]
   ["nginx 504/min"   "nginx_504_per_minute"]
   ["varnish req/sec" "varnish_requests_per_second"]
   ["hermes req/sec"  "hermes_requests_per_second"]
   ["hermes H10/min"  "hermes_H10_per_minute"]
   ["hermes H11/min"  "hermes_H11_per_minute"]
   ["hermes H12/min"  "hermes_H12_per_minute"]
   ["hermes H13/min"  "hermes_H13_per_minute"]
   ["hermes H99/min"  "hermes_H99_per_minute"]
   ["ps converge/sec" "ps_converges_per_second"]
   ["ps run req/min"  "ps_run_requests_per_minute"]
   ["ps stop req/min" "ps_stop_requests_per_minute"]
   ["ps kill req/min" "ps_kill_requests_per_minute"]
   ["ps run/min"      "ps_runs_per_minute"]
   ["ps return/min"   "ps_returns_per_minute"]
   ["ps trap/min"     "ps_traps_per_minute"]
   ["ps lost"         "ps_lost"]
   ["slugc inv/min"   "slugc_invokes_per_minute"]
   ["slugc fail/min"  "slugc_fails_per_minute"]
   ["slugc err/min"   "slugc_errors_per_minute"]
   ["amqp pub/sec"    "amqp_publishes_per_second"]
   ["amqp rec/sec"    "amqp_receives_per_second"]
   ["amqp tim/min"    "amqp_timeouts_per_minute"]])

(def tables-l
  [["req/s" "domain"   "nginx_requests_by_domain_per_second"]
   ["50x/m" "domain"   "nginx_50x_by_domain_per_minute"]
   ["pub/s" "exchange" "amqp_publishes_by_exchange_per_second"]
   ["rec/s" "exchange" "amqp_receives_by_exchange_per_second"]])

(def tables-r
  [["log/s" "app"  "logs_by_app_per_second"]
   ["evt/s" "host" "events_by_tail_host_per_second"]])

(defn render-start []
  (printf "\u001B[2J\u001B[f"))

(defn render-at [r c t]
  (printf "\u001B[%d;%dH%s" (inc r) (inc c) t))

(defn render-finish []
  (flush))

(defn indexed [c]
  (map list c (range (count c))))

(defn render [snap]
  (render-start)
  (doseq [[[stat-label stat-key] idx] (indexed scalars)]
    (render-at idx 0 (format "%5d  %s" (get snap stat-key 0) stat-label)))
  (doseq [[tables offset] [[tables-l 30] [tables-r 70]]]
    (doseq [[[stat-label-rate stat-label-key  stat-key] idx] (indexed tables)]
      (let [base-row (* idx 8)]
        (render-at (+ base-row 0) offset (format "%5s   %s" stat-label-rate stat-label-key))
        (render-at (+ base-row 1) offset (format "-----   -------------"))
        (doseq [[[key rate] rate-idx] (indexed (get snap stat-key []))]
          (render-at (+ base-row 2 rate-idx) offset (format "%5d   %s" rate key))))))
  (render-finish))

(def rd
  (redis/init {:url conf/redis-url}))

(def snap-a
  (atom {}))

(defn receive [_ stat-json]
  (let [[k v] (json/parse-string stat-json)]
    (if (= k "render")
      (render @snap-a)
      (swap! snap-a assoc k v))))

(defn -main []
  (redis/subscribe rd ["stats"] receive))
