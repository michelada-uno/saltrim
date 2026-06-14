(ns uno.michelada.clorax.db
  "Datahike-backed registry for identity and metadata. Owns users and auth
   tokens now; sheet metadata + shares move here next. Sheet CELL data stays in
   the file store (see `store`).

   Backend is env-driven (`config`):
   - default (dev/staging): H2 file at `data/clorax-h2`
   - prod: a full JDBC url via CLORAX_DB_JDBC_URL (YugabyteDB — Postgres-wire,
     served by the com.yugabyte/jdbc-yugabytedb driver)
   - tests: in-memory (`CLORAX_DB_BACKEND=mem`, or `init-mem!` directly)

   We keep history (`:keep-history? true`) deliberately — `as-of` underpins the
   planned audit/branching features. Tokens are stored as a SHA-256 hash of the
   cookie secret (the cookie carries the secret; the DB never sees it)."
  (:require [datahike.api :as d]
            [konserve-jdbc.core]                      ; registers the konserve :jdbc store
            [konserve.store :refer [validate-store-config]]
            [uno.michelada.clorax.util :as util]))

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
   {:db/ident :share/grantee      :db/valueType :db.type/string  :db/cardinality :db.cardinality/one} ; uid | group-id | "*"
   {:db/ident :share/grantee-kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :user | :group | :everyone
   {:db/ident :share/level        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :read | :read-write
   {:db/ident :share/created-at   :db/valueType :db.type/long    :db/cardinality :db.cardinality/one}])

;; --- connection -----------------------------------------------------------

;; konserve requires the store :id to be a UUID. For a persistent backend it
;; must be STABLE across restarts (else each boot is a fresh empty DB), so we
;; use a fixed default, overridable via CLORAX_DB_ID.
(def ^:private default-id #uuid "c10a4a00-0000-4000-8000-000000000001")
(defn- store-id [] (if-let [s (env "CLORAX_DB_ID")] (java.util.UUID/fromString s) default-id))

(defn- config []
  (cond
    (= "mem" (env "CLORAX_DB_BACKEND"))
    {:store {:backend :memory :id (store-id)}
     :schema-flexibility :write :keep-history? true}

    (env "CLORAX_DB_JDBC_URL")
    {:store {:backend :jdbc :id (store-id) :jdbcUrl (env "CLORAX_DB_JDBC_URL")
             :table (or (env "CLORAX_DB_TABLE") "clorax")}
     :schema-flexibility :write :keep-history? true}

    :else
    {:store {:backend :jdbc :id (store-id) :dbtype "h2"
             :dbname (or (env "CLORAX_DB_PATH") "data/clorax-h2")
             :table "clorax"}
     :schema-flexibility :write :keep-history? true}))

(defonce ^:private conn* (atom nil))

(defn- ensure-schema! [c]
  (when-not (d/q '[:find ?e . :where [?e :db/ident :user/uid]] @c)
    (d/transact c schema)))

(defn- connect! [cfg]
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

(defn conn
  "The shared Datahike connection (lazily created from `config`)."
  []
  (or @conn* (reset! conn* (connect! (config)))))

(defn init-mem!
  "Reset the shared connection to a fresh, isolated in-memory database. For
   tests — each call gets a unique store id."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? true}]
    (reset! conn* (connect! cfg))))

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
               @(conn) uid)))

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
    (d/transact (conn) [tx])
    uid))

;; --- auth tokens ----------------------------------------------------------

(defn put-token!
  "Store an auth token (its hash) for `uid`."
  [token-hash uid]
  (d/transact (conn) [{:token/hash token-hash
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
       @(conn) token-hash))

(defn delete-token! [token-hash]
  (when-let [eid (d/q '[:find ?t . :in $ ?h :where [?t :token/hash ?h]] @(conn) token-hash)]
    (d/transact (conn) [[:db/retractEntity eid]])))
