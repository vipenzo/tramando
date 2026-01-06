(ns tramando.server.storage
  "File storage for .trmd project files"
  (:require [tramando.server.config :refer [config]]
            [clojure.java.io :as io])
  (:import [java.security MessageDigest]
           [java.util Base64]))

(defn- ensure-projects-dir! []
  (let [dir (io/file (:projects-path config))]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn project-file-path [project-id]
  (str (:projects-path config) "/" project-id ".trmd"))

(defn save-project-content!
  "Save project content to file"
  [project-id content]
  (ensure-projects-dir!)
  (spit (project-file-path project-id) content))

(defn load-project-content
  "Load project content from file"
  [project-id]
  (let [file (io/file (project-file-path project-id))]
    (when (.exists file)
      (slurp file))))

(defn delete-project-file!
  "Delete project file"
  [project-id]
  (let [file (io/file (project-file-path project-id))]
    (when (.exists file)
      (.delete file))))

(defn project-file-exists? [project-id]
  (.exists (io/file (project-file-path project-id))))

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
   Returns {:ok true :hash new-hash} on success,
   {:ok false :error :conflict :current-hash hash} on conflict,
   {:ok true :hash new-hash} if base-hash is nil (new project or force save)."
  [project-id content base-hash]
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
       :message "Content was modified by another user"})))
