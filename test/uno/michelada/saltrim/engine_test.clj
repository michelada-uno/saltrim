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

(deftest axis-sizing
  (testing "set / read / clear per-axis sizes"
    (let [s (mk)]
      (sh/set-col-width! s 2 200)
      (sh/set-row-height! s 4 40)
      (is (= 200 (sh/col-width s 2)))
      (is (= 40 (sh/row-height s 4)))
      (is (nil? (sh/col-width s 0)) "unset -> nil (caller defaults)")
      (sh/set-col-width! s 2 0)
      (is (nil? (sh/col-width s 2)) "non-positive clears")))
  (testing "sizing round-trips through the document"
    (let [s (mk)]
      (sh/set-col-width! s 1 150)
      (sh/set-row-height! s 3 30)
      (let [s2 (mk)]
        (sh/load-sizing! s2 (sh/col-widths s) (sh/row-heights s))
        (is (= 150 (sh/col-width s2 1)))
        (is (= 30 (sh/row-height s2 3)))))))

(deftest cell-style
  (testing "literal style prop"
    (let [s (mk)]
      (sh/set-style! s "A1" :bg "tomato")
      (is (= "tomato" (sh/style-value s "A1" :bg)))))
  (testing "$val style recomputes reactively with the cell value"
    (let [s (mk)]
      (put s "A1" "50")
      (sh/set-style! s "A1" :bg "=(if (> $val 100) \"tomato\" \"white\")")
      (sh/settle! s)
      (is (= "white" (sh/style-value s "A1" :bg)) "below threshold")
      (put s "A1" "150")
      (sh/settle! s)
      (is (= "tomato" (sh/style-value s "A1" :bg)) "recompute on value change")))
  (testing "style may reference ANOTHER cell; style-dependents tracks the edge"
    (let [s (mk)]
      (put s "A1" "1") (put s "B1" "10")
      (sh/set-style! s "A1" :bg "=(if (> #cell B1 5) \"red\" \"green\")")
      (sh/settle! s)
      (is (= "red" (sh/style-value s "A1" :bg)))
      (is (contains? (sh/style-dependents s "B1") "A1") "B1 change must re-render A1")))
  (testing "broken style formula surfaces {:error}, not silent nil"
    (let [s (mk)]
      (put s "A1" "5")
      (sh/set-style! s "A1" :bg "=(+ $val \"x\")")    ; numeric + string -> error
      (sh/settle! s)
      (let [v (sh/style-value s "A1" :bg)]
        (is (map? v))
        (is (:error v)))
      (is (= [:bg] (map first (sh/style-errors s "A1"))) "listed for the toast")))
  (testing "blank removes the prop"
    (let [s (mk)]
      (sh/set-style! s "A1" :bg "tomato")
      (sh/set-style! s "A1" :bg "")
      (is (nil? (sh/style-value s "A1" :bg)))))
  (testing "style source round-trips through the document"
    (let [s (mk)]
      (put s "A1" "150")
      (sh/set-style! s "A1" :bg "=(if (> $val 100) \"tomato\" \"white\")")
      (let [doc (sh/document s)
            s2  (mk)]
        (sh/load-document! s2 doc)
        (sh/settle! s2)
        (is (= "tomato" (sh/style-value s2 "A1" :bg)) "reloads + recomputes")))))

(deftest sci-formulas
  (testing "let with local bindings (impossible under the old whitelist)"
    (let [s (mk)]
      (put s "A1" "10") (put s "B1" "20")
      (put s "C1" "=(let [x #cell A1 y #cell B1] (+ x y (* x 2)))")
      (is (= 50 (v s "C1")))
      (put s "A1" "100")
      (is (= 320 (v s "C1")) "recomputes through the let")))
  (testing "higher-order: fn literal + map + reduce over a range"
    (let [s (mk)]
      (put s "A1" "3") (put s "A2" "4") (put s "A3" "5")
      (put s "B1" "=(reduce + (map (fn [n] (* n n)) #cells A1:A3))")
      (is (= 50 (v s "B1")) "9+16+25")))
  (testing "destructuring binds a range vector"
    (let [s (mk)]
      (put s "A1" "7") (put s "B1" "8")
      (put s "C1" "=(let [[a b] #cells A1:B1] (- b a))")
      (is (= 1 (v s "C1")))))
  (testing "abs is available in the sandbox"
    (let [s (mk)]
      (put s "A1" "-9")
      (put s "B1" "=(abs #cell A1)")
      (is (= 9 (v s "B1")))))
  (testing "host interop / IO is blocked by the sandbox"
    (let [s (mk)]
      (is (thrown? clojure.lang.ExceptionInfo (put s "Z1" "=(slurp \"/etc/passwd\")")))
      (is (thrown? clojure.lang.ExceptionInfo (put s "Z2" "=(System/exit 0)"))))))
