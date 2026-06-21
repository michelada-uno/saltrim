(ns uno.michelada.saltrim.app
  "SaltRim browser engine — the imperative half of the UI.

   The declarative half lives in Datastar attributes (signals + every server
   round-trip). This namespace owns only what Datastar can't express: the
   logical-scroll math (custom wheel + scrollbars, sub-cell transforms), editor
   positioning, column/row resize dragging, keyboard navigation, and the session
   beacon. It never sets signals or calls @post itself — when it needs the
   server it dispatches a `sr-*` CustomEvent on window and a `data-on:sr-*__window`
   handler (on #ctl / #streamer in web.clj) runs the Datastar action. So there
   are no hidden trigger buttons and no functions called from HTML — which also
   means :advanced compilation needs zero exports.

   Geometry constants come from the shared cljc (constants), so client and server
   agree on cell sizes. The per-render window base/totals + sparse axis overrides
   ride on #meta's data-* and are read live (they change every /view and /size)."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.constants :refer [CW RH]]
            [uno.michelada.saltrim.addr :as addr]))

;; --- tiny DOM + bridge helpers ---------------------------------------------

(defn- $ [id] (.getElementById js/document id))

(defn- dset
  "Read a data-* value off an element's dataset by its camelCase key. aget keeps
   the access string-keyed so :advanced never renames it."
  [el k] (when el (aget (.-dataset el) k)))

(defn- emit!
  "Dispatch a `sr-*` bridge event on window; `detail` is a JS object the matching
   data-on:<name>__window handler reads as evt.detail."
  ([nm] (emit! nm #js {}))
  ([nm detail] (.dispatchEvent js/window (js/CustomEvent. nm #js {:detail detail}))))

(defn- pj [s] (try (or (js/JSON.parse (or s "{}")) #js {}) (catch :default _ #js {})))

;; --- logical-scroll state --------------------------------------------------

(defonce ^:private SX (atom 0))          ; logical scroll position (px)
(defonce ^:private SY (atom 0))
(defonce ^:private last-c0 (atom 0))     ; last window top-left index posted
(defonce ^:private last-r0 (atom 0))
(defonce ^:private view-timer (atom nil))
(defonce ^:private SEL (atom {:ranges []}))  ; multi-selection (see below)
(declare render-sel!)                         ; defined with the selection section

(defn- mta
  "The rendered window's live geometry from #meta: totals (tw/th), base index
   (cb/rb), the per-sheet DEFAULT axis sizes (dcw/drh — the size of any unsized
   column/row) and the sparse per-index size overrides (colw/rowh JS objects)."
  []
  (let [m ($ "meta")]
    {:tw   (js/Number (or (dset m "tw") 1))
     :th   (js/Number (or (dset m "th") 1))
     :cb   (js/Number (or (dset m "cb") 0))
     :rb   (js/Number (or (dset m "rb") 0))
     :dcw  (js/Number (or (dset m "dcw") CW))
     :drh  (js/Number (or (dset m "drh") RH))
     :colw (pj (dset m "colw"))
     :rowh (pj (dset m "rowh"))}))

;; --- variable axis geometry (mirrors web.clj axis-off) ---------------------
;; Each axis defaults to base px but carries sparse overrides (index -> size).
;; ov is a JS object keyed by string indices, so access it with aget.

(defn- axis-size [i base ov]
  (let [v (aget ov (str i))] (if (some? v) v base)))

(defn- axis-pos [i base ov]
  (reduce (fn [acc k] (if (< (js/Number k) i) (+ acc (- (aget ov k) base)) acc))
          (* i base) (js/Object.keys ov)))

(defn- pixel->index [px base ov]
  (loop [ks (sort (map js/Number (js/Object.keys ov))) pos 0 idx 0]
    (if-let [k (first ks)]
      (let [gap (- k idx)]
        (if (> (+ pos (* gap base)) px)
          (+ idx (js/Math.floor (/ (- px pos) base)))
          (let [pos' (+ pos (* gap base))
                sz   (aget ov (str k))]
            (if (> (+ pos' sz) px)
              k
              (recur (rest ks) (+ pos' sz) (inc k))))))
      (+ idx (js/Math.floor (/ (- px pos) base))))))

(defn- view-size []
  (let [c ($ "cellclip")]
    {:w (if c (.-clientWidth c) 0) :h (if c (.-clientHeight c) 0)}))

(defn- set-transform! [id x y]
  (when-let [e ($ id)]
    (set! (.. e -style -transform) (str "translate(" x "px," y "px)"))))

(defn- thumb! [bar-id thumb-id s total vertical?]
  (let [bar ($ bar-id) th ($ thumb-id)]
    (when (and bar th)
      (let [vs    (if vertical? (:h (view-size)) (:w (view-size)))
            track (if vertical? (.-clientHeight bar) (.-clientWidth bar))
            len   (js/Math.max 24 (* track (js/Math.min 1 (/ vs (js/Math.max total 1)))))
            max-s (js/Math.max 1 (- total vs))
            pos   (* (/ (js/Math.min s max-s) max-s) (- track len))]
        (if vertical?
          (do (set! (.. th -style -height) (str len "px")) (set! (.. th -style -top) (str pos "px")))
          (do (set! (.. th -style -width)  (str len "px")) (set! (.. th -style -left) (str pos "px"))))))))

(defn- render! []
  (let [m  (mta)
        tx (- (axis-pos (:cb m) (:dcw m) (:colw m)) @SX)
        ty (- (axis-pos (:rb m) (:drh m) (:rowh m)) @SY)]
    ;; selection/editor/peer overlays + the cell layer all share the transform
    (doseq [id ["cells" "selrange" "self" "peers" "editlayer"]] (set-transform! id tx ty))
    (set-transform! "colstrip" tx 0)
    (set-transform! "rowstrip" 0 ty)
    (thumb! "vbar" "vthumb" @SY (:th m) true)
    (thumb! "hbar" "hthumb" @SX (:tw m) false)))

(defn- clamp-scroll! []
  (let [m (mta) vs (view-size)]
    (swap! SX #(js/Math.max 0 (js/Math.min % (js/Math.max 0 (- (:tw m) (:w vs))))))
    (swap! SY #(js/Math.max 0 (js/Math.min % (js/Math.max 0 (- (:th m) (:h vs))))))))

(defn- request-view!
  "Debounced: when the window's top-left index changes, ask the server for a new
   window (sr-view -> @post '/view'). `force?` posts even if unchanged (jump)."
  [force?]
  (let [m  (mta)
        c0 (pixel->index @SX (:dcw m) (:colw m))
        r0 (pixel->index @SY (:drh m) (:rowh m))]
    (when (or force? (not= c0 @last-c0) (not= r0 @last-r0))
      (reset! last-c0 c0)
      (reset! last-r0 r0)
      (js/clearTimeout @view-timer)
      (reset! view-timer (js/setTimeout #(emit! "sr-view" #js {:r0 r0 :c0 c0}) 70)))))

(defn- on-wheel [e]
  (.preventDefault e)
  (swap! SX + (.-deltaX e))
  (swap! SY + (.-deltaY e))
  (clamp-scroll!) (render!) (request-view! false))

(defn- drag-thumb! [bar-id thumb-id vertical?]
  (when-let [th ($ thumb-id)]
    (.addEventListener
     th "mousedown"
     (fn [ev]
       (.preventDefault ev)
       (let [bar    ($ bar-id)
             start  (if vertical? (.-clientY ev) (.-clientX ev))
             t0     (js/parseFloat (or (if vertical? (.. th -style -top) (.. th -style -left)) 0))
             track  (if vertical? (.-clientHeight bar) (.-clientWidth bar))
             len    (if vertical? (.-clientHeight th) (.-clientWidth th))
             m      (mta) vs (view-size)
             total  (if vertical? (:th m) (:tw m))
             vsz    (if vertical? (:h vs) (:w vs))
             max-s  (js/Math.max 1 (- total vsz))
             mm     (fn mm [e2]
                      (let [cur (if vertical? (.-clientY e2) (.-clientX e2))
                            pos (js/Math.max 0 (js/Math.min (+ t0 (- cur start)) (- track len)))
                            s   (* (/ pos (js/Math.max 1 (- track len))) max-s)]
                        (reset! (if vertical? SY SX) s)
                        (clamp-scroll!) (render!) (request-view! false)))
             mu     (atom nil)]
         (reset! mu (fn []
                      (.removeEventListener js/document "mousemove" mm)
                      (.removeEventListener js/document "mouseup" @mu)))
         (.addEventListener js/document "mousemove" mm)
         (.addEventListener js/document "mouseup" @mu))))))

;; --- selection / navigation -------------------------------------------------
;; $sel lives in #addrbox (data-bind:sel). We read it for nav and update it
;; through the sr-select bridge (which also posts cursor presence + mirrors the
;; cell's value/style into the formula + style bars via signals).

(defn- cur-sel []
  (let [b ($ "addrbox")] (when (and b (seq (.-value b))) (.toUpperCase (.-value b)))))

(defn- select! [a] (emit! "sr-select" #js {:addr a}))

(defn- ensure-visible! [a]
  (when-let [p (addr/parse a)]
    (let [m  (mta) vs (view-size)
          x  (axis-pos (:ci p) (:dcw m) (:colw m)) y (axis-pos (:ri p) (:drh m) (:rowh m))
          w  (axis-size (:ci p) (:dcw m) (:colw m)) h (axis-size (:ri p) (:drh m) (:rowh m))]
      (cond (< x @SX)                   (reset! SX x)
            (< (+ @SX (:w vs)) (+ x w)) (reset! SX (- (+ x w) (:w vs))))
      (cond (< y @SY)                   (reset! SY y)
            (< (+ @SY (:h vs)) (+ y h)) (reset! SY (- (+ y h) (:h vs))))
      (swap! SX #(js/Math.max 0 %))
      (swap! SY #(js/Math.max 0 %))
      (render!) (request-view! false))))

(defn- jump! [a]
  (when-let [p (addr/parse a)]
    (let [m (mta)]
      (reset! SX (axis-pos (:ci p) (:dcw m) (:colw m)))   ; park at top-left; /view extends totals
      (reset! SY (axis-pos (:ri p) (:drh m) (:rowh m))))
    (reset! SEL {:ranges [{:a [(:ci p) (:ri p)] :f [(:ci p) (:ri p)]}]})
    (select! (addr/make (:ci p) (:ri p)))
    (render!) (render-sel!) (request-view! true)))

;; --- multi-selection --------------------------------------------------------
;; Selection = a vector of ranges, each {:a anchor :f focus} as [ci ri]. The
;; ACTIVE cell (focus of the last range) drives $sel / formula bar / presence via
;; the sr-select bridge; the full marquee is drawn locally into #selrange (peers
;; see only the active cell, not the marquee). Plain click = single, Shift =
;; extend the last range, Ctrl/⌘ = add a range; Shift+arrows extend; Delete clears.

(defn- rng-norm [{:keys [a f]}]
  (let [[ac ar] a [fc fr] f]
    [(js/Math.min ac fc) (js/Math.min ar fr) (js/Math.max ac fc) (js/Math.max ar fr)]))

(defn- sel-active [] (some-> (peek (:ranges @SEL)) :f))

(defn- sel-multi?
  "More than one cell selected (a >1×1 range or multiple ranges) — when false the
   server-rendered #self marker already shows the lone active cell, so we draw
   nothing extra."
  []
  (let [rs (:ranges @SEL)]
    (or (> (count rs) 1)
        (and (seq rs) (let [[c0 r0 c1 r1] (rng-norm (first rs))] (or (not= c0 c1) (not= r0 r1)))))))

(defn- render-sel! []
  (when-let [layer ($ "selrange")]
    (let [m   (mta)
          cbx (axis-pos (:cb m) (:dcw m) (:colw m))
          rby (axis-pos (:rb m) (:drh m) (:rowh m))]
      (set! (.-innerHTML layer)
            (if-not (sel-multi?) ""
              (apply str
                (for [rng (:ranges @SEL)
                      :let [[c0 r0 c1 r1] (rng-norm rng)
                            x  (- (axis-pos c0 (:dcw m) (:colw m)) cbx)
                            y  (- (axis-pos r0 (:drh m) (:rowh m)) rby)
                            x2 (- (+ (axis-pos c1 (:dcw m) (:colw m)) (axis-size c1 (:dcw m) (:colw m))) cbx)
                            y2 (- (+ (axis-pos r1 (:drh m) (:rowh m)) (axis-size r1 (:drh m) (:rowh m))) rby)]]
                  (str "<div style='position:absolute;left:" x "px;top:" y "px;width:" (- x2 x 1)
                       "px;height:" (- y2 y 1) "px;box-sizing:border-box;"
                       "background:rgba(47,143,216,.14);border:1px solid var(--accent);'></div>"))))))))

(defn- sel-set! [ranges]
  (reset! SEL {:ranges ranges})
  (when-let [[c r] (sel-active)] (select! (addr/make c r)))   ; active -> $sel/bars/presence
  (render-sel!))

(defn- sel-single! [c r] (sel-set! [{:a [c r] :f [c r]}]))
(defn- sel-add!    [c r] (sel-set! (conj (:ranges @SEL) {:a [c r] :f [c r]})))
(defn- sel-extend! [c r]
  (sel-set! (let [rs (:ranges @SEL)]
              (if (seq rs) (conj (pop rs) (assoc (peek rs) :f [c r])) [{:a [c r] :f [c r]}]))))

(defn- sel-ranges-str []
  (str/join " " (for [rng (:ranges @SEL) :let [[c0 r0 c1 r1] (rng-norm rng)]]
                  (str (addr/make c0 r0) ":" (addr/make c1 r1)))))

(defn- clear-sel! []
  (when (seq (:ranges @SEL)) (emit! "sr-clear" #js {:ranges (sel-ranges-str)})))

(defn- cell-cr [t]
  (when (.contains (.-classList t) "cell")
    (let [p (addr/parse (subs (.-id t) 2))] [(:ci p) (:ri p)])))

(defn- on-cell-click [e]
  (when-let [[c r] (cell-cr (.-target e))]
    (cond
      (.-shiftKey e)                   (sel-extend! c r)
      (or (.-ctrlKey e) (.-metaKey e)) (sel-add! c r)
      :else                            (sel-single! c r))))

;; --- the single floating editor --------------------------------------------
;; Cells are display divs; editing happens in one #editor input positioned over
;; the active cell. data-bind:v feeds its value into $v; data-show:$edit reveals
;; it; commit/cancel are declarative (data-on:keydown/blur in web.clj). Here we
;; only place it and move focus into it.

(defn- start-edit! [a]
  (when-let [p (addr/parse a)]
    (let [a  (addr/make (:ci p) (:ri p))
          m  (mta) ed ($ "editor")]
      (ensure-visible! a)
      (set! (.. ed -style -left)   (str (- (axis-pos (:ci p) (:dcw m) (:colw m)) (axis-pos (:cb m) (:dcw m) (:colw m))) "px"))
      (set! (.. ed -style -top)    (str (- (axis-pos (:ri p) (:drh m) (:rowh m)) (axis-pos (:rb m) (:drh m) (:rowh m))) "px"))
      (set! (.. ed -style -width)  (str (- (axis-size (:ci p) (:dcw m) (:colw m)) 1) "px"))
      (set! (.. ed -style -height) (str (- (axis-size (:ri p) (:drh m) (:rowh m)) 1) "px"))
      (emit! "sr-edit" #js {:addr a})        ; sets $sel,$v,$edit=true,@post('/presence')
      ;; defer focus until Datastar has flipped $edit (data-show) + filled $v
      (js/setTimeout (fn [] (.focus ed) (.select ed)) 0))))

;; --- keyboard navigation ----------------------------------------------------
;; Arrows / Tab move the active cell (Shift+arrows EXTEND the range); Enter edits;
;; Delete/Backspace clears the selection; Ctrl/⌘+Z undo, +Shift (or Ctrl/⌘+Y)
;; redo. Ignored whenever any input is focused (the editor + toolbar fields own
;; their own keys — incl. native text undo while editing).

(defn- on-key [e]
  (let [ae  (.-activeElement js/document)
        tag (and ae (.-tagName ae))
        mod (or (.-ctrlKey e) (.-metaKey e))
        low (.toLowerCase (or (.-key e) ""))
        k   (.-key e)]
    (when-not (#{"INPUT" "TEXTAREA" "SELECT"} tag)
      (cond
        (and mod (= low "z")) (do (.preventDefault e) (emit! (if (.-shiftKey e) "sr-redo" "sr-undo")))
        (and mod (= low "y")) (do (.preventDefault e) (emit! "sr-redo"))
        (#{"Delete" "Backspace"} k) (when (seq (:ranges @SEL)) (.preventDefault e) (clear-sel!))
        :else
        (let [act (sel-active)]
          (if-not act
            (when (#{"ArrowRight" "ArrowLeft" "ArrowUp" "ArrowDown" "Tab" "Enter"} k)
              (.preventDefault e) (sel-single! 0 0) (ensure-visible! "A1"))
            (let [[ci ri] act]
              (if (= k "Enter")
                (do (.preventDefault e) (start-edit! (addr/make ci ri)))
                (let [[nc nr] (case k
                                "ArrowRight" [(inc ci) ri]
                                "ArrowLeft"  [(js/Math.max 0 (dec ci)) ri]
                                "ArrowDown"  [ci (inc ri)]
                                "ArrowUp"    [ci (js/Math.max 0 (dec ri))]
                                "Tab"        [(if (.-shiftKey e) (js/Math.max 0 (dec ci)) (inc ci)) ri]
                                nil)]
                  (when nc
                    (.preventDefault e)
                    ;; Shift+arrows extend the range; plain arrows / Tab move single
                    (if (and (.-shiftKey e) (not= k "Tab")) (sel-extend! nc nr) (sel-single! nc nr))
                    (ensure-visible! (addr/make nc nr))))))))))))

;; --- column / row resize ----------------------------------------------------
;; Header strips render a thin .colgrip/.rowgrip on each trailing edge. Dragging
;; one moves a single guide line; on release we emit one atomic "axis:idx:size"
;; command (sr-size -> @post '/size'). The server stores px + re-renders.

(def ^:private MINSZ 24)
(def ^:private SNAP 10)   ; px: how close to a default-multiple before it sticks

(defn- snap-size
  "Sticky resize: snap `raw` px to the nearest POSITIVE multiple of `base` (the
   sheet's default size) when within SNAP px — so a column settles on default,
   2×default, 3×default… instead of a near-but-off value. Hold Alt while dragging
   (alt? = true) to size freely with no snapping."
  [raw base alt?]
  (if alt?
    raw
    (let [n (js/Math.round (/ raw base)) target (* n base)]
      (if (and (pos? n) (<= (js/Math.abs (- raw target)) SNAP)) target raw))))

(defn- init-resize! []
  (let [vp ($ "viewport")]
    (.addEventListener
     vp "mousedown"
     (fn [e]
       (let [t (.-target e) cls (.-classList t)
             col? (.contains cls "colgrip") row? (.contains cls "rowgrip")]
         (when (or col? row?)
           ;; commit any in-progress edit first: the grip mousedown's
           ;; preventDefault (below) would otherwise BLOCK the editor's blur, so
           ;; the editor would stay open while /size re-renders the window —
           ;; leaving the floating editor misaligned with the server-rendered
           ;; overlay. sr-commit closes it via $edit (no reliance on focus).
           (emit! "sr-commit")
           (.preventDefault e) (.stopPropagation e)
           (let [m (mta) guide ($ "rzguide") rect (.getBoundingClientRect vp)
                 axis  (if col? "col" "row")
                 idx   (js/Number (dset t (if col? "ci" "ri")))
                 base  (if col? (:dcw m) (:drh m))
                 ov    (if col? (:colw m) (:rowh m))
                 start (if col? (.-clientX e) (.-clientY e))
                 start-sz (axis-size idx base ov)
                 sz    (atom start-sz)
                 place (fn [client]
                         (set! (.. guide -style -cssText)
                               (if col?
                                 (str "display:block;top:0;height:100%;width:2px;left:" (- client (.-left rect)) "px")
                                 (str "display:block;left:0;width:100%;height:2px;top:" (- client (.-top rect)) "px"))))
                 mm    (fn mm [e2]
                         (let [cur (if col? (.-clientX e2) (.-clientY e2))
                               raw (js/Math.max MINSZ (js/Math.round (+ start-sz (- cur start))))
                               s   (snap-size raw base (.-altKey e2))]
                           (reset! sz s)
                           ;; guide tracks the (snapped) edge so the stick is visible
                           (place (+ start (- s start-sz)))))
                 mu    (atom nil)]
             (place start)
             (reset! mu (fn []
                          (.removeEventListener js/document "mousemove" mm)
                          (.removeEventListener js/document "mouseup" @mu)
                          (set! (.. guide -style -display) "none")
                          (emit! "sr-size" #js {:cmd (str axis ":" idx ":" (js/Math.round @sz))})))
             (.addEventListener js/document "mousemove" mm)
             (.addEventListener js/document "mouseup" @mu))))))))

;; --- collaboration stream: open + reconnect --------------------------------
;; The persistent SSE is opened with @get('/stream') on #streamer (it reacts to
;; the sr-open bridge event). Datastar's @get retries transient drops itself, but
;; not a clean server close ('finished') or after it exhausts retries
;; ('retries-failed') — so we re-open on those, with capped backoff. No heartbeat.

(defonce ^:private unloading? (atom false))
(defonce ^:private left? (atom false))
(defonce ^:private stream-timer (atom nil))
(defonce ^:private stream-attempt (atom 0))

(defn- open-stream! []
  (when-not (or @unloading? @left?) (emit! "sr-open")))

(defn- schedule-reopen! []
  (when-not (or @unloading? @left? @stream-timer)
    (swap! stream-attempt inc)
    (let [delay (js/Math.min 30000 (* 1000 (js/Math.pow 2 @stream-attempt)))]  ; 2s,4s,…,30s
      (reset! stream-timer (js/setTimeout #(do (reset! stream-timer nil) (open-stream!)) delay)))))

;; --- session lifecycle (beacon out, rejoin on return) ----------------------
;; pagehide/unload are unreliable (Safari, bfcache, mobile), so a closed tab's
;; marker can linger on peers. The event that DOES fire reliably on close is
;; visibilitychange -> hidden: treat "hidden" as "left" (beacon out, suppress
;; reconnect) and rejoin on return. $sid rides on #ctl's data-sid (a signal the
;; server seeds) so the beacon body needs no Datastar access.

(defn- leave-session! []
  (when-let [sid (dset ($ "ctl") "sid")]
    (try
      (.sendBeacon js/navigator "/session/end"
                   (js/Blob. #js [(js/JSON.stringify #js {:sid sid})] #js {:type "application/json"}))
      (catch :default _))))

(defn- rejoin! []
  (reset! left? false)
  (open-stream!)                          ; server re-registers the session
  (when-let [a (cur-sel)] (select! a)))   ; re-assert our cursor for peers

;; --- init (after Datastar is ready) ----------------------------------------

(defn- init []
  (let [vp ($ "viewport") m ($ "meta")]
    (.addEventListener vp "wheel" on-wheel #js {:passive false})
    ;; click a cell to select (Shift = extend range, Ctrl/⌘ = add range)
    (.addEventListener vp "click" on-cell-click)
    ;; double-click a cell opens the floating editor over it
    (.addEventListener vp "dblclick"
                       (fn [e] (let [t (.-target e)]
                                 (when (.contains (.-classList t) "cell")
                                   (start-edit! (subs (.-id t) 2))))))
    ;; address box: Enter jumps to the typed cell
    (when-let [ab ($ "addrbox")]
      (.addEventListener ab "keydown"
                         (fn [e] (when (= "Enter" (.-key e)) (.preventDefault e) (jump! (cur-sel))))))
    (drag-thumb! "vbar" "vthumb" true)
    (drag-thumb! "hbar" "hthumb" false)
    (.addEventListener js/window "resize" render!)
    (.addEventListener js/document "keydown" on-key)
    (init-resize!)
    ;; #meta is morphed on every /view AND /size (including a collaborator's
    ;; pushed resize) — re-render on any of its attribute changes so geometry
    ;; stays live for everyone.
    ;; window geometry changed (scroll/resize by anyone) -> re-translate AND
    ;; redraw the selection marquee (its rects are window-relative to cb/rb).
    (when m (.observe (js/MutationObserver. (fn [] (render!) (render-sel!))) m #js {:attributes true}))
    (render!) (render-sel!)               ; page already rendered the window at (0,0)
    (open-stream!)))                      ; open the collaboration stream

;; Register load-time listeners now (they only addEventListener); defer the
;; DOM-dependent init to datastar-ready so signals + #ctl/#streamer handlers exist.
(.addEventListener js/document "datastar-ready" init)

(.addEventListener
 js/document "datastar-fetch"
 (fn [e]
   (let [d (.-detail e)]
     (when (and d (.-el d) (= (.. d -el -id) "streamer"))
       (case (.-type d)
         "started"        (reset! stream-attempt 0)
         ("finished" "retries-failed") (schedule-reopen!)
         nil)))))

(.addEventListener js/window "pagehide" (fn [] (reset! unloading? true) (leave-session!)))

(.addEventListener
 js/document "visibilitychange"
 (fn []
   (if (= (.-visibilityState js/document) "hidden")
     (do (reset! left? true) (leave-session!))
     (when @left? (rejoin!)))))
