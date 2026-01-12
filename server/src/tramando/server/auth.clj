(ns tramando.server.auth
  (:require [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [tramando.server.config :refer [config]]
            [tramando.server.db :as db])
  (:import [java.time Instant Duration]))

;; =============================================================================
;; Rate Limiting for Registration (anti-spam)
;; =============================================================================

;; In-memory store: {ip -> [{timestamp} ...]}
(defonce registration-attempts (atom {}))

(def ^:private max-attempts-per-hour 5)
(def ^:private one-hour-ms (* 60 60 1000))

(defn- clean-old-attempts!
  "Remove attempts older than 1 hour"
  []
  (let [cutoff (- (System/currentTimeMillis) one-hour-ms)]
    (swap! registration-attempts
           (fn [attempts]
             (into {}
                   (for [[ip timestamps] attempts
                         :let [valid (filterv #(> % cutoff) timestamps)]
                         :when (seq valid)]
                     [ip valid]))))))

(defn- get-client-ip
  "Extract client IP from request, handling X-Forwarded-For for proxies"
  [request]
  (or (get-in request [:headers "x-forwarded-for"])
      (get-in request [:headers "x-real-ip"])
      (:remote-addr request)
      "unknown"))

(defn check-rate-limit
  "Check if IP has exceeded registration rate limit.
   Returns {:ok true} if allowed, {:error message} if blocked."
  [request]
  (clean-old-attempts!)
  (let [ip (get-client-ip request)
        attempts (get @registration-attempts ip [])]
    (if (>= (count attempts) max-attempts-per-hour)
      {:error "Troppe richieste. Riprova più tardi."}
      {:ok true})))

(defn record-registration-attempt!
  "Record a registration attempt for rate limiting"
  [request]
  (let [ip (get-client-ip request)]
    (swap! registration-attempts
           update ip
           (fn [attempts]
             (conj (or attempts []) (System/currentTimeMillis))))))

;; =============================================================================
;; Password Hashing
;; =============================================================================

(defn hash-password [password]
  (hashers/derive password))

(defn check-password [password hash]
  (:valid (hashers/verify password hash)))

;; =============================================================================
;; JWT Token Management
;; =============================================================================

(defn generate-token
  "Generate JWT token including token_version for session invalidation."
  [user]
  (let [now (Instant/now)
        exp (.plus now (Duration/ofHours (:jwt-expiration-hours config)))
        token-version (or (:token_version user) 1)]
    (jwt/sign {:user-id (:id user)
               :username (:username user)
               :is-super-admin (= 1 (:is_super_admin user))
               :token-version token-version
               :iat (.getEpochSecond now)
               :exp (.getEpochSecond exp)}
              (:jwt-secret config))))

(defn verify-token
  "Verify JWT token and check token_version against current user version.
   Returns nil if token is invalid or session has been invalidated."
  [token]
  (try
    (when-let [claims (jwt/unsign token (:jwt-secret config))]
      ;; Verify token version matches current user's version
      (let [user (db/find-user-by-id (:user-id claims))
            current-version (or (:token_version user) 1)
            token-version (or (:token-version claims) 1)]
        (when (not= current-version token-version)
          (println "Token version mismatch for user" (:username claims)
                   "- token:" token-version "db:" current-version))
        (if (= current-version token-version)
          claims
          nil))) ;; Token version mismatch = session invalidated
    (catch Exception _
      nil)))

;; =============================================================================
;; Auth Operations
;; =============================================================================

(defn register!
  "Register a new user. First user becomes super-admin and active.
   Other users are created with status 'pending' and need admin approval."
  [{:keys [username password]}]
  (let [current-users (db/count-users)
        max-users (:max-users config)]
    (cond
      (not (:allow-registration config))
      {:error "Registration is disabled"}

      ;; Check max users limit (if configured)
      (and max-users (>= current-users max-users))
      {:error "Registrazioni chiuse: numero massimo di utenti raggiunto."}

      (< (count password) 6)
      {:error "Password must be at least 6 characters"}

      (db/find-user-by-username username)
      {:error "Username already taken"}

      :else
      (let [is-first-user? (zero? current-users)
            user (db/create-user! {:username username
                                   :password-hash (hash-password password)
                                   :is-super-admin is-first-user?})]
        (if is-first-user?
          ;; First user: login immediately
          {:user (dissoc user :password_hash)
           :token (generate-token user)}
          ;; Other users: pending approval
          {:pending true
           :message "Registrazione completata. Attendi l'approvazione dell'amministratore."})))))

(defn login!
  "Authenticate user and return token. Check user status.
   Increments token_version to invalidate any previous sessions (single-login enforcement)."
  [{:keys [username password]}]
  (if-let [user (db/find-user-by-username username)]
    (cond
      ;; Check password first
      (not (check-password password (:password_hash user)))
      {:error "Invalid password"}

      ;; Check user status
      (= "pending" (:status user))
      {:error "Account in attesa di approvazione"}

      (= "suspended" (:status user))
      {:error "Account sospeso"}

      ;; All good - increment token version to invalidate previous sessions
      :else
      (let [new-version (db/increment-token-version! (:id user))
            updated-user (assoc user :token_version new-version)]
        {:user (dissoc updated-user :password_hash)
         :token (generate-token updated-user)}))
    {:error "User not found"}))

(defn get-current-user
  "Get user from token claims"
  [claims]
  (when claims
    (when-let [user (db/find-user-by-id (:user-id claims))]
      (dissoc user :password_hash))))

;; =============================================================================
;; Middleware
;; =============================================================================

(defn wrap-auth
  "Middleware that extracts JWT from Authorization header and adds :user to request"
  [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          token (when (and auth-header (.startsWith auth-header "Bearer "))
                  (subs auth-header 7))
          claims (when token (verify-token token))
          user (when claims (get-current-user claims))]
      (handler (assoc request :user user :claims claims)))))

(defn require-auth
  "Middleware that returns 401 if user is not authenticated"
  [handler]
  (fn [request]
    (if (:user request)
      (handler request)
      {:status 401
       :body {:error "Authentication required"}})))

(defn require-super-admin
  "Middleware that returns 403 if user is not super-admin"
  [handler]
  (fn [request]
    (if (:is-super-admin (:claims request))
      (handler request)
      {:status 403
       :body {:error "Super-admin access required"}})))
