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

(defn parse-msg-attrs [msg]
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

(defn inflate-default [evt]
  (merge evt (parse-msg-attrs (:msg evt))))

(def nginx-access-re
  ;  http_host                                                               http_method,_url,_version       http_status,_bytes,_referrer,_user_agent,_domain
  #"^([0-9\.]+) - - \[\d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d [\-\+]\d\d00\] \"([a-zA-Z]+) (\S+) HTTP\/(...)\" (\d+) (\d+) \"([^\"]+)\" \"([^\"]+)\" (\S+)$")

(defn inflate-nginx-access [evt]
  (if (= (:source evt) "nginx")
    (let [m (re-matcher nginx-access-re (:msg evt))]
      (if (.find m)
        (merge evt
          {:http_host (.group m 1)
           :http_method (.group m 2)
           :http_url (.group m 3)
           :http_version (.group m 4)
           :http_status (parse-long (.group m 5))
           :http_bytes (parse-long (.group m 6))
           :http_referrer (.group m 7)
           :http_user_agent (.group m 8)
           :http_domain (.group m 9)})))))

(def varnish-access-re
  #"^[0-9\.]+ - - .*\" (\d\d\d) .*$")

(defn inflate-varnish-access [evt]
  (if (= (:source evt) "varnish")
    (let [m (re-matcher varnish-access-re (:msg evt))]
      (if (.find m)
        (assoc evt :http_status (parse-long (.group m 1)))))))

(defn log [& data]
  (apply log/log :ns "parse" data))

(defn parse-evt [evt]
  (try
    (or (inflate-nginx-access evt)
        (inflate-varnish-access evt)
        (inflate-default evt))
    (catch Exception e
      (log :fn "parse-line" :at "exception" :evt evt)
      (throw e))))

