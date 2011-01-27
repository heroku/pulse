(ns pulse.term
  (:require [pulse.pipe :as pipe])
  (:require [pulse.parse :as parse])
  (:require [pulse.engine :as engine]))

(set! *warn-on-reflection* true)

(defn redraw [snap]
  (printf "\u001B[2J\u001B[f")
  (printf "events/sec      %d\n"  (get snap "events_per_second" 0))
  (printf "nginx req/sec   %d\n" (get snap "nginx_requests_per_second" 0))
  (printf "nginx err/min   %d\n" (get snap "nginx_errors_per_minute" 0))
  (printf "nginx 500/min   %d\n" (get snap "nginx_500_per_minute" 0))
  (printf "nginx 502/min   %d\n" (get snap "nginx_502_per_minute" 0))
  (printf "nginx 503/min   %d\n" (get snap "nginx_503_per_minute" 0))
  (printf "nginx 504/min   %d\n" (get snap "nginx_504_per_minute" 0))
  (printf "hermes prx/sec  %d\n" (get snap "hermes_proxies_per_second" 0))
  (printf "hermes H10/min  %d\n" (get snap "hermes_H10_per_minute" 0))
  (printf "hermes H11/min  %d\n" (get snap "hermes_H11_per_minute" 0))
  (printf "hermes H12/min  %d\n" (get snap "hermes_H12_per_minute" 0))
  (printf "hermes H13/min  %d\n" (get snap "hermes_H13_per_minute" 0))
  (printf "hermes H99/min  %d\n" (get snap "hermes_H99_per_minute" 0))
  (printf "slugc inv/min   %d\n" (get snap "slugc_invokes_per_minute" 0))
  (printf "slugc fail/min  %d\n" (get snap "slugc_fails_per_minute" 0))
  (printf "slugc err/min   %d\n" (get snap "slugc_errors_per_minute" 0))
  (flush))

(def snap-a
  (atom {}))

(defn publish [k v]
  (swap! snap-a assoc k v)
  (redraw @snap-a))

(defn -main []
  (let [service (engine/init-service)]
    (engine/add-query service "select count(*) from devent.win:time(10 sec) output every 1 second"
      (fn [[evt] _]
        (publish "events_per_second" (long (/ (get evt "count(*)") 10.0)))))
    (engine/add-query service "select count(*) from devent.win:time(10 sec) where ((event_type? = 'nginx_access') and (http_host? != '127.0.0.1')) output every 1 second"
      (fn [[evt] _]
        (publish "nginx_requests_per_second" (long (/ (get evt "count(*)") 10.0)))))
    (engine/add-query service "select count(*) from devent.win:time(60 sec) where (event_type? = 'nginx_error') output every 1 second"
      (fn [[evt] _]
        (publish "nginx_errors_per_minute" (get evt "count(*)"))))
    (doseq [s ["500" "502" "503" "504"]]
      (engine/add-query service (str "select count(*) from devent.win:time(60 sec) where ((event_type? = 'nginx_access') and (cast(http_status?,long) = " s ")) output every 1 second")
        (fn [[evt] _]
          (publish (str "nginx_" s "_per_minute") (get evt "count(*)")))))
    (engine/add-query service (str "select count(*) from devent.win:time(10 sec) where ((event_type? = 'hermes') and exists(domain?)) output every 1 second")
        (fn [[evt] _]
          (publish (str "hermes_proxies_per_second") (long (/ (get evt "count(*)") 10.0)))))
    (doseq [e ["H10" "H11" "H12" "H13" "H99"]]
      (engine/add-query service (str "select count(*) from devent.win:time(60 sec) where ((event_type? = 'hermes') and (cast(message?,string) regexp '.*Error " e ".*')) output every 1 second")
        (fn [[evt] _]
          (publish (str "hermes_" e "_per_minute") (get evt "count(*)")))))
    (doseq [[k p] {"invokes" "invoke"
                   "fails"   "(compile_error)|(locked_error)"
                   "errors"  "(publish_error)|(unexpected_error)"}]
      (engine/add-query service (str "select count(*) from devent.win:time(60 sec) where ((event_type? = 'standard') and (cast(message?,string) regexp '.*slugc_bin.*" p ".*')) output every 1 second")
        (fn [[evt] _]
          (publish (str "slugc_" k "_per_minute") (get evt "count(*)")))))
    (pipe/stdin-lines
      (fn [line]
        (if-let [evt (parse/parse-line line)]
          (engine/send-event service evt))))))

(defn update-tops [ps])
  ;(println "\u001B[2J\u001B[f")
  ;(println "req/s   domain")
  ;(println "-----   ---------------")
  ;(doseq [p ps]
  ;  (let [rate (int (/ (get p "count(*)") 10))
  ;        domain (get p "http_domain?")]
  ;    (printf "%5d   %s\n" rate domain)))
  ;(flush))

(defn update-errors [ps])
;  (println "\u001B[2J\u001B[f")
;  (println "error/min   domain")
;  (println "-----   ---------------")
;  (doseq [p ps]
;    (let [rate (get p "count(*)")
;          domain (get p "http_domain?")]
;      (printf "%5d   %s\n" rate domain)))
;  (flush))

(defn update-exchanges [es])
  ;(println "\u001B[2J\u001B[f")
  ;(println "pub/m   exchange")
  ;(println "-----   ---------------")
  ;(doseq [e es]
  ;  (let [rate (int (* (get e "count(*)") 2))
  ;        exchange (get e "exchange?")]
  ;    (printf "%5d   %s\n" rate exchange)))
  ;(flush))

; current (atom {})



; statement1 (.createEPL admin )
; listener1 (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (let [info (.getUnderlying ^EventBean (first new-evts))]
;                 (update-current current "events/sec" (int (/ (get info "rate") 5.0))))))
; _ (.addListener statement1 listener1)
; statement1b (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) where event_type? = 'hermes' output every 1 sec")
; listener1b (proxy [UpdateListener] []
;              (update [new-evts old-evts]
;                (let [info (.getUnderlying ^EventBean (first new-evts))]
;                  (update-current current "herm-events/sec" (int (/ (get info "rate") 5.0))))))
; _ (.addListener statement1b listener1b)
; statement2 (.createEPL admin "select http_domain?, count(*) from devent.win:time(10 sec) group by http_domain? output snapshot every 500 milliseconds order by count(*) desc limit 30")
; listener2 (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (update-tops (map (fn [^EventBean eb] (.getUnderlying eb)) new-evts))))
; _ (.addListener statement2 listener2)
; statement2a (.createEPL admin "select http_domain?, count(*) from devent.win:time(60 sec) where (cast(http_status?,long) >= 500) group by http_domain? output snapshot every 2 seconds order by count(*) desc limit 10")
; listener2a (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (update-errors (map (fn [^EventBean eb] (.getUnderlying eb)) new-evts))))
; _ (.addListener statement2a listener2a)
; statement2b (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) where event_type? = 'nginx_access' and http_host? != '127.0.0.1' output every 1 sec")
; listener2b (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (let [info (.getUnderlying ^EventBean (first new-evts))]
;                 (update-current current "hits/sec" (int (/ (get info "rate") 5.0))))))
; _ (.addListener statement2b listener2b)
; statement3 (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) where cast(message?,string) regexp '.*core_service converge_service.*' output every 1 sec")
; listener3 (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (let [info (.getUnderlying ^EventBean (first new-evts))]
;                 (update-current current "converges/sec"  (int (/ (get info "rate") 5.0))))))
; _ (.addListener statement3 listener3)
; statement4 (.createEPL admin "select count(*) as runs from devent.win:time(30 sec) where (cast(message?,string) regexp 'amqp_publish.*' and (cast(exchange?,string) regexp '(ps\\.run|service\\.needed).*')) output every 5 sec")
; listener4 (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (let [info (.getUnderlying ^EventBean (first new-evts))]
;                 (update-current current "run-reqs/min" (int (* (get info "runs") 2.0))))))
; _ (.addListener statement4 listener4)
; statement5 (.createEPL admin "select * from devent where (cast(message?,string) regexp 'core_app create.*')")
; listener5 (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (let [info (.getUnderlying ^EventBean (first new-evts))]
;                 (comment (println info "app event")))))
; _ (.addListener statement5 listener5)
; statement6 (.createEPL admin "select exchange?, count(*) from devent.win:time(30 sec) where (cast(message?,string) regexp 'amqp_publish.*') group by exchange? output snapshot every 2 seconds order by count(*) desc limit 10")
; listener6 (proxy [UpdateListener] []
;             (update [new-evts old-evts]
;               (update-exchanges (map (fn [^EventBean eb] (.getUnderlying eb)) new-evts))))
; _ (.addListener statement6 listener6)]

