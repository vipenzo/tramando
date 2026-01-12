(ns tramando.server.versioning
  "Git-based versioning for projects.

   Each project gets its own git repository in a subdirectory.
   Structure:
     data/projects/
       23/
         .git/
         project.trmd

   For backwards compatibility, projects without a git repo
   are migrated on first versioning operation."
  (:require [tramando.server.config :refer [config]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Path Helpers
;; =============================================================================

(defn project-dir-path
  "Get the directory path for a project's git repo."
  [project-id]
  (str (:projects-path config) "/" project-id))

(defn project-file-in-repo
  "Get the path to project.trmd inside the git repo."
  [project-id]
  (str (project-dir-path project-id) "/project.trmd"))

(defn legacy-project-file
  "Get the legacy flat file path (for migration)."
  [project-id]
  (str (:projects-path config) "/" project-id ".trmd"))

;; =============================================================================
;; Shell Command Execution
;; =============================================================================

(defn- run-git
  "Run a git command in the project directory.
   Returns {:ok true :output string} or {:ok false :error string}."
  [project-id & args]
  (let [dir (File. (project-dir-path project-id))
        pb (ProcessBuilder. (into-array String (cons "git" args)))]
    (.directory pb dir)
    (.redirectErrorStream pb true)
    (try
      (let [process (.start pb)
            output (slurp (.getInputStream process))
            exit-code (.waitFor process)]
        (if (zero? exit-code)
          {:ok true :output (str/trim output)}
          {:ok false :error (str/trim output) :exit-code exit-code}))
      (catch Exception e
        {:ok false :error (.getMessage e)}))))

;; =============================================================================
;; Repository Management
;; =============================================================================

(defn repo-exists?
  "Check if a git repository exists for a project."
  [project-id]
  (let [git-dir (File. (str (project-dir-path project-id) "/.git"))]
    (.isDirectory git-dir)))

(defn- ensure-project-dir!
  "Ensure the project directory exists."
  [project-id]
  (let [dir (File. (project-dir-path project-id))]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn init-repo!
  "Initialize a new git repository for a project.
   If content is provided, creates initial commit."
  [project-id & [content]]
  (ensure-project-dir! project-id)
  (let [init-result (run-git project-id "init")]
    (if (:ok init-result)
      (do
        ;; Configure git user for this repo
        (run-git project-id "config" "user.email" "tramando@local")
        (run-git project-id "config" "user.name" "Tramando")
        ;; If content provided, save and commit
        (when content
          (spit (project-file-in-repo project-id) content)
          (run-git project-id "add" "project.trmd")
          (run-git project-id "commit" "-m" "Initial commit"))
        {:ok true})
      init-result)))

(defn migrate-to-repo!
  "Migrate a legacy flat-file project to git repo structure.
   Moves the .trmd file into a directory and initializes git."
  [project-id]
  (let [legacy-file (File. (legacy-project-file project-id))
        repo-dir (File. (project-dir-path project-id))
        repo-file (File. (project-file-in-repo project-id))]
    (cond
      ;; Already migrated
      (repo-exists? project-id)
      {:ok true :migrated false}

      ;; Legacy file exists - migrate it
      (.exists legacy-file)
      (do
        (ensure-project-dir! project-id)
        ;; Move file into repo directory
        (let [content (slurp legacy-file)]
          (spit repo-file content)
          (.delete legacy-file)
          ;; Initialize git
          (let [result (init-repo! project-id)]
            (if (:ok result)
              (do
                (run-git project-id "add" "project.trmd")
                (run-git project-id "commit" "-m" "Migrated from legacy format")
                {:ok true :migrated true})
              result))))

      ;; No file at all - just init empty repo
      :else
      (init-repo! project-id))))

(defn ensure-repo!
  "Ensure a git repo exists for a project, migrating if needed.
   Call this before any versioning operation."
  [project-id]
  (if (repo-exists? project-id)
    {:ok true}
    (migrate-to-repo! project-id)))

;; =============================================================================
;; Versioning Operations
;; =============================================================================

(defn- format-timestamp []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))

(defn create-auto-version!
  "Create an automatic version (git commit).
   Called when undo stack exceeds threshold."
  [project-id]
  (when-let [result (ensure-repo! project-id)]
    (when (:ok result)
      (run-git project-id "add" "project.trmd")
      (let [commit-result (run-git project-id "commit" "-m"
                                   (str "Auto-save " (format-timestamp)))]
        (if (:ok commit-result)
          {:ok true :message (:output commit-result)}
          ;; Nothing to commit is not an error
          (if (str/includes? (or (:error commit-result) "") "nothing to commit")
            {:ok true :message "No changes to commit"}
            commit-result))))))

(defn create-tagged-version!
  "Create a tagged version with a user-provided name."
  [project-id tag-name & [message]]
  (when-let [result (ensure-repo! project-id)]
    (when (:ok result)
      ;; First commit any pending changes
      (run-git project-id "add" "project.trmd")
      (run-git project-id "commit" "-m" (str "Version: " tag-name) "--allow-empty")
      ;; Create tag
      (let [tag-args (if message
                       ["tag" "-a" tag-name "-m" message]
                       ["tag" tag-name])]
        (apply run-git project-id tag-args)))))

(defn list-versions
  "List all versions (commits and tags) for a project.
   Returns vector of {:ref string :message string :date string :is-tag bool}."
  [project-id]
  (when-let [result (ensure-repo! project-id)]
    (when (:ok result)
      (let [;; Get commit log
            log-result (run-git project-id "log" "--oneline" "--date=short"
                               "--format=%H|%s|%ci")
            ;; Get tags
            tags-result (run-git project-id "tag" "-l")]
        (when (:ok log-result)
          (let [tag-set (if (:ok tags-result)
                          (set (str/split-lines (:output tags-result)))
                          #{})
                commits (when-not (str/blank? (:output log-result))
                          (->> (str/split-lines (:output log-result))
                               (map (fn [line]
                                      (let [[hash msg date] (str/split line #"\|" 3)]
                                        {:ref hash
                                         :short-ref (subs hash 0 (min 7 (count hash)))
                                         :message msg
                                         :date date
                                         :is-tag false})))
                               (vec)))]
            ;; Mark commits that have tags
            (mapv (fn [commit]
                    (let [tag-for-commit (run-git project-id "tag" "--points-at" (:ref commit))]
                      (if (and (:ok tag-for-commit)
                               (not (str/blank? (:output tag-for-commit))))
                        (assoc commit
                               :tag (first (str/split-lines (:output tag-for-commit)))
                               :is-tag true)
                        commit)))
                  (or commits []))))))))

(defn get-version-content
  "Get the content of a specific version (by commit hash or tag name)."
  [project-id ref]
  (when-let [result (ensure-repo! project-id)]
    (when (:ok result)
      (let [show-result (run-git project-id "show" (str ref ":project.trmd"))]
        (when (:ok show-result)
          (:output show-result))))))

(defn get-current-commit
  "Get the current HEAD commit hash."
  [project-id]
  (when (repo-exists? project-id)
    (let [result (run-git project-id "rev-parse" "HEAD")]
      (when (:ok result)
        (:output result)))))

;; =============================================================================
;; Storage Integration
;; =============================================================================

(defn save-to-repo!
  "Save content to the project's git repo (without committing).
   This is called by storage/save-project-content! after migration."
  [project-id content]
  (ensure-project-dir! project-id)
  (spit (project-file-in-repo project-id) content))

(defn load-from-repo
  "Load content from the project's git repo."
  [project-id]
  (let [file (File. (project-file-in-repo project-id))]
    (when (.exists file)
      (slurp file))))

(defn delete-repo!
  "Delete a project's git repository."
  [project-id]
  (let [dir (File. (project-dir-path project-id))]
    (when (.exists dir)
      ;; Recursively delete
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn get-project-content
  "Get project content, checking both new repo structure and legacy flat file.
   Use this as the main entry point for loading."
  [project-id]
  (or (load-from-repo project-id)
      (let [legacy-file (File. (legacy-project-file project-id))]
        (when (.exists legacy-file)
          (slurp legacy-file)))))

(defn save-project-content!
  "Save project content. Handles both repo and legacy structures.
   New projects get repos, existing ones continue with their structure
   until explicitly migrated."
  [project-id content]
  (if (repo-exists? project-id)
    ;; Save to repo
    (save-to-repo! project-id content)
    ;; Check if this is a new project or legacy
    (let [legacy-file (File. (legacy-project-file project-id))]
      (if (.exists legacy-file)
        ;; Legacy project - save to legacy location
        (spit legacy-file content)
        ;; New project - create repo
        (do
          (init-repo! project-id content)
          nil)))))
