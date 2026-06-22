(ns spikes.05-branching
  "Spike: git-like sheet branching ON TOP of the per-(sheet,branch,addr,prop)
   datom store from spike 04. Proves the db-layer pieces BEFORE the web room
   refactor + UI (ROADMAP boss fight, PR A) and the merge base (PR B).

   What this spike proves:
   1. fork lineage — capture the db's `:max-tx` (a plain Long) at fork into
      `:branch/base-tx`, plus `:branch/parent`, on the new branch's `:branch`
      entity. These record the fork POINT.
   2. as-of base — `(d/as-of @conn base-tx)` reconstructs the PARENT branch's
      document at the moment of the fork. That is exactly the 3-way merge BASE
      (common ancestor) PR B needs. Verified: after main A1 10->999 and exp
      A1->555 post-fork, the base doc still reads A1 = \"10\".
   3. delete isolation — `delete-branch!` retracts ONLY that branch's cellprops
      + its `:branch` entity; the parent (main) is untouched. main is refused.
   4. listing — `branch-names` = distinct branches (cellprops ∪ branch entities)
      always incl. \"main\"; `branch-exists?` true for main on any registered
      sheet, else iff datoms exist.

   The candidate fns below are the shape of what lands in `db.clj` (A2). Names/
   keys match `db` (cp-key/br-key, MAIN). Run: clojure -M:nrepl --port 7888,
   then eval the (comment …) forms in order against a FRESH mem db.")

(comment
  (require '[datahike.api :as d] '[uno.michelada.saltrim.db :as db])

  ;; fresh, isolated in-memory db (no H2 lock, no :8080). NOTE: until A2 lands,
  ;; the two lineage attrs aren't in db/schema, so add them here explicitly.
  (def cfg (db/mem-config))
  (d/create-database cfg)
  (def conn (d/connect cfg))
  (d/transact conn db/schema)
  (d/transact conn [{:db/ident :branch/parent  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                    {:db/ident :branch/base-tx :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}])
  (def sid "dev-ann__budget")
  (d/transact conn [{:sheet/id sid}])

  (defn cp-key [sid br addr prop] (str sid "|" br "|" addr "|" (name prop)))
  (defn br-key [sid br] (str sid "|" br))
  (defn doc [db sid br]
    (->> (d/q '[:find ?addr ?prop ?src :in $ ?sid ?br
                :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                       [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                       [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]]
              db sid br)
         (reduce (fn [acc [addr prop src]]
                   (if (= prop :value) (assoc-in acc [addr :value] src)
                       (assoc-in acc [addr :style prop] src))) {})))

  ;; candidate db ops (shape of A2) -----------------------------------------
  (defn fork! [sid from to]
    (let [base-tx (:max-tx @conn)                       ; <-- plain Long, captured at fork
          rows (d/q '[:find ?addr ?prop ?src :in $ ?sid ?br
                      :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                             [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                             [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]]
                    @conn sid from)
          cells (for [[addr prop src] rows]
                  {:cellprop/key (cp-key sid to addr prop) :cellprop/sheet [:sheet/id sid]
                   :cellprop/branch to :cellprop/addr addr :cellprop/prop prop :cellprop/src src})]
      (d/transact conn (conj (vec cells)
                             {:branch/key (br-key sid to) :branch/sheet [:sheet/id sid]
                              :branch/name to :branch/parent from :branch/base-tx base-tx}))
      {:copied (count cells) :base-tx base-tx}))

  (defn branch-names [sid]
    (->> (concat (d/q '[:find [?b ...] :in $ ?sid :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?b]] @conn sid)
                 (d/q '[:find [?n ...] :in $ ?sid :where [?sh :sheet/id ?sid] [?b :branch/sheet ?sh] [?b :branch/name ?n]] @conn sid)
                 ["main"])
         distinct sort vec))

  (defn branch-exists? [sid br]
    (or (= br "main")
        (boolean (or (d/q '[:find ?b . :in $ ?k :where [?b :branch/key ?k]] @conn (br-key sid br))
                     (d/q '[:find ?c . :in $ ?sid ?br :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?br]] @conn sid br)))))

  (defn delete-branch! [sid br]
    (when (not= br "main")
      (let [eids (d/q '[:find [?c ...] :in $ ?sid ?br :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh] [?c :cellprop/branch ?br]] @conn sid br)
            beid (d/q '[:find ?b . :in $ ?k :where [?b :branch/key ?k]] @conn (br-key sid br))
            tx   (cond-> (mapv (fn [e] [:db/retractEntity e]) eids) beid (conj [:db/retractEntity beid]))]
        (when (seq tx) (d/transact conn tx))
        (count eids))))

  ;; seed main, FORK (base = A1 10), then DIVERGE -----------------------------
  (d/transact conn [{:cellprop/key (cp-key sid "main" "A1" :value) :cellprop/sheet [:sheet/id sid] :cellprop/branch "main" :cellprop/addr "A1" :cellprop/prop :value :cellprop/src "10"}
                    {:cellprop/key (cp-key sid "main" "B1" :value) :cellprop/sheet [:sheet/id sid] :cellprop/branch "main" :cellprop/addr "B1" :cellprop/prop :value :cellprop/src "=(* #cell A1 2)"}
                    {:cellprop/key (cp-key sid "main" "A2" :bg) :cellprop/sheet [:sheet/id sid] :cellprop/branch "main" :cellprop/addr "A2" :cellprop/prop :bg :cellprop/src "tomato"}])
  (def f (fork! sid "main" "exp"))
  (d/transact conn [{:cellprop/key (cp-key sid "main" "A1" :value) :cellprop/sheet [:sheet/id sid] :cellprop/branch "main" :cellprop/addr "A1" :cellprop/prop :value :cellprop/src "999"}])
  (d/transact conn [{:cellprop/key (cp-key sid "exp" "A1" :value) :cellprop/sheet [:sheet/id sid] :cellprop/branch "exp" :cellprop/addr "A1" :cellprop/prop :value :cellprop/src "555"}])

  ;; 1+2. lineage + as-of base -----------------------------------------------
  {:base-tx (:base-tx f)
   :lineage (d/pull @conn [:branch/parent :branch/base-tx] [:branch/key (br-key sid "exp")])
   :base-A1 (get-in (doc (d/as-of @conn (:base-tx f)) sid "main") ["A1" :value])  ; => "10"
   :main-A1 (get-in (doc @conn sid "main") ["A1" :value])                          ; => "999"
   :exp-A1  (get-in (doc @conn sid "exp")  ["A1" :value])                          ; => "555"
   :exp-style (get-in (doc @conn sid "exp") ["A2" :style])}                        ; => {:bg "tomato"}
  ;; => {:base-tx <long> :lineage {:branch/parent "main" :branch/base-tx <long>}
  ;;     :base-A1 "10" :main-A1 "999" :exp-A1 "555" :exp-style {:bg "tomato"}}

  ;; 3+4. delete isolation + listing -----------------------------------------
  {:removed (delete-branch! sid "exp")          ; => 3
   :names (branch-names sid)                     ; => ["main"]
   :exp-empty (empty? (doc @conn sid "exp"))     ; => true
   :main-intact (doc @conn sid "main")           ; => main unchanged (A1 999, B1, A2 bg)
   :delete-main-refused (delete-branch! sid "main")} ; => nil

  (d/release conn)
  )
