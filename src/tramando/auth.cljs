(ns tramando.auth
  "Stato autenticazione e gestione sessione"
  (:require [reagent.core :as r]
            [tramando.api :as api]))

;; =============================================================================
;; Auth State
;; =============================================================================

(defonce auth-state
  (r/atom {:user nil           ;; {:id :username :is_super_admin}
           :loading? true      ;; true while checking auth on startup
           :error nil}))       ;; last error message

(defn logged-in? []
  (some? (:user @auth-state)))

(defn get-user []
  (:user @auth-state))

(defn get-username []
  (:username (:user @auth-state)))

(defn get-display-name
  "Get display name for a user map, falling back to username if not set.
   If called with no args, returns the current user's display name."
  ([]
   (get-display-name (:user @auth-state)))
  ([user]
   (or (not-empty (:display_name user))
       (:username user))))

(defn super-admin? []
  (= 1 (:is_super_admin (:user @auth-state))))

(defn loading? []
  (:loading? @auth-state))

;; =============================================================================
;; User Display Names Cache
;; =============================================================================

;; Global cache for username -> display_name mapping
;; This is populated when collaborators are loaded or user logs in
(defonce user-display-names (r/atom {}))

(defn cache-user-display-name!
  "Cache a user's display name for later lookup"
  [username display-name]
  (when (and username (seq username))
    (swap! user-display-names assoc username (or display-name username))))

(defn cache-users-from-collaborators!
  "Cache display names from collaborators data (owner + collaborators list)"
  [collabs-data]
  (when collabs-data
    (let [owner (:owner collabs-data)
          collabs (:collaborators collabs-data)
          all-users (if owner (cons owner collabs) collabs)]
      (doseq [user all-users]
        (when-let [username (:username user)]
          (cache-user-display-name! username (:display_name user)))))))

(defn get-cached-display-name
  "Get cached display name for a username. Returns username if not cached."
  [username]
  (if (or (nil? username) (= username "local"))
    username
    (get @user-display-names username username)))

;; =============================================================================
;; Auth Actions
;; =============================================================================

(defn login!
  "Login and update auth state. Returns promise."
  [username password]
  (swap! auth-state assoc :loading? true :error nil)
  (-> (api/login! username password)
      (.then (fn [result]
               (if (:ok result)
                 (let [user (get-in result [:data :user])]
                   (api/save-token-to-storage!)
                   (api/save-server-url-to-storage!)
                   ;; Cache current user's display name
                   (cache-user-display-name! (:username user) (:display_name user))
                   (swap! auth-state assoc
                          :user user
                          :loading? false
                          :error nil))
                 (swap! auth-state assoc
                        :user nil
                        :loading? false
                        :error (:error result)))
               result))))

(defn register!
  "Register and update auth state. Returns promise.
   If registration returns {:pending true}, user needs admin approval."
  [username password]
  (swap! auth-state assoc :loading? true :error nil)
  (-> (api/register! username password)
      (.then (fn [result]
               (cond
                 ;; Error during registration
                 (not (:ok result))
                 (do
                   (swap! auth-state assoc
                          :user nil
                          :loading? false
                          :error (:error result))
                   result)

                 ;; Pending approval (no token, just message)
                 (get-in result [:data :pending])
                 (do
                   (swap! auth-state assoc
                          :user nil
                          :loading? false
                          :error nil)
                   ;; Return special pending result for UI to handle
                   {:ok true :pending true :message (get-in result [:data :message])})

                 ;; Normal registration (first user gets immediate login)
                 :else
                 (let [user (get-in result [:data :user])]
                   (api/save-token-to-storage!)
                   (api/save-server-url-to-storage!)
                   ;; Cache current user's display name
                   (cache-user-display-name! (:username user) (:display_name user))
                   (swap! auth-state assoc
                          :user user
                          :loading? false
                          :error nil)
                   result))))))

(defn logout! []
  (api/clear-token-from-storage!)
  (swap! auth-state assoc :user nil :error nil))

(defn check-auth!
  "Check if there's a valid token and load user. Call on app startup."
  []
  (api/load-server-url-from-storage!)
  (api/load-token-from-storage!)
  (if (api/get-token)
    (-> (api/get-current-user)
        (.then (fn [result]
                 (if (:ok result)
                   (let [user (get-in result [:data :user])]
                     ;; Cache current user's display name
                     (cache-user-display-name! (:username user) (:display_name user))
                     (swap! auth-state assoc
                            :user user
                            :loading? false))
                   (do
                     ;; Token invalid, clear it
                     (api/clear-token-from-storage!)
                     (swap! auth-state assoc
                            :user nil
                            :loading? false)))))
        (.catch (fn [_]
                  (api/clear-token-from-storage!)
                  (swap! auth-state assoc :user nil :loading? false))))
    ;; No token stored
    (swap! auth-state assoc :loading? false)))
