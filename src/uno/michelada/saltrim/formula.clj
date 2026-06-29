(ns uno.michelada.saltrim.formula
  "Formula = a Clojure expression, evaluated by SCI in a sandbox. Cell refs via
   reader tags:
     #cell  A1       -> current value of A1
     #cells A1:A3    -> vector of current values [A1 A2 A3]      (column)
     #cells A1:C1    -> [A1 B1 C1]                               (row)
     #cells A1:B2    -> [A1 B1 A2 B2]  ROW-MAJOR rectangle       (block)
   Any inclusive rectangle works (for map/reduce); ranges expand at read time.

   Terse sugar: a bare `$A1` reads like `#cell A1`, and `$A3:D8` like
   `#cells A3:D8` — `$` is just a compact cell-ref sigil (shifts on paste like the
   reader tags). See `dollar-ref`.

   Relative sugar: `$<col><row>` names a cell by OFFSET from the owner — each of
   col/row is `_` (same index), `+N`, or `-N`. e.g. in B3, `$_-1` -> B2 and
   `$-2_` -> the cell two columns left. Resolved against the owner at parse time,
   so it is copy-invariant (re-resolves per destination — `=(inc $_-1)` copied
   down fills a running counter). See `rel-ref`.

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

;; A bare `$`-prefixed A1 address is terse sugar for a reader tag:
;;   $A1     <=> #cell  A1
;;   $A3:D8  <=> #cells A3:D8
;; These read as ordinary symbols (`$A1`, `$A3:D8`) so we rewrite them on the
;; PARSED form (not the string) — that way a `$A1` inside a string literal is
;; untouched. `$` is a terse cell ref here (relative, shifts on paste like the
;; reader tags), NOT an Excel-style absolute marker.
(def ^:private dollar-ref-re #"\$([A-Za-z]+[0-9]+)(?::([A-Za-z]+[0-9]+))?")

(defn- dollar-ref
  "Marker form for a `$`-cell symbol (`$A1` -> a ref; `$A3:D8` -> a vector of
   refs like #cells), or nil if `x` isn't one."
  [x]
  (when (symbol? x)
    (when-let [[_ a b] (re-matches dollar-ref-re (name x))]
      (if b
        (cons 'vector (map ref-marker (addr/range-cells a b)))
        (ref-marker a)))))

;; A RELATIVE `$`-ref names a cell by OFFSET from the owner cell, so it survives
;; copy/paste unchanged (re-resolved per destination) — the inverse of the
;; absolute `$A1` sugar above. Syntax `$<col><row>` where each part is `_` (same
;; index), `+N`, or `-N`. e.g. in B3: `$_-1` -> B2, `$-2_` -> the cell two cols
;; left, `$+1-1` -> one col right & one row up. Disjoint from `$A1` (those start
;; with a column LETTER). `shift-refs` deliberately leaves these alone.
(def ^:private rel-ref-re #"\$(_|[-+]\d+)(_|[-+]\d+)")

(defn- rel-coord [part base]
  (if (= "_" part) base (+ base (Long/parseLong part))))   ; parseLong accepts a leading +

(defn- rel-ref
  "Marker for a relative `$`-ref symbol resolved against owner `self`, or nil if
   `x` isn't one. Throws when the offset lands off the grid (negative col/row)."
  [x self]
  (when (and self (symbol? x))
    (when-let [[_ c r] (re-matches rel-ref-re (name x))]
      (let [{:keys [ci ri]} (addr/parse self)
            nc (rel-coord c ci)
            nr (rel-coord r ri)]
        (if (and (>= nc 0) (>= nr 0))
          (ref-marker (addr/make nc nr))
          (throw (ex-info (str "relative reference $" c r " off the grid from " self)
                          {:self self})))))))

(defn parse
  "Formula string (without leading =) -> {:form :deps}.

   Bare `$A1` / `$A3:D8` symbols are terse sugar for `#cell A1` / `#cells A3:D8`
   (see `dollar-ref`) — usable in any formula.

   With `self` (the owner address, e.g. for a STYLE/FORMAT property), the bare
   symbol `$val` rewrites to a ref on the owner's own value — sugar for
   `#cell <self>`. So a style reads the cell's current value reactively (an
   `await` edge) without retyping the address. `$val` is only meaningful where
   an owner exists; in a plain value formula it stays an unknown symbol and SCI
   rejects it at compile."
  ([s] (parse s nil))
  ([s self]
   (let [form0 (edn/read-string {:readers readers} s)
         form  (walk/postwalk
                (fn [x]
                  (cond
                    (and self (= '$val x)) (ref-marker self)
                    :else                  (or (rel-ref x self) (dollar-ref x) x)))
                form0)]
     {:form form :deps (deps form)})))

;; --- reference shifting (clipboard paste) -------------------------------

(defn- shift-addr [a dc dr]
  (let [{:keys [ci ri]} (addr/parse a)]
    (addr/make (max 0 (+ ci (long dc))) (max 0 (+ ri (long dr))))))

(defn shift-refs
  "Shift every cell reference in formula `src` by (dc, dr) cols/rows, clamped at
   A1 — so clipboard paste is RELATIVE: copy =(+ #cell A1 1) from B1 to B2 pastes
   =(+ #cell A2 1). Handles both the reader tags (#cell/#cells) and the terse
   `$A1`/`$A3:D8` sugar. Non-formula text is returned as-is; a zero shift is a
   no-op."
  [src dc dr]
  (if (or (nil? src) (and (zero? dc) (zero? dr)))
    src
    (-> src
        (str/replace #"#cells\s+([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                     (fn [[_ a b]] (str "#cells " (shift-addr a dc dr) ":" (shift-addr b dc dr))))
        (str/replace #"#cell\s+([A-Za-z]+[0-9]+)"
                     (fn [[_ a]] (str "#cell " (shift-addr a dc dr))))
        ;; $-sugar: range first, then a lone $A1 (the (?!:) keeps the single
        ;; pass from re-shifting the already-shifted left half of a $A3:D8 range)
        (str/replace #"\$([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                     (fn [[_ a b]] (str "$" (shift-addr a dc dr) ":" (shift-addr b dc dr))))
        (str/replace #"\$([A-Za-z]+[0-9]+)(?!:)"
                     (fn [[_ a]] (str "$" (shift-addr a dc dr)))))))

(defn- bump-addr
  "Shift `a`'s coordinate on `axis` (:row|:col) by `delta` IFF that coordinate is
   `>= at` (clamped ≥ 0); other coordinates / refs untouched. The conditional
   shift behind inserting/deleting a line."
  [a axis at delta]
  (let [{:keys [ci ri]} (addr/parse a)]
    (cond
      (and (= axis :col) (>= ci at)) (addr/make (max 0 (+ ci (long delta))) ri)
      (and (= axis :row) (>= ri at)) (addr/make ci (max 0 (+ ri (long delta))))
      :else a)))

(defn insert-shift
  "Rewrite cell references in formula `src` for a structural row/col change:
   insert a blank line (`delta` +1) or remove one (`delta` -1) at index `at` on
   `axis` (:row|:col). A ref endpoint whose coordinate on that axis is `>= at`
   moves by `delta`; the rest stay. Each `#cells` endpoint is rewritten
   independently, so a range straddling the line grows/shrinks. Handles the reader
   tags and the `$A1`/`$A3:D8` sugar; non-formula text / nil pass through.
   (delete's handling assumes the removed line carries no references TO it — true
   when undoing an insert of a blank line.)"
  [src axis at delta]
  (if (nil? src)
    src
    (let [b (fn [a] (bump-addr a axis at delta))]
      (-> src
          (str/replace #"#cells\s+([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                       (fn [[_ a c]] (str "#cells " (b a) ":" (b c))))
          (str/replace #"#cell\s+([A-Za-z]+[0-9]+)"
                       (fn [[_ a]] (str "#cell " (b a))))
          (str/replace #"\$([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                       (fn [[_ a c]] (str "$" (b a) ":" (b c))))
          (str/replace #"\$([A-Za-z]+[0-9]+)(?!:)"
                       (fn [[_ a]] (str "$" (b a))))))))

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

(defn- nums
  "Keep only the numbers in a cell collection, so aggregates IGNORE blank cells
   (which resolve to nil) — matching a spreadsheet's SUM/AVERAGE-skip-blanks."
  [c] (filter number? c))

(defn- mean* [c] (let [c (nums c)] (if (seq c) (/ (double (reduce + 0 c)) (count c)) 0)))
(defn- var* [c]
  (let [c (nums c) n (count c)]
    (if (zero? n) 0
        (let [m (/ (double (reduce + 0 c)) n)]
          (/ (reduce + (map #(let [d (- % m)] (* d d)) c)) n)))))

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
   'sum     (fn [c] (reduce + 0 (nums c)))
   'product (fn [c] (reduce * 1 (nums c)))
   ;; stats — all skip blank (nil) cells, like a spreadsheet
   'mean   mean*
   'avg    mean*
   'median (fn [c] (let [s (vec (sort (nums c))) n (count s)]
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
