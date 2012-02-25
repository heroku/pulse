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
(defn session-secret [] (env! "SESSION_SECRET"))
(defn proxy-url [] (env! "PROXY_URL"))
(defn proxy-secret [] (env! "PROXY_SECRET"))
(defn force-https? [] (boolean (env "FORCE_HTTPS")))
(defn scales-url [] (env! "SCALES_URL"))
(defn api-url [] (env! "API_URL"))
(defn deploy [] (env! "DEPLOY"))
(defn cloud [] (env! "CLOUD"))
(defn canonical-host [] (env! "CANONICAL_HOST"))
(defn graphite-url [] (env! "GRAPHITE_URL"))
(defn publish-threads [] (Integer/parseInt (env! "PUBLISH_THREADS")))
(defn merger-count [] 5)

(defn api-password []
  (second (str/split (:auth (util/url-parse (api-url))) #":")))
