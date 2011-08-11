(ns pulse.web
  (:use ring.util.response)
  (:use ring.middleware.params)
  (:use ring.middleware.session)
  (:use ring.middleware.session.cookie)
  (:use ring.middleware.basic-auth)
  (:use ring.middleware.file)
  (:use ring.middleware.file-info)
  (:use ring.middleware.stacktrace)
  (:use ring.adapter.jetty)
  (:use hiccup.core)
  (:require [ring.util.codec :as codec])
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
    ["nginx err/min"      "nginx-errors-per-minute"]
    ["event/sec"          "events-per-second"]]
   [["varnish req/sec"    "varnish-requests-per-second"]
    ["varnish 500/min"    "varnish-500-per-minute"]
    ["varnish 502/min"    "varnish-502-per-minute"]
    ["varnish 503/min"    "varnish-503-per-minute"]
    ["varnish 504/min"    "varnish-504-per-minute"]
    ["varnish purge/min"  "varnish-purges-per-minute"]
    ["rdv join/min"       "rendezvous-joins-per-minute"]]
   [["hermes req/sec"     "hermes-requests-per-second"]
    ["hermes H10/min"     "hermes-h10-per-minute"]
    ["hermes H11/min"     "hermes-h11-per-minute"]
    ["hermes H12/min"     "hermes-h12-per-minute"]
    ["hermes H13/min"     "hermes-h13-per-minute"]
    ["hermes H14/min"     "hermes-h14-per-minute"]
    ["hermes H99/min"     "hermes-h99-per-minute"]]
   [["ps up"              "ps-up-total-last"]
    ["ps up web"          "ps-up-web-last"]
    ["ps up worker"       "ps-up-worker-last"]
    ["ps up other"        "ps-up-other-last"]
    ["ps starting"        "ps-starting-last"]
    ["ps crashed"         "ps-crashed-last"]
    ["ps created"         "ps-created-last"]]
   [["ps running"         "ps-running-total-last"]
    ["ps running web"     "ps-running-web-last"]
    ["ps running worker"  "ps-running-worker-last"]
    ["ps running other"   "ps-running-other-last"]
    ["ps launch time"     "ps-launch-time-mean"]
    ["ps tout/min"        "ps-timeouts-per-minute"]
    ["ps lost"            "ps-lost-last"]]
   [["ps run req/min"     "ps-run-requests-per-minute"]
    ["ps runs/min"        "ps-runs-per-minute"]
    ["ps stop req/min"    "ps-stop-requests-per-minute"]
    ["ps stops/min"       "ps-stops-per-minute"]
    ["ps idle/min"        "ps-idles-per-minute"]
    ["ps unidle/min"      "ps-unidles-per-minute"]
    ["ps conv/sec"        "ps-converges-per-second"]]
   [["amqp pub/sec"       "amqp-publishes-per-second"]
    ["amqp rec/sec"       "amqp-receives-per-second"]
    ["amqp tout/min"      "amqp-timeouts-per-minute"]
    ["slugc push/min"     "slugc-pushes-per-minute"]
    ["slugc fail/min"     "slugc-fails-per-minute"]
    ["slugc err/min"      "slugc-errors-per-minute"]
    ["release/min"        "releases-per-minute"]]
   [["railgun err/min"    "railgun-errors-per-minute"]
    ["psmgr err/min"      "psmgr-errors-per-minute"]
    ["api err/min"        "api-errors-per-minute"]
    ["codex err/min"      "codex-errors-per-minute"]
    ["gitproxy err/min"   "gitproxy-errors-per-minute"]
    ["shen err/min"       "shen-errors-per-minute"]
    ["hermes error/min"   "hermes-errors-per-minute"]]])

(defn view []
  (html
    [:html
      [:head
        [:title "Pulse"]
        [:link {:rel "stylesheet" :media "screen" :type "text/css" :href "/stylesheets/pulse.css"}]
        [:script {:type "text/javascript" :src "javascripts/jquery-1.6.2.js"}]
        [:script {:type "text/javascript" :src "javascripts/jquery.sparkline.js"}]
        [:script {:type "text/javascript" :src "javascripts/pulse.js"}]
        [:script {:type "text/javascript" :src (conf/pulse-scales-url)}]]
      [:body
        [:h1 {:align "center"} "Pulse"]
        [:table {:align "center" :border 0 :cellspacing 10}
          (for [row graphs]
            [:tr
              (for [[label key] row]
                [:td {:align "center"}
                  [:span {:id (str key "-sparkline")}] [:br]
                  (str label ": ") [:span {:id (str key "-scalar")}]])])]]]))

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

(defn api-handler [req]
  {:status 200,
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string @stats-buffs-a)})

(defn wrap-cros-headers [handler]
  (fn [req]
    (let [resp (handler req)]
      (update-in resp [:headers] assoc
        "Access-Control-Allow-Origin" "*"
        "Access-Control-Allow-Methods" "GET, OPTIONS"
        "Access-Control-Allow-Headers" "X-Requested-With, Authorization"))))

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
  (condp = uri
    "/stats"
      (stats-handler req)
    "/api"
      (api-handler req)
    (static-handler req)))

(defn wrap-openid-proxy [handler]
  (fn [req]
    (cond
      (= [:get "/auth"] [(:request-method req) (:uri req)])
        (if (= (conf/proxy-secret) (get (:params req) "proxy_secret"))
          (-> (redirect "/")
            (update-in [:session] assoc :authorized true))
          {:status 403 :headers {"Content-Type" "text/plain"} :body "not authorized\n"})
      (not (:authorized (:session req)))
        (let [callback-url (str (if (conf/force-https?) "https" "http") "://" (:server-name req) ":" (if (conf/force-https?) 443 (:server-port req)) "/auth")]
          (redirect (str (conf/proxy-url) "?" "callback_url=" (codec/url-encode callback-url))))
      :authorized
        (handler req))))

(defn api-auth? [_ password]
  (= (conf/api-password) password))

(defn wrap-force-https [handler]
  (fn [{:keys [headers server-name uri] :as req}]
    (if (and (conf/force-https?) (not= (get headers "x-forwarded-proto") "https"))
      (redirect (format "https://%s%s" server-name uri))
      (handler req))))

(defn wrap-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [method (name request-method)
          start (util/millis)]
      (log "req method=%s uri=%s event=start" method uri)
      (let [{:keys [status] :as resp} (handler req)
            elapsed (- (util/millis) start)]
        (log "req method=%s uri=%s status=%d event=finish elapsed=%.3f" method uri status (/ elapsed 1000.0))
        resp))))

(defn wrap-only [handler wrapper pred]
  (fn [req]
    (if (pred req)
      ((wrapper handler) req)
      (handler req))))

(defn wrap-debug [handler]
  (fn [req]
    (prn req)
    (handler req)))

(def app
  (-> core-app
    (wrap-only #(wrap-basic-auth % api-auth?) #(= "/api" (:uri %)))
    (wrap-only wrap-cros-headers #(= "/api" (:uri %)))
    (wrap-only wrap-openid-proxy #(not= "/api" (:uri %)))
    (wrap-session {:store (cookie-store {:key (conf/session-secret)})})
    (wrap-params)
    (wrap-force-https)
    (wrap-logging)
    (wrap-stacktrace)))

(defn -main []
  (log "init event=start")
  (util/spawn init-buffer)
  (run-jetty #'app {:port (conf/port) :join false})
  (log "init event=finish"))
