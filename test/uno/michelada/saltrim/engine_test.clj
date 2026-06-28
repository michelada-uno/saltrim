(ns uno.michelada.saltrim.engine-test
  "Engine behavior, no UI. Values, chains, ranges, errors, structural rebuild."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.constants :as c]
            [uno.michelada.saltrim.formula :as formula]
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
        (is (= 30 (sh/row-height s2 3))))))
  (testing "per-sheet default axis sizes"
    (let [s (mk)]
      (is (= c/CW (sh/default-col-w s)) "starts at the built-in default")
      (is (= c/RH (sh/default-row-h s)))
      (sh/set-default-col-w! s 60)
      (sh/set-default-row-h! s 20)
      (is (= 60 (sh/default-col-w s)))
      (is (= 20 (sh/default-row-h s)))
      (sh/set-default-col-w! s 0)
      (is (= 60 (sh/default-col-w s)) "non-positive is ignored (keeps last good)"))))

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

(deftest stdlib
  (testing "math + stats, callable bare"
    (let [s (mk)]
      (put s "A1" "10") (put s "A2" "20") (put s "A3" "30")
      (put s "B1" "=(sum #cells A1:A3)")
      (put s "B2" "=(mean #cells A1:A3)")
      (put s "B3" "=(round (sqrt 2))")
      (put s "B4" "=(product #cells A1:A3)")
      (put s "B5" "=(median #cells A1:A3)")
      (is (= 60 (v s "B1")))
      (is (= 20.0 (v s "B2")))
      (is (= 1 (v s "B3")))
      (is (= 6000 (v s "B4")))
      (is (= 20 (v s "B5")))))
  (testing "text"
    (let [s (mk)]
      (put s "A1" "=(upper \"hi\")")
      (put s "A2" "=(join \"-\" [1 2 3])")
      (put s "A3" "=(count (split \"a,b,c\" \",\"))")
      (is (= "HI" (v s "A1")))
      (is (= "1-2-3" (v s "A2")))
      (is (= 3 (v s "A3")))))
  (testing "date (ISO strings)"
    (let [s (mk)]
      (put s "A1" "=(year \"2026-06-17\")")
      (put s "A2" "=(days-between \"2026-06-01\" \"2026-06-17\")")
      (is (= 2026 (v s "A1")))
      (is (= 16 (v s "A2")))))
  (testing "stdlib names do not shadow clojure.core (e.g. replace)"
    (let [s (mk)]
      (put s "A1" "=(replace {1 :a} [1 2 1])")     ; core replace, not our str-replace
      (is (= [:a 2 :a] (v s "A1")))
      (put s "A2" "=(str-replace \"a-b\" \"-\" \"+\")")
      (is (= "a+b" (v s "A2"))))))

(deftest per-sheet-defs
  (testing "a user-defined fn + const is callable from cells"
    (let [s (mk)]
      (sh/set-defs! s "(defn tax [x] (* x 0.2))\n(def vat 1.5)")
      (put s "A1" "100")
      (put s "A2" "=(tax #cell A1)")
      (put s "A3" "=(* #cell A1 vat)")
      (is (= 20.0 (v s "A2")))
      (is (= 150.0 (v s "A3")))
      (is (= "(defn tax [x] (* x 0.2))\n(def vat 1.5)" (sh/merged-defs s)))))
  (testing "editing defs recompiles dependent cells"
    (let [s (mk)]
      (sh/set-defs! s "(defn tax [x] (* x 0.2))")
      (put s "A1" "100")
      (put s "A2" "=(tax #cell A1)")
      (is (= 20.0 (v s "A2")))
      (sh/set-defs! s "(defn tax [x] (* x 0.5))")   ; redefine
      (is (= 50.0 (v s "A2")) "cell picks up the new definition")))
  (testing "removing a used fn -> cell reports an error, others survive; errors collected"
    (let [s (mk)]
      (sh/set-defs! s "(defn tax [x] (* x 0.2))")
      (put s "A1" "100")
      (put s "A2" "=(tax #cell A1)")
      (put s "A3" "=(* #cell A1 2)")
      (let [{:keys [errors]} (sh/set-defs! s "")]   ; drop tax
        (is (seq errors) "the cell that used tax is reported")
        (is (= "A2" (ffirst errors))))
      (is (:error (v s "A2")) "A2 now reads as an error")
      (is (= 200 (v s "A3")) "an unrelated cell still computes")))
  (testing "bad defs are rejected and leave the sheet unchanged"
    (let [s (mk)]
      (sh/set-defs! s "(defn ok [x] x)")
      (put s "A1" "=(ok 7)")
      (is (= 7 (v s "A1")))
      (is (thrown? Exception (sh/set-defs! s "(defn broken [x] (")))  ; unbalanced
      (is (= 7 (v s "A1")) "still works on the previous defs")))
  (testing "definitions are isolated per sheet"
    (let [s1 (mk) s2 (mk)]
      (sh/set-defs! s1 "(defn secret [] 42)")
      (put s1 "A1" "=(secret)")
      (is (= 42 (v s1 "A1")))
      (is (thrown? clojure.lang.ExceptionInfo (put s2 "A1" "=(secret)"))
          "another sheet cannot see s1's defs"))))

(deftest defs-library
  (testing "add/update/remove chunks; all merge into the program"
    (let [s (mk)
          id1 (:id (sh/add-def! s "(defn tri [x] (* x 3))"))
          id2 (:id (sh/add-def! s "(def k 100)"))]
      (put s "A1" "4")
      (put s "A2" "=(+ (tri #cell A1) k)")
      (is (= 112 (v s "A2")) "both chunks visible")
      (is (= 2 (count (sh/defs s))))
      (is (every? :id (sh/defs s)) "each chunk has a stable id")
      (testing "update one chunk recompiles dependents"
        (sh/update-def! s id1 "(defn tri [x] (* x 10))")
        (is (= 140 (v s "A2"))))
      (testing "remove a chunk -> dependent errors, the other still resolves"
        (let [{:keys [errors]} (sh/remove-def! s id2)]
          (is (seq errors)))
        (is (:error (v s "A2")))
        (is (= 1 (count (sh/defs s)))))))
  (testing "a chunk whose source doesn't evaluate is rejected (library unchanged)"
    (let [s (mk)]
      (sh/add-def! s "(defn ok [x] x)")
      (is (thrown? Exception (sh/add-def! s "(defn bad [")))
      (is (= 1 (count (sh/defs s))))))
  (testing "set-defs! accepts a chunk vector; merged-defs concatenates"
    (let [s (mk)]
      (sh/set-defs! s [{:id "a" :src "(defn f [x] (inc x))"}
                       {:id "b" :src "(def g 5)"}])
      (put s "A1" "=(+ (f 1) g)")
      (is (= 7 (v s "A1")))
      (is (= "(defn f [x] (inc x))\n\n(def g 5)" (sh/merged-defs s))))))

;; --- selective undo/redo (sheet/undo-step) ---------------------------------

(deftest undo-redo-value
  (testing "undo walks back through a cell's edits; redo reapplies"
    (let [s (mk)
          _ (do (put s "A1" "5") (sh/settle! s) (put s "A1" "10") (sh/settle! s))
          ;; the per-session stack web would have recorded for these two edits
          st {:undo [{:addr "A1" :prop :value :before nil :after "5"}
                     {:addr "A1" :prop :value :before "5" :after "10"}]
              :redo []}
          r1 (sh/undo-step s st :undo)]
      (sh/settle! s)
      (is (= 5 (sh/value s "A1")) "undo -> previous value")
      (is (= 1 (count (get-in r1 [:stacks :undo]))))
      (is (= 1 (count (get-in r1 [:stacks :redo]))))
      (let [r2 (sh/undo-step s (:stacks r1) :undo)]
        (sh/settle! s)
        (is (nil? (sh/value s "A1")) "undo the add -> cell cleared")
        (let [r3 (sh/undo-step s (:stacks r2) :redo)]
          (sh/settle! s)
          (is (= 5 (sh/value s "A1")) "redo -> re-add")
          (is (= 1 (count (get-in r3 [:stacks :undo])))))))))

(deftest undo-selective-supersession
  (testing "an edit a collaborator overwrote is skipped, not clobbered"
    (let [s (mk)
          _ (do (put s "A1" "5") (sh/settle! s)     ; my edit
                (put s "A1" "10") (sh/settle! s))    ; someone else overwrote
          st {:undo [{:addr "A1" :prop :value :before nil :after "5"}] :redo []}
          r  (sh/undo-step s st :undo)]
      (sh/settle! s)
      (is (= 10 (sh/value s "A1")) "peer's value preserved")
      (is (nil? (:affected r)) "nothing applied")
      (is (empty? (get-in r [:stacks :undo])) "superseded entry consumed"))))

(deftest undo-redo-style
  (testing "undo/redo a style property"
    (let [s (mk)
          _ (do (put s "A1" "5") (sh/set-style! s "A1" :bg "tomato") (sh/settle! s))
          st {:undo [{:addr "A1" :prop :bg :before nil :after "tomato"}] :redo []}
          r  (sh/undo-step s st :undo)]
      (sh/settle! s)
      (is (nil? (sh/style-value s "A1" :bg)) "undo removes the style")
      (let [r2 (sh/undo-step s (:stacks r) :redo)]
        (sh/settle! s)
        (is (= "tomato" (sh/style-value s "A1" :bg)) "redo restores it")))))

(deftest undo-empty-stack
  (testing "undo with nothing to undo is a no-op"
    (let [s (mk)
          r (sh/undo-step s {:undo [] :redo []} :undo)]
      (is (nil? (:affected r)))
      (is (= {:undo [] :redo []} (:stacks r))))))

;; --- clipboard paste: relative reference shifting --------------------------

(deftest shift-refs
  (testing "non-formula text + zero shift are unchanged"
    (is (= "5" (formula/shift-refs "5" 3 3)))
    (is (nil? (formula/shift-refs nil 1 1)))
    (is (= "=(+ #cell A1 1)" (formula/shift-refs "=(+ #cell A1 1)" 0 0))))
  (testing "#cell shifts by (dc dr)"
    (is (= "=(+ #cell B2 1)" (formula/shift-refs "=(+ #cell A1 1)" 1 1)))
    (is (= "=(+ #cell A2 #cell C4)" (formula/shift-refs "=(+ #cell A1 #cell C3)" 0 1))))
  (testing "#cells range shifts both corners"
    (is (= "=(reduce + #cells B1:C2)" (formula/shift-refs "=(reduce + #cells A1:B2)" 1 0))))
  (testing "refs clamp at A1 (no negative indices)"
    (is (= "=(+ #cell A1 1)" (formula/shift-refs "=(+ #cell A1 1)" -5 -5))))
  (testing "$-sugar shifts like the reader tags"
    (is (= "=(* $B2 2)" (formula/shift-refs "=(* $A1 2)" 1 1)))
    (is (= "=(sum $A5:D10)" (formula/shift-refs "=(sum $A3:D8)" 0 2)) "range, no double-shift of left half")
    (is (= "=(+ $B1 #cell C2)" (formula/shift-refs "=(+ $A1 #cell B2)" 1 0)) "mixed $ and reader tag")))

(deftest dollar-refs
  (testing "parse: $A1 / $A3:D8 are sugar for #cell / #cells"
    (is (= #{"A1"} (:deps (formula/parse "(* $A1 2)"))))
    (is (= (:deps (formula/parse "(sum #cells A3:D8)"))
           (:deps (formula/parse "(sum $A3:D8)"))) "range deps match the reader tag")
    (is (= #{"A1" "B2" "C1" "C2" "C3"}
           (:deps (formula/parse "(+ $A1 #cell B2 (sum #cells C1:C3))"))) "mixes with reader tags"))
  (testing "a $-like token inside a string literal is NOT a ref"
    (is (empty? (:deps (formula/parse "(str \"owe $A1 now\")")))))
  (let [s (mk)]
    (put s "A1" "10") (put s "A2" "20") (put s "A3" "30")
    (put s "B1" "=(* $A1 2)")                 ; single $-ref
    (put s "B2" "=(sum $A1:A3)")              ; range $-ref
    (is (= 20 (v s "B1")))
    (is (= 60 (v s "B2")))
    (testing "reacts to a dep change like any ref"
      (put s "A1" "100")
      (is (= 200 (v s "B1")))
      (is (= 150 (v s "B2"))))))

(deftest relative-refs
  (testing "parse: $<col><row> resolves by offset from the owner cell"
    (is (= #{"B2"} (:deps (formula/parse "(inc $_-1)" "B3"))) "$_-1 in B3 -> B2")
    (is (= #{"A1" "B1"} (:deps (formula/parse "(+ $-2_ $-1_)" "C1"))) "two cols left in C1")
    (is (= #{"C5"} (:deps (formula/parse "$+1-1" "B6"))) "$+1-1 in B6 -> C5"))
  (testing "off-grid offset throws a clear error"
    (is (thrown-with-msg? Exception #"off the grid" (formula/parse "$-1_" "A1")))
    (is (thrown-with-msg? Exception #"off the grid" (formula/parse "$_-1" "A1"))))
  (testing "relative refs are copy-invariant — shift-refs leaves them alone"
    (is (= "=(inc $_-1)" (formula/shift-refs "=(inc $_-1)" 2 3)))
    (is (= "=(+ $_-1 $B1)" (formula/shift-refs "=(+ $_-1 $A1)" 1 0)) "relative kept, absolute shifted"))
  (testing "a relative self-reference is a cycle"
    (let [s (mk)]
      (is (thrown-with-msg? Exception #"circular" (put s "B1" "=(inc $__)")))))
  (testing "counter: B2=1, B3.. = =(inc $_-1) (same source in every cell) -> 1..10"
    (let [s (mk)]
      (put s "B2" "1")
      (doseq [r (range 3 12)] (put s (str "B" r) "=(inc $_-1)"))
      (is (= [1 2 3 4 5 6 7 8 9 10] (mapv #(v s (str "B" %)) (range 2 12))))))
  (testing "fibonacci: A1=0 B1=1, C1.. = =(+ $-2_ $-1_)"
    (let [s (mk)]
      (put s "A1" "0") (put s "B1" "1")
      (doseq [c ["C" "D" "E" "F" "G" "H"]] (put s (str c "1") "=(+ $-2_ $-1_)"))
      (is (= [0 1 1 2 3 5 8 13] (mapv #(v s (str % "1")) ["A" "B" "C" "D" "E" "F" "G" "H"]))))))

(deftest insert-shift-refs
  (testing "row insert at index 2 (+1): refs >= row3 bump, ranges straddling grow"
    (is (= "(+ (#cell A1) (#cell A6) (sum #cells A1:A6) $B4)"
           (formula/insert-shift "(+ (#cell A1) (#cell A5) (sum #cells A1:A5) $B3)" :row 2 1))))
  (testing "col insert at index 1 (+1)"
    (is (= "(+ (#cell A1) (#cell D1) #cells A1:D1)"
           (formula/insert-shift "(+ (#cell A1) (#cell C1) #cells A1:C1)" :col 1 1))))
  (testing "delete (-1) is the inverse"
    (let [src "(+ (#cell A1) (#cell A5) #cells A1:A5 $B3)"]
      (is (= src (formula/insert-shift (formula/insert-shift src :row 2 1) :row 2 -1)))))
  (testing "refs before the line are untouched"
    (is (= "(+ $A1 $A2)" (formula/insert-shift "(+ $A1 $A2)" :row 5 1)))))

(deftest insert-line
  (let [s (mk)]
    (put s "A1" "10") (put s "A2" "20")
    (put s "B1" "=(* $A1 2)")          ; ref above the insert
    (put s "B2" "=(* $A2 3)")          ; ref that will move
    (sh/set-style! s "A2" :bg "tomato")
    (sh/set-col-width! s 5 99)
    (sh/settle! s)
    (testing "insert a blank row above row 2 (index 1)"
      (sh/insert-line! s :row 1) (sh/settle! s)
      (is (= 10 (v s "A1")) "row above the insert stays")
      (is (nil? (v s "A2")) "the inserted row is blank")
      (is (= 20 (v s "A3")) "old A2 moved down")
      (is (= 20 (v s "B1")) "ref above the insert ($A1) unchanged")
      (is (= "=(* $A3 3)" (sh/raw s "B3")) "moved formula's ref followed to A3")
      (is (= 60 (v s "B3")))
      (is (= "tomato" (sh/style-value s "A3" :bg)) "style followed the cell"))
    (testing "delete-line! is the exact inverse"
      (sh/delete-line! s :row 1) (sh/settle! s)
      (is (= 20 (v s "A2")))
      (is (= "=(* $A2 3)" (sh/raw s "B2")))
      (is (= "tomato" (sh/style-value s "A2" :bg)))
      (is (= 99 (sh/col-width s 5)) "untouched-axis size preserved")))
  (testing "column insert shifts cols + col sizes"
    (let [s (mk)]
      (put s "A1" "1") (put s "C1" "=(* $A1 5)")
      (sh/set-col-width! s 2 50)        ; column C
      (sh/settle! s)
      (sh/insert-line! s :col 1) (sh/settle! s)   ; insert blank column B
      (is (= 1 (v s "A1")) "A unchanged")
      (is (= "=(* $A1 5)" (sh/raw s "D1")) "old C1 moved to D1, ref to A1 unchanged")
      (is (= 50 (sh/col-width s 3)) "old col C size moved to col D"))))

(deftest structural-undo
  (let [s (mk)]
    (put s "A1" "10") (put s "A2" "20") (sh/settle! s)
    (sh/insert-line! s :row 1) (sh/settle! s)
    (is (= 20 (v s "A3")) "inserted")
    (let [{:keys [stacks affected]} (sh/undo-step s {:undo [{:op :insert :axis :row :at 1}] :redo []} :undo)]
      (sh/settle! s)
      (is (= :all affected) "structural step re-renders all")
      (is (= 20 (v s "A2")) "undo reversed the insert in one step")
      (is (nil? (v s "A3")))
      (testing "redo re-inserts"
        (sh/undo-step s stacks :redo) (sh/settle! s)
        (is (= 20 (v s "A3")))))))
