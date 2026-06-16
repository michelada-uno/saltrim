(ns spikes.await-uniform-model
  "Step 4 — the uniform model: EVERY cell is a Spin; cross-cell refs use `await`
   (which handles Spins, unlike `track` which only handles SignalRefs). A literal
   cell is a spin over its editable signal. Proves formula→formula cascades and
   edit propagation through several hops.

   REPL walkthrough: eval the forms in the (comment …) block one at a time.")

(comment
  (require '[org.replikativ.spindel.spin.cps :as cps]      ; cps/spin
           '[org.replikativ.spindel.effects.track :as trk] ; trk/track
           '[org.replikativ.spindel.effects.await :as awt] ; awt/await
           '[org.replikativ.spindel.signal :as sig]
           '[org.replikativ.spindel.engine.core :as ec]
           '[org.replikativ.spindel.engine.context :as ctx])

  (defn wait-deref [s expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop [] (let [v @s]
                 (if (or (= v expected) (> (System/currentTimeMillis) deadline))
                   v (do (Thread/sleep 5) (recur)))))))

  (def rt (ctx/create-execution-context))

  (binding [ec/*execution-context* rt]
    (let [av (doto (sig/->SignalRef "A:1" 10) sig/ensure-signal-initialized!)
          bv (doto (sig/->SignalRef "B:1" 20) sig/ensure-signal-initialized!)]
      ;; literal cells = spin over the editable signal (via track)
      (def a (cps/spin @(trk/track av)))
      (def b (cps/spin @(trk/track bv)))
      ;; formula cells reference OTHER cells via await (cross-Spin)
      (def c (cps/spin (+ (awt/await a) (awt/await b))))   ; C = A + B
      (def d (cps/spin (+ (awt/await c) 1)))               ; D = C + 1  (formula→formula!)

      ;; ── initial ──
      @a                                ;; => 10
      @c                                ;; => 30
      @d                                ;; => 31   (formula over a formula)

      ;; ── edit A:1 → 100, watch the cascade ──
      (reset! av 100)
      (wait-deref c 120)                ;; => 120
      (wait-deref d 121))))             ;; => 121
