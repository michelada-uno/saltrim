(ns uno.michelada.saltrim.auth-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]))

;; Users + tokens live in Datahike; each test runs against a fresh in-memory db.
(use-fixtures :each (fn [t] (db/init-mem!) (t)))

(deftest dev-login-and-cookie-roundtrip
  ;; no provider env vars in the test JVM -> dev auth active by default
  (is (auth/dev-auth?))
  (let [{:keys [token uid error]} (auth/dev-login! "Test Üser 42")]
    (is (nil? error))
    (testing "uid is sanitized: prefix, [a-z0-9-] only, no underscores"
      (is (= "dev-test-ser-42" uid))
      (is (re-matches auth/uid-re uid)))
    (testing "profile stored"
      (is (= "Test Üser 42" (:name (auth/user-info uid)))))
    (testing "token resolves through the auth cookie"
      (let [req {:headers {"cookie" (str "other=1; "
                                         (second (re-find #"^(saltrim_auth=[a-f0-9]+)"
                                                          (auth/auth-cookie token))))}}]
        (is (= uid (auth/req->uid req)))))
    (testing "revoked token no longer authenticates"
      (auth/revoke-token! token)
      (is (nil? (auth/req->uid {:headers {"cookie" (str "saltrim_auth=" token)}}))))))

(deftest dev-login-rejects-blank
  (is (:error (auth/dev-login! "")))
  (is (:error (auth/dev-login! "!!!"))))

(deftest cookie-attributes
  (let [c (auth/auth-cookie (apply str (repeat 64 "a")))]
    (is (re-find #"HttpOnly" c))
    (is (re-find #"SameSite=Lax" c))
    (is (re-find #"Path=/" c)))
  (is (re-find #"Max-Age=0" (auth/clear-cookie))))

(deftest req->uid-ignores-garbage
  (is (nil? (auth/req->uid {:headers {}})))
  (is (nil? (auth/req->uid {:headers {"cookie" "saltrim_auth=nothex"}})))
  (is (nil? (auth/req->uid {:headers {"cookie" (str "saltrim_auth=" (apply str (repeat 64 "f")))}}))))

(deftest resolve-grantee-dev-uses-name
  ;; dev mode (no provider env) — a name resolves to the deterministic dev uid,
  ;; whether or not that person has logged in yet.
  (is (auth/dev-auth?))
  (is (= "dev-bob"          (auth/resolve-grantee "Bob")))
  (is (= "dev-test-ser-42"  (auth/resolve-grantee "Test Üser 42"))
      "same sanitization as dev-login")
  (is (nil? (auth/resolve-grantee "   ")))
  (is (nil? (auth/resolve-grantee "!!!"))))

(deftest unknown-provider-rejected
  (is (:error (auth/callback! :nope "code" "state")))
  (is (nil? (auth/login-url :github))))   ; not configured in test env
