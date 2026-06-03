(ns calcloj.web
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
            [calcloj.addr :as addr]
            [calcloj.sheet :as sheet]
            [calcloj.store :as store]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]))

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

(defn- xpos [ci] (+ GUT (* ci CW)))   ; cells/col-headers: left
(defn- ypos [ri] (* ri RH))           ; cells/row-headers: top (col strip is separate)

(defn- window
  "Visible cell coords [ci-range ri-range] for first row/col r0 c0 (clamped)."
  [r0 c0]
  (let [c0 (max 0 (- (long c0) OVER))
        r0 (max 0 (- (long r0) OVER))]
    [(range c0 (min MAX-COLS (+ c0 WIN-COLS)))
     (range r0 (min MAX-ROWS (+ r0 WIN-ROWS)))]))

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
                              :dims nil :last-seen (now)}))

(defn- touch! [sid] (when (@sessions* sid) (swap! sessions* assoc-in [sid :last-seen] (now))))

(defn- ensure-session!
  "Lazily (re)register a session for an active request, then stamp it alive. A
   client whose session was swept (crash/sleep TTL) transparently comes back."
  [sid sheet-id]
  (when (and sid (re-matches sid-re (str sid)))
    (when-not (@sessions* sid) (register-session! sid sheet-id))
    (touch! sid)))

(declare close-gen!)
(defn- reap-session!
  "Drop a session: close its push stream and unload the sheet if it was last."
  [sid]
  (when-let [s (@sessions* sid)]
    (close-gen! s)
    (swap! sessions* dissoc sid)
    (unload-sheet! (:sheet s))))

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

(defn- spacer-dims
  "Spacer [w h] px: covers used range AND the current view, plus a buffer, so
   the scrollbar extends just past real content but you can always scroll on."
  [sh r0 c0]
  (let [[cm rm] (used-max sh)
        cols (min MAX-COLS (+ (max MIN-COLS (inc cm) (+ (long c0) WIN-COLS)) BUF-COLS))
        rows (min MAX-ROWS (+ (max MIN-ROWS (inc rm) (+ (long r0) WIN-ROWS)) BUF-ROWS))]
    [(+ GUT (* cols CW)) (* rows RH)]))

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
  "Minimal per-cell HTML: class (shared CSS) + position only. Focus/blur/change
   are delegated on #cells (handlers below), so cells stay tiny -> small SSE."
  [sh a ci ri]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)]
    [:input {:id (cell-id a) :class "cell"
             :value disp :data-raw raw :data-val disp
             :style (format "left:%dpx;top:%dpx" (xpos ci) (ypos ri))}]))

(defn- cells-html [sh cis ris]
  (str (h/html (for [ri ris ci cis] (cell-input sh (addr/make ci ri) ci ri)))))

(defn- colhead-html [cis]
  (str (h/html
        (for [ci cis]
          [:div {:style (format (str "position:absolute;left:%dpx;top:0;width:%dpx;height:%dpx;"
                                     "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                     "border:1px solid #e0e0e0;font:12px sans-serif;")
                                (xpos ci) CW HDR HDR)}
           (addr/idx->col ci)]))))

(defn- rowhead-html [ris]
  (str (h/html
        (for [ri ris]
          [:div {:style (format (str "position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx;"
                                     "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                     "border:1px solid #e0e0e0;font:12px sans-serif;")
                                (ypos ri) GUT RH RH)}
           (inc ri)]))))

(defn- grid-layers
  "Inner content of #scroll: sticky headers + a dynamically-sized spacer +
   the visible window. Spacer grows with used range / view, not a fixed huge
   sheet. Delegation handlers live on #scroll (parent), so re-rendering this
   does not drop them."
  [sh {:keys [r0 c0]}]
  (let [[cis ris] (window r0 c0)
        [w h] (spacer-dims sh r0 c0)]
    (h/html
     [:div {:id "colstrip"
            :style (format (str "position:sticky;top:0;z-index:2;height:%dpx;width:%dpx;"
                                "background:#f3f3f3;") HDR w)}
      [:div {:id "corner"
             :style (format (str "position:sticky;left:0;z-index:3;width:%dpx;height:%dpx;"
                                 "background:#e8e8e8;display:inline-block;") GUT HDR)}]
      [:div {:id "colhead"} (h/raw (colhead-html cis))]]
     [:div {:id "space"
            :style (format "position:relative;width:%dpx;height:%dpx;" w h)}
      [:div {:id "rowstrip"
             :style (format (str "position:sticky;left:0;z-index:1;width:%dpx;height:%dpx;"
                                 "background:#f3f3f3;float:left;") GUT h)}
       [:div {:id "rowhead"} (h/raw (rowhead-html ris))]]
      [:div {:id "cells"} (h/raw (cells-html sh cis ris))]])))

(defn- page [sh id]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "calcloj"]
      [:style (h/raw (format (str "input.cell{position:absolute;width:%dpx;height:%dpx;"
                                  "box-sizing:border-box;border:1px solid #ddd;"
                                  "padding:2px 4px;font:13px monospace;}")
                             (- CW 1) (- RH 1)))]
      [:script {:type "module" :src "/datastar.js"}]
      [:script {:src "/app.js"}]]
     [:body {:data-signals (format "{cell:'', v:'', err:'', sel:'', bar:'', r0:0, c0:0, sheet:'%s', sid:''}" id)
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
       [:input {:id "fbar" :data-bind:bar "" :placeholder "value or =formula"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, $v=$bar, @post('/cell'))"
                :data-on:blur "$cell=$sel, $v=$bar, @post('/cell')"
                :style (str "flex:1;font:13px monospace;padding:5px 8px;border:1px solid #bbb;"
                            "border-radius:4px;")}]]
      ;; scroll viewport. Delegated cell handlers live here (parent of #cells)
      ;; so re-rendering the grid inner keeps them. focus/blur use focusin/out.
      [:div {:id "scroll"
             :data-cw CW :data-rh RH :data-gut GUT     ; geometry for /app.js
             :data-on:scroll__debounce.120ms
             (format "$r0=Math.floor(el.scrollTop/%d); $c0=Math.floor(el.scrollLeft/%d); @post('/view')" RH CW)
             :data-on:focusin
             (str "evt.target.classList.contains('cell') && "
                  "($sel=evt.target.id.slice(2), $bar=evt.target.dataset.raw, "
                  "evt.target.value=evt.target.dataset.raw)")
             :data-on:focusout
             "evt.target.classList.contains('cell') && (evt.target.value=evt.target.dataset.val)"
             :data-on:change
             (str "evt.target.classList.contains('cell') && "
                  "($cell=evt.target.id.slice(2), $v=evt.target.value, $bar=$v, @post('/cell'))")
             :style "height:78vh;overflow:auto;border:1px solid #ccc;position:relative;"}
       (grid-layers sh {:r0 0 :c0 0})]]])))

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
      :else m)))

(defn- sheet-id-of [{:keys [sheet]}]
  (if (store/valid-id? sheet) sheet "default"))

(defn- qparam [req k]
  (some->> (:query-string req)
           (re-find (re-pattern (str "(?:^|&)" k "=([^&]+)")))
           second))

(defn- close-gen! [s]
  (when-let [g (:gen s)] (try (d*/close-sse! g) (catch Throwable _))))

(defn- render-cells [sh addrs]
  (apply str (map #(str (h/html (cell-input sh % (:ci (addr/parse %)) (:ri (addr/parse %)))))
                  addrs)))

(defn- broadcast!
  "Push changed cells to OTHER sessions on the same sheet, each scoped to that
   session's own viewport. Collaboration: a peer sees your edit live. A write to
   a dead stream throws -> reap that session."
  [editor-sid sheet-id sh affected]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid editor-sid) (= sheet-id (:sheet s)) (:gen s))
      (let [vis (filter #(in-window? (:view s) %) affected)]
        (when (seq vis)
          (try (d*/lock-sse! (:gen s) (d*/patch-elements! (:gen s) (render-cells sh vis)))
               (catch Throwable _ (reap-session! sid))))))))

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
                  (d*/patch-elements! gen (render-cells sh visible)))
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
        (let [new-dims (spacer-dims sh (:r0 view) (:c0 view))]
          (if (= (session-dims sid) new-dims)
            ;; bounds unchanged: cheap inner patch of window + headers
            (let [[cis ris] (window (:r0 view) (:c0 view))]
              (patch-inner! gen "#cells"   (cells-html sh cis ris))
              (patch-inner! gen "#colhead" (colhead-html cis))
              (patch-inner! gen "#rowhead" (rowhead-html ris)))
            ;; bounds grew (reached edge / jumped): re-render grid (resizes spacer).
            (do (set-session-dims! sid new-dims)
                (patch-inner! gen "#scroll" (str (grid-layers sh view))))))))))

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
           (register-session! sid sheet-id)
           (swap! sessions* assoc-in [sid :gen] gen)))})))

(defn- body-json [req]
  (when-let [b (:body req)]
    (json/read-value (slurp b) json/keyword-keys-object-mapper)))

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
                                   :loaded-sheets (vec (keys @sheets*))})}
    [:post "/cell"]       (handle-cell req)
    [:post "/view"]       (handle-view req)
    {:status 404 :body "not found"}))

(defonce ^:private server* (atom nil))

(defn start! [& [port]]
  (when @server* (@server*))
  (start-sweeper!)                          ; reap idle/orphan sessions
  (reset! server* (http/run-server #'app {:port (or port 8080)}))
  (println "calcloj on http://localhost:" (or port 8080)))

(defn -main [& _] (start!) @(promise))
