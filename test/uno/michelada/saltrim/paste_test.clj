(ns uno.michelada.saltrim.paste-test
  "Clipboard paste tiling: a copied clip fills the whole target SELECTION, not
   just its top-left cell (the bug: Ctrl+V into a range pasted one cell only)."
  (:require [clojure.test :refer [deftest is testing]]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.handlers :as h]))

(deftest tile-origins
  (let [t #'h/tile-origins]
    (testing "a 1x1 clip fills every cell of a row range"
      (is (= [[7 0] [8 0] [9 0] [10 0] [11 0] [12 0]] (t 7 0 12 0 1 1))))
    (testing "single target cell -> one paste"
      (is (= [[7 0]] (t 7 0 7 0 1 1))))
    (testing "a 2x2 clip tiles a 4x4 target"
      (is (= [[0 0] [2 0] [0 2] [2 2]] (t 0 0 3 3 2 2))))
    (testing "target smaller than the clip -> one paste (so a block pastes whole)"
      (is (= [[5 5]] (t 5 5 5 5 3 3))))))

(deftest capture-records-footprint
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "B2" "9")
    (let [clip (#'h/capture-clip s "B2:D4")]
      (is (= 3 (:w clip)) "selection width incl. empties")
      (is (= 3 (:h clip)) "selection height incl. empties")
      (is (= [1 1] (:origin clip)) "B2 -> [ci ri] = [1 1]"))))

(deftest paste-fills-selection
  ;; the reported bug, end to end: copy one Fibonacci formula cell, paste it into
  ;; a row range -> the whole range fills (relative refs re-resolve per cell).
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "0")
    (sheet/set-cell! s "B1" "1")
    (sheet/set-cell! s "C1" "=(+ $-2_ $-1_)")
    (sheet/settle! s)
    (let [clip (#'h/capture-clip s "C1:C1")]
      (is (= [1 1] [(:w clip) (:h clip)]) "single copied cell")
      ;; paste into D1:H1 (sid not in sessions* -> record-edit! just no-ops)
      (doseq [[tc tr] (#'h/tile-origins 3 0 7 0 (:w clip) (:h clip))]
        (#'h/paste-cells! s "no-session" clip tc tr))
      (sheet/settle! s)
      (is (= [0 1 1 2 3 5 8 13]
             (mapv #(sheet/value s (str % "1")) ["A" "B" "C" "D" "E" "F" "G" "H"]))
          "the formula filled the range, not just the first cell"))))
