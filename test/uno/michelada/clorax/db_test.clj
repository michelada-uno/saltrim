(ns uno.michelada.clorax.db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [uno.michelada.clorax.db :as db]))

;; Fresh in-memory Datahike per test (CLORAX_DB_BACKEND-independent).
(use-fixtures :each (fn [t] (db/init-mem!) (t)))

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
  (testing "an unregistered sheet has no grants and is private"
    (is (false? (db/public? "nobody__none")))
    (is (nil?   (db/access-level "anyone" "nobody__none")))))

(deftest public-grant-levels-and-access
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__sheet" "dev-ann" "sheet")
  (testing "private by default"
    (is (nil? (db/public-level "dev-ann__sheet")))
    (is (false? (db/public? "dev-ann__sheet")))
    (is (nil?   (db/access-level "dev-bob" "dev-ann__sheet"))))
  (testing "public read-only grants :read to anyone (not :read-write)"
    (is (= :read (db/set-public! "dev-ann__sheet" :read)))
    (is (= :read (db/public-level "dev-ann__sheet")))
    (is (= :read (db/access-level "dev-bob" "dev-ann__sheet"))))
  (testing "raising to read-write updates in place (no duplicate :everyone row)"
    (is (= :read-write (db/set-public! "dev-ann__sheet" :read-write)))
    (is (= 1 (count (db/sheet-grants "dev-ann__sheet"))))
    (is (= :read-write (db/access-level "dev-bob" "dev-ann__sheet"))))
  (testing "nil removes the public grant"
    (is (nil? (db/set-public! "dev-ann__sheet" nil)))
    (is (false? (db/public? "dev-ann__sheet")))
    (is (empty? (db/sheet-grants "dev-ann__sheet")))
    (is (nil?   (db/access-level "dev-bob" "dev-ann__sheet")))))

(deftest direct-user-grants
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__sheet" "dev-ann" "sheet")
  (testing "a :user grant only reaches that user"
    (is (= :read (db/set-share! "dev-ann__sheet" "dev-bob" :user :read)))
    (is (= :read (db/access-level "dev-bob" "dev-ann__sheet")))
    (is (nil?    (db/access-level "dev-cat" "dev-ann__sheet"))))
  (testing "grant-level reports the specific grant"
    (is (= :read (db/grant-level "dev-ann__sheet" "dev-bob" :user)))
    (is (nil?    (db/grant-level "dev-ann__sheet" "dev-cat" :user))))
  (testing "a public :read combines with a user :read-write -> highest wins"
    (db/set-public! "dev-ann__sheet" :read)
    (db/set-share! "dev-ann__sheet" "dev-bob" :user :read-write)
    (is (= :read-write (db/access-level "dev-bob" "dev-ann__sheet")))
    (is (= :read       (db/access-level "dev-cat" "dev-ann__sheet"))))
  (testing "revoking the user grant drops them back to the public level"
    (is (true? (db/remove-share! "dev-ann__sheet" "dev-bob" :user)))
    (is (= :read (db/access-level "dev-bob" "dev-ann__sheet"))))
  (testing "shared-with lists direct grants only (not public)"
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
