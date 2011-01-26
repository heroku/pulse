(ns pulse.cloud
  (:import (java.util Properties))
  (:import (clojure.lang LineNumberingPushbackReader))
  (:import (com.espertech.esper.client Configuration EventBean UpdateListener EPStatement EPServiceProviderManager))
  (:require [pulse.parse :as parse])
  (:require [pulse.pipe :as pipe]))

(set! *warn-on-reflection* true)

(defn update-current [current k v]
  (swap! current assoc k v))
  ;println "\u001B[2j\u001B[f")
  ;(doseq [p @current]
  ;  (prn p)))

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

(defn update-exchanges [es]
  (println "\u001B[2J\u001B[f")
  (println "pub/m   exchange")
  (println "-----   ---------------")
  (doseq [e es]
    (let [rate (int (* (get e "count(*)") 2))
          exchange (get e "exchange?")]
      (printf "%5d   %s\n" rate exchange)))
  (flush))

(let [current (atom {})
      config (doto (Configuration.) (.addEventType "devent" (Properties.)))
      service (EPServiceProviderManager/getDefaultProvider config)
      runtime (.getEPRuntime service)
      admin (.getEPAdministrator service)
      statement1 (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) output every 1 second")
      listener1 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (update-current current "events/sec" (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement1 listener1)
      statement1b (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) where event_type? = 'hermes' output every 1 sec")
      listener1b (proxy [UpdateListener] []
                   (update [new-evts old-evts]
                     (let [info (.getUnderlying ^EventBean (first new-evts))]
                       (update-current current "herm-events/sec" (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement1b listener1b)
      statement2 (.createEPL admin "select http_domain?, count(*) from devent.win:time(10 sec) group by http_domain? output snapshot every 500 milliseconds order by count(*) desc limit 30")
      listener2 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (update-tops (map (fn [^EventBean eb] (.getUnderlying eb)) new-evts))))
      _ (.addListener statement2 listener2)
      statement2a (.createEPL admin "select http_domain?, count(*) from devent.win:time(60 sec) where (cast(http_status?,long) >= 500) group by http_domain? output snapshot every 2 seconds order by count(*) desc limit 10")
      listener2a (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (update-errors (map (fn [^EventBean eb] (.getUnderlying eb)) new-evts))))
      _ (.addListener statement2a listener2a)
      statement2b (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) where event_type? = 'nginx_access' and http_host? != '127.0.0.1' output every 1 sec")
      listener2b (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (update-current current "hits/sec" (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement2b listener2b)
      statement3 (.createEPL admin "select count(*) as rate from devent.win:time(10 sec) where cast(message?,string) regexp '.*core_service converge_service.*' output every 1 sec")
      listener3 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (update-current current "converges/sec"  (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement3 listener3)
      statement4 (.createEPL admin "select count(*) as runs from devent.win:time(30 sec) where (cast(message?,string) regexp 'amqp_publish.*' and (cast(exchange?,string) regexp '(ps\\.run|service\\.needed).*')) output every 5 sec")
      listener4 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (update-current current "run-reqs/min" (int (* (get info "runs") 2.0))))))
      _ (.addListener statement4 listener4)
      statement5 (.createEPL admin "select * from devent where (cast(message?,string) regexp 'core_app create.*')")
      listener5 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (comment (println info "app event")))))
      _ (.addListener statement5 listener5)
      statement6 (.createEPL admin "select exchange?, count(*) from devent.win:time(30 sec) where (cast(message?,string) regexp 'amqp_publish.*') group by exchange? output snapshot every 2 seconds order by count(*) desc limit 10")
      listener6 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (update-exchanges (map (fn [^EventBean eb] (.getUnderlying eb)) new-evts))))
      _ (.addListener statement6 listener6)]
  (pipe/pipe-lines
    (fn [line]
      (if-let [parsed (parse/parse-line line)]
        (.sendEvent runtime parsed "devent")))))

; db ssh syslog tail -f /logs/heroku.log -f /logs/nginx_access.log | clj dev/cloud.clj
