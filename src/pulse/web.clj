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
    ["nginx 500 dom/min"   "nginx-500-domains-per-minute"]
    ["nginx 502 dom/min"   "nginx-502-domains-per-minute"]
    ["nginx 503 dom/min"   "nginx-503-domains-per-minute"]
    ["nginx 504 dom/min"   "nginx-504-domains-per-minute"]
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
    ["hermes h10 app/min"  "hermes-h10-apps-per-minute"]
    ["hermes h11 app/min"  "hermes-h11-apps-per-minute"]
    ["hermes h12 app/min"  "hermes-h12-apps-per-minute"]
    ["hermes h13 app/min"  "hermes-h13-apps-per-minute"]
    ["hermes h14 app/min"  "hermes-h14-apps-per-minute"]
    ["hermes h99 app/min"  "hermes-h99-apps-per-minute"]]
   [["psmgr ps up total"   "psmgr-ps-up-total-last"]
    ["psmgr ps up web"     "psmgr-ps-up-web-last"]
    ["psngr ps up worker"  "psmgr-ps-up-worker-last"]
    ["psmgr ps up other"   "psmgr-ps-up-other-last"]
    ["psmgr ps starting"   "psmgr-ps-starting-last"]
    ["psmgr ps crashed"    "psmgr-ps-crashed-last"]
    ["psmgr ps created"    "psmgr-ps-created-last"]]
   [["railgun ps total"    "railgun-ps-running-total-last"]
    ["railgun ps web"      "railgun-ps-running-web-last"]
    ["railgun ps worker"   "railgun-ps-running-worker-last"]
    ["railgun ps clock"    "railgun-ps-running-clock-last"]
    ["railgun save time"   "railgun-save-time-mean"]
    ["railgun launch time" "railgun-launch-time-mean"]
    ["psmgr ps lost"       "psmgr-ps-lost-last"]]
   [["psmgr run req/min"   "psmgr-run-requests-per-minute"]
    ["railgun run/min"     "railgun-runs-per-minute"]
    ["psmgr kill req/min"  "psmgr-kill-requests-per-minute"]
    ["railgun kill/min"    "railgun-kills-per-minute"]
    ["psmgr idle/min"      "psmgr-idles-per-minute"]
    ["psmgr unidle/min"    "psmgr-unidles-per-minute"]
    ["psmgr conv/sec"      "psmgr-converges-per-second"]]
   [["amqp pub/sec"        "amqp-publishes-per-second"]
    ["amqp rec/sec"        "amqp-receives-per-second"]
    ["amqp tout/min"       "amqp-timeouts-per-minute"]
    ["slugc compile/min"   "slugc-compiles-per-minute"]
    ["slugc fail/min"      "slugc-failures-per-minute"]
    ["slugc err/min"       "slugc-errors-per-minute"]
    ["api release/min"     "api-releases-per-minute"]]
   [["railgun excp/min"    "railgun-unhandled-exceptions-per-minute"]
    ["psmgr excp/min"      "psmgr-unhandled-exceptions-per-minute"]
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
    ["nginx err/min"       "nginx-errors-per-minute"]]
   [["nginx req dom/min"   "nginx-requests-domains-per-minute"]
    ["nginx 500 dom/min"   "nginx-500-domains-per-minute"]
    ["nginx 502 dom/min"   "nginx-502-domains-per-minute"]
    ["nginx 503 dom/min"   "nginx-503-domains-per-minute"]
    ["nginx 504 dom/min"   "nginx-504-domains-per-minute"]
    ["nginx err ins/min"   "nginx-errors-instances-per-minute"]]
   [["varnish req/sec"     "varnish-requests-per-second"]
    ["varnish 500/min"     "varnish-500-per-minute"]
    ["varnish 502/min"     "varnish-502-per-minute"]
    ["varnish 503/min"     "varnish-503-per-minute"]
    ["varnish 504/min"     "varnish-504-per-minute"]
    ["varnish purge/min"   "varnish-purges-per-minute"]
    ["rdv join/min"        "rendezvous-joins-per-minute"]]
   [["hermes req/sec"      "hermes-requests-per-second"]
    ["hermes h10/min"      "hermes-h10-per-minute"]
    ["hermes h11/min"      "hermes-h11-per-minute"]
    ["hermes h12/min"      "hermes-h12-per-minute"]
    ["hermes h13/min"      "hermes-h13-per-minute"]
    ["hermes h14/min"      "hermes-h14-per-minute"]
    ["hermes h99/min"      "hermes-h99-per-minute"]]
   [["hermes req app/min"  "hermes-requests-apps-per-minute"]
    ["hermes h10 app/min"  "hermes-h10-apps-per-minute"]
    ["hermes h11 app/min"  "hermes-h11-apps-per-minute"]
    ["hermes h12 app/min"  "hermes-h12-apps-per-minute"]
    ["hermes h13 app/min"  "hermes-h13-apps-per-minute"]
    ["hermes h14 app/min"  "hermes-h14-apps-per-minute"]
    ["hermes h99 app/min"  "hermes-h99-apps-per-minute"]]
   [["hermes ls upd/min"   "hermes-lockstep-updates-per-minute"]
    ["hermes ls conn/min"  "hermes-lockstep-connections-per-minute"]
    ["hermes ls dconn/min" "hermes-lockstep-disconnections-per-minute"]
    ["hermes ls ltcy"      "hermes-lockstep-mean-latency"]
    ["hermes ls max ltcy"  "hermes-lockstep-max-latency"]
    ["hermes ls still"     "hermes-lockstep-mean-stillness"]
    ["hermes ls max still" "hermes-lockstep-max-stillness"]]
   [["hermes elv rt/min"   "hermes-elevated-route-lookups-per-minute"]
    ["hermes slow rt/min"  "hermes-slow-route-lookups-per-minute"]
    ["hermes cat rt/min"   "hermes-catastrophic-route-lookups-per-minute"]
    ["hermes slow rd/min"  "hermes-slow-redis-lookups-per-minute"]
    ["hermes cat rd/min"   "hermes-catastrophic-redis-lookups-per-minute"]
    ["hermes processes"    "hermes-processes-last"]
    ["hermes ports"        "hermes-ports-last"]]])

(def graphs-railgun
  [[["railgun running"     "railgun-running-count"]
    ["railgun denied"      "railgun-denied-count"]
    ["railgun packed"      "railgun-packed-count"]
    ["railgun loaded"      "railgun-loaded-count"]
    ["railgun critical"    "railgun-critical-count"]
    ["railgun accepting"   "railgun-accepting-count"]
    ["railgun load avg"    "railgun-load-avg-15m-mean"]]
   [["railgun ps total"    "railgun-ps-running-total-last"]
    ["railgun ps web"      "railgun-ps-running-web-last"]
    ["railgun ps worker"   "railgun-ps-running-worker-last"]
    ["railgun ps clock"    "railgun-ps-running-clock-last"]
    ["railgun ps console"  "railgun-ps-running-console-last"]
    ["railgun ps rake"     "railgun-ps-running-rake-last"]
    ["railgun ps other"    "railgun-ps-running-other-last"]]
   [["railgun run/min"     "railgun-runs-per-minute"]
    ["railgun ret/min"     "railgun-returns-per-minute"]
    ["railgun kill/min"    "railgun-kills-per-minute"]
    ["railgun sub/min"     "railgun-subscribes-per-minute"]
    ["railgun unsub/min"   "railgun-unsubscribes-per-minute"]
    ["railgun batch/min"   "railgun-status-batches-per-minute"]
    ["railgun gc/min"      "railgun-gcs-per-minute"]]
   [["railgun kill time"   "railgun-kill-time-mean"]
    ["railgun save time"   "railgun-save-time-mean"]
    ["railgun unpack time" "railgun-unpack-time-mean"]
    ["railgun setup time"  "railgun-setup-time-mean"]
    ["railgun launch time" "railgun-launch-time-mean"]
    ["railgun batch time"  "railgun-status-batch-time-mean"]
    ["railgun gc time"     "railgun-gc-time-mean"]]
   [["railgun s3 req/min"  "railgun-s3-requests-per-minute"]
    ["railgun s3 err/min"  "railgun-s3-errors-per-minute"]
    ["railgun s3 time"     "railgun-s3-time-mean"]
    ["railgun s3c req/min" "railgun-s3-canary-requests-per-minute"]
    ["railgun s3c err/min" "railgun-s3-canary-errors-per-minute"]
    ["railgun s3c time"    "railgun-s3-canary-time-mean"]]
   [["railgun r10/min"     "railgun-r10-per-minute"]
    ["railgun r11/min"     "railgun-r11-per-minute"]
    ["railgun r12/min"     "railgun-r12-per-minute"]
    ["railgun r14/min"     "railgun-r14-per-minute"]
    ["railgun r15/min"     "railgun-r15-per-minute"]]
   [["railgun r10 app/min" "railgun-r10-apps-per-minute"]
    ["railgun r11 app/min" "railgun-r11-apps-per-minute"]
    ["railgun r12 app/min" "railgun-r12-apps-per-minute"]
    ["railgun r14 app/min" "railgun-r14-apps-per-minute"]
    ["railgun r15 app/min" "railgun-r15-apps-per-minute"]]
   [["railgun init/min"    "railgun-inits-per-minute"]
    ["railgun trap/min"    "railgun-traps-per-minute"]
    ["railgun exit/min"    "railgun-exits-per-minute"]
    ["railgun excp/min"    "railgun-unhandled-exceptions-per-minute"]
    ["railgun ping/min"    "railgun-pings-per-minute"]
    ["railgun beat/min"    "railgun-heartbeats-per-minute"]
    ["railgun evt/sec"     "railgun-events-per-second"]]])

(def graphs-psmgr
  [])

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
  [[["jobs/min"            "api-worker-jobs-per-minute"]
    ["jobs delay"          "api-worker-jobs-delay"]
    ["jobs time"           "api-worker-jobs-time"]
    ["jobs excp/min"       "api-worker-unhandled-exceptions-per-minute"]]
   [["req/sec"             "api-requests-per-second"]
    ["req user err/min"    "api-request-user-errors-per-minute"]
    ["req excp/min"        "api-request-unhandled-exceptions-per-minute"]
    ["req time"            "api-request-time"]]
   [["dev actions/min"     "api-developer-actions-per-minute"]
    ["creates/min"         "api-creates-per-minute"]
    ["releases/min"        "api-releases-per-minute"]
    ["deploys/min"         "api-deploys-per-minute"]
    ["runs/min"            "api-runs-per-minute"]
    ["restarts/min"        "api-restarts-per-minute"]]
   [["scales/min"          "api-scales-per-minute"]
    ["config changes/min"  "api-config-changes-per-minute"]
    ["logs/min"            "api-logs-per-minute"]
    ["config list/min"     "api-configs-per-minute"]
    ["codex prov/min"      "api-codex-provisions-per-minute"]
    ["codex prov time"     "api-codex-provision-time"]]
   [["s3 copy/min"         "api-s3-copies-per-minute"]
    ["s3 copy errors/min"  "api-s3-copy-unhandled-exceptions-per-minute"]
    ["s3 copy duration"    "api-s3-copy-time"]]])

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
          [:id#index
            [:a {:href "/"}          "home"]      " | "
            [:a {:href "/railgun"}   "railgun"]   " | "
            [:a {:href "/psmgr"}     "psmgr"]     " | "
            [:a {:href "/routing"}   "routing"]   " | "
            [:a {:href "/packaging"} "packaging"] " | "
            [:a {:href "/api"}       "api"]]
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
    (= uri "/railgun")
      (view-handler graphs-railgun)
    (= uri "/psmgr")
      (view-handler graphs-psmgr)
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
