(ns tramando.server.auth
  (:require [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [tramando.server.config :refer [config]]
            [tramando.server.db :as db])
  (:import [java.time Instant Duration]))

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

(defn generate-token [user]
  (let [now (Instant/now)
        exp (.plus now (Duration/ofHours (:jwt-expiration-hours config)))]
    (jwt/sign {:user-id (:id user)
               :username (:username user)
               :is-super-admin (= 1 (:is_super_admin user))
               :iat (.getEpochSecond now)
               :exp (.getEpochSecond exp)}
              (:jwt-secret config))))

(defn verify-token [token]
  (try
    (jwt/unsign token (:jwt-secret config))
    (catch Exception _
      nil)))

;; =============================================================================
;; Auth Operations
;; =============================================================================

(defn register!
  "Register a new user. First user becomes super-admin."
  [{:keys [username password]}]
  (cond
    (not (:allow-registration config))
    {:error "Registration is disabled"}

    (< (count password) 6)
    {:error "Password must be at least 6 characters"}

    (db/find-user-by-username username)
    {:error "Username already taken"}

    :else
    (let [is-first-user? (zero? (db/count-users))
          user (db/create-user! {:username username
                                 :password-hash (hash-password password)
                                 :is-super-admin is-first-user?})]
      {:user (dissoc user :password_hash)
       :token (generate-token user)})))

(defn login!
  "Authenticate user and return token"
  [{:keys [username password]}]
  (if-let [user (db/find-user-by-username username)]
    (if (check-password password (:password_hash user))
      {:user (dissoc user :password_hash)
       :token (generate-token user)}
      {:error "Invalid password"})
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
