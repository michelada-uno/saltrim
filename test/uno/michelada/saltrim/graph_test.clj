(ns uno.michelada.saltrim.graph-test
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.graph :as graph]))

(deftest empty-graph
  (is (= {:nodes #{} :edges [] :layer {}} (graph/build {}))))

(deftest chain-layers
  ;; C reads B reads A  →  A(0) → B(1) → C(2)
  (let [{:keys [nodes edges layer]} (graph/build {"B" #{"A"} "C" #{"B"}})]
    (is (= #{"A" "B" "C"} nodes))
    (is (= #{["A" "B"] ["B" "C"]} (set edges)) "edge points from dep to the cell that reads it")
    (is (= {"A" 0 "B" 1 "C" 2} layer))))

(deftest diamond-longest-path
  ;; D reads B and C; both read A.  A(0) B(1) C(1) D(2)
  (let [{:keys [layer edges]} (graph/build {"B" #{"A"} "C" #{"A"} "D" #{"B" "C"}})]
    (is (= {"A" 0 "B" 1 "C" 1 "D" 2} layer) "D sits past the longest path through B/C")
    (is (= #{["A" "B"] ["A" "C"] ["B" "D"] ["C" "D"]} (set edges)))))

(deftest multi-dep-fan-in
  ;; a total cell reading a range of inputs → all inputs at layer 0, total at 1
  (let [{:keys [layer]} (graph/build {"B1" #{"A1" "A2" "A3"}})]
    (is (= 0 (layer "A1")) "leaf inputs are layer 0")
    (is (= 1 (layer "B1")) "the formula sits one layer right")))
