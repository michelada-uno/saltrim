(ns uno.michelada.saltrim.web
  "Web entry point: the http-kit router (`app`) dispatching to the handler
   namespaces, plus the mount states (`server`, `sweeper`) and `-main`. The
   request/render/collab/state logic lives in `uno.michelada.saltrim.web.*`."
  (:require
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [jsonista.core :as json]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.util :as util :refer [timed]]
            [mount.core :refer [defstate]]
            [uno.michelada.saltrim.web.state :refer [SWEEP-MS sessions* sheets*]]
            [uno.michelada.saltrim.web.render :refer [page]]
            [uno.michelada.saltrim.web.collab :refer [sweep!]]
            [uno.michelada.saltrim.web.handlers :refer [auth-routes handle-branch handle-cell handle-clear handle-copy handle-cut handle-defadd handle-defdel handle-deflock handle-defsave handle-defunlock handle-export handle-graph handle-insert handle-merge handle-paste handle-presence handle-props handle-redo handle-root handle-session-end handle-share handle-size handle-stream handle-style handle-undo handle-view handle-viewat]])
  (:gen-class))

(defn- app [req]
  (or
   (auth-routes req)
   (case [(:request-method req) (:uri req)]
    [:get "/"]            (handle-root req)
    [:get "/datastar.js"] (if-let [r (io/resource "public/datastar.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no datastar"})
    [:get "/app.js"]      (if-let [r (io/resource "public/app.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no app.js"})
    ;; brand wordmark (login page)
    [:get "/SaltRim.png"] (if-let [r (io/resource "SaltRim.png")]
                            {:status 200 :headers {"Content-Type" "image/png"
                                                   "Cache-Control" "max-age=86400"}
                             :body (io/input-stream r)}
                            {:status 404 :body "no logo"})
    ;; both the explicit link (/favicon.png) and the browser's automatic
    ;; /favicon.ico request (covers pages without the <link>, e.g. login)
    ([:get "/favicon.png"] [:get "/favicon.ico"])
    (if-let [r (io/resource "favicon.png")]
      {:status 200 :headers {"Content-Type" "image/png"
                             "Cache-Control" "max-age=86400"}
       :body (io/input-stream r)}                         ; binary — not slurp
      {:status 404 :body "no favicon"})
    [:get "/stream"]         (handle-stream req)
    [:get "/export.xlsx"]    (handle-export req)
    [:post "/session/end"]   (handle-session-end req)
    ;; dev-only diagnostics: exposed only under the name-only dev provider
    [:get "/debug"]       (if-not (auth/dev-auth?)
                            {:status 404 :body "not found"}
                            {:status 200 :headers {"Content-Type" "application/json"}
                             :body (json/write-value-as-string
                                    {:sessions (count @sessions*)
                                     :loaded-sheets (vec (keys @sheets*))
                                     :detail (mapv (fn [[sid s]]
                                                     {:sid (subs sid 0 (min 6 (count sid)))
                                                      :sheet (:sheet s)
                                                      :uid (:uid s)
                                                      :gen? (boolean (:gen s))
                                                      :view (:view s)})
                                                   @sessions*)})})
    [:post "/cell"]       (handle-cell req)
    [:post "/style"]      (handle-style req)
    [:post "/undo"]       (handle-undo req)
    [:post "/redo"]       (handle-redo req)
    [:post "/clear"]      (handle-clear req)
    [:post "/copy"]       (handle-copy req)
    [:post "/cut"]        (handle-cut req)
    [:post "/paste"]      (handle-paste req)
    [:post "/size"]       (handle-size req)
    [:post "/insert"]     (handle-insert req)
    [:post "/props"]      (handle-props req)
    [:post "/deflock"]    (handle-deflock req)
    [:post "/defunlock"]  (handle-defunlock req)
    [:post "/defsave"]    (handle-defsave req)
    [:post "/defadd"]     (handle-defadd req)
    [:post "/defdel"]     (handle-defdel req)
    [:post "/view"]       (handle-view req)
    [:post "/viewat"]     (handle-viewat req)
    [:post "/presence"]   (handle-presence req)
    [:post "/share"]      (handle-share req)
    [:post "/branch"]     (handle-branch req)
    [:post "/merge"]      (handle-merge req)
    [:post "/graph"]      (handle-graph req)
    {:status 404 :body "not found"})))

(defn port
  "HTTP port — SALTRIM_PORT env or 8080."
  []
  (or (some-> (System/getenv "SALTRIM_PORT") parse-long) 8080))

(defn- start-sweeper-pool!
  "A scheduled pool that reaps idle/orphan sessions on an interval."
  []
  (doto (java.util.concurrent.Executors/newScheduledThreadPool 1)
    (.scheduleAtFixedRate ^Runnable (fn [] (try (sweep!) (catch Throwable _)))
                          SWEEP-MS SWEEP-MS java.util.concurrent.TimeUnit/MILLISECONDS)))

;; --- mount states ---------------------------------------------------------
;; Each state's VALUE is the live resource (no side atoms): `sweeper` is the
;; scheduled pool, `server` is http-kit's stop-fn. db's `conn` state starts
;; first (web requires db), so the order is conn → sweeper → server, reversed on
;; stop. (sessions*/sheets* stay atoms — they're in-memory caches, not lifecycle.)

(defstate sweeper
  :start (timed "session sweeper" (start-sweeper-pool!))
  :stop  (timed "session sweeper" (.shutdownNow ^java.util.concurrent.ExecutorService sweeper)))

(defstate server
  :start (timed (str "http server :" (port))
           (let [stop (http/run-server (-> #'app
                                           wrap-params
                                           wrap-keyword-params
                                           wrap-cookies)
                                       {:port (port)})]
                  (util/log "  serving http://localhost:" (port) "·"
                            (if-let [ps (seq (keys (auth/providers)))]
                              (str "auth: " (str/join ", " (map name ps))) "auth: none")
                            (if (auth/dev-auth?) "(+ dev login)" ""))
                  stop))
  ;; http-kit's run-server returns a stop-fn; the live sessions' streams die with
  ;; it, so drop the cache too.
  :stop  (timed "http server" (do (server) (reset! sessions* {}))))

;; Lifecycle is owned by the mount `system` ns; -main delegates there (resolved
;; at runtime to avoid a compile-time cycle, since system requires web).
(defn -main [& _]
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable (requiring-resolve 'uno.michelada.saltrim.system/stop!)))
  ((requiring-resolve 'uno.michelada.saltrim.system/start!))
  @(promise))

