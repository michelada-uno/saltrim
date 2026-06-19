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

;; --- SCI sandbox + stdlib ----------------------------------------------
;; SCI runs the user expression in a curated, side-effect-free subset of
;; clojure.core (real lexical scope, NO host interop the user can reach). On top
;; we merge a spreadsheet stdlib (math / stats / text / date): plain host fns
;; exposed by name, callable bare from any formula. Names are chosen NOT to
;; shadow clojure.core (so map/reduce/str/… keep their meaning). Each sheet gets
;; its OWN context (isolation) built from this base plus the sheet's user `defs`
;; — see `new-ctx` (ROADMAP item 2: per-sheet namespace + functions).

(defn- ld ^java.time.LocalDate [s] (java.time.LocalDate/parse (str s)))

;; SCI's core exposes the print/read family, but its *out*/*in* are unbound, so
;; calling them crashes with an opaque cast (SciUnbound -> Writer). They're also
;; meaningless here: a formula is PURE and recomputes reactively (no console, and
;; it would re-fire on every dependency change). Override them to fail clearly.
(defn- no-io [& _]
  (throw (ex-info "I/O isn't available in formulas — the sandbox is pure (no console)" {})))

(defn- mean* [c] (if (seq c) (/ (double (reduce + 0 c)) (count c)) 0))
(defn- var* [c]
  (let [n (count c)]
    (if (zero? n) 0
        (let [m (mean* c)] (/ (reduce + (map #(let [d (- % m)] (* d d)) c)) n)))))

(def stdlib
  "Predefined functions merged into clojure.core for every formula sandbox.
   Grouped by category; all pure, none shadowing a clojure.core name."
  {;; math
   'abs abs
   'ceil    (fn [x] (long (Math/ceil (double x))))
   'floor   (fn [x] (long (Math/floor (double x))))
   'round   (fn [x] (Math/round (double x)))
   'sqrt    (fn [x] (Math/sqrt (double x)))
   'pow     (fn [b e] (Math/pow (double b) (double e)))
   'exp     (fn [x] (Math/exp (double x)))
   'ln      (fn [x] (Math/log (double x)))
   'log10   (fn [x] (Math/log10 (double x)))
   'sign    (fn [x] (long (Math/signum (double x))))
   'sum     (fn [c] (reduce + 0 c))
   'product (fn [c] (reduce * 1 c))
   ;; stats
   'mean   mean*
   'avg    mean*
   'median (fn [c] (let [s (vec (sort c)) n (count s)]
                     (cond (zero? n) 0
                           (odd? n)  (nth s (quot n 2))
                           :else (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0))))
   'variance var*
   'stdev    (fn [c] (Math/sqrt (double (var* c))))
   ;; text
   'upper        str/upper-case
   'lower        str/lower-case
   'trim         str/trim
   'join         (fn ([c] (str/join c)) ([sep c] (str/join sep c)))
   'split        (fn [s sep] (vec (str/split (str s) (re-pattern (java.util.regex.Pattern/quote (str sep))))))
   'str-replace  (fn [s a b] (str/replace (str s) (str a) (str b)))
   'starts-with? (fn [s p] (str/starts-with? (str s) (str p)))
   'ends-with?   (fn [s p] (str/ends-with? (str s) (str p)))
   'includes?    (fn [s p] (str/includes? (str s) (str p)))
   'blank?       (fn [s] (str/blank? (str s)))
   ;; date (ISO yyyy-MM-dd strings)
   'today        (fn [] (str (java.time.LocalDate/now)))
   'year         (fn [s] (.getYear (ld s)))
   'month        (fn [s] (.getMonthValue (ld s)))
   'day          (fn [s] (.getDayOfMonth (ld s)))
   'days-between (fn [a b] (.between java.time.temporal.ChronoUnit/DAYS (ld a) (ld b)))
   ;; I/O (see no-io): clear "not available" instead of an opaque cast crash
   'println no-io 'print no-io 'prn no-io 'pr no-io 'printf no-io
   'newline no-io 'flush no-io 'read no-io 'read-line no-io})

(defn new-ctx
  "A fresh per-sheet SCI context: the stdlib merged into clojure.core, then the
   sheet's user `defs` (a string of top-level forms, e.g. (defn …)) evaluated
   into its namespace, so cells in that sheet can call them. Throws if `defs`
   doesn't evaluate — the caller surfaces it and leaves the sheet unchanged.
   `defs` may be nil/blank (just the stdlib)."
  [defs]
  (let [ctx (sci/init {:namespaces {'clojure.core stdlib}})]
    (when-not (str/blank? defs)
      (sci/eval-string* ctx defs))
    ctx))

(defn- sci-fn
  "Compile the pure user `body` (markers already replaced by the value `syms`)
   to a host-callable fn of those values, sandboxed by SCI `ctx`."
  [ctx syms body]
  (sci/eval-form (sci/fork ctx) (list 'fn (vec syms) body)))

;; --- compile ------------------------------------------------------------

(defn compile
  "Marker form -> Spin, using the sheet's SCI `ctx` (stdlib + user defs). SCI-
   compiles the user body over resolved cell values; the spin awaits each
   distinct referenced cell once (de-dup) and calls the SCI fn. SCI never sees
   spin/await/track."
  [ctx form]
  (let [addrs (vec (deps form))
        syms  (mapv (fn [_] (gensym "c_")) addrs)
        a->s  (zipmap addrs syms)
        body  (walk/postwalk (fn [x] (if (ref? x) (a->s (second x)) x)) form)
        user-fn (sci-fn ctx syms body)
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
