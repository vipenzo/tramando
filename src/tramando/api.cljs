(ns tramando.api
  "Client API per il server Tramando"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn- detect-base-path
  "Rileva il BASE_PATH dal pathname corrente.
   Es: se pathname è /tramando/ o /tramando/index.html, ritorna /tramando
   Se pathname è / o /index.html, ritorna stringa vuota."
  []
  (let [pathname (.-pathname js/location)
        ;; Rimuovi /index.html se presente
        path (str/replace pathname #"/index\.html$" "")
        ;; Rimuovi trailing slash
        path (str/replace path #"/$" "")]
    ;; Se il path è vuoto o solo /, non c'è base path
    (if (or (empty? path) (= path "/"))
      ""
      path)))

(defn- detect-server-url
  "Rileva automaticamente l'URL del server.
   - In Tauri: restituisce nil (non usato)
   - Se il frontend è servito da localhost:8080 (dev) usa localhost:3000.
   - Altrimenti usa l'origine corrente + base path (produzione Docker)."
  []
  (let [protocol (.-protocol js/location)
        hostname (.-hostname js/location)
        port (.-port js/location)
        base-path (detect-base-path)
        ;; Costruisci origin manualmente per maggiore affidabilità
        origin (str protocol "//" hostname (when (and port (not= port ""))
                                             (str ":" port)))
        server-url (str origin base-path)]
    (js/console.log "detect-server-url:"
                    "protocol=" protocol
                    "hostname=" hostname
                    "port=" port
                    "base-path=" base-path
                    "server-url=" server-url)
    (cond
      ;; Tauri mode - API not used, return nil
      (or (= protocol "tauri:")
          (.-__TAURI__ js/window))
      nil
      ;; Development mode (shadow-cljs dev server)
      (= port "8080") "http://localhost:3000"
      ;; Production: origin + base path
      :else server-url)))

(def ^:private default-server-url (detect-server-url))

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
  "Register a new user. Returns promise with {:ok true :data {:user :token}} or {:ok false :error}.
   The honeypot parameter is optional - if filled by a bot, server will fake success."
  [username password & [honeypot]]
  (-> (api-post "/api/register" (cond-> {:username username :password password}
                                  honeypot (assoc :website honeypot)))
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
  "Save project content with optimistic concurrency control.
   base-hash is the hash received when loading the project.
   Returns {:ok true :data {:content-hash new-hash}} on success,
   {:ok false :status 409 :data {:current-hash hash}} on conflict."
  ([project-id content]
   (save-project! project-id content nil))
  ([project-id content base-hash]
   (api-put (str "/api/projects/" project-id)
            (cond-> {:content content}
              base-hash (assoc :base-hash base-hash)))))

(defn update-project!
  "Update project metadata and/or content.
   Include :base-hash when updating content for conflict detection."
  [project-id {:keys [name content base-hash]}]
  (api-put (str "/api/projects/" project-id)
           (cond-> {}
             name (assoc :name name)
             content (assoc :content content)
             base-hash (assoc :base-hash base-hash))))

(defn delete-project!
  "Delete a project"
  [project-id]
  (api-delete (str "/api/projects/" project-id)))

(defn get-project-hash
  "Get only the content-hash of a project (for polling)"
  [project-id]
  (api-get (str "/api/projects/" project-id "/hash")))

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
  "List all users (super-admin only).
   Response includes {:users [...] :pending_count n}"
  []
  (api-get "/api/admin/users"))

(defn get-pending-count
  "Get count of pending users (super-admin only, lightweight for polling)"
  []
  (api-get "/api/admin/pending-count"))

(defn create-user!
  "Create a new user (super-admin only)"
  [username password is-super-admin]
  (api-post "/api/admin/users" {:username username
                                 :password password
                                 :is-super-admin is-super-admin}))

(defn delete-user!
  "Delete a user (super-admin only)"
  [user-id]
  (api-delete (str "/api/admin/users/" user-id)))

(defn update-user-admin!
  "Update user fields (super-admin only).
   Can include: is-super-admin, display_name, email, status,
   max_projects, max_project_size_mb, max_collaborators, notes"
  [user-id params]
  (api-put (str "/api/admin/users/" user-id) params))

;; =============================================================================
;; Profile API
;; =============================================================================

(defn get-profile
  "Get current user's profile and quotas"
  []
  (api-get "/api/profile"))

(defn update-profile!
  "Update current user's profile (display_name, email)"
  [params]
  (api-put "/api/profile" params))

;; =============================================================================
;; Chat API
;; =============================================================================

(defn get-chat-messages
  "Get chat messages for a project.
   Optionally pass after-id to get only new messages (for polling)."
  ([project-id]
   (get-chat-messages project-id nil))
  ([project-id after-id]
   (let [url (if after-id
               (str "/api/projects/" project-id "/chat?after=" after-id)
               (str "/api/projects/" project-id "/chat"))]
     (api-get url))))

(defn send-chat-message!
  "Send a chat message to a project"
  [project-id message]
  (api-post (str "/api/projects/" project-id "/chat") {:message message}))

;; =============================================================================
;; Token and Server URL Persistence
;; =============================================================================

(def ^:private token-storage-key "tramando-auth-token")
(def ^:private server-url-storage-key "tramando-server-url")

(defn save-token-to-storage!
  "Save token to localStorage for persistence across sessions"
  []
  (when-let [token (:token @config)]
    (.setItem js/localStorage token-storage-key token)))

(defn save-server-url-to-storage!
  "Save server URL to localStorage for persistence"
  []
  (when-let [url (:server-url @config)]
    (.setItem js/localStorage server-url-storage-key url)))

(defn load-token-from-storage!
  "Load token from localStorage"
  []
  (when-let [token (.getItem js/localStorage token-storage-key)]
    (set-token! token)))

(defn load-server-url-from-storage!
  "Load server URL from localStorage"
  []
  (when-let [url (.getItem js/localStorage server-url-storage-key)]
    (set-server-url! url)))

(defn clear-token-from-storage!
  "Clear token from localStorage"
  []
  (.removeItem js/localStorage token-storage-key)
  (clear-token!))
