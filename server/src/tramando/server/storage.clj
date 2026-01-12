(ns tramando.server.storage
  "File storage for .trmd project files.

   Storage structure:
   - New projects: data/projects/{id}/project.trmd (with .git/)
   - Legacy projects: data/projects/{id}.trmd (migrated on first versioning op)

   This module handles both structures transparently."
  (:require [tramando.server.config :refer [config]]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.util Base64]))

;; =============================================================================
;; Per-project locking for atomic check-and-write
;; =============================================================================

(defonce ^:private project-locks (atom {}))
(defonce ^:private locks-lock (Object.))

(defn get-project-lock
  "Get or create a lock object for a specific project.
   Each project has its own lock to avoid blocking unrelated projects.
   Uses double-checked locking pattern for thread-safe lazy initialization."
  [project-id]
  (if-let [lock (get @project-locks project-id)]
    lock
    (locking locks-lock
      (if-let [lock (get @project-locks project-id)]
        lock
        (let [new-lock (Object.)]
          (swap! project-locks assoc project-id new-lock)
          new-lock)))))

;; =============================================================================
;; Path Helpers
;; =============================================================================

(defn- ensure-projects-dir! []
  (let [dir (io/file (:projects-path config))]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn project-dir-path
  "Get the directory path for a project (new structure)."
  [project-id]
  (str (:projects-path config) "/" project-id))

(defn project-file-in-dir
  "Get the path to project.trmd inside the project directory."
  [project-id]
  (str (project-dir-path project-id) "/project.trmd"))

(defn legacy-file-path
  "Get the legacy flat file path."
  [project-id]
  (str (:projects-path config) "/" project-id ".trmd"))

;; Keep old function for backwards compatibility
(defn project-file-path
  "Get the project file path. Prefers new structure, falls back to legacy."
  [project-id]
  (let [new-path (project-file-in-dir project-id)
        new-file (File. new-path)]
    (if (.exists new-file)
      new-path
      (legacy-file-path project-id))))

(defn- ensure-project-dir!
  "Ensure the project directory exists."
  [project-id]
  (ensure-projects-dir!)
  (let [dir (File. (project-dir-path project-id))]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn uses-new-structure?
  "Check if a project uses the new directory structure."
  [project-id]
  (let [dir (File. (project-dir-path project-id))]
    (.isDirectory dir)))

;; =============================================================================
;; File Operations
;; =============================================================================

(defn save-project-content!
  "Save project content to file.
   New projects get the directory structure.
   Existing projects keep their current structure."
  [project-id content]
  (ensure-projects-dir!)
  (let [legacy-file (File. (legacy-file-path project-id))
        dir-file (File. (project-file-in-dir project-id))]
    (cond
      ;; Project already uses new structure
      (.exists dir-file)
      (spit dir-file content)

      ;; Legacy file exists - continue using it (until migration)
      (.exists legacy-file)
      (spit legacy-file content)

      ;; New project - use new structure
      :else
      (do
        (ensure-project-dir! project-id)
        (spit (project-file-in-dir project-id) content)))))

(defn load-project-content
  "Load project content from file.
   Checks new structure first, then legacy."
  [project-id]
  (let [dir-file (File. (project-file-in-dir project-id))
        legacy-file (File. (legacy-file-path project-id))]
    (cond
      (.exists dir-file) (slurp dir-file)
      (.exists legacy-file) (slurp legacy-file)
      :else nil)))

(defn delete-project-file!
  "Delete project file(s) and directory."
  [project-id]
  (let [legacy-file (File. (legacy-file-path project-id))
        project-dir (File. (project-dir-path project-id))]
    ;; Delete legacy file if exists
    (when (.exists legacy-file)
      (.delete legacy-file))
    ;; Delete project directory recursively if exists
    (when (.exists project-dir)
      (doseq [f (reverse (file-seq project-dir))]
        (.delete f)))))

(defn project-file-exists?
  "Check if a project file exists (in either structure)."
  [project-id]
  (or (.exists (File. (project-file-in-dir project-id)))
      (.exists (File. (legacy-file-path project-id)))))

;; =============================================================================
;; Content hashing for optimistic concurrency control
;; =============================================================================

(defn content-hash
  "Calculate SHA-256 hash of content, returns base64-encoded string.
   Empty/nil content returns nil hash."
  [content]
  (when (and content (not= content ""))
    (let [digest (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes content "UTF-8"))]
      (.encodeToString (Base64/getEncoder) hash-bytes))))

(defn get-project-hash
  "Get the current hash of a project's content"
  [project-id]
  (content-hash (load-project-content project-id)))

(defn save-project-content-if-matches!
  "Save project content only if the base-hash matches current content.
   Uses per-project locking to prevent TOCTOU race conditions.
   Returns {:ok true :hash new-hash} on success,
   {:ok false :error :conflict :current-hash hash} on conflict,
   {:ok true :hash new-hash} if base-hash is nil (new project or force save)."
  [project-id content base-hash]
  (locking (get-project-lock project-id)
    (let [current-hash (get-project-hash project-id)]
      (cond
        ;; No base-hash provided = force save (new project or initial save)
        (nil? base-hash)
        (do
          (save-project-content! project-id content)
          {:ok true :hash (content-hash content)})

        ;; Hash matches = safe to save
        (= base-hash current-hash)
        (do
          (save-project-content! project-id content)
          {:ok true :hash (content-hash content)})

        ;; Hash mismatch = conflict
        :else
        {:ok false
         :error :conflict
         :current-hash current-hash
         :message "Content was modified by another user"}))))
