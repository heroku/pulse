(ns pulse.util)

(set! *warn-on-reflection* true)

(defn spawn [f]
  (let [t (Thread. ^Runnable f)]
    (.start t)
    t))

(defn log [fmt & args]
  (locking *out*
    (apply printf (str fmt "\n") args)
    (flush)))
