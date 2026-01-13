(ns tramando.api
  "Client API per il server Tramando"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn detect-base-path
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

;; Callback for session invalidation (set by auth.cljs)
(defonce on-session-invalid (atom nil))

(defn set-session-invalid-callback!
  "Set callback to be called when server returns 401 (session invalidated)"
  [callback]
  (reset! on-session-invalid callback))

(defn- fetch-json
  "Make a fetch request and parse JSON response.
   Triggers session-invalid callback on 401 responses."
  [url opts]
  (-> (js/fetch url (clj->js opts))
      (.then (fn [response]
               (let [status (.-status response)]
                 ;; Check for 401 BEFORE parsing JSON (in case body is empty/invalid)
                 (when (and (= status 401) @on-session-invalid)
                   (@on-session-invalid))
                 ;; Try to parse JSON response
                 (-> (.json response)
                     (.then (fn [data]
                              (let [result (js->clj data :keywordize-keys true)]
                                (if (.-ok response)
                                  {:ok true :data result}
                                  {:ok false :error (:error result) :status status}))))
                     (.catch (fn [_]
                               ;; JSON parsing failed, return error with status
                               {:ok false :error "Invalid response" :status status}))))))))

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
  "Delete a project (soft delete - moves to trash)"
  [project-id]
  (api-delete (str "/api/projects/" project-id)))

(defn list-trash
  "List projects in trash (soft deleted)"
  []
  (api-get "/api/projects-trash"))

(defn restore-project!
  "Restore a project from trash"
  [project-id]
  (api-post (str "/api/projects/" project-id "/restore") {}))

(defn permanent-delete-project!
  "Permanently delete a project from trash (cannot be undone)"
  [project-id]
  (api-delete (str "/api/projects/" project-id "/permanent")))

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
;; Users API (for all authenticated users)
;; =============================================================================

(defn list-users-basic
  "List all active users with basic info (id, username, display_name).
   Available to all authenticated users for adding collaborators."
  []
  (api-get "/api/users"))

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

(defn reset-user-password!
  "Reset a user's password (super-admin only)"
  [user-id new-password]
  (api-put (str "/api/admin/users/" user-id "/password") {:new_password new-password}))

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

(defn change-password!
  "Change current user's password"
  [old-password new-password]
  (api-put "/api/profile/password" {:old_password old-password
                                     :new_password new-password}))

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
;; Chunk Operations API (Atomic REST)
;; =============================================================================

(defn add-chunk!
  "Add a new chunk to the project.
   Returns {:ok true :data {:chunk {...} :content-hash string}}
   or {:ok false :status 403 :error string}"
  [project-id {:keys [parent-id summary content]}]
  (api-post (str "/api/projects/" project-id "/chunks")
            {:parent-id parent-id
             :summary summary
             :content content}))

(defn delete-chunk!
  "Delete a chunk from the project.
   Returns {:ok true :data {:success true :content-hash string}}
   or {:ok false :status 403/404 :error string}"
  [project-id chunk-id]
  (api-delete (str "/api/projects/" project-id "/chunks/" chunk-id)))

;; =============================================================================
;; Aspect Operations API (Atomic REST)
;; =============================================================================

(defn add-aspect!
  "Create a new aspect under an aspect container.
   Returns {:ok true :data {:aspect {...} :content-hash string}}"
  [project-id {:keys [container-id summary]}]
  (api-post (str "/api/projects/" project-id "/aspects")
            {:container-id container-id
             :summary summary}))

(defn delete-aspect!
  "Delete an aspect.
   Returns {:ok true :data {:success true :content-hash string}}"
  [project-id aspect-id]
  (api-delete (str "/api/projects/" project-id "/aspects/" aspect-id)))

(defn add-aspect-to-chunk!
  "Add an aspect reference to a chunk.
   Returns {:ok true :data {:success true :content-hash string}}"
  [project-id chunk-id aspect-id]
  (api-post (str "/api/projects/" project-id "/chunks/" chunk-id "/aspects")
            {:aspect-id aspect-id}))

(defn remove-aspect-from-chunk!
  "Remove an aspect reference from a chunk.
   Returns {:ok true :data {:success true :content-hash string}}"
  [project-id chunk-id aspect-id]
  (api-delete (str "/api/projects/" project-id "/chunks/" chunk-id "/aspects/" aspect-id)))

;; =============================================================================
;; Annotation Operations API (Atomic REST)
;; =============================================================================

(defn add-annotation!
  "Add an annotation to a chunk's content.
   type: 'TODO', 'NOTE', or 'FIX'
   selected-text: the text to annotate
   position: character position in content (or nil to append)
   priority: optional priority value (number or string)
   comment: optional comment text
   Returns {:ok true :data {:success true :chunk {...} :content-hash string}}"
  [project-id chunk-id {:keys [type selected-text position priority comment]}]
  (api-post (str "/api/projects/" project-id "/chunks/" chunk-id "/annotations")
            {:type type
             :selected-text selected-text
             :position position
             :priority priority
             :comment comment}))

(defn delete-annotation!
  "Delete an annotation from a chunk's content.
   annotation-id format: TYPE-position (e.g., 'TODO-42')
   Returns {:ok true :data {:success true :chunk {...} :content-hash string}}"
  [project-id chunk-id annotation-id]
  (api-delete (str "/api/projects/" project-id "/chunks/" chunk-id "/annotations/" annotation-id)))

;; =============================================================================
;; Proposal Operations API (Atomic REST)
;; =============================================================================

(defn create-proposal!
  "Create a proposal annotation in a chunk's content.
   original-text: the text to replace
   proposed-text: the suggested replacement
   position: character position in content (or nil to find first occurrence)
   Returns {:ok true :data {:success true :chunk {...} :content-hash string}}"
  [project-id chunk-id {:keys [original-text proposed-text position]}]
  (api-post (str "/api/projects/" project-id "/chunks/" chunk-id "/proposals")
            {:original-text original-text
             :proposed-text proposed-text
             :position position}))

(defn accept-proposal!
  "Accept a proposal: replace annotation with proposed text.
   position: character position of the proposal in content
   Returns {:ok true :data {:success true :chunk {...} :content-hash string}}"
  [project-id chunk-id position]
  (api-post (str "/api/projects/" project-id "/chunks/" chunk-id "/proposals/accept")
            {:position position}))

(defn reject-proposal!
  "Reject a proposal: replace annotation with original text.
   position: character position of the proposal in content
   Returns {:ok true :data {:success true :chunk {...} :content-hash string}}"
  [project-id chunk-id position]
  (api-post (str "/api/projects/" project-id "/chunks/" chunk-id "/proposals/reject")
            {:position position}))

;; =============================================================================
;; Undo/Redo API
;; =============================================================================

(defn undo!
  "Undo the last operation on a project (owner only).
   Returns {:ok true :data {:content string :content-hash string :can-undo bool :can-redo bool}}
   or {:ok false :status 400 :error 'Nothing to undo'}"
  [project-id]
  (api-post (str "/api/projects/" project-id "/undo") {}))

(defn redo!
  "Redo the last undone operation on a project (owner only).
   Returns {:ok true :data {:content string :content-hash string :can-undo bool :can-redo bool}}
   or {:ok false :status 400 :error 'Nothing to redo'}"
  [project-id]
  (api-post (str "/api/projects/" project-id "/redo") {}))

(defn get-undo-status
  "Get undo/redo availability for a project.
   Returns {:ok true :data {:can-undo bool :can-redo bool :undo-count int :redo-count int}}"
  [project-id]
  (api-get (str "/api/projects/" project-id "/undo-status")))

;; =============================================================================
;; Presence API
;; =============================================================================

(defn notify-editing!
  "Notify server that user is editing a chunk (call on first keypress, then as heartbeat).
   Returns {:ok true :data {:success true}}"
  [project-id chunk-id]
  (api-post (str "/api/projects/" project-id "/presence/editing")
            {:chunk_id chunk-id}))

(defn notify-stopped-editing!
  "Notify server that user stopped editing a chunk (call on blur or chunk change).
   Returns {:ok true :data {:success true}}"
  [project-id chunk-id]
  (api-post (str "/api/projects/" project-id "/presence/stopped")
            {:chunk_id chunk-id}))

(defn get-presence
  "Get current editing presence for a project.
   Returns {:ok true :data {:editing {chunk-id [username1 username2 ...]}}}"
  [project-id]
  (api-get (str "/api/projects/" project-id "/presence")))

;; =============================================================================
;; Versioning API
;; =============================================================================

(defn list-versions
  "List all versions (commits and tags) for a project.
   Returns {:ok true :data {:versions [{:ref :short-ref :message :date :is-tag :tag} ...]}}"
  [project-id]
  (api-get (str "/api/projects/" project-id "/versions")))

(defn get-version-content
  "Get the content of a specific version.
   ref can be a commit hash or tag name.
   Returns {:ok true :data {:content string}}"
  [project-id ref]
  (api-get (str "/api/projects/" project-id "/versions/" ref)))

(defn create-version-tag!
  "Create a tagged version (git tag) with an optional message.
   Returns {:ok true :data {:success true}}"
  [project-id tag-name message]
  (api-post (str "/api/projects/" project-id "/versions")
            {:tag-name tag-name :message message}))

(defn fork-version!
  "Create a new project from a specific version.
   Returns {:ok true :data {:project {:id ... :name ...}}}"
  [project-id ref new-name]
  (api-post (str "/api/projects/" project-id "/versions/" ref "/fork")
            {:name new-name}))

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
