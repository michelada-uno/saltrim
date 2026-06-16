(ns spikes.formula-string-to-spin
  "Step 0b — a runtime formula STRING compiles to a Spin (real spin macro via
   eval), wired to signals by address, recomputing on change, sandboxed by the
   symbol whitelist.

   (This is the path the SCI migration replaces — see 03-sci-formula-eval.clj —
   but the parse/deps/address machinery it exercises stays.)

   REPL walkthrough: eval the forms in the (comment …) block one at a time.")

(comment
  (require '[uno.michelada.saltrim.addr :as addr]
           '[uno.michelada.saltrim.formula :as formula]
           '[org.replikativ.spindel.signal :as sig]
           '[org.replikativ.spindel.engine.core :as ec]
           '[org.replikativ.spindel.engine.context :as ctx])

  (defn wait-deref [spin expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop [] (let [v @spin]
                 (if (or (= v expected) (> (System/currentTimeMillis) deadline))
                   v (do (Thread/sleep 5) (recur)))))))

  ;; ── addressing utils ──
  (addr/col->idx "AAB")                 ;; => 703
  (addr/idx->col 703)                   ;; => "AAB"
  (addr/make 27 1233)                   ;; => "AB:1234"  (round-trip)

  ;; ── parse + deps ──
  (formula/parse "(+ #cell A:1 #cell B:1)")
  ;; => {:form (+ (:uno…/ref "A:1") (:uno…/ref "B:1")) :deps #{"A:1" "B:1"}}

  ;; ── compile string → Spin, wired to a registry of signals ──
  (def registry (atom {}))
  (def rt (ctx/create-execution-context {:metadata {:registry registry}}))

  (binding [ec/*execution-context* rt]
    (let [a (doto (sig/->SignalRef "A:1" 10) sig/ensure-signal-initialized!)
          b (doto (sig/->SignalRef "B:1" 20) sig/ensure-signal-initialized!)]
      (swap! registry assoc "A:1" a "B:1" b)

      (def c (formula/compile (:form (formula/parse "(+ #cell A:1 #cell B:1)"))))
      @c                                ;; => 30
      (reset! a 99)
      (wait-deref c 119)                ;; => 119  (auto-drain)

      ;; ── sandbox: a disallowed symbol is rejected at compile ──
      (try (formula/compile (:form (formula/parse "(slurp \"/etc/passwd\")")))
           :NOT-rejected
           (catch clojure.lang.ExceptionInfo _ :rejected)))))  ;; => :rejected
