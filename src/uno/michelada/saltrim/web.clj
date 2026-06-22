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
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.fmt :as fmt]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.util :as util :refer [timed]]
            [uno.michelada.saltrim.constants :refer [CW RH GUT HDR
                                                     MAX-COLS MAX-ROWS
                                                     WIN-COLS WIN-ROWS
                                                     MIN-COLS MIN-ROWS
                                                     BUF-COLS BUF-ROWS
                                                     OVER BAR]]
            [mount.core :refer [defstate]]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [starfederation.datastar.clojure.adapter.common :as ac])
  (:gen-class))

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

(defn- col-w [sh ci] (or (sheet/col-width sh ci) (sheet/default-col-w sh)))
(defn- row-h [sh ri] (or (sheet/row-height sh ri) (sheet/default-row-h sh)))

(defn- axis-off
  "Absolute start px of `i` along an axis whose default size is `base` and whose
   sparse overrides are `om` (index -> size)."
  [om base i]
  (reduce-kv (fn [acc k v] (cond-> acc (< (long k) (long i)) (+ (- (long v) base))))
             (* (long i) base) om))

(defn- axis-x [sh ci] (axis-off (sheet/col-widths sh) (sheet/default-col-w sh) ci))
(defn- axis-y [sh ri] (axis-off (sheet/row-heights sh) (sheet/default-row-h sh) ri))

;; --- state --------------------------------------------------------------

;; room = [storage-id branch]. sheets* : room -> {:sh sheet :owner uid|nil}
;; (lazy: load / create). A branch is its own collaborative working copy, so the
;; loaded engine + every collaboration broadcast is keyed by the (sheet,branch)
;; pair, not the bare id. The default branch is db/MAIN.
(defonce ^:private sheets* (atom {}))

(defn- sheet-rec
  "The loaded record for a (storage id, branch); loads from the db lazily, else
   creates a fresh private sheet owned by `owner`. Registers the SHEET (not the
   branch) in the DB — branch existence is handled by db/branch-exists?/fork.
   On a sheet's first registration the legacy :public flag becomes a capability
   link; any pre-link :everyone grant is upgraded too (one-shot)."
  [id branch owner]
  (let [room [id branch]]
    (or (@sheets* room)
        (let [loaded   (store/load-record id branch)
              rec      (or loaded {:sh (sheet/create-sheet) :owner owner})
              [o n]    (store/split-id id)
              owner    (or (:owner rec) o)
              new?     (db/ensure-sheet! id owner n)]
          (when (and new? (:public rec)) (db/set-link-level! id :read-write))
          (db/migrate-everyone->link! id)       ; upgrade legacy public grants
          (let [rec (-> rec (dissoc :public) (assoc :owner owner))]
            (swap! sheets* assoc room rec)
            rec)))))

(defn- save-rec!
  "Persist a loaded room's content to the db (branch-scoped), authored by
   `author` uid (the acting user — recorded per changed cell for per-user undo).
   `author` is nil for non-edit autosaves (e.g. the save on session unload).
   `room` = [storage-id branch]."
  ([room] (save-rec! room nil))
  ([[id branch :as room] author]
   (when-let [{:keys [sh]} (@sheets* room)]
     (store/save! id sh {:author author :branch branch}))))

(defn- accessible-rec
  "The record for (storage id, branch) IF `uid` (carrying optional link `token`)
   may access it: owners reach (and auto-create) their own sheets; anyone
   signed-in reaches a foreign sheet they were granted, or whose link token they
   hold. A non-existent branch (other than MAIN) is denied so a typo'd `&b=`
   never materialises an empty branch (creation is explicit via fork). The rec
   carries `:room`/`:branch`. Nil = denied/invalid."
  [uid id branch token]
  (when (and uid (store/valid-id? id))
    (let [[owner _] (store/split-id id)]
      (cond
        (nil? owner)                       nil  ; legacy un-namespaced ids: not served
        (not (db/branch-exists? id branch)) nil ; unknown branch (MAIN always exists)
        (= owner uid) (sheet-rec id branch uid)
        :else (when (or (@sheets* [id branch]) (store/exists? id))
                (let [rec (sheet-rec id branch owner)]
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

(defn- sessions-on
  "How many live sessions are in `room` (= [sheet-id branch])."
  [room]
  (count (filter #(= room (:room %)) (vals @sessions*))))

(defn- unload-sheet!
  "Save then release the engine for a room whose last session just left."
  [room]
  (when (and (zero? (sessions-on room)) (@sheets* room))
    (let [{:keys [sh]} (@sheets* room)]
      (save-rec! room)                   ; autosave on unload — no acting user
      (sheet/close! sh)
      (swap! sheets* dissoc room))))

(defn- register-session! [sid sheet-id branch uid token]
  (let [[owner _] (store/split-id sheet-id)]
    (sheet-rec sheet-id branch (or owner uid)))  ; acquire (load) the branch
  (swap! sessions* assoc sid {:sheet sheet-id :branch branch :room [sheet-id branch]
                              :view {:r0 0 :c0 0}
                              :dims nil :last-seen (now)
                              :uid uid :token token
                              :uname (or (:name (auth/user-info uid)) uid)
                              :color (color-for sid) :cursor nil :editing nil
                              :editdef nil}))   ; chunk id this session is editing (def lock)

(defn- touch! [sid] (when (@sessions* sid) (swap! sessions* assoc-in [sid :last-seen] (now))))

(defn- ensure-session!
  "Lazily (re)register a session for an active request, then stamp it alive. A
   client whose session was swept (crash/sleep TTL) transparently comes back.
   A sid registered under a DIFFERENT user OR a different room (the client
   navigated to another sheet/branch reusing the sid) is re-registered (a sid is
   client-generated — never trust it to carry identity). The link `token` (if
   any) is remembered so a later link rotate/downgrade can re-check access."
  [sid sheet-id branch uid & [token]]
  (when (and sid (re-matches sid-re (str sid)))
    (let [s    (@sessions* sid)
          room [sheet-id branch]]
      (if (or (nil? s) (not= uid (:uid s)) (not= room (:room s)))
        (register-session! sid sheet-id branch uid token)
        (when token (swap! sessions* assoc-in [sid :token] token))))
    (touch! sid)))

(declare close-gen! broadcast-presence! broadcast-deflib!)
(defn- reap-session!
  "Drop a session: close its push stream and unload the room if it was last."
  [sid]
  (when-let [s (@sessions* sid)]
    (close-gen! s)
    (swap! sessions* dissoc sid)
    (unload-sheet! (:room s))
    ;; the departed cursor must disappear from peers still in the room; and any
    ;; definition lock it held must release for everyone else
    (when (pos? (sessions-on (:room s)))
      (broadcast-presence! (:room s))
      (broadcast-deflib! (:room s)))))

(defn- sweep! []
  (let [cutoff (- (now) SESSION-TTL-MS)]
    (doseq [[sid s] @sessions*]
      (when (< (long (:last-seen s 0)) cutoff)
        (reap-session! sid)))))

;; The session sweeper IS its mount state: the state value is the scheduled
;; executor pool; :stop shuts it down. (declared lower, with `server`, once
;; sweep! is defined.)

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
        srcs (sheet/style-srcs sh a)]      ; {prop raw} -> echoed into the style bar
    ;; width/height always emitted (override OR the sheet default) so geometry is
    ;; fully data-driven: a default-size change re-renders the window and applies
    ;; live for everyone — no stale CSS, no reload.
    [:div {:id (cell-id a) :class "cell" :data-raw raw
           :data-sty (when (seq srcs) (json/write-value-as-string srcs))
           :style (str (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx"
                               (- (axis-x sh ci) (axis-x sh cbase))
                               (- (axis-y sh ri) (axis-y sh rbase))
                               (dec (col-w sh ci)) (dec (row-h sh ri)))
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
                        ;; per-sheet default axis sizes (client geometry base)
                        :data-dcw (sheet/default-col-w sh) :data-drh (sheet/default-row-h sh)
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
            ;; Selection (single + range + multi-range), double-click-to-edit and
            ;; keyboard all live in app.cljs now — it owns the selection state that
            ;; the #selrange overlay + clipboard read. A plain click still ends up
            ;; setting $sel + mirroring the cell into the bars + posting presence,
            ;; via the sr-select bridge; Shift extends a range, Ctrl/⌘ adds one.
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
       ;; multi-cell selection highlight — drawn CLIENT-side by app.cljs from its
       ;; selection state (ranges + multi-range), translated with #cells. Local
       ;; only: peers see your active cell via presence, not the whole marquee.
       [:div {:id "selrange" :style (str "position:absolute;left:0;top:0;z-index:1;"
                                         "pointer-events:none;will-change:transform;")}]
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
       ;; the single floating editor, translated with #cells. app.cljs positions
       ;; it over the active cell and moves focus in; everything else is
       ;; declarative: data-bind:v shares $v with the formula bar, data-show
       ;; reveals it on $edit, Enter/blur commit (@post '/cell' + drop the edit
       ;; lock), Esc cancels. preventDefault is INLINE for Enter/Esc only — a
       ;; `__prevent` MODIFIER fires on every keydown and would block all typing.
       ;; `__stop` keeps these keys from the document-level nav handler.
       [:div {:id "editlayer" :style "position:absolute;left:0;top:0;will-change:transform;"}
        ;; Visibility is $celledit (set ONLY by start-edit!, which also positions +
        ;; sizes it over the cell) — NOT $edit, which the formula bar also sets for
        ;; presence/peer-lock. Sharing $edit would pop this box, unpositioned and
        ;; at its default size, on every formula-bar focus.
        [:input {:id "editor" :data-bind:v "" :data-show "$celledit"
                 :data-on:keydown__stop
                 (str "evt.key==='Enter' ? (evt.preventDefault(),$cell=$sel,@post('/cell'),$edit=false,$celledit=false,@post('/presence'))"
                      " : evt.key==='Escape' ? (evt.preventDefault(),$edit=false,$celledit=false,@post('/presence')) : null")
                 :data-on:blur "$celledit && ($cell=$sel,@post('/cell'),$edit=false,$celledit=false,@post('/presence'))"
                 :style "display:none;"}]]]
      ;; custom scrollbars
      [:div {:id "vbar" :style (format (str "position:absolute;right:0;top:%dpx;bottom:%dpx;width:%dpx;"
                                            "background:#f0f0f0;z-index:5;") HDR BAR BAR)}
       [:div {:id "vthumb"
              :style "position:absolute;left:1px;right:1px;top:0;height:30px;background:#bbb;border-radius:6px;"}]]
      [:div {:id "hbar" :style (format (str "position:absolute;left:%dpx;bottom:0;right:%dpx;height:%dpx;"
                                            "background:#f0f0f0;z-index:5;") GUT BAR BAR)}
       [:div {:id "hthumb"
              :style "position:absolute;top:1px;bottom:1px;left:0;width:30px;background:#bbb;border-radius:6px;"}]]
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
            [:p {:style p} "Built-in functions: math (" [:span {:style kbd} "sum"] ", "
             [:span {:style kbd} "round"] ", " [:span {:style kbd} "sqrt"] "…), stats ("
             [:span {:style kbd} "mean"] ", " [:span {:style kbd} "median"] ", "
             [:span {:style kbd} "stdev"] "), text (" [:span {:style kbd} "upper"] ", "
             [:span {:style kbd} "join"] ", " [:span {:style kbd} "split"] "…), date ("
             [:span {:style kbd} "today"] ", " [:span {:style kbd} "year"] ", "
             [:span {:style kbd} "days-between"] ")."]

            [:div {:style h3} "Reusable functions (ƒ)"]
            [:p {:style p} "The " [:span {:style kbd} "ƒ"] " button (top bar) opens this sheet's "
             "definitions library: write your own functions/constants as separate entries and call "
             "them from any cell. e.g. add "
             [:span {:style kbd} "(defn margin [rev cost] (/ (- rev cost) rev))"] " then use "
             [:span {:style kbd} "=(margin #cell A1 #cell B1)"] ". Each entry collapses to name "
             "badges; Edit expands it and " [:span {:style kbd} "⤢"] " opens a big editor (also next "
             "to the formula and style bars). While you edit one it's locked for other "
             "collaborators; saving recompiles every cell."]

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
             "number) to resize it. Sizes are saved with the sheet. Dragging "
             [:b "snaps"] " to multiples of the sheet default (1×, 2×, 3×…); hold "
             [:span {:style kbd} "Alt"] " while dragging to size freely."]
            [:p {:style p} "Owners can set the sheet-wide default column width / row height in "
             [:span {:style kbd} "⚙"] " (Sheet properties, top bar)."]

            [:div {:style h3} "Navigation"]
            [:p {:style p} "Click to select · double-click or " [:span {:style kbd} "Enter"]
             " to edit · arrows / " [:span {:style kbd} "Tab"] " to move · the address box jumps to a cell."]

            [:div {:style h3} "Selecting ranges"]
            [:p {:style p} [:span {:style kbd} "Shift"] "+click or " [:span {:style kbd} "Shift"]
             "+arrows extends a range · " [:span {:style kbd} "Ctrl/⌘"] "+click adds another range · "
             [:span {:style kbd} "Delete"] " clears the selected cells (undoable)."]

            [:div {:style h3} "Copy / paste"]
            [:p {:style p} [:span {:style kbd} "Ctrl/⌘+C"] " copy · " [:span {:style kbd} "Ctrl/⌘+X"]
             " cut · " [:span {:style kbd} "Ctrl/⌘+V"] " paste at the selected cell. Pasted formulas "
             "shift their references relative to the move (copy " [:span {:style kbd} "=(+ #cell A1 1)"]
             " down a row pastes " [:span {:style kbd} "=(+ #cell A2 1)"] ")."]

            [:div {:style h3} "Undo / redo"]
            [:p {:style p} [:span {:style kbd} "Ctrl/⌘+Z"] " undoes your last edit · "
             [:span {:style kbd} "Ctrl/⌘+Shift+Z"] " (or " [:span {:style kbd} "Ctrl+Y"] ") redoes. "
             "Undo only affects your own edits — a cell a collaborator changed after you is left alone."]

            [:div {:style h3} "Branches"]
            [:p {:style p} "A branch is a parallel version of the sheet you can edit without touching the "
             "others — like git for spreadsheets. The " [:span {:style kbd} "🌿"] " picker (top bar) "
             "switches branches; people on different branches don't see each other's cells. The owner's "
             [:span {:style kbd} "⑂"] " button forks the current branch into a new one or deletes a "
             "non-main branch. (Merging branches is coming next.)"]

            [:div {:style h3} "Sharing"]
            [:p {:style p} "The link / lock button (top bar, owner only) shares the sheet by capability "
             "link or with specific people, at view or edit level."]]]))))

(declare deflib-html bigedit-html)

(def ^:private stdlib-reference
  "Read-only reference of the built-in functions (always available, can't be
   edited), grouped by category."
  [["math"  "sum product abs ceil floor round sqrt pow exp ln log10 sign"]
   ["stats" "mean avg median variance stdev"]
   ["text"  "upper lower trim join split str-replace starts-with? ends-with? includes? blank?"]
   ["date"  "today year month day days-between  (ISO yyyy-MM-dd strings)"]])

(defn- defs-html
  "The definitions LIBRARY modal, toggled by $defspanel. The editable library
   (#deflib) is a server-rendered fragment of chunks — each edited and locked
   independently for collaboration, all merged into the sheet's program. Below it
   is the read-only built-in stdlib reference. Pure server HTML + Datastar."
  [storage-id]
  (let [p   "margin:.2rem 0;font:13px sans-serif;color:var(--fg);"
        kbd "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;"]
    (str (h/html
          [:div {:id "defswrap" :data-show "$defspanel"
                 :data-on:click "$defspanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:44rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Definitions library"]
             [:button {:class "btn" :data-on:click "$defspanel=false" :title "close"} "✕"]]
            [:p {:style p} "Functions and constants reusable by every formula in this sheet, kept as "
             "separate entries. Editing one locks it for other collaborators; they all merge (in order) "
             "into the sheet's program. Same sandbox as formulas — pure, no host interop."]
            [:p {:style p} "e.g. " [:span {:style kbd} "(defn margin [rev cost] (/ (- rev cost) rev))"]
             " → in a cell " [:span {:style kbd} "=(margin #cell A1 #cell B1)"]]
            ;; dynamic, per-session library fragment (pushed on changes)
            [:div {:id "deflib"} (h/raw (deflib-html nil storage-id))]
            ;; read-only built-ins
            [:details {:style "margin-top:.9rem;"}
             [:summary {:style "font:600 13px sans-serif;cursor:pointer;color:var(--muted);"}
              "Built-in functions (read-only)"]
             (for [[cat names] stdlib-reference]
               [:p {:style (str p "margin-left:.4rem;")}
                [:b cat] ": " [:span {:style kbd} names]])]]]))))

(defn- props-html
  "Owner-only Sheet properties modal, toggled by $propspanel. Today: the sheet's
   DEFAULT column width / row height (px) — the size of any unsized column/row.
   Built as a labelled grid so more sheet-wide settings slot in as new rows.
   $pcw/$prh are server-seeded with the current values; Apply posts /props."
  []
  (let [p     "margin:.2rem 0 .7rem;font:13px sans-serif;color:var(--muted);"
        lbl   "font:13px sans-serif;color:var(--fg);align-self:center;"
        num   "font:13px monospace;padding:5px 6px;border:1px solid var(--line);border-radius:var(--radius);background:var(--panel);width:6rem;"]
    (str (h/html
          [:div {:id "propswrap" :data-show "$propspanel"
                 :data-on:click "$propspanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:30rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Sheet properties"]
             [:button {:class "btn" :data-on:click "$propspanel=false" :title "close"} "✕"]]
            [:p {:style p} "Sheet-wide defaults. Individual columns/rows you've dragged keep their own size."]
            [:div {:style "display:grid;grid-template-columns:auto 1fr;gap:.55rem .8rem;align-items:center;"}
             [:label {:style lbl :for "pcw"} "Default column width"]
             [:input {:id "pcw" :type "number" :min "24" :max "2000" :step "1"
                      :data-bind:pcw "" :style num
                      :data-on:keydown "evt.key==='Enter' && @post('/props')"}]
             [:label {:style lbl :for "prh"} "Default row height"]
             [:input {:id "prh" :type "number" :min "16" :max "1000" :step "1"
                      :data-bind:prh "" :style num
                      :data-on:keydown "evt.key==='Enter' && @post('/props')"}]]
            [:div {:style "margin-top:1rem;text-align:right;"}
             [:button {:class "btn primary" :data-on:click "@post('/props'), $propspanel=false"} "Apply"]]]]))))

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

(defn- branch-bar
  "Branch picker + owner-only fork/delete (a 🌿 dropdown + ⑂ modal). A branch is
   a parallel copy of the sheet you edit independently. Switching navigates (full
   reload, like the sheet picker), preserving the sheet URL. Fork/delete post to
   /branch; on success the server sets $goto and the page navigates."
  [uid storage-id sname branch link-token owner?]
  (let [names (db/branch-names storage-id)
        ;; the sheet's own URL (owners reach theirs by ?s=; a link visitor keeps
        ;; ?t=; a shared viewer keeps ?u=&s=). The picker appends &b=.
        base  (cond link-token (str "/?t=" link-token)
                    owner?      (str "/?s=" sname)
                    :else       (str "/?u=" (first (store/split-id storage-id)) "&s=" sname))
        overlay (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                     "display:flex;align-items:flex-start;justify-content:center;padding:12vh 1rem;")
        modal   (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                     "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:24rem;width:100%;padding:1rem 1.1rem;"
                     "font:13px sans-serif;color:var(--fg);")
        field   (str "font:13px sans-serif;padding:6px 8px;border:1px solid var(--line);"
                     "border-radius:var(--radius);box-sizing:border-box;")]
    (list
     [:select {:id "branchpicker" :class "tool" :title "branch — a parallel version of this sheet"
               ;; navigate to the picked branch (main → no &b=, keeps URLs clean)
               :data-on:change (str "el.value && (location.href='" base "'"
                                    " + (el.value==='" db/MAIN "' ? '' : '&b='+el.value))")
               :style "max-width:9rem;"}
      (for [n names]
        [:option {:value n :selected (= n branch)} (str "🌿 " n)])]
     (when owner?
       (list
        [:button {:class "btn" :data-on:click "$branchpanel=true"
                  :title "branches: fork / delete"} "⑂"]
        [:div {:data-show "$branchpanel" :data-on:click "$branchpanel=false" :style overlay}
         [:div {:data-on:click "evt.stopPropagation()" :style modal}
          [:div {:style "display:flex;align-items:center;margin-bottom:.5rem;"}
           [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} "Branches"]
           [:button {:class "btn" :data-on:click "$branchpanel=false" :title "close"} "✕"]]
          [:p {:style "color:var(--muted);margin:.2rem 0 .7rem;"}
           "On branch " [:strong (str "🌿 " branch)] ". A fork copies it into a new "
           "parallel branch you can edit without touching this one."]
          [:label {:style "display:block;font-size:12px;color:var(--muted);margin-bottom:.2rem;"}
           "New branch name"]
          [:div {:style "display:flex;gap:.4rem;"}
           [:input {:data-bind:bname "" :placeholder "feature-x" :autocomplete "off"
                    :data-on:keydown "evt.key==='Enter' && ($branchact='fork', @post('/branch'))"
                    :style (str field "flex:1;")}]
           [:button {:class "btn primary" :data-on:click "$branchact='fork', @post('/branch')"}
            (str "Fork from " branch)]]
          (when (not= branch db/MAIN)
            [:div {:style "border-top:1px solid var(--grid);margin-top:.8rem;padding-top:.7rem;"}
             [:button {:class "btn"
                       :data-on:click (str "confirm('Delete branch \\u201c" branch "\\u201d? "
                                           "This removes its cells and cannot be undone.') && "
                                           "($branchact='delete', @post('/branch'))")
                       :style "color:var(--danger);border-color:var(--danger);"}
              (str "Delete “" branch "”")]])]])))))

(defn- page [sh storage-id sname branch uid link-token]
  ;; one session id seeds BOTH $sid (sent on /stream, registers the session) and
  ;; #ctl's data-sid (read by the unload beacon) — they must be the same value.
  (let [sid    (str (random-uuid))
        owner? (= uid (first (store/split-id storage-id)))]
   (str
    "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "SaltRim"]
      [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
      ;; Cells are display <div class="cell"> (not inputs); the floating editor
      ;; is the single #editor input. Both are absolutely positioned (cells by
      ;; their inline left/top, #editor by app.js) — without this the left/top
      ;; are ignored and everything stacks in flow at the top-left.
      [:style (h/raw
               (str
                ;; design tokens — centralize colors/geometry so a merge can't
                ;; silently drift them and so inline styles can share them.
                ;; palette tuned to the SaltRim logo: blue grid/parens, lime
                ;; slice, slate wordmark — softer neutrals than the old grey/blue.
                ":root{--bg:#fefefe;--panel:#f4f6f8;--line:#c7ccd1;--grid:#e2e6ea;"
                "--fg:#3a4149;--muted:#7a828b;--accent:#2f8fd8;--accent2:#9ec9ee;"
                "--accent-bg:#eaf4fc;--lime:#7cc62e;--danger:#c0392b;--radius:4px;}"
                ;; toolbar: two rows. row 1 = picker/new/share/identity,
                ;; row 2 = cell-ref + formula bar. .tool/.btn unify the inputs
                ;; and buttons that used to repeat the same inline style string.
                ".toolrow{display:flex;align-items:center;gap:.5rem;margin-bottom:.4rem;}"
                ".tool{font:13px sans-serif;padding:5px 6px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);}"
                ".tool.mono{font-family:monospace;}"
                ".btn{font:12px sans-serif;padding:5px 8px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);cursor:pointer;}"
                ".btn:hover{border-color:var(--accent);}"
                ;; toggled-on section chip (e.g. 🎨 format) — tinted, not loud
                ".btn.active{background:var(--accent-bg);border-color:var(--accent);color:var(--accent);}"
                ;; primary action button (Save / Apply) — the brand accent
                ".btn.primary{background:var(--accent);color:#fff;border-color:var(--accent);}"
                ".btn.primary:hover{filter:brightness(1.06);}"
                ;; definition name badges (collapsed library cards)
                ".badge{display:inline-block;font:600 11px/1.5 monospace;color:var(--accent);"
                "background:var(--accent-bg);border:1px solid var(--accent2);"
                "border-radius:10px;padding:0 .5rem;}"
                ".spacer{flex:1;}"
                ;; resize grips: a thin hit-zone on a header's trailing edge that
                ;; /app.js drags. The #rzguide is the single moving guide line.
                ".colgrip{position:absolute;top:0;right:-3px;width:6px;height:100%;"
                "cursor:col-resize;z-index:5;}"
                ".rowgrip{position:absolute;left:0;bottom:-3px;height:6px;width:100%;"
                "cursor:row-resize;z-index:5;}"
                "#rzguide{position:absolute;display:none;background:var(--accent);"
                "z-index:7;pointer-events:none;}"
                ;; default cell/editor box = this SHEET's default axis sizes (a
                ;; sized column/row overrides inline per cell). Server-rendered so
                ;; changing the sheet default reflows the grid on reload.
                (let [dw (dec (sheet/default-col-w sh)) dh (dec (sheet/default-row-h sh))]
                  (format (str ".cell{position:absolute;width:%dpx;height:%dpx;"
                               "box-sizing:border-box;border:1px solid var(--grid);"
                               "padding:2px 4px;font:13px monospace;overflow:hidden;"
                               "white-space:nowrap;background:var(--bg);}"
                               "#editor{position:absolute;width:%dpx;height:%dpx;"
                               "box-sizing:border-box;border:1px solid var(--accent);"
                               "padding:2px 4px;font:13px monospace;outline:none;z-index:6;}")
                          dw dh dw dh))
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
      [:script {:type "module" :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js" #_"/datastar.js"}]
      [:script {:src "/app.js"}]]
     [:body {:data-signals:cell "''"
             :data-signals:v "''"
             :data-signals:err "''"
             :data-signals:sel "''"
             :data-signals:edit "false"
             ;; floating in-cell editor visibility (distinct from $edit: only the
             ;; cell dblclick/Enter path shows it, after start-edit! positions it)
             :data-signals:celledit "false"
             :data-signals:r0 "0"
             :data-signals:c0 "0"
             :data-signals:sheet (format "'%s'" storage-id)
             ;; the working branch (rides in every POST so the server routes to
             ;; the right (sheet,branch) room); seeded from the resolved &b=.
             :data-signals:branch (format "'%s'" branch)
             :data-signals:bname "''"            ; new-branch name (fork modal)
             :data-signals:branchact "''"        ; fork | delete
             :data-signals:branchpanel "false"   ; 🌿 modal open?
             ;; server sets $goto on a fork/delete to navigate (full reload) to
             ;; the resulting branch — the #goto effect element below watches it.
             :data-signals:goto "''"
             :data-signals:sid (format "'%s'" sid)
             :data-signals:link (format "'%s'" (or link-token ""))
             :data-signals:sharepanel "false"
             :data-signals:shareact "''"
             :data-signals:plevel "''"
             :data-signals:gtarget "''"
             :data-signals:glevel "'read-write'"
             :data-signals:grantee "''"
             :data-signals:styleprop "'bg'"
             :data-signals:stylesrc "''"
             ;; collapsible toolbar sections (formula bar stays; others toggle)
             :data-signals:fmtbar "false"
             ;; current multi-selection as space-separated "TL:BR" ranges (set by
             ;; app.cljs before a selection-wide action, e.g. Delete -> /clear)
             :data-signals:selcells "''"
             :data-signals:rzcmd "''"
             ;; definitions library (ƒ modal)
             :data-signals:defspanel "false"
             :data-signals:defid "''"
             :data-signals:defsrc "''"
             ;; shared big-editor modal (formula bar / style bar / definitions)
             :data-signals:bigedit "false"
             :data-signals:bigwhat "''"
             :data-signals:big "''"
             :data-signals:help "false"
             ;; sheet properties (⚙ modal, owner-only) — seeded with current defaults
             :data-signals:propspanel "false"
             :data-signals:pcw (str (sheet/default-col-w sh))
             :data-signals:prh (str (sheet/default-row-h sh))
             :style "font-family:sans-serif;margin:0;padding:.6rem;min-height:100vh;background:var(--bg);color:var(--fg);"}
      [:div {:id "toast" :data-show "$err != ''" :data-text "$err"
             :data-on:click "$err=''"
             :style (str "position:fixed;top:1rem;right:1rem;max-width:26rem;background:#c0392b;"
                         "color:#fff;padding:.6rem .9rem;border-radius:6px;font:13px sans-serif;"
                         "cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:20;")}]
      (h/raw (help-html))
      (h/raw (defs-html storage-id))
      (h/raw (bigedit-html))
      (when owner? (h/raw (props-html)))
      ;; ── toolbar row 1: sheet management + sharing + identity ───────────
      [:div {:class "toolrow"}
       (sheet-picker uid storage-id sname)
       [:input {:id "sheetbox" :class "tool" :placeholder "new sheet…"
                :data-on:keydown "evt.key==='Enter' && el.value && (location.href='/?s='+el.value)"
                :title "type a name + Enter to create/open one of your sheets"
                :style "width:6rem;"}]
       ;; branch picker + owner-only fork/delete
       (branch-bar uid storage-id sname branch link-token owner?)
       ;; navigate (full reload) when the server sets $goto (fork/delete result).
       ;; no signal refs besides $goto ⇒ runs only when $goto changes (not on load
       ;; while it is "").
       [:div {:id "goto" :data-effect "$goto && window.location.assign($goto)" :style "display:none;"}]
       ;; sharing toggle / badge (patched back by POST /share)
       (h/raw (share-html uid storage-id link-token))
       [:span {:class "spacer"}]
       ;; section toggle: show/hide the format row (the pattern future control
       ;; rows — clipboard, data — reuse, so the bar never just grows)
       [:button {:class "btn" :data-class:active "$fmtbar"
                 :data-on:click "$fmtbar = !$fmtbar" :title "format / style controls"} "🎨"]
       [:button {:class "btn" :data-on:click "$defspanel=true" :title "sheet definitions (reusable functions)"} "ƒ"]
       (when owner?
         [:button {:class "btn" :data-on:click "$propspanel=true" :title "sheet properties"} "⚙"])
       [:button {:class "btn" :data-on:click "$help=true" :title "help / quick guide"} "?"]
       ;; who am I + sign out
       [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
        (or (:name (auth/user-info uid)) uid)]
       [:form {:method "post" :action "/logout" :style "margin:0;"}
        [:button {:class "btn"} "sign out"]]]
      ;; ── toolbar row 2: cell reference + formula bar ────────────────────
      [:div {:class "toolrow"}
       ;; address box: $sel via data-bind; Enter jumps (app.cljs scrolls there +
       ;; selects). The keydown listener is attached in app.cljs (a scroll action).
       [:input {:id "addrbox" :class "tool mono" :data-bind:sel "" :placeholder "A1"
                :style "width:5rem;text-align:center;"}]
       ;; editing via the formula bar still drives presence on the SELECTED cell
       ;; (so it shows the marching-ants self marker and locks it for peers).
       ;; formula bar shares $v with the floating #editor, so the two stay live-
       ;; synced: typing in either updates $v and the other reflects it.
       [:input {:id "fbar" :class "tool mono" :data-bind:v "" :placeholder "value or =formula"
                :data-on:focus "$edit=true, @post('/presence')"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/cell'))"
                :data-on:blur "$cell=$sel, @post('/cell'), $edit=false, @post('/presence')"
                :style "flex:1;"}]
       [:button {:class "btn" :title "big editor" :data-on:click "$big=$v, $bigwhat='v', $bigedit=true"} "⤢"]]
      ;; ── toolbar row 3: style of the selected cell (collapsible) ────────
      ;; prop dropdown + a literal-or-=formula source, applied to $sel on Enter
      ;; (like the formula bar — no separate button). $val is the cell's own
      ;; value, e.g. =(if (> $val 100) "tomato" "white"). Hidden until the 🎨
      ;; toggle ($fmtbar) reveals it — keeps the default bar lean.
      [:div {:class "toolrow" :data-show "$fmtbar"}
       [:select {:id "stylepropbox" :class "tool" :data-bind:styleprop ""
                 :data-on:change
                 (str "const c=document.getElementById('c_'+$sel);"
                      "c && ($v=c.dataset.raw||'',"
                      "$stylesrc=c.dataset.sty?(JSON.parse(c.dataset.sty)[$styleprop]||''):'')")
                 :title "style / format property of the selected cell"}
        (for [p (concat (keys style-css) value-props)] [:option {:value (name p)} (name p)])]
       [:input {:id "stylesrcbox" :class "tool mono" :data-bind:stylesrc ""
                :placeholder "color / mask / =formula (use $val) — Enter to apply"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/style'))"
                :style "flex:1;"}]
       [:button {:class "btn" :title "big editor" :data-on:click "$big=$stylesrc, $bigwhat='style', $bigedit=true"} "⤢"]]
      ;; logical-scroll viewport (custom wheel + scrollbars in /app.js)
      (grid-layers sh {:r0 0 :c0 0})

      ;; ── client ⇆ server bridge (no hidden trigger buttons) ─────────────
      ;; app.cljs does the imperative work (scroll / edit / resize / keyboard)
      ;; and, when the server must hear about it, dispatches a `sr-*` CustomEvent
      ;; on window; these declarative handlers turn each into the Datastar action,
      ;; pulling the carried data off evt.detail. The persistent collaboration
      ;; stream lives on its OWN element (#streamer) so app.cljs can pick its
      ;; datastar-fetch lifecycle apart from the @posts for reconnect.
      [:div {:id "streamer" :data-on:sr-open__window "@get('/stream')"
             :style "display:none;"} ""]
      [:div {:id "ctl" :data-sid sid :style "display:none;"
             :data-on:sr-view__window "$r0=evt.detail.r0, $c0=evt.detail.c0, @post('/view')"
             :data-on:sr-size__window "$rzcmd=evt.detail.cmd, @post('/size')"
             ;; per-user undo / redo (Ctrl+Z / Ctrl+Shift+Z|Ctrl+Y from app.cljs)
             :data-on:sr-undo__window "@post('/undo')"
             :data-on:sr-redo__window "@post('/redo')"
             ;; clear the current selection (Delete/Backspace from app.cljs)
             :data-on:sr-clear__window "$selcells=evt.detail.ranges, @post('/clear')"
             ;; clipboard (Ctrl/⌘ C / X / V from app.cljs) — selection rides in $selcells
             :data-on:sr-copy__window  "$selcells=evt.detail.ranges, @post('/copy')"
             :data-on:sr-cut__window   "$selcells=evt.detail.ranges, @post('/cut')"
             :data-on:sr-paste__window "$selcells=evt.detail.ranges, @post('/paste')"
             ;; commit an in-progress edit (app.cljs fires this before a resize
             ;; drag, whose preventDefault would otherwise swallow the blur)
             :data-on:sr-commit__window "$edit && ($cell=$sel, @post('/cell'), $edit=false, $celledit=false, @post('/presence'))"
             ;; select: move cursor + mirror the cell's value/style into the bars
             :data-on:sr-select__window
             (str "const c=document.getElementById('c_'+evt.detail.addr);"
                  "$sel=evt.detail.addr; $v=c?(c.dataset.raw||''):'';"
                  "$stylesrc=c&&c.dataset.sty?(JSON.parse(c.dataset.sty)[$styleprop]||''):'';"
                  "$edit=false; $celledit=false; @post('/presence')")
             ;; start editing in-cell: load the cell's source into $v, take the edit
             ;; lock, and reveal the floating editor (app.cljs already positioned it)
             :data-on:sr-edit__window
             (str "const c=document.getElementById('c_'+evt.detail.addr);"
                  "$sel=evt.detail.addr; $v=c?(c.dataset.raw||''):'';"
                  "$edit=true; $celledit=true; @post('/presence')")} ""]]]))))

;; --- SSE (official Datastar SDK) ----------------------------------------

;; Optional SSE tracing: set SALTRIM_SSE_DEBUG=1 to log every server-sent event
;; (type + a snippet of its data lines) to the console. Implemented as a Datastar
;; write profile — the SDK's designed seam for this — so it sees every event the
;; server emits through the SDK, on both the one-shot @post responses and the
;; persistent /stream. (The raw WebKit flush comment bypasses the SDK, so
;; `flush-tick!` logs itself.) Off by default = zero overhead.
(def ^:private sse-debug? (some? (System/getenv "SALTRIM_SSE_DEBUG")))

(def ^:private logging-write-profile
  (let [build (ac/->build-event-str)]
    {ac/write! (fn [event-type data-lines opts]
                 (util/log "SSE →" event-type "·"
                           (let [s (str/join " | " data-lines)]
                             (if (> (count s) 240) (str (subs s 0 240) "…") s)))
                 (build event-type data-lines opts))}))

(defn- sse-opts
  "Add the SSE-tracing write profile to `->sse-response` opts when SALTRIM_SSE_DEBUG
   is set; otherwise pass them through unchanged (SDK uses its default profile)."
  [opts]
  (cond-> opts sse-debug? (assoc hk/write-profile logging-write-profile)))

(defn- sse
  "One-shot SSE response: open, run f with the generator, close. f does the
   patch-elements!/patch-signals! calls."
  [req f]
  (hk/->sse-response req (sse-opts {hk/on-open (fn [gen] (f gen) (d*/close-sse! gen))})))

;; --- Safari/WebKit fetch-stream flush (server-side, WebKit-only) ---------
;; WebKit delivers a fetch() response body to JS in coalesced lumps and holds
;; the trailing bytes (below an internal threshold) until a LATER, separate
;; network write arrives — and drops them for good if nothing does. Datastar's
;; @get stream reads over fetch(), so a collaboration push to a peer that isn't
;; followed by more traffic never lands in Safari (verified: trailing edit lost
;; ~40% of the time, even after seconds). Chromium/Firefox stream incrementally
;; and are unaffected; the editor's own actions answer over one-shot @post
;; responses that CLOSE (=flush), so only peer broadcasts are affected.
;;
;; Padding the push itself does NOT help — same-instant bytes coalesce into the
;; same held lump (measured: 16 KB appended still lost 4/6). What reliably
;; triggers delivery is a *time-separated* follow-up write. So a few ms after a
;; broadcast to a WebKit peer we send a raw SSE comment (ignored by the client
;; parser — its colon is at column 0): that extra read cycle flushes the push.
;; ~30 ms is imperceptible yet reliable (0 lost across 24 edits). It is gated by
;; User-Agent (only WebKit pays) and coalesced to one pending tick per session,
;; so idle and non-WebKit streams stay completely silent — this is not a
;; heartbeat.
(def ^:private webkit-flush-ms 30)

(defonce ^:private flush-pool
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "saltrim-webkit-flush")
                         (.setDaemon true))))))

(defonce ^:private flush-pending (java.util.concurrent.ConcurrentHashMap.))

(defn- webkit-ua?
  "True for WebKit engines (Safari desktop/iOS, iOS browsers) but not
   Chrome/Chromium/Edge — i.e. the ones whose fetch() SSE buffering needs the
   flush tick. iOS Chrome (\"CriOS\", no \"Chrome\") is WebKit and matches."
  [ua]
  (let [ua (str ua)]
    (and (str/includes? ua "AppleWebKit")
         (not (str/includes? ua "Chrome"))
         (not (str/includes? ua "Chromium")))))

(defn- flush-tick!
  "Send a raw SSE comment to sid's stream — a time-separated write that flushes
   WebKit's held fetch buffer. The colon-at-column-0 line is ignored by the
   Datastar client parser (no DOM/signal effect). The SDK has no comment
   primitive, so we write the http-kit channel directly, under the gen's lock."
  [sid]
  (when-let [g (:gen (@sessions* sid))]
    (when sse-debug? (util/log "SSE →" ": flush" "·" sid))
    (try (d*/lock-sse! g
           (http/send! (.ch ^starfederation.datastar.clojure.adapter.http_kit.impl.SSEGenerator g)
                       ": flush\n" false))
         (catch Throwable _))))

(defn- webkit-flush!
  "Schedule a flush tick ~webkit-flush-ms after a broadcast to a WebKit peer,
   coalescing to at most one pending tick per session (so a burst of pushes
   costs one trailing flush, and idle streams none)."
  [sid]
  (when (nil? (.putIfAbsent flush-pending sid Boolean/TRUE))
    (.schedule flush-pool
               ^Runnable (fn [] (.remove flush-pending sid) (flush-tick! sid))
               (long webkit-flush-ms) java.util.concurrent.TimeUnit/MILLISECONDS)))

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
      (re-find #"cannot be cast.*?(Number|Long|Double|Integer|Ratio|BigDecimal|BigInt)" m)
      "type error (number expected)"
      (re-find #"cannot be cast" m)    "type error"
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
  "Push changed cells to OTHER sessions in the same ROOM (sheet+branch), each
   scoped to that session's own viewport (so coords match their window). A write
   to a dead stream throws -> reap that session."
  [editor-sid room sh affected]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid editor-sid) (= room (:room s)) (:gen s))
      (let [vis (filter #(in-window? (:view s) %) affected)]
        (when (seq vis)
          (try (d*/lock-sse! (:gen s) (d*/patch-elements! (:gen s) (render-cells sh vis (:view s))))
               (when (:webkit? s) (webkit-flush! sid))
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
  "Overlay markers for every OTHER session in the viewer's ROOM whose cursor
   falls in viewer-sid's window. Rendered relative to the viewer's own view so
   coords line up. `room` = [sheet-id branch]."
  [viewer-sid room]
  (let [view (session-view viewer-sid)
        sh   (:sh (@sheets* room))]
    (apply str
           (for [[sid s] @sessions*
                 :when (and (not= sid viewer-sid) (= room (:room s))
                            (:cursor s) (in-window? view (:cursor s)))]
             (peer-marker sh view s)))))

(defn- self-html
  "THIS session's own selection / editing marker for its #self overlay, rendered
   window-relative to its own view. Empty when there is no cursor or it scrolled
   out of the window. `room` = [sheet-id branch]."
  [sid room]
  (let [s    (@sessions* sid)
        view (session-view sid)
        a    (:cursor s)
        sh   (:sh (@sheets* room))]
    (if (and s sh (= room (:room s)) a (in-window? view a))
      (let [{:keys [ci ri]} (addr/parse a)
            [cb rb] (view-base view)]
        (str (h/html
              [:div {:class (str "selfcell" (when (= (:editing s) a) " editing"))
                     :style (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx;"
                                    (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                                    (dec (col-w sh ci)) (dec (row-h sh ri)))}])))
      "")))

(defn- broadcast-presence!
  "Re-render the #peers overlay for every session in `room` (each scoped to its
   own view). Called whenever any cursor / editing state changes."
  [room]
  (doseq [[sid s] @sessions*]
    (when (and (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#peers" (peers-html sid room)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

(defn- render-window!
  "Push the full visible window for `view` to `gen`: cells, both header strips,
   the #meta totals, and this session's own overlays. Used after a scroll (/view)
   and after an axis resize (/size), where every position in the window shifts.
   `room` = [sheet-id branch]."
  [gen sid room sh view]
  (let [[cis ris] (window (:r0 view) (:c0 view))]
    (patch-inner! gen "#cells"   (cells-html sh cis ris))
    (patch-inner! gen "#colhead" (colhead-html sh cis))
    (patch-inner! gen "#rowhead" (rowhead-html sh ris))
    (d*/patch-elements! gen (meta-html sh (:r0 view) (:c0 view)))   ; #meta by id
    (patch-inner! gen "#self"  (self-html sid room))
    (patch-inner! gen "#peers" (peers-html sid room))))

(defn- broadcast-window!
  "A resize shifts every position, so re-render each OTHER session in the room's
   whole window on its own stream (scoped to that session's view)."
  [editor-sid room sh]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid editor-sid) (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (render-window! (:gen s) sid room sh (:view s)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

(defn- locked-by-other?
  "Is `cell` currently being edited by some other session IN THE SAME ROOM?"
  [sid room cell]
  (boolean (some (fn [[k s]]
                   (and (not= k sid) (= room (:room s)) (= (:editing s) cell)))
                 @sessions*)))

;; --- definitions library (per-chunk, collaboratively locked) ------------

(defn- def-editor-of
  "The sid currently editing definition chunk `id` in `room`, or nil. (Defs are
   per-branch, so the lock is per-room.)"
  [room id]
  (some (fn [[k s]] (when (and (= room (:room s)) (= (:editdef s) id)) k))
        @sessions*))

(defn- def-names
  "The symbols a chunk declares — the names of every top-level (def…)/(defn…)
   form — shown as badges on the collapsed card. Empty source -> [\"untitled\"]."
  [src]
  (let [ns (map second (re-seq #"\(def[a-z]*\s+([A-Za-z0-9*+!?<>=_.%/-]+)" (str src)))]
    (if (seq ns) (vec ns) ["untitled"])))

(defn- fmt-edited
  "Epoch-ms -> \"yyyy-MM-dd HH:mm\" (local), or nil."
  [ms]
  (when ms
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant (java.time.Instant/ofEpochMilli (long ms))
                                                (java.time.ZoneId/systemDefault)))))

(defn- deflib-html
  "Inner HTML of #deflib for session `sid`. An ACCORDION: each chunk shows
   collapsed (tier 1) as its declared-name badges + last-edit time, with Edit /
   delete. Edit (`/deflock`) expands it (tier 2) into a textarea bound to $defsrc
   with Save / Cancel and a ⤢ that opens the shared big editor (tier 3). A chunk
   held by another session shows a lock badge and stays collapsed. `sid` may be
   nil (initial page render: all read-only, no own-edit). `room` = [sheet-id branch]."
  [sid room]
  (let [sh     (:sh (@sheets* room))
        chunks (when sh (sheet/defs sh))
        card   "border:1px solid var(--grid);border-radius:6px;padding:.45rem .6rem;margin:.4rem 0;"
        row    "display:flex;align-items:center;gap:.4rem;flex-wrap:wrap;"
        when-s "font:11px sans-serif;color:var(--muted);white-space:nowrap;"
        ta     (str "width:100%;box-sizing:border-box;min-height:7rem;margin:.3rem 0;"
                    "white-space:pre;font:13px/1.4 monospace;resize:vertical;")
        badges (fn [src] (for [n (def-names src)] [:span {:class "badge"} n]))]
    (str (h/html
          [:div
           (if (empty? chunks)
             [:p {:style "font:13px sans-serif;color:var(--muted);margin:.3rem 0;"}
              "No definitions yet — add one below."]
             (for [{:keys [id src edited]} chunks
                   :let [editor (def-editor-of room id)
                         mine?  (and sid (= editor sid))]]
               [:div {:style card}
                (cond
                  ;; tier 2/3 — this session is editing: textarea + ⤢ big editor
                  mine?
                  [:div
                   [:div {:style row}
                    (badges src) [:span {:style "flex:1;"}]
                    [:span {:style "font:11px sans-serif;color:var(--accent);"} "editing"]
                    [:button {:class "btn" :title "open the big editor"
                              :data-on:click "$big=$defsrc, $bigwhat='def', $bigedit=true"} "⤢"]]
                   [:textarea {:class "tool mono" :data-bind:defsrc "" :spellcheck "false"
                               :placeholder "(defn double [x] (* 2 x))" :style ta}]
                   [:div {:style "display:flex;gap:.4rem;"}
                    [:button {:class "btn primary" :data-on:click "@post('/defsave')"} "Save"]
                    [:button {:class "btn" :data-on:click "@post('/defunlock')"} "Cancel"]]]

                  ;; locked by another collaborator: collapsed, no Edit
                  editor
                  [:div {:style row}
                   (badges src) [:span {:style "flex:1;"}]
                   [:span {:style when-s}
                    (str "🔒 " (or (get-in @sessions* [editor :uname]) "someone") " editing")]]

                  ;; tier 1 collapsed: name badges + last-edit time + Edit / delete
                  :else
                  [:div {:style row}
                   (badges src) [:span {:style "flex:1;"}]
                   (when-let [w (fmt-edited edited)] [:span {:style when-s} (str "edited " w)])
                   [:button {:class "btn" :data-on:click (str "$defid='" id "', @post('/deflock')")} "Edit"]
                   [:button {:class "btn" :data-on:click (str "$defid='" id "', @post('/defdel')")
                             :title "delete this definition"} "🗑"]])]))
           [:button {:class "btn" :data-on:click "@post('/defadd')" :style "margin-top:.3rem;"}
            "+ Add definition"]]))))

(defn- bigedit-html
  "A large shared editor modal (#bigedit). Opened from the formula bar, the style
   bar, or a definition card by setting $big (the text to edit), $bigwhat (which
   target: 'v' | 'style' | 'def') and $bigedit=true. Apply writes $big back to the
   target signal and posts to the matching endpoint — entirely declarative."
  []
  (let [ta (str "width:100%;box-sizing:border-box;min-height:52vh;"
                "font:13px/1.5 monospace;white-space:pre;resize:vertical;"
                "border:1px solid var(--line);border-radius:var(--radius);padding:.5rem .6rem;")]
    (str (h/html
          [:div {:id "bigeditwrap" :data-show "$bigedit" :data-on:click "$bigedit=false"
                 :style (str "position:fixed;inset:0;z-index:60;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:48rem;width:100%;padding:1rem 1.1rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.4rem;"}
             [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"
                   :data-text (str "$bigwhat==='v' ? 'Edit value / formula' : "
                                   "($bigwhat==='style' ? 'Edit style source' : 'Edit definition')")}]
             [:button {:class "btn" :data-on:click "$bigedit=false" :title "close"} "✕"]]
            [:textarea {:id "bigbox" :class "mono" :data-bind:big "" :spellcheck "false" :style ta}]
            [:div {:style "display:flex;gap:.4rem;margin-top:.5rem;justify-content:flex-end;"}
             [:button {:class "btn" :data-on:click "$bigedit=false"} "Cancel"]
             [:button {:class "btn primary"
                       :data-on:click
                       (str "($bigwhat==='v' ? ($v=$big, $cell=$sel, @post('/cell')) : "
                            "$bigwhat==='style' ? ($stylesrc=$big, $cell=$sel, @post('/style')) : "
                            "($defsrc=$big, @post('/defsave'))), $bigedit=false")} "Apply"]]]]))))

(defn- push-deflib! [gen sid room]
  (patch-inner! gen "#deflib" (deflib-html sid room)))

(defn- broadcast-deflib!
  "Re-render #deflib for every session in `room` (each its own view, since lock
   badges + the editable card are per session). Used when the library or a lock
   changes, and on reap to release a departed editor's lock."
  [room]
  (doseq [[sid s] @sessions*]
    (when (and (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#deflib" (deflib-html sid room)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

(defn- broadcast-deflib-except!
  "Like broadcast-deflib! but skips `except-sid` (whose own #deflib the calling
   handler already patched on its one-shot response)."
  [except-sid room]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid except-sid) (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#deflib" (deflib-html sid room)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

(defn- def-errs-msg [errors]
  (if (seq errors)
    (str "saved; cells still erroring: "
         (str/join "; " (map (fn [[a m]] (str a ": " (pretty-err m))) errors)))
    ""))

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

(defn- sig-branch
  "The validated branch from request signals ($branch), defaulting to db/MAIN."
  [sig]
  (let [b (:branch sig)] (if (store/valid-branch? b) b db/MAIN)))

(defn- with-access
  "POST handlers: resolve the signed-in user and sheet access from the request
   signals (the link token rides in $link, the branch in $branch). On success
   open the one-shot SSE response and call (f uid sheet-id rec sig gen);
   otherwise raise the access/auth error toast. The rec handed to `f` carries the
   effective `:level`, the link `:token`, and the resolved `:branch`/`:room`."
  [req f]
  (let [uid      (auth/req->uid req)
        sig      (read-signals req)
        sheet-id (:sheet sig)
        branch   (sig-branch sig)
        token    (not-empty (str (:link sig)))
        rec      (accessible-rec uid sheet-id branch token)]
    (if-not rec
      (deny req (if uid "no access to this sheet" "not signed in"))
      (let [rec (assoc rec :level (level-of uid sheet-id token rec) :token token
                       :branch branch :room [sheet-id branch])]
        (sse req (fn [gen] (f uid sheet-id rec sig gen)))))))

(defn- with-owner
  "Like `with-access`, but requires the user to OWN the (already-loaded) sheet —
   for owner-only actions such as sharing/branching. The rec carries
   `:branch`/`:room` like with-access."
  [req f]
  (let [uid      (auth/req->uid req)
        sig      (read-signals req)
        sheet-id (:sheet sig)
        branch   (sig-branch sig)
        rec      (when (store/valid-id? (str sheet-id)) (@sheets* [sheet-id branch]))]
    (if-not (and uid rec (= uid (:owner rec)))
      (deny req "only the owner can do this")
      (let [rec (assoc rec :branch branch :room [sheet-id branch])]
        (sse req (fn [gen] (f uid sheet-id rec sig gen)))))))

(defn- with-stream-access
  "The persistent /stream GET. It is opened with Datastar's @get, so identity +
   sheet + link token + branch all ride in the request SIGNALS
   ($sid/$sheet/$link/$branch), not the URL path. On success call
   (f uid sid sheet-id branch token) — which returns its OWN long-lived SSE
   response; otherwise a plain 403."
  [req f]
  (let [uid      (auth/req->uid req)
        sig      (read-signals req)
        sid      (:sid sig)
        sheet-id (:sheet sig)
        branch   (sig-branch sig)
        token    (not-empty (str (:link sig)))]
    (if-not (accessible-rec uid sheet-id branch token)
      {:status 403 :body "no access"}
      (f uid sid sheet-id branch token))))

(defn- push-changes!
  "Render `affected` cells back to the editor (one-shot SSE), toast any value /
   style errors, and broadcast the same cells to collaborators in the room.
   Shared by /cell and /style — both just compute an affected set and hand it
   here. `room` = [sheet-id branch]."
  [gen sid room sh affected]
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
    (broadcast! sid room sh affected)))

;; --- per-session undo/redo --------------------------------------------------
;; Each session (≈ a tab) keeps a stack of the edits IT made; the selective-undo
;; step is sheet/undo-step (it skips props a collaborator has overwritten). Undo
;; is a normal authored write (persisted + broadcast); the stack is in-memory per
;; session (lost on reload, like most editors). Stack mutations all happen under
;; `edit-lock`, so the read-modify-write of a session's stacks is serialized.

(def ^:private UNDO-CAP 200)

(defn- record-edit!
  "Push an undo entry for a just-applied edit (capped; clears redo). No-op when
   nothing actually changed."
  [sid addr prop before after]
  (when (and (@sessions* sid) (not= before after))
    (swap! sessions* update sid
           (fn [s] (-> s
                       (update :undo #(vec (take-last UNDO-CAP (conj (or % []) {:addr addr :prop prop :before before :after after}))))
                       (assoc :redo []))))))

(defn- handle-undo* [req dir]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (if (not= :read-write (:level rec))
        (signals! gen {:err "read-only access — you can't edit this sheet"})
        (let [sh (:sh rec)
              affected
              (locking edit-lock
                (let [s (@sessions* sid)
                      {:keys [stacks affected]}
                      (sheet/undo-step sh {:undo (:undo s) :redo (:redo s)} dir)]
                  (swap! sessions* update sid assoc :undo (:undo stacks) :redo (:redo stacks))
                  (when affected (sheet/settle! sh) (save-rec! (:room rec) uid))
                  affected))]
          (if affected
            (push-changes! gen sid (:room rec) sh affected)
            (signals! gen {:err ""})))))))           ; nothing (un)doable — silent

(defn- handle-undo [req] (handle-undo* req :undo))
(defn- handle-redo [req] (handle-undo* req :redo))

(defn- handle-cell [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [cell v sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))   ; lazy re-register + keep alive
      (if (not= :read-write (:level rec))
        (signals! gen {:err "read-only access — you can't edit this sheet"})
        (let [sh (:sh rec)]
          (when (addr/valid? cell)
            (locking edit-lock
            (try
              (when (locked-by-other? sid (:room rec) cell)
                (throw (ex-info "locked by another collaborator" {:locked cell})))
              (let [before (sheet/raw sh cell)]
                (sheet/set-cell! sh cell (str v))
                (sheet/settle! sh)
                (record-edit! sid cell :value before (sheet/raw sh cell)))
              (save-rec! (:room rec) uid)              ; autosave (source + meta)
              (push-changes! gen sid (:room rec) sh
                             (cons cell (into (sheet/dependents* sh cell)
                                              (sheet/style-dependents sh cell))))
              (catch Throwable e
                (signals! gen {:err (str cell ": " (pretty-err (.getMessage e)))}))))))))))

(defn- handle-style [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [cell sid] :as sig} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
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
              (let [before (get (sheet/style-srcs sh cell) prop)]
                (sheet/set-style! sh cell prop src)
                (sheet/settle! sh)
                (record-edit! sid cell prop before (get (sheet/style-srcs sh cell) prop)))
              (save-rec! (:room rec) uid)
              ;; only the styled cell re-renders now; style refs to OTHER cells
              ;; just register the edge for future value changes.
              (push-changes! gen sid (:room rec) sh [cell])
              (catch Throwable e
                (signals! gen {:err (str cell " " (name prop) " style: "
                                         (pretty-err (.getMessage e)))})))))))))

(defn- selected-cells
  "All distinct cell addresses across the space-separated \"TL:BR\" ranges in
   `selcells` (handles multi-range)."
  [selcells]
  (->> (str/split (str selcells) #"\s+")
       (remove str/blank?)
       (mapcat (fn [r] (let [[a b] (str/split r #":")] (addr/range-cells a (or b a)))))
       distinct))

(defn- sel-topleft
  "Top-left [c0 r0] of the bounding box of all selected cells, or nil when empty."
  [selcells]
  (let [crs (map (fn [a] (let [{:keys [ci ri]} (addr/parse a)] [ci ri])) (selected-cells selcells))]
    (when (seq crs) [(apply min (map first crs)) (apply min (map second crs))])))

(defn- handle-clear
  "Clear every cell in the selection ($selcells = space-separated \"TL:BR\"
   ranges). Each cleared cell records an undo entry (so Ctrl+Z restores it),
   then one settle / save / re-render + broadcast."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid selcells]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (if (not= :read-write (:level rec))
        (signals! gen {:err "read-only access — you can't edit this sheet"})
        (let [sh    (:sh rec)
              cells (selected-cells selcells)]
          (when (seq cells)
            (locking edit-lock
              (try
                (let [affected (atom [])]
                  (doseq [c cells]
                    (when-let [before (sheet/raw sh c)]   ; skip already-empty cells
                      (swap! affected into (cons c (sheet/dependents* sh c)))
                      (sheet/set-cell! sh c "")
                      (record-edit! sid c :value before nil)))
                  (when (seq @affected)
                    (sheet/settle! sh)
                    (save-rec! (:room rec) uid)
                    (push-changes! gen sid (:room rec) sh (distinct @affected))))
                (catch Throwable e
                  (signals! gen {:err (pretty-err (.getMessage e))}))))))))))

;; --- clipboard (copy / cut / paste) ---------------------------------------
;; A per-session clipboard ({:origin [c0 r0] :cells [{:dc :dr :value}]}), captured
;; server-side so it works for off-window ranges. Captures ALL selected cells
;; (multi-range too), each keyed by its offset from the selection's bounding-box
;; top-left, so the relative layout is preserved on paste. Value-level; paste
;; shifts formula refs RELATIVE to the move (see formula/shift-refs). Cut = copy +
;; clear. (Paste granularity, style/format in the clip and cross-sheet are
;; follow-ups.)

(defn- capture-clip
  "Capture all non-empty selected cells, keyed by offset from the selection's
   bounding-box top-left."
  [sh selcells]
  (when-let [[c0 r0] (sel-topleft selcells)]
    {:origin [c0 r0]
     :cells  (vec (for [a (selected-cells selcells)
                        :let [{:keys [ci ri]} (addr/parse a) v (sheet/raw sh a)]
                        :when v]
                    {:dc (- ci c0) :dr (- ri r0) :value v}))}))

(defn- handle-copy [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid selcells]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (when-let [clip (capture-clip (:sh rec) selcells)]
        (swap! sessions* assoc-in [sid :clip] clip))
      (signals! gen {:err ""}))))                  ; copy is silent (like everywhere)

(defn- paste-cells!
  "Write `clip` so its top-left lands at (tc,tr): each cell's value, formula refs
   shifted by the move. Records per-cell undo. Returns the affected addresses."
  [sh sid clip tc tr]
  (let [[oc orr] (:origin clip)
        dc (- tc oc) dr (- tr orr)
        affected (atom [])]
    (doseq [{cdc :dc cdr :dr value :value} (:cells clip)
            :let [a      (addr/make (+ tc cdc) (+ tr cdr))
                  before (sheet/raw sh a)
                  src    (if (str/starts-with? (str value) "=")
                           (formula/shift-refs value dc dr) value)]]
      (swap! affected into (cons a (sheet/dependents* sh a)))
      (sheet/set-cell! sh a src)
      (record-edit! sid a :value before (sheet/raw sh a)))
    (distinct @affected)))

(defn- handle-paste [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid selcells]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (if (not= :read-write (:level rec))
        (signals! gen {:err "read-only access — you can't edit this sheet"})
        (let [sh   (:sh rec)
              clip (get-in @sessions* [sid :clip])
              tgt  (sel-topleft selcells)]
          (when (and clip tgt (seq (:cells clip)))
            (locking edit-lock
              (try
                (let [affected (paste-cells! sh sid clip (first tgt) (second tgt))]
                  (when (seq affected)
                    (sheet/settle! sh) (save-rec! (:room rec) uid)
                    (push-changes! gen sid (:room rec) sh affected)))
                (catch Throwable e
                  (signals! gen {:err (pretty-err (.getMessage e))}))))))))))

(defn- handle-cut [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid selcells]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (if (not= :read-write (:level rec))
        (signals! gen {:err "read-only access — you can't edit this sheet"})
        (when-let [clip (capture-clip (:sh rec) selcells)]
          (let [sh (:sh rec) [c0 r0] (:origin clip)]
            (swap! sessions* assoc-in [sid :clip] clip)
            (locking edit-lock
              (try
                (let [affected (atom [])]
                  (doseq [{cdc :dc cdr :dr value :value} (:cells clip)
                          :let [a (addr/make (+ c0 cdc) (+ r0 cdr))]]
                    (swap! affected into (cons a (sheet/dependents* sh a)))
                    (sheet/set-cell! sh a "")
                    (record-edit! sid a :value value nil))
                  (when (seq @affected)
                    (sheet/settle! sh) (save-rec! (:room rec) uid)
                    (push-changes! gen sid (:room rec) sh (distinct @affected))))
                (catch Throwable e
                  (signals! gen {:err (pretty-err (.getMessage e))}))))))))))

(defn- handle-view [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [r0 c0 sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))   ; lazy re-register + keep alive
      (let [sh   (:sh rec)
            view {:r0 (max 0 (long (or r0 0))) :c0 (max 0 (long (or c0 0)))}]
        (set-session-view! sid view)
        ;; logical scroll: always cheap inner patches. The window is positioned
        ;; relative to (c0,r0); /app.js translates + sizes the scrollbars from
        ;; #meta totals (no giant spacer to resize).
        (render-window! gen sid (:room rec) sh view)))))

(defn- handle-size [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid rzcmd]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh (:sh rec)
            [axis idx size] (str/split (str rzcmd) #":")
            i (parse-long (str idx))
            s (parse-long (str size))]
        (cond
          (not= :read-write (:level rec))
          (signals! gen {:err "read-only access — you can't edit this sheet"})
          ;; ignore stray / malformed commands so a glitch can NEVER wipe a size
          ;; back to default (sizes only change via a valid positive value here)
          (not (and (#{"col" "row"} axis) i s (pos? s)))
          (signals! gen {:err ""})
          :else
          (locking edit-lock
            (try
              (case axis
                "col" (sheet/set-col-width!  sh i s)
                "row" (sheet/set-row-height! sh i s))
              (save-rec! (:room rec) uid)
              ;; positions across the whole window shift -> full re-render
              (render-window! gen sid (:room rec) sh (session-view sid))
              (broadcast-window! sid (:room rec) sh)
              (catch Throwable e
                (signals! gen {:err (pretty-err (.getMessage e))})))))))))

(defn- handle-props
  "Owner-only sheet properties: set the default column width / row height from
   $pcw/$prh. Every position + the default cell box change, so re-render the
   whole window for the owner and broadcast it to collaborators. Reseed $pcw/$prh
   with the stored (clamped) values."
  [req]
  (with-owner req
    (fn [uid sheet-id rec {:keys [sid pcw prh]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh (:sh rec)
            w  (parse-long (str pcw))
            h  (parse-long (str prh))]
        (locking edit-lock
          (try
            (when (and w (pos? w)) (sheet/set-default-col-w! sh w))
            (when (and h (pos? h)) (sheet/set-default-row-h! sh h))
            (save-rec! (:room rec) uid)
            (signals! gen {:pcw (str (sheet/default-col-w sh))
                           :prh (str (sheet/default-row-h sh)) :err ""})
            (render-window! gen sid (:room rec) sh (session-view sid))
            (broadcast-window! sid (:room rec) sh)
            (catch Throwable e
              (signals! gen {:err (pretty-err (.getMessage e))}))))))))

;; The definitions library is edited per chunk with a collaborative lock: a
;; session claims a chunk (/deflock), edits its own textarea, then /defsave or
;; /defunlock. While held, the chunk is read-only for everyone else. /defadd
;; creates a chunk and locks it; /defdel removes one. Every mutation recompiles
;; the sheet (defs feed every formula) so we re-render the window for all and
;; refresh #deflib for all.

(defn- guard-rw
  "Run f only with read-write access, else toast. f is a thunk."
  [rec gen f]
  (if (not= :read-write (:level rec))
    (signals! gen {:err "read-only access — you can't edit definitions"})
    (f)))

(defn- handle-deflock
  "Claim the edit lock on chunk $defid (if free), populate $defsrc with its
   source, and show this session an editable card; others see it locked."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid defid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh    (:sh rec)
            chunk (first (filter #(= (:id %) defid) (sheet/defs sh)))]
        (guard-rw rec gen
          (fn []
            (cond
              (nil? chunk)                       (signals! gen {:err ""})
              (def-editor-of (:room rec) defid)     (signals! gen {:err "that definition is being edited by someone else"})
              :else
              (do (swap! sessions* assoc-in [sid :editdef] defid)
                  (signals! gen {:defid defid :defsrc (:src chunk) :err ""})
                  (push-deflib! gen sid (:room rec))
                  (broadcast-deflib-except! sid (:room rec))))))))))

(defn- handle-defunlock
  "Release this session's edit lock without saving."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (swap! sessions* assoc-in [sid :editdef] nil)
      (signals! gen {:defid "" :defsrc ""})
      (push-deflib! gen sid (:room rec))
      (broadcast-deflib-except! sid (:room rec)))))

(defn- handle-defsave
  "Save the held chunk's new source ($defsrc), release the lock, recompile. A
   source that doesn't evaluate is rejected and the lock is KEPT so the user can
   fix it (toast)."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid defid defsrc]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh (:sh rec)]
        (guard-rw rec gen
          (fn []
            (if (not= sid (def-editor-of (:room rec) defid))
              (signals! gen {:err "you no longer hold this definition's lock"})
              (locking edit-lock
                (try
                  (let [{:keys [errors]} (sheet/update-def! sh defid (str defsrc))]
                    (swap! sessions* assoc-in [sid :editdef] nil)
                    (save-rec! (:room rec) uid)
                    (signals! gen {:defid "" :defsrc "" :err (def-errs-msg errors)})
                    (render-window! gen sid (:room rec) sh (session-view sid))
                    (broadcast-window! sid (:room rec) sh)
                    (push-deflib! gen sid (:room rec))
                    (broadcast-deflib-except! sid (:room rec)))
                  (catch Throwable e
                    (signals! gen {:err (str "definition error: " (pretty-err (.getMessage e)))})))))))))))

(defn- handle-defadd
  "Add a new (empty) chunk and immediately lock it for this session to edit."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh (:sh rec)]
        (guard-rw rec gen
          (fn []
            (locking edit-lock
              (let [{:keys [id]} (sheet/add-def! sh "")]
                (swap! sessions* assoc-in [sid :editdef] id)
                (save-rec! (:room rec) uid)
                (signals! gen {:defid id :defsrc "" :err ""})
                (push-deflib! gen sid (:room rec))
                (broadcast-deflib-except! sid (:room rec))))))))))

(defn- handle-defdel
  "Delete chunk $defid (unless another session is editing it) and recompile."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid defid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh     (:sh rec)
            editor (def-editor-of (:room rec) defid)]
        (guard-rw rec gen
          (fn []
            (if (and editor (not= editor sid))
              (signals! gen {:err "that definition is being edited by someone else"})
              (locking edit-lock
                (try
                  (let [{:keys [errors]} (sheet/remove-def! sh defid)]
                    (when (= defid (get-in @sessions* [sid :editdef]))
                      (swap! sessions* assoc-in [sid :editdef] nil))
                    (save-rec! (:room rec) uid)
                    (signals! gen {:defid "" :defsrc "" :err (def-errs-msg errors)})
                    (render-window! gen sid (:room rec) sh (session-view sid))
                    (broadcast-window! sid (:room rec) sh)
                    (push-deflib! gen sid (:room rec))
                    (broadcast-deflib-except! sid (:room rec)))
                  (catch Throwable e
                    (signals! gen {:err (pretty-err (.getMessage e))})))))))))))

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
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [can-write? (= :read-write (:level rec))]
        (swap! sessions* update sid assoc
               :cursor  (when (addr/valid? sel) sel)
               ;; a read-only viewer may move their cursor but never hold an
               ;; edit lock (which would block writers on a cell they can't edit)
               :editing (when (and edit can-write? (addr/valid? sel)) sel)))
      ;; peers' #peers via their persistent streams; this session's #self via
      ;; the one-shot @post response (the gen this gate opened).
      (broadcast-presence! (:room rec))
      (patch-inner! gen "#self" (self-html sid (:room rec))))))

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
    (fn [uid sid sheet-id branch token]
      (let [webkit? (webkit-ua? (get-in req [:headers "user-agent"]))
            room    [sheet-id branch]]
       (hk/->sse-response req
        (sse-opts
        {hk/on-open
         (fn [gen]
           (when (and sid (re-matches sid-re sid))
             ;; reconnect: keep the existing session's view/dims, just swap the
             ;; (dead) generator for the new one. fresh connect: register.
             (ensure-session! sid sheet-id branch uid token)
             ;; note the engine so collaboration pushes get the WebKit flush tick
             (swap! sessions* update sid assoc :gen gen :webkit? webkit?)
             ;; flush once so the client sees an established, open stream (an SSE
             ;; that sends nothing looks "finished" -> client reconnect storm).
             (try (d*/patch-signals! gen "{}") (catch Throwable _))
             ;; restore this session's own marker (reconnect) and show it the
             ;; cursors already present (and vice versa).
             (try (patch-inner! gen "#self" (self-html sid room)) (catch Throwable _))
             ;; show this session the current definitions library + lock state
             (try (push-deflib! gen sid room) (catch Throwable _))
             (broadcast-presence! room)))}))))))

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
    (fn [uid sheet-id rec {:keys [sid shareact plevel gtarget glevel grantee]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid)
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
        (save-rec! (:room rec) uid)
        (evict-unauthorized! sheet-id)
        (d*/patch-elements! gen (share-html uid sheet-id nil))
        (signals! gen {:err (or err "") :gtarget ""})))))

(defn- handle-branch
  "Owner-only branch ops, dispatched on $branchact:
   - fork:   create branch $bname as a copy of the CURRENT branch (records fork
             lineage in db/fork-branch!), then $goto the new branch.
   - delete: drop the current (non-main) branch and $goto main. The in-memory
             room is discarded WITHOUT an autosave (else unload-sheet! would
             re-persist — resurrect — the just-deleted cells).
   Switching branches is a plain client navigation (the picker) — not here."
  [req]
  (with-owner req
    (fn [uid sheet-id rec {:keys [sid branchact bname]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid)
      (let [branch    (:branch rec)
            [_ sname] (store/split-id sheet-id)
            base      (str "/?s=" sname)]          ; owners reach their sheet by ?s=
        (case (str branchact)
          "fork"
          (let [to (str bname)]
            (cond
              (not (store/valid-branch? to))
              (signals! gen {:err "branch name: letters, digits and - only (max 32)"})
              (= to db/MAIN)
              (signals! gen {:err "\"main\" is reserved"})
              (db/branch-exists? sheet-id to)
              (signals! gen {:err (str "branch \"" to "\" already exists")})
              :else
              (do
                (save-rec! (:room rec) uid)         ; flush source state before copying
                (db/fork-branch! sheet-id branch to)
                (signals! gen {:err "" :bname "" :branchpanel false
                               :goto (str base "&b=" to)}))))
          "delete"
          (if (= branch db/MAIN)
            (signals! gen {:err "the main branch can't be deleted"})
            (do
              (db/delete-branch! sheet-id branch)
              ;; discard the in-memory engine for this room so its eventual unload
              ;; can't re-save the deleted cells; sessions still here get denied on
              ;; their next request and reload to main.
              (when-let [{:keys [sh]} (@sheets* (:room rec))] (sheet/close! sh))
              (swap! sheets* dissoc (:room rec))
              (signals! gen {:err "" :branchpanel false :goto base})))
          (signals! gen {:err ""}))))))

;; --- auth routes (login page, OAuth redirects, logout) -------------------

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
(defn- url-decode [s] (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn- redirect [loc & [set-cookie]]
  (cond-> {:status 303 :headers {"Location" loc}}
    set-cookie (assoc-in [:headers "Set-Cookie"] set-cookie)))

(defn- login-page [err]
  (let [field (str "font:13px sans-serif;padding:6px 8px;border:1px solid #c7ccd1;"
                   "border-radius:4px;")]
    (str
     "<!doctype html>"
     (h/html
      [:html
       [:head [:meta {:charset "utf-8"}] [:title "SaltRim — sign in"]
        [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]]
       ;; explicit light bg so an OS dark theme can't black out the page; the
       ;; centered column lives in an inner wrapper.
       [:body {:style "font-family:sans-serif;margin:0;min-height:100vh;background:#fefefe;color:#3a4149;"}
        [:div {:style "max-width:24rem;margin:0 auto;padding:14vh 1rem 0;"}
        [:img {:src "/SaltRim.png" :alt "SaltRim"
               :style "display:block;margin:0 auto .6rem;width:180px;height:auto;"}]
        [:p {:style "color:#7a828b;text-align:center;margin-top:0;"} "Sign in to open your sheets."]
        (when err
          [:p {:style "color:#c0392b;font:13px sans-serif;"} (url-decode err)])
        [:div {:style "display:flex;flex-direction:column;gap:.6rem;"}
         (for [[k p] (auth/providers)]
           [:a {:href (str "/auth/" (name k))
                :style (str field "text-align:center;text-decoration:none;"
                            "background:#f4f6f8;color:#3a4149;display:block;")}
            (str "Continue with " (:label p))])
         (when (auth/dev-auth?)
           [:form {:method "get" :action "/auth/dev"
                   :style "display:flex;gap:.4rem;"}
            [:input {:name "name" :placeholder "your name (dev login)"
                     :autofocus true :style (str field "flex:1;")}]
            [:button {:style field} "Sign in"]])]]]]))))

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
            ;; the working branch rides in &b= (default MAIN). A stale/typo'd or
            ;; deleted branch silently falls back to main (creation is explicit
            ;; via fork) rather than 403-ing the whole sheet.
            branch      (let [b (qparam req "b")
                              b (if (store/valid-branch? b) b db/MAIN)]
                          (if (and id (db/branch-exists? id b)) b db/MAIN))
            rec         (when id (accessible-rec uid id branch token))]
        (if rec
          {:status 200 :headers {"Content-Type" "text/html"}
           :body (page (:sh rec) id sname branch uid token)}
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
    ;; brand wordmark (login page)
    [:get "/SaltRim.png"] (if-let [r (io/resource "SaltRim.png")]
                            {:status 200 :headers {"Content-Type" "image/png"
                                                   "Cache-Control" "max-age=86400"}
                             :body (io/input-stream r)}
                            {:status 404 :body "no logo"})
    ;; both the explicit link (/favicon.png) and the browser's automatic
    ;; /favicon.ico request (covers pages without the <link>, e.g. login)
    ([:get "/favicon.png"] [:get "/favicon.ico"])
    (if-let [r (io/resource "SaltRim-logo.png")]
      {:status 200 :headers {"Content-Type" "image/png"
                             "Cache-Control" "max-age=86400"}
       :body (io/input-stream r)}                         ; binary — not slurp
      {:status 404 :body "no favicon"})
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
    [:post "/undo"]       (handle-undo req)
    [:post "/redo"]       (handle-redo req)
    [:post "/clear"]      (handle-clear req)
    [:post "/copy"]       (handle-copy req)
    [:post "/cut"]        (handle-cut req)
    [:post "/paste"]      (handle-paste req)
    [:post "/size"]       (handle-size req)
    [:post "/props"]      (handle-props req)
    [:post "/deflock"]    (handle-deflock req)
    [:post "/defunlock"]  (handle-defunlock req)
    [:post "/defsave"]    (handle-defsave req)
    [:post "/defadd"]     (handle-defadd req)
    [:post "/defdel"]     (handle-defdel req)
    [:post "/view"]       (handle-view req)
    [:post "/presence"]   (handle-presence req)
    [:post "/share"]      (handle-share req)
    [:post "/branch"]     (handle-branch req)
    {:status 404 :body "not found"})))

(defn port
  "HTTP port — SALTRIM_PORT env or 8080."
  []
  (or (some-> (System/getenv "SALTRIM_PORT") parse-long) 8080))

(defn- start-sweeper-pool!
  "A scheduled pool that reaps idle/orphan sessions on an interval."
  []
  (doto (java.util.concurrent.Executors/newScheduledThreadPool 1)
    (.scheduleAtFixedRate ^Runnable (fn [] (try (sweep!) (catch Throwable _)))
                          SWEEP-MS SWEEP-MS java.util.concurrent.TimeUnit/MILLISECONDS)))

;; --- mount states ---------------------------------------------------------
;; Each state's VALUE is the live resource (no side atoms): `sweeper` is the
;; scheduled pool, `server` is http-kit's stop-fn. db's `conn` state starts
;; first (web requires db), so the order is conn → sweeper → server, reversed on
;; stop. (sessions*/sheets* stay atoms — they're in-memory caches, not lifecycle.)

(defstate sweeper
  :start (timed "session sweeper" (start-sweeper-pool!))
  :stop  (timed "session sweeper" (.shutdownNow ^java.util.concurrent.ExecutorService sweeper)))

(defstate server
  :start (timed (str "http server :" (port))
           (let [stop (http/run-server (-> #'app
                                           wrap-params
                                           wrap-keyword-params
                                           wrap-cookies)
                                       {:port (port)})]
                  (util/log "  serving http://localhost:" (port) "·"
                            (if-let [ps (seq (keys (auth/providers)))]
                              (str "auth: " (str/join ", " (map name ps))) "auth: none")
                            (if (auth/dev-auth?) "(+ dev login)" ""))
                  stop))
  ;; http-kit's run-server returns a stop-fn; the live sessions' streams die with
  ;; it, so drop the cache too.
  :stop  (timed "http server" (do (server) (reset! sessions* {}))))

;; Lifecycle is owned by the mount `system` ns; -main delegates there (resolved
;; at runtime to avoid a compile-time cycle, since system requires web).
(defn -main [& _]
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable (requiring-resolve 'uno.michelada.saltrim.system/stop!)))
  ((requiring-resolve 'uno.michelada.saltrim.system/start!))
  @(promise))
