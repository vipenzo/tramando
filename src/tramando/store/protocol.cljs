(ns tramando.store.protocol
  "Protocol definition for state storage abstraction.

   This protocol allows switching between:
   - LocalStore: local file I/O (Tauri API or browser File API)
   - RemoteStore: server sync via API/WebSocket (future)

   The protocol defines the core operations for document state management.")

;; =============================================================================
;; IStateStore Protocol
;; =============================================================================

(defprotocol IStateStore
  "Protocol for document state storage and synchronization.

   Implementations:
   - LocalStore: wraps current atom-based state with local file I/O
   - RemoteStore (future): syncs with server, caches locally"

  ;; --- Project/Document Operations ---

  (load-project [this project-id]
    "Load a project by ID (or filepath for local).
     Returns a Promise that resolves to {:chunks [...] :metadata {...}}")

  (save-project [this]
    "Save the current project.
     Returns a Promise that resolves when save is complete.")

  (save-project-as [this filename]
    "Save the current project with a new name.
     Returns a Promise that resolves when save is complete.")

  ;; --- State Access ---

  (get-state [this]
    "Get the current state as a map.
     Returns {:chunks [...] :selected-id ... :metadata {...} ...}")

  (get-chunks [this]
    "Get all chunks as a vector.")

  (get-chunk [this id]
    "Get a single chunk by ID.")

  (get-selected-id [this]
    "Get the currently selected chunk ID.")

  (get-selected-chunk [this]
    "Get the currently selected chunk.")

  (get-metadata [this]
    "Get project metadata.")

  ;; --- State Mutation ---

  (select-chunk! [this id]
    "Select a chunk by ID.")

  (update-chunk! [this id changes]
    "Update a chunk with the given changes map.
     Automatically handles history push and mark-modified.")

  (add-chunk! [this opts]
    "Add a new chunk. opts can include :id :parent-id :summary :content :select?
     Returns the created chunk.")

  (delete-chunk! [this id]
    "Delete a chunk by ID.")

  ;; --- History ---

  (can-undo? [this]
    "Check if undo is available.")

  (can-redo? [this]
    "Check if redo is available.")

  (undo! [this]
    "Undo last change. Returns true if successful.")

  (redo! [this]
    "Redo last undone change. Returns true if successful.")

  ;; --- Subscription ---

  (subscribe [this path callback]
    "Subscribe to changes at a specific path in the state.
     callback is called with new value when it changes.
     Returns an unsubscribe function.")

  ;; --- Ownership (collaborative) ---

  (get-current-user [this]
    "Get the current user identifier.
     Returns 'local' for local mode, username for remote.")

  (is-owner? [this chunk-id]
    "Check if current user owns the given chunk.")

  (can-edit? [this chunk-id]
    "Check if current user can directly edit the chunk.
     In local mode, always true. In remote, only if owner."))

;; =============================================================================
;; Store Instance Management
;; =============================================================================

(defonce ^:private current-store (atom nil))

(defn set-store!
  "Set the current store instance."
  [store]
  (reset! current-store store))

(defn get-store
  "Get the current store instance."
  []
  @current-store)
