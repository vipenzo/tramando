(ns tramando.server.routes
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [tramando.server.auth :as auth]
            [tramando.server.db :as db]
            [tramando.server.storage :as storage]
            [tramando.server.config :refer [config]]))

;; =============================================================================
;; Auth Handlers
;; =============================================================================

(defn register-handler [request]
  (let [{:keys [username password]} (:body-params request)
        result (auth/register! {:username username :password password})]
    (if (:error result)
      {:status 400 :body result}
      {:status 201 :body result})))

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
        project (db/create-project! {:name name :owner-id user-id})]
    ;; Save initial content if provided
    (when content
      (storage/save-project-content! (:id project) content))
    {:status 201
     :body {:project project}}))

(defn get-project-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [project (db/find-project-by-id project-id)
            content (storage/load-project-content project-id)]
        {:status 200
         :body {:project project
                :content (or content "")}}))))

(defn update-project-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [name content]} (:body-params request)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (do
        ;; Update metadata if name provided
        (when name
          (db/update-project! project-id {:name name}))
        ;; Save content if provided
        (when content
          (storage/save-project-content! project-id content))
        (let [project (db/find-project-by-id project-id)]
          {:status 200
           :body {:project project}})))))

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
         :body {:owner {:id (:id owner) :username (:username owner)}
                :collaborators collaborators}}))))

(defn add-collaborator-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [username role]} (:body-params request)]
    (cond
      (not (db/user-is-project-admin? user-id project-id))
      {:status 403 :body {:error "Admin access required"}}

      (not (#{"admin" "collaborator"} role))
      {:status 400 :body {:error "Invalid role"}}

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

;; =============================================================================
;; Admin Handlers
;; =============================================================================

(defn list-users-handler [_request]
  (let [users (db/query ["SELECT id, username, is_super_admin, created_at FROM users"])]
    {:status 200
     :body {:users users}}))

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
      ["/collaborators"
       ["" {:get {:handler list-collaborators-handler
                  :middleware [auth/require-auth]}
            :post {:handler add-collaborator-handler
                   :middleware [auth/require-auth]}}]
       ["/:user-id" {:delete {:handler remove-collaborator-handler
                              :middleware [auth/require-auth]}}]]]]

    ["/admin"
     ["/users" {:get {:handler list-users-handler
                      :middleware [auth/require-auth auth/require-super-admin]}}]]]])

(def router
  (ring/router
    app-routes
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}}))
