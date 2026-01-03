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

(defn super-admin? []
  (= 1 (:is_super_admin (:user @auth-state))))

(defn loading? []
  (:loading? @auth-state))

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
                 (do
                   (api/save-token-to-storage!)
                   (swap! auth-state assoc
                          :user (get-in result [:data :user])
                          :loading? false
                          :error nil))
                 (swap! auth-state assoc
                        :user nil
                        :loading? false
                        :error (:error result)))
               result))))

(defn register!
  "Register and update auth state. Returns promise."
  [username password]
  (swap! auth-state assoc :loading? true :error nil)
  (-> (api/register! username password)
      (.then (fn [result]
               (if (:ok result)
                 (do
                   (api/save-token-to-storage!)
                   (swap! auth-state assoc
                          :user (get-in result [:data :user])
                          :loading? false
                          :error nil))
                 (swap! auth-state assoc
                        :user nil
                        :loading? false
                        :error (:error result)))
               result))))

(defn logout! []
  (api/clear-token-from-storage!)
  (swap! auth-state assoc :user nil :error nil))

(defn check-auth!
  "Check if there's a valid token and load user. Call on app startup."
  []
  (api/load-token-from-storage!)
  (if (api/get-token)
    (-> (api/get-current-user)
        (.then (fn [result]
                 (if (:ok result)
                   (swap! auth-state assoc
                          :user (get-in result [:data :user])
                          :loading? false)
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
