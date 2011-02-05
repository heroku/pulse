(ns pulse.conf
  (:require [clojure.string :as str]))

(def env (System/getenv))
(def port (Integer/parseInt (get env "PORT" "8080")))
(def redis-url (get env "REDIS_URL"))
(def forwarder-hosts (if-let [h (get env "FORWARDER_HOSTS")] (str/split h #",")))
(def logplex-hosts (if-let [h (get env "LOGPLEX_HOSTS")] (str/split h #",")))
