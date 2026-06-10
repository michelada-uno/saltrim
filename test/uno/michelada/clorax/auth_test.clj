(ns uno.michelada.clorax.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.clorax.auth :as auth]))

;; NOTE: dev-login!/tokens persist to data/users.edn + data/tokens.edn. Tests
;; revoke the tokens they mint; the dev-test user entry merging is idempotent.

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
                                         (second (re-find #"^(clorax_auth=[a-f0-9]+)"
                                                          (auth/auth-cookie token))))}}]
        (is (= uid (auth/req->uid req)))))
    (testing "revoked token no longer authenticates"
      (auth/revoke-token! token)
      (is (nil? (auth/req->uid {:headers {"cookie" (str "clorax_auth=" token)}}))))))

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
  (is (nil? (auth/req->uid {:headers {"cookie" "clorax_auth=nothex"}})))
  (is (nil? (auth/req->uid {:headers {"cookie" (str "clorax_auth=" (apply str (repeat 64 "f")))}}))))

(deftest unknown-provider-rejected
  (is (:error (auth/callback! :nope "code" "state")))
  (is (nil? (auth/login-url :github))))   ; not configured in test env
