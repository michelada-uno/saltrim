(ns uno.michelada.clorax.util
  "Small shared helpers with no project dependencies."
  (:require [clojure.string :as str]))

(defn env
  "Value of environment variable `k`, or nil when unset/blank."
  [k]
  (let [v (System/getenv k)]
    (when-not (str/blank? v) v)))
