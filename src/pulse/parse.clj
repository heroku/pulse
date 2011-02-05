(ns pulse.parse
  (:import java.text.SimpleDateFormat)
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def tail-re
  #"^==>")

(defn tail-line? [l]
  (or (= "" l) (re-find tail-re l)))

(def long-re
  #"^-?[0-9]{1,18}$")

(def double-re
  #"^-?[0-9]+\.[0-9]+$")

(defn coerce-val [v]
  (cond
    (re-find long-re v)
      (Long/parseLong v)
    (re-find double-re v)
      (Double/parseDouble v)
    (= "" v)
      nil
    :else
      v))

(def attrs-re
  #"([a-zA-Z0-9_]+)(=?)([a-zA-Z0-9\.\_\-\:\/]*)")

(defn parse-message-attrs [msg]
  (reduce
    (fn [h [_ k e v]]
      (assoc h (keyword k) (if (= "" e) true (coerce-val v))))
    {}
    (re-seq attrs-re msg)))

(defn parse-timestamp [s]
  (let [f (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssZ")]
    (.getTime (.parse f (str/replace s "-08:00" "-0800")))))

(def standard-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-08:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) ([a-z\-\_]+)\[(\d+)\] - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - (.*)$")

(defn parse-standard-line [l]
  (if-let [s-finds (re-find standard-re l)]
    (let [timestamp-src (parse-timestamp (get s-finds 1))
          host (get s-finds 2)
          facility (get s-finds 3)
          level (get s-finds 4)
          component (get s-finds 5)
          pid (Long/parseLong (get s-finds 6))
          slot (get s-finds 7)
          ion-id (Long/parseLong (get s-finds 8))
          cloud (get s-finds 9)
          message (get s-finds 10)
          message-attrs (parse-message-attrs message)]
    (merge
      {:event_type "standard"
       :timestamp_src timestamp-src
       :host host
       :facility facility
       :level level
       :component component
       :pid pid
       :slot slot
       :ion_id ion-id
       :cloud cloud}
      message-attrs))))


(def logplex-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-08:00) (logplex_.*)$")

(defn parse-logplex-line [l]
  (if-let [s-finds (re-find logplex-re l)]
    (let [timestamp-src (parse-timestamp (get s-finds 1))
          message-attrs (parse-message-attrs (get s-finds 2))]
    (merge
      {:event_type "logplex"
       :timestamp_src timestamp-src
       :component "logplex"}
      message-attrs))))

(def nginx-access-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-08:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) nginx - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - \d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d -0800 \| ([a-zA-Z0-9\.\-]+) \| [A-Z]{3,6} (\S+) HTTP\/(...) \| [0-9\.]+ \| (\d+) \| (https?) \| (\d+)$")

(defn parse-nginx-access-line [l]
  (if-let [n-finds (re-find nginx-access-re l)]
    (let [timestamp-src (parse-timestamp (get n-finds 1))
          host (get n-finds 2)
          facility (get n-finds 3)
          level (get n-finds 4)
          slot (get n-finds 5)
          ion-id (Long/parseLong (get n-finds 6))
          cloud (get n-finds 7)
          http-domain (get n-finds 8)
          http-url (get n-finds 9)
          http-version (get n-finds 10)
          http-bytes (Long/parseLong (get n-finds 11))
          http-protocol (get n-finds 12)
          http-status (Long/parseLong (get n-finds 13))]
       {:event_type "nginx_access"
        :timestamp_src timestamp-src
        :host host
        :facility facility
        :level level
        :component "nginx"
        :slot slot
        :ion_id ion-id
        :cloud cloud
        :http_domain http-domain
        :http_url http-url
        :http_version http-version
        :http_bytes http-bytes
        :http_protocol http-protocol
        :http_status http-status})))

(def nginx-error-line
  "2011-01-31T16:42:35-08:00 10.122.131.19 local5.crit nginx - face64.45038@heroku.com - 2011/01/31 16:42:35 [error] 7850#0: *3802501751 upstream sent no valid HTTP/1.0 header while reading response header from upstream, client: 72.167.191.7, server: _, request: \"POST /sessions HTTP/1.1\", upstream: \"http://10.102.7.182:8001/sessions\", host: \"senubo-dev.heroku.com\"")

(def nginx-error-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-08:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) nginx - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - .* \[error\] (.*)$")

(defn parse-nginx-error-line [l]
  (if-let [n-finds (re-find nginx-error-re l)]
    (let [timestamp-src (parse-timestamp (get n-finds 1))
          host (get n-finds 2)
          facility (get n-finds 3)
          level (get n-finds 4)
          slot (get n-finds 5)
          ion-id (Long/parseLong (get n-finds 6))
          cloud (get n-finds 7)
          message (get n-finds 8)]
       {:event_type "nginx_error"
        :timestamp_src timestamp-src
        :facility facility
        :level level
        :component "nginx"
        :slot slot
        :ion_id ion-id
        :cloud cloud
        :message message})))

(def varnish-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-08:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) varnish\[(\d+)\] - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - [0-9\.]+ - - (.*)$")

(defn parse-varnish-line [l]
  (if-let [v-finds (re-find varnish-re l)]
    (let [timestamp-src (parse-timestamp (get v-finds 1))
          host (get v-finds 2)
          facility (get v-finds 3)
          level (get v-finds 4)
          pid (Long/parseLong (get v-finds 5))
          slot (get v-finds 6)
          ion-id (Long/parseLong (get v-finds 7))
          cloud (get v-finds 8)
          message (get v-finds 9)]
       {:event_type "varnish_access"
        :timestamp_src timestamp-src
        :facility facility
        :level level
        :component "varnish"
        :pid pid
        :slot slot
        :ion_id ion-id
        :cloud cloud
        :message message})))


(def hermes-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-08:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) hermes\[(\d+)\] - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - \[hermes_proxy\] (.*)$")

(defn parse-hermes-line [l]
  (if-let [s-finds (re-find hermes-re l)]
    (let [timestamp-src (parse-timestamp (get s-finds 1))
          host (get s-finds 2)
          facility (get s-finds 3)
          level (get s-finds 4)
          pid (Long/parseLong (get s-finds 5))
          slot (get s-finds 6)
          ion-id (Long/parseLong (get s-finds 7))
          cloud (get s-finds 8)
          message (get s-finds 9)
          message-attrs (parse-message-attrs message)]
      (merge
        {:event_type "hermes_access"
         :timestamp_src timestamp-src
         :host host
         :facility facility
         :level level
         :component "hermes"
         :pid pid
         :slot slot
         :ion_id ion-id
         :cloud cloud}
        message-attrs))))

(defn parse-line [l]
  (try
    (if-not (tail-line? l)
      (or (parse-nginx-access-line l)
          (parse-nginx-error-line l)
          (parse-hermes-line l)
          (parse-varnish-line l)
          (parse-logplex-line l)
          (parse-standard-line l)))
    (catch Exception e
      (locking *out*
        (prn "error" l))
      (throw e))))
