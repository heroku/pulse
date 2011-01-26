(ns pulse.spike
  (:import (java.util Properties))
  (:import (com.espertech.esper.client Configuration EventBean UpdateListener EPStatement EPServiceProviderManager)))

(set! *warn-on-reflection* true)

(let [evt-props (doto (Properties.)
                  (.put "price" "double")
                  (.put "timestamp" "long")
                  (.put "symbol" "string"))
      config (doto (Configuration.)
               (.addEventType "tickEvent" evt-props))
      service (EPServiceProviderManager/getDefaultProvider config)
      runtime (.getEPRuntime service)
      admin (.getEPAdministrator service)
      statement (.createEPL admin "select * from tickEvent(symbol='APPL').win:length(2) having avg(price) > 6.0")
      listener (proxy [UpdateListener] []
                 (update [new-evts _]
                   (println "update" (.getUnderlying ^EventBean (first new-evts)))))]
  (.addListener statement listener)
  (dotimes [_ 100]
    (let [price (* 10 (rand))
          timestamp (System/currentTimeMillis)
          symbol "APPL"
          tick {"price" price "timestamp" timestamp "symbol" symbol}]
      (println "send" tick)
      (.sendEvent runtime tick "tickEvent"))))
