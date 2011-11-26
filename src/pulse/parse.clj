(ns pulse.parse
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
    (= "true" v)
      true
    (= "false" v)
      false
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

(defn parse-long [s]
  (if s (Long/parseLong s)))

(def standard-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?[\-\+]\d\d:00) [0-9\.]+ [a-z0-7]+\.([a-z]+) ([a-zA-Z0-9\/\-\_\.]+)(\[([a-zA-Z0-9\.]+)\])?:? - (([a-z0-9\-\_]+)?\.(\d+)@([a-z.\-]+))?([a-zA-Z0-9\-\_\.]+)?( -)? (.*)$")

(defn parse-standard-line [l]
  (let [m (re-matcher standard-re l)]
    (if (.find m)
      (merge
        {:event_type "standard"
         :timestamp (.group m 1)
         :level (.group m 3)
         :source (.group m 4)
         :ps (.group m 6)
         :slot (.group m 8)
         :instance_id (parse-long (.group m 9))
         :cloud (.group m 10)}
        (parse-message-attrs (.group m 13))))))

(def nginx-access-re
  ;                                                                                                                                                                                                           http_host                                                               http_method,_url,_version       http_status,_bytes,_referrer,_user_agent,_domain
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?[\-\+]\d\d:00) [0-9\.]+ [a-z0-7]+\.([a-z]+) ([a-zA-Z0-9\/\-\_\.]+)(\[([a-zA-Z0-9\.]+)\])?:? - (([a-z0-9\-\_]+)?\.(\d+)@([a-z.\-]+))?([a-zA-Z0-9\-\_\.]+)?( -)? ([0-9\.]+) - - \[\d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d -\d\d00\] \"([a-zA-Z]+) (\S+) HTTP\/(...)\" (\d+) (\d+) \"([^\"]+)\" \"([^\"]+)\" (\S+)$")

(defn parse-nginx-access-line [l]
  (let [m (re-matcher nginx-access-re l)]
    (if (.find m)
      (let [source (.group m 4)]
        (if (= source "nginx")
          {:event_type "nginx_access"
           :timestamp (.group m 1)
           :level (.group m 3)
           :source (.group m 4)
           :ps (.group m 6)
           :slot (.group m 8)
           :instance_id (parse-long (.group m 9))
           :cloud (.group m 10)
           :http_host (.group m 13)
           :http_method (.group m 14)
           :http_url (.group m 15)
           :http_version (.group m 16)
           :http_status (parse-long (.group m 17))
           :http_bytes (parse-long (.group m 18))
           :http_referrer (.group m 19)
           :http_user_agent (.group m 20)
           :http_domain (.group m 21)})))))

(def nginx-error-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?[\-\+]\d\d:00) [0-9\.]+ [a-z0-7]+\.([a-z]+) ([a-zA-Z0-9\/\-\_\.]+)(\[([a-zA-Z0-9\.]+)\])?:? - (([a-z0-9\-\_]+)?\.(\d+)@([a-z.\-]+))?([a-zA-Z0-9\-\_\.]+)?( -)? .* \[error\] (.*)$")

(defn parse-nginx-error-line [l]
  (let [m (re-matcher nginx-error-re l)]
    (if (.find m)
      (let [source (.group m 4)]
        (if (= source "nginx")
          {:timestamp (.group m 1)
           :level (.group m 3)
           :source (.group m 4)
           :ps (.group m 6)
           :slot (.group m 8)
           :instance_id (parse-long (.group m 9))
           :cloud (.group m 10)
           :message (.group m 13)})))))

(def varnish-access-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?[\-\+]\d\d:00) [0-9\.]+ [a-z0-7]+\.([a-z]+) varnish\[(\d+)\] - ([a-z4-6\-]+)?\.(\d+)@([a-z.\-]+\.com) - [0-9\.]+ - - .*\" (\d\d\d) .*$")

(defn parse-varnish-access-line [l]
  (let [m (re-matcher varnish-access-re l)]
    (if (.find m)
       {:event_type "varnish_access"
        :timestamp (.group m 1)
        :level (.group m 3)
        :source "varnish"
        :ps (.group m 4)
        :slot (.group m 5)
        :instance_id (parse-long (.group m 6))
        :cloud (.group m 7)
        :http_status (parse-long (.group m 8))})))

(defn log [& data]
  (apply log/log :ns "parse" data))

(defn parse-line [l]
  (try
    (or (parse-nginx-access-line l)
        (parse-nginx-error-line l)
        (parse-varnish-access-line l)
        (parse-standard-line l))
    (catch Exception e
      (log :fn "parse-line" :at "exception" :line l)
      (throw e))))
