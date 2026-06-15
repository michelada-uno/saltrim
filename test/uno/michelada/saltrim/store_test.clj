(ns uno.michelada.saltrim.store-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]))

(def ^:private id "test_roundtrip")

(defn- cleanup [] (io/delete-file (io/file "data" (str id ".edn")) true))

(deftest valid-id
  (is (store/valid-id? "default"))
  (is (store/valid-id? "tenant_42-x"))
  (is (not (store/valid-id? "../etc/passwd")))
  (is (not (store/valid-id? "a/b")))
  (is (not (store/valid-id? ""))))

(deftest roundtrip
  (cleanup)
  (try
    (let [s (sheet/create-sheet)]
      (sheet/set-cell! s "A1" "10")
      (sheet/set-cell! s "A2" "20")
      (sheet/set-cell! s "A3" "=(reduce + #cells A1:A2)")   ; range formula
      (sheet/set-cell! s "B1" "=(* #cell A3 2)")            ; chained formula
      (sheet/set-cell! s "C1" "hello")
      (sheet/settle! s)
      (store/save! id s))
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
          (is (= 240 (sheet/value s2 "B1"))))))
    (finally (cleanup))))

(deftest load-missing-nil
  (is (nil? (store/load-sheet "no_such_sheet_xyz"))))

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

(def ^:private owned-id "dev-alice__t-owned")

(deftest ownership-roundtrip
  (io/delete-file (io/file "data" (str owned-id ".edn")) true)
  (try
    (let [s (sheet/create-sheet)]
      (sheet/set-cell! s "A1" "7")
      (sheet/settle! s)
      (store/save! owned-id s {:owner "dev-alice" :public true}))
    (let [{:keys [sh owner public]} (store/load-record owned-id)]
      (is (= "dev-alice" owner))
      (is (true? public))
      (is (= 7 (sheet/value sh "A1"))))
    (finally (io/delete-file (io/file "data" (str owned-id ".edn")) true))))

(deftest legacy-fmt1-loads-public
  ;; pre-auth files have no envelope: owner nil, public true (stay reachable)
  (let [f (io/file "data" "t-legacy-fmt1.edn")]
    (try
      (spit f (pr-str {:fmt 1 :cells {"A1" {:value "5"}}}))
      (let [{:keys [sh owner public]} (store/load-record "t-legacy-fmt1")]
        (is (nil? owner))
        (is (true? public))
        (is (= 5 (sheet/value sh "A1"))))
      (finally (io/delete-file f true)))))
