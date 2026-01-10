#!/usr/bin/env clojure
;; Script per creare un database di test con fixture predefinite
;; Uso: clojure -M e2e/fixtures/setup-test-db.clj

(require '[clojure.java.io :as io])

;; Aggiungi il path del server al classpath
(let [server-src (io/file "server/src")]
  (when (.exists server-src)
    (add-classpath (.toURL server-src))))

(require '[next.jdbc :as jdbc]
         '[buddy.hashers :as hashers])

(def test-db-path "e2e/fixtures/test-tramando.db")

(defn delete-if-exists [path]
  (let [f (io/file path)]
    (when (.exists f)
      (.delete f)
      (println "Deleted existing:" path))))

(defn create-schema [ds]
  ;; Users table
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name TEXT,
    email TEXT,
    is_super_admin INTEGER DEFAULT 0,
    status TEXT DEFAULT 'pending',
    max_projects INTEGER DEFAULT 10,
    max_project_size_mb INTEGER DEFAULT 50,
    max_collaborators INTEGER DEFAULT 5,
    notes TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
  )"])

  ;; Projects table
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    owner_id INTEGER NOT NULL,
    metadata_cache TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_id) REFERENCES users(id)
  )"])

  ;; Collaborators table
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS collaborators (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    role TEXT DEFAULT 'collaborator',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(project_id, user_id)
  )"])

  ;; Chat messages table
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS chat_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
  )"])

  (println "Schema created"))

(defn hash-password [password]
  (hashers/derive password))

(defn insert-users [ds]
  ;; Super admin
  (jdbc/execute! ds ["INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
                      VALUES (?, ?, ?, 1, 'active')"
                     "admin" (hash-password "admin123") "Admin User"])

  ;; Normal users
  (jdbc/execute! ds ["INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
                      VALUES (?, ?, ?, 0, 'active')"
                     "alice" (hash-password "alice123") "Alice Writer"])

  (jdbc/execute! ds ["INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
                      VALUES (?, ?, ?, 0, 'active')"
                     "bob" (hash-password "bob123") "Bob Editor"])

  (jdbc/execute! ds ["INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
                      VALUES (?, ?, ?, 0, 'active')"
                     "carol" (hash-password "carol123") "Carol Reviewer"])

  ;; Pending user (for testing registration approval)
  (jdbc/execute! ds ["INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
                      VALUES (?, ?, ?, 0, 'pending')"
                     "pending_user" (hash-password "pending123") "Pending User"])

  (println "Users created: admin, alice, bob, carol, pending_user"))

(defn insert-projects [ds]
  ;; Alice's projects
  (jdbc/execute! ds ["INSERT INTO projects (name, owner_id, metadata_cache) VALUES (?, 2, ?)"
                     "Alice Project 1"
                     "{\"title\":\"Il mio romanzo\",\"author\":\"Alice\",\"word_count\":15000}"])

  (jdbc/execute! ds ["INSERT INTO projects (name, owner_id, metadata_cache) VALUES (?, 2, ?)"
                     "Alice Project 2"
                     "{\"title\":\"Racconti brevi\",\"author\":\"Alice\",\"word_count\":5000}"])

  ;; Bob's project
  (jdbc/execute! ds ["INSERT INTO projects (name, owner_id, metadata_cache) VALUES (?, 3, ?)"
                     "Bob Project"
                     "{\"title\":\"Saggio\",\"author\":\"Bob\",\"word_count\":8000}"])

  ;; Admin's project (for testing admin features)
  (jdbc/execute! ds ["INSERT INTO projects (name, owner_id, metadata_cache) VALUES (?, 1, ?)"
                     "Admin Project"
                     "{\"title\":\"Documentazione\",\"author\":\"Admin\",\"word_count\":2000}"])

  (println "Projects created"))

(defn insert-collaborators [ds]
  ;; Bob collaborates on Alice Project 1
  (jdbc/execute! ds ["INSERT INTO collaborators (project_id, user_id, role) VALUES (1, 3, 'collaborator')"])

  ;; Carol collaborates on Alice Project 1 as admin
  (jdbc/execute! ds ["INSERT INTO collaborators (project_id, user_id, role) VALUES (1, 4, 'admin')"])

  ;; Alice collaborates on Bob's project
  (jdbc/execute! ds ["INSERT INTO collaborators (project_id, user_id, role) VALUES (3, 2, 'collaborator')"])

  (println "Collaborators created"))

(defn create-project-files []
  ;; Create project content files
  (let [projects-dir (io/file "e2e/fixtures/test-projects")]
    (.mkdirs projects-dir)

    ;; Alice Project 1
    (spit (io/file projects-dir "1.trmd")
          "---\ntitle: Il mio romanzo\nauthor: Alice\nyear: 2024\n---\n\n# Capitolo 1\n\nC'era una volta...\n\n# Capitolo 2\n\nE vissero felici e contenti.")

    ;; Alice Project 2
    (spit (io/file projects-dir "2.trmd")
          "---\ntitle: Racconti brevi\nauthor: Alice\n---\n\n# Racconto 1\n\nUn breve racconto.\n\n# Racconto 2\n\nUn altro racconto.")

    ;; Bob Project
    (spit (io/file projects-dir "3.trmd")
          "---\ntitle: Saggio\nauthor: Bob\n---\n\n# Introduzione\n\nQuesto è un saggio.\n\n# Conclusione\n\nFine del saggio.")

    ;; Admin Project
    (spit (io/file projects-dir "4.trmd")
          "---\ntitle: Documentazione\nauthor: Admin\n---\n\n# Documentazione\n\nContenuto della documentazione.")

    (println "Project files created in" (.getPath projects-dir))))

(defn -main []
  (println "Setting up test database...")

  ;; Delete existing test database
  (delete-if-exists test-db-path)

  ;; Create datasource
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname test-db-path})]

    ;; Create schema
    (create-schema ds)

    ;; Insert fixture data
    (insert-users ds)
    (insert-projects ds)
    (insert-collaborators ds)

    ;; Create project files
    (create-project-files)

    (println "\nTest database created successfully!")
    (println "Database:" test-db-path)
    (println "\nTest credentials:")
    (println "  admin/admin123 (super-admin)")
    (println "  alice/alice123 (normal user, owns 2 projects)")
    (println "  bob/bob123 (normal user, owns 1 project, collaborates on alice's)")
    (println "  carol/carol123 (normal user, collaborates on alice's as admin)")
    (println "  pending_user/pending123 (pending approval)")))

(-main)
