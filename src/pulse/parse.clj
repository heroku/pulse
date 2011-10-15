(ns pulse.parse
  (:import java.text.SimpleDateFormat)
  (:require [clojure.string :as str]
            [pulse.util :as util]
            [pulse.log :as log]))

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

(def nginx-access-re
     ;timestamp_src                              ;host      ;facility    ;level           ;slot        ;ion_id ;cloud           ;http_host                                                              ;http_method,_url,_version      ;http_status,_bytes,_referrer,_user_agent,_domain
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d-\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) nginx - ([a-z4-6-]+)?\.(\d+)@([a-z.]+\.com) - ([0-9\.]+) - - \[\d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d -\d\d00\] \"([a-zA-Z]+) (\S+) HTTP\/(...)\" (\d+) (\d+) \"([^\"]+)\" \"([^\"]+)\" (\S+)$")

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
        :http_host (.group m 8)
        :http_method (.group m 9)
        :http_url (.group m 10)
        :http_version (.group m 11)
        :http_status (Long/parseLong (.group m 12))
        :http_bytes (Long/parseLong (.group m 13))
        :http_referrer (.group m 14)
        :http_user_agent (.group m 15)
        :http_domain (.group m 16)})))

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

(def varnish-access-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d[\-+]\d\d:00) ([0-9\.]+) ([a-z0-7]+)\.([a-z]+) varnish\[(\d+)\] - ([a-z4-6\-]+)?\.(\d+)@([a-z.]+\.com) - [0-9\.]+ - - .*\" (\d\d\d) .*$")

(defn parse-varnish-access-line [l]
  (let [m (re-matcher varnish-access-re l)]
    (if (.find m)
       {:event_type "varnish_access"
        :timestamp_src (parse-timestamp (.group m 1))
        :host (.group m 2)
        :facility (.group m 3)
        :level (.group m 4)
        :component "varnish"
        :pid (parse-long (.group m 5))
        :slot (.group m 6)
        :ion_id (parse-long (.group m 7))
        :cloud (.group m 8)
        :http_status (parse-long (.group m 9))})))

(defn parse-line [l]
  (try
    (or (parse-nginx-access-line l)
        (parse-nginx-error-line l)
        (parse-varnish-access-line l)
        (parse-standard-line l)
        (parse-raw-line l))
    (catch Exception e
      (log/log "parse error %s" l)
      (throw e))))
