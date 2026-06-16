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
   {:db/ident :share/created-at   :db/valueType :db.type/long    :db/cardinality :db.cardinality/one}])

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

(defn- ensure-schema! [c]
  (when-not (d/q '[:find ?e . :where [?e :db/ident :user/uid]] @c)
    (d/transact c schema)))

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
