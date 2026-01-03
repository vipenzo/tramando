(ns tramando.server.storage
  "File storage for .trmd project files"
  (:require [tramando.server.config :refer [config]]
            [clojure.java.io :as io]))

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
