(ns uno.michelada.saltrim.web
  "Windowed spreadsheet grid over the sheet engine, driven by Datastar.

   Viewport: a scroll container holds a full-size spacer (real scrollbar for a
   huge logical sheet) and an absolutely-positioned window of only the visible
   cells. Scroll -> post first row/col -> server re-renders the window and
   SSE-patches the cells + sticky headers. Empty cells cost nothing (absent
   from the registry, no spin).

   Formula bar: wide input mirroring the selected cell's SOURCE (value or
   formula). It shares the `$v` signal with the floating #editor, so the two
   stay live-synced; editing the bar posts to the selected cell.

   Run:  clj -M:web   then open http://localhost:8080"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.fmt :as fmt]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk])
  (:gen-class))

;; --- geometry -----------------------------------------------------------

(def ^:private CW 112)            ; cell width  px
(def ^:private RH 26)             ; cell height px
(def ^:private GUT 48)            ; row-header gutter px
(def ^:private HDR 26)            ; col-header height px
(def ^:private MAX-COLS 16384)    ; hard cap for clamping jumps
;; ~600k keeps the spacer div under Firefox's ~17.9M px element limit
;; (600000 * 26 = 15.6M px). See TECHDEBT.md — the giant-spacer scroll model
;; is the real ceiling; want a logical scrollbar that needs no huge div.
(def ^:private MAX-ROWS 600000)
(def ^:private WIN-COLS 16)       ; window size (+overscan)
(def ^:private WIN-ROWS 34)
(def ^:private OVER 2)            ; overscan cells
(def ^:private MIN-COLS 26)       ; spacer never smaller than this
(def ^:private MIN-ROWS 100)
(def ^:private BUF-COLS 6)        ; scrollable buffer past used/visible range
(def ^:private BUF-ROWS 30)
(def ^:private BAR 12)            ; custom scrollbar thickness px

;; Logical scroll: no giant spacer. Cells/headers are positioned WINDOW-RELATIVE
;; (cell at index i in the window sits at (i - base)*CW), and /app.js translates
;; the layers by the sub-cell offset for smoothness + draws its own scrollbars.
;; The window base is the first index rendered = max(0, c0-OVER).

(defn- view-base
  "[col-base row-base] = top-left index of the rendered (overscanned) window."
  [{:keys [r0 c0]}]
  [(max 0 (- (long c0) OVER)) (max 0 (- (long r0) OVER))])

(defn- window
  "Visible cell coords [ci-range ri-range] for first row/col r0 c0 (clamped)."
  [r0 c0]
  (let [[cb rb] (view-base {:r0 r0 :c0 c0})]
    [(range cb (min MAX-COLS (+ cb WIN-COLS)))
     (range rb (min MAX-ROWS (+ rb WIN-ROWS)))]))

;; Axis sizing: columns/rows default to CW/RH but carry sparse per-index px
;; overrides (sheet :cols/:rows). Absolute offset of an index = uniform base
;; plus the (override-base) deltas of every sized index BEFORE it. The same
;; arithmetic runs in /app.js (from the maps in #meta) so client + server agree.

(defn- col-w [sh ci] (or (sheet/col-width sh ci) CW))
(defn- row-h [sh ri] (or (sheet/row-height sh ri) RH))

(defn- axis-off
  "Absolute start px of `i` along an axis whose default size is `base` and whose
   sparse overrides are `om` (index -> size)."
  [om base i]
  (reduce-kv (fn [acc k v] (cond-> acc (< (long k) (long i)) (+ (- (long v) base))))
             (* (long i) base) om))

(defn- axis-x [sh ci] (axis-off (sheet/col-widths sh) CW ci))
(defn- axis-y [sh ri] (axis-off (sheet/row-heights sh) RH ri))

;; --- state --------------------------------------------------------------

;; storage-id -> {:sh sheet :owner uid|nil} (lazy: load / create)
(defonce ^:private sheets* (atom {}))

(defn- sheet-rec
  "The loaded record for a storage id; loads from disk lazily, else creates a
   fresh private sheet owned by `owner`. Registers the sheet in the DB. On its
   first registration the file's legacy :public flag becomes a capability link;
   any pre-link :everyone grant is upgraded to a link too (one-shot)."
  [id owner]
  (or (@sheets* id)
      (let [loaded   (store/load-record id)
            rec      (or loaded {:sh (sheet/create-sheet) :owner owner})
            [o n]    (store/split-id id)
            owner    (or (:owner rec) o)
            new?     (db/ensure-sheet! id owner n)]
        (when (and new? (:public rec)) (db/set-link-level! id :read-write))
        (db/migrate-everyone->link! id)         ; upgrade legacy public grants
        (let [rec (-> rec (dissoc :public) (assoc :owner owner))]
          (swap! sheets* assoc id rec)
          rec))))

(defn- save-rec!
  "Persist a loaded sheet together with its ownership meta."
  [id]
  (when-let [{:keys [sh owner]} (@sheets* id)]
    (store/save! id sh {:owner owner})))

(defn- accessible-rec
  "The record for storage id IF `uid` (carrying optional link `token`) may
   access it: owners reach (and auto-create) their own sheets; anyone signed-in
   reaches a foreign sheet they were granted, or whose link token they hold.
   Nil = denied/invalid."
  [uid id token]
  (when (and uid (store/valid-id? id))
    (let [[owner _] (store/split-id id)]
      (cond
        (nil? owner)  nil                       ; legacy un-namespaced ids: not served
        (= owner uid) (sheet-rec id uid)
        :else (when (or (@sheets* id) (store/exists? id))
                (let [rec (sheet-rec id owner)]
                  (when (db/access-level uid id token) rec)))))))

;; Sessions: one per client. Hold the sheet id + per-session viewport (view/dims)
;; so concurrent clients on the same sheet keep independent scroll. Each carries
;; :last-seen (ms) — real activity (edits/scrolls) refreshes it; a server-side
;; sweep reaps sessions idle past a TTL (the only way to handle crash/sleep,
;; where the beacon never fires). No client heartbeat.
(defonce ^:private sessions* (atom {}))  ; sid -> {:sheet :view :dims :last-seen}

(def ^:private sid-re #"[A-Za-z0-9-]{1,64}")
(def ^:private SESSION-TTL-MS (* 30 60 1000))   ; reap sessions idle > 30 min
(def ^:private SWEEP-MS 60000)                  ; check once a minute

(defn- now [] (System/currentTimeMillis))

;; Per-session presence: a stable color + the cell the user is on (:cursor) and,
;; while actively typing, the cell they are editing (:editing -> locks it for
;; others). Colors are assigned deterministically from the sid so a reconnect
;; keeps the same color.
(def ^:private palette
  ["#e6194b" "#3cb44b" "#4363d8" "#f58231" "#911eb4"
   "#008080" "#f032e6" "#9a6324" "#46827d" "#808000"])
(defn- color-for [sid] (nth palette (mod (Math/abs (long (hash sid))) (count palette))))
(defn- rgba [hex a]
  (let [h (subs hex 1)]
    (format "rgba(%d,%d,%d,%s)"
            (Integer/parseInt (subs h 0 2) 16)
            (Integer/parseInt (subs h 2 4) 16)
            (Integer/parseInt (subs h 4 6) 16) a)))

(defn- session-view [sid] (get-in @sessions* [sid :view] {:r0 0 :c0 0}))
(defn- set-session-view! [sid v] (when (@sessions* sid) (swap! sessions* assoc-in [sid :view] v)))
(defn- session-dims [sid] (get-in @sessions* [sid :dims]))
(defn- set-session-dims! [sid d] (when (@sessions* sid) (swap! sessions* assoc-in [sid :dims] d)))
(defn- sheet-of [sid] (get-in @sessions* [sid :sheet]))

(defn- sessions-on [sheet-id]
  (count (filter #(= sheet-id (:sheet %)) (vals @sessions*))))

(defn- unload-sheet!
  "Save then release a sheet whose last session just left."
  [sheet-id]
  (when (and (zero? (sessions-on sheet-id)) (@sheets* sheet-id))
    (let [{:keys [sh]} (@sheets* sheet-id)]
      (save-rec! sheet-id)
      (sheet/close! sh)
      (swap! sheets* dissoc sheet-id))))

(defn- register-session! [sid sheet-id uid token]
  (let [[owner _] (store/split-id sheet-id)]
    (sheet-rec sheet-id (or owner uid)))  ; acquire (load) the sheet
  (swap! sessions* assoc sid {:sheet sheet-id :view {:r0 0 :c0 0}
                              :dims nil :last-seen (now)
                              :uid uid :token token
                              :uname (or (:name (auth/user-info uid)) uid)
                              :color (color-for sid) :cursor nil :editing nil}))

(defn- touch! [sid] (when (@sessions* sid) (swap! sessions* assoc-in [sid :last-seen] (now))))

(defn- ensure-session!
  "Lazily (re)register a session for an active request, then stamp it alive. A
   client whose session was swept (crash/sleep TTL) transparently comes back.
   A sid registered under a DIFFERENT user is re-registered for the current
   one (a sid is client-generated — never trust it to carry identity). The link
   `token` (if any) is remembered so a later link rotate/downgrade can re-check
   this session's access."
  [sid sheet-id uid & [token]]
  (when (and sid (re-matches sid-re (str sid)))
    (let [s (@sessions* sid)]
      (if (or (nil? s) (not= uid (:uid s)))
        (register-session! sid sheet-id uid token)
        (when token (swap! sessions* assoc-in [sid :token] token))))
    (touch! sid)))

(declare close-gen! broadcast-presence!)
(defn- reap-session!
  "Drop a session: close its push stream and unload the sheet if it was last."
  [sid]
  (when-let [s (@sessions* sid)]
    (close-gen! s)
    (swap! sessions* dissoc sid)
    (unload-sheet! (:sheet s))
    ;; the departed cursor must disappear from peers still on the sheet
    (when (pos? (sessions-on (:sheet s)))
      (broadcast-presence! (:sheet s)))))

(defn- sweep! []
  (let [cutoff (- (now) SESSION-TTL-MS)]
    (doseq [[sid s] @sessions*]
      (when (< (long (:last-seen s 0)) cutoff)
        (reap-session! sid)))))

(defonce ^:private sweeper* (atom nil))
(defn- start-sweeper! []
  (when-not @sweeper*
    (let [pool (java.util.concurrent.Executors/newScheduledThreadPool 1)]
      (reset! sweeper*
              (.scheduleAtFixedRate
               pool ^Runnable (fn [] (try (sweep!) (catch Throwable _)))
               SWEEP-MS SWEEP-MS java.util.concurrent.TimeUnit/MILLISECONDS)))))

(defn- used-max
  "[max-ci max-ri] over non-empty cells (-1 if none)."
  [sh]
  (reduce (fn [[cm rm] a] (let [{:keys [ci ri]} (addr/parse a)]
                            [(max cm ci) (max rm ri)]))
          [-1 -1] (sheet/cells sh)))

(defn- total-px
  "Logical scroll extent [w h] px (cells area only, no gutter/header): covers the
   used range and the current view plus a buffer. Just numbers for the custom
   scrollbar — no DOM element is this big."
  [sh r0 c0]
  (let [[cm rm] (used-max sh)
        cols (min MAX-COLS (+ (max MIN-COLS (inc cm) (+ (long c0) WIN-COLS)) BUF-COLS))
        rows (min MAX-ROWS (+ (max MIN-ROWS (inc rm) (+ (long r0) WIN-ROWS)) BUF-ROWS))]
    ;; total extent = absolute offset of the index just past the buffered range
    ;; (folds in any sparse width/height overrides)
    [(axis-x sh cols) (axis-y sh rows)]))

(defn- in-window? [{:keys [r0 c0]} addr]
  (let [{:keys [ci ri]} (addr/parse addr)]
    (and (<= (- (long c0) OVER) ci (+ (long c0) WIN-COLS))
         (<= (- (long r0) OVER) ri (+ (long r0) WIN-ROWS)))))

;; --- rendering ----------------------------------------------------------

(defn- display [sh a]
  (let [v    (sheet/value sh a)
        mask (sheet/style-value sh a :format)]   ; nil / string / {:error}
    (cond (nil? v) ""
          (map? v) "#ERR"
          (string? mask) (fmt/apply-mask mask v)
          :else (str v))))

(defn- cell-id [a] (str "c_" a))

(def style-css
  "Reactive style prop -> CSS declaration. The whole supported set lives here;
   adding a property is one entry (+ a control in the style panel). Each value is
   a literal or an =-formula computed per cell into a CSS string."
  (array-map :bg     "background-color"
             :fg     "color"
             :weight "font-weight"
             :slant  "font-style"
             :align  "text-align"))

(def value-props
  "Presentational props consumed by transforming the displayed VALUE (not CSS).
   :format is a number mask (see the fmt ns). Same reactive literal-or-=formula
   model as style props — just applied in `display` instead of as a CSS decl."
  [:format])

(defn- prop-allowed? [p]
  (or (contains? style-css p) (some #{p} value-props)))

(defn- cell-style-decls
  "Inline CSS for `a`'s style props (only those resolving to a string; errors /
   blanks are skipped here and reported via the toast)."
  [sh a]
  (apply str (keep (fn [[prop css]]
                     (let [v (sheet/style-value sh a prop)]
                       (when (string? v) (str ";" css ":" v))))
                   style-css)))

(defn- cell-input
  "Minimal per-cell HTML: a DISPLAY div (not an input), positioned WINDOW-RELATIVE
   to (cbase,rbase). data-raw carries the source for the floating editor. A single
   click selects; Enter/double-click opens the editor (handled in /app.js). No
   per-cell input -> no 500 live <input>s."
  [sh a ci ri cbase rbase]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)
        w    (sheet/col-width sh ci)
        h    (sheet/row-height sh ri)
        srcs (sheet/style-srcs sh a)]      ; {prop raw} -> echoed into the style bar
    [:div {:id (cell-id a) :class "cell" :data-raw raw
           :data-sty (when (seq srcs) (json/write-value-as-string srcs))
           :style (str (format "left:%dpx;top:%dpx"
                               (- (axis-x sh ci) (axis-x sh cbase))
                               (- (axis-y sh ri) (axis-y sh rbase)))
                       (when w (format ";width:%dpx" (dec w)))
                       (when h (format ";height:%dpx" (dec h)))
                       (cell-style-decls sh a))}
     disp]))

(defn- cells-html [sh cis ris]
  (let [cb (first cis) rb (first ris)]
    (str (h/html (for [ri ris ci cis] (cell-input sh (addr/make ci ri) ci ri cb rb))))))

(defn- colhead-html [sh cis]
  (let [xb (axis-x sh (first cis))]
    (str (h/html
          (for [ci cis :let [w (col-w sh ci)]]
            [:div {:style (format (str "position:absolute;left:%dpx;top:0;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (- (axis-x sh ci) xb) w HDR HDR)}
             (addr/idx->col ci)
             ;; drag handle on the right edge -> resize this column (/app.js)
             [:div {:class "colgrip" :data-ci ci}]])))))

(defn- rowhead-html [sh ris]
  (let [yb (axis-y sh (first ris))]
    (str (h/html
          (for [ri ris :let [h (row-h sh ri)]]
            [:div {:style (format (str "position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (- (axis-y sh ri) yb) GUT h h)}
             (inc ri)
             [:div {:class "rowgrip" :data-ri ri}]])))))

(defn- meta-html
  "Hidden element carrying, to /app.js: the logical scroll totals (size the
   scrollbars) and the rendered window's base index cb/rb. /app.js translates the
   layers relative to cb/rb — patched together with #cells, so the transform
   always matches the displayed content (no jump while a fetch is in flight)."
  [sh r0 c0]
  (let [[tw th] (total-px sh r0 c0)
        [cb rb] (view-base {:r0 r0 :c0 c0})]
    (str (h/html [:div {:id "meta" :data-tw tw :data-th th :data-cb cb :data-rb rb
                        ;; sparse axis-size overrides so /app.js computes offsets
                        :data-colw (json/write-value-as-string (sheet/col-widths sh))
                        :data-rowh (json/write-value-as-string (sheet/row-heights sh))
                        :style "display:none;"}]))))

(defn- grid-layers
  "Logical-scroll viewport: fixed-size, overflow hidden. Clipped header strips +
   a cell area, each holding an absolutely-positioned layer that /app.js
   translates by the sub-cell offset. Plus the corner, the totals #meta, and two
   custom scrollbars. No giant spacer -> no row cap, no precision wobble."
  [sh {:keys [r0 c0] :as view}]
  (let [[cis ris] (window r0 c0)
        clip "position:absolute;overflow:hidden;"]
    (h/html
     [:div {:id "viewport"
            :data-cw CW :data-rh RH :data-gut GUT :data-hdr HDR
            :data-over OVER :data-bar BAR
            ;; delegated cell handlers. Single click = SELECT only (no edit);
            ;; double-click = open the floating editor (/app.js). Selection posts
            ;; presence declaratively (@post '/presence'); the server renders the
            ;; #self and #peers overlays. Keyboard nav + editor live in /app.js.
            :data-on:click
            (str "evt.target.classList.contains('cell') && "
                 "($sel=evt.target.id.slice(2), $v=(evt.target.dataset.raw||''), "
                 "$edit=false, syncStyle(evt.target.id.slice(2)), @post('/presence'))")
            :data-on:dblclick
            "evt.target.classList.contains('cell') && startEdit(evt.target.id.slice(2))"
            :style "position:relative;height:78vh;border:1px solid #ccc;overflow:hidden;"}
      (h/raw (meta-html sh r0 c0))
      ;; corner
      [:div {:id "corner"
             :style (format (str "position:absolute;left:0;top:0;z-index:4;width:%dpx;height:%dpx;"
                                 "background:#e8e8e8;border:1px solid #e0e0e0;box-sizing:border-box;")
                            GUT HDR)}]
      ;; column header strip (clipped; translated in X)
      [:div {:id "colclip" :style (format "%sleft:%dpx;top:0;right:%dpx;height:%dpx;z-index:3;"
                                          clip GUT BAR HDR)}
       [:div {:id "colstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "colhead"} (h/raw (colhead-html sh cis))]]]
      ;; row header strip (clipped; translated in Y)
      [:div {:id "rowclip" :style (format "%sleft:0;top:%dpx;bottom:%dpx;width:%dpx;z-index:3;"
                                          clip HDR BAR GUT)}
       [:div {:id "rowstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "rowhead"} (h/raw (rowhead-html sh ris))]]]
      ;; cell area (clipped; translated in X+Y)
      [:div {:id "cellclip" :style (format "%sleft:%dpx;top:%dpx;right:%dpx;bottom:%dpx;"
                                           clip GUT HDR BAR BAR)}
       [:div {:id "cells" :style "position:absolute;left:0;top:0;will-change:transform;"}
        (h/raw (cells-html sh cis ris))]
       ;; THIS user's own selection / editing marker — server-rendered from the
       ;; session's :cursor/:editing (no per-cell client JS). pointer-events:none
       ;; so it never blocks typing in the cell beneath. Translated with #cells.
       [:div {:id "self" :style (str "position:absolute;left:0;top:0;z-index:2;"
                                     "pointer-events:none;will-change:transform;")}]
       ;; collaborator cursors / edit-locks; translated with #cells by /app.js.
       ;; container ignores pointer events — only an editing marker re-enables
       ;; them to block the cell beneath.
       [:div {:id "peers" :style (str "position:absolute;left:0;top:0;z-index:3;"
                                      "pointer-events:none;will-change:transform;")}]
       ;; the single floating editor, translated with #cells; /app.js positions +
       ;; shows it over the active cell on Enter/double-click and wires its
       ;; keydown/blur/dblclick. data-bind:v feeds the value into $v; commit posts
       ;; /cell via #celltrigger, Esc cancels.
       [:div {:id "editlayer" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:input {:id "editor" :data-bind:v "" :style "display:none;"}]]]
      ;; custom scrollbars
      [:div {:id "vbar" :style (format (str "position:absolute;right:0;top:%dpx;bottom:%dpx;width:%dpx;"
                                            "background:#f0f0f0;z-index:5;") HDR BAR BAR)}
       [:div {:id "vthumb" :style "position:absolute;left:1px;right:1px;top:0;height:30px;background:#bbb;border-radius:6px;"}]]
      [:div {:id "hbar" :style (format (str "position:absolute;left:%dpx;bottom:0;right:%dpx;height:%dpx;"
                                            "background:#f0f0f0;z-index:5;") GUT BAR BAR)}
       [:div {:id "hthumb" :style "position:absolute;top:1px;bottom:1px;left:0;width:30px;background:#bbb;border-radius:6px;"}]]
      ;; single moving guide line shown while dragging a header grip
      [:div {:id "rzguide"}]])))

(declare share-html)

(defn- help-html
  "In-app end-user reference, toggled by $help. Mirrors README's user guide.
   Pure server-rendered HTML shown/hidden by Datastar data-show — no app.js."
  []
  (let [h3  "margin:.8rem 0 .25rem;font:600 13px sans-serif;"
        p   "margin:.2rem 0;font:13px sans-serif;color:var(--fg);"
        kbd "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;"]
    (str (h/html
          [:div {:id "helpwrap" :data-show "$help"
                 :data-on:click "$help=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:38rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "SaltRim — quick guide"]
             [:button {:class "btn" :data-on:click "$help=false" :title "close"} "✕"]]

            [:div {:style h3} "Cells & formulas"]
            [:p {:style p} "Type a value, or start with " [:span {:style kbd} "="]
             " for a formula (Clojure s-expressions). Reference cells with "
             [:span {:style kbd} "#cell A1"] " and ranges with " [:span {:style kbd} "#cells A1:A3"] "."]
            [:p {:style p} "e.g. " [:span {:style kbd} "=(+ #cell A1 #cell B1)"] " · "
             [:span {:style kbd} "=(reduce + #cells A1:A3)"]]

            [:div {:style h3} "Styling a cell"]
            [:p {:style p} "Use the third toolbar row: pick a property, type a value or an "
             [:span {:style kbd} "="] "-formula, press Apply (or Enter). "
             [:span {:style kbd} "$val"] " is the selected cell's own value, so styles can react to it."]
            [:p {:style p} "Properties: " [:span {:style kbd} "bg"] " (background), "
             [:span {:style kbd} "fg"] " (text color), " [:span {:style kbd} "weight"] " (e.g. bold), "
             [:span {:style kbd} "slant"] " (e.g. italic), " [:span {:style kbd} "align"] " (left/right/center)."]
            [:p {:style p} "e.g. bg " [:span {:style kbd} "=(if (> $val 100) \"tomato\" \"white\")"]]

            [:div {:style h3} "Number format"]
            [:p {:style p} "Property " [:span {:style kbd} "format"] " takes a mask applied to numeric values: "
             [:span {:style kbd} "0.00"] " → 1234.50 · " [:span {:style kbd} "#,##0"] " → 1,234,567 · "
             [:span {:style kbd} "0.0%"] " → 25.0% · " [:span {:style kbd} "$#,##0.00"] " → $1,234.50"]

            [:div {:style h3} "Column & row size"]
            [:p {:style p} "Drag the trailing edge of a column header (or the bottom edge of a row "
             "number) to resize it. Sizes are saved with the sheet."]

            [:div {:style h3} "Navigation"]
            [:p {:style p} "Click to select · double-click or " [:span {:style kbd} "Enter"]
             " to edit · arrows / " [:span {:style kbd} "Tab"] " to move · the address box jumps to a cell."]

            [:div {:style h3} "Sharing"]
            [:p {:style p} "The link / lock button (top bar, owner only) shares the sheet by capability "
             "link or with specific people, at view or edit level."]]]))))

(defn- sheet-picker
  "Dropdown for switching sheets, grouped into 'your sheets' (👤) and 'shared
   with you' (✎ edit / 👁 view). Selecting one navigates to it. A foreign sheet
   reached by a public link (in neither group) shows as a leading ↗ option."
  [uid storage-id sname]
  (let [names       (store/list-names uid)
        [owner _]   (store/split-id storage-id)
        own?        (= owner uid)
        mine        (if own? (distinct (cons sname names)) names)
        shared      (db/sheets-shared-with uid)
        cur-shared? (some #(= storage-id (:sheet-id %)) shared)]
    [:select {:id "sheetpicker" :class "tool" :title "your sheets"
              :data-on:change "el.value && (location.href='/?'+el.value)"
              :style "max-width:11rem;"}
     (when (and (not own?) (not cur-shared?))
       [:option {:value "" :selected true} (str "↗ " sname)])
     [:optgroup {:label "your sheets"}
      (for [n mine]
        [:option {:value (str "s=" n) :selected (and own? (= n sname))} (str "👤 " n)])]
     (when (seq shared)
       [:optgroup {:label "shared with you"}
        (for [{:keys [sheet-id name level]} shared
              :let [[o nm] (store/split-id sheet-id)
                    icon   (if (= level :read-write) "✎" "👁")]]
          [:option {:value (str "u=" o "&s=" nm) :selected (= sheet-id storage-id)}
           (str icon " " (if (str/blank? name) nm name))])])]))

(defn- page [sh storage-id sname uid link-token]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "SaltRim"]
      ;; Cells are display <div class="cell"> (not inputs); the floating editor
      ;; is the single #editor input. Both are absolutely positioned (cells by
      ;; their inline left/top, #editor by app.js) — without this the left/top
      ;; are ignored and everything stacks in flow at the top-left.
      [:style (h/raw
               (str
                ;; design tokens — centralize colors/geometry so a merge can't
                ;; silently drift them and so inline styles can share them.
                ":root{--bg:#fff;--panel:#f6f6f6;--line:#bbb;--grid:#ddd;"
                "--fg:#222;--muted:#666;--accent:#4a90d9;--accent2:#7aa7f0;"
                "--danger:#c0392b;--radius:4px;}"
                ;; toolbar: two rows. row 1 = picker/new/share/identity,
                ;; row 2 = cell-ref + formula bar. .tool/.btn unify the inputs
                ;; and buttons that used to repeat the same inline style string.
                ".toolrow{display:flex;align-items:center;gap:.5rem;margin-bottom:.4rem;}"
                ".tool{font:13px sans-serif;padding:5px 6px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);}"
                ".tool.mono{font-family:monospace;}"
                ".btn{font:12px sans-serif;padding:5px 8px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);cursor:pointer;}"
                ".spacer{flex:1;}"
                ;; resize grips: a thin hit-zone on a header's trailing edge that
                ;; /app.js drags. The #rzguide is the single moving guide line.
                ".colgrip{position:absolute;top:0;right:-3px;width:6px;height:100%;"
                "cursor:col-resize;z-index:5;}"
                ".rowgrip{position:absolute;left:0;bottom:-3px;height:6px;width:100%;"
                "cursor:row-resize;z-index:5;}"
                "#rzguide{position:absolute;display:none;background:var(--accent);"
                "z-index:7;pointer-events:none;}"
                (format (str ".cell{position:absolute;width:%dpx;height:%dpx;"
                             "box-sizing:border-box;border:1px solid var(--grid);"
                             "padding:2px 4px;font:13px monospace;overflow:hidden;"
                             "white-space:nowrap;background:var(--bg);}"
                             "#editor{position:absolute;width:%dpx;height:%dpx;"
                             "box-sizing:border-box;border:1px solid var(--accent);"
                             "padding:2px 4px;font:13px monospace;outline:none;z-index:6;}")
                        (- CW 1) (- RH 1) (- CW 1) (- RH 1))
                ;; selection / editing OVERLAY (#self), server-rendered. Literal %
                ;; in the gradients -> kept OUT of the format call above.
                ;; calm "you are here" selection box:
                ".selfcell{position:absolute;box-sizing:border-box;pointer-events:none;"
                "border:2px solid var(--accent2);}"
                ;; actively editing: animated 'marching ants' border (four gradient
                ;; edges whose position scrolls). pointer-events stays none so the
                ;; cell beneath is still typable.
                ".selfcell.editing{border-color:transparent;"
                "background-image:"
                "linear-gradient(90deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(90deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(0deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(0deg,#1a73e8 50%,transparent 50%);"
                "background-repeat:repeat-x,repeat-x,repeat-y,repeat-y;"
                "background-size:8px 2px,8px 2px,2px 8px,2px 8px;"
                "background-position:0 0,0 100%,0 0,100% 0;"
                "animation:cc-ants .6s infinite linear;}"
                "@keyframes cc-ants{to{background-position:8px 0,-8px 100%,0 -8px,100% 8px;}}"
                "@media(prefers-reduced-motion:reduce){.selfcell.editing{animation:none;}}"
                ;; collaborator cursor overlays (#peers):
                ".peer{position:absolute;box-sizing:border-box;border:2px solid #888;border-radius:2px;}"
                ".peer.editing{cursor:not-allowed;}"
                ".peer .peertag{position:absolute;top:-15px;left:-2px;"
                "font:10px/14px sans-serif;color:#fff;padding:0 4px;"
                "border-radius:3px 3px 3px 0;white-space:nowrap;}"))]
      [:script {:type "module" :src "/datastar.js"}]
      [:script {:src "/app.js"}]]
     [:body {:data-signals (format (str "{cell:'', v:'', err:'', sel:'', edit:false, r0:0, c0:0, "
                                        "sheet:'%s', sid:'', link:'%s', sharepanel:false, shareact:'', "
                                        "plevel:'', gtarget:'', glevel:'read-write', grantee:'', "
                                        "styleprop:'bg', stylesrc:'', rzaxis:'', rzidx:0, rzsize:0, "
                                        "help:false}")
                                   storage-id (or link-token ""))
             :style "font-family:sans-serif;margin:0;padding:.6rem;"}
      ;; hidden input carrying the session id into $sid, and a hidden trigger
      ;; /app.js clicks to open the persistent collaboration stream via Datastar
      ;; (so pushed patches are applied). $sid/$sheet go in the URL.
      [:input {:id "sidbox" :data-bind:sid "" :style "display:none;"}]
      [:button {:id "streamtrigger"
                :data-on:click "@get('/stream?sid='+$sid+'&s='+$sheet+'&t='+$link)"
                :style "display:none;"} ""]
      [:div {:id "toast" :data-show "$err != ''" :data-text "$err"
             :data-on:click "$err=''"
             :style (str "position:fixed;top:1rem;right:1rem;max-width:26rem;background:#c0392b;"
                         "color:#fff;padding:.6rem .9rem;border-radius:6px;font:13px sans-serif;"
                         "cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:20;")}]
      (h/raw (help-html))
      ;; ── toolbar row 1: sheet management + sharing + identity ───────────
      [:div {:class "toolrow"}
       (sheet-picker uid storage-id sname)
       [:input {:id "sheetbox" :class "tool" :placeholder "new sheet…"
                :data-on:keydown "evt.key==='Enter' && el.value && (location.href='/?s='+el.value)"
                :title "type a name + Enter to create/open one of your sheets"
                :style "width:6rem;"}]
       ;; sharing toggle / badge (patched back by POST /share)
       (h/raw (share-html uid storage-id link-token))
       [:span {:class "spacer"}]
       [:button {:class "btn" :data-on:click "$help=true" :title "help / quick guide"} "?"]
       ;; who am I + sign out
       [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
        (or (:name (auth/user-info uid)) uid)]
       [:form {:method "post" :action "/logout" :style "margin:0;"}
        [:button {:class "btn"} "sign out"]]]
      ;; ── toolbar row 2: cell reference + formula bar ────────────────────
      [:div {:class "toolrow"}
       [:input {:id "addrbox" :class "tool mono" :data-bind:sel "" :placeholder "A1"
                :data-on:keydown "evt.key==='Enter' && jump($sel)"
                :style "width:5rem;text-align:center;"}]
       ;; editing via the formula bar still drives presence on the SELECTED cell
       ;; (so it shows the marching-ants self marker and locks it for peers).
       ;; formula bar shares $v with the floating #editor, so the two stay live-
       ;; synced: typing in either updates $v and the other reflects it.
       [:input {:id "fbar" :class "tool mono" :data-bind:v "" :placeholder "value or =formula"
                :data-on:focus "$edit=true, @post('/presence')"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/cell'))"
                :data-on:blur "$cell=$sel, @post('/cell'), $edit=false, @post('/presence')"
                :style "flex:1;"}]]
      ;; ── toolbar row 3: style of the selected cell ──────────────────────
      ;; prop dropdown + a literal-or-=formula source, applied to $sel on Enter
      ;; (like the formula bar — no separate button). $val is the cell's own
      ;; value, e.g. =(if (> $val 100) "tomato" "white")
      [:div {:class "toolrow"}
       [:select {:id "stylepropbox" :class "tool" :data-bind:styleprop ""
                 :data-on:change "syncStyle($sel)"
                 :title "style / format property of the selected cell"}
        (for [p (concat (keys style-css) value-props)] [:option {:value (name p)} (name p)])]
       [:input {:id "stylesrcbox" :class "tool mono" :data-bind:stylesrc ""
                :placeholder "color / mask / =formula (use $val) — Enter to apply"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/style'))"
                :style "flex:1;"}]]
      ;; hidden r0/c0 carriers (/app.js sets these then clicks #viewtrigger so
      ;; Datastar @post /view and applies the returned window patch).
      [:input {:id "r0box" :data-bind:r0 "" :style "display:none;"}]
      [:input {:id "c0box" :data-bind:c0 "" :style "display:none;"}]
      [:button {:id "viewtrigger" :data-on:click "@post('/view')" :style "display:none;"} ""]
      ;; hidden triggers /app.js clicks to drive Datastar actions (it can't set
      ;; signals directly): selecting (cursor presence), starting an edit (lock),
      ;; and committing a cell value. $sel comes from #addrbox, $v from #editor.
      [:button {:id "selecttrigger" :data-on:click "$edit=false, @post('/presence')"
                :style "display:none;"} ""]
      [:button {:id "edittrigger" :data-on:click "$edit=true, @post('/presence')"
                :style "display:none;"} ""]
      [:button {:id "celltrigger" :data-on:click "$cell=$sel, @post('/cell')"
                :style "display:none;"} ""]
      ;; column/row resize: /app.js fills these from a header grip drag, then
      ;; clicks #sizetrigger to POST /size. (rzidx/rzsize seeded numeric so
      ;; Datastar keeps them numbers, not strings.)
      [:input {:id "rzaxisbox" :data-bind:rzaxis "" :style "display:none;"}]
      [:input {:id "rzidxbox"  :data-bind:rzidx ""  :style "display:none;"}]
      [:input {:id "rzsizebox" :data-bind:rzsize "" :style "display:none;"}]
      [:button {:id "sizetrigger" :data-on:click "@post('/size')" :style "display:none;"} ""]
      ;; logical-scroll viewport (custom wheel + scrollbars in /app.js)
      (grid-layers sh {:r0 0 :c0 0})]])))

;; --- SSE (official Datastar SDK) ----------------------------------------

(defn- sse
  "One-shot SSE response: open, run f with the generator, close. f does the
   patch-elements!/patch-signals! calls."
  [req f]
  (hk/->sse-response req {hk/on-open (fn [gen] (f gen) (d*/close-sse! gen))}))

(defn- patch-inner!
  "Replace inner HTML of `selector` with `html`. Blank `html` (e.g. an empty
   #self / #peers overlay) would make the SDK emit a `datastar-patch-elements`
   event with NO `elements` line, which Datastar's client parser rejects
   (\"Error in input stream\") — aborting the SSE stream and reconnect-storming.
   So clear with an inert HTML comment instead: a valid, non-empty `elements`
   payload that still empties the element visually."
  [gen selector html]
  (let [html (if (str/blank? html) "<!-- -->" html)]
    (d*/patch-elements! gen html {d*/selector selector d*/patch-mode d*/pm-inner})))

(defn- signals! [gen m]
  (d*/patch-signals! gen (json/write-value-as-string m)))

;; --- handlers -----------------------------------------------------------

(defn- read-signals [req]
  (json/read-value (d*/get-signals req) json/keyword-keys-object-mapper))

(def ^:private edit-lock (Object.))

(defn- pretty-err [msg]
  (let [m (str msg)]
    (cond
      (re-find #"cannot be cast" m)    "type error (number expected)"
      (re-find #"Divide by zero" m)    "divide by zero"
      (re-find #"unknown cell" m)      "reference to empty cell"
      (re-find #"disallowed symbol" m) "not allowed in a formula"
      (re-find #"circular" m)          "circular reference"
      (re-find #"locked by another" m) "cell is being edited by another collaborator"
      :else m)))

(defn- qparam [req k]
  (some->> (:query-string req)
           (re-find (re-pattern (str "(?:^|&)" k "=([^&]+)")))
           second))

(defn- close-gen! [s]
  (when-let [g (:gen s)] (try (d*/close-sse! g) (catch Throwable _))))

(defn- render-cells
  "Cell-input HTML for addrs, positioned window-relative to view (cbase,rbase)."
  [sh addrs view]
  (let [[cb rb] (view-base view)]
    (apply str (map #(let [{:keys [ci ri]} (addr/parse %)]
                       (str (h/html (cell-input sh % ci ri cb rb))))
                    addrs))))

(defn- broadcast!
  "Push changed cells to OTHER sessions on the same sheet, each scoped to that
   session's own viewport (so coords match their window). A write to a dead
   stream throws -> reap that session."
  [editor-sid sheet-id sh affected]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid editor-sid) (= sheet-id (:sheet s)) (:gen s))
      (let [vis (filter #(in-window? (:view s) %) affected)]
        (when (seq vis)
          (try (d*/lock-sse! (:gen s) (d*/patch-elements! (:gen s) (render-cells sh vis (:view s))))
               (catch Throwable _ (reap-session! sid))))))))

;; --- presence (collaborator cursors + edit locks) ----------------------

(defn- peer-marker
  "Overlay div for one peer's cursor, positioned window-relative to `view`. An
   editing peer's marker captures pointer events (locks the cell beneath)."
  [sh view {:keys [cursor editing color uname]}]
  (let [{:keys [ci ri]} (addr/parse cursor)
        [cb rb] (view-base view)
        editing? (= editing cursor)
        tag (or uname "•")
        ;; the tag normally floats above the cell (CSS top:-15px); on the top
        ;; rendered row that's clipped by #cellclip's overflow, so flip it below.
        top-row? (zero? (- ri rb))
        tag-style (str "background:" color
                       (when top-row? (str ";top:" (dec (row-h sh ri)) "px;border-radius:0 3px 3px 3px")))
        base (format (str "left:%dpx;top:%dpx;width:%dpx;height:%dpx;border-color:%s;")
                     (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                     (dec (col-w sh ci)) (dec (row-h sh ri)) color)]
    (str (h/html
          [:div {:class (str "peer" (when editing? " editing"))
                 :style (if editing?
                          (str base "background:" (rgba color "0.16")
                               ";pointer-events:auto;cursor:not-allowed;")
                          base)}
           [:span {:class "peertag" :style tag-style}
            (if editing? (str tag " editing…") tag)]]))))

(defn- peers-html
  "Overlay markers for every OTHER session whose cursor falls in viewer-sid's
   window. Rendered relative to the viewer's own view so coords line up."
  [viewer-sid sheet-id]
  (let [view (session-view viewer-sid)
        sh   (:sh (@sheets* sheet-id))]
    (apply str
           (for [[sid s] @sessions*
                 :when (and (not= sid viewer-sid) (= sheet-id (:sheet s))
                            (:cursor s) (in-window? view (:cursor s)))]
             (peer-marker sh view s)))))

(defn- self-html
  "THIS session's own selection / editing marker for its #self overlay, rendered
   window-relative to its own view. Empty when there is no cursor or it scrolled
   out of the window."
  [sid sheet-id]
  (let [s    (@sessions* sid)
        view (session-view sid)
        a    (:cursor s)
        sh   (:sh (@sheets* sheet-id))]
    (if (and s sh (= sheet-id (:sheet s)) a (in-window? view a))
      (let [{:keys [ci ri]} (addr/parse a)
            [cb rb] (view-base view)]
        (str (h/html
              [:div {:class (str "selfcell" (when (= (:editing s) a) " editing"))
                     :style (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx;"
                                    (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                                    (dec (col-w sh ci)) (dec (row-h sh ri)))}])))
      "")))

(defn- broadcast-presence!
  "Re-render the #peers overlay for every session on the sheet (each scoped to
   its own view). Called whenever any cursor / editing state changes."
  [sheet-id]
  (doseq [[sid s] @sessions*]
    (when (and (= sheet-id (:sheet s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#peers" (peers-html sid sheet-id)))
           (catch Throwable _ (reap-session! sid))))))

(defn- render-window!
  "Push the full visible window for `view` to `gen`: cells, both header strips,
   the #meta totals, and this session's own overlays. Used after a scroll (/view)
   and after an axis resize (/size), where every position in the window shifts."
  [gen sid sheet-id sh view]
  (let [[cis ris] (window (:r0 view) (:c0 view))]
    (patch-inner! gen "#cells"   (cells-html sh cis ris))
    (patch-inner! gen "#colhead" (colhead-html sh cis))
    (patch-inner! gen "#rowhead" (rowhead-html sh ris))
    (d*/patch-elements! gen (meta-html sh (:r0 view) (:c0 view)))   ; #meta by id
    (patch-inner! gen "#self"  (self-html sid sheet-id))
    (patch-inner! gen "#peers" (peers-html sid sheet-id))))

(defn- broadcast-window!
  "A resize shifts every position, so re-render each OTHER session's whole window
   on its own stream (scoped to that session's view)."
  [editor-sid sheet-id sh]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid editor-sid) (= sheet-id (:sheet s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (render-window! (:gen s) sid sheet-id sh (:view s)))
           (catch Throwable _ (reap-session! sid))))))

(defn- locked-by-other?
  "Is `cell` currently being edited by some session other than `sid`?"
  [sid sheet-id cell]
  (boolean (some (fn [[k s]]
                   (and (not= k sid) (= sheet-id (:sheet s)) (= (:editing s) cell)))
                 @sessions*)))

(defn- deny
  "One-shot SSE that only raises the error toast (auth/access failures)."
  [req msg]
  (sse req (fn [gen] (signals! gen {:err msg}))))

;; --- request gates -------------------------------------------------------
;; Resolve identity + authorization ONCE, then hand the handler what it needs
;; (or short-circuit with a denial). These replace the gate boilerplate that
;; otherwise repeats in every handler.

(defn- owner-of [id] (first (store/split-id id)))

(defn- level-of
  "This user's effective level on the sheet: owners get :read-write; everyone
   else gets whatever the ACL grants the uid or the link `token`
   (:read-write | :read). nil = no access."
  [uid id token rec]
  (if (= uid (:owner rec)) :read-write (db/access-level uid id token)))

(defn- with-access
  "POST handlers: resolve the signed-in user and sheet access from the request
   signals (the link token rides in $link). On success open the one-shot SSE
   response and call (f uid sheet-id rec sig gen); otherwise raise the
   access/auth error toast. The rec handed to `f` carries the effective `:level`."
  [req f]
  (let [uid      (auth/req->uid req)
        sig      (read-signals req)
        sheet-id (:sheet sig)
        token    (not-empty (str (:link sig)))
        rec      (accessible-rec uid sheet-id token)]
    (if-not rec
      (deny req (if uid "no access to this sheet" "not signed in"))
      (let [rec (assoc rec :level (level-of uid sheet-id token rec) :token token)]
        (sse req (fn [gen] (f uid sheet-id rec sig gen)))))))

(defn- with-owner
  "Like `with-access`, but requires the user to OWN the (already-loaded) sheet —
   for owner-only actions such as sharing."
  [req f]
  (let [uid      (auth/req->uid req)
        sig      (read-signals req)
        sheet-id (:sheet sig)
        rec      (when (store/valid-id? (str sheet-id)) (@sheets* sheet-id))]
    (if-not (and uid rec (= uid (:owner rec)))
      (deny req "only the owner can do this")
      (sse req (fn [gen] (f uid sheet-id rec sig gen))))))

(defn- with-stream-access
  "The persistent /stream GET: identity + sheet come from query params (not
   signals). On success call (f uid sid sheet-id) — which returns its OWN
   long-lived SSE response; otherwise a plain 403."
  [req f]
  (let [uid      (auth/req->uid req)
        sid      (qparam req "sid")
        sheet-id (qparam req "s")
        token    (not-empty (qparam req "t"))]
    (if-not (accessible-rec uid sheet-id token)
      {:status 403 :body "no access"}
      (f uid sid sheet-id token))))

(defn- push-changes!
  "Render `affected` cells back to the editor (one-shot SSE), toast any value /
   style errors, and broadcast the same cells to collaborators. Shared by /cell
   and /style — both just compute an affected set and hand it here."
  [gen sid sheet-id sh affected]
  (let [affected (sort (distinct affected))
        view     (session-view sid)
        visible  (filter #(in-window? view %) affected)
        errs (concat
              (keep (fn [a] (when-let [e (:error (sheet/value sh a))]
                              (str a ": " (pretty-err e))))
                    affected)
              (for [a affected [prop e] (sheet/style-errors sh a)]
                (str a " " (name prop) " style: " (pretty-err e))))]
    (when (seq visible)
      (d*/patch-elements! gen (render-cells sh visible view)))
    (signals! gen {:err (if (seq errs) (str/join "; " errs) "")})
    (broadcast! sid sheet-id sh affected)))

(defn- handle-cell [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [cell v sid]} gen]
      (ensure-session! sid sheet-id uid (:token rec))   ; lazy re-register + keep alive
      (if (not= :read-write (:level rec))
        (signals! gen {:err "read-only access — you can't edit this sheet"})
        (let [sh (:sh rec)]
          (when (addr/valid? cell)
            (locking edit-lock
            (try
              (when (locked-by-other? sid sheet-id cell)
                (throw (ex-info "locked by another collaborator" {:locked cell})))
              (sheet/set-cell! sh cell (str v))
              (sheet/settle! sh)
              (save-rec! sheet-id)              ; autosave (source + meta)
              (push-changes! gen sid sheet-id sh
                             (cons cell (into (sheet/dependents* sh cell)
                                              (sheet/style-dependents sh cell))))
              (catch Throwable e
                (signals! gen {:err (str cell ": " (pretty-err (.getMessage e)))}))))))))))

(defn- handle-style [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [cell sid] :as sig} gen]
      (ensure-session! sid sheet-id uid (:token rec))
      (let [sh   (:sh rec)
            prop (keyword (:styleprop sig))
            src  (str (:stylesrc sig))]
        (cond
          (not= :read-write (:level rec))
          (signals! gen {:err "read-only access — you can't edit this sheet"})
          (not (prop-allowed? prop))
          (signals! gen {:err (str "unknown style property: " (:styleprop sig))})
          (not (addr/valid? cell))
          (signals! gen {:err "select a cell first"})
          :else
          (locking edit-lock
            (try
              (sheet/set-style! sh cell prop src)
              (sheet/settle! sh)
              (save-rec! sheet-id)
              ;; only the styled cell re-renders now; style refs to OTHER cells
              ;; just register the edge for future value changes.
              (push-changes! gen sid sheet-id sh [cell])
              (catch Throwable e
                (signals! gen {:err (str cell " " (name prop) " style: "
                                         (pretty-err (.getMessage e)))})))))))))

(defn- handle-view [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [r0 c0 sid]} gen]
      (ensure-session! sid sheet-id uid (:token rec))   ; lazy re-register + keep alive
      (let [sh   (:sh rec)
            view {:r0 (max 0 (long (or r0 0))) :c0 (max 0 (long (or c0 0)))}]
        (set-session-view! sid view)
        ;; logical scroll: always cheap inner patches. The window is positioned
        ;; relative to (c0,r0); /app.js translates + sizes the scrollbars from
        ;; #meta totals (no giant spacer to resize).
        (render-window! gen sid sheet-id sh view)))))

(defn- handle-size [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid rzaxis rzidx rzsize]} gen]
      (ensure-session! sid sheet-id uid (:token rec))
      (let [sh (:sh rec)]
        (if (not= :read-write (:level rec))
          (signals! gen {:err "read-only access — you can't edit this sheet"})
          (locking edit-lock
            (try
              (let [i (long rzidx)
                    s (when rzsize (long rzsize))]    ; nil/0 -> reset to default
                (case (str rzaxis)
                  "col" (sheet/set-col-width!  sh i s)
                  "row" (sheet/set-row-height! sh i s)
                  (throw (ex-info "bad axis" {:axis rzaxis})))
                (save-rec! sheet-id)
                ;; positions across the whole window shift -> full re-render
                (render-window! gen sid sheet-id sh (session-view sid))
                (broadcast-window! sid sheet-id sh))
              (catch Throwable e
                (signals! gen {:err (pretty-err (.getMessage e))})))))))))

(defn- body-json [req]
  (when-let [b (:body req)]
    (json/read-value (slurp b) json/keyword-keys-object-mapper)))

(defn- handle-presence
  "Datastar @post: signals carry {sel edit sheet sid}. Updates this session's
   cursor (:cursor) and editing cell (:editing), patches THIS session's own
   #self overlay back, and re-broadcasts #peers to everyone else on the sheet."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sel edit sid]} gen]
      (ensure-session! sid sheet-id uid (:token rec))
      (let [can-write? (= :read-write (:level rec))]
        (swap! sessions* update sid assoc
               :cursor  (when (addr/valid? sel) sel)
               ;; a read-only viewer may move their cursor but never hold an
               ;; edit lock (which would block writers on a cell they can't edit)
               :editing (when (and edit can-write? (addr/valid? sel)) sel)))
      ;; peers' #peers via their persistent streams; this session's #self via
      ;; the one-shot @post response (the gen this gate opened).
      (broadcast-presence! sheet-id)
      (patch-inner! gen "#self" (self-html sid sheet-id)))))

;; Session lifecycle. The persistent /stream registers the session and stores
;; its push generator (server -> client collaboration channel). Cleanup never
;; relies on http-kit's channel close (it doesn't fire on idle disconnect):
;; - graceful unload -> navigator.sendBeacon('/session/end')
;; - crash/sleep      -> the TTL sweep
;; both call reap-session!, which close-sse!s the stored generator.

(defn- handle-stream
  "Persistent per-session SSE. Registers the session and stores its generator so
   edits elsewhere can be pushed here. Stays open — never close-sse! on open."
  [req]
  (with-stream-access req
    (fn [uid sid sheet-id token]
      (hk/->sse-response req
        {hk/on-open
         (fn [gen]
           (when (and sid (re-matches sid-re sid))
             ;; reconnect: keep the existing session's view/dims, just swap the
             ;; (dead) generator for the new one. fresh connect: register.
             (ensure-session! sid sheet-id uid token)
             (swap! sessions* update sid assoc :gen gen)
             ;; flush once so the client sees an established, open stream (an SSE
             ;; that sends nothing looks "finished" -> client reconnect storm).
             (try (d*/patch-signals! gen "{}") (catch Throwable _))
             ;; restore this session's own marker (reconnect) and show it the
             ;; cursors already present (and vice versa).
             (try (patch-inner! gen "#self" (self-html sid sheet-id)) (catch Throwable _))
             (broadcast-presence! sheet-id)))}))))

(defn- handle-session-end [req]
  (let [uid (auth/req->uid req)
        {:keys [sid]} (body-json req)]
    ;; only the session's own user may end it (sids are client-generated)
    (when (and sid uid (= uid (get-in @sessions* [sid :uid])))
      (reap-session! sid))
    {:status 204}))

;; --- sharing -------------------------------------------------------------

(defn- level-kw
  "Parse a level string from the share UI: \"read\"/\"read-write\" -> keyword,
   anything else (incl. \"none\") -> nil (= no access)."
  [s]
  (case (str s) "read" :read "read-write" :read-write nil))

(defn- level-label [lvl]
  (case lvl :read-write "can edit" :read "can view" "no access"))

(defn- share-html
  "The #sharebar fragment. Visitors see a read-only badge (their effective
   level, given the link `token` they arrived with). The owner gets a popover
   (toggled by $sharepanel) to set the capability-link level, copy/rotate the
   secret link, and grant/revoke direct per-user shares (name in dev, email in
   prod)."
  [uid sheet-id token]
  (let [owner     (owner-of sheet-id)
        owner?    (= uid owner)
        link      (db/link-grant sheet-id)             ; {:token :level} | nil
        lvl       (:level link)
        grants    (->> (db/sheet-grants sheet-id)
                       (filter #(= :user (:kind %)))
                       (sort-by :grantee))
        url       (str (auth/base-url) "/?t=" (:token link))   ; self-contained capability
        badge     (cond (= lvl :read-write) "🔗 link" (= lvl :read) "🔗 link" :else "🔒 private")
        add-ph    (if (auth/dev-auth?) "name to share with…" "email to share with…")
        row-style "display:flex;align-items:center;gap:.4rem;margin-bottom:.45rem;"]
    (str (h/html
          [:div {:id "sharebar" :style "display:flex;align-items:center;gap:.4rem;position:relative;"}
           (if-not owner?
             [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
              (str "shared by " (or (:name (auth/user-info owner)) owner)
                   " · " (level-label (db/access-level uid sheet-id token)))]
             (list
              [:button {:class "btn" :data-on:click "$sharepanel = !$sharepanel" :title "sharing"}
               badge]
              [:div {:data-show "$sharepanel"
                     :style (str "position:absolute;top:118%;left:0;z-index:30;width:24rem;"
                                 "background:var(--bg);border:1px solid var(--line);border-radius:6px;"
                                 "box-shadow:0 4px 16px rgba(0,0,0,.18);padding:.7rem;"
                                 "font:12px sans-serif;color:var(--fg);")}
               ;; capability link
               [:div {:style row-style}
                [:span {:style "flex:1;"} "Anyone with the link"]
                [:select {:class "tool"
                          :data-on:change "$shareact='link', $plevel=el.value, @post('/share')"}
                 [:option {:value "none"       :selected (nil? lvl)}          "no access"]
                 [:option {:value "read"       :selected (= lvl :read)}       "can view"]
                 [:option {:value "read-write" :selected (= lvl :read-write)} "can edit"]]]
               (when link
                 [:div {:style "display:flex;gap:.3rem;margin-bottom:.5rem;"}
                  [:input {:readonly true :value url :title "secret share link"
                           :style (str "flex:1;box-sizing:border-box;font:11px monospace;"
                                       "padding:4px 6px;border:1px solid var(--grid);"
                                       "border-radius:var(--radius);color:var(--muted);")}]
                  [:button {:class "btn" :title "make a new link (invalidates the old one)"
                            :data-on:click "$shareact='rotate', @post('/share')"} "↻"]])
               ;; per-user grants
               [:div {:style "border-top:1px solid var(--grid);padding-top:.5rem;"}
                (if (seq grants)
                  (for [{:keys [grantee level]} grants]
                    [:div {:style row-style}
                     [:span {:style "flex:1;"} (or (:name (auth/user-info grantee)) grantee)]
                     [:span {:style "color:var(--muted);"} (level-label level)]
                     [:button {:class "btn" :title "remove"
                               :data-on:click (str "$shareact='revoke', $grantee='" grantee "', @post('/share')")}
                      "✕"]])
                  [:div {:style "color:var(--muted);margin-bottom:.45rem;"} "not shared with anyone yet"])
                ;; add person
                [:div {:style "display:flex;gap:.3rem;margin-top:.4rem;"}
                 [:input {:class "tool" :data-bind:gtarget "" :placeholder add-ph :style "flex:1;"}]
                 [:select {:class "tool" :data-bind:glevel ""}
                  [:option {:value "read-write"} "edit"]
                  [:option {:value "read"} "view"]]
                 [:button {:class "btn" :data-on:click "$shareact='grant', @post('/share')"} "share"]]]]))]))))

(defn- evict-unauthorized!
  "Reap every NON-OWNER session on `sheet-id` whose access was just revoked
   (access-level now nil) — e.g. the link disabled or rotated (their stored
   token no longer matches), or a direct grant removed. Their held stream
   closes; the next request 403s. A mere downgrade (edit -> view) keeps access,
   so it is NOT evicted; the /cell write-guard handles it."
  [sheet-id]
  (let [owner (owner-of sheet-id)]
    (doseq [[sid s] @sessions*]
      (when (and (= sheet-id (:sheet s))
                 (not= owner (:uid s))
                 (nil? (db/access-level (:uid s) sheet-id (:token s))))
        (reap-session! sid)))))

(defn- handle-share
  "Owner-only sharing mutations, dispatched on the $shareact signal:
   - link:   set the capability-link level from $plevel (none/read/read-write)
   - rotate: mint a new link token (kills old links)
   - grant:  share to the $gtarget person at $glevel (resolve name/email -> uid)
   - revoke: drop the $grantee user grant
   Re-renders #sharebar and evicts anyone who just lost access."
  [req]
  (with-owner req
    (fn [uid sheet-id _rec {:keys [sid shareact plevel gtarget glevel grantee]} gen]
      (ensure-session! sid sheet-id uid)
      (let [err (case (str shareact)
                  "link"   (do (db/set-link-level! sheet-id (level-kw plevel)) nil)
                  "rotate" (do (db/rotate-link! sheet-id) nil)
                  "grant"  (if-let [g (auth/resolve-grantee gtarget)]
                             (if (= g uid)
                               "that's you — you already own this sheet"
                               (do (db/set-share! sheet-id g :user (or (level-kw glevel) :read-write)) nil))
                             "no signed-in user matches that (they must sign in first)")
                  "revoke" (do (when-not (str/blank? (str grantee))
                                 (db/remove-share! sheet-id grantee :user))
                               nil)
                  nil)]
        (save-rec! sheet-id)
        (evict-unauthorized! sheet-id)
        (d*/patch-elements! gen (share-html uid sheet-id nil))
        (signals! gen {:err (or err "") :gtarget ""})))))

;; --- auth routes (login page, OAuth redirects, logout) -------------------

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
(defn- url-decode [s] (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn- redirect [loc & [set-cookie]]
  (cond-> {:status 303 :headers {"Location" loc}}
    set-cookie (assoc-in [:headers "Set-Cookie"] set-cookie)))

(defn- login-page [err]
  (let [field (str "font:13px sans-serif;padding:6px 8px;border:1px solid #bbb;"
                   "border-radius:4px;")]
    (str
     "<!doctype html>"
     (h/html
      [:html
       [:head [:meta {:charset "utf-8"}] [:title "SaltRim — sign in"]]
       [:body {:style "font-family:sans-serif;max-width:24rem;margin:14vh auto;"}
        [:h1 {:style "font-weight:600;"} "SaltRim"]
        [:p {:style "color:#666;"} "Sign in to open your sheets."]
        (when err
          [:p {:style "color:#c0392b;font:13px sans-serif;"} (url-decode err)])
        [:div {:style "display:flex;flex-direction:column;gap:.6rem;"}
         (for [[k p] (auth/providers)]
           [:a {:href (str "/auth/" (name k))
                :style (str field "text-align:center;text-decoration:none;"
                            "background:#f6f6f6;color:#222;display:block;")}
            (str "Continue with " (:label p))])
         (when (auth/dev-auth?)
           [:form {:method "get" :action "/auth/dev"
                   :style "display:flex;gap:.4rem;"}
            [:input {:name "name" :placeholder "your name (dev login)"
                     :autofocus true :style (str field "flex:1;")}]
            [:button {:style field} "Sign in"]])]]]))))

(defn- denied-page [uid]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head [:meta {:charset "utf-8"}] [:title "SaltRim — no access"]]
     [:body {:style "font-family:sans-serif;max-width:24rem;margin:14vh auto;"}
      [:h1 {:style "font-weight:600;"} "No access"]
      [:p {:style "color:#666;"}
       "This sheet doesn't exist or isn't shared with you."]
      [:p [:a {:href "/"} "Back to your sheets"]]]])))

(defn- auth-routes
  "Identity endpoints; nil when the request is not one of them."
  [req]
  (let [{:keys [request-method uri]} req]
    (cond
      (and (= :get request-method) (= uri "/login"))
      (if (auth/req->uid req)
        (redirect "/")
        {:status 200 :headers {"Content-Type" "text/html"}
         :body (login-page (qparam req "err"))})

      (and (= :post request-method) (= uri "/logout"))
      (let [uid (auth/req->uid req)]
        (auth/revoke-token! (auth/req->token req))
        ;; reap the user's live sessions so their presence markers and edit
        ;; locks don't linger until the TTL sweep
        (doseq [[sid s] @sessions* :when (and uid (= uid (:uid s)))]
          (reap-session! sid))
        (redirect "/login" (auth/clear-cookie)))

      (and (= :get request-method) (= uri "/auth/dev"))
      (let [{:keys [token error]} (auth/dev-login! (some-> (qparam req "name") url-decode))]
        (if token
          (redirect "/" (auth/auth-cookie token))
          (redirect (str "/login?err=" (url-encode (or error "login failed"))))))

      (and (= :get request-method) (re-matches #"/auth/(github|google)" uri))
      (let [[_ p] (re-matches #"/auth/(github|google)" uri)]
        (if-let [u (auth/login-url (keyword p))]
          (redirect u)
          (redirect (str "/login?err=" (url-encode (str p " login is not configured"))))))

      (and (= :get request-method) (re-matches #"/auth/(github|google)/callback" uri))
      (let [[_ p] (re-matches #"/auth/(github|google)/callback" uri)
            {:keys [token error]} (auth/callback! (keyword p)
                                                  (some-> (qparam req "code") url-decode)
                                                  (qparam req "state"))]
        (if token
          (redirect "/" (auth/auth-cookie token))
          (redirect (str "/login?err=" (url-encode (or error "login failed")))))))))

(defn- handle-root [req]
  (let [uid (auth/req->uid req)]
    (if-not uid
      (redirect "/login")
      (let [token       (not-empty (qparam req "t"))
            ;; a capability link is self-contained: /?t=<token> resolves its own
            ;; sheet (no owner/name in the URL). A present-but-unresolved token
            ;; (bad/expired/rotated) is a dead end — deny, don't silently fall
            ;; through to the user's own default. Otherwise route by ?u=/?s=.
            [id sname]  (if token
                          (when-let [tid (db/sheet-by-link-token token)]
                            [tid (second (store/split-id tid))])
                          (let [sname (let [s (qparam req "s")] (if (store/valid-name? s) s "default"))
                                owner (let [o (qparam req "u")] (when (and o (re-matches auth/uid-re o)) o))]
                            [(store/storage-id (or owner uid) sname) sname]))
            rec         (when id (accessible-rec uid id token))]
        (if rec
          {:status 200 :headers {"Content-Type" "text/html"}
           :body (page (:sh rec) id sname uid token)}
          {:status 403 :headers {"Content-Type" "text/html"}
           :body (denied-page uid)})))))

(defn- app [req]
  (or
   (auth-routes req)
   (case [(:request-method req) (:uri req)]
    [:get "/"]            (handle-root req)
    [:get "/datastar.js"] (if-let [r (io/resource "public/datastar.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no datastar"})
    [:get "/app.js"]      (if-let [r (io/resource "public/app.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no app.js"})
    [:get "/stream"]         (handle-stream req)
    [:post "/session/end"]   (handle-session-end req)
    ;; dev-only diagnostics: exposed only under the name-only dev provider
    [:get "/debug"]       (if-not (auth/dev-auth?)
                            {:status 404 :body "not found"}
                            {:status 200 :headers {"Content-Type" "application/json"}
                             :body (json/write-value-as-string
                                    {:sessions (count @sessions*)
                                     :loaded-sheets (vec (keys @sheets*))
                                     :detail (mapv (fn [[sid s]]
                                                     {:sid (subs sid 0 (min 6 (count sid)))
                                                      :sheet (:sheet s)
                                                      :uid (:uid s)
                                                      :gen? (boolean (:gen s))
                                                      :view (:view s)})
                                                   @sessions*)})})
    [:post "/cell"]       (handle-cell req)
    [:post "/style"]      (handle-style req)
    [:post "/size"]       (handle-size req)
    [:post "/view"]       (handle-view req)
    [:post "/presence"]   (handle-presence req)
    [:post "/share"]      (handle-share req)
    {:status 404 :body "not found"})))

(defonce ^:private server* (atom nil))

(defn start! [& [port]]
  (when @server* (@server*))
  (start-sweeper!)                          ; reap idle/orphan sessions
  (reset! server* (http/run-server #'app {:port (or port 8080)}))
  (println "SaltRim on http://localhost:" (or port 8080))
  (println "auth:" (if-let [ps (seq (keys (auth/providers)))]
                     (str/join ", " (map name ps))
                     "none configured")
           (if (auth/dev-auth?) "(+ dev login)" "")))

(defn -main [& _] (start!) @(promise))
