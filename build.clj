(ns build
  "Build a runnable uberjar for SaltRim.

   Usage:
     clojure -T:build uber            ; -> target/saltrim-<version>.jar
     clojure -T:build uber :version '\"1.2.3\"'
   The CI release workflow passes the git tag as VERSION (see
   .github/workflows/release.yml). Run it with:
     java -jar target/saltrim-<version>.jar"
  (:require [clojure.tools.build.api :as b]))

(def lib 'uno.michelada/saltrim)
(def main 'uno.michelada.saltrim.web)

(defn- version []
  ;; CI sets VERSION from the pushed tag (vX.Y.Z -> X.Y.Z); locally we derive a
  ;; dev version from the commit count so jar names are still unique.
  (or (System/getenv "VERSION")
      (format "0.1.%s-dev" (b/git-count-revs nil))))

(defn- ctx [opts]
  (let [v (or (:version opts) (version))]
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
    (b/compile-clj {:basis basis :class-dir class-dir :ns-compile [main]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main})
    (println "Built" uber-file "(version" version ")")
    uber-file))
