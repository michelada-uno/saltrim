(ns uno.michelada.saltrim.version
  "Runtime access to the app version. `clojure -T:build uber` writes the canonical
   version string — build.clj's single source of truth (`base-version` + the git
   commit count) — into the packaged resource `saltrim-version.txt`. Run from a
   built jar it reports e.g. \"0.4.58\"; run from source (no uber build, so no
   resource) it reports \"dev\"."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resolved
  (delay (if-let [r (io/resource "saltrim-version.txt")]
           (str/trim (slurp r))
           "dev")))

(defn current
  "The app version string (\"0.4.58\" from a jar, \"dev\" from source)."
  []
  @resolved)
