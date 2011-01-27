(ns pulse.engine
  (:import java.util.Properties)
  (:import (com.espertech.esper.client Configuration EventBean UpdateListener EPStatement EPServiceProvider EPServiceProviderManager)))

(defn init-service []
  (let [config (doto (Configuration.)
                 (.addEventType "devent" (Properties.)))]
    (EPServiceProviderManager/getDefaultProvider config)))

(defn- extract-underlying [^EventBean eb]
  (-> (.getUnderlying eb) (into {})))

(defn add-query [service query handler]
  (let [admin     (.getEPAdministrator service)
        statement (.createEPL admin query)
        listener  (proxy [UpdateListener] []
                    (update [new-evts old-evts]
                      (handler (map extract-underlying new-evts)
                               (map extract-underlying old-evts))))]
    (.addListener statement listener)))

(defn send-event [service event]
  (-> service (.getEPRuntime) (.sendEvent event "devent")))
