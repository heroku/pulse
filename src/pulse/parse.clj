(ns pulse.parse
  (:import java.text.SimpleDateFormat)
  (:require [clojure.string :as str])
  (:require [pulse.util :as util]))

(set! *warn-on-reflection* true)

(defn re-match? [re s]
  (let [m (re-matcher re s)]
    (.find m)))

(def long-re
  #"^-?[0-9]{1,18}$")

(def double-re
  #"^-?[0-9]+\.[0-9]+$")

(defn coerce-val [v]
  (cond
    (re-match? long-re v)
      (Long/parseLong v)
    (re-match? double-re v)
      (Double/parseDouble v)
    (= "" v)
      nil
    :else
      v))

(def attrs-re
  #"([a-zA-Z0-9_]+)(=?)([a-zA-Z0-9\.\_\-\:\/]*)")

(defn parse-message-attrs [msg]
  (let [m (re-matcher attrs-re msg)]
    (loop [a (java.util.HashMap.)]
      (if (.find m)
        (do
          (.put a
            (keyword (.group m 1))
            (if (= "" (.group m 2)) true (coerce-val (.group m 3))))
          (recur a))
        a))))

(defn parse-timestamp [s]
  (let [f (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssZ")]
    (.getTime (.parse f (str/replace s #":\d\d$" "00")))))

(defn parse-long [s]
  (if s (Long/parseLong s)))

(def standard-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d[\-\+]\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) ([a-z\-\_]+)(\[(\d+)\])? - ([a-z4-6-]+)?\.(\d+)@([a-z.]+\.com) - (.*)$")

(defn parse-standard-line [l]
  (let [m (re-matcher standard-re l)]
    (if (.find m)
      (merge
        {:event_type "standard"
         :timestamp_src (parse-timestamp (.group m 1))
         :host (.group m 2)
         :facility (.group m 3)
         :level (.group m 4)
         :component (.group m 5)
         :pid (parse-long (.group m 7))
         :slot (.group m 8)
         :ion_id (Long/parseLong (.group m 9))
         :cloud (.group m 10)}
        (parse-message-attrs (.group m 11))))))

(def raw-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d[\-\+]\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) (.*)$")

(defn parse-raw-line [l]
  (let [m (re-matcher raw-re l)]
    (if (.find m)
      {:event_type "raw"
       :timestamp_src (parse-timestamp (.group m 1))
       :host (.group m 2)
       :facility (.group m 3)
       :level (.group m 4)
       :message (.group m 5)})))

(def logplex-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) (logplex_.*)$")

(defn parse-logplex-line [l]
  (let [m (re-matcher logplex-re l)]
    (if (.find m)
      (merge
        {:event_type "logplex"
         :timestamp_src (parse-timestamp (.group m 1))
         :component "logplex"}
        (parse-message-attrs (.group m 2))))))

(def nginx-access-re
     ;timestamp_src                              ;host      ;facility    ;level           ;slot        ;ion_id ;cloud                                                                 ;http_domain                     ;http_url   ;http_version     ;http_bytes ;http_proto ;http_status               
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) nginx - ([a-z4-6-]+)?\.(\d+)@([a-z.]+\.com) - \d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d -\d\d00 \| ([a-zA-Z0-9\.\-\:]+) \| [A-Z]{3,6} (\S+) HTTP\/(...) \| [0-9\.]+ \| (\d+) \| (https?) \| (\d+)$")

(defn parse-nginx-access-line [l]
  (let [m (re-matcher nginx-access-re l)]
    (if (.find m)
       {:event_type "nginx_access"
        :timestamp_src (parse-timestamp (.group m 1))
        :host (.group m 2)
        :facility (.group m 3)
        :level (.group m 4)
        :component "nginx"
        :slot (.group m 5)
        :ion_id (Long/parseLong (.group m 6))
        :cloud (.group m 7)
        :http_domain (.group m 8)
        :http_url (.group m 9)
        :http_version (.group m 10)
        :http_bytes (Long/parseLong (.group m 11))
        :http_protocol (.group m 12)
        :http_status (Long/parseLong (.group m 13))})))

(def nginx-access2-re
     ;timestamp_src                              ;host      ;facility    ;level           ;slot        ;ion_id ;cloud                                                                                       ;http_bytes ;http_proto ;http_status               
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) nginx - ([a-z4-6-]+)?\.(\d+)@([a-z.]+\.com) - \d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d -\d\d00 \| - \| - \| [0-9\.]+ \| (\d+) \| (https?) \| (\d+)$")

(defn parse-nginx-access2-line [l]
  (let [m (re-matcher nginx-access2-re l)]
    (if (.find m)
       {:event_type "nginx_access2"
        :timestamp_src (parse-timestamp (.group m 1))
        :host (.group m 2)
        :facility (.group m 3)
        :level (.group m 4)
        :component "nginx"
        :slot (.group m 5)
        :ion_id (Long/parseLong (.group m 6))
        :cloud (.group m 7)
        :http_bytes (Long/parseLong (.group m 8))
        :http_protocol (.group m 9)
        :http_status (Long/parseLong (.group m 10))})))

(def nginx-error-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) nginx - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - .* \[error\] (.*)$")

(defn parse-nginx-error-line [l]
  (let [m (re-matcher nginx-error-re l)]
    (if (.find m)
       {:event_type "nginx_error"
        :timestamp_src (parse-timestamp (.group m 1))
        :host (.group m 2)
        :facility (.group m 3)
        :level (.group m 4)
        :component "nginx"
        :slot (.group m 5)
        :ion_id (Long/parseLong (.group m 6))
        :cloud (.group m 7)
        :message (.group m 8)})))

(def varnish-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) varnish\[(\d+)\] - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - [0-9\.]+ - - (.*)$")

(defn parse-varnish-line [l]
  (let [m (re-matcher varnish-re l)]
    (if (.find m)
       {:event_type "varnish_access"
        :timestamp_src (parse-timestamp (.group m 1))
        :host (.group m 2)
        :facility (.group m 3)
        :level (.group m 4)
        :component "varnish"
        :pid (Long/parseLong (.group m 5))
        :slot (.group m 6)
        :ion_id (Long/parseLong (.group m 7))
        :cloud (.group m 8)
        :message (.group m 9)})))

(def hermes-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) hermes\[(\d+)\] - ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) - \[hermes_proxy\] (.*)$")

(defn parse-hermes-line [l]
  (let [m (re-matcher hermes-re l)]
    (if (.find m)
      (merge
        {:event_type "hermes_access"
         :timestamp_src (parse-timestamp (.group m 1))
         :host (.group m 2)
         :facility (.group m 3)
         :level (.group m 4)
         :component "hermes"
         :pid (Long/parseLong (.group m 5))
         :slot (.group m 6)
         :ion_id (Long/parseLong (.group m 7))
         :cloud (.group m 8)}
        (parse-message-attrs (.group m 9))))))

(defn parse-line [l]
  (try
    (if-not (tail-line? l)
      (or (parse-nginx-access-line l)
          (parse-nginx-access2-line l)
          (parse-nginx-error-line l)
          (parse-hermes-line l)
          (parse-varnish-line l)
          (parse-logplex-line l)
          (parse-standard-line l)
          (parse-raw-line l)))
    (catch Exception e
      (util/log "parse error %s" l)
      (throw e))))
