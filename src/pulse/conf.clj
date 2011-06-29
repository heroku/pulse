(ns pulse.conf
  (:require [clojure.string :as str]))

(defn env! [k]
  (or (System/getenv k) (throw (Exception. (str "missing key " k)))))

(defn port [] (Integer/parseInt (env! "PORT")))
(defn redis-url [] (env! "REDIS_URL"))
(defn aorta-urls [] (str/split (env! "AORTA_URLS") #","))
(defn web-auth [] (str/split (env! "WEB_AUTH") #":"))
