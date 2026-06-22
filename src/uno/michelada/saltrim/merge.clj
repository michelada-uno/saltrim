(ns uno.michelada.saltrim.merge
  "Pure 3-way merge of sheet documents (ROADMAP boss fight, PR B). Operates on
   the source document `{addr {:value raw :style {prop raw}}}` shape — the same
   one `db/sheet-doc` produces — flattened to one entry per cell PROPERTY
   `[addr prop] -> src`, which is the true unit of edit/branch/merge.

   `plan` does the 3-way classification against the common-ancestor `base`
   (resolved by `db/merge-base` via fork lineage + as-of, proven in
   spikes/06-merge.clj). The web layer turns a plan + the owner's per-conflict
   picks into the write actions to apply on the target branch. Everything here is
   pure data → easy to test; no db, no engine."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.addr :as addr]))

(def DELETE ::delete)   ; an action value meaning "clear this property on target"

(defn flatten-doc
  "{addr {:value raw :style {prop raw}}} -> {[addr prop] src} (drops nils)."
  [doc]
  (into {} (for [[a {:keys [value style]}] doc
                 [p s] (cons [:value value] style)
                 :when (some? s)]
             [[a p] s])))

(defn plan
  "3-way merge plan for bringing `source` INTO `target`, given the common-ancestor
   `base` (all three are nested docs). Per property key `[addr prop]` with base b /
   source s / target t (nil = absent):
     s = t  -> already equal (skip)
     s = b  -> source unchanged vs base -> keep target (skip)
     t = b  -> target unchanged -> TAKE source (its src, or DELETE when absent)
     else   -> CONFLICT (both diverged) -> the owner picks
   Returns {:take {key src|DELETE} :conflicts [{:key :base :source :target} …]},
   conflicts sorted by cell then property for a stable UI."
  [base source target]
  (let [b (flatten-doc base) s (flatten-doc source) t (flatten-doc target)
        ks (distinct (concat (keys b) (keys s) (keys t)))
        {:keys [take conflicts]}
        (reduce (fn [acc k]
                  (let [bv (get b k) sv (get s k) tv (get t k)]
                    (cond
                      (= sv tv) acc
                      (= sv bv) acc
                      (= tv bv) (assoc-in acc [:take k] (if (nil? sv) DELETE sv))
                      :else     (update acc :conflicts conj
                                        {:key k :base bv :source sv :target tv}))))
                {:take {} :conflicts []}
                ks)]
    {:take take
     :conflicts (vec (sort-by (fn [{[a p] :key}]
                                (let [{:keys [ci ri]} (addr/parse a)] [ri ci (name p)]))
                              conflicts))}))

(defn key->str
  "[addr prop] -> \"addr|prop\" (a form-safe id for $mergetake; addr is alnum,
   prop a keyword name)."
  [[a p]] (str a "|" (name p)))

(defn str->key
  "\"addr|prop\" -> [addr prop-keyword]."
  [s] (let [[a p] (str/split (str s) #"\|")] [a (keyword p)]))

(defn actions
  "The final write actions {key src|DELETE} for applying a `plan`: the auto-merge
   `:take` plus, for each conflict whose key string is in `take-source` (the set
   the owner chose to take from source), that conflict's source value (or DELETE
   when the source has none)."
  [plan take-source]
  (let [picks (into #{} (map str (or take-source #{})))]
    (reduce (fn [acc {:keys [key source]}]
              (if (picks (key->str key))
                (assoc acc key (if (nil? source) DELETE source))
                acc))
            (:take plan)
            (:conflicts plan))))
