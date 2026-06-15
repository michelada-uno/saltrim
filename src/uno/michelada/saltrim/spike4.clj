(ns uno.michelada.saltrim.spike4
  "Step 4 spike — uniform model: every cell is a Spin; refs use `await`
   (handles Spins, unlike `track`). Literal = spin over editable signal.
   Proves formula->formula cascade + edit propagation.

   Run:  clj -M:spike4"
  (:refer-clojure :exclude [await])
  (:require [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn wait-deref [s expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [] (let [v @s]
               (if (or (= v expected) (> (System/currentTimeMillis) deadline))
                 v (do (Thread/sleep 5) (recur)))))))

(defn check [label expected actual]
  (let [ok? (= expected actual)]
    (println (if ok? "  PASS" "  FAIL") label "| exp" (pr-str expected) "got" (pr-str actual))
    ok?))

(defn run []
  (let [rt      (ctx/create-execution-context)
        results (atom [])
        note    #(swap! results conj %)]
    (binding [ec/*execution-context* rt]
      (let [av (sig/->SignalRef "A:1" 10)
            bv (sig/->SignalRef "B:1" 20)]
        (sig/ensure-signal-initialized! av)
        (sig/ensure-signal-initialized! bv)
        (let [a (spin @(track av))                 ; literal cell A:1
              b (spin @(track bv))                 ; literal cell B:1
              c (spin (+ (await a) (await b)))      ; formula C:1 = A + B
              d (spin (+ (await c) 1))]             ; formula D:1 = C + 1  (formula->formula!)
          (println "== initial ==")
          (note (check "A" 10 @a))
          (note (check "C = A+B" 30 @c))
          (note (check "D = C+1 (formula over formula)" 31 @d))

          (println "== edit A:1 -> 100, cascade ==")
          (reset! av 100)
          (note (check "C -> 120" 120 (wait-deref c 120)))
          (note (check "D -> 121" 121 (wait-deref d 121))))))
    (let [all @results]
      (println "\n==== SPIKE 4:" (count (filter true? all)) "/" (count all) "passed ===="))))

(defn -main [& _] (run) (shutdown-agents) (System/exit 0))
