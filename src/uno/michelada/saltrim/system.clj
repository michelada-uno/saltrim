(ns uno.michelada.saltrim.system
  "System lifecycle. The mount states live next to the resources they manage —
   `db/conn` (the connection), `web/sweeper` (the scheduled pool), `web/server`
   (http-kit's stop-fn) — each holding its live value directly. This namespace
   just requires those (so mount sees the states), wraps `mount/start`/`stop`
   with a boot-time summary, and is the entry point `web/-main` delegates to.

   Start order = state load order: conn → sweeper → server (web requires db, so
   db loads first); stop is the reverse, so the server drains before the db
   closes. Each state logs its own timing (uno…/util `timed`)."
  (:require [mount.core :as mount]
            [uno.michelada.saltrim.util :as util]
            [uno.michelada.saltrim.db]     ; load db/conn state
            [uno.michelada.saltrim.web]))  ; load web/sweeper + web/server states

(defn start!
  "Bring the system up, logging total boot time."
  []
  (util/log "starting…")
  (let [t0 (System/currentTimeMillis)
        r  (mount/start)]
    (util/log "ready —" (str (- (System/currentTimeMillis) t0) " ms total ·")
              (pr-str (:started r)))
    r))

(defn stop!
  "Bring the system down."
  []
  (let [r (mount/stop)]
    (util/log "stopped")
    r))
