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

(defn uber
  "AOT-compile the gen-class main ns and package a standalone uberjar."
  [opts]
  (let [{:keys [basis class-dir uber-file version]} (ctx opts)]
    (clean nil)
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :class-dir class-dir :ns-compile [main]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main})
    (println "Built" uber-file "(version" version ")")
    uber-file))
