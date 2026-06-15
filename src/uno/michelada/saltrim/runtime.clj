(ns uno.michelada.saltrim.runtime
  "Runtime support referenced by compiled cell bodies, resolving against the
   CURRENT execution context's metadata (works on executor threads).

   Uniform cell model:
   - every cell has a PUBLIC Spin in :registry -> `lookup`      (used by `await`)
   - literal cells also have an editable SignalRef in :vals -> `lookup-val`
     (used by the literal wrapper spin's `track`)."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- meta-get [k]
  (-> (ec/current-execution-context) ctx/get-metadata (get k)))

(defn lookup
  "Address -> public Spin for the current sheet."
  [addr]
  (or (get @(meta-get :registry) addr)
      (throw (ex-info "unknown cell" {:addr addr}))))

(defn lookup-val
  "Address -> editable SignalRef (literal cells only)."
  [addr]
  (or (get @(meta-get :vals) addr)
      (throw (ex-info "no value signal" {:addr addr}))))
