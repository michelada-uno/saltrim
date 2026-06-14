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
