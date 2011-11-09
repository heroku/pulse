(ns pulse.log
  (:require [pulse.conf :as conf]
            [clojure.string :as str]))

(defn- re-match? [re s]
  (let [m (re-matcher re s)]
    (.find m)))

(defn- unparse1 [[k v]]
  (str (name k) "="
    (cond
      (or (true? v) (false? v))
        v
      (keyword? v)
        (name v)
      (float? v)
        (format "%.3f" v)
      (number? v)
        v
      (string? v)
        (cond
          (re-match? #"^[a-zA-Z0-9\:\.\-\_]+$" v)
            v
          (neg? (.indexOf v "\""))
            (str "\"" v "\"")
          :else
            (str "'" v "'"))
      :else
        "?")))

(defn- unparse [data]
  (->> data
    (partition 2)
    (map unparse1)
    (str/join " ")))

(defn log [& data]
  (let [msg (unparse (list* :app "leech" :deploy (conf/deploy) data))]
    (locking *out*
      (println msg))))
