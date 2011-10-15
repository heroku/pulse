(ns pulse.log
  (:require [pulse.conf :as conf]))

(defn log [fmt & args]
  (locking *out*
    (apply printf (str "app=pulse deploy=%s " fmt "\n") (conf/deploy) args)
    (flush)))
