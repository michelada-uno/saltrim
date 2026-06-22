(ns uno.michelada.saltrim.store-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [mount.core :as mount]
            [uno.michelada.saltrim.constants :as c]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]))

;; Sheet content lives in Datahike now — fresh in-memory db per test.
(use-fixtures :each (fn [t] (db/start-mem!) (try (t) (finally (mount/stop)))))

(def ^:private id "dev-ann__budget")

(defn- register!
  "A sheet must be registered (own a :sheet entity) before content is saved —
   web.clj's sheet-rec does this via db/ensure-sheet! before any edit."
  ([] (register! id "budget"))
  ([sheet-id name]
   (db/upsert-user! {:uid "dev-ann" :name "Ann"})
   (db/ensure-sheet! sheet-id "dev-ann" name)))

;; --- id helpers (pure) ----------------------------------------------------

(deftest valid-id
  (is (store/valid-id? "default"))
  (is (store/valid-id? "dev-ann__budget"))
  (is (not (store/valid-id? "../etc")))
  (is (not (store/valid-id? "")))
  (is (not (store/valid-id? nil))))

(deftest names-and-storage-ids
  (testing "sheet names exclude underscores (owner__name separator)"
    (is (store/valid-name? "budget-2026"))
    (is (not (store/valid-name? "a_b")))
    (is (not (store/valid-name? ""))))
  (testing "storage-id composes only from safe parts"
    (is (= "dev-alice__budget" (store/storage-id "dev-alice" "budget")))
    (is (nil? (store/storage-id "Bad_Owner" "budget")))
    (is (nil? (store/storage-id "dev-alice" "a_b"))))
  (testing "split-id inverts storage-id; legacy plain ids have no owner"
    (is (= ["dev-alice" "budget"] (store/split-id "dev-alice__budget")))
    (is (= [nil "default"] (store/split-id "default")))))

;; --- content round-trip (Datahike) ---------------------------------------

(deftest load-missing-nil
  (is (nil? (store/load-record "dev-ann__nope")))
  (is (not (store/exists? "dev-ann__nope"))))

(deftest roundtrip
  (register!)
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "10")
    (sheet/set-cell! s "A2" "20")
    (sheet/set-cell! s "A3" "=(reduce + #cells A1:A2)")   ; range formula
    (sheet/set-cell! s "B1" "=(* #cell A3 2)")            ; chained formula
    (sheet/set-cell! s "C1" "hello")
    (sheet/settle! s)
    (store/save! id s {:author "dev-ann"}))
  (is (store/exists? id))
  (testing "reload rebuilds values, formulas, chains"
    (let [s2 (store/load-sheet id)]
      (sheet/settle! s2)
      (is (= 10 (sheet/value s2 "A1")))
      (is (= 30 (sheet/value s2 "A3")) "range formula recomputed")
      (is (= 60 (sheet/value s2 "B1")) "chained formula recomputed")
      (is (= "hello" (sheet/value s2 "C1")))
      (testing "reloaded sheet is live: edit propagates"
        (sheet/set-cell! s2 "A1" "100")
        (sheet/settle! s2)
        (is (= 120 (sheet/value s2 "A3")))
        (is (= 240 (sheet/value s2 "B1")))))))

(deftest style-roundtrip
  (register!)
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "5")
    (sheet/set-style! s "A1" :bg "tomato")
    (sheet/set-style! s "A1" :align "center")
    (sheet/settle! s)
    (store/save! id s {:author "dev-ann"}))
  (let [s2 (store/load-sheet id)]
    (sheet/settle! s2)
    (is (= "tomato" (sheet/style-value s2 "A1" :bg)))
    (is (= "center" (sheet/style-value s2 "A1" :align)))))

(deftest defs-roundtrip
  (register!)
  (let [s (sheet/create-sheet)]
    (sheet/add-def! s "(defn dbl [x] (* 2 x))")
    (sheet/add-def! s "(def k 100)")
    (sheet/set-cell! s "A1" "21")
    (sheet/set-cell! s "A2" "=(+ (dbl #cell A1) k)")   ; user fn + const
    (sheet/settle! s)
    (store/save! id s {:author "dev-ann"}))
  (testing "the definitions library persists + applies before cells on reload"
    (let [s2 (store/load-sheet id)]
      (sheet/settle! s2)
      (is (= 2 (count (sheet/defs s2))) "both chunks round-trip")
      (is (every? :id (sheet/defs s2)) "chunk ids preserved")
      (is (= 142 (sheet/value s2 "A2"))))))

(deftest sizing-roundtrip
  (register!)
  (let [s (sheet/create-sheet)]
    (sheet/set-col-width! s 1 150)
    (sheet/set-row-height! s 3 30)
    (sheet/set-default-col-w! s 60)
    (sheet/set-default-row-h! s 18)
    (sheet/set-cell! s "A1" "x")
    (store/save! id s {:author "dev-ann"}))
  (let [s2 (store/load-sheet id)]
    (is (= 150 (sheet/col-width s2 1)))
    (is (= 30 (sheet/row-height s2 3)))
    (is (= 60 (sheet/default-col-w s2)))
    (is (= 18 (sheet/default-row-h s2))))
  (testing "a sheet left at the built-in default sizes still round-trips"
    (register! "dev-ann__plain" "plain")
    (let [s (sheet/create-sheet)]
      (sheet/set-cell! s "A1" "x")
      (store/save! "dev-ann__plain" s {:author "dev-ann"}))
    (let [s2 (store/load-sheet "dev-ann__plain")]
      (is (= c/CW (sheet/default-col-w s2)))
      (is (= c/RH (sheet/default-row-h s2))))))

(deftest owner-from-id
  (register!)
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "1")
    (store/save! id s {:author "dev-ann"}))
  (is (= "dev-ann" (:owner (store/load-record id))) "owner derived from the id"))

(deftest list-names-from-db
  (register! "dev-ann__a" "a")
  (register! "dev-ann__b" "b")
  (is (= ["a" "b"] (store/list-names "dev-ann")))
  (is (empty? (store/list-names "dev-nobody"))))

(deftest branch-aware-save-load
  (register!)
  ;; seed main
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "1")
    (store/save! id s {:author "dev-ann"}))            ; defaults to MAIN
  (testing "load a non-existent branch is nil (creation is explicit via fork)"
    (is (nil? (store/load-record id "exp"))))
  ;; fork main -> exp at the db layer, then edit exp through the store
  (db/fork-branch! id "main" "exp")
  (let [s (store/load-sheet id "exp")]
    (sheet/settle! s)
    (is (= 1 (sheet/value s "A1")) "exp starts as a copy of main")
    (sheet/set-cell! s "A1" "999")
    (sheet/set-cell! s "B1" "exp-only")
    (sheet/settle! s)
    (store/save! id s {:author "dev-ann" :branch "exp"}))
  (testing "branches are isolated: the exp edit does not leak to main"
    (let [m (store/load-sheet id)                       ; main
          e (store/load-sheet id "exp")]
      (sheet/settle! m) (sheet/settle! e)
      (is (= 1 (sheet/value m "A1")) "main unchanged")
      (is (nil? (sheet/value m "B1")) "exp-only cell absent from main")
      (is (= 999 (sheet/value e "A1")) "exp diverged")
      (is (= "exp-only" (sheet/value e "B1"))))))
