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
            [tramando.settings :as settings]
            [clojure.string :as str]
            [reagent.core :as r]))

;; =============================================================================
;; Remote Store State
;; =============================================================================

;; Server project info
(defonce ^:private project-id (atom nil))
(defonce ^:private project-name (atom nil))
(defonce ^:private content-hash (atom nil))
;; Note: user-role is stored in model/user-role, not here

;; Undo state
;; last-synced-hash: hash of content when CodeMirror was last synced with server
;; Used to detect when to transition from CodeMirror undo to server undo
(defonce ^:private last-synced-hash (atom nil))
;; Server undo availability (cached from last server response)
(defonce ^:private server-can-undo (atom false))
(defonce ^:private server-can-redo (atom false))
;; Flag: true when local changes have been synced to server
;; When true, undo should skip CodeMirror and go directly to server
(defonce ^:private local-changes-synced (atom false))

;; Sync state
(defonce ^:private sync-timer (atom nil))
(defonce ^:private sync-status (atom :idle)) ;; :idle :pending :syncing :error :conflict

(defn- get-sync-delay-ms
  "Get sync delay from user settings"
  []
  (settings/get-autosave-delay))

;; Polling state
(defonce ^:private poll-timer (atom nil))
(def ^:private poll-interval-ms 15000) ;; 15 seconds

;; Presence state
;; Tracks which chunks others are editing: {chunk-id [username1 username2 ...]}
(defonce ^:private others-editing (atom {}))
;; Tracks which chunk we're currently editing (for heartbeat and stop notification)
(defonce ^:private current-editing-chunk (atom nil))
;; Heartbeat timer for presence
(defonce ^:private presence-timer (atom nil))
(def ^:private presence-heartbeat-ms 10000) ;; 10 seconds

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
                   (let [new-hash (get-in result [:data :content-hash])
                         editing-map (get-in result [:data :editing])]
                     (reset! content-hash new-hash)
                     ;; Update presence info
                     (reset! others-editing (or editing-map {}))
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

;; =============================================================================
;; Presence Helpers
;; =============================================================================

(defn- cancel-presence-timer! []
  (when @presence-timer
    (js/clearInterval @presence-timer)
    (reset! presence-timer nil)))

(defn- send-presence-heartbeat!
  "Send heartbeat to server for current editing chunk"
  []
  (when (and @project-id @current-editing-chunk)
    (api/notify-editing! @project-id @current-editing-chunk)))

(defn- start-presence-heartbeat! []
  (cancel-presence-timer!)
  ;; Send immediately, then set up interval
  (send-presence-heartbeat!)
  (reset! presence-timer (js/setInterval send-presence-heartbeat! presence-heartbeat-ms)))

(defn- stop-presence-heartbeat! []
  (cancel-presence-timer!))

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
                       (let [new-hash (get-in result [:data :content-hash])]
                         (reset! content-hash new-hash)
                         (reset! last-synced-hash new-hash))
                       ;; Update server undo/redo availability from response
                       (reset! server-can-undo (get-in result [:data :can-undo] false))
                       (reset! server-can-redo (get-in result [:data :can-redo] false))
                       ;; Mark that local changes are now synced - next undo should go to server
                       (reset! local-changes-synced true)
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
                                              (js/setTimeout do-sync! (get-sync-delay-ms)))))))))
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
  (let [step-ms (/ (get-sync-delay-ms) 4)]
    (reset! progress-timer
            (js/setInterval
             (fn []
               (when (< @model/autosave-progress 4)
                 (swap! model/autosave-progress inc)))
             step-ms)))
  (reset! sync-timer (js/setTimeout do-sync! (get-sync-delay-ms))))

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
                         ;; Initialize last-synced-hash to content-hash on load
                         (reset! last-synced-hash (:content-hash data))
                         ;; Initialize server undo state
                         (reset! server-can-undo (get-in data [:can-undo] false))
                         (reset! server-can-redo (get-in data [:can-redo] false))
                         ;; Initialize presence info
                         (reset! others-editing (or (:editing data) {}))
                         (reset! current-editing-chunk nil)
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
                         ;; Load collaborators to populate display names cache
                         (-> (api/list-collaborators pid)
                             (.then (fn [collab-result]
                                      (when (:ok collab-result)
                                        (auth/cache-users-from-collaborators! (:data collab-result)))))
                             (.catch (fn [_] nil))) ;; Ignore errors, not critical
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

  ;; --- State Mutation ---

  (select-chunk! [_this id]
    (model/select-chunk! id))

  (update-chunk! [_this id changes]
    ;; Content updates still use local model + sync
    (model/update-chunk! id changes))

  (add-chunk! [_this opts]
    ;; Use atomic REST API for structural changes
    (let [{:keys [parent-id summary content select?]} opts]
      (-> (api/add-chunk! @project-id {:parent-id parent-id
                                        :summary (or summary "Nuovo chunk")
                                        :content (or content "")})
          (.then (fn [result]
                   (if (:ok result)
                     (let [new-chunk (get-in result [:data :chunk])
                           new-hash (get-in result [:data :content-hash])]
                       ;; Update local state
                       (reset! content-hash new-hash)
                       ;; Add chunk to local model (without triggering sync)
                       (swap! model/app-state update :chunks conj new-chunk)
                       ;; Select if requested
                       (when select?
                         (model/select-chunk! (:id new-chunk)))
                       new-chunk)
                     (do
                       (js/console.error "add-chunk! failed:" (:error result))
                       nil))))
          (.catch (fn [err]
                    (js/console.error "add-chunk! error:" err)
                    nil)))))

  (delete-chunk! [_this id]
    ;; Use atomic REST API for structural changes
    (-> (api/delete-chunk! @project-id id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-hash (get-in result [:data :content-hash])]
                     ;; Update local state
                     (reset! content-hash new-hash)
                     ;; Remove chunk from local model (without triggering sync)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (->> chunks
                                   (remove #(= (:id %) id))
                                   (remove #(= (:parent-id %) id))
                                   vec)))
                     ;; Clear selection if deleted chunk was selected
                     (when (= (model/get-selected-id) id)
                       (model/select-chunk! nil))
                     true)
                   (do
                     (js/console.error "delete-chunk! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "delete-chunk! error:" err)
                  false))))

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
    ;; Project owner can edit ALL chunks (handles imported files)
    ;; Chunks with nil or "local" owner can be edited by project owner
    ;; Owned chunks can be edited by chunk owner OR project owner
    (let [chunk (model/get-chunk chunk-id)
          owner (:owner chunk)]
      (cond
        ;; Project owner can edit everything
        (model/is-project-owner?) true
        ;; Unowned chunks: only project owner can edit (already handled above)
        (or (nil? owner) (= "local" owner)) false
        ;; Owned chunks: only the chunk owner can edit
        :else (protocol/is-owner? _this chunk-id)))))

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
  ;; Notify server we stopped editing before cleanup
  (when (and @project-id @current-editing-chunk)
    (api/notify-stopped-editing! @project-id @current-editing-chunk))
  (stop-presence-heartbeat!)
  (chat/cleanup-chat!)  ;; Stop chat polling and clear state
  (reset! model/on-modified-callback nil)
  (reset! model/on-title-changed-callback nil)
  (model/set-current-user! nil)  ;; Clear collaborative user
  (reset! model/user-role nil)   ;; Clear user role
  (reset! project-id nil)
  (reset! project-name nil)
  (reset! content-hash nil)
  (reset! last-synced-hash nil)
  (reset! server-can-undo false)
  (reset! server-can-redo false)
  (reset! sync-status :idle)
  (reset! others-editing {})
  (reset! current-editing-chunk nil))

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

;; =============================================================================
;; Presence Operations
;; =============================================================================

(defn get-others-editing
  "Get map of chunks being edited by others: {chunk-id [username1 ...]}"
  []
  @others-editing)

(defn is-chunk-being-edited?
  "Check if a specific chunk is being edited by someone else."
  [chunk-id]
  (seq (get @others-editing chunk-id)))

(defn get-chunk-editors
  "Get list of users editing a specific chunk."
  [chunk-id]
  (get @others-editing chunk-id []))

(defn notify-editing!
  "Notify server that we started editing a chunk.
   Call this on first keypress in a chunk."
  [chunk-id]
  (when (and @project-id chunk-id)
    ;; If we were editing a different chunk, notify stop first
    (when (and @current-editing-chunk
               (not= @current-editing-chunk chunk-id))
      (api/notify-stopped-editing! @project-id @current-editing-chunk))
    ;; Update current editing chunk and start heartbeat
    (reset! current-editing-chunk chunk-id)
    (start-presence-heartbeat!)))

(defn notify-stopped-editing!
  "Notify server that we stopped editing.
   Call this on blur or chunk change."
  []
  (when (and @project-id @current-editing-chunk)
    (api/notify-stopped-editing! @project-id @current-editing-chunk)
    (stop-presence-heartbeat!)
    (reset! current-editing-chunk nil)))

(defn update-presence-from-server!
  "Update local presence state from server response."
  [editing-map]
  (reset! others-editing (or editing-map {})))

;; =============================================================================
;; Aspect Operations (via atomic REST API)
;; =============================================================================

(defn add-aspect!
  "Create a new aspect under an aspect container.
   Returns a Promise resolving to the created aspect or nil on error."
  [container-id summary]
  (when @project-id
    (-> (api/add-aspect! @project-id {:container-id container-id
                                       :summary (or summary "Nuovo aspetto")})
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-aspect (get-in result [:data :aspect])
                         new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks conj new-aspect)
                     new-aspect)
                   (do
                     (js/console.error "add-aspect! failed:" (:error result))
                     nil))))
        (.catch (fn [err]
                  (js/console.error "add-aspect! error:" err)
                  nil)))))

(defn delete-aspect!
  "Delete an aspect. Returns a Promise resolving to true/false."
  [aspect-id]
  (when @project-id
    (-> (api/delete-aspect! @project-id aspect-id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (->> chunks
                                   (remove #(= (:id %) aspect-id))
                                   vec)))
                     true)
                   (do
                     (js/console.error "delete-aspect! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "delete-aspect! error:" err)
                  false)))))

(defn add-aspect-to-chunk!
  "Add an aspect reference to a chunk. Returns a Promise resolving to true/false."
  [chunk-id aspect-id]
  (when @project-id
    (-> (api/add-aspect-to-chunk! @project-id chunk-id aspect-id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        (update c :aspects (fnil conj #{}) aspect-id)
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "add-aspect-to-chunk! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "add-aspect-to-chunk! error:" err)
                  false)))))

(defn remove-aspect-from-chunk!
  "Remove an aspect reference from a chunk. Returns a Promise resolving to true/false."
  [chunk-id aspect-id]
  (when @project-id
    (-> (api/remove-aspect-from-chunk! @project-id chunk-id aspect-id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        (update c :aspects disj aspect-id)
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "remove-aspect-from-chunk! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "remove-aspect-from-chunk! error:" err)
                  false)))))

;; =============================================================================
;; Annotation Operations (via atomic REST API)
;; =============================================================================

(defn add-annotation!
  "Add an annotation to a chunk's content. Returns a Promise.
   type: 'TODO', 'NOTE', or 'FIX'
   selected-text: the text to annotate
   position: character position in content
   priority: optional priority value
   comment: optional comment text"
  [chunk-id {:keys [type selected-text position priority comment]}]
  (when @project-id
    (-> (api/add-annotation! @project-id chunk-id
                              {:type type
                               :selected-text selected-text
                               :position position
                               :priority priority
                               :comment comment})
        (.then (fn [result]
                 (if (:ok result)
                   (let [updated-chunk (get-in result [:data :chunk])
                         new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        updated-chunk
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "add-annotation! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "add-annotation! error:" err)
                  false)))))

(defn delete-annotation!
  "Delete an annotation from a chunk's content. Returns a Promise.
   annotation-id format: TYPE-position (e.g., 'TODO-42')"
  [chunk-id annotation-id]
  (when @project-id
    (-> (api/delete-annotation! @project-id chunk-id annotation-id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [updated-chunk (get-in result [:data :chunk])
                         new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        updated-chunk
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "delete-annotation! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "delete-annotation! error:" err)
                  false)))))

;; =============================================================================
;; Proposal Operations (via atomic REST API)
;; =============================================================================

(defn create-proposal!
  "Create a proposal annotation in a chunk's content. Returns a Promise.
   original-text: the text to replace
   proposed-text: the suggested replacement
   position: character position in content"
  [chunk-id {:keys [original-text proposed-text position]}]
  (when @project-id
    (-> (api/create-proposal! @project-id chunk-id
                               {:original-text original-text
                                :proposed-text proposed-text
                                :position position})
        (.then (fn [result]
                 (if (:ok result)
                   (let [updated-chunk (get-in result [:data :chunk])
                         new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        updated-chunk
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "create-proposal! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "create-proposal! error:" err)
                  false)))))

(defn accept-proposal!
  "Accept a proposal: replace annotation with proposed text. Returns a Promise.
   position: character position of the proposal in content"
  [chunk-id position]
  (when @project-id
    (-> (api/accept-proposal! @project-id chunk-id position)
        (.then (fn [result]
                 (if (:ok result)
                   (let [updated-chunk (get-in result [:data :chunk])
                         new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        updated-chunk
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "accept-proposal! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "accept-proposal! error:" err)
                  false)))))

(defn reject-proposal!
  "Reject a proposal: replace annotation with original text. Returns a Promise.
   position: character position of the proposal in content"
  [chunk-id position]
  (when @project-id
    (-> (api/reject-proposal! @project-id chunk-id position)
        (.then (fn [result]
                 (if (:ok result)
                   (let [updated-chunk (get-in result [:data :chunk])
                         new-hash (get-in result [:data :content-hash])]
                     (reset! content-hash new-hash)
                     (swap! model/app-state update :chunks
                            (fn [chunks]
                              (mapv (fn [c]
                                      (if (= (:id c) chunk-id)
                                        updated-chunk
                                        c))
                                    chunks)))
                     true)
                   (do
                     (js/console.error "reject-proposal! failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "reject-proposal! error:" err)
                  false)))))

;; =============================================================================
;; Undo/Redo Operations (server-side)
;; =============================================================================

(defn get-last-synced-hash
  "Get the hash of content when last synced with server.
   Used by editor to detect when to transition from CodeMirror undo to server undo."
  []
  @last-synced-hash)

(defn get-content-hash
  "Get the current content hash."
  []
  @content-hash)

(defn server-can-undo?
  "Check if server has undo operations available."
  []
  @server-can-undo)

(defn server-can-redo?
  "Check if server has redo operations available."
  []
  @server-can-redo)

(defn local-changes-synced?
  "Check if local changes have been synced to server.
   When true, undo should bypass CodeMirror and go directly to server."
  []
  @local-changes-synced)

(defn clear-local-changes-synced!
  "Clear the synced flag (called when user makes new edits)."
  []
  (reset! local-changes-synced false))

(defn- format-changes-message
  "Format changes info into a user-friendly message"
  [changes action]
  (let [modified (:modified changes)
        added (:added changes)
        removed (:removed changes)
        all-changed (concat modified added removed)]
    (when (seq all-changed)
      (let [summaries (->> all-changed
                           (map :summary)
                           (filter seq)
                           (take 3))
            count-total (count all-changed)
            msg (if (seq summaries)
                  (str action ": " (str/join ", " summaries)
                       (when (> count-total (count summaries))
                         (str " (+" (- count-total (count summaries)) ")")))
                  (str action ": " count-total " chunk"))]
        msg))))

(defn server-undo!
  "Perform server-side undo. Returns a Promise.
   On success, reloads the project content from the undo stack."
  []
  (when @project-id
    ;; Cancel any pending sync to avoid overwriting undo result
    (cancel-sync-timer!)
    (cancel-progress-timer!)
    (reset! sync-status :idle)
    (reset! model/autosave-progress 0)
    (-> (api/undo! @project-id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-content (get-in result [:data :content])
                         new-hash (get-in result [:data :content-hash])
                         changes (get-in result [:data :changes])]
                     ;; Update hashes
                     (reset! content-hash new-hash)
                     (reset! last-synced-hash new-hash)
                     ;; Update server undo/redo availability
                     (reset! server-can-undo (get-in result [:data :can-undo] false))
                     (reset! server-can-redo (get-in result [:data :can-redo] false))
                     ;; Reload content without triggering sync
                     (model/reload-from-remote! new-content @project-name)
                     ;; Show toast with changes info
                     (when-let [msg (format-changes-message changes "Undo")]
                       (events/show-toast! msg))
                     (js/console.log "Server undo successful")
                     true)
                   (do
                     (js/console.warn "Server undo failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "Server undo error:" err)
                  false)))))

(defn server-redo!
  "Perform server-side redo. Returns a Promise.
   On success, reloads the project content from the redo stack."
  []
  (when @project-id
    ;; Cancel any pending sync to avoid overwriting redo result
    (cancel-sync-timer!)
    (cancel-progress-timer!)
    (reset! sync-status :idle)
    (reset! model/autosave-progress 0)
    (-> (api/redo! @project-id)
        (.then (fn [result]
                 (if (:ok result)
                   (let [new-content (get-in result [:data :content])
                         new-hash (get-in result [:data :content-hash])
                         changes (get-in result [:data :changes])]
                     ;; Update hashes
                     (reset! content-hash new-hash)
                     (reset! last-synced-hash new-hash)
                     ;; Update server undo/redo availability
                     (reset! server-can-undo (get-in result [:data :can-undo] false))
                     (reset! server-can-redo (get-in result [:data :can-redo] false))
                     ;; Reload content without triggering sync
                     (model/reload-from-remote! new-content @project-name)
                     ;; Show toast with changes info
                     (when-let [msg (format-changes-message changes "Redo")]
                       (events/show-toast! msg))
                     (js/console.log "Server redo successful")
                     true)
                   (do
                     (js/console.warn "Server redo failed:" (:error result))
                     false))))
        (.catch (fn [err]
                  (js/console.error "Server redo error:" err)
                  false)))))
