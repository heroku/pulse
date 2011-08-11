(ns pulse.conf
  (:require [clojure.string :as str]))

(defn env [k]
  (System/getenv k))

(defn env! [k]
  (or (env k) (throw (Exception. (str "missing key " k)))))

(defn port [] (Integer/parseInt (env! "PORT")))
(defn redis-url [] (env! "REDIS_URL"))
(defn aorta-urls [] (str/split (env! "AORTA_URLS") #","))
(defn session-secret [] (env! "SESSION_SECRET"))
(defn proxy-url [] (env! "PROXY_URL"))
(defn proxy-secret [] (env! "PROXY_SECRET"))
(defn force-https? [] (boolean (env "FORCE_HTTPS")))
(defn api-password [] (env! "API_PASSWORD"))
(defn pulse-scales-url [] (env! "PULSE_SCALES_URL"))
