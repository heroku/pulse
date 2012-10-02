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
(defn clouds [] (str/split (env! "CLOUDS") #","))
(defn default-cloud [] (or (env "DEFAULT_CLOUD") (first (clouds))))
(defn canonical-host [] (env! "CANONICAL_HOST"))
(defn publish-threads [] (Integer. (env! "PUBLISH_THREADS")))
(defn merger-count [] (Integer. (or (env "MERGER_COUNT") 5)))
(defn graphite-prefix [] (env "GRAPHITE_PREFIX")) ; for staging deploys
