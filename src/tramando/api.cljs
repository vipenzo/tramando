(ns tramando.api
  "Client API per il server Tramando"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-server-url "http://localhost:3000")

(defonce config (atom {:server-url default-server-url
                       :token nil}))

(defn set-server-url! [url]
  (swap! config assoc :server-url (str/replace url #"/$" "")))

(defn set-token! [token]
  (swap! config assoc :token token))

(defn clear-token! []
  (swap! config assoc :token nil))

(defn get-token []
  (:token @config))

;; =============================================================================
;; HTTP Helpers
;; =============================================================================

(defn- api-url [path]
  (str (:server-url @config) path))

(defn- auth-headers []
  (if-let [token (:token @config)]
    {"Authorization" (str "Bearer " token)
     "Content-Type" "application/json"}
    {"Content-Type" "application/json"}))

(defn- fetch-json
  "Make a fetch request and parse JSON response"
  [url opts]
  (-> (js/fetch url (clj->js opts))
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [data]
                            (let [result (js->clj data :keywordize-keys true)]
                              (if (.-ok response)
                                {:ok true :data result}
                                {:ok false :error (:error result) :status (.-status response)})))))))))

(defn- api-get [path]
  (fetch-json (api-url path) {:method "GET" :headers (auth-headers)}))

(defn- api-post [path body]
  (fetch-json (api-url path) {:method "POST"
                               :headers (auth-headers)
                               :body (js/JSON.stringify (clj->js body))}))

(defn- api-put [path body]
  (fetch-json (api-url path) {:method "PUT"
                               :headers (auth-headers)
                               :body (js/JSON.stringify (clj->js body))}))

(defn- api-delete [path]
  (fetch-json (api-url path) {:method "DELETE" :headers (auth-headers)}))

;; =============================================================================
;; Auth API
;; =============================================================================

(defn register!
  "Register a new user. Returns promise with {:ok true :data {:user :token}} or {:ok false :error}"
  [username password]
  (-> (api-post "/api/register" {:username username :password password})
      (.then (fn [result]
               (when (:ok result)
                 (set-token! (get-in result [:data :token])))
               result))))

(defn login!
  "Login user. Returns promise with {:ok true :data {:user :token}} or {:ok false :error}"
  [username password]
  (-> (api-post "/api/login" {:username username :password password})
      (.then (fn [result]
               (when (:ok result)
                 (set-token! (get-in result [:data :token])))
               result))))

(defn logout! []
  (clear-token!))

(defn get-current-user
  "Get current authenticated user. Returns promise."
  []
  (api-get "/api/me"))

;; =============================================================================
;; Projects API
;; =============================================================================

(defn list-projects
  "List all projects accessible to the current user"
  []
  (api-get "/api/projects"))

(defn create-project!
  "Create a new project"
  [name content]
  (api-post "/api/projects" {:name name :content content}))

(defn get-project
  "Get a project by ID (includes content)"
  [project-id]
  (api-get (str "/api/projects/" project-id)))

(defn save-project!
  "Save project content"
  [project-id content]
  (api-put (str "/api/projects/" project-id) {:content content}))

(defn update-project!
  "Update project metadata and/or content"
  [project-id {:keys [name content]}]
  (api-put (str "/api/projects/" project-id)
           (cond-> {}
             name (assoc :name name)
             content (assoc :content content))))

(defn delete-project!
  "Delete a project"
  [project-id]
  (api-delete (str "/api/projects/" project-id)))

;; =============================================================================
;; Collaborators API
;; =============================================================================

(defn list-collaborators
  "List collaborators for a project"
  [project-id]
  (api-get (str "/api/projects/" project-id "/collaborators")))

(defn add-collaborator!
  "Add a collaborator to a project"
  [project-id username role]
  (api-post (str "/api/projects/" project-id "/collaborators")
            {:username username :role role}))

(defn remove-collaborator!
  "Remove a collaborator from a project"
  [project-id user-id]
  (api-delete (str "/api/projects/" project-id "/collaborators/" user-id)))

;; =============================================================================
;; Admin API
;; =============================================================================

(defn list-users
  "List all users (super-admin only)"
  []
  (api-get "/api/admin/users"))

;; =============================================================================
;; Token Persistence
;; =============================================================================

(def ^:private token-storage-key "tramando-auth-token")

(defn save-token-to-storage!
  "Save token to localStorage for persistence across sessions"
  []
  (when-let [token (:token @config)]
    (.setItem js/localStorage token-storage-key token)))

(defn load-token-from-storage!
  "Load token from localStorage"
  []
  (when-let [token (.getItem js/localStorage token-storage-key)]
    (set-token! token)))

(defn clear-token-from-storage!
  "Clear token from localStorage"
  []
  (.removeItem js/localStorage token-storage-key)
  (clear-token!))
