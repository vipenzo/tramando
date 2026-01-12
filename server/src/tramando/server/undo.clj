(ns tramando.server.undo
  "Server-side undo/redo stack management.

   Maintains per-project stacks of TRMD content snapshots in memory.
   When stack exceeds threshold, triggers auto-versioning (git commit)."
  (:require [tramando.server.storage :as storage]
            [tramando.server.versioning :as versioning]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private max-undo-stack-size
  "Maximum number of undo operations before triggering auto-versioning."
  50)

;; =============================================================================
;; State
;; =============================================================================

;; Per-project undo/redo stacks.
;; Structure: {project-id {:undo-stack [snapshot-n ... snapshot-1]
;;                         :redo-stack [snapshot-1 ... snapshot-m]}}
(defonce ^:private undo-state (atom {}))

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- get-project-state [project-id]
  (get @undo-state project-id {:undo-stack [] :redo-stack []}))

(defn- update-project-state! [project-id f]
  (swap! undo-state update project-id
         (fn [state]
           (f (or state {:undo-stack [] :redo-stack []})))))

;; =============================================================================
;; Public API
;; =============================================================================

;; Forward declaration for use in push-undo!
(declare clear-stacks!)

(defn push-undo!
  "Save current content as undo point before a modification.
   Clears redo stack (new branch of history).
   Automatically triggers auto-versioning if stack exceeds threshold."
  [project-id content]
  (let [should-auto-version? (atom false)]
    (update-project-state! project-id
      (fn [{:keys [undo-stack]}]
        (let [new-stack (conj (vec undo-stack) content)]
          (when (>= (count new-stack) max-undo-stack-size)
            (reset! should-auto-version? true))
          {:undo-stack new-stack
           :redo-stack []})))
    ;; Trigger auto-versioning if needed (async to not block the request)
    (when @should-auto-version?
      (future
        (try
          (let [result (versioning/create-auto-version! project-id)]
            (when (:ok result)
              (clear-stacks! project-id)
              (println "Auto-version created for project" project-id)))
          (catch Exception e
            (println "Auto-versioning failed for project" project-id ":" (.getMessage e))))))
    @should-auto-version?))

(defn pop-undo!
  "Pop the last undo point and push current content to redo stack.
   Returns the previous content, or nil if undo stack is empty."
  [project-id current-content]
  (let [result (atom nil)]
    (update-project-state! project-id
      (fn [{:keys [undo-stack redo-stack]}]
        (if (seq undo-stack)
          (let [previous (peek undo-stack)
                new-undo-stack (pop undo-stack)
                new-redo-stack (conj (vec redo-stack) current-content)]
            (reset! result previous)
            {:undo-stack new-undo-stack
             :redo-stack new-redo-stack})
          {:undo-stack undo-stack
           :redo-stack redo-stack})))
    @result))

(defn pop-redo!
  "Pop the last redo point and push current content to undo stack.
   Returns the redo content, or nil if redo stack is empty."
  [project-id current-content]
  (let [result (atom nil)]
    (update-project-state! project-id
      (fn [{:keys [undo-stack redo-stack]}]
        (if (seq redo-stack)
          (let [next-content (peek redo-stack)
                new-redo-stack (pop redo-stack)
                new-undo-stack (conj (vec undo-stack) current-content)]
            (reset! result next-content)
            {:undo-stack new-undo-stack
             :redo-stack new-redo-stack})
          {:undo-stack undo-stack
           :redo-stack redo-stack})))
    @result))

(defn can-undo?
  "Check if undo is available for a project."
  [project-id]
  (seq (:undo-stack (get-project-state project-id))))

(defn can-redo?
  "Check if redo is available for a project."
  [project-id]
  (seq (:redo-stack (get-project-state project-id))))

(defn get-undo-count
  "Get number of undo operations available."
  [project-id]
  (count (:undo-stack (get-project-state project-id))))

(defn get-redo-count
  "Get number of redo operations available."
  [project-id]
  (count (:redo-stack (get-project-state project-id))))

(defn clear-stacks!
  "Clear both undo and redo stacks for a project.
   Called after auto-versioning or manual version creation."
  [project-id]
  (swap! undo-state dissoc project-id))

(defn cleanup-project!
  "Remove all undo state for a deleted project."
  [project-id]
  (swap! undo-state dissoc project-id))

;; =============================================================================
;; Debug / Admin
;; =============================================================================

(defn get-all-stats
  "Get undo/redo stats for all projects (for admin/debug)."
  []
  (into {}
        (map (fn [[project-id state]]
               [project-id {:undo-count (count (:undo-stack state))
                            :redo-count (count (:redo-stack state))}])
             @undo-state)))

;; =============================================================================
;; Auto-versioning
;; =============================================================================

(defn trigger-auto-version!
  "Trigger auto-versioning for a project.
   Creates a git commit and clears the undo stack.
   Called when push-undo! returns true."
  [project-id]
  (let [result (versioning/create-auto-version! project-id)]
    (when (:ok result)
      (clear-stacks! project-id)
      (println "Auto-version created for project" project-id))
    result))
