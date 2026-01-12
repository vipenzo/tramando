(ns tramando.server.presence
  "Real-time presence tracking for collaborative editing.

   Tracks which users are currently editing which chunks in each project.
   This helps avoid conflicts by showing 'User X is typing in chunk Y'.

   State is kept in memory with automatic cleanup of stale entries.
   Presence info is included in polling responses.")

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private presence-timeout-ms
  "How long before a user's presence is considered stale (no heartbeat)."
  15000) ;; 15 seconds

;; =============================================================================
;; State
;; =============================================================================

;; Structure: {project-id {chunk-id {username {:last-seen timestamp}}}}
(defonce ^:private presence-state (atom {}))

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- now []
  (System/currentTimeMillis))

(defn- stale? [last-seen]
  (> (- (now) last-seen) presence-timeout-ms))

(defn- cleanup-stale-entries!
  "Remove stale presence entries from a project."
  [project-id]
  (swap! presence-state update project-id
         (fn [chunks]
           (into {}
                 (keep (fn [[chunk-id users]]
                         (let [active-users (into {}
                                                  (remove (fn [[_ data]]
                                                            (stale? (:last-seen data)))
                                                          users))]
                           (when (seq active-users)
                             [chunk-id active-users])))
                       chunks)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn notify-editing!
  "Mark a user as currently editing a chunk.
   Called when user starts typing or as a heartbeat while typing."
  [project-id chunk-id username]
  (swap! presence-state assoc-in [project-id chunk-id username]
         {:last-seen (now)})
  true)

(defn notify-stopped-editing!
  "Mark a user as no longer editing a chunk.
   Called on blur, chunk change, or explicit stop."
  [project-id chunk-id username]
  (swap! presence-state update-in [project-id chunk-id]
         dissoc username)
  ;; Clean up empty entries
  (swap! presence-state update project-id
         (fn [chunks]
           (let [updated (if (empty? (get chunks chunk-id))
                           (dissoc chunks chunk-id)
                           chunks)]
             (when (seq updated) updated))))
  true)

(defn get-editors
  "Get list of users currently editing a specific chunk.
   Returns set of usernames, excluding stale entries."
  [project-id chunk-id]
  (cleanup-stale-entries! project-id)
  (let [users (get-in @presence-state [project-id chunk-id])]
    (set (keys users))))

(defn get-project-presence
  "Get all editing activity for a project.
   Returns map of {chunk-id [username1 username2 ...]}.
   Used in polling response."
  [project-id]
  (cleanup-stale-entries! project-id)
  (let [chunks (get @presence-state project-id)]
    (into {}
          (map (fn [[chunk-id users]]
                 [chunk-id (vec (keys users))])
               chunks))))

(defn get-project-presence-excluding
  "Get editing activity excluding a specific user.
   Used to show 'others are editing' without showing yourself."
  [project-id exclude-username]
  (cleanup-stale-entries! project-id)
  (let [chunks (get @presence-state project-id)]
    (into {}
          (keep (fn [[chunk-id users]]
                  (let [other-users (vec (remove #(= % exclude-username) (keys users)))]
                    (when (seq other-users)
                      [chunk-id other-users])))
                chunks))))

(defn is-chunk-being-edited?
  "Check if a chunk is being edited by someone other than the given user.
   Useful for optional locking."
  [project-id chunk-id username]
  (let [editors (get-editors project-id chunk-id)]
    (seq (disj editors username))))

(defn cleanup-project!
  "Remove all presence state for a project (e.g., on delete)."
  [project-id]
  (swap! presence-state dissoc project-id))

(defn cleanup-user!
  "Remove a user from all projects (e.g., on logout/disconnect)."
  [username]
  (swap! presence-state
         (fn [state]
           (into {}
                 (keep (fn [[project-id chunks]]
                         (let [cleaned (into {}
                                             (keep (fn [[chunk-id users]]
                                                     (let [remaining (dissoc users username)]
                                                       (when (seq remaining)
                                                         [chunk-id remaining])))
                                                   chunks))]
                           (when (seq cleaned)
                             [project-id cleaned])))
                       state)))))

;; =============================================================================
;; Debug / Admin
;; =============================================================================

(defn get-all-presence
  "Get all presence state (for debugging)."
  []
  @presence-state)

(defn clear-all!
  "Clear all presence state (for testing)."
  []
  (reset! presence-state {}))
