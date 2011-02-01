(ns pulse.util)

(set! *warn-on-reflection* true)

(defn log [fmt & args]
  (locking *out*
    (apply printf (str fmt "\n") args)
    (flush)))
