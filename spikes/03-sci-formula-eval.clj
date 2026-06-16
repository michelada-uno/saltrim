(ns spikes.sci-formula-eval
  "Replace the host-eval + symbol-whitelist formula path with SCI, WITHOUT
   disturbing the reactive await machinery — the basis of the formula-engine →
   SCI migration (ROADMAP item 1).

   Insight: after the await-lift the user body is PURE — every `await` lives in
   the `let` bindings (our infra), the body just computes over already-resolved
   values. So SCI never sees `spin`/`await`/`track`; it compiles only the user
   expression to a host-callable fn of the cell values, and the spin wrapper
   stays host-compiled and closes over it:

     (fn [uf] (spin (let [c1 (await (lookup A1)) …] (uf c1 …))))   ; host
     uf = (sci/eval '(fn [c1 …] <user-body>))                      ; sandbox

   Proves: let/locals (the thing the whitelist couldn't allow), higher-order
   fns, reactive recompute on a dep change, and that the sandbox blocks host fns.

   REPL walkthrough: eval the forms in the (comment …) block one at a time.")

(comment
  (require '[sci.core :as sci]
           '[clojure.walk :as walk]
           '[clojure.edn :as edn]
           '[org.replikativ.spindel.spin.cps :refer [spin]]
           '[org.replikativ.spindel.effects.await :refer [await]]
           '[org.replikativ.spindel.engine.core :as ec]
           '[uno.michelada.saltrim.runtime :as rt]
           '[uno.michelada.saltrim.sheet :as sheet])

  ;; markers: #cell A1 → (::ref "A1"); bare $val → (::ref self)
  (defn ref? [x] (and (seq? x) (= ::ref (first x))))
  (defn parse [s self]
    (let [form (edn/read-string {:readers {'cell (fn [sym] (list ::ref (str sym)))}} s)]
      (walk/postwalk #(if (= '$val %) (list ::ref self) %) form)))
  (defn ref-addrs [form]
    (let [acc (volatile! [])]
      (walk/postwalk (fn [x] (when (ref? x) (vswap! acc conj (second x))) x) form)
      (vec (distinct @acc))))

  ;; default SCI core: curated, side-effect-free, NO host interop, real scope.
  (def sci-ctx (sci/init {}))

  ;; user body → host-callable fn of cell values; spin/await wrapper host-compiled.
  (defn compile-sci [marker-form]
    (let [addrs (ref-addrs marker-form)
          syms  (mapv (fn [a] (gensym (str "c_" a "_"))) addrs)
          a->s  (zipmap addrs syms)
          body  (walk/postwalk #(if (ref? %) (a->s (second %)) %) marker-form)
          user-fn (sci/eval-form (sci/fork sci-ctx) (list 'fn syms body))   ; sandboxed
          bnds   (vec (mapcat (fn [a s] [s (list 'await (list `rt/lookup a))]) addrs syms))
          factory (binding [*ns* (find-ns 'spikes.sci-formula-eval)]        ; refers spin/await
                    (eval (list 'fn ['uf] (list 'spin (list 'let bnds (list* 'uf syms))))))]
      (factory user-fn)))

  (defn install! [s addr formula self]
    (binding [ec/*execution-context* (:rt s)]                              ; spin needs the ctx
      (swap! (:registry s) assoc addr (compile-sci (parse formula self)))))

  ;; ── exercise on the real sheet engine ──
  (def s (sheet/create-sheet))
  (sheet/set-cell! s "A1" "10")
  (sheet/set-cell! s "B1" "20")

  ;; local bindings — rejected by the old whitelist, fine in SCI:
  (install! s "C1" "(let [x #cell A1 y #cell B1] (+ x y (* x 2)))" "C1")
  ;; higher-order + lambda — also impossible before:
  (install! s "C2" "(reduce + (map (fn [n] (* n n)) [#cell A1 #cell B1]))" "C2")
  (sheet/settle! s)
  (sheet/value s "C1")                  ;; => 50    (10+20+10*2)
  (sheet/value s "C2")                  ;; => 500   (100+400)

  ;; reactive recompute still fires on a dep change:
  (sheet/set-cell! s "A1" "100")
  (sheet/settle! s)
  (sheet/value s "C1")                  ;; => 320   (100+20+100*2)
  (sheet/value s "C2")                  ;; => 10400 (10000+400)

  ;; sandbox holds: host fns are unresolvable…
  (try (sci/eval-form (sci/fork sci-ctx) '(slurp "/etc/passwd"))
       :LEAK (catch Exception e (.getMessage e)))
  ;; => "Could not resolve symbol: slurp"

  ;; …and SCI's own `eval` is in-sandbox (a nested slurp is still blocked):
  (try (sci/eval-form (sci/fork sci-ctx) '(eval '(slurp "/etc/passwd")))
       :LEAK (catch Exception e (str "safe: " (.getMessage e)))))
  ;; => "safe: Could not resolve symbol: slurp"
