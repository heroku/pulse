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
  #"([a-zA-Z0-9\_\-\.]+)=?(([a-zA-Z0-9\.\-\_\.\:\/]+)|(\"([^\"]+)\"))?")

(defn parse-msg-attrs [msg]
  (let [m (re-matcher attrs-re msg)]
    (loop [a (java.util.HashMap.)]
      (if (.find m)
        (let [v (or (.group m 3) (.group m 5))]
          (do
            (.put a
              (keyword (.group m 1))
              (if (nil? v) true (coerce-val v)))
            (recur a)))
        a))))

(defn parse-long [s]
  (if s (Long/parseLong s)))

(def base-re
  #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?[\-\+]\d\d:00) [0-9\.]+ [a-z0-7]+\.([a-z]+) ([a-zA-Z0-9\/\-\_\.]+)(\[([a-zA-Z0-9\.]+)\])?:? - ((([a-z0-9\-\_]+)?\.(\d+)@([a-z.\-]+))|(([a-z\_\-]+)-(\d+)\.([a-z.\-]+)))?([a-zA-Z0-9\-\_\.]+)?( -)? (.*)$")

(defn parse-base [l]
  (let [m (re-matcher base-re l)]
    (if (.find m)
      {:timestamp (.group m 1)
       :level (.group m 3)
       :source (.group m 4)
       :ps (.group m 6)
       :slot (or (.group m 9) (.group m 13))
       :instance_id (parse-long (or (.group m 10) (.group m 14)))
       :cloud (or (.group m 11) (.group m 15))
       :msg (.group m 18)})))

(defn inflate-default [evt]
  (merge evt (parse-msg-attrs (:msg evt))))

(def nginx-access-re
  ;  http_host    ;http_user                                                       http_method,_url,_version       http_status,_bytes,_referrer,_user_agent,_domain
  #"^([0-9\.]+) - (\S+) \[\d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d [\-\+]\d\d00\] \"([a-zA-Z]+) (\S+) HTTP\/(...)\" (\d+) (\d+) \"([^\"]+)\" \"([^\"]+)\" (\S+)$")

(defn inflate-nginx-access [evt]
  (if (= (:source evt) "nginx")
    (let [m (re-matcher nginx-access-re (:msg evt))]
      (if (.find m)
        (merge evt
          {:http_host (.group m 1)
           :http_user (.group m 2)
           :http_method (.group m 3)
           :http_url (.group m 4)
           :http_version (.group m 5)
           :http_status (parse-long (.group m 6))
           :http_bytes (parse-long (.group m 7))
           :http_referrer (.group m 8)
           :http_user_agent (.group m 9)
           :http_domain (.group m 10)})))))

(def varnish-access-re
  #"^[0-9\.]+ - - .*\" (\d\d\d) .*$")

(defn inflate-varnish-access [evt]
  (if (= (:source evt) "varnish")
    (let [m (re-matcher varnish-access-re (:msg evt))]
      (if (.find m)
        (assoc evt :http_status (parse-long (.group m 1)))))))

(defn log [& data]
  (apply log/log :ns "parse" data))

(defn parse-line [l]
  (try
    (if-let [evt (parse-base l)]
      (or (inflate-nginx-access evt)
          (inflate-varnish-access evt)
          (inflate-default evt)))
    (catch Exception e
      (log :fn "parse-line" :at "exception" :line l)
      (throw e))))
