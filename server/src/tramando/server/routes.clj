(ns tramando.server.routes
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [clojure.string :as str]
            [tramando.server.auth :as auth]
            [tramando.server.db :as db]
            [tramando.server.storage :as storage]
            [tramando.server.config :refer [config]]))

;; =============================================================================
;; Auth Handlers
;; =============================================================================

(defn register-handler [request]
  (let [{:keys [username password website]} (:body-params request)]
    ;; Honeypot check: if 'website' field is filled, it's a bot
    ;; Respond with fake success to not alert the bot
    (if (and website (seq website))
      {:status 201 :body {:pending true
                          :message "Registrazione completata. Attendi l'approvazione dell'amministratore."}}
      ;; Rate limiting check
      (let [rate-check (auth/check-rate-limit request)]
        (if (:error rate-check)
          {:status 429 :body rate-check}
          ;; Record attempt and proceed with registration
          (do
            (auth/record-registration-attempt! request)
            (let [result (auth/register! {:username username :password password})]
              (if (:error result)
                {:status 400 :body result}
                {:status 201 :body result}))))))))

(defn login-handler [request]
  (let [{:keys [username password]} (:body-params request)
        result (auth/login! {:username username :password password})]
    (if (:error result)
      {:status 401 :body result}
      {:status 200 :body result})))

(defn me-handler [request]
  {:status 200
   :body {:user (:user request)}})

;; =============================================================================
;; Project Handlers
;; =============================================================================

(defn list-projects-handler [request]
  (let [user-id (get-in request [:user :id])
        projects (db/find-projects-for-user user-id)]
    {:status 200
     :body {:projects projects}}))

(defn create-project-handler [request]
  (let [user-id (get-in request [:user :id])
        {:keys [name content]} (:body-params request)
        quotas (db/get-user-quotas user-id)]
    ;; Check project quota
    (if (>= (:projects_used quotas) (:max_projects quotas))
      {:status 403
       :body {:error (str "Hai raggiunto il limite massimo di progetti (" (:max_projects quotas) ")")}}
      (let [project (db/create-project! {:name name :owner-id user-id})]
        ;; Save initial content if provided
        (when content
          (storage/save-project-content! (:id project) content))
        {:status 201
         :body {:project project}}))))

(defn get-project-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [project (db/find-project-by-id project-id)
            content (storage/load-project-content project-id)
            content-hash (storage/content-hash content)
            role (db/get-user-project-role user-id project-id)]
        {:status 200
         :body {:project project
                :content (or content "")
                :content-hash content-hash
                :role (when role (name role))}}))))

(defn get-project-hash-handler
  "Returns only the content-hash for polling - lightweight endpoint"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [content (storage/load-project-content project-id)
            content-hash (storage/content-hash content)]
        {:status 200
         :body {:content-hash content-hash}}))))

(defn update-project-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [name content base-hash]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      ;; No access at all
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      ;; Trying to change name without admin/owner privileges
      (and name (not (#{:owner :admin} role)))
      {:status 403 :body {:error "Only owner or admin can rename project"}}

      :else
      (do
        ;; Update metadata if name provided (already checked permission above)
        (when name
          (db/update-project! project-id {:name name}))
        ;; Save content if provided (all roles can edit content)
        (if content
          (let [save-result (storage/save-project-content-if-matches! project-id content base-hash)]
            (if (:ok save-result)
              (let [project (db/find-project-by-id project-id)]
                {:status 200
                 :body {:project project
                        :content-hash (:hash save-result)}})
              ;; Conflict - return 409 with current content for client-side merge
              {:status 409
               :body {:error "Conflict: content was modified by another user"
                      :current-hash (:current-hash save-result)
                      :current-content (storage/load-project-content project-id)}}))
          ;; No content change, just metadata update
          (let [project (db/find-project-by-id project-id)]
            {:status 200
             :body {:project project}}))))))

(defn delete-project-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        project (db/find-project-by-id project-id)]
    (cond
      (nil? project)
      {:status 404 :body {:error "Project not found"}}

      (not= (:owner_id project) user-id)
      {:status 403 :body {:error "Only owner can delete project"}}

      :else
      (do
        (storage/delete-project-file! project-id)
        (db/delete-project! project-id)
        {:status 200 :body {:success true}}))))

;; =============================================================================
;; Collaborator Handlers
;; =============================================================================

(defn list-collaborators-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [collaborators (db/get-project-collaborators project-id)
            project (db/find-project-by-id project-id)
            owner (db/find-user-by-id (:owner_id project))]
        {:status 200
         :body {:owner {:id (:id owner)
                        :username (:username owner)
                        :display_name (:display_name owner)}
                :collaborators collaborators}}))))

(defn add-collaborator-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [username role]} (:body-params request)
        project (db/find-project-by-id project-id)
        owner-quotas (db/get-user-quotas (:owner_id project))
        current-collaborators (db/get-project-collaborators project-id)]
    (cond
      (not (db/user-is-project-admin? user-id project-id))
      {:status 403 :body {:error "Admin access required"}}

      (not (#{"admin" "collaborator"} role))
      {:status 400 :body {:error "Invalid role"}}

      ;; Check collaborators quota (only for new collaborators)
      (and (not (some #(= username (:username %)) current-collaborators))
           (>= (count current-collaborators) (:max_collaborators owner-quotas)))
      {:status 403 :body {:error (str "Limite collaboratori raggiunto (" (:max_collaborators owner-quotas) ")")}}

      :else
      (if-let [target-user (db/find-user-by-username username)]
        (do
          (db/add-collaborator! project-id (:id target-user) role)
          {:status 200 :body {:success true}})
        {:status 404 :body {:error "User not found"}}))))

(defn remove-collaborator-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        target-user-id (-> request :path-params :user-id Integer/parseInt)]
    (if-not (db/user-is-project-admin? user-id project-id)
      {:status 403 :body {:error "Admin access required"}}
      (do
        (db/remove-collaborator! project-id target-user-id)
        {:status 200 :body {:success true}}))))

(defn update-collaborator-role-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        target-user-id (-> request :path-params :user-id Integer/parseInt)
        {:keys [role]} (:body-params request)]
    (cond
      (not (db/user-is-project-admin? user-id project-id))
      {:status 403 :body {:error "Admin access required"}}

      (not (#{"admin" "collaborator"} role))
      {:status 400 :body {:error "Invalid role"}}

      :else
      (do
        (db/add-collaborator! project-id target-user-id role)
        {:status 200 :body {:success true}}))))

;; =============================================================================
;; Chat Handlers
;; =============================================================================

(defn get-chat-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        ;; Query params may come as string keys depending on middleware
        query-params (:query-params request)
        after-str (or (get query-params :after) (get query-params "after"))
        after-id (when after-str (Integer/parseInt after-str))]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      {:status 200
       :body {:messages (db/get-chat-messages project-id after-id)}})))

(defn post-chat-handler [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [message]} (:body-params request)]
    (cond
      (not (db/user-can-access-project? user-id project-id))
      {:status 403 :body {:error "Access denied"}}

      (or (nil? message) (empty? (str/trim message)))
      {:status 400 :body {:error "Message cannot be empty"}}

      :else
      (let [msg (db/add-chat-message! project-id user-id username message)]
        {:status 201 :body {:message msg}}))))

;; =============================================================================
;; Admin Handlers
;; =============================================================================

(defn list-users-handler [_request]
  (let [users (db/list-all-users)
        pending-count (db/count-pending-users)]
    {:status 200
     :body {:users users
            :pending_count pending-count}}))

(defn create-user-handler [request]
  (let [{:keys [username password is-super-admin]} (:body-params request)]
    (cond
      (< (count username) 3)
      {:status 400 :body {:error "Username must be at least 3 characters"}}

      (< (count password) 6)
      {:status 400 :body {:error "Password must be at least 6 characters"}}

      (db/find-user-by-username username)
      {:status 400 :body {:error "Username already exists"}}

      :else
      (let [password-hash (auth/hash-password password)
            user (db/create-user! {:username username
                                   :password-hash password-hash
                                   :is-super-admin is-super-admin})]
        {:status 201
         :body {:user (dissoc user :password_hash)}}))))

(defn delete-user-handler [request]
  (let [current-user-id (get-in request [:user :id])
        target-user-id (-> request :path-params :id Integer/parseInt)]
    (cond
      ;; Cannot delete yourself
      (= current-user-id target-user-id)
      {:status 400 :body {:error "Cannot delete yourself"}}

      ;; User doesn't exist
      (nil? (db/find-user-by-id target-user-id))
      {:status 404 :body {:error "User not found"}}

      :else
      (do
        (db/delete-user! target-user-id)
        {:status 200 :body {:success true}}))))

(defn update-user-admin-handler [request]
  (let [current-user-id (get-in request [:user :id])
        target-user-id (-> request :path-params :id Integer/parseInt)
        params (:body-params request)
        {:keys [is-super-admin display_name email status max_projects
                max_project_size_mb max_collaborators notes]} params]
    (cond
      ;; User doesn't exist
      (nil? (db/find-user-by-id target-user-id))
      {:status 404 :body {:error "User not found"}}

      ;; Cannot change your own admin status
      (and (some? is-super-admin) (= current-user-id target-user-id))
      {:status 400 :body {:error "Cannot change your own admin status"}}

      :else
      (do
        ;; Update admin status if provided
        (when (some? is-super-admin)
          (db/update-user-super-admin! target-user-id is-super-admin))
        ;; Update other fields
        (db/update-user! target-user-id
          {:display_name display_name
           :email email
           :status status
           :max_projects max_projects
           :max_project_size_mb max_project_size_mb
           :max_collaborators max_collaborators
           :notes notes})
        {:status 200 :body {:success true :user (db/find-user-by-id target-user-id)}}))))

;; =============================================================================
;; Profile Handlers
;; =============================================================================

(defn get-profile-handler [request]
  (let [user-id (get-in request [:user :id])
        user (db/find-user-by-id user-id)
        quotas (db/get-user-quotas user-id)]
    {:status 200
     :body {:user (dissoc user :password_hash)
            :quotas quotas}}))

(defn update-profile-handler [request]
  (let [user-id (get-in request [:user :id])
        {:keys [display_name email]} (:body-params request)
        updated-user (db/update-own-profile! user-id {:display_name display_name :email email})]
    {:status 200
     :body {:user (dissoc updated-user :password_hash)}}))

;; =============================================================================
;; Router
;; =============================================================================

(def app-routes
  [["/api"
    ["/register" {:post {:handler register-handler}}]
    ["/login" {:post {:handler login-handler}}]
    ["/me" {:get {:handler me-handler
                  :middleware [auth/require-auth]}}]

    ["/projects"
     ["" {:get {:handler list-projects-handler
                :middleware [auth/require-auth]}
          :post {:handler create-project-handler
                 :middleware [auth/require-auth]}}]
     ["/:id"
      ["" {:get {:handler get-project-handler
                 :middleware [auth/require-auth]}
           :put {:handler update-project-handler
                 :middleware [auth/require-auth]}
           :delete {:handler delete-project-handler
                    :middleware [auth/require-auth]}}]
      ["/hash" {:get {:handler get-project-hash-handler
                      :middleware [auth/require-auth]}}]
      ["/collaborators"
       ["" {:get {:handler list-collaborators-handler
                  :middleware [auth/require-auth]}
            :post {:handler add-collaborator-handler
                   :middleware [auth/require-auth]}}]
       ["/:user-id" {:delete {:handler remove-collaborator-handler
                              :middleware [auth/require-auth]}
                     :put {:handler update-collaborator-role-handler
                           :middleware [auth/require-auth]}}]]
      ["/chat" {:get {:handler get-chat-handler
                      :middleware [auth/require-auth]}
                :post {:handler post-chat-handler
                       :middleware [auth/require-auth]}}]]]

    ["/profile" {:get {:handler get-profile-handler
                       :middleware [auth/require-auth]}
                  :put {:handler update-profile-handler
                        :middleware [auth/require-auth]}}]

    ["/admin/users" {:get {:handler list-users-handler
                           :middleware [auth/require-auth auth/require-super-admin]}
                     :post {:handler create-user-handler
                            :middleware [auth/require-auth auth/require-super-admin]}}]
    ["/admin/users/:id" {:delete {:handler delete-user-handler
                                  :middleware [auth/require-auth auth/require-super-admin]}
                         :put {:handler update-user-admin-handler
                               :middleware [auth/require-auth auth/require-super-admin]}}]
    ["/admin/pending-count" {:get {:handler (fn [_] {:status 200 :body {:count (db/count-pending-users)}})
                                   :middleware [auth/require-auth auth/require-super-admin]}}]]])

(def router
  (ring/router
    app-routes
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}}))
