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
    (with-open [out (-> (.getInputStream proc) (InputStreamReader.) (BufferedReader.))]
      (loop []
        (when-let [line (.readLine out)]
          (handler line)
          (recur))))))
