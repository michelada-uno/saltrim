(ns uno.michelada.saltrim.runtime
  "Runtime support referenced by compiled cell bodies, resolving against the
   CURRENT execution context's metadata (works on executor threads).

   Uniform cell model:
   - every cell has a PUBLIC Spin in :registry -> `lookup`      (used by `await`)
   - literal cells also have an editable SignalRef in :vals -> `lookup-val`
     (used by the literal wrapper spin's `track`)."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.cps :refer [spin]]))

(defn- meta-get [k]
  (-> (ec/current-execution-context) ctx/get-metadata (get k)))

(defn lookup
  "Address -> public Spin for the current sheet. A referenced EMPTY cell (no
   registry entry) resolves to a fresh constant nil-Spin, so a ref to a blank cell
   yields nil (the stdlib aggregates filter nils) rather than erroring. Fresh per
   call so two blank refs in one formula await DISTINCT nodes — a shared node would
   trip Spindel's await-same-node-twice glitch. Filling the blank later is a
   structural change, so `sheet/set-cell!` rebuilds dependents to capture the real
   node (reactivity preserved)."
  [addr]
  (or (get @(meta-get :registry) addr)
      (spin nil)))

(defn lookup-val
  "Address -> editable SignalRef (literal cells only)."
  [addr]
  (or (get @(meta-get :vals) addr)
      (throw (ex-info "no value signal" {:addr addr}))))
