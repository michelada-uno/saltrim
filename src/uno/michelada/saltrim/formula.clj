(ns uno.michelada.saltrim.formula
  "Formula = restricted Clojure expression. Cell refs via reader tags:
     #cell  A1       -> current value of A1
     #cells A1:A3    -> vector of current values [A1 A2 A3]      (column)
     #cells A1:C1    -> [A1 B1 C1]                               (row)
     #cells A1:B2    -> [A1 B1 A2 B2]  ROW-MAJOR rectangle       (block)
   Any inclusive rectangle works (for map/reduce); ranges expand at read time.

   Uniform model: every cell is a Spin, so refs use `await` (handles Spins).
   Two constraints shape compilation:
   1. `await` inside a runtime (fn ...) is NOT CPS-transformed -> ranges expand
      STATICALLY at read time into literal refs.
   2. awaiting the SAME spin twice in a body glitches on recompute (Spindel) ->
      each distinct cell is awaited ONCE, bound in a `let`, then referenced.

   Reader emits neutral ref-markers `(::ref \"A1\")`; `lift` turns the whole
   form into `(let [c1 (await (lookup \"A1\")) ...] body)`.

   Pipeline:
     parse    : string -> {:form <marker-form> :deps #{addr}}
     validate : whitelist user symbols (sandbox; EDN reader blocks #=)
     compile  : marker-form -> Spin (validate user form, lift, eval spin)."
  (:refer-clojure :exclude [compile await])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
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
  "Formula string (without leading =) -> {:form :deps}."
  [s]
  (let [form (edn/read-string {:readers readers} s)]
    {:form form :deps (deps form)}))

;; --- validate (whitelist sandbox) --------------------------------------

(def allowed-ops
  '#{+ - * / await track deref vector
     uno.michelada.saltrim.runtime/lookup uno.michelada.saltrim.runtime/lookup-val
     min max abs mod quot rem inc dec
     = not= < > <= >= and or not if when
     map reduce count})

(defn validate!
  "Whitelist every symbol in the USER form. Markers are keyword-headed lists,
   so their addresses never reach this check."
  [form]
  (walk/postwalk
   (fn [x]
     (when (and (symbol? x) (not (contains? allowed-ops x)))
       (throw (ex-info "disallowed symbol in formula" {:symbol x})))
     x)
   form)
  form)

;; --- lift (dedupe awaits) ----------------------------------------------

(defn- lift
  "Replace each distinct (::ref addr) with a let-bound local awaited once."
  [form]
  (let [addrs (vec (deps form))]
    (if (empty? addrs)
      form
      (let [sym  (into {} (map (fn [a] [a (gensym "c_")]) addrs))
            body (walk/postwalk (fn [x] (if (ref? x) (sym (second x)) x)) form)
            bnds (vec (mapcat (fn [a] [(sym a)
                                       (list 'await (list 'uno.michelada.saltrim.runtime/lookup a))])
                              addrs))]
        (list 'let bnds body)))))

;; --- compile ------------------------------------------------------------

(defn compile
  "Marker form -> Spin. Validates user symbols, lifts refs, evals `(spin ...)`
   in this namespace so spin/track/await resolve and CPS sees the effects."
  [form]
  (validate! form)
  (binding [*ns* (find-ns 'uno.michelada.saltrim.formula)]
    (eval (list 'spin (lift form)))))

(defn compile-literal-wrapper
  "Spin exposing a literal cell's editable signal as a public awaitable node:
   (spin (deref (track (lookup-val addr))))."
  [addr]
  (compile (list 'deref (list 'track (list 'uno.michelada.saltrim.runtime/lookup-val addr)))))
