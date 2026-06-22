(ns uno.michelada.saltrim.store
  "Sheet persistence. The SOURCE document — cells + per-branch scalars — lives in
   Datahike (see `db`), NOT files: cells are per-property, branch-aware datoms so
   git-like branching + per-property history layer on later. This ns is the thin
   seam web.clj calls (save!/load-record/exists?/list-names); the storage id stays
   `<owner-uid>__<sheet-name>` (owner uids are [a-z0-9-] only — no underscores —
   so the first \"__\" splits unambiguously). `load-record`/`save!` take a
   `branch` (default `db/MAIN`); git-like branching is built on that dimension.

   Legacy: sheets used to be one EDN file per id under `data/`. That store is
   retired — old `.edn` files are ignored (not migrated); collections start fresh
   in the database."
  (:require [clojure.edn :as edn]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]))

;; --- ids (pure; no storage) ----------------------------------------------

(defn valid-id?
  "Allow only safe storage ids."
  [id]
  (boolean (and id (re-matches #"[A-Za-z0-9_-]{1,64}" (str id)))))

(defn valid-name?
  "User-facing sheet NAME: no underscores (they'd collide with the owner__name
   separator), modest length."
  [n]
  (boolean (and n (re-matches #"[A-Za-z0-9-]{1,32}" (str n)))))

(defn valid-branch?
  "A branch NAME: same charset as a sheet name — no `@`/`|`/`__`/`/` — so it is
   safe in URLs (`&b=`), the db `\"<sheet>|<branch>\"` keys, and the web room key
   `[sheet-id branch]`. `\"main\"` (= `db/MAIN`) is the reserved default."
  [b]
  (boolean (and b (re-matches #"[A-Za-z0-9-]{1,32}" (str b)))))

(defn storage-id
  "Compose `<owner>__<name>`, or nil if either part is unsafe."
  [owner name]
  (when (and (re-matches #"[a-z0-9][a-z0-9-]{0,23}" (str owner)) (valid-name? name))
    (str owner "__" name)))

(defn split-id
  "[owner name] for a namespaced storage id, else [nil id] (legacy un-owned)."
  [id]
  (if-let [[_ o n] (re-matches #"([a-z0-9][a-z0-9-]*)__([A-Za-z0-9-]+)" (str id))]
    [o n]
    [nil (str id)]))

;; --- content persistence (Datahike, branch "main") -----------------------

(defn exists?
  "Has this sheet been created (registered in the db)?"
  [id]
  (and (valid-id? id) (db/sheet-registered? id)))

(defn- ne
  "A non-empty map as an edn string (for a :branch scalar blob), else nil so the
   diff-upsert skips it."
  [m]
  (when (seq m) (pr-str m)))

(defn save!
  "Persist the sheet's source document + per-branch scalars to the db, authored
   by `author` uid (recorded per changed cell for per-user undo) on branch
   `branch` (default `db/MAIN`). Diff-based: unchanged props/scalars write
   nothing. `opts` = {:author uid :branch name}. Owner + sharing are NOT here —
   owner is the :sheet entity's owner, sharing is the ACL. The sheet must already
   be registered (web.clj's sheet-rec calls db/ensure-sheet! before any edit)."
  ([id sheet] (save! id sheet nil))
  ([id sheet {:keys [author branch] :or {branch db/MAIN}}]
   (db/save-doc! id branch (sheet/document sheet) author)
   (db/set-branch-meta! id branch {:dcw  (sheet/default-col-w sheet)
                                   :drh  (sheet/default-row-h sheet)
                                   :cols (ne (sheet/col-widths sheet))
                                   :rows (ne (sheet/row-heights sheet))
                                   :defs (ne (sheet/defs sheet))})
   id))

(defn load-record
  "Rebuild a sheet from the db for `branch` (default `db/MAIN`):
   {:sh sheet :owner uid|nil :public false}, or nil when that branch has no
   content yet (caller then creates a fresh empty one). Owner is derived from the
   id; public/sharing live in the ACL, not here. Definitions are applied BEFORE
   cells so formulas compile against them."
  ([id] (load-record id db/MAIN))
  ([id branch]
   (when (and (valid-id? id) (db/sheet-has-content? id branch))
     (let [s (sheet/create-sheet)
           {:keys [dcw drh cols rows defs]} (db/branch-meta id branch)]
       (when defs (sheet/set-defs! s (edn/read-string defs)))
       (sheet/load-document! s (db/sheet-doc id branch))
       (sheet/load-sizing! s (or (some-> cols edn/read-string) {})
                           (or (some-> rows edn/read-string) {}))
       (when dcw (sheet/set-default-col-w! s dcw))
       (when drh (sheet/set-default-row-h! s drh))
       (sheet/settle! s)
       {:sh s :owner (first (split-id id)) :public false}))))

(defn load-sheet
  "Load and rebuild just the sheet engine for `id` on `branch` (default main),
   or nil if none stored."
  ([id] (:sh (load-record id)))
  ([id branch] (:sh (load-record id branch))))

(defn list-names
  "Names of the sheets owned by `owner-uid` (for the picker), from the db."
  [owner-uid]
  (->> (db/sheets-of-owner owner-uid) (map (comp second split-id)) sort vec))
