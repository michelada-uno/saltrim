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
  (:require [uno.michelada.saltrim.constants :refer [CW RH]]
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

(defn- mta
  "The rendered window's live geometry from #meta: totals (tw/th), base index
   (cb/rb) and the sparse per-index size overrides (colw/rowh JS objects)."
  []
  (let [m ($ "meta")]
    {:tw   (js/Number (or (dset m "tw") 1))
     :th   (js/Number (or (dset m "th") 1))
     :cb   (js/Number (or (dset m "cb") 0))
     :rb   (js/Number (or (dset m "rb") 0))
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
        tx (- (axis-pos (:cb m) CW (:colw m)) @SX)
        ty (- (axis-pos (:rb m) RH (:rowh m)) @SY)]
    ;; selection/editor/peer overlays + the cell layer all share the transform
    (doseq [id ["cells" "self" "peers" "editlayer"]] (set-transform! id tx ty))
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
        c0 (pixel->index @SX CW (:colw m))
        r0 (pixel->index @SY RH (:rowh m))]
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
          x  (axis-pos (:ci p) CW (:colw m)) y (axis-pos (:ri p) RH (:rowh m))
          w  (axis-size (:ci p) CW (:colw m)) h (axis-size (:ri p) RH (:rowh m))]
      (cond (< x @SX)                   (reset! SX x)
            (< (+ @SX (:w vs)) (+ x w)) (reset! SX (- (+ x w) (:w vs))))
      (cond (< y @SY)                   (reset! SY y)
            (< (+ @SY (:h vs)) (+ y h)) (reset! SY (- (+ y h) (:h vs))))
      (swap! SX #(js/Math.max 0 %))
      (swap! SY #(js/Math.max 0 %))
      (render!) (request-view! false))))

(defn- jump! [a]
  (when-let [p (addr/parse a)]
    (reset! SX (axis-pos (:ci p) CW (:colw (mta))))   ; park at top-left; /view extends totals
    (reset! SY (axis-pos (:ri p) RH (:rowh (mta))))
    (select! (addr/make (:ci p) (:ri p)))
    (render!) (request-view! true)))

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
      (set! (.. ed -style -left)   (str (- (axis-pos (:ci p) CW (:colw m)) (axis-pos (:cb m) CW (:colw m))) "px"))
      (set! (.. ed -style -top)    (str (- (axis-pos (:ri p) RH (:rowh m)) (axis-pos (:rb m) RH (:rowh m))) "px"))
      (set! (.. ed -style -width)  (str (- (axis-size (:ci p) CW (:colw m)) 1) "px"))
      (set! (.. ed -style -height) (str (- (axis-size (:ri p) RH (:rowh m)) 1) "px"))
      (emit! "sr-edit" #js {:addr a})        ; sets $sel,$v,$edit=true,@post('/presence')
      ;; defer focus until Datastar has flipped $edit (data-show) + filled $v
      (js/setTimeout (fn [] (.focus ed) (.select ed)) 0))))

;; --- keyboard navigation ----------------------------------------------------
;; Arrows / Tab move the selection; Enter opens the editor. Ignored whenever any
;; input is focused (the editor + toolbar fields own their own keys).

(defn- on-key [e]
  (let [ae (.-activeElement js/document)
        tag (and ae (.-tagName ae))]
    (when-not (#{"INPUT" "TEXTAREA" "SELECT"} tag)
      (let [sel (cur-sel)]
        (if-not sel
          (when (#{"ArrowRight" "ArrowLeft" "ArrowUp" "ArrowDown" "Tab" "Enter"} (.-key e))
            (.preventDefault e) (select! "A1") (ensure-visible! "A1"))
          (let [p (addr/parse sel) k (.-key e)]
            (if (= k "Enter")
              (do (.preventDefault e) (start-edit! sel))
              (let [ci (:ci p) ri (:ri p)
                    [ci ri] (case k
                              "ArrowRight" [(inc ci) ri]
                              "ArrowLeft"  [(js/Math.max 0 (dec ci)) ri]
                              "ArrowDown"  [ci (inc ri)]
                              "ArrowUp"    [ci (js/Math.max 0 (dec ri))]
                              "Tab"        [(if (.-shiftKey e) (js/Math.max 0 (dec ci)) (inc ci)) ri]
                              nil)]
                (when ci
                  (.preventDefault e)
                  (let [na (addr/make ci ri)] (select! na) (ensure-visible! na)))))))))))

;; --- column / row resize ----------------------------------------------------
;; Header strips render a thin .colgrip/.rowgrip on each trailing edge. Dragging
;; one moves a single guide line; on release we emit one atomic "axis:idx:size"
;; command (sr-size -> @post '/size'). The server stores px + re-renders.

(def ^:private MINSZ 24)

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
                 base  (if col? CW RH)
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
                         (let [cur (if col? (.-clientX e2) (.-clientY e2))]
                           (reset! sz (js/Math.max MINSZ (js/Math.round (+ start-sz (- cur start)))))
                           (place cur)))
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
    (when m (.observe (js/MutationObserver. render!) m #js {:attributes true}))
    (render!)                             ; page already rendered the window at (0,0)
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
