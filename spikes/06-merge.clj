(ns spikes.06-merge
  "Spike: 3-way branch MERGE (ROADMAP boss fight, PR B) on top of switch+fork
   (PR A, spikes/05). Proves the merge BASE resolution + the per-property 3-way
   diff BEFORE wiring `merge.clj` + the web conflict UI.

   Model: merge SOURCE branch INTO TARGET (the current branch). The 3-way base
   is the common ancestor document, reconstructed from the recorded fork lineage
   (`:branch/parent` + `:branch/base-tx`) via Datahike `as-of`:
   - source forked from target  -> base = target `as-of` source.base-tx
   - target forked from source  -> base = source `as-of` target.base-tx
   - siblings (same parent)      -> base = parent `as-of` min(base-tx)
   - otherwise                   -> no common ancestor (can't 3-way merge)

   Per (addr, prop) key, with base b / source s / target t (nil = absent):
   - s = t            -> nothing (already equal)
   - s = b            -> source didn't change vs base -> keep target
   - t = b            -> target didn't change -> TAKE source (s, or DELETE if nil)
   - else             -> CONFLICT (both changed differently) -> user picks

   Auto-merge = the t=b cases; conflicts are surfaced for a per-property pick
   (default: keep target). Verified below: base={A1 10,B1 20,C1 30};
   take={A1->11, C1->delete, D1->99}; conflicts=[A2: base nil src 7 tgt 8]; B1/E1
   no-op. Run: clojure -M:nrepl --port 7888, eval the (comment …) forms.")

(def DEL ::delete)

(comment
  (require '[datahike.api :as d] '[mount.core :as mount] '[uno.michelada.saltrim.db :as db])
  (db/start-mem!)                                  ; clean mem mount; db/* use db/conn
  (def S "dev-ann__s")
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "10"} "B1" {:value "20"} "C1" {:value "30"}} "dev-ann")
  (db/fork-branch! S "main" "exp")
  ;; diverge: exp A1->11, C1 deleted, D1 new, A2=7 ; main B1->21, E1 new, A2=8
  (db/save-doc! S "exp"  {"A1" {:value "11"} "B1" {:value "20"} "D1" {:value "99"} "A2" {:value "7"}} "dev-ann")
  (db/save-doc! S "main" {"A1" {:value "10"} "B1" {:value "21"} "C1" {:value "30"} "E1" {:value "88"} "A2" {:value "8"}} "dev-ann")

  ;; --- candidate fns (shape of merge.clj + db additions) -------------------
  (defn doc-asof [dbv sid br]                       ; sheet-doc against an as-of db
    (->> (d/q '[:find ?addr ?prop ?src :in $ ?sid ?br
                :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                       [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                       [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]]
              dbv sid br)
         (reduce (fn [a [ad p s]] (if (= p :value) (assoc-in a [ad :value] s) (assoc-in a [ad :style p] s))) {})))
  (defn flatten-doc [doc]
    (into {} (for [[a {:keys [value style]}] doc, [p s] (cons [:value value] style) :when (some? s)] [[a p] s])))
  (defn lineage [sid br]                            ; nil-safe (main has no :branch entity)
    (when-let [e (d/q '[:find ?b . :in $ ?k :where [?b :branch/key ?k]] @db/conn (str sid "|" br))]
      (d/pull @db/conn [:branch/parent :branch/base-tx] e)))
  (defn merge-base [sid source target]
    (let [ls (lineage sid source) lt (lineage sid target)]
      (cond
        (and (= (:branch/parent ls) target) (:branch/base-tx ls))
        (flatten-doc (doc-asof (d/as-of @db/conn (:branch/base-tx ls)) sid target))
        (and (= (:branch/parent lt) source) (:branch/base-tx lt))
        (flatten-doc (doc-asof (d/as-of @db/conn (:branch/base-tx lt)) sid source))
        (and (:branch/parent ls) (= (:branch/parent ls) (:branch/parent lt)) (:branch/base-tx ls) (:branch/base-tx lt))
        (flatten-doc (doc-asof (d/as-of @db/conn (min (:branch/base-tx ls) (:branch/base-tx lt))) sid (:branch/parent ls)))
        :else nil)))
  (defn diff3 [base src tgt]
    (reduce (fn [acc k]
              (let [b (get base k) s (get src k) t (get tgt k)]
                (cond
                  (= s t) acc
                  (= s b) acc
                  (= t b) (assoc-in acc [:take k] (if (nil? s) DEL s))
                  :else   (update acc :conflicts conj {:key k :base b :source s :target t}))))
            {:take {} :conflicts []}
            (distinct (concat (keys base) (keys src) (keys tgt)))))

  ;; --- merge exp INTO main -------------------------------------------------
  (def base (merge-base S "exp" "main"))
  (diff3 base (flatten-doc (db/sheet-doc S "exp")) (flatten-doc (db/sheet-doc S "main")))
  ;; => {:take {["A1" :value] "11", ["C1" :value] :spikes.06-merge/delete, ["D1" :value] "99"}
  ;;     :conflicts [{:key ["A2" :value], :base nil, :source "7", :target "8"}]}
  ;; base => {["B1" :value] "20" ["A1" :value] "10" ["C1" :value] "30"}   (main @ fork)

  (mount/stop)
  )
