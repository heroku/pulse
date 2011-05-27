(ns pulse.conf
  (:require [clojure.string :as str]))

(def env (System/getenv))
(def port (Integer/parseInt (get env "PORT" "8080")))
(def redis-url (or (get env "REDIS_URL") (get env "REDISTOGO_URL")))
(def aorta-urls (if-let [h (get env "AORTA_URLS")] (str/split h #",")))
