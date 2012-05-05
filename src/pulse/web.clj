(ns pulse.web
  (:use ring.util.response
        ring.adapter.jetty)
  (:require [pulse.conf :as conf]
            [pulse.util :as util]
            [pulse.log :as log]))

(defn log [& data]
  (apply log/log :ns "web" data))

(defn wrap-canonical-host [handler canonical-host]
  (fn [req]
    (if (= (:server-name req) canonical-host)
      (handler req)
      (redirect (format "%s://%s/"
                  (if (conf/force-https?) "https" "http")
                  canonical-host)))))

(defn core-app [{:keys [uri] :as req}]
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Pulse no longer provides a web interface."})

(defn wrap-force-https [handler]
  (fn [{:keys [headers server-name uri] :as req}]
    (if (and (conf/force-https?) (not= (get headers "x-forwarded-proto") "https"))
      (redirect (format "https://%s%s" server-name uri))
      (handler req))))

(defn wrap-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [method (name request-method)
          start (util/millis)]
      (log :fn "wrap-logging" :method method :uri uri :at "start")
      (let [{:keys [status] :as resp} (handler req)
            elapsed (- (util/millis) start)]
        (log :fn "warp-logging" :method method :uri uri :status status :at "finish" :elapsed (/ elapsed 1000.0))
        resp))))

(defn wrap-only [handler wrapper pred]
  (let [wrapped-handler (wrapper handler)]
    (fn [req]
      (if (pred req)
        (wrapped-handler req)
        (handler req)))))

(defn app []
  (-> core-app
    (wrap-canonical-host (conf/canonical-host))
    (wrap-force-https)
    (wrap-logging)))

(defn -main []
  (log :fn "main" :at "start")
  (run-jetty (app) {:port (conf/port) :join false})
  (log :fn "main" :at "finish"))
