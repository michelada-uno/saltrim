(ns uno.michelada.saltrim.db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
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
