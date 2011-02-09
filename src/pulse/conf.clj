(ns pulse.conf
  (:require [clojure.string :as str]))

(def env (System/getenv))
(def port (Integer/parseInt (get env "PORT" "8080")))
(def redis-url (or (get env "REDIS_URL") (get env "REDISTOGO_URL")))
(def forwarder-hosts (if-let [h (get env "FORWARDER_HOSTS")] (str/split h #",")))
(def logplex-hosts (if-let [h (get env "LOGPLEX_HOSTS")] (str/split h #",")))
(def websocket-url (or (get env "WEBSOCKET_URL") (str "ws://127.0.0.1:" port "/stats")))
(def web-auth (if-let [a (get env "WEB_AUTH")] (str/split a #":")))
