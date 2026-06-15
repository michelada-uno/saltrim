(ns uno.michelada.saltrim.spike
  "Step 0 spike — reactive cell lifecycle on real Spindel.

   Findings that shape the architecture:

   * Signal mutation (reset!/swap!) goes through rtp/enqueue! =
     enqueue-event! + trigger-drain!. The drain runs on the context
     executor (virtual thread, JDK21+). So propagation is AUTOMATIC and
     ASYNC — app code never pumps a drain loop.

   * Spins are PULL/lazy + cached: they recompute when deref'd after their
     deps went dirty. The eager `effect` root is cljs/DOM-only on JVM.

   * Therefore the right model for a viewport spreadsheet is PULL-ON-VISIBLE:
     mutate a cell's signal → executor auto-propagates dirtiness → we deref
     only the VISIBLE spins to get fresh values for SSE. We never compute
     off-screen cells, and we never manually drive a drain.

   This spike proves: after a mutation, with ZERO explicit drain calls,
   dereferencing a dependent spin eventually yields the fresh value (the
   executor settled it on its own). We only WAIT (poll), never pump.

   Run:  clj -M:spike"
  (:refer-clojure :exclude [await])
  (:require [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn wait-deref
  "Poll @spin until it equals expected or timeout. NO drain pumping — proves
   the executor propagates on its own. Returns the last value seen."
  [spin expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v @spin]
        (cond
          (= v expected) v
          (> (System/currentTimeMillis) deadline) v
          :else (do (Thread/sleep 5) (recur)))))))

(defn check [label expected actual]
  (let [ok? (= expected actual)]
    (println (if ok? "  PASS" "  FAIL") label
             "| expected" (pr-str expected) "got" (pr-str actual))
    ok?))

(defn run []
  (let [rt (ctx/create-execution-context)]
    (binding [ec/*execution-context* rt]
      (let [results (atom [])
            note    (fn [r] (swap! results conj r))]

        (println "\n== 1. signal cells ==")
        (let [a (sig/->SignalRef "A:1" 10)
              b (sig/->SignalRef "B:1" 20)]
          (sig/ensure-signal-initialized! a)
          (sig/ensure-signal-initialized! b)
          (note (check "A:1 derefs initial" 10 @a))
          (note (check "B:1 derefs initial" 20 @b))

          (println "\n== 2. spin cell = formula over signals ==")
          (let [c (spin (+ @(track a) @(track b)))]
            (note (check "C:1 computes (pull)" 30 @c))

            (println "\n== 3. auto-propagation, NO explicit drain ==")
            (reset! a 99)
            (note (check "C:1 -> 119 via executor auto-drain" 119
                         (wait-deref c 119)))

            (println "\n== 4. remove spin ==")
            (spin-core/cleanup-spin! c)
            (note (check "cleanup-spin! ok" nil nil))

            (println "\n== 5. update: re-register C:1 new formula ==")
            (let [c2 (spin (* @(track a) @(track b)))]
              (note (check "C:1 new formula (pull)" 1980 @c2))
              (reset! b 2)
              (note (check "C:1 -> 198 via auto-drain" 198
                           (wait-deref c2 198))))))

        (let [all @results]
          (println "\n==== SPIKE:" (count (filter true? all)) "/" (count all) "passed ===="))))))

(defn -main [& _]
  (run)
  (shutdown-agents)
  (System/exit 0))
