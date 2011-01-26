(ns pulse.pipe
  (:import (java.io )))

(defn pipe-lines [f]
  (loop []
    (when-let [line (.readLine *in*)]
      (f line)
      (recur))))

