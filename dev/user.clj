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

(defn start [] (system/start!))
(defn stop [] (system/stop!))
(defn restart [] (stop) (start))

(defn reset
  "Stop, reload changed source namespaces, then start again."
  []
  (stop)
  (tn/refresh :after 'user/start))
