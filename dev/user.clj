(ns user
  "REPL entry point (on the classpath via the :dev / :nrepl extra-path). Drive
   the running system without restarting the JVM:

     (start)    bring the mount system up   (db -> sweeper -> http server)
     (stop)     take it down
     (restart)  stop + start, no code reload
     (reset)    stop, reload changed src namespaces, start again

   `reset` is the interactive-dev workhorse: edit a file, `(reset)`, see it live."
  (:require [mount.core :as mount]
            [clojure.tools.namespace.repl :as tn]
            [uno.michelada.saltrim.system :as system]))

;; Only reload application source — never dev/ (this ns) or test/.
(tn/set-refresh-dirs "src")

;; Compile /app.js from ClojureScript and keep rebuilding it on every save, so a
;; running dev server always serves a fresh bundle (an edit-then-browser-reload
;; picks it up — app.js is slurped per request). Starts once on a daemon thread
;; and survives (reset) (it lives in dev/, outside the refresh dirs). The initial
;; :simple compile runs in the background; it never blocks system start. Prod
;; compiles the :advanced bundle in build.clj. See dev/cljs_build.clj.
(defonce ^:private cljs-watch
  (delay
    (doto (Thread. ^Runnable #((requiring-resolve 'cljs-build/watch)) "saltrim-cljs-watch")
      (.setDaemon true)
      (.start))
    :watching))

(defn start [] @cljs-watch (system/start!))
(defn stop [] (system/stop!))
(defn restart [] (stop) (start))

(defn reset
  "Stop, reload changed source namespaces, then start again."
  []
  (stop)
  (tn/refresh :after 'user/start))
