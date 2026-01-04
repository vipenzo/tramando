(ns tramando.store.remote
  "RemoteStore implementation of IStateStore protocol.

   This provides server-backed state management with:
   - Local cache in ratoms for reactive UI
   - Automatic sync to server with debounce
   - Optimistic concurrency control via content-hash
   - Conflict detection and resolution

   The store maintains local state that mirrors the server,
   syncing changes automatically with a debounce timer."
  (:require [tramando.store.protocol :as protocol]
            [tramando.model :as model]
            [tramando.api :as api]
            [tramando.auth :as auth]
            [tramando.versioning :as versioning]
            [reagent.core :as r]))

;; =============================================================================
;; Remote Store State
;; =============================================================================

;; Server project info
(defonce ^:private project-id (atom nil))
(defonce ^:private project-name (atom nil))
(defonce ^:private content-hash (atom nil))
;; Note: user-role is stored in model/user-role, not here

;; Sync state
(defonce ^:private sync-timer (atom nil))
(defonce ^:private sync-status (atom :idle)) ;; :idle :pending :syncing :error :conflict
(def ^:private sync-delay-ms 3000)

;; Polling state
(defonce ^:private poll-timer (atom nil))
(def ^:private poll-interval-ms 15000) ;; 15 seconds

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- cancel-sync-timer! []
  (when @sync-timer
    (js/clearTimeout @sync-timer)
    (reset! sync-timer nil)))

(defn- cancel-poll-timer! []
  (when @poll-timer
    (js/clearInterval @poll-timer)
    (reset! poll-timer nil)))

(defn- reload-from-server!
  "Reload project content from server (called when remote changes detected)"
  []
  (when @project-id
    (-> (api/get-project @project-id)
        (.then (fn [result]
                 (when (:ok result)
                   (let [new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     ;; Use reload-from-remote! to preserve selection and avoid sync loop
                     (model/reload-from-remote!
                       (get-in result [:data :content])
                       @project-name)
                     (js/console.log "Remote changes loaded")))))
        (.catch (fn [err]
                  (js/console.error "Failed to reload from server:" err))))))

(defn- check-for-remote-changes!
  "Poll server to check if content has changed"
  []
  (when (and @project-id
             (not= @sync-status :syncing)
             (not= @sync-status :pending))
    (-> (api/get-project-hash @project-id)
        (.then (fn [result]
                 (when (:ok result)
                   (let [server-hash (get-in result [:data :content-hash])]
                     (when (and server-hash
                                @content-hash
                                (not= server-hash @content-hash))
                       ;; Content changed on server, reload
                       (js/console.log "Remote changes detected, reloading...")
                       (reload-from-server!))))))
        (.catch (fn [_err]
                  ;; Silently ignore polling errors (network issues, etc.)
                  nil)))))

(defn- start-polling! []
  (cancel-poll-timer!)
  (reset! poll-timer (js/setInterval check-for-remote-changes! poll-interval-ms)))

(defn- stop-polling! []
  (cancel-poll-timer!))

(defn- do-sync!
  "Perform the actual sync to server"
  []
  (when @project-id
    (reset! sync-status :syncing)
    (reset! model/save-status :saving)
    (let [content (model/serialize-file (model/get-chunks) (model/get-metadata))
          base-hash @content-hash]
      (-> (api/save-project! @project-id content base-hash)
          (.then (fn [result]
                   (cond
                     ;; Success
                     (:ok result)
                     (do
                       (reset! content-hash (get-in result [:data :content-hash]))
                       (reset! sync-status :idle)
                       (reset! model/save-status :saved)
                       (js/setTimeout #(reset! model/save-status :idle) 2000))

                     ;; Conflict (409)
                     (= (:status result) 409)
                     (do
                       (reset! sync-status :conflict)
                       (reset! model/save-status :conflict)
                       ;; Show conflict dialog
                       (versioning/show-conflict-dialog!
                         {:on-overwrite (fn []
                                          ;; Force save without hash check
                                          (-> (api/save-project! @project-id content nil)
                                              (.then (fn [r]
                                                       (when (:ok r)
                                                         (reset! content-hash (get-in r [:data :content-hash]))
                                                         (reset! sync-status :idle)
                                                         (reset! model/save-status :saved)
                                                         (js/setTimeout #(reset! model/save-status :idle) 2000))))))
                          :on-reload (fn []
                                       ;; Reload from server
                                       (when @project-id
                                         (-> (api/get-project @project-id)
                                             (.then (fn [r]
                                                      (when (:ok r)
                                                        (reset! content-hash (get-in r [:data :content-hash]))
                                                        (model/load-file-content!
                                                          (get-in r [:data :content])
                                                          @project-name
                                                          nil)
                                                        (reset! sync-status :idle)
                                                        (reset! model/save-status :idle)))))))}))

                     ;; Other error
                     :else
                     (do
                       (js/console.error "Server save failed:" (:error result))
                       (reset! sync-status :error)
                       (reset! model/save-status :modified)))))
          (.catch (fn [err]
                    (js/console.error "Server save error:" err)
                    (reset! sync-status :error)
                    (reset! model/save-status :modified)))))))

(defn- schedule-sync!
  "Schedule a sync after debounce delay"
  []
  (cancel-sync-timer!)
  (reset! sync-status :pending)
  (reset! model/save-status :modified)
  (reset! sync-timer (js/setTimeout do-sync! sync-delay-ms)))

(defn- on-state-modified!
  "Called when local state is modified"
  []
  (when @project-id
    (schedule-sync!)))

;; =============================================================================
;; RemoteStore Record
;; =============================================================================

(defrecord RemoteStore []
  protocol/IStateStore

  ;; --- Project/Document Operations ---

  (load-project [_this pid]
    (js/Promise.
      (fn [resolve reject]
        (-> (api/get-project pid)
            (.then (fn [result]
                     (if (:ok result)
                       (let [data (:data result)]
                         ;; Store project info
                         (reset! project-id pid)
                         (reset! project-name (get-in data [:project :name]))
                         (reset! content-hash (:content-hash data))
                         ;; Set current user for collaborative ownership
                         (model/set-current-user! (auth/get-username))
                         ;; Load content into model FIRST
                         (model/load-file-content! (:content data) @project-name nil)
                         ;; Set user role in model for permission checks AFTER loading
                         ;; The server returns role as a string (e.g., "collaborator")
                         (let [role-str (:role data)
                               role-kw (when role-str (keyword role-str))]
                           (reset! model/user-role role-kw))
                         ;; Set up sync callback
                         (reset! model/on-modified-callback on-state-modified!)
                         (reset! sync-status :idle)
                         ;; Start polling for remote changes
                         (start-polling!)
                         (resolve {:chunks (model/get-chunks)
                                   :metadata (model/get-metadata)
                                   :project (:project data)
                                   :role (:role data)}))
                       (reject (js/Error. (or (:error result) "Failed to load project"))))))
            (.catch reject)))))

  (save-project [_this]
    (js/Promise.
      (fn [resolve _reject]
        (cancel-sync-timer!)
        (do-sync!)
        (resolve true))))

  (save-project-as [_this _filename]
    ;; For remote, "save as" would mean creating a new project
    ;; For now, just do a regular save
    (protocol/save-project _this))

  ;; --- State Access (delegate to model) ---

  (get-state [_this]
    @model/app-state)

  (get-chunks [_this]
    (model/get-chunks))

  (get-chunk [_this id]
    (model/get-chunk id))

  (get-selected-id [_this]
    (model/get-selected-id))

  (get-selected-chunk [_this]
    (model/get-selected-chunk))

  (get-metadata [_this]
    (model/get-metadata))

  ;; --- State Mutation (delegate to model, triggers sync) ---

  (select-chunk! [_this id]
    (model/select-chunk! id))

  (update-chunk! [_this id changes]
    (model/update-chunk! id changes))

  (add-chunk! [_this opts]
    (apply model/add-chunk! (mapcat identity opts)))

  (delete-chunk! [_this id]
    (model/delete-chunk! id))

  ;; --- History ---

  (can-undo? [_this]
    (model/can-undo?))

  (can-redo? [_this]
    (model/can-redo?))

  (undo! [_this]
    (model/undo!))

  (redo! [_this]
    (model/redo!))

  ;; --- Subscription ---

  (subscribe [_this path callback]
    (let [ratom (r/track! #(get-in @model/app-state path))]
      (callback @ratom)
      (fn []
        (r/dispose! ratom))))

  ;; --- Ownership (collaborative) ---

  (get-current-user [_this]
    (or (auth/get-username) "anonymous"))

  (is-owner? [_this chunk-id]
    (let [chunk (model/get-chunk chunk-id)
          user (protocol/get-current-user _this)
          owner (:owner chunk)]
      ;; Owner is current user, or chunk has no real owner yet (nil or "local")
      (or (nil? owner)
          (= "local" owner)
          (= user owner))))

  (can-edit? [_this chunk-id]
    ;; Project owners can edit all chunks
    ;; Collaborators can only edit chunks they own
    (or (model/is-project-owner?)
        (protocol/is-owner? _this chunk-id))))

;; =============================================================================
;; Store Constructor and Management
;; =============================================================================

(defn create-remote-store
  "Create a new RemoteStore instance."
  []
  (->RemoteStore))

(defn init!
  "Initialize the RemoteStore as the current store."
  []
  (let [store (create-remote-store)]
    (protocol/set-store! store)
    store))

(defn cleanup!
  "Clean up remote store state when leaving remote mode."
  []
  (cancel-sync-timer!)
  (stop-polling!)
  (reset! model/on-modified-callback nil)
  (model/set-current-user! nil)  ;; Clear collaborative user
  (reset! model/user-role nil)   ;; Clear user role
  (reset! project-id nil)
  (reset! project-name nil)
  (reset! content-hash nil)
  (reset! sync-status :idle))

;; =============================================================================
;; Accessors for UI
;; =============================================================================

(defn get-project-id []
  @project-id)

(defn get-project-name []
  @project-name)

(defn get-sync-status []
  @sync-status)

(defn syncing? []
  (= @sync-status :syncing))

(defn has-conflict? []
  (= @sync-status :conflict))
