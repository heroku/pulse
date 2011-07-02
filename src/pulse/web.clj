(ns pulse.web
  (:use ring.middleware.file)
  (:use ring.middleware.file-info)
  (:use ring.middleware.basic-auth)
  (:use ring.middleware.stacktrace)
  (:use ring.adapter.jetty)
  (:use hiccup.core)
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util]))

(defn log [msg & args]
  (apply util/log (str "web " msg) args))

(def graphs
  [[["nginx req/sec"      "nginx-requests-per-second"]
    ["nginx 500/min"      "nginx-500-per-minute"]
    ["nginx 502/min"      "nginx-502-per-minute"]
    ["nginx 503/min"      "nginx-503-per-minute"]
    ["nginx 504/min"      "nginx-504-per-minute"]
    ["nginx err/min"      "nginx-errors-per-minute"]]
   [["varnish reqs/sec"   "varnish-requests-per-second"]
    ["varnish 500/min"    "varnish-500-per-minute"]
    ["varnish 502/min"    "varnish-502-per-minute"]
    ["varnish 503/min"    "varnish-503-per-minute"]
    ["varnish 504/min"    "varnish-504-per-minute"]]
   [["hermes req/sec"     "hermes-requests-per-second"]
    ["hermes H10/min"     "hermes-h10-per-minute"]
    ["hermes H11/min"     "hermes-h11-per-minute"]
    ["hermes H12/min"     "hermes-h12-per-minute"]
    ["hermes H13/min"     "hermes-h13-per-minute"]
    ["hermes H99/min"     "hermes-h99-per-minute"]]
   [["ps up"              "ps-up-total-last"]
    ["ps up web"          "ps-up-web-last"]
    ["ps up worker"       "ps-up-worker-last"]
    ["ps up other"        "ps-up-other-last"]
    ["ps starting"        "ps-starting-last"]
    ["ps crashed"         "ps-crashed-last"]]
   [["ps running"         "ps-running-total-last"]
    ["ps running web"     "ps-running-web-last"]
    ["ps running worker"  "ps-running-worker-last"]
    ["ps running other"   "ps-running-other-last"]
    ["ps created"         "ps-created-last"]]
   [["ps run reqs/min"    "ps-run-requests-per-minute"]
    ["ps runs/min"        "ps-runs-per-minute"]
    ["ps stop reqs/min"   "ps-stop-requests-per-minute"]
    ["ps stops/min"       "ps-stops-per-minute"]
    ["ps convs/sec"       "ps-converges-per-second"]
    ["ps lost"            "ps-lost-last"]]
   [["amqp pub/sec"       "amqp-publishes-per-second"]
    ["amqp rec/sec"       "amqp-receives-per-second"]
    ["amqp touts/min"     "amqp-timeouts-per-minute"]
    ["events/sec"         "events-per-second"]]])

(defn view []
  (html
    [:html
      [:head
        [:title "Heroku Pulse"]
        [:script {:type "text/javascript" :src "javascripts/jquery-1.5.js"}]
        [:script {:type "text/javascript" :src "javascripts/jquery.sparkline.js"}]
        [:script {:type "text/javascript" :src "javascripts/pulse.js"}]]
      [:body
        [:h1 {:align "center"} "Heroku Pulse"]
        [:table {:align "center" :border 0 :cellspacing 10}
          (for [row graphs]
            [:tr
              (for [[label key] row]
                [:td {:align "center"}
                  (str label ": ") [:span {:id (str key "-scalar")}] [:br]
                  [:span {:id (str key "-sparkline")}]])])]]]))

(defn view-handler [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (view)})

(def static-handler
  (-> view-handler
    (wrap-file "public")
    (wrap-file-info)))

(defonce stats-buffs-a
  (atom {}))

(defn stats-handler [req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string @stats-buffs-a)})

(defn buff-append [buff val limit]
  (if (< (count buff) limit)
    (conj (or buff (clojure.lang.PersistentQueue/EMPTY)) val)
    (conj (pop buff) val)))

(defn init-buffer []
  (log "init_buffer")
  (let [rd (redis/init {:url (conf/redis-url)})]
    (redis/subscribe rd ["stats.merged"] (fn [_ stat-json]
      (let [[stat-name stat-val] (json/parse-string stat-json)]
        (swap! stats-buffs-a util/update stat-name #(buff-append % stat-val 120)))))))

(defn core-app [{:keys [uri] :as req}]
  (if (= uri "/stats")
    (stats-handler req)
    (static-handler req)))

(defn web-auth? [& creds]
  (= (conf/web-auth) creds))

(defn wrap-logging [handler]
  (fn [{:keys [uri request-method] :as req}]
    (let [method (name request-method)
          start (util/millis)]
      (log "req method=%s uri=%s event=start" method uri)
      (let [{:keys [status] :as resp} (handler req)
            elapsed (- (util/millis) start)]
        (log "req method=%s uri=%s event=finish elapsed=%.3f" method uri (/ elapsed 1000.0))
        resp))))

(def app
  (-> core-app
    (wrap-basic-auth web-auth?)
    (wrap-logging)
    (wrap-stacktrace)))

(defn -main []
  (log "init event=start")
  (util/spawn init-buffer)
  (run-jetty #'app {:port (conf/port) :join false})
  (log "init event=finish"))
