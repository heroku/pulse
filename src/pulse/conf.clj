(ns pulse.conf
  (:require [pulse.util :as util]
            [clojure.string :as str]))

(defn env [k]
  (System/getenv k))

(defn env! [k]
  (or (env k) (throw (Exception. (str "missing key " k)))))

(defn port [] (Integer/parseInt (env! "PORT")))
(defn redis-url [] (env! "REDIS_URL"))
(defn aorta-urls [] (str/split (env! "AORTA_URLS") #","))
(defn metrics-urls [] (str/split (env! "METRICS_URLS") #","))
(defn deploy [] (env! "DEPLOY"))
(defn clouds [] (env! "CLOUDS"))
(defn canonical-host [] (env! "CANONICAL_HOST"))
(defn graphite-url [] (env! "GRAPHITE_URL"))
(defn graphite-period [] (env! "GRAPHITE_PERIOD"))
(defn publish-threads [] (Integer/parseInt (env! "PUBLISH_THREADS")))
(defn merger-count [] 5)
