(ns uno.michelada.saltrim.web.handlers
  "Request handlers + access gates: resolve identity/room, mutate the sheet under
   the edit lock, persist, and push changes. Plus the auth routes and root page."
  (:require
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.export :as export]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.graph :as graph]
            [uno.michelada.saltrim.merge :as mrg]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [uno.michelada.saltrim.web.geom :refer [in-window? pretty-err qparam url-decode url-encode window]]
            [uno.michelada.saltrim.web.state :refer [accessible-rec can-read? def-editor-of locked-by-other? now owner-of save-rec! session-view sessions* set-session-view! sheets* sid-re unload-sheet!]]
            [uno.michelada.saltrim.web.sse :refer [patch-inner! read-signals signals! sse sse-opts webkit-ua?]]
            [uno.michelada.saltrim.web.render :refer [cells-html colhead-html denied-page graph-svg login-page merge-result-html meta-html page prop-allowed? render-cells rowhead-html self-html share-html]]
            [uno.michelada.saltrim.web.collab :refer [broadcast! broadcast-deflib-except! broadcast-presence! broadcast-window! ensure-session! push-deflib! reap-session! render-window!]]))

(def ^:private edit-lock (Object.))

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

(defn- sig-at
  "The as-of transaction from request signals ($at) as a long, or nil for live."
  [sig]
  (some-> (:at sig) str not-empty parse-long))

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
      ;; a request carrying $at is a read-only as-of view: force :read so every
      ;; edit handler's write-guard rejects it (it targets the live room, which
      ;; the as-of view must never mutate).
      (let [level (if (sig-at sig) :read (level-of uid sheet-id token rec))
            rec   (assoc rec :level level :token token
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

(defn- record-structural!
  "Push a structural undo entry (a whole row/col insert/delete) — undone/redone as
   ONE step by sheet/undo-step. Clears redo, like record-edit!."
  [sid op axis at]
  (when (@sessions* sid)
    (swap! sessions* update sid
           (fn [s] (-> s
                       (update :undo #(vec (take-last UNDO-CAP (conj (or % []) {:op op :axis axis :at at}))))
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
          (cond
            (= affected :all)            ; a structural (insert/delete) step — re-render all
            (do (render-window! gen sid (:room rec) sh (session-view sid))
                (broadcast-window! sid (:room rec) sh)
                (signals! gen {:err ""}))
            affected (push-changes! gen sid (:room rec) sh affected)
            :else    (signals! gen {:err ""})))))))  ; nothing (un)doable — silent

(defn handle-undo [req] (handle-undo* req :undo))
(defn handle-redo [req] (handle-undo* req :redo))

(defn handle-cell [req]
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

(declare selected-cells)

(defn handle-style
  "Set a style/format/label prop. Applies to the WHOLE selection when $selcells
   spans more than one cell (so you can style a rectangle in one go); otherwise to
   the single active $cell. Per-cell undo; one settle/save; re-render the touched
   cells + broadcast."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [cell sid selcells] :as sig} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh    (:sh rec)
            prop  (keyword (:styleprop sig))
            src   (str (:stylesrc sig))
            sel   (selected-cells selcells)
            cells (if (> (count sel) 1) sel (when (addr/valid? cell) [cell]))]
        (cond
          (not= :read-write (:level rec))
          (signals! gen {:err "read-only access — you can't edit this sheet"})
          (not (prop-allowed? prop))
          (signals! gen {:err (str "unknown style property: " (:styleprop sig))})
          (empty? cells)
          (signals! gen {:err "select a cell first"})
          :else
          (locking edit-lock
            (try
              (doseq [c cells]
                (let [before (get (sheet/style-srcs sh c) prop)]
                  (sheet/set-style! sh c prop src)
                  (record-edit! sid c prop before (get (sheet/style-srcs sh c) prop))))
              (sheet/settle! sh)
              (save-rec! (:room rec) uid)
              (push-changes! gen sid (:room rec) sh cells)
              (catch Throwable e
                (signals! gen {:err (str (name prop) " style: "
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

(defn handle-clear
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

(defn handle-copy [req]
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

(defn handle-paste [req]
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

(defn handle-cut [req]
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

(defn handle-view [req]
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

(defn handle-viewat
  "Read-only scroll for an as-of view: re-render the window of (sheet, branch) AS
   OF $at from a TRANSIENT historical sheet — no room, no session, no presence,
   never mutates. Built per request (the snapshot is request-scoped)."
  [req]
  (let [uid      (auth/req->uid req)
        sig      (read-signals req)
        sheet-id (:sheet sig)
        branch   (sig-branch sig)
        at       (sig-at sig)
        token    (not-empty (str (:link sig)))
        r0 (max 0 (long (or (:r0 sig) 0)))
        c0 (max 0 (long (or (:c0 sig) 0)))]
    (if-not (and at (can-read? uid sheet-id branch token))
      (deny req "no access")
      (if-let [{:keys [sh]} (store/load-record-asof sheet-id branch at)]
        (sse req (fn [gen]
                   (try
                     (let [[cis ris] (window r0 c0)]
                       (patch-inner! gen "#cells"   (cells-html sh cis ris))
                       (patch-inner! gen "#colhead" (colhead-html sh cis))
                       (patch-inner! gen "#rowhead" (rowhead-html sh ris))
                       (d*/patch-elements! gen (meta-html sh r0 c0)))   ; #meta by id
                     (finally (sheet/close! sh)))))
        (deny req "no such revision")))))

(defn handle-size [req]
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

(defn handle-insert
  "Insert a blank row/column relative to the active cell ($sel). `$insertdir` ∈
   top|bottom|left|right → (axis, index). `sheet/insert-line!` shifts cells +
   follows formula references; recorded as ONE structural undo step. Re-renders
   the whole window for everyone (positions all change)."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid sel insertdir]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh (:sh rec)]
        (cond
          (not= :read-write (:level rec))
          (signals! gen {:err "read-only access — you can't edit this sheet"})
          (not (addr/valid? sel))
          (signals! gen {:err "select a cell first"})
          :else
          (let [{:keys [ci ri]} (addr/parse sel)
                [axis at] (case (str insertdir)
                            "top"    [:row ri]
                            "bottom" [:row (inc ri)]
                            "left"   [:col ci]
                            "right"  [:col (inc ci)]
                            [nil nil])]
            (if-not axis
              (signals! gen {:err ""})
              (locking edit-lock
                (try
                  (sheet/insert-line! sh axis at)
                  (sheet/settle! sh)
                  (record-structural! sid :insert axis at)
                  (save-rec! (:room rec) uid)
                  (render-window! gen sid (:room rec) sh (session-view sid))
                  (broadcast-window! sid (:room rec) sh)
                  (signals! gen {:err ""})
                  (catch Throwable e
                    (signals! gen {:err (pretty-err (.getMessage e))})))))))))))

(defn handle-props
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

(defn handle-deflock
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

(defn handle-defunlock
  "Release this session's edit lock without saving."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (swap! sessions* assoc-in [sid :editdef] nil)
      (signals! gen {:defid "" :defsrc ""})
      (push-deflib! gen sid (:room rec))
      (broadcast-deflib-except! sid (:room rec)))))

(defn handle-defsave
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

(defn handle-defadd
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

(defn handle-defdel
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

(defn handle-presence
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

(defn handle-stream
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

(defn handle-session-end [req]
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

(defn handle-share
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

(defn handle-branch
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

;; --- merge (PR B): 3-way against the recorded fork point -------------------

(defn- split-keys [s] (remove str/blank? (str/split (str s) #"\s+")))

(defn- apply-merge!
  "Write the merge `actions` ({[addr prop] src|DELETE}) onto the target engine,
   recording per-property undo. Value props go through set-cell!, style props
   through set-style!; a DELETE clears (empty src). Returns affected addrs."
  [sh sid actions]
  (let [affected (atom #{})]
    (doseq [[[addr prop] v] actions
            :let [src (if (= v mrg/DELETE) "" v)]]
      (if (= prop :value)
        (let [before (sheet/raw sh addr)]
          (sheet/set-cell! sh addr src)
          (record-edit! sid addr :value before (sheet/raw sh addr))
          (swap! affected into (cons addr (sheet/dependents* sh addr))))
        (let [before (get (sheet/style-srcs sh addr) prop)]
          (sheet/set-style! sh addr prop src)
          (record-edit! sid addr prop before (get (sheet/style-srcs sh addr) prop))
          (swap! affected conj addr))))
    (distinct @affected)))

(defn handle-merge
  "Owner-only 3-way merge of `$mergefrom` (source) INTO the current branch
   (target), against the common-ancestor doc (`db/merge-base` via fork lineage +
   as-of). `$branchact`:
   - preview: compute the plan, patch #mergeresult (clean count + conflict picker).
   - apply:   auto-merge the non-conflicting props plus the conflicts whose key is
              in $mergetake (take source); write to the target engine, settle,
              save, broadcast, toast."
  [req]
  (with-owner req
    (fn [uid sheet-id rec {:keys [sid branchact mergefrom mergetake]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid)
      (let [target (:branch rec)
            source (str mergefrom)
            sh     (:sh rec)]
        (cond
          (not (store/valid-branch? source))        (signals! gen {:err "pick a branch to merge"})
          (= source target)                         (signals! gen {:err "can't merge a branch into itself"})
          (not (db/branch-exists? sheet-id source))  (signals! gen {:err (str "no branch \"" source "\"")})
          :else
          (let [base (db/merge-base sheet-id source target)]
            (if (nil? base)
              (signals! gen {:err "no common ancestor — these branches can't be 3-way merged"})
              (let [plan (mrg/plan base (db/sheet-doc sheet-id source) (sheet/document sh))]
                (case (str branchact)
                  "preview"
                  (d*/patch-elements! gen (merge-result-html source target plan))
                  "apply"
                  (locking edit-lock
                    (let [acts     (mrg/actions plan (split-keys mergetake))
                          affected (apply-merge! sh sid acts)]
                      (if (empty? acts)
                        (signals! gen {:err (str "🌿 " target " is already up to date") :mergetake ""})
                        (do (sheet/settle! sh)
                            (save-rec! (:room rec) uid)
                            (render-window! gen sid (:room rec) sh (session-view sid))
                            (broadcast-window! sid (:room rec) sh)
                            (d*/patch-elements! gen (str (h/html [:div {:id "mergeresult"}])))
                            (signals! gen {:err (str "merged " (count acts) " cell-propert"
                                                     (if (= 1 (count acts)) "y" "ies") " from 🌿 " source)
                                           :mergetake "" :mergefrom "" :branchpanel false})))))
                  (signals! gen {:err ""}))))))))))

;; --- dependency-graph view ------------------------------------------------

(defn handle-graph
  "Render the current (sheet,branch) dependency graph into #graphview. Any reader
   (incl. read-only viewers) may view it. Capped — a huge sheet is unreadable as
   a graph, so we show a count instead of drawing thousands of nodes."
  [req]
  (with-access req
    (fn [uid sheet-id rec {:keys [sid]} gen]
      (ensure-session! sid sheet-id (:branch rec) uid (:token rec))
      (let [sh       (:sh rec)
            deps-map (into {} (for [a (sheet/cells sh)
                                    :let [ds (sheet/deps sh a)] :when (seq ds)]
                                [a ds]))
            {:keys [nodes] :as g} (graph/build deps-map)
            inner (cond
                    (empty? nodes)
                    (str (h/html [:p {:style "color:var(--muted);"}
                                  "No dependencies yet — write a formula that references "
                                  "another cell, e.g. " [:span {:style "font-family:monospace;"} "=(+ $A1 1)"] "."]))
                    (> (count nodes) 250)
                    (str (h/html [:p {:style "color:var(--muted);"}
                                  (str "This sheet has " (count nodes) " connected cells — too many to "
                                       "draw usefully yet. Filtering / zoom is a future improvement.")]))
                    :else (graph-svg sh g))]
        (patch-inner! gen "#graphview" inner)))))

(defn- redirect [loc & [set-cookie]]
  (cond-> {:status 303 :headers {"Location" loc}}
    set-cookie (assoc-in [:headers "Set-Cookie"] set-cookie)))

(defn auth-routes
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

(defn handle-root [req]
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
            ;; &at=<tx> → a READ-ONLY snapshot of (sheet, branch) as of that tx.
            at          (some-> (qparam req "at") parse-long)]
        (cond
          ;; as-of view: render a transient historical sheet (no live room).
          (and id at (can-read? uid id branch token))
          (if-let [{:keys [sh]} (store/load-record-asof id branch at)]
            (try {:status 200 :headers {"Content-Type" "text/html"}
                  :body (page sh id sname branch at uid token)}
                 (finally (sheet/close! sh)))   ; historical sheet is request-scoped
            {:status 403 :headers {"Content-Type" "text/html"} :body (denied-page uid)})

          ;; live view
          :else
          (let [rec (when id (accessible-rec uid id branch token))]
            (if rec
              {:status 200 :headers {"Content-Type" "text/html"}
               :body (page (:sh rec) id sname branch nil uid token)}
              {:status 403 :headers {"Content-Type" "text/html"}
               :body (denied-page uid)})))))))

(defn- xlsx-response [^bytes b sname]
  (let [fname (let [s (str/replace (str (or sname "sheet")) #"[^A-Za-z0-9_.-]" "_")]
                (if (str/blank? s) "sheet" s))]
    {:status 200
     :headers {"Content-Type"        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
               "Content-Disposition" (str "attachment; filename=\"" fname ".xlsx\"")
               "Content-Length"      (str (count b))}
     :body (java.io.ByteArrayInputStream. b)}))

(defn handle-export
  "GET /export.xlsx — download a STATIC .xlsx of the (sheet, branch[, as-of])
   the user can read. Mirrors handle-root's access resolution (?t / ?s / ?u / ?b /
   ?at). Read-only: builds the workbook from the live room engine (or a transient
   as-of snapshot) without mutating anything. Formulas are exported as their
   computed values — see the export ns."
  [req]
  (let [uid (auth/req->uid req)]
    (if-not uid
      {:status 403 :body "not signed in"}
      (let [token      (not-empty (qparam req "t"))
            [id sname] (if token
                         (when-let [tid (db/sheet-by-link-token token)]
                           [tid (second (store/split-id tid))])
                         (let [sname (let [s (qparam req "s")] (if (store/valid-name? s) s "default"))
                               owner (let [o (qparam req "u")] (when (and o (re-matches auth/uid-re o)) o))]
                           [(store/storage-id (or owner uid) sname) sname]))
            branch     (let [b (qparam req "b")
                             b (if (store/valid-branch? b) b db/MAIN)]
                         (if (and id (db/branch-exists? id b)) b db/MAIN))
            at         (some-> (qparam req "at") parse-long)]
        (cond
          (nil? id) {:status 403 :body "no access"}

          (and at (can-read? uid id branch token))
          (if-let [{:keys [sh]} (store/load-record-asof id branch at)]
            (try (xlsx-response (export/workbook-bytes sh sname) sname)
                 (finally (sheet/close! sh)))
            {:status 403 :body "no access"})

          :else
          (if-let [rec (accessible-rec uid id branch token)]
            (xlsx-response (export/workbook-bytes (:sh rec) sname) sname)
            {:status 403 :body "no access"}))))))

