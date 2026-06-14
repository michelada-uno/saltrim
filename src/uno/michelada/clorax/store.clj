(ns uno.michelada.clorax.store
  "Sheet persistence — saves the SOURCE document (not the Spindel graph), one
   EDN file per sheet id under data/. Behind save!/load, so the backend can
   become Datahike/SQL later without touching callers.

   Multi-tenancy: the storage id is `<owner-uid>__<sheet-name>` (owner uids are
   [a-z0-9-] only — no underscores — so the first \"__\" splits unambiguously).
   fmt 2 adds the ownership envelope: {:fmt 2 :owner uid :public bool :cells …}.
   fmt 1 files (pre-auth) load with owner nil + public true (legacy sheets stay
   reachable). The per-cell value is a property map ({:value raw}) with room
   for :style/:format later."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [uno.michelada.clorax.sheet :as sheet]
            [uno.michelada.clorax.util :as util]))

(def ^:private dir (or (util/env "CLORAX_DATA_DIR") "data"))
(def ^:private fmt 2)

(defn valid-id?
  "Allow only safe storage ids (no path traversal)."
  [id]
  (boolean (and id (re-matches #"[A-Za-z0-9_-]{1,64}" (str id)))))

(defn valid-name?
  "User-facing sheet NAME: no underscores (they'd collide with the owner__name
   separator), modest length."
  [n]
  (boolean (and n (re-matches #"[A-Za-z0-9-]{1,32}" (str n)))))

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

(defn- file [id]
  (assert (valid-id? id) (str "bad sheet id: " (pr-str id)))
  (io/file dir (str id ".edn")))

(defn exists? [id]
  (and (valid-id? id) (.exists (file id))))

(defn save!
  "Persist a sheet's source document under `id`, with its ownership meta."
  ([id sheet] (save! id sheet nil))
  ([id sheet {:keys [owner public]}]
   (let [f (file id)]
     (io/make-parents f)
     (spit f (pr-str {:fmt fmt :owner owner :public (boolean public)
                      :cells (sheet/document sheet)})))
   id))

(defn load-record
  "Load and rebuild a sheet for `id` with its ownership meta:
   {:sh sheet :owner uid|nil :public bool}, or nil if none stored.
   fmt 1 files have no envelope -> owner nil, public true (legacy)."
  [id]
  (when (exists? id)
    (let [{:keys [fmt cells owner public]} (edn/read-string (slurp (file id)))
          s (sheet/create-sheet)]
      (sheet/load-document! s cells)
      (sheet/settle! s)
      {:sh s
       :owner owner
       :public (if (and fmt (>= (long fmt) 2)) (boolean public) true)})))

(defn load-sheet
  "Load and rebuild just the sheet engine for `id`, or nil if none stored."
  [id]
  (:sh (load-record id)))

(defn list-names
  "Names of the stored sheets owned by `owner-uid` (for a sheet picker)."
  [owner-uid]
  (let [prefix (str owner-uid "__")]
    (->> (.listFiles (io/file dir))
         (keep (fn [f]
                 (let [n (.getName f)]
                   (when (and (str/ends-with? n ".edn") (str/starts-with? n prefix))
                     (subs n (count prefix) (- (count n) 4))))))
         sort
         vec)))
