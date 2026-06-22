(ns uno.michelada.saltrim.merge-test
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.merge :as mrg]))

(defn- doc [m]
  ;; tiny builder: {addr value} -> nested doc with :value
  (into {} (for [[a v] m] [a {:value v}])))

(deftest plan-classifies-3-way
  (let [base {"A1" {:value "1"} "B1" {:value "2"} "C1" {:value "3"}}
        ;; source: A1 changed, C1 deleted, D1 added; B1 untouched
        src  {"A1" {:value "9"}                 "D1" {:value "4"} "B1" {:value "2"}}
        ;; target: B1 changed, E1 added; A1/C1 untouched
        tgt  {"A1" {:value "1"} "B1" {:value "8"} "C1" {:value "3"} "E1" {:value "5"}}
        {:keys [take conflicts]} (mrg/plan base src tgt)]
    (testing "auto-merge = props only source changed vs base"
      (is (= "9" (take ["A1" :value])) "source-only change taken")
      (is (= "4" (take ["D1" :value])) "source-only addition taken")
      (is (= mrg/DELETE (take ["C1" :value])) "source deletion taken"))
    (testing "target-only changes are kept (not in take)"
      (is (not (contains? take ["B1" :value])))
      (is (not (contains? take ["E1" :value]))))
    (testing "no conflicts when changes are disjoint"
      (is (empty? conflicts)))))

(deftest plan-detects-conflict
  (let [base {}
        src  {"A2" {:value "7"}}
        tgt  {"A2" {:value "8"}}
        {:keys [take conflicts]} (mrg/plan base src tgt)]
    (is (empty? take))
    (is (= [{:key ["A2" :value] :base nil :source "7" :target "8"}] conflicts))))

(deftest plan-equal-is-noop
  (let [d {"A1" {:value "5"}}]
    (is (= {:take {} :conflicts []} (mrg/plan d d d)) "identical docs → nothing to do")))

(deftest plan-style-props-are-independent
  (let [base {"A1" {:value "1" :style {:bg "white"}}}
        src  {"A1" {:value "1" :style {:bg "tomato"}}}   ; only bg changed on source
        tgt  {"A1" {:value "2" :style {:bg "white"}}}    ; only value changed on target
        {:keys [take conflicts]} (mrg/plan base src tgt)]
    (is (= "tomato" (take ["A1" :bg])) "the style prop merges cleanly")
    (is (not (contains? take ["A1" :value])) "the value prop (target-only) is kept")
    (is (empty? conflicts) "value vs style don't conflict — different keys")))

(deftest actions-applies-picks
  (let [plan {:take {["A1" :value] "9" ["C1" :value] mrg/DELETE}
              :conflicts [{:key ["A2" :value] :base nil :source "7" :target "8"}
                          {:key ["B2" :value] :base "x" :source "y" :target "z"}]}]
    (testing "no picks → only the auto take"
      (is (= {["A1" :value] "9" ["C1" :value] mrg/DELETE} (mrg/actions plan #{}))))
    (testing "a pick adds that conflict's source"
      (is (= "7" (get (mrg/actions plan #{"A2|value"}) ["A2" :value])))
      (is (not (contains? (mrg/actions plan #{"A2|value"}) ["B2" :value]))))
    (testing "both picks"
      (let [a (mrg/actions plan #{"A2|value" "B2|value"})]
        (is (= "7" (a ["A2" :value])))
        (is (= "y" (a ["B2" :value])))))))

(deftest key-string-roundtrip
  (is (= "A1|value" (mrg/key->str ["A1" :value])))
  (is (= "B7|bg" (mrg/key->str ["B7" :bg])))
  (is (= ["A1" :value] (mrg/str->key "A1|value")))
  (is (= ["B7" :bg] (mrg/str->key "B7|bg"))))

(deftest conflicts-sorted-by-cell
  ;; rows then cols then prop — stable UI order
  (let [base {} src {"B2" {:value "s"} "A1" {:value "s"} "A2" {:value "s"}}
        tgt  {"B2" {:value "t"} "A1" {:value "t"} "A2" {:value "t"}}
        ks   (map :key (:conflicts (mrg/plan base src tgt)))]
    (is (= [["A1" :value] ["A2" :value] ["B2" :value]] ks))))
