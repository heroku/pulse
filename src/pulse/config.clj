(ns pulse.config
  (:require [clojure.string :as str]))

(def env (System/getenv))
(def port (Integer/parseInt (get env "PORT" "8080")))
(def redis-url (get env "REDIS_URL"))
(def forwarders (if-let [f (get env "FORWARDERS")] (str/split f #",")))
