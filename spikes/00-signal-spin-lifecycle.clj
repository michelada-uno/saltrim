(ns spikes.signal-spin-lifecycle
  "Step 0 — reactive cell lifecycle on real Spindel.

   Findings that shape the architecture:
   * Signal mutation (reset!/swap!) goes through enqueue! → the drain runs on the
     context executor (virtual thread, JDK21+). Propagation is AUTOMATIC and
     ASYNC — app code never pumps a drain loop.
   * Spins are PULL/lazy + cached: they recompute when deref'd after their deps
     went dirty. The eager `effect` root is cljs/DOM-only on the JVM.
   * So the viewport model is PULL-ON-VISIBLE: mutate a cell's signal → executor
     auto-propagates dirtiness → we deref only the VISIBLE spins for SSE.

   REPL walkthrough: eval the forms in the (comment …) block one at a time.")

(comment
  (require '[org.replikativ.spindel.spin.cps :as cps]      ; cps/spin macro
           '[org.replikativ.spindel.spin.core :as spin-core]
           '[org.replikativ.spindel.effects.track :as trk] ; trk/track
           '[org.replikativ.spindel.signal :as sig]
           '[org.replikativ.spindel.engine.core :as ec]
           '[org.replikativ.spindel.engine.context :as ctx])

  ;; poll @spin until it equals expected (NO drain pumping — proves the executor
  ;; settles on its own).
  (defn wait-deref [spin expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop [] (let [v @spin]
                 (if (or (= v expected) (> (System/currentTimeMillis) deadline))
                   v (do (Thread/sleep 5) (recur)))))))

  (def rt (ctx/create-execution-context))

  ;; everything below runs with the context bound:
  (binding [ec/*execution-context* rt]

    ;; ── 1. signal cells ──
    (def a (doto (sig/->SignalRef "A:1" 10) sig/ensure-signal-initialized!))
    (def b (doto (sig/->SignalRef "B:1" 20) sig/ensure-signal-initialized!))
    @a                                   ;; => 10
    @b                                   ;; => 20

    ;; ── 2. spin cell = formula over signals ──
    (def c (cps/spin (+ @(trk/track a) @(trk/track b))))
    @c                                   ;; => 30   (pull/compute on deref)

    ;; ── 3. auto-propagation, NO explicit drain ──
    (reset! a 99)
    (wait-deref c 119)                   ;; => 119  (executor drained on its own)

    ;; ── 4. remove a spin ──
    (spin-core/cleanup-spin! c)          ;; => nil

    ;; ── 5. re-register C with a new formula ──
    (def c2 (cps/spin (* @(trk/track a) @(trk/track b))))
    @c2                                  ;; => 1980
    (reset! b 2)
    (wait-deref c2 198)))                ;; => 198
