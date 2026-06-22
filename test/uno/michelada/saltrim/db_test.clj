(ns uno.michelada.saltrim.db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datahike.api :as d]
            [mount.core :as mount]
            [uno.michelada.saltrim.db :as db]))

;; Fresh in-memory Datahike per test via mount (the `conn` state, mem-substituted).
(use-fixtures :each (fn [t] (db/start-mem!) (try (t) (finally (mount/stop)))))

(deftest user-upsert-and-lookup
  (is (nil? (db/user-info "gh-1")))
  (db/upsert-user! {:uid "gh-1" :name "Ann" :email "a@x.io" :provider "github"})
  (let [u (db/user-info "gh-1")]
    (is (= "Ann" (:name u)))
    (is (= "a@x.io" (:email u)))
    (is (some? (:created-at u))))
  (testing "re-upsert updates fields but preserves created-at"
    (let [c0 (:created-at (db/user-info "gh-1"))]
      (db/upsert-user! {:uid "gh-1" :name "Annie"})
      (is (= "Annie" (:name (db/user-info "gh-1"))))
      (is (= c0 (:created-at (db/user-info "gh-1"))) "created-at stable across upserts"))))

(deftest token-roundtrip-and-revoke
  (db/upsert-user! {:uid "dev-bob" :name "Bob"})
  (db/put-token! "abc-hash" "dev-bob")
  (is (= "dev-bob" (db/token-uid "abc-hash")))
  (testing "unknown token resolves to nil"
    (is (nil? (db/token-uid "nope"))))
  (testing "revoked token no longer resolves"
    (db/delete-token! "abc-hash")
    (is (nil? (db/token-uid "abc-hash")))))

(deftest isolation-between-tests
  ;; if the fixture didn't reset, a previous test's user would leak
  (is (nil? (db/user-info "gh-1")))
  (is (nil? (db/user-info "dev-bob"))))

(deftest sheet-register-is-idempotent
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (is (true?  (db/ensure-sheet! "dev-ann__budget" "dev-ann" "budget"))
      "first registration is new")
  (is (false? (db/ensure-sheet! "dev-ann__budget" "dev-ann" "budget"))
      "second registration is a no-op")
  (testing "owner ref is set when the user exists, skipped otherwise"
    (is (true? (db/ensure-sheet! "ghost__x" "ghost" "x")))) ; no such user — must not throw
  (testing "an unregistered sheet has no link and is private"
    (is (nil? (db/link-grant "nobody__none")))
    (is (nil? (db/access-level "anyone" "nobody__none" nil)))))

(deftest capability-link-levels-and-access
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__sheet" "dev-ann" "sheet")
  (testing "private by default — no link, no access even with a bogus token"
    (is (nil? (db/link-grant "dev-ann__sheet")))
    (is (nil? (db/access-level "dev-bob" "dev-ann__sheet" nil)))
    (is (nil? (db/access-level "dev-bob" "dev-ann__sheet" "deadbeef"))))
  (testing "enabling a view link mints a token; only the holder gets :read"
    (let [{:keys [token level]} (db/set-link-level! "dev-ann__sheet" :read)]
      (is (= :read level))
      (is (string? token))
      (is (= :read (db/access-level "dev-bob" "dev-ann__sheet" token)))
      (is (nil?    (db/access-level "dev-bob" "dev-ann__sheet" nil))
          "no token = no access (the name+sheet is no longer enough)")
      (is (nil?    (db/access-level "dev-bob" "dev-ann__sheet" "wrong")))))
  (testing "raising the level keeps the SAME token (update in place)"
    (let [t0 (:token (db/link-grant "dev-ann__sheet"))
          {:keys [token level]} (db/set-link-level! "dev-ann__sheet" :read-write)]
      (is (= t0 token) "token stable across a level change")
      (is (= :read-write level))
      (is (= 1 (count (db/sheet-grants "dev-ann__sheet"))) "still one link row")
      (is (= :read-write (db/access-level "dev-bob" "dev-ann__sheet" token)))))
  (testing "rotating mints a NEW token and invalidates the old one"
    (let [old (:token (db/link-grant "dev-ann__sheet"))
          {new :token} (db/rotate-link! "dev-ann__sheet")]
      (is (not= old new))
      (is (nil?       (db/access-level "dev-bob" "dev-ann__sheet" old)))
      (is (= :read-write (db/access-level "dev-bob" "dev-ann__sheet" new)))))
  (testing "nil removes the link entirely"
    (is (nil? (db/set-link-level! "dev-ann__sheet" nil)))
    (is (nil? (db/link-grant "dev-ann__sheet")))
    (is (empty? (db/sheet-grants "dev-ann__sheet")))))

(deftest sheet-by-link-token-reverse-lookup
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__sheet" "dev-ann" "sheet")
  (is (nil? (db/sheet-by-link-token "nope")))
  (let [{:keys [token]} (db/set-link-level! "dev-ann__sheet" :read)]
    (is (= "dev-ann__sheet" (db/sheet-by-link-token token))
        "a token resolves its sheet with no owner/name in the URL")
    (testing "rotating moves the lookup to the new token"
      (let [{new :token} (db/rotate-link! "dev-ann__sheet")]
        (is (nil? (db/sheet-by-link-token token)))
        (is (= "dev-ann__sheet" (db/sheet-by-link-token new)))))))

(deftest legacy-everyone-migrates-to-link
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__old" "dev-ann" "old")
  ;; simulate a pre-link public sheet (an :everyone grant from PR-A/B)
  (db/set-share! "dev-ann__old" "*" :everyone :read)
  (db/migrate-everyone->link! "dev-ann__old")
  (let [{:keys [token level]} (db/link-grant "dev-ann__old")]
    (is (= :read level) "level preserved")
    (is (string? token) "now a token-gated link")
    (is (= 1 (count (db/sheet-grants "dev-ann__old"))) "the :everyone row is gone")
    (is (= :read (db/access-level "dev-bob" "dev-ann__old" token))))
  (testing "calling again is a no-op"
    (let [t0 (:token (db/link-grant "dev-ann__old"))]
      (db/migrate-everyone->link! "dev-ann__old")
      (is (= t0 (:token (db/link-grant "dev-ann__old")))))))

(deftest direct-user-grants
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__sheet" "dev-ann" "sheet")
  (testing "a :user grant only reaches that user"
    (is (= :read (db/set-share! "dev-ann__sheet" "dev-bob" :user :read)))
    (is (= :read (db/access-level "dev-bob" "dev-ann__sheet" nil)))
    (is (nil?    (db/access-level "dev-cat" "dev-ann__sheet" nil))))
  (testing "grant-level reports the specific grant"
    (is (= :read (db/grant-level "dev-ann__sheet" "dev-bob" :user)))
    (is (nil?    (db/grant-level "dev-ann__sheet" "dev-cat" :user))))
  (testing "a view link combines with a user :read-write -> highest wins"
    (let [{tok :token} (db/set-link-level! "dev-ann__sheet" :read)]
      (db/set-share! "dev-ann__sheet" "dev-bob" :user :read-write)
      (is (= :read-write (db/access-level "dev-bob" "dev-ann__sheet" tok)))
      (is (= :read       (db/access-level "dev-cat" "dev-ann__sheet" tok)))))
  (testing "revoking the user grant drops Bob back to the link level"
    (let [tok (:token (db/link-grant "dev-ann__sheet"))]
      (is (true? (db/remove-share! "dev-ann__sheet" "dev-bob" :user)))
      (is (= :read (db/access-level "dev-bob" "dev-ann__sheet" tok)))))
  (testing "shared-with lists direct grants only (not the link)"
    (db/set-share! "dev-ann__sheet" "dev-cat" :user :read)
    (let [shared (db/sheets-shared-with "dev-cat")]
      (is (= [{:sheet-id "dev-ann__sheet" :name "sheet" :level :read}] shared)))
    (is (empty? (db/sheets-shared-with "dev-nobody")))))

(deftest uid-by-email-lookup
  (db/upsert-user! {:uid "gh-7" :name "Eve" :email "Eve@Example.io" :provider "github"})
  (is (= "gh-7" (db/uid-by-email "eve@example.io")) "case-insensitive match")
  (is (= "gh-7" (db/uid-by-email "  Eve@Example.io ")) "trimmed")
  (is (nil? (db/uid-by-email "missing@example.io")))
  (is (nil? (db/uid-by-email ""))))

;; --- sheet content: cells (per-property) + branch scalars -----------------

(def ^:private S "dev-ann__s")

(defn- cell-author [sid addr]
  (d/q '[:find ?a . :in $ ?k :where [?c :cellprop/key ?k] [?c :cellprop/author ?a]]
       @db/conn (str sid "|main|" addr "|value")))

(deftest cell-doc-roundtrip-and-diff
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (let [doc {"A1" {:value "99"}
             "B1" {:value "=(* #cell A1 2)"}
             "A2" {:value "=(+ 1 2)" :style {:bg "tomato" :align "center"}}}]
    (db/save-doc! S doc "dev-ann")
    (is (= doc (db/sheet-doc S)) "document round-trips (value + style props)")
    (testing "diff-save: re-saving the SAME doc writes nothing"
      (is (= {:changed 0 :removed 0} (db/save-doc! S doc "dev-ann"))))
    (testing "change one prop + drop one cell -> only those touched"
      (let [doc2 (-> doc (assoc-in ["A1" :value] "100") (dissoc "B1"))]
        (is (= {:changed 1 :removed 1} (db/save-doc! S doc2 "dev-ann")))
        (let [d (db/sheet-doc S)]
          (is (= "100" (get-in d ["A1" :value])))
          (is (not (contains? d "B1")) "B1 retracted")
          (is (= {:bg "tomato" :align "center"} (get-in d ["A2" :style])) "untouched style kept"))))))

(deftest cell-author-and-history
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/upsert-user! {:uid "dev-bob" :name "Bob"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "5"}}  "dev-ann")
  (db/save-doc! S {"A1" {:value "10"}} "dev-bob")
  (testing "current author = last writer (drives 'undo my changes')"
    (is (= "dev-bob" (cell-author S "A1"))))
  (testing "every value retained in history (as-of/undo source)"
    (is (= #{"5" "10"}
           (set (d/q '[:find [?v ...] :in $ ?k :where [?c :cellprop/key ?k] [?c :cellprop/src ?v]]
                     (d/history @db/conn) (str S "|main|A1|value")))))))

(deftest branch-meta-and-fork
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "1"}} "dev-ann")
  (db/set-branch-meta! S {:dcw 60 :drh 20 :defs "[{:id \"d1\" :src \"(def k 1)\"}]"})
  (is (= 60 (:dcw (db/branch-meta S))))
  (is (= "[{:id \"d1\" :src \"(def k 1)\"}]" (:defs (db/branch-meta S))))
  (testing "diff-upsert: re-setting the same scalars reports no change"
    (is (empty? (db/set-branch-meta! S {:dcw 60 :drh 20}))))
  (testing "fork copies cells + scalars; then branches diverge"
    (db/fork-branch! S "main" "exp")
    (is (= (db/sheet-doc S "main") (db/sheet-doc S "exp")) "exp starts identical")
    (is (= 60 (:dcw (db/branch-meta S "exp"))) "scalars copied")
    (db/save-doc! S "exp" {"A1" {:value "777"}} "dev-ann")
    (is (= "1"   (get-in (db/sheet-doc S "main") ["A1" :value])) "main untouched")
    (is (= "777" (get-in (db/sheet-doc S "exp")  ["A1" :value])) "exp diverged")))

(deftest sheets-of-owner-lists-registered
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__a" "dev-ann" "a")
  (db/ensure-sheet! "dev-ann__b" "dev-ann" "b")
  (is (= ["dev-ann__a" "dev-ann__b"] (db/sheets-of-owner "dev-ann")))
  (is (empty? (db/sheets-of-owner "dev-nobody"))))

(deftest branch-names-exists-and-listing
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "1"}} "dev-ann")
  (testing "main always exists / lists, even with no :branch entity"
    (is (db/branch-exists? S "main"))
    (is (= ["main"] (db/branch-names S))))
  (testing "an unknown branch does not exist"
    (is (not (db/branch-exists? S "nope"))))
  (testing "a fork shows up in names + exists"
    (db/fork-branch! S "main" "exp")
    (is (db/branch-exists? S "exp"))
    (is (= ["exp" "main"] (db/branch-names S)))))

(deftest fork-records-lineage
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "10"}} "dev-ann")
  (db/fork-branch! S "main" "exp")
  (let [m (d/pull @db/conn [:branch/parent :branch/base-tx]
                  [:branch/key (str S "|exp")])]
    (is (= "main" (:branch/parent m)) "records the parent branch")
    (is (integer? (:branch/base-tx m)) "records the fork-point tx (a long)"))
  (testing "the recorded base-tx reconstructs the fork-point doc via as-of"
    (let [base (:branch/base-tx (d/pull @db/conn [:branch/base-tx] [:branch/key (str S "|exp")]))]
      ;; diverge BOTH branches after the fork
      (db/save-doc! S "main" {"A1" {:value "999"}} "dev-ann")
      (db/save-doc! S "exp"  {"A1" {:value "555"}} "dev-ann")
      (is (= "999" (get-in (db/sheet-doc S "main") ["A1" :value])))
      (is (= "555" (get-in (db/sheet-doc S "exp")  ["A1" :value])))
      ;; as-of base = main at the fork instant = "10" (the common ancestor)
      (let [as-of-doc (->> (d/q '[:find ?addr ?prop ?src :in $ ?sid ?br
                                   :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                                          [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                                          [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]]
                                 (d/as-of @db/conn base) S "main")
                           (some (fn [[a p s]] (when (and (= a "A1") (= p :value)) s))))]
        (is (= "10" as-of-doc) "as-of base-tx yields the pre-divergence value")))))

(deftest merge-base-and-asof
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "10"} "B1" {:value "20"} "C1" {:value "30"}} "dev-ann")
  (db/fork-branch! S "main" "exp")
  ;; diverge both branches after the fork
  (db/save-doc! S "main" {"A1" {:value "99"} "B1" {:value "20"} "C1" {:value "30"}} "dev-ann")
  (db/save-doc! S "exp"  {"A1" {:value "11"} "B1" {:value "20"} "C1" {:value "30"}} "dev-ann")
  (testing "merge-base = the common ancestor (parent doc at the fork point)"
    (let [base (db/merge-base S "exp" "main")]      ; exp forked from main
      (is (= "10" (get-in base ["A1" :value])) "base A1 is the pre-divergence value")
      (is (= {"A1" {:value "10"} "B1" {:value "20"} "C1" {:value "30"}} base)))
    (testing "symmetric: merging main into exp resolves the same ancestor"
      (is (= "10" (get-in (db/merge-base S "main" "exp") ["A1" :value])))))
  (testing "branch-lineage exposes parent + base-tx; main has none"
    (is (= "main" (:parent (db/branch-lineage S "exp"))))
    (is (integer? (:base-tx (db/branch-lineage S "exp"))))
    (is (nil? (db/branch-lineage S "main"))))
  (testing "unrelated branches have no common ancestor"
    (db/fork-branch! S "main" "other")             ; sibling of exp (both off main)
    ;; exp and other are siblings off main → common ancestor IS resolvable
    (is (some? (db/merge-base S "exp" "other")) "siblings share their parent as base")))

(deftest delete-branch-isolation
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! S "dev-ann" "s")
  (db/save-doc! S {"A1" {:value "1"} "B1" {:value "2"}} "dev-ann")
  (db/fork-branch! S "main" "exp")
  (db/save-doc! S "exp" {"A1" {:value "x"} "B1" {:value "y"} "C1" {:value "z"}} "dev-ann")
  (testing "delete drops only the branch's datoms; main is untouched"
    (let [removed (db/delete-branch! S "exp")]
      (is (= 3 removed) "all exp cellprops removed")
      (is (empty? (db/sheet-doc S "exp")) "exp doc now empty")
      (is (not (db/branch-exists? S "exp")))
      (is (= ["main"] (db/branch-names S)))
      (is (= {"A1" {:value "1"} "B1" {:value "2"}} (db/sheet-doc S "main")) "main intact")))
  (testing "main can't be deleted"
    (is (nil? (db/delete-branch! S "main")))
    (is (db/branch-exists? S "main"))))
