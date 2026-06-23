(ns uno.michelada.saltrim.web.state
  "In-memory web state: the loaded-sheet and session registries (keyed by the
   (sheet,branch) room) plus pure accessors. No rendering, no broadcasts."
  (:require
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]))

(defonce sheets* (atom {}))

(defn sheet-rec
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

(defn save-rec!
  "Persist a loaded room's content to the db (branch-scoped), authored by
   `author` uid (the acting user — recorded per changed cell for per-user undo).
   `author` is nil for non-edit autosaves (e.g. the save on session unload).
   `room` = [storage-id branch]."
  ([room] (save-rec! room nil))
  ([[id branch :as room] author]
   (when-let [{:keys [sh]} (@sheets* room)]
     (store/save! id sh {:author author :branch branch}))))

(defn accessible-rec
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

(defn can-read?
  "Whether `uid` (with optional link `token`) may READ (id, branch): the sheet is
   registered, the branch exists, and the user owns it or has ACL access. Used by
   the read-only as-of views, which render a transient historical sheet WITHOUT
   loading or registering a live room."
  [uid id branch token]
  (boolean
   (and uid (store/valid-id? id) (store/exists? id) (db/branch-exists? id branch)
        (let [[owner _] (store/split-id id)]
          (or (= owner uid) (db/access-level uid id token))))))

;; Sessions: one per client. Hold the sheet id + per-session viewport (view/dims)
;; so concurrent clients on the same sheet keep independent scroll. Each carries
;; :last-seen (ms) — real activity (edits/scrolls) refreshes it; a server-side
;; sweep reaps sessions idle past a TTL (the only way to handle crash/sleep,
;; where the beacon never fires). No client heartbeat.
(defonce sessions* (atom {}))  ; sid -> {:sheet :view :dims :last-seen}

(def sid-re #"[A-Za-z0-9-]{1,64}")
(def SESSION-TTL-MS (* 30 60 1000))   ; reap sessions idle > 30 min
(def SWEEP-MS 60000)                  ; check once a minute

(defn now [] (System/currentTimeMillis))

;; Per-session presence: a stable color + the cell the user is on (:cursor) and,
;; while actively typing, the cell they are editing (:editing -> locks it for
;; others). Colors are assigned deterministically from the sid so a reconnect
;; keeps the same color.
(def palette
  ["#e6194b" "#3cb44b" "#4363d8" "#f58231" "#911eb4"
   "#008080" "#f032e6" "#9a6324" "#46827d" "#808000"])
(defn color-for [sid] (nth palette (mod (Math/abs (long (hash sid))) (count palette))))
(defn session-view [sid] (get-in @sessions* [sid :view] {:r0 0 :c0 0}))
(defn set-session-view! [sid v] (when (@sessions* sid) (swap! sessions* assoc-in [sid :view] v)))

(defn sessions-on
  "How many live sessions are in `room` (= [sheet-id branch])."
  [room]
  (count (filter #(= room (:room %)) (vals @sessions*))))

(defn unload-sheet!
  "Save then release the engine for a room whose last session just left."
  [room]
  (when (and (zero? (sessions-on room)) (@sheets* room))
    (let [{:keys [sh]} (@sheets* room)]
      (save-rec! room)                   ; autosave on unload — no acting user
      (sheet/close! sh)
      (swap! sheets* dissoc room))))

(defn touch! [sid] (when (@sessions* sid) (swap! sessions* assoc-in [sid :last-seen] (now))))

(defn locked-by-other?
  "Is `cell` currently being edited by some other session IN THE SAME ROOM?"
  [sid room cell]
  (boolean (some (fn [[k s]]
                   (and (not= k sid) (= room (:room s)) (= (:editing s) cell)))
                 @sessions*)))

;; --- definitions library (per-chunk, collaboratively locked) ------------

(defn def-editor-of
  "The sid currently editing definition chunk `id` in `room`, or nil. (Defs are
   per-branch, so the lock is per-room.)"
  [room id]
  (some (fn [[k s]] (when (and (= room (:room s)) (= (:editdef s) id)) k))
        @sessions*))

(defn owner-of [id] (first (store/split-id id)))

