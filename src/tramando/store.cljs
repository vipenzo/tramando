(ns tramando.store
  "Facade for state store operations.

   This namespace provides convenience functions that delegate to the
   current store instance. Components can use these functions without
   knowing whether LocalStore or RemoteStore is active.

   Usage:
     (require '[tramando.store :as store])
     (store/get-chunks)
     (store/update-chunk! id {:content \"new content\"})

   Note: For now, most components still use tramando.model directly.
   This facade enables gradual migration to the protocol-based approach."
  (:require [tramando.store.protocol :as protocol]))

;; =============================================================================
;; State Access (delegating to current store)
;; =============================================================================

(defn get-state
  "Get the current state as a map."
  []
  (when-let [store (protocol/get-store)]
    (protocol/get-state store)))

(defn get-chunks
  "Get all chunks as a vector."
  []
  (when-let [store (protocol/get-store)]
    (protocol/get-chunks store)))

(defn get-chunk
  "Get a single chunk by ID."
  [id]
  (when-let [store (protocol/get-store)]
    (protocol/get-chunk store id)))

(defn get-selected-id
  "Get the currently selected chunk ID."
  []
  (when-let [store (protocol/get-store)]
    (protocol/get-selected-id store)))

(defn get-selected-chunk
  "Get the currently selected chunk."
  []
  (when-let [store (protocol/get-store)]
    (protocol/get-selected-chunk store)))

(defn get-metadata
  "Get project metadata."
  []
  (when-let [store (protocol/get-store)]
    (protocol/get-metadata store)))

;; =============================================================================
;; State Mutation
;; =============================================================================

(defn select-chunk!
  "Select a chunk by ID."
  [id]
  (when-let [store (protocol/get-store)]
    (protocol/select-chunk! store id)))

(defn update-chunk!
  "Update a chunk with the given changes map."
  [id changes]
  (when-let [store (protocol/get-store)]
    (protocol/update-chunk! store id changes)))

(defn add-chunk!
  "Add a new chunk. opts can include :id :parent-id :summary :content :select?"
  [opts]
  (when-let [store (protocol/get-store)]
    (protocol/add-chunk! store opts)))

(defn delete-chunk!
  "Delete a chunk by ID."
  [id]
  (when-let [store (protocol/get-store)]
    (protocol/delete-chunk! store id)))

;; =============================================================================
;; History
;; =============================================================================

(defn can-undo?
  "Check if undo is available."
  []
  (when-let [store (protocol/get-store)]
    (protocol/can-undo? store)))

(defn can-redo?
  "Check if redo is available."
  []
  (when-let [store (protocol/get-store)]
    (protocol/can-redo? store)))

(defn undo!
  "Undo last change."
  []
  (when-let [store (protocol/get-store)]
    (protocol/undo! store)))

(defn redo!
  "Redo last undone change."
  []
  (when-let [store (protocol/get-store)]
    (protocol/redo! store)))

;; =============================================================================
;; Project Operations
;; =============================================================================

(defn load-project
  "Load a project by ID (or filepath for local).
   Returns a Promise."
  [project-id]
  (when-let [store (protocol/get-store)]
    (protocol/load-project store project-id)))

(defn save-project
  "Save the current project.
   Returns a Promise."
  []
  (when-let [store (protocol/get-store)]
    (protocol/save-project store)))

(defn save-project-as
  "Save the current project with a new name.
   Returns a Promise."
  [filename]
  (when-let [store (protocol/get-store)]
    (protocol/save-project-as store filename)))

;; =============================================================================
;; Ownership (collaborative features)
;; =============================================================================

(defn get-current-user
  "Get the current user identifier.
   Returns 'local' for local mode, username for remote."
  []
  (when-let [store (protocol/get-store)]
    (protocol/get-current-user store)))

(defn is-owner?
  "Check if current user owns the given chunk."
  [chunk-id]
  (when-let [store (protocol/get-store)]
    (protocol/is-owner? store chunk-id)))

(defn can-edit?
  "Check if current user can directly edit the chunk.
   In local mode, always true. In remote, only if owner."
  [chunk-id]
  (when-let [store (protocol/get-store)]
    (protocol/can-edit? store chunk-id)))

;; =============================================================================
;; Subscription
;; =============================================================================

(defn subscribe
  "Subscribe to changes at a specific path in the state.
   Returns an unsubscribe function."
  [path callback]
  (when-let [store (protocol/get-store)]
    (protocol/subscribe store path callback)))
