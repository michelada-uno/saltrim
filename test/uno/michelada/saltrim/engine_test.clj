(ns uno.michelada.saltrim.engine-test
  "Engine behavior, no UI. Values, chains, ranges, errors, structural rebuild."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.sheet :as sh]))

(defn- mk [] (sh/create-sheet))
(defn- put [s a raw] (sh/set-cell! s a raw))
(defn- v [s a] (sh/settle! s) (sh/value s a))

(deftest literals
  (let [s (mk)]
    (put s "A1" "10") (put s "A2" "1.5") (put s "A3" "hello")
    (is (= 10 (v s "A1")))
    (is (= 1.5 (v s "A2")))
    (is (= "hello" (v s "A3")))
    (is (nil? (v s "Z9"))) "blank cell -> nil"))

(deftest single-formula
  (let [s (mk)]
    (put s "A1" "10") (put s "B1" "20")
    (put s "C1" "=(+ #cell A1 #cell B1)")
    (is (= 30 (v s "C1")))
    (put s "A1" "100")
    (is (= 120 (v s "C1")) "recompute on dep change")))

(deftest chain
  (let [s (mk)]
    (put s "A1" "2")
    (put s "B1" "=(* #cell A1 3)")
    (put s "C1" "=(+ #cell B1 1)")
    (put s "D1" "=(* #cell C1 #cell C1)")
    (is (= [6 7 49] [(v s "B1") (v s "C1") (v s "D1")]))
    (put s "A1" "10")
    (is (= [30 31 961] [(v s "B1") (v s "C1") (v s "D1")]) "whole chain recomputes")))

(deftest ranges
  (let [s (mk)]
    (put s "A1" "10") (put s "A2" "20") (put s "A3" "30")
    (put s "B1" "=(reduce + #cells A1:A3)")
    (put s "B2" "=(count #cells A1:A3)")
    (put s "B3" "=(reduce + (map inc #cells A1:A3))")
    (is (= 60 (v s "B1")))
    (is (= 3  (v s "B2")))
    (is (= 63 (v s "B3")) "map over range")
    (put s "A2" "200")
    (is (= 240 (v s "B1")) "range recomputes when a member changes")))

(deftest formula-over-formula
  (let [s (mk)]
    (put s "A1" "100") (put s "B1" "21")
    (put s "C1" "=(- #cell A1 #cell B1)")
    (put s "C2" "=(+ #cell C1 1)")            ; formula referencing a formula
    (is (= 79 (v s "C1")))
    (is (= 80 (v s "C2")))))

(deftest structural-rebuild
  (let [s (mk)]
    (put s "A1" "100") (put s "B1" "21")
    (put s "C1" "=(- #cell A1 #cell B1)")
    (put s "C2" "=(+ #cell C1 1)")
    (is (= 80 (v s "C2")))
    (testing "C1 formula -> literal, dependent re-points"
      (put s "C1" "5")
      (is (= 5 (v s "C1")))
      (is (= 6 (v s "C2"))))
    (testing "C1 literal -> formula again"
      (put s "C1" "=(* #cell B1 2)")
      (is (= 42 (v s "C1")))
      (is (= 43 (v s "C2"))))))

(deftest cycles
  (let [s (mk)]
    (testing "self reference rejected, cell keeps prior value"
      (put s "A1" "10")
      (is (thrown? clojure.lang.ExceptionInfo (put s "A1" "=(+ #cell A1 1)")))
      (is (= 10 (v s "A1")) "old value intact, no install"))
    (testing "indirect cycle A1->B1->A1 rejected"
      (put s "A1" "=(+ #cell B1 1)")
      (put s "B1" "5")
      (is (= 6 (v s "A1")))
      (is (thrown? clojure.lang.ExceptionInfo (put s "B1" "=(+ #cell A1 1)")))
      (is (= 5 (v s "B1")) "B1 stays literal"))
    (testing "3-hop cycle rejected"
      (let [s2 (mk)]
        (put s2 "A1" "=(+ #cell B1 1)")
        (put s2 "B1" "=(+ #cell C1 1)")
        (is (thrown? clojure.lang.ExceptionInfo (put s2 "C1" "=(+ #cell A1 1)")))))))

(deftest errors
  (let [s (mk)]
    (put s "A1" "10") (put s "A2" "hello")
    (testing "type error in compute -> {:error}"
      (put s "B1" "=(+ #cell A1 #cell A2)")
      (let [r (v s "B1")]
        (is (map? r))
        (is (contains? r :error))))
    (testing "reference to blank cell -> {:error}"
      (put s "B2" "=(+ #cell Z9 1)")
      (is (:error (v s "B2"))))
    (testing "disallowed symbol rejected at set time"
      (is (thrown? clojure.lang.ExceptionInfo
                   (put s "B3" "=(slurp \"/etc/passwd\")"))))))
