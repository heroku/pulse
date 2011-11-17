(ns pulse.web
  (:use ring.util.response
        ring.middleware.params
        ring.middleware.session
        ring.middleware.session.cookie
        ring.middleware.basic-auth
        ring.middleware.file
        ring.middleware.file-info
        ring.middleware.stacktrace
        ring.adapter.jetty
        hiccup.core)
  (:require [ring.util.codec :as codec]
            [clj-json.core :as json]
            [clj-redis.client :as redis]
            [pulse.conf :as conf]
            [pulse.util :as util]
            [pulse.log :as log]))

(defn log [& data]
  (apply log/log :ns "web" data))

(def graphs-index
  [[["nginx req/sec"       "nginx-requests-per-second"]
    ["nginx 500/min"       "nginx-500-per-minute"]
    ["nginx 502/min"       "nginx-502-per-minute"]
    ["nginx 503/min"       "nginx-503-per-minute"]
    ["nginx 504/min"       "nginx-504-per-minute"]
    ["nginx err/min"       "nginx-errors-per-minute"]
    ["event/sec"           "events-per-second"]]
   [["varnish req/sec"     "varnish-requests-per-second"]
    ["varnish 500/min"     "varnish-500-per-minute"]
    ["varnish 502/min"     "varnish-502-per-minute"]
    ["varnish 503/min"     "varnish-503-per-minute"]
    ["varnish 504/min"     "varnish-504-per-minute"]
    ["varnish purge/min"   "varnish-purges-per-minute"]
    ["rdv join/min"        "rendezvous-joins-per-minute"]]
   [["hermes req/sec"      "hermes-requests-per-second"]
    ["hermes H10/min"      "hermes-h10-per-minute"]
    ["hermes H11/min"      "hermes-h11-per-minute"]
    ["hermes H12/min"      "hermes-h12-per-minute"]
    ["hermes H13/min"      "hermes-h13-per-minute"]
    ["hermes H14/min"      "hermes-h14-per-minute"]
    ["hermes H99/min"      "hermes-h99-per-minute"]]
   [["ps up"               "ps-up-total-last"]
    ["ps up web"           "ps-up-web-last"]
    ["ps up worker"        "ps-up-worker-last"]
    ["ps up other"         "ps-up-other-last"]
    ["ps starting"         "ps-starting-last"]
    ["ps crashed"          "ps-crashed-last"]
    ["ps created"          "ps-created-last"]]
   [["ps running"          "ps-running-total-last"]
    ["ps running web"      "ps-running-web-last"]
    ["ps running worker"   "ps-running-worker-last"]
    ["ps running other"    "ps-running-other-last"]
    ["ps launch time"      "ps-launch-time-mean"]
    ["ps tout/min"         "ps-timeouts-per-minute"]
    ["ps lost"             "ps-lost-last"]]
   [["ps run req/min"      "ps-run-requests-per-minute"]
    ["ps runs/min"         "ps-runs-per-minute"]
    ["ps stop req/min"     "ps-stop-requests-per-minute"]
    ["ps stops/min"        "ps-stops-per-minute"]
    ["ps idle/min"         "ps-idles-per-minute"]
    ["ps unidle/min"       "ps-unidles-per-minute"]
    ["ps conv/sec"         "ps-converges-per-second"]]
   [["amqp pub/sec"        "amqp-publishes-per-second"]
    ["amqp rec/sec"        "amqp-receives-per-second"]
    ["amqp tout/min"       "amqp-timeouts-per-minute"]
    ["slugc compile/min"   "slugc-compiles-per-minute"]
    ["slugc fail/min"      "slugc-failures-per-minute"]
    ["slugc err/min"       "slugc-errors-per-minute"]
    ["release/min"         "releases-per-minute"]]
   [["railgun excp/min"    "railgun-unhandled-exceptions-per-minute"]
    ["psmgr err/min"       "psmgr-errors-per-minute"]
    ["api err/min"         "api-errors-per-minute"]
    ["codex err/min"       "codex-errors-per-minute"]
    ["gitproxy err/min"    "gitproxy-errors-per-minute"]
    ["shen err/min"        "shen-errors-per-minute"]
    ["hermes error/min"    "hermes-errors-per-minute"]]])

(def graphs-routing
  [[["nginx req/sec"       "nginx-requests-per-second"]
    ["nginx 500/min"       "nginx-500-per-minute"]
    ["nginx 502/min"       "nginx-502-per-minute"]
    ["nginx 503/min"       "nginx-503-per-minute"]
    ["nginx 504/min"       "nginx-504-per-minute"]
    ["nginx err/min"       "nginx-errors-per-minute"]
    ["event/sec"           "events-per-second"]]
   [["varnish req/sec"     "varnish-requests-per-second"]
    ["varnish 500/min"     "varnish-500-per-minute"]
    ["varnish 502/min"     "varnish-502-per-minute"]
    ["varnish 503/min"     "varnish-503-per-minute"]
    ["varnish 504/min"     "varnish-504-per-minute"]
    ["varnish purge/min"   "varnish-purges-per-minute"]
    ["rdv join/min"        "rendezvous-joins-per-minute"]]
   [["hermes req/sec"      "hermes-requests-per-second"]
    ["hermes H10/min"      "hermes-h10-per-minute"]
    ["hermes H11/min"      "hermes-h11-per-minute"]
    ["hermes H12/min"      "hermes-h12-per-minute"]
    ["hermes H13/min"      "hermes-h13-per-minute"]
    ["hermes H14/min"      "hermes-h14-per-minute"]
    ["hermes H99/min"      "hermes-h99-per-minute"]],
   [["hermes req app/sec"  "hermes-requests-apps-per-second"]
    ["hermes H10 app/min"  "hermes-h10-apps-per-minute"]
    ["hermes H11 app/min"  "hermes-h11-apps-per-minute"]
    ["hermes H12 app/min"  "hermes-h12-apps-per-minute"]
    ["hermes H13 app/min"  "hermes-h13-apps-per-minute"]
    ["hermes H14 app/min"  "hermes-h14-apps-per-minute"]
    ["hermes H99 app/min"  "hermes-h99-apps-per-minute"]]])

(def graphs-runtime
  nil)

(def graphs-packaging
  [[["gitproxy con/min"    "gitproxy-connections-per-minute"]
    ["gitproxy inv/min"    "gitproxy-invalids-per-minute"]
    ["gitproxy err/min"    "gitproxy-errors-per-minute"]
    ["gitproxy succ/min"   "gitproxy-successes-per-minute"]
    ["gitproxy meta time"  "gitproxy-mean-metadata-time"]
    ["gitproxy prov time"  "gitproxy-mean-provision-time"]
    ["gitproxy serv time"  "gitproxy-mean-service-time"]]
   [["codon launch/min"    "codon-launches-per-minute"]
    ["codon rec/min"       "codon-receives-per-minute"]
    ["codon exit/min"      "codon-exits-per-minute"]
    ["codon cycle/min"     "codon-cycles-per-minute"]
    ["codon up"            "codon-up-last"]
    ["codon busy"          "codon-busy-last"]
    ["codon compiling"     "codon-compiling-last"]]
   [["codon fetch time"    "codon-mean-fetch-time"]
    ["codon stow time"     "codon-mean-stow-time"]
    ["codon fetch err/min" "codon-fetch-errors-per-minute"]
    ["codon stow err/min"  "codon-stow-errors-per-minute"]
    ["codon serv time"     "codon-mean-service-time"]
    ["codon age"           "codon-mean-age"]
    ["codon excp/min"      "codon-unhandled-exceptions-per-minute"]]
   [["slugc comp/min"      "slugc-compiles-per-minute"]
    ["slugc fail/min"      "slugc-failures-per-minute"]
    ["slugc err/min"       "slugc-errors-per-minute"]
    ["slugc succ/min"      "slugc-successes-per-minute"]
    ["slugc asp comp/min"  "slugc-aspen-compiles-per-minute"]
    ["slugc bam comp/min"  "slugc-bamboo-compiles-per-minute"]
    ["slugc ced comp/mins" "slugc-cedar-compiles-per-minute"]]
   [["slugc stow time"     "slugc-mean-stow-time"]
    ["slugc release time"  "slugc-mean-release-time"]
    ["slugc stow err/min"  "slugc-stow-errors-per-minute"]
    ["slugc rel err/min"   "slugc-release-errors-per-minute"]
    ["slugc comp time"     "slugc-mean-compile-time"]
    ["codex err/min"       "codex-errors-per-minute"]]])

(def graphs-api
  nil)

(defn view [graphs]
  (html
    [:html
      [:head
        [:title "Pulse"]
        [:link {:rel "stylesheet" :media "screen" :type "text/css" :href "/stylesheets/pulse.css"}]
        [:script {:type "text/javascript" :src "javascripts/jquery-1.6.2.js"}]
        [:script {:type "text/javascript" :src "javascripts/jquery.sparkline.js"}]
        [:script {:type "text/javascript"} (str "var pulseApiUrl=\"" (conf/api-url) "\"")]
        [:script {:type "text/javascript" :src (conf/scales-url)}]
        [:script {:type "text/javascript" :src "javascripts/pulse.js"}]]
      [:body
        [:id#content
          [:table
            (for [row graphs]
              [:tr
                (for [[label key] row]
                  [:td
                    [:span {:id (str key "-sparkline")}] [:br]
                    (str label ": ") [:span {:id (str key "-scalar")}]])])]]]]))

(defn view-handler [graphs]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (view graphs)})

(defonce stats-buffs-a
  (atom {}))

(defn stats-handler []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string @stats-buffs-a)})

(defn not-found-handler []
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "Not Found"})

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
  (log :fn "init-buffer" :at "start")
  (let [rd (redis/init {:url (conf/redis-url)})]
    (redis/subscribe rd ["stats.merged"] (fn [_ stat-json]
      (let [[stat-name stat-val] (json/parse-string stat-json)
            stat-depth (if (coll? stat-val) 1 60)]
        (swap! stats-buffs-a util/update stat-name #(buff-append % stat-val stat-depth)))))))

(defn core-app [{:keys [uri] :as req}]
  (cond
    (= uri "/stats")
      (stats-handler)
    (= uri "/")
      (view-handler graphs-index)
    (= uri "/routing")
      (view-handler graphs-routing)
    (= uri "/runtime")
      (view-handler graphs-runtime)
    (= uri "/packaging")
      (view-handler graphs-packaging)
    (= uri "/api")
      (view-handler graphs-api)
    :else
      (not-found-handler)))

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
    (wrap-file "public")
    (wrap-file-info)
    (wrap-only #(wrap-basic-auth % api-auth?) #(= "/stats" (:uri %)))
    (wrap-only wrap-cros-headers #(= "/stats" (:uri %)))
    (wrap-only wrap-openid-proxy #(not= "/stats" (:uri %)))
    (wrap-session {:store (cookie-store {:key (conf/session-secret)})})
    (wrap-params)
    (wrap-force-https)
    (wrap-logging)
    (wrap-stacktrace)))

(defn -main []
  (log :fn "main" :at "start")
  (util/spawn init-buffer)
  (run-jetty (app) {:port (conf/port) :join false})
  (log :fn "main" :at "finish"))
