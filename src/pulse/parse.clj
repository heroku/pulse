(ns pulse.parse
  (:import java.text.SimpleDateFormat))

(def tail-re
  #"^==>")

(defn tail-line? [l]
  (or (= "" l) (re-find tail-re l)))

(def long-re
  #"^-?[0-9]+$")

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
  #"([a-z_]+)=([a-zA-Z0-9._-]*)")

(defn parse-message-attrs [msg]
  (reduce
    (fn [h [_ k v]] (assoc h k (coerce-val v)))
    {}
    (re-seq attrs-re msg)))

(def time-formatter
  (SimpleDateFormat. "MMM dd HH:mm:ss"))

(def standard-re
  #"^([a-zA-Z]{3} \d\d \d\d:\d\d:\d\d) ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) ([a-z]+)\[(\d+)\]: (.*)$")

(defn parse-standard-line [l]
  (if-let [s-finds (re-find standard-re l)]
    (let [timestamp-src (.getTime (.parse time-formatter (get s-finds 1)))
          slot (get s-finds 2)
          ion-id (Long/parseLong (get s-finds 3))
          cloud (get s-finds 4)
          component (get s-finds 5)
          pid (Long/parseLong (get s-finds 6))
          message (get s-finds 7)
          message-attrs (parse-message-attrs message)]
    (merge
      {"level" "info"
       "timestamp_src" timestamp-src
       "slot" slot
       "ion_id" ion-id
       "cloud" cloud
       "component" component
       "pid" pid
       "message" message}
      message-attrs))))

(def nginx-access-re
  #"^([a-zA-Z]{3} \d\d \d\d:\d\d:\d\d) ([a-z4-6]+)?\.(\d+)@([a-z.]+\.com) nginx: \d\d\/[a-zA-z]{3}\/\d\d\d\d:\d\d:\d\d:\d\d -0800 \| ([a-zA-Z0-9\.\-]+) \| [A-Z]{3,6} (\S+) HTTP\/(...) \| [0-9\.]+ \| (\d+) \| (https?) \| (\d+)$")

(defn parse-nginx-access-line [l]
  (if-let [n-finds (re-find nginx-access-re l)]
    (let [timestamp-src (.getTime (.parse time-formatter (get n-finds 1)))
          slot (get n-finds 2)
          ion-id (Long/parseLong (get n-finds 3))
          cloud (get n-finds 4)
          http-domain (get n-finds 5)
          http-url (get n-finds 6)
          http-version (get n-finds 7)
          http-bytes (Long/parseLong (get n-finds 8))
          http-protocol (get n-finds 9)
          http-status (Long/parseLong (get n-finds 10))]
       {"level" "info"
        "timestamp_src" timestamp-src
        "slot" slot
        "ion_id" ion-id
        "cloud" cloud
        "http_domain" http-domain
        "http_url" http-url
        "http_version" http-version
        "http_bytes" http-bytes
        "http_protocol" http-protocol
        "http_status" http-status})))

(defn parse-line [l]
  (if-not (tail-line? l)
    (or (parse-standard-line l)
        (parse-nginx-access-line l))))

