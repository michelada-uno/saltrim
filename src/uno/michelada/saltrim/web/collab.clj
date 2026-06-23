(ns uno.michelada.saltrim.web.collab
  "Live collaboration: session lifecycle (register/ensure/reap/sweep) and the
   per-room broadcasts (cells, presence, window, definitions) + the /stream push
   generator. reap<->broadcast are mutually recursive, so they live together."
  (:require
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [starfederation.datastar.clojure.api :as d*]
            [uno.michelada.saltrim.web.geom :refer [in-window? window]]
            [uno.michelada.saltrim.web.state :refer [SESSION-TTL-MS color-for now sessions* sessions-on sheet-rec sid-re touch! unload-sheet!]]
            [uno.michelada.saltrim.web.sse :refer [patch-inner! webkit-flush!]]
            [uno.michelada.saltrim.web.render :refer [cells-html colhead-html deflib-html meta-html peers-html render-cells rowhead-html self-html]]))

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

(defn ensure-session!
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
(defn reap-session!
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

(defn sweep! []
  (let [cutoff (- (now) SESSION-TTL-MS)]
    (doseq [[sid s] @sessions*]
      (when (< (long (:last-seen s 0)) cutoff)
        (reap-session! sid)))))

;; The session sweeper IS its mount state: the state value is the scheduled
;; executor pool; :stop shuts it down. (declared lower, with `server`, once
;; sweep! is defined.)

(defn- close-gen! [s]
  (when-let [g (:gen s)] (try (d*/close-sse! g) (catch Throwable _))))

(defn broadcast!
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

(defn broadcast-presence!
  "Re-render the #peers overlay for every session in `room` (each scoped to its
   own view). Called whenever any cursor / editing state changes."
  [room]
  (doseq [[sid s] @sessions*]
    (when (and (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#peers" (peers-html sid room)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

(defn render-window!
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

(defn broadcast-window!
  "A resize shifts every position, so re-render each OTHER session in the room's
   whole window on its own stream (scoped to that session's view)."
  [editor-sid room sh]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid editor-sid) (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (render-window! (:gen s) sid room sh (:view s)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

(defn push-deflib! [gen sid room]
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

(defn broadcast-deflib-except!
  "Like broadcast-deflib! but skips `except-sid` (whose own #deflib the calling
   handler already patched on its one-shot response)."
  [except-sid room]
  (doseq [[sid s] @sessions*]
    (when (and (not= sid except-sid) (= room (:room s)) (:gen s))
      (try (d*/lock-sse! (:gen s) (patch-inner! (:gen s) "#deflib" (deflib-html sid room)))
           (when (:webkit? s) (webkit-flush! sid))
           (catch Throwable _ (reap-session! sid))))))

