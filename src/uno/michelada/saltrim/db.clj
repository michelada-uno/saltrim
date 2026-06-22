(ns uno.michelada.saltrim.db
  "Datahike-backed registry for identity and metadata. Owns users and auth
   tokens now; sheet metadata + shares move here next. Sheet CELL data stays in
   the file store (see `store`).

   Backend is env-driven (`config`):
   - default (dev/staging): H2 file at `data/saltrim-h2`
   - prod: a full JDBC url via SALTRIM_DB_JDBC_URL (YugabyteDB — Postgres-wire,
     served by the com.yugabyte/jdbc-yugabytedb driver)
   - tests: in-memory (`SALTRIM_DB_BACKEND=mem`, or `start-mem!` via mount)

   We keep history (`:keep-history? true`) deliberately — `as-of` underpins the
   planned audit/branching features. Tokens are stored as a SHA-256 hash of the
   cookie secret (the cookie carries the secret; the DB never sees it)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [konserve-jdbc.core]                      ; registers the konserve :jdbc store
            [konserve.store :refer [validate-store-config]]
            [mount.core :as mount :refer [defstate]]
            [uno.michelada.saltrim.util :as util :refer [timed]]))

(defn- env [k] (util/env k))
(defn- now [] (System/currentTimeMillis))

;; --- schema ---------------------------------------------------------------

(def schema
  [{:db/ident :user/uid        :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :user/name       :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :user/email      :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :user/avatar     :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :user/provider   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :user/created-at :db/valueType :db.type/long    :db/cardinality :db.cardinality/one}

   {:db/ident :token/hash       :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :token/user       :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :token/created-at :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :token/last-seen  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}

   ;; sheets + shares — schema defined now, wired in the next step
   {:db/ident :sheet/id         :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :sheet/owner      :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :sheet/name       :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :sheet/created-at :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}

   {:db/ident :share/sheet        :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :share/grantee      :db/valueType :db.type/string  :db/cardinality :db.cardinality/one} ; uid | group-id | link-token
   {:db/ident :share/grantee-kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :user | :group | :link (:everyone = legacy, migrated)
   {:db/ident :share/level        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :read | :read-write
   {:db/ident :share/created-at   :db/valueType :db.type/long    :db/cardinality :db.cardinality/one}

   ;; sheet CONTENT as datoms (ROADMAP boss fight: cells leave file EDN). The
   ;; unit of edit / history / branching is ONE PROPERTY of a cell on a branch:
   ;; (sheet, branch, addr, prop) -> src. The cell's value is just the :value
   ;; prop; each style prop (:bg/:fg/:format/…) is its own cellprop, so adding a
   ;; style needs no schema change. :cellprop/author (current writer uid) enables
   ;; per-user selective undo; the change TIME is Datahike's built-in :db/txInstant.
   {:db/ident :cellprop/key    :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one} ; "<sheet>|<branch>|<addr>|<prop>"
   {:db/ident :cellprop/sheet  :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/branch :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/addr   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/prop   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/src    :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/author :db/valueType :db.type/string  :db/cardinality :db.cardinality/one} ; uid of the current writer (undo)

   ;; per-(sheet, branch) CONTENT scalars that vary by branch — the axis-size
   ;; defaults + sparse maps + definitions library (edn-string blobs).
   {:db/ident :branch/key    :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one} ; "<sheet>|<branch>"
   {:db/ident :branch/sheet  :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :branch/name   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :branch/dcw    :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :branch/drh    :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :branch/cols   :db/valueType :db.type/string :db/cardinality :db.cardinality/one} ; edn {ci width}
   {:db/ident :branch/rows   :db/valueType :db.type/string :db/cardinality :db.cardinality/one} ; edn {ri height}
   {:db/ident :branch/defs   :db/valueType :db.type/string :db/cardinality :db.cardinality/one} ; edn [chunk …]
   ;; fork lineage — the branch this was forked FROM and the db's :max-tx at the
   ;; fork instant. Together they pin the fork POINT: the 3-way merge base is the
   ;; parent branch's document `as-of` base-tx (the common ancestor). main has
   ;; neither (it is the root). See spikes/05-branching.clj.
   {:db/ident :branch/parent  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :branch/base-tx :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}])

;; --- connection -----------------------------------------------------------

;; konserve requires the store :id to be a UUID. For a persistent backend it
;; must be STABLE across restarts (else each boot is a fresh empty DB), so we
;; use a fixed default, overridable via SALTRIM_DB_ID.
(def ^:private default-id #uuid "c10a4a00-0000-4000-8000-000000000001")
(defn- store-id [] (if-let [s (env "SALTRIM_DB_ID")] (java.util.UUID/fromString s) default-id))

(defn- config []
  (cond
    (= "mem" (env "SALTRIM_DB_BACKEND"))
    {:store {:backend :memory :id (store-id)}
     :schema-flexibility :write :keep-history? true}

    (env "SALTRIM_DB_JDBC_URL")
    {:store {:backend :jdbc :id (store-id) :jdbcUrl (env "SALTRIM_DB_JDBC_URL")
             :table (or (env "SALTRIM_DB_TABLE") "saltrim")}
     :schema-flexibility :write :keep-history? true}

    :else
    {:store {:backend :jdbc :id (store-id) :dbtype "h2"
             :dbname (or (env "SALTRIM_DB_PATH") "data/saltrim-h2")
             :table "saltrim"}
     :schema-flexibility :write :keep-history? true}))

(defn- ensure-schema!
  "Install only the schema attributes not already present — so a fresh db gets
   the whole schema AND an existing db picks up newly-added attrs, without
   re-asserting (which would churn history) attrs it already has."
  [c]
  (let [have (set (d/q '[:find [?id ...] :where [_ :db/ident ?id]] @c))
        need (remove #(have (:db/ident %)) schema)]
    (when (seq need) (d/transact c (vec need)))))

(defn connect!
  "Connect a Datahike configuration (creating the database first if needed) and
   ensure the schema. Returns the connection."
  [cfg]
  (validate-store-config (:store cfg))
  (when-not (d/database-exists? cfg)
    (d/create-database cfg)
    ;; konserve-jdbc caches one c3p0 pool per db-spec, and create-database's
    ;; store release closes that pool ASYNCHRONOUSLY. Since `connect` reuses the
    ;; same spec (same pool), without this pause the freshly opened pool gets
    ;; closed under us on the first query. 300ms is the proven settle window.
    (when (= :jdbc (get-in cfg [:store :backend]))
      (Thread/sleep 300)))
  (let [c (d/connect cfg)]
    (ensure-schema! c)
    c))

(defn mem-config
  "A fresh, isolated in-memory config (unique store id) — for tests."
  []
  {:store {:backend :memory :id (random-uuid)}
   :schema-flexibility :write :keep-history? true})

;; The shared Datahike connection IS the mount state — callers use `conn`
;; directly (deref for the db value: `@conn`). No side atom. Tests start it with
;; a substituted mem value via mount/start-with (see the test fixtures).
(defstate conn
  :start (timed "db connection" (connect! (config)))
  :stop  (timed "db release" (d/release conn)))

(defn start-mem!
  "Start ONLY the `conn` state, substituted with a fresh in-memory db. For
   tests — `(mount/stop)` afterwards releases it. Scoped via mount/only so it
   never boots the http server even if web is loaded in the same JVM."
  []
  (-> (mount/only #{#'conn})
      (mount/swap {#'conn (connect! (mem-config))})
      mount/start))

;; --- users ----------------------------------------------------------------

(defn- ->user [m]
  (when m
    {:uid (:user/uid m) :name (:user/name m) :email (:user/email m)
     :avatar (:user/avatar m) :provider (:user/provider m)
     :created-at (:user/created-at m)}))

(defn user-info
  "Profile map for `uid`, or nil."
  [uid]
  (->user (d/q '[:find (pull ?e [*]) . :in $ ?uid :where [?e :user/uid ?uid]]
               @conn uid)))

(defn upsert-user!
  "Create or update a user by uid (created-at set only on first insert)."
  [{:keys [uid name email avatar provider]}]
  (let [exists? (some? (user-info uid))
        tx (cond-> {:user/uid uid}
             name        (assoc :user/name name)
             email       (assoc :user/email email)
             avatar      (assoc :user/avatar avatar)
             provider    (assoc :user/provider provider)
             (not exists?) (assoc :user/created-at (now)))]
    (d/transact conn [tx])
    uid))

;; --- auth tokens ----------------------------------------------------------

(defn put-token!
  "Store an auth token (its hash) for `uid`."
  [token-hash uid]
  (d/transact conn [{:token/hash token-hash
                       :token/user [:user/uid uid]
                       :token/created-at (now)
                       :token/last-seen (now)}])
  token-hash)

(defn token-uid
  "The uid a token hash authenticates, or nil."
  [token-hash]
  (d/q '[:find ?uid .
         :in $ ?h
         :where [?t :token/hash ?h] [?t :token/user ?u] [?u :user/uid ?uid]]
       @conn token-hash))

(defn delete-token! [token-hash]
  (when-let [eid (d/q '[:find ?t . :in $ ?h :where [?t :token/hash ?h]] @conn token-hash)]
    (d/transact conn [[:db/retractEntity eid]])))

;; --- sheets + shares ------------------------------------------------------
;; Sheet metadata + an ACL of share grants live here; the CELL data stays in
;; the file store. A grant is (sheet, grantee, grantee-kind, level): an
;; `:everyone` grant is the old "public" flag, a `:user` grant is a direct
;; share to one uid. The owner is NOT represented as a grant — ownership is
;; derived from the `<owner>__<name>` storage id (see `store/split-id`).

(defn ensure-sheet!
  "Idempotently register a sheet entity. Returns true iff it was newly created
   (first registration) — the caller uses that to run a one-shot migration of
   the file's legacy :public flag into a grant. `owner-uid`/`name` seed the
   metadata; the owner ref is set only when that user already exists."
  [sheet-id owner-uid name]
  (if (d/q '[:find ?e . :in $ ?id :where [?e :sheet/id ?id]] @conn sheet-id)
    false
    (let [owner? (some? (user-info owner-uid))
          tx (cond-> {:sheet/id sheet-id :sheet/created-at (now)}
               name   (assoc :sheet/name name)
               owner? (assoc :sheet/owner [:user/uid owner-uid]))]
      (d/transact conn [tx])
      true)))

(defn sheet-grants
  "All share rows on `sheet-id` as maps {:grantee :kind :level}. Drives both the
   access check and (later) the share panel."
  [sheet-id]
  (->> (d/q '[:find ?grantee ?kind ?level
              :in $ ?sid
              :where [?sh :sheet/id ?sid] [?s :share/sheet ?sh]
                     [?s :share/grantee ?grantee]
                     [?s :share/grantee-kind ?kind]
                     [?s :share/level ?level]]
            @conn sheet-id)
       (map (fn [[grantee kind level]] {:grantee grantee :kind kind :level level}))))

(defn access-level
  "Highest level `uid` (carrying optional capability `token`) holds on
   `sheet-id` via shares (NOT ownership): :read-write | :read | nil. Considers
   direct :user grants for this uid and the :link grant when `token` matches its
   secret. There is no blanket :everyone tier — broad sharing IS the link, so a
   sheet is reachable only by its owner, the people granted to, or whoever holds
   the unguessable link token."
  [uid sheet-id token]
  (let [levels (for [{:keys [grantee kind level]} (sheet-grants sheet-id)
                     :when (or (and (= kind :user) (= grantee uid))
                               (and (= kind :link) token (= grantee token)))]
                 level)]
    (cond (some #{:read-write} levels) :read-write
          (some #{:read} levels)       :read
          :else                        nil)))

(defn- grant-eid [sheet-id grantee kind]
  (d/q '[:find ?s . :in $ ?sid ?g ?k
         :where [?sh :sheet/id ?sid] [?s :share/sheet ?sh]
                [?s :share/grantee ?g] [?s :share/grantee-kind ?k]]
       @conn sheet-id grantee kind))

(defn grant-level
  "The level (:read | :read-write) of the (grantee, kind) grant on `sheet-id`,
   or nil if there is none."
  [sheet-id grantee kind]
  (when-let [eid (grant-eid sheet-id grantee kind)]
    (:share/level (d/pull @conn [:share/level] eid))))

(defn set-share!
  "Create or update the (grantee, kind) grant on `sheet-id` to `level`
   (:read | :read-write). Idempotent — an existing grant is updated in place.
   Requires the sheet entity to exist (call `ensure-sheet!` first). Returns
   `level`."
  [sheet-id grantee kind level]
  (let [eid (grant-eid sheet-id grantee kind)
        tx  (cond-> {:share/sheet [:sheet/id sheet-id]
                     :share/grantee grantee
                     :share/grantee-kind kind
                     :share/level level
                     :share/created-at (now)}
              eid (assoc :db/id eid))]
    (d/transact conn [tx])
    level))

(defn remove-share!
  "Retract the (grantee, kind) grant on `sheet-id`, if present. Returns true iff
   something was removed."
  [sheet-id grantee kind]
  (when-let [eid (grant-eid sheet-id grantee kind)]
    (d/transact conn [[:db/retractEntity eid]])
    true))

;; --- capability link ------------------------------------------------------
;; "Anyone with the link" = a :link grant whose `grantee` is an unguessable
;; secret token (stored in the URL, not derivable from owner/sheet names). One
;; link per sheet, at a level; rotating mints a new token (kills old links).

(defn- link-eid [sheet-id]
  (d/q '[:find ?s . :in $ ?sid
         :where [?sh :sheet/id ?sid] [?s :share/sheet ?sh]
                [?s :share/grantee-kind :link]]
       @conn sheet-id))

(defn link-grant
  "The sheet's capability-link grant as {:token :level}, or nil if none."
  [sheet-id]
  (when-let [eid (link-eid sheet-id)]
    (let [m (d/pull @conn [:share/grantee :share/level] eid)]
      {:token (:share/grantee m) :level (:share/level m)})))

(def ^:private link-rng (java.security.SecureRandom.))

(defn- gen-link-token []
  (let [b (byte-array 16)]
    (.nextBytes link-rng b)
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) b))))

(defn set-link-level!
  "Set the capability-link level for `sheet-id` to `level` (:read |
   :read-write), minting a token on first enable. `level` nil removes the link.
   The token is STABLE across level changes — only `rotate-link!` changes it.
   Returns the resulting {:token :level}, or nil when removed."
  [sheet-id level]
  (let [eid (link-eid sheet-id)]
    (cond
      (nil? level) (do (when eid (d/transact conn [[:db/retractEntity eid]])) nil)
      eid          (do (d/transact conn [{:db/id eid :share/level level}])
                       (link-grant sheet-id))
      :else        (let [tok (gen-link-token)]
                     (d/transact conn [{:share/sheet [:sheet/id sheet-id]
                                          :share/grantee tok
                                          :share/grantee-kind :link
                                          :share/level level
                                          :share/created-at (now)}])
                     {:token tok :level level}))))

(defn sheet-by-link-token
  "The sheet-id whose capability link carries secret `token`, or nil. Lets a
   token-only link (`/?t=…`) resolve its sheet without leaking owner/name in
   the URL."
  [token]
  (when token
    (d/q '[:find ?id . :in $ ?tok
           :where [?s :share/grantee ?tok] [?s :share/grantee-kind :link]
                  [?s :share/sheet ?sh] [?sh :sheet/id ?id]]
         @conn token)))

(defn rotate-link!
  "Mint a NEW token for the existing link grant (invalidating old links), keeping
   its level. Returns the new {:token :level}, or nil if there is no link."
  [sheet-id]
  (when-let [eid (link-eid sheet-id)]
    (let [tok (gen-link-token)]
      (d/transact conn [{:db/id eid :share/grantee tok}])
      (link-grant sheet-id))))

(defn migrate-everyone->link!
  "One-shot upgrade: convert a legacy public (:everyone) grant — from before
   capability links — into a :link grant at the same level (fresh token). Safe
   to call on every load; a no-op once there is no :everyone grant left."
  [sheet-id]
  (when-let [eid (d/q '[:find ?s . :in $ ?sid
                        :where [?sh :sheet/id ?sid] [?s :share/sheet ?sh]
                               [?s :share/grantee-kind :everyone]]
                      @conn sheet-id)]
    (let [lvl (:share/level (d/pull @conn [:share/level] eid))]
      (d/transact conn [[:db/retractEntity eid]])
      (when-not (link-eid sheet-id)
        (set-link-level! sheet-id lvl)))))

(defn sheets-shared-with
  "Sheets directly shared TO `uid` (a :user grant) as maps
   {:sheet-id :name :level} — drives the 'shared with me' picker section.
   Excludes :everyone (public) grants, which would be the whole world."
  [uid]
  (->> (d/q '[:find ?id ?name ?level
              :in $ ?uid
              :where [?s :share/grantee ?uid] [?s :share/grantee-kind :user]
                     [?s :share/level ?level] [?s :share/sheet ?sh]
                     [?sh :sheet/id ?id]
                     [(get-else $ ?sh :sheet/name "") ?name]]
            @conn uid)
       (map (fn [[id name level]] {:sheet-id id :name name :level level}))
       (sort-by :sheet-id)
       vec))

(defn uid-by-email
  "The uid of an existing user whose email matches (case-insensitive), or nil.
   Used to resolve a prod share-by-email to a uid (we only share to people who
   have signed in at least once)."
  [email]
  (let [e (some-> email str/trim str/lower-case)]
    (when (and e (not= e ""))
      (d/q '[:find ?uid .
             :in $ ?e
             :where [?u :user/email ?em]
                    [(clojure.string/lower-case ?em) ?eml]
                    [(= ?eml ?e)]
                    [?u :user/uid ?uid]]
           @conn e))))

;; --- sheet content: cells (per-property) + per-branch scalars -------------
;; The sheet's SOURCE document lives here as datoms, not a file. A cell is a set
;; of (sheet, branch, addr, prop)->src entities (`:value` + each style prop), and
;; the branch-varying scalars (axis-size defaults/maps, defs) ride on a `:branch`
;; entity. Writes DIFF against the current state so unchanged props don't churn
;; history (Datahike :keep-history? logs every re-assertion — see
;; spikes/04-db-cell-storage.clj). Everything is branch-scoped; the default
;; branch is "main" (git-like branching builds on this dimension later).

(def MAIN "main")

(defn- cp-key [sid branch addr prop] (str sid "|" branch "|" addr "|" (name prop)))
(defn- br-key [sid branch] (str sid "|" branch))

(defn sheet-doc
  "Rebuild the source document {addr {:value raw :style {prop raw}}} for
   (sheet-id, branch) from its cellprop datoms. Empty map if none."
  ([sheet-id] (sheet-doc sheet-id MAIN))
  ([sheet-id branch]
   (->> (d/q '[:find ?addr ?prop ?src
               :in $ ?sid ?br
               :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                      [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                      [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]]
             @conn sheet-id branch)
        (reduce (fn [acc [addr prop src]]
                  (if (= prop :value)
                    (assoc-in acc [addr :value] src)
                    (assoc-in acc [addr :style prop] src)))
                {}))))

(defn- doc->flat
  "{addr {:value raw :style {prop raw}}} -> {[addr prop] src}: :value + each
   style prop, dropping nils."
  [doc]
  (into {} (for [[addr {:keys [value style]}] doc
                 [prop src] (cons [:value value] style)
                 :when (some? src)]
             [[addr prop] src])))

(defn save-doc!
  "DIFF-save the source document for (sheet-id, branch), authored by uid
   `author`. Transacts only props whose src changed (new/different) and retracts
   props no longer present — so an unchanged sheet writes nothing and history
   stays meaningful. Returns {:changed n :removed n}."
  ([sheet-id doc author] (save-doc! sheet-id MAIN doc author))
  ([sheet-id branch doc author]
   (let [old     (doc->flat (sheet-doc sheet-id branch))
         nu      (doc->flat doc)
         changed (for [[[addr prop] src] nu :when (not= src (get old [addr prop]))]
                   (cond-> {:cellprop/key    (cp-key sheet-id branch addr prop)
                            :cellprop/sheet  [:sheet/id sheet-id]
                            :cellprop/branch branch
                            :cellprop/addr   addr
                            :cellprop/prop   prop
                            :cellprop/src    src}
                     author (assoc :cellprop/author author)))
         removed (for [[[addr prop] _] old :when (not (contains? nu [addr prop]))]
                   [:db/retractEntity [:cellprop/key (cp-key sheet-id branch addr prop)]])
         tx      (vec (concat changed removed))]
     (when (seq tx) (d/transact conn tx))
     {:changed (count changed) :removed (count removed)})))

(defn branch-meta
  "Per-(sheet,branch) content scalars {:dcw :drh :cols :rows :defs} (cols/rows/
   defs are edn STRINGS as stored), or nil if the branch has no entity yet."
  ([sheet-id] (branch-meta sheet-id MAIN))
  ([sheet-id branch]
   (when-let [m (d/q '[:find (pull ?b [:branch/dcw :branch/drh :branch/cols :branch/rows :branch/defs]) .
                       :in $ ?k :where [?b :branch/key ?k]]
                     @conn (br-key sheet-id branch))]
     {:dcw (:branch/dcw m) :drh (:branch/drh m)
      :cols (:branch/cols m) :rows (:branch/rows m) :defs (:branch/defs m)})))

(defn set-branch-meta!
  "Diff-upsert the per-(sheet,branch) scalars from map `m` ({:dcw :drh :cols
   :rows :defs}; cols/rows/defs as edn strings). Transacts only changed attrs
   (no churn when unchanged) and ensures the branch entity exists."
  ([sheet-id m] (set-branch-meta! sheet-id MAIN m))
  ([sheet-id branch m]
   (let [cur  (branch-meta sheet-id branch)
         want {:dcw (some-> (:dcw m) long) :drh (some-> (:drh m) long)
               :cols (:cols m) :rows (:rows m) :defs (:defs m)}
         chg  (into {} (for [[a v] want :when (and (some? v) (not= v (get cur a)))]
                         [(keyword "branch" (name a)) v]))]
     (when (or (nil? cur) (seq chg))
       (d/transact conn [(merge {:branch/key (br-key sheet-id branch)
                                 :branch/sheet [:sheet/id sheet-id]
                                 :branch/name branch}
                                chg)]))
     chg)))

(defn sheet-registered?
  "True if a :sheet entity exists for `sheet-id` (it has been created)."
  [sheet-id]
  (boolean (d/q '[:find ?e . :in $ ?id :where [?e :sheet/id ?id]] @conn sheet-id)))

(defn sheet-has-content?
  "True if (sheet-id, branch) has any cell or branch entity — i.e. something was
   saved. (load-record returns nil otherwise so the caller makes a fresh sheet.)"
  ([sheet-id] (sheet-has-content? sheet-id MAIN))
  ([sheet-id branch]
   (boolean (or (d/q '[:find ?b . :in $ ?k :where [?b :branch/key ?k]] @conn (br-key sheet-id branch))
                (d/q '[:find ?c . :in $ ?sid ?br
                       :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?br]]
                     @conn sheet-id branch)))))

(defn sheets-of-owner
  "Sheet ids owned by `owner-uid` that have a registered :sheet entity — drives
   the picker now that names live in the DB, not the filesystem."
  [owner-uid]
  (->> (d/q '[:find [?id ...] :in $ ?uid
              :where [?u :user/uid ?uid] [?sh :sheet/owner ?u] [?sh :sheet/id ?id]]
            @conn owner-uid)
       sort vec))

(defn branch-names
  "Sorted distinct branch names for `sheet-id` — the union of branches that have
   any cellprop and branches that have a `:branch` entity, always including
   `MAIN` (a registered sheet always has a conceptual main). Drives the picker."
  [sheet-id]
  (->> (concat (d/q '[:find [?b ...] :in $ ?sid
                      :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?b]]
                    @conn sheet-id)
               (d/q '[:find [?n ...] :in $ ?sid
                      :where [?sh :sheet/id ?sid] [?b :branch/sheet ?sh] [?b :branch/name ?n]]
                    @conn sheet-id)
               [MAIN])
       distinct sort vec))

(defn branch-exists?
  "True if `branch` is real for `sheet-id`: `MAIN` always (the root), else iff it
   has a `:branch` entity or any cellprop. Lets the web layer reject a typo'd
   `&b=` instead of silently materialising an empty branch (creation is explicit
   via `fork-branch!`)."
  [sheet-id branch]
  (or (= branch MAIN)
      (boolean (or (d/q '[:find ?b . :in $ ?k :where [?b :branch/key ?k]] @conn (br-key sheet-id branch))
                   (d/q '[:find ?c . :in $ ?sid ?br
                          :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?br]]
                        @conn sheet-id branch)))))

(defn delete-branch!
  "Retract every cellprop of (sheet-id, branch) plus its `:branch` entity. Refuses
   `MAIN` (no-op, returns nil). Other branches are untouched — only datoms under
   this exact branch are removed (history retains the retracted datoms under
   :keep-history?). Returns the count of cellprops removed."
  [sheet-id branch]
  (when (not= branch MAIN)
    (let [eids (d/q '[:find [?c ...] :in $ ?sid ?br
                      :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?br]]
                    @conn sheet-id branch)
          beid (d/q '[:find ?b . :in $ ?k :where [?b :branch/key ?k]] @conn (br-key sheet-id branch))
          tx   (cond-> (mapv (fn [e] [:db/retractEntity e]) eids)
                 beid (conj [:db/retractEntity beid]))]
      (when (seq tx) (d/transact conn tx))
      (count eids))))

(defn fork-branch!
  "Copy every cellprop + the branch scalars of (sheet-id, from) under branch
   `to`, recording the fork lineage (`:branch/parent` = from, `:branch/base-tx` =
   the db's `:max-tx` captured at this instant) so a later 3-way merge can
   reconstruct the common-ancestor document via `as-of` base-tx. `to` starts
   identical, then diverges. Caller validates name/uniqueness. Returns the number
   of cellprops copied. (Merge itself is a later step — storage + lineage only.)"
  [sheet-id from to]
  (let [base-tx (:max-tx @conn)                         ; the fork point (see spikes/05)
        rows (d/q '[:find ?addr ?prop ?src ?author
                    :in $ ?sid ?br
                    :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                           [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                           [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]
                           [(get-else $ ?c :cellprop/author "") ?author]]
                  @conn sheet-id from)
        cells (for [[addr prop src author] rows]
                (cond-> {:cellprop/key (cp-key sheet-id to addr prop)
                         :cellprop/sheet [:sheet/id sheet-id]
                         :cellprop/branch to :cellprop/addr addr
                         :cellprop/prop prop :cellprop/src src}
                  (not= "" author) (assoc :cellprop/author author)))]
    ;; cells + the `to` branch entity (with lineage) in one tx, so a fork is
    ;; atomic and the lineage exists even when `from` had no branch scalars.
    (d/transact conn (conj (vec cells)
                           {:branch/key (br-key sheet-id to) :branch/sheet [:sheet/id sheet-id]
                            :branch/name to :branch/parent from :branch/base-tx base-tx}))
    (when-let [m (branch-meta sheet-id from)] (set-branch-meta! sheet-id to m))
    (count cells)))
