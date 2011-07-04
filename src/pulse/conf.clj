(ns pulse.conf
  (:require [clojure.string :as str]))

(defn env [k]
  (System/getenv k))

(defn env! [k]
  (or (env k) (throw (Exception. (str "missing key " k)))))

(defn port [] (Integer/parseInt (env! "PORT")))
(defn redis-url [] (env! "REDIS_URL"))
(defn aorta-urls [] (str/split (env! "AORTA_URLS") #","))
(defn web-auth [] (str/split (env! "WEB_AUTH") #":"))
(defn force-https [] (boolean (env "FORCE_HTTPS")))
