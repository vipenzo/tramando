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
            [tramando.events :as events]
            [tramando.chat :as chat]
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

;; Progress timer for visual feedback
(defonce ^:private progress-timer (atom nil))

(defn- cancel-progress-timer! []
  (when @progress-timer
    (js/clearInterval @progress-timer)
    (reset! progress-timer nil)))

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
    ;; Stop progress animation
    (cancel-progress-timer!)
    (reset! model/autosave-progress 0)
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

                     ;; Conflict (409) - attempt automatic merge
                     (= (:status result) 409)
                     (let [server-content (get-in result [:data :current-content])
                           server-hash (get-in result [:data :current-hash])
                           local-chunks (model/get-chunks)]
                       (if server-content
                         ;; We have server content - try merge
                         (let [merge-result (model/merge-with-server-content local-chunks server-content)
                               merged-chunks (:merged-chunks merge-result)
                               conflicts (:conflicts merge-result)]
                           ;; Apply merged state
                           (swap! model/app-state assoc :chunks merged-chunks)
                           (reset! content-hash server-hash)
                           ;; Now save the merged result
                           (let [merged-content (model/serialize-file merged-chunks (model/get-metadata))]
                             (-> (api/save-project! @project-id merged-content server-hash)
                                 (.then (fn [save-result]
                                          (if (:ok save-result)
                                            (do
                                              (reset! content-hash (get-in save-result [:data :content-hash]))
                                              (reset! sync-status :idle)
                                              (reset! model/save-status :saved)
                                              ;; Show conflict notification if there were real conflicts
                                              (when (seq conflicts)
                                                (let [conflict-msg (if (= 1 (count conflicts))
                                                                     (str "Modifiche perse su \""
                                                                          (:chunk-summary (first conflicts))
                                                                          "\" per conflitto con "
                                                                          (or (:server-owner (first conflicts)) "altro utente"))
                                                                     (str "Modifiche perse su " (count conflicts)
                                                                          " chunk per conflitto"))]
                                                  (reset! model/save-status :conflict)
                                                  (js/console.warn "Conflict resolved:" (pr-str conflicts))
                                                  ;; Show toast with conflict info
                                                  (events/show-toast! conflict-msg)
                                                  (js/setTimeout #(reset! model/save-status :idle) 5000)))
                                              (when (empty? conflicts)
                                                (js/setTimeout #(reset! model/save-status :idle) 2000)))
                                            ;; Merge save failed - another conflict, retry later
                                            (do
                                              (js/console.warn "Merge save failed, will retry")
                                              (reset! sync-status :pending)
                                              (reset! model/save-status :modified)
                                              ;; Retry sync after delay
                                              (js/setTimeout do-sync! sync-delay-ms))))))))
                         ;; No server content in response - fall back to reload
                         (do
                           (js/console.warn "No server content in 409 response, reloading")
                           (reload-from-server!)
                           (reset! sync-status :idle)
                           (reset! model/save-status :idle))))

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
  (cancel-progress-timer!)
  (reset! sync-status :pending)
  (reset! model/save-status :modified)
  ;; Start progress animation (4 steps toward sync)
  (reset! model/autosave-progress 1)
  (let [step-ms (/ sync-delay-ms 4)]
    (reset! progress-timer
            (js/setInterval
             (fn []
               (when (< @model/autosave-progress 4)
                 (swap! model/autosave-progress inc)))
             step-ms)))
  (reset! sync-timer (js/setTimeout do-sync! sync-delay-ms)))

(defn- on-state-modified!
  "Called when local state is modified"
  []
  (when @project-id
    (schedule-sync!)))

(defn- on-title-changed!
  "Called when metadata title changes - sync to server project name"
  [new-title]
  (when (and @project-id (seq new-title))
    (reset! project-name new-title)
    (-> (api/update-project! @project-id {:name new-title})
        (.then (fn [result]
                 (when (:ok result)
                   (js/console.log "Project name synced to server:" new-title))))
        (.catch (fn [err]
                  (js/console.error "Failed to sync project name:" err))))))

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
                     (js/console.log "RemoteStore.load-project: result =" (pr-str (select-keys (:data result) [:project :content-hash :role])))
                     (js/console.log "RemoteStore.load-project: content length =" (count (:content (:data result))))
                     (js/console.log "RemoteStore.load-project: content preview =" (subs (or (:content (:data result)) "") 0 (min 300 (count (or (:content (:data result)) "")))))
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
                         ;; Set up sync callbacks
                         (reset! model/on-modified-callback on-state-modified!)
                         (reset! model/on-title-changed-callback on-title-changed!)
                         (reset! sync-status :idle)
                         ;; Start polling for remote changes
                         (start-polling!)
                         ;; Initialize project chat
                         (chat/init-chat! pid)
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
      ;; Owner is current user (exact match only)
      ;; Chunks with nil or "local" owner belong to project owner, not collaborators
      (= user owner)))

  (can-edit? [_this chunk-id]
    ;; Strict ownership: only chunk owner can edit
    ;; Chunks with nil or "local" owner can be edited by project owner
    (let [chunk (model/get-chunk chunk-id)
          owner (:owner chunk)]
      (if (or (nil? owner) (= "local" owner))
        ;; Unowned chunks: only project owner can edit
        (model/is-project-owner?)
        ;; Owned chunks: only the chunk owner can edit
        (protocol/is-owner? _this chunk-id)))))

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
  (chat/cleanup-chat!)  ;; Stop chat polling and clear state
  (reset! model/on-modified-callback nil)
  (reset! model/on-title-changed-callback nil)
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
