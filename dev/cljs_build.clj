(ns cljs-build
  "Dev-time ClojureScript build for /app.js — plain CLJS compiler, no node/npm.

   `once`  : a single :simple compile.
   `watch` : recompiles on every save (blocks; dev/user.clj runs it on a daemon
             thread from (start), so the running app always serves a fresh
             bundle and an edit-then-reload picks it up).

   Production builds the smaller :advanced bundle from build.clj instead. Both
   read the same src tree; only app.cljs + the shared .cljc reach the browser
   (the .clj backend never resolves into the cljs graph)."
  (:require [cljs.build.api :as api]))

(def ^:private src "src")

(def ^:private opts
  {:output-to     "resources/public/app.js"
   :output-dir    "target/cljs"          ; intermediates under (gitignored) target/
   :main          'uno.michelada.saltrim.app
   :optimizations :simple                ; single self-contained file, fast enough
   :pretty-print  false})

(defn once  [] (api/build src opts))
(defn watch [] (api/watch src opts))
