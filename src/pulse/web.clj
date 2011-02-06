(ns pulse.web
  (:use ring.middleware.file)
  (:use ring.middleware.file-info)
  (:use ring.adapter.jetty-async)
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util]))

(def rd
  (redis/init {:url conf/redis-url}))

(def stats-connections
  (atom #{}))

(defn stats-handler [req]
  {:async :websocket
   :reactor
     (fn [emit]
       (fn [{:keys [type data]}]
         (cond
           (= type :connect)
             (do
               (util/log "web connect")
               (swap! stats-connections conj emit))
           (= type :disconnect)
             (do
               (util/log "web disconnect")
               (swap! stats-connections disj emit)))))})

(def static-handler
  (-> (constantly
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "not found"})
    (wrap-file "public")
    (wrap-file-info)))

(defn app [{:keys [uri] :as req}]
  (if (= uri "/stats")
    (stats-handler req)
    (static-handler req)))

(defn receive [_ stat-json]
  (let [[stat-key _] (json/parse-string stat-json)
        emits @stats-connections]
    (if (= stat-key "render")
      (util/log "web render num_connections=%d" (count emits)))
    (doseq [emit emits]
      (emit {:type :message :data stat-json}))))

(defn -main []
  (run-jetty-async #'app {:port conf/port :join? false})
  (redis/subscribe rd ["stats"] receive))
