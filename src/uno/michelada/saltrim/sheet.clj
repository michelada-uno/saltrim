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
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.constants :as c]
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
        cols     (atom {})        ; ci -> width-px  (sparse; falls back to @dcw)
        rows     (atom {})        ; ri -> height-px (sparse; falls back to @drh)
        dcw      (atom c/CW)      ; this sheet's DEFAULT column width  (px)
        drh      (atom c/RH)      ; this sheet's DEFAULT row height     (px)
        sci      (atom (formula/new-ctx nil))  ; per-sheet SCI ctx: stdlib + user defs
        defs     (atom [])        ; library: ordered vector of chunks {:id :src} (persisted)
        rt       (ctx/create-execution-context
                  {:metadata {:registry registry :vals vals}})]
    {:rt rt :registry registry :vals vals :meta meta :styles styles
     :cols cols :rows rows :dcw dcw :drh drh :sci sci :defs defs}))

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
  [{:keys [registry vals meta sci]} addr raw]
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
            sp (formula/compile @sci form)]
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
  "Current computed value of `addr`, or nil if blank. Errors -> {:error msg}
   (runtime errors, or a compile error recorded when the formula couldn't be
   built against the sheet's current definitions)."
  [{:keys [rt registry meta]} addr]
  (if-let [ref (get @registry addr)]
    (binding [ec/*execution-context* rt]
      (try @ref (catch Exception e {:error (.getMessage e)})))
    (when-let [err (get-in @meta [addr :err])]
      {:error err})))

(defn raw   [{:keys [meta]} addr] (get-in @meta [addr :raw]))
(defn kind  [{:keys [meta]} addr] (get-in @meta [addr :kind]))
(defn cells [{:keys [meta]}] (keys @meta))
(defn deps
  "Direct cell addresses `addr`'s value formula references (forward deps), or
   #{}. The reverse of `dependents*`; together they are the dependency-graph
   edges."
  [{:keys [meta]} addr] (get-in @meta [addr :deps] #{}))

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
  [{:keys [rt sci]} addr raw]
  (let [t (some-> raw str/trim)]
    (cond
      (or (nil? t) (= "" t)) nil
      (str/starts-with? t "=")
      (binding [ec/*execution-context* rt]
        (let [{:keys [form deps]} (formula/parse (subs t 1) addr)]
          {:raw raw :kind :formula :deps deps :spin (formula/compile @sci form)}))
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

;; --- undo / redo (selective; the per-session stack lives in web) ---------
;;
;; An undo ENTRY = {:addr :prop :before :after} where `prop` is :value or a style
;; keyword and before/after are raw sources (nil = blank). `undo-step` applies one
;; step over the two stacks {:undo [..] :redo [..]} (vectors, top = last): UNDO
;; sets :before + moves the entry to :redo, REDO sets :after + moves it back — but
;; ONLY if the target's CURRENT source still equals what that edit set (`:after`
;; for undo, `:before` for redo). An entry a collaborator has since overwritten is
;; SUPERSEDED → skipped (dropped), so undo never clobbers someone else's work —
;; local selective undo. Pure over the stacks; mutates the sheet via set-cell!/
;; set-style! (caller settles + persists). Returns {:stacks :affected addrs|nil}.

(defn- src-of [sheet addr prop]
  (if (= prop :value) (raw sheet addr) (get (style-srcs sheet addr) prop)))

(defn- apply-prop!
  "Set addr's value (prop :value) or one style prop to `src` (nil/blank clears).
   Returns the addresses to re-render."
  [sheet addr prop src]
  (if (= prop :value)
    (do (set-cell! sheet addr (or src ""))
        (cons addr (into (dependents* sheet addr) (style-dependents sheet addr))))
    (do (set-style! sheet addr prop (or src ""))
        [addr])))

(declare insert-line! delete-line!)

(defn undo-step
  "Apply one undo (`dir` = :undo) or redo (:redo) over `stacks`. See section note.
   A STRUCTURAL entry `{:op :insert|:delete :axis :at}` (a whole row/col
   insert/delete) is always applied — never superseded — by running the inverse
   op on undo / the op itself on redo; it reports `:affected :all` so the caller
   re-renders the whole window."
  [sheet stacks dir]
  (let [[from to pick chk] (if (= dir :undo) [:undo :redo :before :after]
                               [:redo :undo :after :before])]
    (loop [fs (vec (get stacks from)) ts (vec (get stacks to))]
      (if (empty? fs)
        {:stacks (assoc stacks from fs to ts) :affected nil}
        (let [entry (peek fs) fs' (pop fs)]
          (cond
            (:op entry)
            (let [{:keys [op axis at]} entry
                  f (if (= dir :undo)
                      (if (= op :insert) delete-line! insert-line!)
                      (if (= op :insert) insert-line! delete-line!))]
              (f sheet axis at)
              {:stacks (assoc stacks from fs' to (conj ts entry)) :affected :all})

            (= (src-of sheet (:addr entry) (:prop entry)) (chk entry))
            {:stacks (assoc stacks from fs' to (conj ts entry))
             :affected (apply-prop! sheet (:addr entry) (:prop entry) (pick entry))}

            :else (recur fs' ts)))))))

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

;; Per-sheet DEFAULT axis sizes: the size of any column/row WITHOUT a sparse
;; override. Editable in the sheet's properties (so a sheet can be globally
;; denser/wider) and persisted; the global constants/CW/RH are just the initial
;; default. The client reads these from #meta so its geometry math agrees.
(defn default-col-w [{:keys [dcw]}] @dcw)
(defn default-row-h [{:keys [drh]}] @drh)

(defn set-default-col-w! [{:keys [dcw]} w] (when-let [w (pos-int w)] (reset! dcw (long w))))
(defn set-default-row-h! [{:keys [drh]} h] (when-let [h (pos-int h)] (reset! drh (long h))))

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
   before its dependencies. Tolerant: a cell/style whose formula can't compile
   (e.g. it calls a name the sheet's current definitions don't provide) is kept
   as an error (its raw is preserved; `value` reports {:error …}) instead of
   aborting the load. Returns {:errors [[addr msg] …]}."
  [{:keys [meta] :as sheet} doc]
  (let [errs (atom [])]
    (doseq [[addr props] doc]
      (when-let [raw (:value props)]
        (try (set-cell! sheet addr raw)
             (catch Exception e
               (swap! errs conj [addr (.getMessage e)])
               (swap! meta assoc addr {:raw raw :kind :error :err (.getMessage e)}))))
      (doseq [[prop raw] (:style props)]
        (try (set-style! sheet addr prop raw)
             (catch Exception e
               (swap! errs conj [(str addr " " (name prop)) (.getMessage e)])))))
    {:errors @errs}))

;; --- structural edits: insert / delete a whole row or column ------------
;;
;; Inserting a blank line at index `at` on an axis shifts every cell at index
;; >= at by one, and — crucially — every formula reference that points at a
;; shifted cell follows (formula/insert-shift), so the sheet stays consistent (a
;; range straddling the line grows). It is a full rebuild from the (shifted)
;; document; `delete-line!` is the exact inverse (so an insert undoes as one step,
;; via a structural undo entry). Cells shifted off the grid are dropped.

(defn- shift-sizes
  "Shift a sparse axis-size map for an insert(+1)/delete(-1) at index `at`
   (`at` nil = the other axis, untouched). Drops the removed line's entry."
  [m at delta]
  (if (nil? at)
    m
    (into {} (keep (fn [[k v]]
                     (let [k (long k)]
                       (cond (and (neg? delta) (= k at)) nil
                             (>= k at) [(max 0 (+ k (long delta))) v]
                             :else     [k v]))))
          m)))

(defn- reshape!
  "Insert (delta +1) or delete (delta -1) a line at index `at` on `axis`
   (:row|:col). Rebuilds the cell graph from the document with addresses + refs
   shifted. Returns the sheet."
  [{:keys [cols rows] :as sheet} axis at delta]
  (let [remap (fn [a] (let [{:keys [ci ri]} (addr/parse a)]
                        (cond
                          (and (= axis :col) (>= ci at)) (addr/make (max 0 (+ ci (long delta))) ri)
                          (and (= axis :row) (>= ri at)) (addr/make ci (max 0 (+ ri (long delta))))
                          :else a)))
        drop? (fn [a] (let [{:keys [ci ri]} (addr/parse a)]   ; on delete, drop the removed line
                        (and (neg? delta)
                             (or (and (= axis :col) (= ci at))
                                 (and (= axis :row) (= ri at))))))
        rw    (fn [s] (formula/insert-shift s axis at delta))
        in-grid? (fn [a] (let [{:keys [ci ri]} (addr/parse a)]
                           (and (< ci c/MAX-COLS) (< ri c/MAX-ROWS))))
        doc'  (into {} (for [[a {:keys [value style]}] (document sheet)
                             :when (not (drop? a))
                             :let  [na (remap a)] :when (in-grid? na)]
                         [na (cond-> {:value (when value (rw value))}
                               (seq style) (assoc :style (update-vals style rw)))]))
        cw'   (shift-sizes @cols (when (= axis :col) at) delta)
        rh'   (shift-sizes @rows (when (= axis :row) at) delta)]
    ;; tear down the old graph (cleans every spin) then rebuild at new positions
    (doseq [[a props] (document-styles sheet) [p _] props] (set-style! sheet a p ""))
    (doseq [a (vec (cells sheet))] (set-cell! sheet a ""))
    (load-document! sheet doc')
    (load-sizing! sheet cw' rh')
    sheet))

(defn insert-line!
  "Insert a blank row/column at index `at` on `axis` (:row|:col): cells at index
   >= at shift one further, formula references follow, axis sizes shift."
  [sheet axis at] (reshape! sheet axis at 1))

(defn delete-line!
  "Remove the row/column at index `at` on `axis` — the inverse of `insert-line!`.
   (Assumes nothing references the removed line, which holds when undoing the
   insert of a freshly-blank line.)"
  [sheet axis at] (reshape! sheet axis at -1))

;; --- per-sheet definitions: a chunk LIBRARY (user functions; ROADMAP #2) --
;;
;; Each sheet has its own SCI namespace (`:sci`) built from the stdlib plus the
;; user's definitions. The definitions are a LIBRARY: an ordered vector of chunks
;; `{:id :src}`, each a block of top-level forms ((defn …)/(def …)) edited
;; independently (and lockable per chunk for collaboration — see web). All chunks
;; merge (in order) into one source that builds the context; every formula in the
;; sheet can call the resulting functions/constants. Any change rebuilds the
;; context and recompiles the whole cell graph against it. Persisted with the
;; sheet. Name conflicts across chunks are resolved by eval order (later wins) —
;; a real conflict policy is TBD.

(defn- chunk-id [] (str "d" (subs (str (random-uuid)) 0 8)))

(defn- merge-src
  "Concatenate the non-blank chunk sources (in order) into one definitions
   source string for the SCI context."
  [chunks]
  (str/join "\n\n" (keep (fn [{:keys [src]}] (not-empty (some-> src str/trim))) chunks)))

(defn defs
  "The sheet's definitions library: an ordered vector of chunks {:id :src}."
  [{:keys [defs]}]
  @defs)

(defn merged-defs
  "All chunk sources merged into one string (what the SCI context is built from)."
  [sheet]
  (merge-src (defs sheet)))

(defn- clear-all!
  "Tear down every cell + style spin and empty the source maps for a full
   rebuild. Leaves axis sizing (:cols/:rows) and :defs/:sci intact."
  [sheet]
  (run! spin-core/cleanup-spin! (vals @(:registry sheet)))
  (doseq [props (vals @(:styles sheet))
          e     (vals props)]
    (when-let [sp (:spin e)] (spin-core/cleanup-spin! sp)))
  (reset! (:registry sheet) {})
  (reset! (:vals sheet) {})
  (reset! (:meta sheet) {})
  (reset! (:styles sheet) {}))

(defn- apply-defs!
  "Replace the library with `chunks` and rebuild the whole sheet against the new
   SCI context. The merged source is validated first — if it doesn't evaluate,
   this throws and leaves the sheet (library, context, cells) untouched. Per-cell
   compile failures afterwards are tolerated (the cell keeps its raw and reads as
   an error). Returns {:errors [[addr msg] …]} from the rebuild."
  [{:keys [rt sci defs] :as sheet} chunks]
  (let [chunks (vec chunks)
        ctx    (formula/new-ctx (merge-src chunks))   ; throws on bad source
        doc    (document sheet)]
    (reset! defs chunks)
    (reset! sci ctx)
    (binding [ec/*execution-context* rt]
      (clear-all! sheet)
      (let [res (load-document! sheet doc)]
        (settle! sheet)
        res))))

(defn set-defs!
  "Replace the whole definitions library and rebuild. Accepts a chunk vector, or
   a plain string (wrapped as a single chunk — convenient for tests/loading a
   legacy single-source `:defs`). Returns {:errors …}."
  [sheet chunks]
  (apply-defs! sheet
               (cond
                 (string? chunks) (if (str/blank? chunks) [] [{:id (chunk-id) :src chunks}])
                 :else            (mapv (fn [c] (update c :id #(or % (chunk-id)))) chunks))))

(defn add-def!
  "Append a new chunk with source `src`; rebuild. Returns {:id <new> :errors …}.
   A chunk whose merged source doesn't evaluate is rejected (apply-defs! throws)."
  [sheet src]
  (let [c {:id (chunk-id) :src (str src) :edited (System/currentTimeMillis)}]
    (assoc (apply-defs! sheet (conj (defs sheet) c)) :id (:id c))))

(defn update-def!
  "Replace chunk `id`'s source (stamping its last-edit time); rebuild. {:errors …}."
  [sheet id src]
  (apply-defs! sheet (mapv #(if (= (:id %) id)
                              (assoc % :src (str src) :edited (System/currentTimeMillis))
                              %)
                           (defs sheet))))

(defn remove-def!
  "Drop chunk `id`; rebuild. Returns {:errors …}."
  [sheet id]
  (apply-defs! sheet (filterv #(not= (:id %) id) (defs sheet))))
