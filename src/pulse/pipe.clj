(ns pulse.pipe
  (:import clojure.lang.LineNumberingPushbackReader)
  (:import (java.io InputStreamReader BufferedReader)))

(set! *warn-on-reflection* true)

(defn stdin-lines [handler]
  (loop []
    (when-let [line (.readLine ^LineNumberingPushbackReader *in*)]
      (handler line)
      (recur))))

(defn shell-lines [cmd-list handler]
  (let [rt (Runtime/getRuntime)
       proc (.exec rt ^"[Ljava.lang.String;" (into-array cmd-list))]
    (with-open [out (-> (.getInputStream proc)
                      (InputStreamReader.) (BufferedReader.))]
      (loop []
        (when-let [line (.readLine out)]
          (handler line)
          (recur))))))

(defn catched [f]
  (fn []
    (try
      (f)
      (catch Exception e
        (.printStackTrace e)
        (throw e)))))

(defn spawn [f]
  (let [t (Thread. ^Runnable (catched f))]
    (.start t)
    t))

(def count-a
  (atom 0))

(defn handle-line [line]
  (let [c (swap! count-a inc)]
    (if (zero? (rem c 1000))
      (println c))))

(def public-face-ips
  ["75.101.163.44" "174.129.212.2" "174.129.171.11"
   "50.16.215.196", "75.101.145.87", "184.72.68.8"])

(doseq [ip public-face-ips]
  (spawn (fn []
    (let [cmd-list ["ssh" (str "root@" ip) "tail" "-f" "/var/log/messages"]]
      (shell-lines cmd-list handle-line)))))
