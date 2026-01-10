(ns tramando.server.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [tramando.server.config :refer [config]]
            [clojure.java.io :as io]))

;; =============================================================================
;; Database Connection
;; =============================================================================

(defonce datasource (atom nil))

(declare create-tables!)

(defn get-datasource []
  (or @datasource
      (throw (ex-info "Database not initialized" {}))))

(defn init-db!
  "Initialize database connection and create tables if needed"
  []
  (let [db-path (:db-path config)
        db-dir (io/file (.getParent (io/file db-path)))]
    ;; Ensure data directory exists
    (when-not (.exists db-dir)
      (.mkdirs db-dir))
    ;; Create datasource
    (reset! datasource
            (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path}))
    ;; Create tables
    (create-tables!)))

(defn- migrate-users-table!
  "Add new columns to users table if they don't exist"
  []
  (let [ds (get-datasource)]
    ;; Check if display_name column exists by trying to select it
    (try
      (jdbc/execute! ds ["SELECT display_name FROM users LIMIT 1"])
      (catch Exception _
        ;; Column doesn't exist, add new columns
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN display_name TEXT"])
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN email TEXT"])
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN status TEXT DEFAULT 'active'"])
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN max_projects INTEGER DEFAULT 10"])
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN max_project_size_mb INTEGER DEFAULT 50"])
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN max_collaborators INTEGER DEFAULT 10"])
        (jdbc/execute! ds ["ALTER TABLE users ADD COLUMN notes TEXT"])))))

(defn- migrate-projects-table!
  "Add metadata_cache, disabled, and has_validation_errors columns to projects table if they don't exist.
   metadata_cache stores JSON with: title, author, year, custom fields, word_count, char_count
   disabled is used for soft delete (0 = active, 1 = in trash)
   has_validation_errors flags projects with structural issues (0 = ok, 1 = has errors)"
  []
  (let [ds (get-datasource)]
    ;; Add metadata_cache column
    (try
      (jdbc/execute! ds ["SELECT metadata_cache FROM projects LIMIT 1"])
      (catch Exception _
        (jdbc/execute! ds ["ALTER TABLE projects ADD COLUMN metadata_cache TEXT"])))
    ;; Add disabled column for soft delete
    (try
      (jdbc/execute! ds ["SELECT disabled FROM projects LIMIT 1"])
      (catch Exception _
        (jdbc/execute! ds ["ALTER TABLE projects ADD COLUMN disabled INTEGER DEFAULT 0"])))
    ;; Add has_validation_errors column for validation flag
    (try
      (jdbc/execute! ds ["SELECT has_validation_errors FROM projects LIMIT 1"])
      (catch Exception _
        (jdbc/execute! ds ["ALTER TABLE projects ADD COLUMN has_validation_errors INTEGER DEFAULT 0"])))))

(defn create-tables!
  "Create database tables if they don't exist"
  []
  (let [ds (get-datasource)]
    ;; Users table (base schema)
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          username TEXT UNIQUE NOT NULL,
          password_hash TEXT NOT NULL,
          is_super_admin INTEGER DEFAULT 0,
          created_at TEXT DEFAULT (datetime('now'))
        )"])
    ;; Run migration to add new columns
    (migrate-users-table!)
    ;; Run projects migration
    (migrate-projects-table!)
    ;; Projects table
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS projects (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL,
          owner_id INTEGER NOT NULL,
          created_at TEXT DEFAULT (datetime('now')),
          updated_at TEXT DEFAULT (datetime('now')),
          FOREIGN KEY (owner_id) REFERENCES users(id)
        )"])
    ;; Permissions table
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS permissions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL,
          project_id INTEGER NOT NULL,
          role TEXT NOT NULL CHECK (role IN ('admin', 'collaborator')),
          created_at TEXT DEFAULT (datetime('now')),
          FOREIGN KEY (user_id) REFERENCES users(id),
          FOREIGN KEY (project_id) REFERENCES projects(id),
          UNIQUE (user_id, project_id)
        )"])
    ;; Project chat messages table
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS chat_messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL,
          user_id INTEGER NOT NULL,
          username TEXT NOT NULL,
          message TEXT NOT NULL,
          created_at TEXT DEFAULT (datetime('now')),
          FOREIGN KEY (project_id) REFERENCES projects(id),
          FOREIGN KEY (user_id) REFERENCES users(id)
        )"])))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(def ^:private query-opts
  {:builder-fn rs/as-unqualified-maps})

(defn query
  "Execute a query and return results as unqualified maps"
  [sql-params]
  (jdbc/execute! (get-datasource) sql-params query-opts))

(defn query-one
  "Execute a query and return first result"
  [sql-params]
  (jdbc/execute-one! (get-datasource) sql-params query-opts))

;; =============================================================================
;; User Operations
;; =============================================================================

(defn find-user-by-username [username]
  (query-one ["SELECT * FROM users WHERE username = ?" username]))

(defn find-user-by-id [id]
  (query-one ["SELECT * FROM users WHERE id = ?" id]))

(defn create-user! [{:keys [username password-hash is-super-admin status]}]
  (let [ds (get-datasource)
        ;; First user is always active, others depend on status param (default 'pending')
        effective-status (if is-super-admin "active" (or status "pending"))]
    (jdbc/execute-one! ds
      ["INSERT INTO users (username, password_hash, is_super_admin, status) VALUES (?, ?, ?, ?)"
       username password-hash (if is-super-admin 1 0) effective-status]
      {:return-keys true})
    (find-user-by-username username)))

(defn count-users []
  (:count (query-one ["SELECT COUNT(*) as count FROM users"])))

;; =============================================================================
;; Project Operations
;; =============================================================================

(defn find-project-by-id [id]
  (query-one ["SELECT * FROM projects WHERE id = ?" id]))

(defn find-projects-for-user
  "Find active (non-disabled) projects for a user"
  [user-id]
  (query ["SELECT p.*,
             CASE WHEN p.owner_id = ? THEN 'owner'
                  ELSE pm.role END as user_role
           FROM projects p
           LEFT JOIN permissions pm ON pm.project_id = p.id AND pm.user_id = ?
           WHERE (p.owner_id = ? OR pm.user_id = ?)
             AND (p.disabled = 0 OR p.disabled IS NULL)"
          user-id user-id user-id user-id]))

(defn find-disabled-projects-for-user
  "Find disabled (trashed) projects owned by a user.
   Only owners can see their trashed projects."
  [user-id]
  (query ["SELECT p.*, 'owner' as user_role
           FROM projects p
           WHERE p.owner_id = ? AND p.disabled = 1"
          user-id]))

(defn create-project! [{:keys [name owner-id]}]
  (let [ds (get-datasource)]
    (jdbc/execute-one! ds
      ["INSERT INTO projects (name, owner_id) VALUES (?, ?)" name owner-id]
      {:return-keys true})
    ;; Get the created project
    (query-one ["SELECT * FROM projects WHERE owner_id = ? ORDER BY id DESC LIMIT 1" owner-id])))

(defn update-project! [id changes]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE projects SET name = ?, updated_at = datetime('now') WHERE id = ?"
       (:name changes) id])
    (find-project-by-id id)))

(defn update-project-metadata-cache!
  "Update the metadata_cache column for a project.
   metadata-json should be a JSON string with title, author, year, custom fields, word_count, char_count"
  [project-id metadata-json]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE projects SET metadata_cache = ?, updated_at = datetime('now') WHERE id = ?"
       metadata-json project-id])))

(defn update-project-validation-status!
  "Update the has_validation_errors flag for a project.
   has-errors? should be true if validation found errors, false otherwise."
  [project-id has-errors?]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE projects SET has_validation_errors = ? WHERE id = ?"
       (if has-errors? 1 0) project-id])))

(defn disable-project!
  "Soft delete a project by setting disabled = 1"
  [id]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE projects SET disabled = 1, updated_at = datetime('now') WHERE id = ?" id])))

(defn restore-project!
  "Restore a disabled project by setting disabled = 0"
  [id]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE projects SET disabled = 0, updated_at = datetime('now') WHERE id = ?" id])))

(defn delete-project! [id]
  (let [ds (get-datasource)]
    ;; Delete permissions first
    (jdbc/execute! ds ["DELETE FROM permissions WHERE project_id = ?" id])
    ;; Delete project
    (jdbc/execute! ds ["DELETE FROM projects WHERE id = ?" id])))

(defn user-can-access-project? [user-id project-id]
  (let [project (find-project-by-id project-id)]
    (or (= (:owner_id project) user-id)
        (some? (query-one ["SELECT 1 FROM permissions WHERE user_id = ? AND project_id = ?"
                           user-id project-id])))))

(defn user-is-project-admin? [user-id project-id]
  (let [project (find-project-by-id project-id)]
    (or (= (:owner_id project) user-id)
        (= "admin" (:role (query-one ["SELECT role FROM permissions WHERE user_id = ? AND project_id = ?"
                                       user-id project-id]))))))

(defn get-user-project-role
  "Returns the user's role for a project: :owner, :admin, :collaborator, or nil"
  [user-id project-id]
  (let [project (find-project-by-id project-id)]
    (cond
      (nil? project) nil
      (= (:owner_id project) user-id) :owner
      :else (when-let [perm (query-one ["SELECT role FROM permissions WHERE user_id = ? AND project_id = ?"
                                         user-id project-id])]
              (keyword (:role perm))))))

(defn user-can-edit-content?
  "Returns true if user can edit project content (owner, admin, or collaborator)"
  [user-id project-id]
  (some? (get-user-project-role user-id project-id)))

(defn user-can-edit-metadata?
  "Returns true if user can edit project metadata like name (owner or admin only)"
  [user-id project-id]
  (#{:owner :admin} (get-user-project-role user-id project-id)))

(defn user-is-project-owner?
  "Returns true if user is the project owner"
  [user-id project-id]
  (= :owner (get-user-project-role user-id project-id)))

;; =============================================================================
;; Permission Operations
;; =============================================================================

(defn add-collaborator! [project-id user-id role]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["INSERT OR REPLACE INTO permissions (user_id, project_id, role) VALUES (?, ?, ?)"
       user-id project-id role])))

(defn remove-collaborator! [project-id user-id]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["DELETE FROM permissions WHERE user_id = ? AND project_id = ?" user-id project-id])))

(defn get-project-collaborators [project-id]
  (query ["SELECT u.id, u.username, u.display_name, pm.role
           FROM users u
           JOIN permissions pm ON pm.user_id = u.id
           WHERE pm.project_id = ?" project-id]))

;; =============================================================================
;; Admin User Operations
;; =============================================================================

(defn list-all-users
  "List all users with full info (for super admin), including project count"
  []
  (query ["SELECT u.id, u.username, u.display_name, u.email, u.is_super_admin, u.status,
                  u.max_projects, u.max_project_size_mb, u.max_collaborators, u.notes, u.created_at,
                  (SELECT COUNT(*) FROM projects WHERE owner_id = u.id) as projects_owned
           FROM users u ORDER BY u.id"]))

(defn count-pending-users
  "Count users with status 'pending' (for admin badge)"
  []
  (:count (query-one ["SELECT COUNT(*) as count FROM users WHERE status = 'pending'"])))

(defn get-user-project-count
  "Count how many projects a user owns"
  [user-id]
  (:count (query-one ["SELECT COUNT(*) as count FROM projects WHERE owner_id = ?" user-id])))

(defn delete-user!
  "Delete a user and all their related data"
  [user-id]
  (let [ds (get-datasource)]
    ;; Remove from all permissions
    (jdbc/execute! ds ["DELETE FROM permissions WHERE user_id = ?" user-id])
    ;; Delete chat messages by this user
    (jdbc/execute! ds ["DELETE FROM chat_messages WHERE user_id = ?" user-id])
    ;; Delete projects owned by this user
    (let [owned-projects (query ["SELECT id FROM projects WHERE owner_id = ?" user-id])]
      (doseq [p owned-projects]
        (jdbc/execute! ds ["DELETE FROM chat_messages WHERE project_id = ?" (:id p)])
        (jdbc/execute! ds ["DELETE FROM permissions WHERE project_id = ?" (:id p)])
        (jdbc/execute! ds ["DELETE FROM projects WHERE id = ?" (:id p)])))
    ;; Delete the user
    (jdbc/execute! ds ["DELETE FROM users WHERE id = ?" user-id])))

(defn update-user-super-admin!
  "Update user's super admin status"
  [user-id is-super-admin?]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE users SET is_super_admin = ? WHERE id = ?"
       (if is-super-admin? 1 0) user-id])))

(defn update-user!
  "Update user fields (admin operation).
   Empty strings are converted to nil for optional fields like display_name, email, notes."
  [user-id {:keys [display_name email status max_projects max_project_size_mb max_collaborators notes]}]
  (let [ds (get-datasource)
        ;; Convert empty strings to nil for optional text fields
        display_name (when (seq display_name) display_name)
        email (when (seq email) email)
        notes (when (seq notes) notes)]
    (jdbc/execute! ds
      ["UPDATE users SET
          display_name = ?,
          email = ?,
          status = COALESCE(?, status),
          max_projects = COALESCE(?, max_projects),
          max_project_size_mb = COALESCE(?, max_project_size_mb),
          max_collaborators = COALESCE(?, max_collaborators),
          notes = ?
        WHERE id = ?"
       display_name email status max_projects max_project_size_mb max_collaborators notes user-id])
    (find-user-by-id user-id)))

(defn update-own-profile!
  "Update user's own profile (display_name, email)"
  [user-id {:keys [display_name email]}]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE users SET display_name = ?, email = ? WHERE id = ?"
       display_name email user-id])
    (find-user-by-id user-id)))

(defn update-user-password!
  "Update user's password hash"
  [user-id password-hash]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["UPDATE users SET password_hash = ? WHERE id = ?"
       password-hash user-id])))

(defn get-user-quotas
  "Get user's quota limits and current usage"
  [user-id]
  (let [user (find-user-by-id user-id)
        project-count (get-user-project-count user-id)]
    {:max_projects (or (:max_projects user) 10)
     :max_project_size_mb (or (:max_project_size_mb user) 50)
     :max_collaborators (or (:max_collaborators user) 10)
     :projects_used project-count}))

;; =============================================================================
;; Chat Operations
;; =============================================================================

(defn get-chat-messages
  "Get chat messages for a project, optionally after a specific ID (for polling)"
  ([project-id]
   (get-chat-messages project-id nil))
  ([project-id after-id]
   (if after-id
     (query ["SELECT id, username, message, created_at
              FROM chat_messages
              WHERE project_id = ? AND id > ?
              ORDER BY id ASC"
             project-id after-id])
     (query ["SELECT id, username, message, created_at
              FROM chat_messages
              WHERE project_id = ?
              ORDER BY id ASC
              LIMIT 100"
             project-id]))))

(def ^:private max-chat-messages 500)

(defn- purge-old-chat-messages!
  "Delete oldest messages if project has more than max-chat-messages"
  [project-id]
  (let [ds (get-datasource)
        count-result (query-one ["SELECT COUNT(*) as cnt FROM chat_messages WHERE project_id = ?" project-id])
        msg-count (:cnt count-result 0)]
    (when (> msg-count max-chat-messages)
      ;; Delete oldest messages, keeping only the most recent max-chat-messages
      (let [to-delete (- msg-count max-chat-messages)]
        (jdbc/execute! ds
          ["DELETE FROM chat_messages WHERE id IN (
              SELECT id FROM chat_messages WHERE project_id = ? ORDER BY id ASC LIMIT ?
            )" project-id to-delete])))))

(defn add-chat-message!
  "Add a chat message to a project.
   Automatically purges old messages if over limit (500)."
  [project-id user-id username message]
  (let [ds (get-datasource)]
    (jdbc/execute-one! ds
      ["INSERT INTO chat_messages (project_id, user_id, username, message) VALUES (?, ?, ?, ?)"
       project-id user-id username message]
      {:return-keys true})
    ;; Auto-purge old messages
    (purge-old-chat-messages! project-id)
    ;; Return the created message
    (query-one ["SELECT id, username, message, created_at
                 FROM chat_messages
                 WHERE project_id = ? AND user_id = ?
                 ORDER BY id DESC LIMIT 1"
                project-id user-id])))

(defn clear-chat!
  "Clear all chat messages for a project (admin/owner only)"
  [project-id]
  (let [ds (get-datasource)]
    (jdbc/execute! ds
      ["DELETE FROM chat_messages WHERE project_id = ?" project-id])))

;; =============================================================================
;; Cleanup Operations
;; =============================================================================

(defn cleanup-old-pending-users!
  "Delete pending users that were created more than 7 days ago.
   Returns the number of deleted users."
  []
  (let [;; SQLite datetime comparison: created_at < datetime('now', '-7 days')
        old-pending (query ["SELECT id FROM users
                             WHERE status = 'pending'
                             AND created_at < datetime('now', '-7 days')"])
        deleted-count (count old-pending)]
    (doseq [user old-pending]
      ;; Use existing delete-user! to properly clean up related data
      (delete-user! (:id user)))
    deleted-count))
