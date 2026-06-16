(ns uno.michelada.saltrim.formula
  "Formula = a Clojure expression, evaluated by SCI in a sandbox. Cell refs via
   reader tags:
     #cell  A1       -> current value of A1
     #cells A1:A3    -> vector of current values [A1 A2 A3]      (column)
     #cells A1:C1    -> [A1 B1 C1]                               (row)
     #cells A1:B2    -> [A1 B1 A2 B2]  ROW-MAJOR rectangle       (block)
   Any inclusive rectangle works (for map/reduce); ranges expand at read time.

   Why SCI: the old path host-`eval`d the body under a symbol whitelist, which
   could not allow `let`/`fn` (the user's own binder names aren't in the list).
   SCI gives real lexical scope, fn literals, destructuring, etc., in a sandbox
   (no host interop) — see spikes/03-sci-formula-eval.clj.

   How it composes with the reactive engine: every cell is a Spin, refs use
   `await`. After the await-lift the user body is PURE — every `await` sits in
   the `let` bindings (our infra); the body just computes over already-resolved
   values. So SCI never sees `spin`/`await`/`track`; it compiles only the user
   expression to a host-callable fn of the cell values, and the spin wrapper
   stays host-compiled and closes over it:

     (fn [uf] (spin (let [c1 (await (lookup A1)) ...] (uf c1 ...))))   ; host
     uf = (sci/eval '(fn [c1 ...] <user-body>))                        ; sandbox

   Each DISTINCT referenced cell is awaited once (de-dup; awaiting the same spin
   twice in a body glitches on recompute). `await`/`lookup` appear literally in
   the spin body (CPS breakpoints), never inside a nested fn.

   Pipeline:
     parse    : string -> {:form <marker-form> :deps #{addr}}
     compile  : marker-form -> Spin (SCI-compile body, lift refs, eval spin)."
  (:refer-clojure :exclude [compile await])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [sci.core :as sci]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.runtime :as rt]))

;; --- parse --------------------------------------------------------------

(defn- ref-marker [addr] (list ::ref addr))
(defn- ref? [x] (and (seq? x) (= ::ref (first x))))

(def ^:private readers
  {;; #cell A1 -> (::ref "A1")
   'cell  (fn [sym] (ref-marker (str sym)))
   ;; #cells A1:A3 -> (vector (::ref "A1") (::ref "A2") (::ref "A3"))
   ;; #cells A1:C1 -> (vector (::ref "A1") (::ref "B1") (::ref "C1"))  (rectangle)
   'cells (fn [sym]
            (let [[a b] (str/split (str sym) #":")]
              (cons 'vector (map ref-marker (addr/range-cells a b)))))})

(defn deps
  "Cell addresses referenced by a marker form."
  [form]
  (let [acc (volatile! #{})]
    (walk/postwalk (fn [x] (when (ref? x) (vswap! acc conj (second x))) x) form)
    @acc))

(defn parse
  "Formula string (without leading =) -> {:form :deps}.

   With `self` (the owner address, e.g. for a STYLE/FORMAT property), the bare
   symbol `$val` rewrites to a ref on the owner's own value — sugar for
   `#cell <self>`. So a style reads the cell's current value reactively (an
   `await` edge) without retyping the address. `$val` is only meaningful where
   an owner exists; in a plain value formula it stays an unknown symbol and SCI
   rejects it at compile."
  ([s] (parse s nil))
  ([s self]
   (let [form0 (edn/read-string {:readers readers} s)
         form  (if self
                 (walk/postwalk #(if (= '$val %) (ref-marker self) %) form0)
                 form0)]
     {:form form :deps (deps form)})))

;; --- SCI sandbox --------------------------------------------------------
;; Default SCI core (a curated, side-effect-free clojure.core subset with real
;; lexical scope and NO host interop) plus `abs`. A per-sheet namespace and a
;; richer predefined stdlib are the next step (ROADMAP item 2).

(def ^:private sci-ctx (sci/init {:namespaces {'clojure.core {'abs abs}}}))

(defn- sci-fn
  "Compile the pure user `body` (markers already replaced by the value `syms`)
   to a host-callable fn of those values, sandboxed by SCI."
  [syms body]
  (sci/eval-form (sci/fork sci-ctx) (list 'fn (vec syms) body)))

;; --- compile ------------------------------------------------------------

(defn compile
  "Marker form -> Spin. SCI-compiles the user body over resolved cell values;
   the spin awaits each distinct referenced cell once (de-dup) and calls the
   SCI fn. SCI never sees spin/await/track."
  [form]
  (let [addrs (vec (deps form))
        syms  (mapv (fn [_] (gensym "c_")) addrs)
        a->s  (zipmap addrs syms)
        body  (walk/postwalk (fn [x] (if (ref? x) (a->s (second x)) x)) form)
        user-fn (sci-fn syms body)
        bnds (vec (mapcat (fn [a s]
                            [s (list 'await (list 'uno.michelada.saltrim.runtime/lookup a))])
                          addrs syms))
        ;; eval a factory (fn [uf] (spin (let [<awaits>] (uf <syms>)))) in this
        ;; ns so spin/await resolve and CPS sees the effects; then close over uf.
        factory (binding [*ns* (find-ns 'uno.michelada.saltrim.formula)]
                  (eval (list 'fn ['uf] (list 'spin (list 'let bnds (list* 'uf syms))))))]
    (factory user-fn)))

(defn compile-literal-wrapper
  "Spin exposing a literal cell's editable signal as a public awaitable node:
   (spin (deref (track (lookup-val addr)))). Pure infra — host-compiled, no SCI."
  [addr]
  (binding [*ns* (find-ns 'uno.michelada.saltrim.formula)]
    (eval (list 'spin (list 'deref (list 'track
                                         (list 'uno.michelada.saltrim.runtime/lookup-val addr)))))))
