(ns pulse.cloud
  (:import (java.util Properties))
  (:import (clojure.lang LineNumberingPushbackReader))
  (:import (com.espertech.esper.client Configuration EventBean UpdateListener EPStatement EPServiceProviderManager))
  (:require [pulse.parse :as parse])
  (:require [pulse.pipe :as pipe]))

(set! *warn-on-reflection* true)

(defn update-current [current k v]
  (swap! current assoc k v)
  (println "\u001B[2j\u001B[f")
  (doseq [p @current]
    (prn p)))

(let [current (atom {})
      config (doto (Configuration.) (.addEventType "devent" (Properties.)))
      service (EPServiceProviderManager/getDefaultProvider config)
      runtime (.getEPRuntime service)
      admin (.getEPAdministrator service)
      statement1 (.createEPL admin "select count(*) as rate from devent.win:time(5 sec) output every 1 sec")
      listener1 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (update-current current "events/sec" (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement1 listener1)
      statement1b (.createEPL admin "select count(*) as rate from devent.win:time(5 sec) where event_type? = 'hermes' output every 1 sec")
      listener1b (proxy [UpdateListener] []
                   (update [new-evts old-evts]
                     (let [info (.getUnderlying ^EventBean (first new-evts))]
                       (update-current current "herm-events/sec" (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement1b listener1b)
      statement2 (.createEPL admin "select count(*) as rate from devent.win:time(5 sec) where exists(http_protocol?)  and http_host? != '127.0.0.1' output every 1 sec")
      listener2 (proxy [UpdateListener] []
                  (update [new-evts old-evts]
                    (let [info (.getUnderlying ^EventBean (first new-evts))]
                      (update-current current "hits/sec" (int (/ (get info "rate") 5.0))))))
      _ (.addListener statement2 listener2)
      statement3 (.createEPL admin "select count(*) as rate from devent.win:time(5 sec) where cast(message?,string) regexp '.*core_service converge_service.*' output every 1 sec")
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
      _ (.addListener statement5 listener5)]
  (pipe/pipe-lines
    (fn [line]
      (if-let [parsed (parse/parse-line line)]
        (.sendEvent runtime parsed "devent")))))

; db ssh syslog tail -f /logs/heroku.log -f /logs/nginx_access.log | clj dev/cloud.clj
