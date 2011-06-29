(ns pulse.conf
  (:require [clojure.string :as str]))

(defn env! [k]
  (or (System/getenv k) (throw (Exception.)))

(def port (Integer/parseInt (env! "PORT")))
(def redis-url (env! "REDIS_URL"))
(def aorta-urls (str/split (env! "AORTA_URLS") #","))
