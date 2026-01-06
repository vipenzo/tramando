(ns tramando.store.local
  "LocalStore implementation of IStateStore protocol.

   This wraps the existing model.cljs atom-based state management,
   providing backward compatibility while enabling the new protocol-based
   abstraction for future RemoteStore implementation.

   In local mode:
   - Owner is always 'local'
   - User can always edit any chunk
   - File I/O uses Tauri API or browser File API via platform.cljs"
  (:require [tramando.store.protocol :as protocol]
            [tramando.model :as model]
            [reagent.core :as r]))

;; =============================================================================
;; LocalStore Record
;; =============================================================================

(defrecord LocalStore []
  protocol/IStateStore

  ;; --- Project/Document Operations ---

  (load-project [_this project-id]
    ;; In local mode, project-id is either a filepath or nil (for new document)
    (js/Promise.
     (fn [resolve _reject]
       (if project-id
         ;; Load from file
         (model/open-file!)
         ;; New document - just resolve with current state
         (resolve {:chunks (model/get-chunks)
                   :metadata (model/get-metadata)}))
       ;; Note: open-file! is async and updates state internally
       ;; For now, resolve immediately - the state will be updated
       (resolve {:chunks (model/get-chunks)
                 :metadata (model/get-metadata)}))))

  (save-project [_this]
    (js/Promise.
     (fn [resolve _reject]
       (model/save-file!)
       (resolve true))))

  (save-project-as [_this _filename]
    (js/Promise.
     (fn [resolve _reject]
       (model/save-file-as!)
       (resolve true))))

  ;; --- State Access ---

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
    ;; Use Reagent's track! for reactive subscriptions
    (let [ratom (r/track! #(get-in @model/app-state path))]
      ;; Call callback with initial value
      (callback @ratom)
      ;; Return unsubscribe function
      (fn []
        (r/dispose! ratom))))

  ;; --- Ownership (collaborative) ---

  (get-current-user [_this]
    "local")

  (is-owner? [_this chunk-id]
    ;; In local mode, user always owns everything
    (let [chunk (model/get-chunk chunk-id)]
      (or (nil? (:owner chunk))
          (= "local" (:owner chunk)))))

  (can-edit? [_this _chunk-id]
    ;; In local mode, user can always edit
    true))

;; =============================================================================
;; Store Constructor
;; =============================================================================

(defn create-local-store
  "Create a new LocalStore instance."
  []
  (->LocalStore))

;; =============================================================================
;; Initialization Helper
;; =============================================================================

(defn init!
  "Initialize the LocalStore as the current store."
  []
  (let [store (create-local-store)]
    (protocol/set-store! store)
    store))
