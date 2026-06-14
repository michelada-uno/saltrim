(ns uno.michelada.clorax.auth
  "Identity layer: OAuth 2.0 login (authorization-code flow), auth-token
   cookies, and the persistent user registry.

   Providers are data (`provider-defs`); a provider is active when its client
   id/secret env vars are set. When NO real provider is configured the `dev`
   provider activates by default (name-only login, local development) — set
   CLORAX_DEV_AUTH=1/0 to force it on/off explicitly.

   User ids are `<prefix>-<external-id>` sanitized to [a-z0-9-] — deliberately
   NO underscores, so the `<uid>__<sheet-name>` storage key (see store/web)
   splits unambiguously on the first \"__\".

   State: users and auth tokens live in Datahike (see `db`); tokens are stored
   as a SHA-256 hash of the cookie secret. OAuth `state` nonces are in-memory
   with a TTL (they only need to outlive one redirect)."
  (:require [clojure.string :as str]
            [org.httpkit.client :as hc]
            [jsonista.core :as json]
            [uno.michelada.clorax.db :as db]
            [uno.michelada.clorax.util :as util])
  (:import [java.security SecureRandom MessageDigest]))

;; --- config ---------------------------------------------------------------

(defn base-url []
  (or (util/env "CLORAX_BASE_URL") "http://localhost:8080"))

(def ^:private provider-defs
  {:github {:label      "GitHub"
            :id-env     "CLORAX_GITHUB_CLIENT_ID"
            :secret-env "CLORAX_GITHUB_CLIENT_SECRET"
            :authorize  "https://github.com/login/oauth/authorize"
            :token      "https://github.com/login/oauth/access_token"
            :userinfo   "https://api.github.com/user"
            :scope      "read:user"
            :prefix     "gh"
            :extract    (fn [u] {:ext-id (str (get u "id"))
                                 :name   (or (get u "name") (get u "login"))
                                 :email  (get u "email")
                                 :avatar (get u "avatar_url")})}
   :google {:label      "Google"
            :id-env     "CLORAX_GOOGLE_CLIENT_ID"
            :secret-env "CLORAX_GOOGLE_CLIENT_SECRET"
            :authorize  "https://accounts.google.com/o/oauth2/v2/auth"
            :token      "https://oauth2.googleapis.com/token"
            :userinfo   "https://openidconnect.googleapis.com/v1/userinfo"
            :scope      "openid email profile"
            :prefix     "gg"
            :extract    (fn [u] {:ext-id (str (get u "sub"))
                                 :name   (or (get u "name") (get u "email"))
                                 :email  (get u "email")
                                 :avatar (get u "picture")})}})

(defn providers
  "Active OAuth providers: {key {:label :client-id :client-secret ...}}."
  []
  (into {}
        (keep (fn [[k {:keys [id-env secret-env] :as p}]]
                (when-let [id (util/env id-env)]
                  (when-let [secret (util/env secret-env)]
                    [k (assoc p :client-id id :client-secret secret)]))))
        provider-defs))

(defn dev-auth?
  "Name-only dev login. Defaults to ON when no real provider is configured;
   CLORAX_DEV_AUTH=1/0 (or true/false) overrides either way."
  []
  (let [v (util/env "CLORAX_DEV_AUTH")]
    (cond
      (contains? #{"1" "true" "yes"} v) true
      (contains? #{"0" "false" "no"} v) false
      :else (empty? (providers)))))

;; --- user registry (Datahike) ----------------------------------------------

(defn user-info [uid] (db/user-info uid))

(defn- upsert-user! [uid profile]
  (db/upsert-user! (assoc profile :uid uid)))

;; --- ids / tokens -----------------------------------------------------------

(def uid-re #"[a-z0-9][a-z0-9-]{0,23}")

(defn- sanitize-id
  "Lower-case, collapse anything outside [a-z0-9] to single dashes, trim."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- make-uid [prefix ext-id]
  (let [id (sanitize-id ext-id)
        uid (str prefix "-" id)]
    (subs uid 0 (min 24 (count uid)))))

(def ^:private rng (SecureRandom.))

(defn- rand-hex [n-bytes]
  (let [bs (byte-array n-bytes)]
    (.nextBytes rng bs)
    (apply str (map #(format "%02x" %) bs))))

(defn- sha256 [s]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))
       (map #(format "%02x" %))
       (apply str)))

(defn- mint-token!
  "Mint a token: persist its HASH against `uid`, return the secret for the
   cookie (the DB never stores the secret itself)."
  [uid]
  (let [secret (rand-hex 32)]
    (db/put-token! (sha256 secret) uid)
    secret))

(defn revoke-token! [secret]
  (when secret (db/delete-token! (sha256 secret))))

;; --- cookies ----------------------------------------------------------------

(def ^:private cookie-name "clorax_auth")
(def ^:private COOKIE-MAX-AGE (* 30 24 60 60)) ; 30 days

(defn- secure? [] (str/starts-with? (base-url) "https"))

(defn auth-cookie [tok]
  (str cookie-name "=" tok "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" COOKIE-MAX-AGE
       (when (secure?) "; Secure")))

(defn clear-cookie []
  (str cookie-name "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"))

(defn req->token [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|;\\s*)" cookie-name "=([a-f0-9]{64})")))
           second))

(defn req->uid
  "The authenticated user id for a request, or nil."
  [req]
  (some-> (req->token req) sha256 db/token-uid))

;; --- OAuth state (CSRF nonce) -------------------------------------------------

(def ^:private STATE-TTL-MS (* 10 60 1000))
(defonce ^:private states* (atom {}))

(defn- sweep-states! []
  (let [cutoff (- (System/currentTimeMillis) STATE-TTL-MS)]
    (swap! states* (fn [m] (into {} (filter #(> (long (:at (val %))) cutoff)) m)))))

(defn- mint-state! [provider-key]
  (sweep-states!)
  (let [s (rand-hex 16)]
    (swap! states* assoc s {:provider provider-key :at (System/currentTimeMillis)})
    s))

(defn- take-state!
  "Consume a state nonce; true iff it was valid for `provider-key`."
  [provider-key s]
  (let [v (get @states* s)]
    (swap! states* dissoc s)
    (boolean (and v (= provider-key (:provider v))))))

;; --- OAuth flow ---------------------------------------------------------------

(defn- redirect-uri [provider-key]
  (str (base-url) "/auth/" (name provider-key) "/callback"))

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn login-url
  "Authorize-endpoint redirect for an active provider, or nil."
  [provider-key]
  (when-let [p (get (providers) provider-key)]
    (str (:authorize p)
         "?client_id=" (url-encode (:client-id p))
         "&redirect_uri=" (url-encode (redirect-uri provider-key))
         "&scope=" (url-encode (:scope p))
         "&response_type=code"
         "&state=" (mint-state! provider-key))))

(defn- parse-json [s] (json/read-value s))

(defn- exchange-code!
  "code -> access token (or nil). Both GitHub and Google accept form params and
   return JSON when asked."
  [{:keys [token client-id client-secret]} provider-key code]
  (let [resp @(hc/post token {:headers {"Accept" "application/json"}
                              :form-params {"client_id"     client-id
                                            "client_secret" client-secret
                                            "code"          code
                                            "grant_type"    "authorization_code"
                                            "redirect_uri"  (redirect-uri provider-key)}})]
    (when (= 200 (:status resp))
      (get (parse-json (:body resp)) "access_token"))))

(defn- fetch-userinfo! [{:keys [userinfo]} access-token]
  (let [resp @(hc/get userinfo {:headers {"Authorization" (str "Bearer " access-token)
                                          "Accept" "application/json"}})]
    (when (= 200 (:status resp))
      (parse-json (:body resp)))))

(defn callback!
  "Complete the code flow: validate state, exchange the code, fetch the user,
   upsert + mint an auth token. Returns {:token :uid} or {:error msg}."
  [provider-key code state]
  (let [p (get (providers) provider-key)]
    (cond
      (nil? p)                          {:error "unknown provider"}
      (not (take-state! provider-key state)) {:error "bad state (retry login)"}
      (str/blank? (str code))           {:error "missing code"}
      :else
      (try
        (if-let [at (exchange-code! p provider-key code)]
          (if-let [u (fetch-userinfo! p at)]
            (let [{:keys [ext-id] :as profile} ((:extract p) u)
                  uid (make-uid (:prefix p) ext-id)]
              (upsert-user! uid (assoc profile :provider (name provider-key)))
              {:token (mint-token! uid) :uid uid})
            {:error "userinfo fetch failed"})
          {:error "code exchange failed"})
        (catch Throwable e
          {:error (str "oauth error: " (.getMessage e))})))))

(defn dev-login!
  "Name-only login (dev provider). Returns {:token :uid} or {:error msg}."
  [display-name]
  (if-not (dev-auth?)
    {:error "dev login disabled"}
    (let [id (sanitize-id display-name)]
      (if (str/blank? id)
        {:error "name required"}
        (let [uid (make-uid "dev" id)]
          (upsert-user! uid {:provider "dev" :ext-id id :name (str display-name)
                             :email nil :avatar nil})
          {:token (mint-token! uid) :uid uid})))))
