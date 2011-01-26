(ns pulse.cloud
  (:import (java.util Properties))
  (:import (clojure.lang LineNumberingPushbackReader))
  (:import (com.espertech.esper.client Configuration EventBean UpdateListener EPStatement EPServiceProviderManager)))

(set! *warn-on-reflection* true)

(defn pipe-lines [f]
  (loop []
    (when-let [line (.readLine ^LineNumberingPushbackReader *in*)]
      (f line)
      (recur))))

(let [config (doto (Configuration.) (.addEventType "devent" (Properties.)))
      service (EPServiceProviderManager/getDefaultProvider config)
      runtime (.getEPRuntime service)
      admin (.getEPAdministrator service)
      statement (.createEPL admin "select count(*) as rate from devent.win:time(5 sec) output every 1 sec")
      listener (proxy [UpdateListener] []
                 (update [new-evts old-evts]
                   (let [info (.getUnderlying ^EventBean (first new-evts))]
                     (println (int (/ (get info "rate") 5.0)) "lines/sec"))))]
  (.addListener statement listener)
  (pipe-lines
    (fn [line]
      (.sendEvent runtime {"text" line} "devent"))))

; db ssh syslog tail -f /logs/heroku.log | clj src/pulse/cloud.clj