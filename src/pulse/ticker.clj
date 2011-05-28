(ns pulse.ticker)

(defn -main []
  (loop []
    (println "ticking")
    (Thread/sleep 1000)
    (recur)))
