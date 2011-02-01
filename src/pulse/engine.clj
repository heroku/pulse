(ns pulse.engine
  (:import java.util.Properties)
  (:import (com.espertech.esper.client Configuration EventBean UpdateListener EPStatement EPServiceProvider EPServiceProviderManager))
  (:require [clojure.string :as str])
  (:require [pulse.util :as util]))

(set! *warn-on-reflection* true)

(defn init-service []
  (util/log "init_service")
  (let [config (doto (Configuration.)
                 (.addEventType "hevent" (Properties.)))]
    (EPServiceProviderManager/getDefaultProvider config)))

(defn- extract-underlying [^EventBean eb]
  (-> (.getUnderlying eb) (into {})))

(defn add-query [service query handler]
  (util/log "add_query query='%s'" (str/replace query #"\s+" " "))
  (let [admin     (.getEPAdministrator service)
        statement (.createEPL admin query)
        listener  (proxy [UpdateListener] []
                    (update [new-evts old-evts]
                      (handler (map extract-underlying new-evts)
                               (map extract-underlying old-evts))))]
    (.addListener statement listener)))

(defn send-event [service event]
  (-> service (.getEPRuntime) (.sendEvent event "hevent")))
