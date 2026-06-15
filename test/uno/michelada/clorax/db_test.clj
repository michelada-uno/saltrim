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

(deftest public-grant-toggle-and-access
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (db/ensure-sheet! "dev-ann__sheet" "dev-ann" "sheet")
  (testing "private by default"
    (is (false? (db/public? "dev-ann__sheet")))
    (is (nil?   (db/access-level "dev-bob" "dev-ann__sheet"))))
  (testing "going public adds an :everyone/:read-write grant reachable by anyone"
    (is (true? (db/set-public! "dev-ann__sheet" true)))
    (is (true? (db/public? "dev-ann__sheet")))
    (is (= :read-write (db/access-level "dev-bob" "dev-ann__sheet"))))
  (testing "set-public! is idempotent (no duplicate :everyone rows)"
    (db/set-public! "dev-ann__sheet" true)
    (is (= 1 (count (db/sheet-grants "dev-ann__sheet")))))
  (testing "going private removes the grant"
    (is (false? (db/set-public! "dev-ann__sheet" false)))
    (is (false? (db/public? "dev-ann__sheet")))
    (is (empty? (db/sheet-grants "dev-ann__sheet")))
    (is (nil?   (db/access-level "dev-bob" "dev-ann__sheet")))))
