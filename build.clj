(ns build
  "Build a runnable uberjar for SaltRim.

   Usage:
     clojure -T:build uber            ; -> target/saltrim-<version>.jar
     clojure -T:build version         ; print the canonical version string
     clojure -T:build uber :version '\"1.2.3\"'   ; manual override (rare)
   Version has a single source of truth: `base-version` (major.minor, below) plus
   the git commit count as the patch. The release workflow reads it via
   `clojure -T:build version` (see .github/workflows/release.yml). Run the jar:
     java -jar target/saltrim-<version>.jar"
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def lib 'uno.michelada/saltrim)
(def main 'uno.michelada.saltrim.web)

;; Single source of truth for the release line: major.minor live HERE and only
;; here. The patch number is the git commit count — monotonic, never reset when
;; the minor/major bumps — so the full version is e.g. "0.4.57". Cutting a new
;; line is a one-string edit.
(def base-version "0.4")

(defn- compute-version []
  (format "%s.%s" base-version (b/git-count-revs nil)))

(defn version
  "Print the canonical version (base-version + commit count). The release
   workflow names the tag/jar from this, so build.clj stays the only source."
  [_]
  (println (compute-version)))

(defn- ctx [opts]
  (let [v (or (:version opts) (compute-version))]
    (assoc opts
           :version   v
           :basis     (b/create-basis {:project "deps.edn"})
           :class-dir "target/classes"
           :uber-file (format "target/saltrim-%s.jar" v))))

(defn clean [_] (b/delete {:path "target"}))

(defn cljs
  "Compile ClojureScript -> resources/public/app.js, :advanced (smallest bundle).
   Only app.cljs + the shared .cljc reach the browser; the .clj backend never
   resolves into the cljs graph. Dev rebuilds the :simple bundle instead — see
   dev/cljs_build.clj."
  [_]
  (println "Compiling ClojureScript (:advanced) -> resources/public/app.js")
  ((requiring-resolve 'cljs.build.api/build)
   "src"
   {:output-to     "resources/public/app.js"
    :output-dir    "target/cljs"
    :main          'uno.michelada.saltrim.app
    :optimizations :advanced
    :pretty-print  false}))

(defn uber
  "Compile /app.js, AOT-compile the gen-class main ns, package a standalone jar."
  [opts]
  (let [{:keys [basis class-dir uber-file version]} (ctx opts)]
    (clean nil)
    (cljs nil)
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    ;; Stamp the resolved version into a packaged resource so the running app can
    ;; show it (see uno.michelada.saltrim.version). Only the jar carries this —
    ;; run from source it's absent and the app reports "dev".
    (spit (io/file class-dir "saltrim-version.txt") version)
    (b/compile-clj {:basis basis :class-dir class-dir :ns-compile [main]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main})
    (println "Built" uber-file "(version" version ")")
    uber-file))
