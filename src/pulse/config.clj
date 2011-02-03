(ns pulse.config
  (:require [clojure.string :as str]))

(def env (System/getenv))
(def port (Integer/parseInt (get env "PORT" "8080")))
(def redis-url (get env "REDIS_URL"))
(def splunk-hosts (if-let [s (get env "SPLUNK_HOSTS")] (str/split s #",")))
