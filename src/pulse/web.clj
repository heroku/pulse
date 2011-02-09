(ns pulse.web
  (:use ring.middleware.file)
  (:use ring.middleware.file-info)
  (:use ring.adapter.jetty-async)
  (:use hiccup.core)
  (:require [clj-json.core :as json])
  (:require [clj-redis.client :as redis])
  (:require [pulse.conf :as conf])
  (:require [pulse.util :as util]))

(def graphs
  [[["nginx req/sec"   "nginx_requests_per_second"]
    ["nginx err/min"   "nginx_errors_per_minute"]
    ["nginx 500/min"   "nginx_500_per_minute"]
    ["nginx 502/min"   "nginx_502_per_minute"]
    ["nginx 503/min"   "nginx_503_per_minute"]
    ["nginx 504/min"   "nginx_504_per_minute"]]
   [["hermes req/sec"  "hermes_requests_per_second"]
    ["hermes H10/min"  "hermes_H10_per_minute"]
    ["hermes H11/min"  "hermes_H11_per_minute"]
    ["hermes H12/min"  "hermes_H12_per_minute"]
    ["hermes H13/min"  "hermes_H13_per_minute"]
    ["hermes H99/min"  "hermes_H99_per_minute"]]
   [["amqp pub/sec"    "amqp_publishes_per_second"]
    ["amqp rec/sec"    "amqp_receives_per_second"]
    ["amqp tim/min"    "amqp_timeouts_per_minute"]
    ["slugc inv/min"   "slugc_invokes_per_minute"]
    ["slugc fail/min"  "slugc_fails_per_minute"]
    ["slugc err/min"   "slugc_errors_per_minute"]]
   [["ps converge/sec" "ps_converges_per_second"]
    ["ps run/min"      "ps_runs_per_minute"]
    ["ps return/min"   "ps_returns_per_minute"]
    ["ps trap/min"     "ps_traps_per_minute"]
    ["ps lost"         "ps_lost"]]
   [["events/sec"      "events_per_second"]
    ["internal/sec"    "events_internal_per_second"]
    ["external/sec"    "events_external_per_second"]
    ["unparsed/sec"    "events_unparsed_per_second"]]])

(defn view []
  (html
    [:html
      [:head
        [:title "Heroku Ops Pulse"]
        [:script {:type "text/javascript" :src "javascripts/jquery-1.5.js"}]
        [:script {:type "text/javascript" :src "javascripts/jquery.sparkline.js"}]
        [:script {:type "text/javascript" :src "javascripts/pulse.js"}]
        [:script {:type "text/javascript"}
          (format "var pulse_websocket_url ='%s';" conf/websocket-url)]]
      [:body
        [:h1 {"align" "center"} "Heroku Ops Pulse"]
        [:table {"align" "center" "border" 0 "cellspacing" 10}
          (for [row graphs]
            [:tr
              (for [[label key] row]
                [:td
                  (str label ": ") [:b [:span {:id (str key "_scalar")}]] [:br]
                  [:span {:id (str key "_sparkline")}]])])]]]))

(defn view-handler [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (view)})

(def static-handler
  (-> view-handler
    (wrap-file "public")
    (wrap-file-info)))

(defonce rd
  (redis/init {:url conf/redis-url}))

(defonce stats-connections
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

(defn receive [_ stat-json]
  (let [[stat-key _] (json/parse-string stat-json)
        emits @stats-connections]
    (if (= stat-key "render")
      (util/log "web render num_connections=%d" (count emits)))
    (doseq [emit emits]
      (emit {:type :message :data stat-json}))))

(defn app [{:keys [uri] :as req}]
  (if (= uri "/stats")
    (stats-handler req)
    (static-handler req)))

(defn -main []
  (run-jetty-async #'app {:port conf/port :join? false})
  (redis/subscribe rd ["stats"] receive))
