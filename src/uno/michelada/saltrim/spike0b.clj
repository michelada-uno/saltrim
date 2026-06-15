(ns uno.michelada.saltrim.spike0b
  "Step 0b — runtime formula STRING -> Spin (real spin macro via eval),
   wired to signals by address, recomputing on change, sandboxed by whitelist.

   Run:  clj -M:spike0b"
  (:require [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.formula :as formula]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn wait-deref [spin expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v @spin]
        (if (or (= v expected) (> (System/currentTimeMillis) deadline))
          v (do (Thread/sleep 5) (recur)))))))

(defn check [label expected actual]
  (let [ok? (= expected actual)]
    (println (if ok? "  PASS" "  FAIL") label "| exp" (pr-str expected) "got" (pr-str actual))
    ok?))

(defn run []
  (let [registry (atom {})
        rt       (ctx/create-execution-context {:metadata {:registry registry}})
        results  (atom [])
        note     (fn [r] (swap! results conj r))]
    (binding [ec/*execution-context* rt]
      (let [a (sig/->SignalRef "A:1" 10)
            b (sig/->SignalRef "B:1" 20)]
        (sig/ensure-signal-initialized! a)
        (sig/ensure-signal-initialized! b)
        (swap! registry assoc "A:1" a "B:1" b)

        (println "\n== addressing utils ==")
        (note (check "col->idx AAB" 703 (addr/col->idx "AAB")))
        (note (check "idx->col 703" "AAB" (addr/idx->col 703)))
        (note (check "round-trip" "AB:1234" (addr/make 27 1233)))

        (println "\n== parse + deps ==")
        (let [{:keys [form deps]} (formula/parse "(+ #cell A:1 #cell B:1)")]
          (note (check "deps" #{"A:1" "B:1"} deps))
          (println "  form =>" (pr-str form))

          (println "\n== compile string -> Spin ==")
          (let [c (formula/compile form)]
            (note (check "C = 30" 30 @c))
            (reset! a 99)
            (note (check "C -> 119 (auto-drain)" 119 (wait-deref c 119)))

            (println "\n== second formula ==")
            (let [c2 (formula/compile (:form (formula/parse "(* #cell A:1 #cell B:1)")))]
              (note (check "D = 99*20" 1980 @c2))
              (reset! b 2)
              (note (check "D -> 198" 198 (wait-deref c2 198))))))

        (println "\n== sandbox: reject disallowed symbol ==")
        (note (check "rejects (slurp ...)" :rejected
                     (try (formula/compile (:form (formula/parse "(slurp \"/etc/passwd\")")))
                          :NOT-rejected
                          (catch clojure.lang.ExceptionInfo _ :rejected))))

        (let [all @results]
          (println "\n==== SPIKE 0b:" (count (filter true? all)) "/" (count all) "passed ===="))))))

(defn -main [& _] (run) (shutdown-agents) (System/exit 0))
