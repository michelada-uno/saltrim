(ns uno.michelada.calcloj.web
  "Windowed spreadsheet grid over the sheet engine, driven by Datastar.

   Viewport: a scroll container holds a full-size spacer (real scrollbar for a
   huge logical sheet) and an absolutely-positioned window of only the visible
   cells. Scroll -> post first row/col -> server re-renders the window and
   SSE-patches the cells + sticky headers. Empty cells cost nothing (absent
   from the registry, no spin).

   Formula bar: wide input mirroring the selected cell's SOURCE (value or
   formula). Focusing a cell sets $sel/$bar; editing the bar posts to that cell.

   Run:  clj -M:web   then open http://localhost:8080"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [uno.michelada.calcloj.addr :as addr]
            [uno.michelada.calcloj.sheet :as sheet]
            [uno.michelada.calcloj.store :as store]
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

;; --- state --------------------------------------------------------------

(defonce ^:private sheets* (atom {}))   ; id -> sheet (lazy: load from disk / create)
(defn- sheet-for [id]
  (let [id (if (store/valid-id? id) id "default")]
    (or (@sheets* id)
        (let [s (or (store/load-sheet id) (sheet/create-sheet))]
          (swap! sheets* assoc id s)
          s))))

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
    (let [sh (@sheets* sheet-id)]
      (store/save! sheet-id sh)
      (sheet/close! sh)
      (swap! sheets* dissoc sheet-id))))

(defn- register-session! [sid sheet-id]
  (sheet-for sheet-id)                    ; acquire (load) the sheet
  (swap! sessions* assoc sid {:sheet sheet-id :view {:r0 0 :c0 0}
                              :dims nil :last-seen (now)
                              :color (color-for sid) :cursor nil :editing nil}))

(defn- touch! [sid] (when (@sessions* sid) (swap! sessions* assoc-in [sid :last-seen] (now))))

(defn- ensure-session!
  "Lazily (re)register a session for an active request, then stamp it alive. A
   client whose session was swept (crash/sleep TTL) transparently comes back."
  [sid sheet-id]
  (when (and sid (re-matches sid-re (str sid)))
    (when-not (@sessions* sid) (register-session! sid sheet-id))
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
    [(* cols CW) (* rows RH)]))

(defn- in-window? [{:keys [r0 c0]} addr]
  (let [{:keys [ci ri]} (addr/parse addr)]
    (and (<= (- (long c0) OVER) ci (+ (long c0) WIN-COLS))
         (<= (- (long r0) OVER) ri (+ (long r0) WIN-ROWS)))))

;; --- rendering ----------------------------------------------------------

(defn- display [sh a]
  (let [v (sheet/value sh a)]
    (cond (nil? v) "" (map? v) "#ERR" :else (str v))))

(defn- cell-id [a] (str "c_" a))

(defn- cell-input
  "Minimal per-cell HTML, positioned WINDOW-RELATIVE to (cbase,rbase). Class +
   position only; focus/blur/change are delegated on #cells."
  [sh a ci ri cbase rbase]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)]
    [:input {:id (cell-id a) :class "cell"
             :value disp :data-raw raw :data-val disp
             :style (format "left:%dpx;top:%dpx" (* (- ci cbase) CW) (* (- ri rbase) RH))}]))

(defn- cells-html [sh cis ris]
  (let [cb (first cis) rb (first ris)]
    (str (h/html (for [ri ris ci cis] (cell-input sh (addr/make ci ri) ci ri cb rb))))))

(defn- colhead-html [cis]
  (let [cb (first cis)]
    (str (h/html
          (for [ci cis]
            [:div {:style (format (str "position:absolute;left:%dpx;top:0;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (* (- ci cb) CW) CW HDR HDR)}
             (addr/idx->col ci)])))))

(defn- rowhead-html [ris]
  (let [rb (first ris)]
    (str (h/html
          (for [ri ris]
            [:div {:style (format (str "position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (* (- ri rb) RH) GUT RH RH)}
             (inc ri)])))))

(defn- meta-html
  "Hidden element carrying, to /app.js: the logical scroll totals (size the
   scrollbars) and the rendered window's base index cb/rb. /app.js translates the
   layers relative to cb/rb — patched together with #cells, so the transform
   always matches the displayed content (no jump while a fetch is in flight)."
  [sh r0 c0]
  (let [[tw th] (total-px sh r0 c0)
        [cb rb] (view-base {:r0 r0 :c0 c0})]
    (str (h/html [:div {:id "meta" :data-tw tw :data-th th :data-cb cb :data-rb rb
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
            ;; delegated cell handlers (focus/blur don't bubble -> focusin/out).
            ;; Presence is posted declaratively (@post '/presence') — the server
            ;; renders the selection (#self) and collaborator (#peers) overlays.
            :data-on:focusin
            (str "evt.target.classList.contains('cell') && "
                 "($sel=evt.target.id.slice(2), $bar=evt.target.dataset.raw, "
                 "evt.target.value=evt.target.dataset.raw, $edit=true, @post('/presence'))")
            :data-on:focusout
            (str "evt.target.classList.contains('cell') && "
                 "(evt.target.value=evt.target.dataset.val, $edit=false, @post('/presence'))")
            :data-on:change
            (str "evt.target.classList.contains('cell') && "
                 "($cell=evt.target.id.slice(2), $v=evt.target.value, $bar=$v, @post('/cell'))")
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
        [:div {:id "colhead"} (h/raw (colhead-html cis))]]]
      ;; row header strip (clipped; translated in Y)
      [:div {:id "rowclip" :style (format "%sleft:0;top:%dpx;bottom:%dpx;width:%dpx;z-index:3;"
                                          clip HDR BAR GUT)}
       [:div {:id "rowstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "rowhead"} (h/raw (rowhead-html ris))]]]
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
                                      "pointer-events:none;will-change:transform;")}]]
      ;; custom scrollbars
      [:div {:id "vbar" :style (format (str "position:absolute;right:0;top:%dpx;bottom:%dpx;width:%dpx;"
                                            "background:#f0f0f0;z-index:5;") HDR BAR BAR)}
       [:div {:id "vthumb" :style "position:absolute;left:1px;right:1px;top:0;height:30px;background:#bbb;border-radius:6px;"}]]
      [:div {:id "hbar" :style (format (str "position:absolute;left:%dpx;bottom:0;right:%dpx;height:%dpx;"
                                            "background:#f0f0f0;z-index:5;") GUT BAR BAR)}
       [:div {:id "hthumb" :style "position:absolute;top:1px;bottom:1px;left:0;width:30px;background:#bbb;border-radius:6px;"}]]])))

(defn- page [sh id]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "calcloj"]
      [:style (h/raw
               (str
                ;; cell sizing (geometry-driven -> format)
                (format (str "input.cell{position:absolute;width:%dpx;height:%dpx;"
                             "box-sizing:border-box;border:1px solid #ddd;"
                             "padding:2px 4px;font:13px monospace;}")
                        (- CW 1) (- RH 1))
                ;; --- selection / editing OVERLAY (#self), server-rendered.
                ;; literal % (gradients) -> kept outside the format call.
                ;; calm "you are here" selection box
                ".selfcell{position:absolute;box-sizing:border-box;pointer-events:none;"
                "border:2px solid #7aa7f0;}"
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
                ;; collaborator cursor overlays
                ".peer{position:absolute;box-sizing:border-box;border:2px solid #888;border-radius:2px;}"
                ".peer.editing{cursor:not-allowed;}"
                ".peer .peertag{position:absolute;top:-15px;left:-2px;"
                "font:10px/14px sans-serif;color:#fff;padding:0 4px;"
                "border-radius:3px 3px 3px 0;white-space:nowrap;}"))]
      [:script {:type "module" :src "/datastar.js"}]
      [:script {:src "/app.js"}]]
     [:body {:data-signals (format "{cell:'', v:'', err:'', sel:'', bar:'', edit:false, r0:0, c0:0, sheet:'%s', sid:''}" id)
             :style "font-family:sans-serif;margin:0;padding:.6rem;"}
      ;; hidden input carrying the session id into $sid, and a hidden trigger
      ;; /app.js clicks to open the persistent collaboration stream via Datastar
      ;; (so pushed patches are applied). $sid/$sheet go in the URL.
      [:input {:id "sidbox" :data-bind:sid "" :style "display:none;"}]
      [:button {:id "streamtrigger"
                :data-on:click "@get('/stream?sid='+$sid+'&s='+$sheet)"
                :style "display:none;"} ""]
      [:div {:id "toast" :data-show "$err != ''" :data-text "$err"
             :data-on:click "$err=''"
             :style (str "position:fixed;top:1rem;right:1rem;max-width:26rem;background:#c0392b;"
                         "color:#fff;padding:.6rem .9rem;border-radius:6px;font:13px sans-serif;"
                         "cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:20;")}]
      ;; sheet switcher + address box (jump) + formula bar
      [:div {:style "display:flex;align-items:center;gap:.5rem;margin-bottom:.4rem;"}
       [:input {:id "sheetbox" :value id :placeholder "sheet"
                :data-on:keydown "evt.key==='Enter' && (location.href='/?s='+el.value)"
                :title "sheet id — Enter to open"
                :style (str "width:7rem;font:13px sans-serif;padding:5px 6px;"
                            "border:1px solid #bbb;border-radius:4px;background:#f6f6f6;")}]
       [:input {:id "addrbox" :data-bind:sel "" :placeholder "A1"
                :data-on:keydown "evt.key==='Enter' && jump($sel)"
                :style (str "width:5rem;font:13px monospace;padding:5px 6px;text-align:center;"
                            "border:1px solid #bbb;border-radius:4px;")}]
       ;; editing via the formula bar still drives presence on the SELECTED cell
       ;; (so it shows the marching-ants self marker and locks it for peers).
       [:input {:id "fbar" :data-bind:bar "" :placeholder "value or =formula"
                :data-on:focus "$edit=true, @post('/presence')"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, $v=$bar, @post('/cell'))"
                :data-on:blur "$cell=$sel, $v=$bar, @post('/cell'), $edit=false, @post('/presence')"
                :style (str "flex:1;font:13px monospace;padding:5px 8px;border:1px solid #bbb;"
                            "border-radius:4px;")}]]
      ;; hidden r0/c0 carriers (/app.js sets these then clicks #viewtrigger so
      ;; Datastar @post /view and applies the returned window patch).
      [:input {:id "r0box" :data-bind:r0 "" :style "display:none;"}]
      [:input {:id "c0box" :data-bind:c0 "" :style "display:none;"}]
      [:button {:id "viewtrigger" :data-on:click "@post('/view')" :style "display:none;"} ""]
      ;; /app.js clicks this after a jump (address-box navigation) to post the new
      ;; selection as presence — the server moves the #self overlay to it.
      [:button {:id "presencetrigger" :data-on:click "$edit=false, @post('/presence')"
                :style "display:none;"} ""]
      ;; logical-scroll viewport (custom wheel + scrollbars in /app.js)
      (grid-layers sh {:r0 0 :c0 0})]])))

;; --- SSE (official Datastar SDK) ----------------------------------------

(defn- sse
  "One-shot SSE response: open, run f with the generator, close. f does the
   patch-elements!/patch-signals! calls."
  [req f]
  (hk/->sse-response req {hk/on-open (fn [gen] (f gen) (d*/close-sse! gen))}))

(defn- patch-inner!
  "Replace inner HTML of `selector` with `html`."
  [gen selector html]
  (d*/patch-elements! gen html {d*/selector selector d*/patch-mode d*/pm-inner}))

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

(defn- sheet-id-of [{:keys [sheet]}]
  (if (store/valid-id? sheet) sheet "default"))

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
  [view {:keys [cursor editing color]}]
  (let [{:keys [ci ri]} (addr/parse cursor)
        [cb rb] (view-base view)
        editing? (= editing cursor)
        base (format (str "left:%dpx;top:%dpx;width:%dpx;height:%dpx;border-color:%s;")
                     (* (- ci cb) CW) (* (- ri rb) RH) (dec CW) (dec RH) color)]
    (str (h/html
          [:div {:class (str "peer" (when editing? " editing"))
                 :style (if editing?
                          (str base "background:" (rgba color "0.16")
                               ";pointer-events:auto;cursor:not-allowed;")
                          base)}
           [:span {:class "peertag" :style (str "background:" color)}
            (if editing? "editing…" "•")]]))))

(defn- peers-html
  "Overlay markers for every OTHER session whose cursor falls in viewer-sid's
   window. Rendered relative to the viewer's own view so coords line up."
  [viewer-sid sheet-id]
  (let [view (session-view viewer-sid)]
    (apply str
           (for [[sid s] @sessions*
                 :when (and (not= sid viewer-sid) (= sheet-id (:sheet s))
                            (:cursor s) (in-window? view (:cursor s)))]
             (peer-marker view s)))))

(defn- self-html
  "THIS session's own selection / editing marker for its #self overlay, rendered
   window-relative to its own view. Empty when there is no cursor or it scrolled
   out of the window."
  [sid sheet-id]
  (let [s    (@sessions* sid)
        view (session-view sid)
        a    (:cursor s)]
    (if (and s (= sheet-id (:sheet s)) a (in-window? view a))
      (let [{:keys [ci ri]} (addr/parse a)
            [cb rb] (view-base view)]
        (str (h/html
              [:div {:class (str "selfcell" (when (= (:editing s) a) " editing"))
                     :style (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx;"
                                    (* (- ci cb) CW) (* (- ri rb) RH) (dec CW) (dec RH))}])))
      "")))

(defn- broadcast-presence!
  "Re-render the #peers overlay for every session on the sheet (each scoped to
   its own view). Called whenever any cursor / editing state changes."
  [sheet-id]
  (doseq [[sid s] @sessions*]
    (when (and (= sheet-id (:sheet s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#peers" (peers-html sid sheet-id)))
           (catch Throwable _ (reap-session! sid))))))

(defn- locked-by-other?
  "Is `cell` currently being edited by some session other than `sid`?"
  [sid sheet-id cell]
  (boolean (some (fn [[k s]]
                   (and (not= k sid) (= sheet-id (:sheet s)) (= (:editing s) cell)))
                 @sessions*)))

(defn- handle-cell [req]
  (let [{:keys [cell v sid] :as sig} (read-signals req)
        sheet-id (sheet-id-of sig)
        sh   (sheet-for sheet-id)
        _    (ensure-session! sid sheet-id)     ; lazy re-register + keep alive
        view (session-view sid)]
    (sse req
      (fn [gen]
        (when (addr/valid? cell)
          (locking edit-lock
            (try
              (when (locked-by-other? sid sheet-id cell)
                (throw (ex-info "locked by another collaborator" {:locked cell})))
              (sheet/set-cell! sh cell (str v))
              (sheet/settle! sh)
              (store/save! sheet-id sh)               ; autosave (source document)
              (let [affected (cons cell (sort (sheet/dependents* sh cell)))
                    visible  (filter #(in-window? view %) affected)   ; on-screen for THIS session
                    errs (keep (fn [a]
                                 (when-let [e (:error (sheet/value sh a))]
                                   (str a ": " (pretty-err e))))
                               affected)]
                ;; editor: immediate feedback on this one-shot response
                (when (seq visible)
                  (d*/patch-elements! gen (render-cells sh visible view)))
                (signals! gen {:err (if (seq errs) (str/join "; " errs) "")})
                ;; collaborators: push the same change to their streams
                (broadcast! sid sheet-id sh affected))
              (catch Throwable e
                (signals! gen {:err (str cell ": " (pretty-err (.getMessage e)))})))))))))

(defn- handle-view [req]
  (let [{:keys [r0 c0 sid] :as sig} (read-signals req)
        sheet-id (sheet-id-of sig)
        sh   (sheet-for sheet-id)
        view {:r0 (max 0 (long (or r0 0))) :c0 (max 0 (long (or c0 0)))}]
    (ensure-session! sid sheet-id)            ; lazy re-register + keep alive
    (set-session-view! sid view)
    (sse req
      (fn [gen]
        ;; logical scroll: always cheap inner patches. The window is positioned
        ;; relative to (c0,r0); /app.js translates + sizes the scrollbars from
        ;; #meta totals (no giant spacer to resize).
        (let [[cis ris] (window (:r0 view) (:c0 view))]
          (patch-inner! gen "#cells"   (cells-html sh cis ris))
          (patch-inner! gen "#colhead" (colhead-html cis))
          (patch-inner! gen "#rowhead" (rowhead-html ris))
          (d*/patch-elements! gen (meta-html sh (:r0 view) (:c0 view)))   ; #meta by id
          ;; re-render this session's own marker + peer cursors for the new window
          (patch-inner! gen "#self"  (self-html sid sheet-id))
          (patch-inner! gen "#peers" (peers-html sid sheet-id)))))))

(defn- body-json [req]
  (when-let [b (:body req)]
    (json/read-value (slurp b) json/keyword-keys-object-mapper)))

(defn- handle-presence
  "Datastar @post: signals carry {sel edit sheet sid}. Updates this session's
   cursor (:cursor) and editing cell (:editing), patches THIS session's own
   #self overlay back, and re-broadcasts #peers to everyone else on the sheet."
  [req]
  (let [{:keys [sel edit sid] :as sig} (read-signals req)
        sheet-id (sheet-id-of sig)]
    (ensure-session! sid sheet-id)
    (swap! sessions* update sid assoc
           :cursor  (when (addr/valid? sel) sel)
           :editing (when (and edit (addr/valid? sel)) sel))
    ;; peers' #peers via their persistent streams; this session's #self via the
    ;; one-shot @post response below (which is what we return).
    (broadcast-presence! sheet-id)
    (sse req (fn [gen] (patch-inner! gen "#self" (self-html sid sheet-id))))))

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
  (let [sid      (qparam req "sid")
        sheet-id (let [s (qparam req "s")] (if (store/valid-id? s) s "default"))]
    (hk/->sse-response req
      {hk/on-open
       (fn [gen]
         (when (and sid (re-matches sid-re sid))
           ;; reconnect: keep the existing session's view/dims, just swap the
           ;; (dead) generator for the new one. fresh connect: register.
           (when-not (@sessions* sid) (register-session! sid sheet-id))
           (swap! sessions* update sid assoc :gen gen)
           (touch! sid)
           ;; flush once so the client sees an established, open stream (an SSE
           ;; that sends nothing looks "finished" -> client reconnect storm).
           (try (d*/patch-signals! gen "{}") (catch Throwable _))
           ;; restore this session's own marker (reconnect) and show it the
           ;; cursors already present (and vice versa).
           (try (patch-inner! gen "#self" (self-html sid sheet-id)) (catch Throwable _))
           (broadcast-presence! sheet-id)))})))

(defn- handle-session-end [req]
  (let [{:keys [sid]} (body-json req)]
    (when sid (reap-session! sid))
    {:status 204}))

(defn- app [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"]            (let [sheet-id (or (some->> (:query-string req)
                                                      (re-find #"(?:^|&)s=([A-Za-z0-9_-]{1,64})")
                                                      second)
                                             "default")]
                            {:status 200 :headers {"Content-Type" "text/html"}
                             :body (page (sheet-for sheet-id) sheet-id)})
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
    [:get "/debug"]       {:status 200 :headers {"Content-Type" "application/json"}
                           :body (json/write-value-as-string
                                  {:sessions (count @sessions*)
                                   :loaded-sheets (vec (keys @sheets*))
                                   :detail (mapv (fn [[sid s]]
                                                   {:sid (subs sid 0 (min 6 (count sid)))
                                                    :sheet (:sheet s)
                                                    :gen? (boolean (:gen s))
                                                    :view (:view s)})
                                                 @sessions*)})}
    [:post "/cell"]       (handle-cell req)
    [:post "/view"]       (handle-view req)
    [:post "/presence"]   (handle-presence req)
    {:status 404 :body "not found"}))

(defonce ^:private server* (atom nil))

(defn start! [& [port]]
  (when @server* (@server*))
  (start-sweeper!)                          ; reap idle/orphan sessions
  (reset! server* (http/run-server #'app {:port (or port 8080)}))
  (println "calcloj on http://localhost:" (or port 8080)))

(defn -main [& _] (start!) @(promise))
