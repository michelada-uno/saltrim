(ns uno.michelada.saltrim.util
  "Small shared helpers with no project dependencies."
  (:require [clojure.string :as str]))

(defn env
  "Value of environment variable `k`, or nil when unset/blank."
  [k]
  (let [v (System/getenv k)]
    (when-not (str/blank? v) v)))

(defn log [& msg]
  (println (str \[ (java.time.LocalTime/now) \]) "saltrim ·" (apply str (interpose \space msg))))

(defmacro timed
  "Evaluate body, logging the step + elapsed ms under `label` (the slow steps in
   a lifecycle become visible). Direction is conveyed by the label itself
   (e.g. \"db connection\" vs \"db release\"). Returns the body's value."
  [label & body]
  `(let [lbl# ~label, t0# (System/currentTimeMillis)]
     (log "→" lbl# "…")
     (let [r# (do ~@body)]
       (log "  ✓" lbl# (str "(" (- (System/currentTimeMillis) t0#) " ms)"))
       r#)))
