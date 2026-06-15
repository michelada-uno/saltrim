(ns uno.michelada.saltrim.sheet
  "Cell registry over a Spindel execution context — uniform Spin model.

   Per sheet (all in ctx metadata so compiled bodies resolve cells):
   - :registry {addr -> public Spin}      every non-blank cell (lookup / await)
   - :vals     {addr -> SignalRef}         editable input of literal cells
   - :meta     {addr -> {:raw :kind :deps}} document layer (raw text, etc.)

   Literal cell  = SignalRef (value) + a wrapper Spin (deref (track sig)).
   Formula cell  = Spin compiled from an `=`-expression; refs other cells via
                   `await`, so formula->formula works."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.formula :as formula]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(defn create-sheet []
  (let [registry (atom {})
        vals     (atom {})
        meta     (atom {})
        styles   (atom {})
        cols     (atom {})        ; ci -> width-px  (sparse; default elsewhere)
        rows     (atom {})        ; ri -> height-px (sparse)
        rt       (ctx/create-execution-context
                  {:metadata {:registry registry :vals vals}})]
    {:rt rt :registry registry :vals vals :meta meta :styles styles
     :cols cols :rows rows}))

(defn- classify [raw]
  (let [t (some-> raw str/trim)]
    (cond (or (nil? t) (= "" t))   :blank
          (str/starts-with? t "=") :formula
          :else                    :literal)))

(defn- parse-literal [raw]
  (let [t (str/trim raw)]
    (cond
      (re-matches #"[-+]?\d+" t)               (Long/parseLong t)
      (re-matches #"[-+]?\d*\.\d+([eE]\d+)?" t) (Double/parseDouble t)
      :else                                    t)))

(defn- rdeps
  "Addresses whose formula references `addr`."
  [meta addr]
  (keep (fn [[a m]] (when (contains? (:deps m) addr) a)) @meta))

(defn- would-cycle?
  "Would installing `addr` with `new-deps` create a cycle? True if `addr` is
   reachable from new-deps following the forward dep graph (cell -> :deps).
   Catches self-ref and indirect cycles. Must run BEFORE compile — a cyclic
   formula deadlocks await into StackOverflowError."
  [meta addr new-deps]
  (let [deps-of (fn [c] (if (= c addr) new-deps (get-in @meta [c :deps])))]
    (loop [stack (vec new-deps) seen #{}]
      (if-let [c (peek stack)]
        (let [stack (pop stack)]
          (cond
            (= c addr)  true
            (seen c)    (recur stack seen)
            :else       (recur (into stack (deps-of c)) (conj seen c))))
        false))))

(defn- write-cell!
  "Local update of one cell. Returns true if addr's PUBLIC spin object was
   created/replaced/removed (structural change -> dependents must rebuild)."
  [{:keys [registry vals meta]} addr raw]
  (let [prev-kind (get-in @meta [addr :kind])
        old-spin  (get @registry addr)]
    (case (classify raw)
      :blank
      (do (when old-spin (spin-core/cleanup-spin! old-spin))
          (swap! registry dissoc addr)
          (swap! vals dissoc addr)
          (swap! meta dissoc addr)
          true)

      :literal
      (let [v (parse-literal raw)]
        (if-let [vs (get @vals addr)]
          (reset! vs v)                              ; value-only: propagates
          (let [vs (sig/->SignalRef (str "val:" addr) v)]
            (sig/ensure-signal-initialized! vs)
            (swap! vals assoc addr vs)))
        (swap! meta assoc addr {:raw raw :kind :literal})
        (if (= prev-kind :literal)
          false                                       ; spin unchanged
          (do (when old-spin (spin-core/cleanup-spin! old-spin))
              (swap! registry assoc addr (formula/compile-literal-wrapper addr))
              true)))

      :formula
      (let [{:keys [form deps]} (formula/parse (subs (str/trim raw) 1))
            _  (when (would-cycle? meta addr deps)
                 (throw (ex-info "circular reference" {:addr addr :deps deps})))
            sp (formula/compile form)]
        (when old-spin (spin-core/cleanup-spin! old-spin))
        (swap! registry assoc addr sp)
        (swap! meta assoc addr {:raw raw :kind :formula :deps deps})
        true))))

(defn set-cell!
  "Set cell `addr` from raw input. When a cell's public spin is replaced
   (structural change), transitively rebuilds dependents so they re-capture
   the new node. Value-only edits skip the rebuild (signal propagates). The
   visited set guards against cycles. Returns the sheet."
  [{:keys [rt meta] :as sheet} addr raw]
  (binding [ec/*execution-context* rt]
    (let [visited (volatile! #{})]
      (letfn [(go [a r]
                (when-not (contains? @visited a)
                  (vswap! visited conj a)
                  (when (write-cell! sheet a r)
                    (doseq [d (rdeps meta a)]
                      (go d (get-in @meta [d :raw]))))))]
        (go addr raw))))
  sheet)

(defn dependents*
  "Transitive set of addresses whose formulas reference `addr` (reverse-dep
   closure), excluding `addr` itself. These are the cells whose value may
   change when `addr` changes — the set to re-render."
  [{:keys [meta]} addr]
  (let [m @meta]
    (loop [seen #{} frontier [addr]]
      (if-let [a (first frontier)]
        (let [ds  (keep (fn [[x mm]] (when (contains? (:deps mm) a) x)) m)
              new (remove #(or (= % addr) (contains? seen %)) ds)]
          (recur (into seen new) (into (subvec (vec frontier) 1) new)))
        seen))))

(defn settle! [{:keys [rt]}]
  (simple/await-drain-complete! rt :timeout-ms 5000))

(defn close!
  "Release the execution context (executor + drain). Call when unloading a
   sheet that no session references."
  [{:keys [rt]}]
  (ctx/close-context! rt))

(defn value
  "Current computed value of `addr`, or nil if blank. Errors -> {:error msg}."
  [{:keys [rt registry]} addr]
  (when-let [ref (get @registry addr)]
    (binding [ec/*execution-context* rt]
      (try @ref (catch Exception e {:error (.getMessage e)})))))

(defn raw   [{:keys [meta]} addr] (get-in @meta [addr :raw]))
(defn kind  [{:keys [meta]} addr] (get-in @meta [addr :kind]))
(defn cells [{:keys [meta]}] (keys @meta))

;; --- style layer --------------------------------------------------------
;;
;; A SEPARATE registry of presentational properties (e.g. :bg) per cell. Each
;; property is a literal string OR an `=`-formula compiled into its own Spin.
;; Style spins only READ the value layer (via `$val`/`#cell`) — never the
;; reverse — so they can't form a cycle with value formulas, and the value
;; layer's cycle detection / rebuild logic stays untouched. Style state is
;; {addr -> {prop -> {:raw :kind :deps :spin}}}.

(defn- compile-style
  "Build a style entry for raw source, or nil if blank. `=`-formulas compile to
   a Spin (with `$val` bound to the owner's value); literals store the string."
  [{:keys [rt]} addr raw]
  (let [t (some-> raw str/trim)]
    (cond
      (or (nil? t) (= "" t)) nil
      (str/starts-with? t "=")
      (binding [ec/*execution-context* rt]
        (let [{:keys [form deps]} (formula/parse (subs t 1) addr)]
          {:raw raw :kind :formula :deps deps :spin (formula/compile form)}))
      :else {:raw raw :kind :literal :deps #{} :spin nil})))

(defn set-style!
  "Set style PROP (keyword, e.g. :bg) of `addr` from raw source. Blank removes
   it. Returns the sheet."
  [{:keys [rt styles] :as sheet} addr prop raw]
  (binding [ec/*execution-context* rt]
    (when-let [old (get-in @styles [addr prop])]
      (when-let [sp (:spin old)] (spin-core/cleanup-spin! sp)))
    (if-let [e (compile-style sheet addr raw)]
      (swap! styles assoc-in [addr prop] e)
      (swap! styles update addr dissoc prop)))
  sheet)

(defn style-value
  "Computed value of style PROP of `addr`: a string, nil (blank/absent), or
   `{:error msg}` when the property's formula blows up. Errors are surfaced (not
   swallowed) so a broken style formula is visible — same contract as `value`."
  [{:keys [rt styles]} addr prop]
  (when-let [{:keys [kind raw spin]} (get-in @styles [addr prop])]
    (case kind
      :literal raw
      :formula (binding [ec/*execution-context* rt]
                 (try (let [v @spin] (when (some? v) (str v)))
                      (catch Exception e {:error (.getMessage e)}))))))

(defn style-errors
  "Seq of [prop msg] for `addr`'s style props that currently error (for toast)."
  [{:keys [styles] :as sheet} addr]
  (keep (fn [prop] (let [v (style-value sheet addr prop)]
                     (when (:error v) [prop (:error v)])))
        (keys (get @styles addr))))

(defn style-deps
  "Addresses referenced by `addr`'s style formulas (reverse edges to re-render
   when a value changes). Excludes `addr` itself (its own re-render is implied)."
  [{:keys [styles]} addr]
  (disj (reduce into #{} (map :deps (vals (get @styles addr)))) addr))

(defn style-dependents
  "Addresses whose style formulas reference `addr` — must re-render when `addr`
   changes (the style layer's analogue of `dependents*`)."
  [{:keys [styles]} addr]
  (set (keep (fn [[a props]]
               (when (some #(contains? (:deps %) addr) (vals props)) a))
             @styles)))

(defn style-srcs
  "Raw style sources of one cell: {prop raw} (empty when none). For echoing the
   selected cell's style back into the style bar."
  [{:keys [styles]} addr]
  (into {} (map (fn [[p e]] [p (:raw e)])) (get @styles addr)))

(defn document-styles
  "Serializable style source: {addr {prop raw}} (only cells that have any)."
  [{:keys [styles]}]
  (into {} (keep (fn [[a props]]
                   (when (seq props)
                     [a (into {} (map (fn [[p e]] [p (:raw e)])) props)])))
        @styles))

;; --- axis sizing --------------------------------------------------------
;;
;; Per-column widths / per-row heights, sparse: only non-default entries are
;; stored, keyed by zero-based index. Plain pixel integers (not reactive
;; formulas) — the rendering geometry wants concrete numbers, and width-by-
;; formula is a rare need we can layer on later via the style machinery.

(defn- pos-int [n] (let [n (long n)] (when (pos? n) n)))

(defn set-col-width!  [{:keys [cols]} ci w]
  (if-let [w (pos-int w)] (swap! cols assoc (long ci) w) (swap! cols dissoc (long ci))))
(defn set-row-height! [{:keys [rows]} ri h]
  (if-let [h (pos-int h)] (swap! rows assoc (long ri) h) (swap! rows dissoc (long ri))))

(defn col-width  [{:keys [cols]} ci] (get @cols (long ci)))
(defn row-height [{:keys [rows]} ri] (get @rows (long ri)))
(defn col-widths  [{:keys [cols]}] @cols)
(defn row-heights [{:keys [rows]}] @rows)

(defn load-sizing!
  "Replace the axis-size maps from a stored document (keys may arrive as ints)."
  [{:keys [cols rows]} col-map row-map]
  (reset! cols (into {} (map (fn [[k v]] [(long k) (long v)])) col-map))
  (reset! rows (into {} (map (fn [[k v]] [(long k) (long v)])) row-map)))

;; --- persistence (source, not runtime state) ---------------------------

(defn document
  "Serializable source of the sheet: {addr {:value raw :style {prop raw}}}.
   Per-cell PROPERTY map; :style holds presentational source (each a reactive
   property compiled from its source). :format slots in the same way later.
   Runtime spins are rebuilt from this; we never serialize the graph."
  [{:keys [meta] :as sheet}]
  (let [st (document-styles sheet)]
    (into {} (map (fn [[a m]]
                    [a (cond-> {:value (:raw m)}
                         (seq (get st a)) (assoc :style (get st a)))]))
          @meta)))

(defn load-document!
  "Rebuild a sheet's cells (and their style props) from a document map.
   Order-independent: formula refs resolve at run time, so a cell may load
   before its dependencies."
  [sheet doc]
  (doseq [[addr props] doc]
    (when-let [raw (:value props)]
      (set-cell! sheet addr raw))
    (doseq [[prop raw] (:style props)]
      (set-style! sheet addr prop raw)))
  sheet)
