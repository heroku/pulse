(ns pulse.io
  (:require [pulse.util :as util])
  (:require [pulse.queue :as queue])
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:import (clojure.lang LineNumberingPushbackReader))
  (:import (java.io InputStreamReader BufferedReader PrintWriter))
  (:import (java.net Socket ConnectException)))

(set! *warn-on-reflection* true)

(defn log [msg & args]
  (apply util/log (str "io " msg) args))

(defn bleeder [aorta-url handler]
  (let [{:keys [^String host ^Integer port auth]} (util/url-parse aorta-url)]
    (loop []
      (log "bleed event=connect aorta_host=%s" host)
      (try
        (with-open [socket (Socket. host port)
                    in     (-> (.getInputStream socket) (InputStreamReader.) (BufferedReader.))
                    out    (-> (.getOutputStream socket) (PrintWriter.))]
          (.println out auth)
          (.flush out)
          (loop []
            (when-let [line (.readLine in)]
              (handler line)
              (recur))))
        (util/log "bleed event=eof aorta_host=%s" host)
        (catch ConnectException e
          (util/log "bleed event=exception aorta_host=%s" host)))
      (Thread/sleep 100)
      (recur))))

(defn init-bleeders [aorta-urls apply-queue]
  (log "init_bleeders")
  (doseq [aorta-url aorta-urls]
    (let [{aorta-host :host} (util/url-parse aorta-url)]
      (log "init_bleeder aorta_host=%s" aorta-host)
      (util/spawn (fn []
        (bleeder aorta-url (fn [line]
          (queue/offer apply-queue [aorta-host line]))))))))

(defn init-publishers [publish-queue redis-url chan workers]
  (let [redis (redis/init {:url redis-url})]
    (log "init_publishers chan=%s" chan)
    (dotimes [i workers]
      (log "init_publisher chan=%s index=%d" chan i)
      (util/spawn-loop (fn []
        (let [data (queue/take publish-queue)
              data-str (try
                         (json/generate-string data)
                         (catch Exception e
                           (log "publish event=error data=%s" (pr-str data))
                           (throw e)))]
          (redis/publish redis chan data-str)))))))

(defn init-subscriber [redis-url chan apply-queue]
  (let [redis (redis/init {:url redis-url})]
    (log "init-subscribe chan=%s" chan)
    (redis/subscribe redis [chan]
      (fn [_ data-json]
        (queue/offer apply-queue (json/parse-string data-json))))))
