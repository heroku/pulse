(ns pulse.log
  (:require [pulse.conf :as conf]))

(defn log [fmt & args]
  (locking *out*
    (apply printf (str "pulse deploy=%s " fmt "\n") (conf/deploy) args)
    (flush)))
