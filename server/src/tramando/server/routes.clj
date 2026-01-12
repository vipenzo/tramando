(ns tramando.server.routes
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [tramando.server.auth :as auth]
            [tramando.server.db :as db]
            [tramando.server.storage :as storage]
            [tramando.server.trmd :as trmd]
            [tramando.server.undo :as undo]
            [tramando.server.versioning :as versioning]
            [tramando.server.presence :as presence]
            [tramando.server.config :refer [config]]))

;; =============================================================================
;; Content Metadata Extraction
;; =============================================================================

(defn- parse-yaml-frontmatter
  "Extract YAML frontmatter from TRMD content.
   Returns a map with metadata fields or nil if no frontmatter."
  [content]
  (when (and content (str/starts-with? (str/trim content) "---"))
    (let [trimmed (str/trim content)
          ;; Find closing ---
          end-idx (str/index-of trimmed "---" 3)]
      (when end-idx
        (let [yaml-str (subs trimmed 3 end-idx)]
          ;; Simple YAML parsing for key: value pairs
          (->> (str/split-lines yaml-str)
               (map str/trim)
               (filter #(str/includes? % ":"))
               (map (fn [line]
                      (let [colon-idx (str/index-of line ":")]
                        (when colon-idx
                          [(keyword (str/trim (subs line 0 colon-idx)))
                           (str/trim (subs line (inc colon-idx)))]))))
               (filter some?)
               (into {})))))))

(defn- count-words
  "Count words in text content (excluding YAML frontmatter and chunk headers)"
  [content]
  (if (str/blank? content)
    0
    (let [;; Remove YAML frontmatter
          text (if (str/starts-with? (str/trim content) "---")
                 (let [end-idx (str/index-of content "---" 3)]
                   (if end-idx
                     (subs content (+ end-idx 3))
                     content))
                 content)
          ;; Remove chunk headers (lines starting with #)
          lines (str/split-lines text)
          content-lines (remove #(str/starts-with? (str/trim %) "#") lines)
          text-only (str/join " " content-lines)]
      (count (re-seq #"\S+" text-only)))))

(defn- count-chars
  "Count characters in text content (excluding YAML frontmatter and chunk headers)"
  [content]
  (if (str/blank? content)
    0
    (let [;; Remove YAML frontmatter
          text (if (str/starts-with? (str/trim content) "---")
                 (let [end-idx (str/index-of content "---" 3)]
                   (if end-idx
                     (subs content (+ end-idx 3))
                     content))
                 content)
          ;; Remove chunk headers (lines starting with #)
          lines (str/split-lines text)
          content-lines (remove #(str/starts-with? (str/trim %) "#") lines)
          text-only (str/join "\n" content-lines)]
      (count (str/replace text-only #"\s+" "")))))

(defn- extract-metadata-cache
  "Extract metadata from TRMD content and return as JSON string.
   Includes: title, author, year, genre, custom fields, word_count, char_count"
  [content]
  (let [frontmatter (or (parse-yaml-frontmatter content) {})
        word-count (count-words content)
        char-count (count-chars content)
        metadata (assoc frontmatter
                        :word_count word-count
                        :char_count char-count)]
    (json/generate-string metadata)))

(defn- update-metadata-cache!
  "Update the metadata cache for a project based on its content"
  [project-id content]
  (when content
    (let [metadata-json (extract-metadata-cache content)]
      (db/update-project-metadata-cache! project-id metadata-json))))

;; =============================================================================
;; Content Validation
;; =============================================================================

(def ^:private chunk-header-re
  "Regex to match chunk headers: [C:id\"summary\"]..."
  #"\[C:([^\]\"]+)\"([^\"]*)\"\]")

(defn- extract-chunk-ids-and-summaries
  "Extract all chunk IDs and summaries from TRMD content"
  [content]
  (when content
    (map (fn [[_ id summary]]
           {:id id :summary summary})
         (re-seq chunk-header-re content))))

(defn- validate-trmd-content
  "Validate TRMD content for structural integrity.
   Returns {:ok? true} or {:ok? false :errors [...]}

   Checks:
   - Duplicate IDs
   - Quotes in summaries (breaks format)"
  [content]
  (if (str/blank? content)
    {:ok? true}
    (let [chunks (extract-chunk-ids-and-summaries content)
          ;; Check duplicate IDs
          id-counts (frequencies (map :id chunks))
          duplicate-ids (keep (fn [[id cnt]] (when (> cnt 1) id)) id-counts)
          duplicate-errors (map (fn [id]
                                  {:type :duplicate-id
                                   :id id
                                   :message (str "Duplicate ID: " id)})
                                duplicate-ids)
          ;; Check quotes in summaries
          invalid-summaries (filter #(and (:summary %)
                                          (str/includes? (:summary %) "\""))
                                    chunks)
          summary-errors (map (fn [chunk]
                                {:type :invalid-summary
                                 :id (:id chunk)
                                 :message (str "Summary contains quotes: " (:summary chunk))})
                              invalid-summaries)
          ;; Combine errors
          all-errors (concat duplicate-errors summary-errors)]
      (if (empty? all-errors)
        {:ok? true}
        {:ok? false :errors (vec all-errors)}))))

(defn- validate-and-update-status!
  "Validate content and update project validation status in DB.
   Logs errors if found. Always saves anyway (save-anyway behavior)."
  [project-id content]
  (let [result (validate-trmd-content content)
        file-path (storage/project-file-path project-id)]
    (if (:ok? result)
      (db/update-project-validation-status! project-id nil)
      (let [errors-json (json/generate-string (:errors result))]
        ;; Log validation errors with file path
        (println (str "[VALIDATION] Project " project-id " (" file-path ") has errors:"))
        (doseq [err (:errors result)]
          (println (str "  - " (name (:type err)) ": " (:message err))))
        ;; Save errors to DB
        (db/update-project-validation-status! project-id errors-json)))))

;; =============================================================================
;; Auth Handlers
;; =============================================================================

(defn register-handler [request]
  (let [{:keys [username password website]} (:body-params request)]
    ;; Honeypot check: if 'website' field is filled, it's a bot
    ;; Respond with fake success to not alert the bot
    (if (and website (seq website))
      {:status 201 :body {:pending true
                          :message "Registrazione completata. Attendi l'approvazione dell'amministratore."}}
      ;; Rate limiting check
      (let [rate-check (auth/check-rate-limit request)]
        (if (:error rate-check)
          {:status 429 :body rate-check}
          ;; Record attempt and proceed with registration
          (do
            (auth/record-registration-attempt! request)
            (let [result (auth/register! {:username username :password password})]
              (if (:error result)
                {:status 400 :body result}
                {:status 201 :body result}))))))))

(defn login-handler [request]
  (let [{:keys [username password]} (:body-params request)
        result (auth/login! {:username username :password password})]
    (if (:error result)
      {:status 401 :body result}
      {:status 200 :body result})))

(defn me-handler [request]
  {:status 200
   :body {:user (:user request)}})

;; =============================================================================
;; Project Handlers
;; =============================================================================

(defn list-projects-handler [request]
  (let [user-id (get-in request [:user :id])
        projects (db/find-projects-for-user user-id)]
    {:status 200
     :body {:projects projects}}))

(defn create-project-handler [request]
  (let [user-id (get-in request [:user :id])
        {:keys [name content]} (:body-params request)
        quotas (db/get-user-quotas user-id)]
    ;; Check project quota
    (if (>= (:projects_used quotas) (:max_projects quotas))
      {:status 403
       :body {:error (str "Hai raggiunto il limite massimo di progetti (" (:max_projects quotas) ")")}}
      (let [project (db/create-project! {:name name :owner-id user-id})]
        ;; Save initial content if provided
        (when content
          (storage/save-project-content! (:id project) content)
          ;; Update metadata cache
          (update-metadata-cache! (:id project) content)
          ;; Validate content and update status flag
          (validate-and-update-status! (:id project) content))
        {:status 201
         :body {:project project}}))))

(defn get-project-handler [request]
  (let [user-id (get-in request [:user :id])
        username (-> request :user :username)
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [project (db/find-project-by-id project-id)
            content (storage/load-project-content project-id)
            content-hash (storage/content-hash content)
            role (db/get-user-project-role user-id project-id)
            ;; Include presence info (excluding self)
            editing (presence/get-project-presence-excluding (str project-id) username)]
        {:status 200
         :body {:project project
                :content (or content "")
                :content-hash content-hash
                :role (when role (name role))
                :editing editing}}))))

(defn get-project-hash-handler
  "Returns only the content-hash for polling - lightweight endpoint"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [content (storage/load-project-content project-id)
            content-hash (storage/content-hash content)]
        {:status 200
         :body {:content-hash content-hash}}))))

(defn update-project-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [name content base-hash]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      ;; No access at all
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      ;; Trying to change name without admin/owner privileges
      (and name (not (#{:owner :admin} role)))
      {:status 403 :body {:error "Only owner or admin can rename project"}}

      :else
      (do
        ;; Update metadata if name provided (already checked permission above)
        (when name
          (db/update-project! project-id {:name name}))
        ;; Save content if provided (all roles can edit content)
        (if content
          (let [;; Push current content to undo stack before saving (only for owner)
                _ (when (= role :owner)
                    (when-let [current-content (storage/load-project-content project-id)]
                      (undo/push-undo! project-id current-content)))
                save-result (storage/save-project-content-if-matches! project-id content base-hash)]
            (if (:ok save-result)
              (do
                ;; Update metadata cache with new content
                (update-metadata-cache! project-id content)
                ;; Validate content and update status flag (save anyway)
                (validate-and-update-status! project-id content)
                (let [project (db/find-project-by-id project-id)]
                  {:status 200
                   :body {:project project
                          :content-hash (:hash save-result)
                          :can-undo (undo/can-undo? project-id)
                          :can-redo (undo/can-redo? project-id)}}))
              ;; Conflict - return 409 with current content for client-side merge
              {:status 409
               :body {:error "Conflict: content was modified by another user"
                      :current-hash (:current-hash save-result)
                      :current-content (storage/load-project-content project-id)}}))
          ;; No content change, just metadata update
          (let [project (db/find-project-by-id project-id)]
            {:status 200
             :body {:project project}}))))))

(defn delete-project-handler
  "Soft delete: moves project to trash by setting disabled=1"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        project (db/find-project-by-id project-id)]
    (cond
      (nil? project)
      {:status 404 :body {:error "Project not found"}}

      (not= (:owner_id project) user-id)
      {:status 403 :body {:error "Only owner can delete project"}}

      :else
      (do
        ;; Soft delete - just disable, don't delete file
        (db/disable-project! project-id)
        {:status 200 :body {:success true}}))))

(defn list-trash-handler
  "List disabled (trashed) projects for the current user"
  [request]
  (let [user-id (get-in request [:user :id])
        projects (db/find-disabled-projects-for-user user-id)]
    {:status 200
     :body {:projects projects}}))

(defn restore-project-handler
  "Restore a project from trash"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        project (db/find-project-by-id project-id)]
    (cond
      (nil? project)
      {:status 404 :body {:error "Project not found"}}

      (not= (:owner_id project) user-id)
      {:status 403 :body {:error "Only owner can restore project"}}

      (not= 1 (:disabled project))
      {:status 400 :body {:error "Project is not in trash"}}

      :else
      (do
        (db/restore-project! project-id)
        {:status 200 :body {:success true}}))))

(defn permanent-delete-handler
  "Permanently delete a project (only from trash)"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        project (db/find-project-by-id project-id)]
    (cond
      (nil? project)
      {:status 404 :body {:error "Project not found"}}

      (not= (:owner_id project) user-id)
      {:status 403 :body {:error "Only owner can permanently delete project"}}

      (not= 1 (:disabled project))
      {:status 400 :body {:error "Project must be in trash before permanent deletion"}}

      :else
      (do
        ;; Permanent delete - remove file and database record
        (storage/delete-project-file! project-id)
        (db/delete-project! project-id)
        {:status 200 :body {:success true}}))))

;; =============================================================================
;; Chunk Operations Handlers (Atomic REST API)
;; =============================================================================

(defn- can-create-chunk-at?
  "Check if user can create a chunk at the given parent-id.
   Owner can create anywhere. Collaborator can only create under aspects."
  [role parent-id]
  (or (#{:owner :admin} role)
      ;; Collaborator can create under aspect containers or existing aspects
      (trmd/is-aspect-container? parent-id)
      ;; Could also be under an existing aspect - check parent's parent
      ;; For now, allow if parent is an aspect container
      false))

(defn- can-delete-chunk?
  "Check if user can delete a chunk. Only owner can delete structure."
  [role _chunk]
  (#{:owner :admin} role))

(defn add-chunk-handler
  "Add a new chunk to the project.
   POST /api/projects/:id/chunks
   Body: {:parent-id string, :summary string, :content string}"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [parent-id summary content]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      ;; No access
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      ;; Check permission to create at this location
      (not (can-create-chunk-at? role parent-id))
      {:status 403 :body {:error "Collaborators can only create chunks under aspects"}}

      :else
      ;; Load, modify, save atomically
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              ;; Push undo before modifying (only for owner)
              _ (when (= role :owner)
                  (undo/push-undo! project-id current-content))
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              new-id (trmd/generate-id chunks parent-id)
              new-chunk (trmd/make-chunk {:id new-id
                                          :summary (or summary "Nuovo chunk")
                                          :content (or content "")
                                          :parent-id parent-id
                                          :owner username})
              updated-chunks (trmd/add-chunk chunks new-chunk)
              new-content (trmd/serialize-trmd metadata updated-chunks)
              new-hash (storage/content-hash new-content)]
          (storage/save-project-content! project-id new-content)
          (update-metadata-cache! project-id new-content)
          {:status 201
           :body {:chunk new-chunk
                  :content-hash new-hash
                  :can-undo (undo/can-undo? project-id)
                  :can-redo (undo/can-redo? project-id)}})))))

(defn delete-chunk-handler
  "Delete a chunk from the project.
   DELETE /api/projects/:id/chunks/:chunk-id"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        role (db/get-user-project-role user-id project-id)]
    (cond
      ;; No access
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      :else
      ;; Load, check, modify, save atomically
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            ;; Chunk not found
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            ;; Cannot delete aspect containers
            (trmd/is-aspect-container? chunk-id)
            {:status 400 :body {:error "Cannot delete aspect containers"}}

            ;; Only owner can delete
            (not (can-delete-chunk? role chunk))
            {:status 403 :body {:error "Only project owner can delete chunks"}}

            :else
            (let [;; Push undo before modifying
                  _ (undo/push-undo! project-id current-content)
                  updated-chunks (trmd/remove-chunk chunks chunk-id)
                  new-content (trmd/serialize-trmd metadata updated-chunks)
                  new-hash (storage/content-hash new-content)]
              (storage/save-project-content! project-id new-content)
              (update-metadata-cache! project-id new-content)
              {:status 200
               :body {:success true
                      :content-hash new-hash
                      :can-undo (undo/can-undo? project-id)
                      :can-redo (undo/can-redo? project-id)}})))))))

;; =============================================================================
;; Aspect Operations Handlers
;; =============================================================================

(defn add-aspect-handler
  "Create a new aspect under an aspect container.
   POST /api/projects/:id/aspects
   Body: {:container-id string, :summary string}"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [container-id summary]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      ;; Only owner can create aspects
      (not (#{:owner :admin} role))
      {:status 403 :body {:error "Only project owner can create aspects"}}

      ;; Must be a valid aspect container
      (not (trmd/is-aspect-container? container-id))
      {:status 400 :body {:error "Invalid aspect container"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              new-id (trmd/generate-id chunks container-id)
              new-aspect (trmd/make-chunk {:id new-id
                                           :summary (or summary "Nuovo aspetto")
                                           :content ""
                                           :parent-id container-id
                                           :owner username})
              updated-chunks (trmd/add-chunk chunks new-aspect)
              new-content (trmd/serialize-trmd metadata updated-chunks)
              new-hash (storage/content-hash new-content)]
          (storage/save-project-content! project-id new-content)
          (update-metadata-cache! project-id new-content)
          {:status 201
           :body {:aspect new-aspect
                  :content-hash new-hash}})))))

(defn delete-aspect-handler
  "Delete an aspect.
   DELETE /api/projects/:id/aspects/:aspect-id"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        aspect-id (-> request :path-params :aspect-id)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      ;; Only owner can delete aspects
      (not (#{:owner :admin} role))
      {:status 403 :body {:error "Only project owner can delete aspects"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              aspect (trmd/find-chunk chunks aspect-id)]
          (cond
            (nil? aspect)
            {:status 404 :body {:error "Aspect not found"}}

            ;; Cannot delete aspect containers
            (trmd/is-aspect-container? aspect-id)
            {:status 400 :body {:error "Cannot delete aspect containers"}}

            ;; Must be an aspect (child of aspect container)
            (not (trmd/is-aspect? aspect))
            {:status 400 :body {:error "Not an aspect"}}

            :else
            (let [updated-chunks (trmd/remove-chunk chunks aspect-id)
                  new-content (trmd/serialize-trmd metadata updated-chunks)
                  new-hash (storage/content-hash new-content)]
              (storage/save-project-content! project-id new-content)
              (update-metadata-cache! project-id new-content)
              {:status 200
               :body {:success true
                      :content-hash new-hash}})))))))

(defn- can-edit-chunk?
  "Check if user can edit a chunk.
   Owner can edit everything. Collaborator can edit chunks they own."
  [role chunk username]
  (or (#{:owner :admin} role)
      (= (:owner chunk) username)))

(defn add-aspect-to-chunk-handler
  "Add an aspect reference to a chunk.
   POST /api/projects/:id/chunks/:chunk-id/aspects
   Body: {:aspect-id string}"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        {:keys [aspect-id]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)
              aspect (trmd/find-chunk chunks aspect-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            (nil? aspect)
            {:status 404 :body {:error "Aspect not found"}}

            (not (trmd/is-aspect? aspect))
            {:status 400 :body {:error "Not an aspect"}}

            (not (can-edit-chunk? role chunk username))
            {:status 403 :body {:error "Cannot edit this chunk"}}

            :else
            (let [updated-chunks (trmd/update-chunk chunks chunk-id
                                                    {:aspects (conj (or (:aspects chunk) #{}) aspect-id)})
                  new-content (trmd/serialize-trmd metadata updated-chunks)
                  new-hash (storage/content-hash new-content)]
              (storage/save-project-content! project-id new-content)
              (update-metadata-cache! project-id new-content)
              {:status 200
               :body {:success true
                      :content-hash new-hash}})))))))

(defn remove-aspect-from-chunk-handler
  "Remove an aspect reference from a chunk.
   DELETE /api/projects/:id/chunks/:chunk-id/aspects/:aspect-id"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        aspect-id (-> request :path-params :aspect-id)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            (not (can-edit-chunk? role chunk username))
            {:status 403 :body {:error "Cannot edit this chunk"}}

            :else
            (let [updated-chunks (trmd/update-chunk chunks chunk-id
                                                    {:aspects (disj (or (:aspects chunk) #{}) aspect-id)})
                  new-content (trmd/serialize-trmd metadata updated-chunks)
                  new-hash (storage/content-hash new-content)]
              (storage/save-project-content! project-id new-content)
              (update-metadata-cache! project-id new-content)
              {:status 200
               :body {:success true
                      :content-hash new-hash}})))))))

;; =============================================================================
;; Annotation Operations Handlers
;; =============================================================================

(defn- insert-annotation-at-position
  "Insert annotation marker at position in content.
   If position is :end-of-text, appends at the end.
   Otherwise, replaces selected-text at position with the annotated version."
  [content selected-text position type author priority comment]
  (let [author-part (if (and author (seq author) (not= author "local"))
                      (str "@" author)
                      "")
        priority-str (if priority (str priority) "")
        comment-str (or comment "")
        annotation (str "[!" (str/upper-case (name type)) author-part ":"
                        selected-text ":" priority-str ":" comment-str "]")]
    (cond
      ;; Append at end
      (= position :end-of-text)
      (str content "\n" annotation)

      ;; Find selected-text starting at position and wrap it
      (and (number? position) (>= position 0))
      (let [before (subs content 0 (min position (count content)))
            after (subs content (min position (count content)))
            ;; Find selected-text in after portion
            idx (str/index-of after selected-text)]
        (if (and idx (= idx 0))
          ;; Found at expected position
          (str before annotation (subs after (count selected-text)))
          ;; Not found at position - try to find anywhere and wrap
          (if-let [full-idx (str/index-of content selected-text)]
            (str (subs content 0 full-idx)
                 annotation
                 (subs content (+ full-idx (count selected-text))))
            ;; Not found at all - append at end
            (str content "\n" annotation))))

      ;; Invalid position - append at end
      :else
      (str content "\n" annotation))))

(defn- find-and-remove-annotation
  "Find and remove an annotation from content.
   Returns {:ok true :content new-content :removed annotation-text}
   or {:ok false :error msg}"
  [content annotation-id]
  ;; annotation-id format: TYPE-position (e.g., TODO-42, NOTE-100)
  (let [[type-str pos-str] (str/split annotation-id #"-" 2)
        position (when pos-str (try (Integer/parseInt pos-str) (catch Exception _ nil)))
        ;; Build regex pattern for this annotation type
        pattern-str (str "\\[!" type-str "(?:@[^:]+)?:[^:]*:[^:]*:[^\\]]*\\]")
        pattern (re-pattern pattern-str)]
    (if (nil? position)
      {:ok false :error "Invalid annotation ID format"}
      ;; Find all matches and select the one at/near position
      (let [matcher (re-matcher pattern content)
            matches (loop [ms []]
                      (if (.find matcher)
                        (recur (conj ms {:start (.start matcher)
                                         :end (.end matcher)
                                         :text (.group matcher)}))
                        ms))
            ;; Find the match that starts at or near position
            target (first (filter #(<= (Math/abs (- (:start %) position)) 5) matches))]
        (if target
          {:ok true
           :content (str (subs content 0 (:start target))
                         ;; Keep the selected text (extract from annotation)
                         (let [ann-text (:text target)
                               ;; Parse: [!TYPE(@author)?:selected:priority:comment]
                               m (re-find #"\[![^:]+:([^:]*):.*\]" ann-text)]
                           (if m (second m) ""))
                         (subs content (:end target)))
           :removed (:text target)}
          {:ok false :error "Annotation not found at position"})))))

(defn add-annotation-handler
  "Add an annotation to a chunk's content.
   POST /api/projects/:id/chunks/:chunk-id/annotations
   Body: {:type string (TODO|NOTE|FIX), :selected-text string, :position int,
          :priority string/int, :comment string}"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        {:keys [type selected-text position priority comment]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      (not (#{"TODO" "NOTE" "FIX"} (str/upper-case (or type ""))))
      {:status 400 :body {:error "Invalid annotation type. Must be TODO, NOTE, or FIX"}}

      (str/blank? selected-text)
      {:status 400 :body {:error "selected-text is required"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            (not (can-edit-chunk? role chunk username))
            {:status 403 :body {:error "Cannot edit this chunk"}}

            :else
            (let [;; Push undo before modifying (only for owner)
                  _ (when (= role :owner)
                      (undo/push-undo! project-id current-content))
                  new-content (insert-annotation-at-position
                                (:content chunk) selected-text position
                                type username priority comment)
                  updated-chunks (trmd/update-chunk chunks chunk-id {:content new-content})
                  new-file-content (trmd/serialize-trmd metadata updated-chunks)
                  new-hash (storage/content-hash new-file-content)]
              (storage/save-project-content! project-id new-file-content)
              (update-metadata-cache! project-id new-file-content)
              {:status 201
               :body {:success true
                      :chunk (trmd/find-chunk updated-chunks chunk-id)
                      :content-hash new-hash
                      :can-undo (undo/can-undo? project-id)
                      :can-redo (undo/can-redo? project-id)}})))))))

(defn delete-annotation-handler
  "Delete an annotation from a chunk's content.
   DELETE /api/projects/:id/chunks/:chunk-id/annotations/:annotation-id
   annotation-id format: TYPE-position (e.g., TODO-42)"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        annotation-id (-> request :path-params :annotation-id)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            (not (can-edit-chunk? role chunk username))
            {:status 403 :body {:error "Cannot edit this chunk"}}

            :else
            (let [result (find-and-remove-annotation (:content chunk) annotation-id)]
              (if (:ok result)
                (let [;; Push undo before modifying (only for owner)
                      _ (when (= role :owner)
                          (undo/push-undo! project-id current-content))
                      updated-chunks (trmd/update-chunk chunks chunk-id {:content (:content result)})
                      new-file-content (trmd/serialize-trmd metadata updated-chunks)
                      new-hash (storage/content-hash new-file-content)]
                  (storage/save-project-content! project-id new-file-content)
                  (update-metadata-cache! project-id new-file-content)
                  {:status 200
                   :body {:success true
                          :chunk (trmd/find-chunk updated-chunks chunk-id)
                          :content-hash new-hash
                          :can-undo (undo/can-undo? project-id)
                          :can-redo (undo/can-redo? project-id)}})
                {:status 404 :body {:error (:error result)}}))))))))

;; =============================================================================
;; Proposal Operations Handlers
;; =============================================================================

(defn- decode-proposal-data
  "Decode proposal data from Base64. Returns {:text string :sel int} or nil."
  [b64-str]
  (try
    (let [decoded-bytes (.decode (java.util.Base64/getDecoder) b64-str)
          decoded-str (java.net.URLDecoder/decode (String. decoded-bytes "UTF-8") "UTF-8")]
      (read-string decoded-str))
    (catch Exception _ nil)))

(defn- insert-proposal
  "Insert a PROPOSAL annotation into content.
   Format: [!PROPOSAL{:text \"original\" :proposed \"proposed\" :from \"user\" :sel 0}]"
  [content original-text proposed-text position _author user]
  (let [;; Use EDN format that matches find-proposal-at-position
        proposal-data {:text original-text
                       :proposed proposed-text
                       :from user
                       :sel 0}
        proposal-marker (str "[!PROPOSAL" (pr-str proposal-data) "]")]
    (cond
      ;; Find original-text at position and wrap it
      (and (number? position) (>= position 0))
      (let [before (subs content 0 (min position (count content)))
            after (subs content (min position (count content)))
            idx (str/index-of after original-text)]
        (if (and idx (= idx 0))
          (str before proposal-marker (subs after (count original-text)))
          ;; Not at exact position - try to find anywhere
          (if-let [full-idx (str/index-of content original-text)]
            (str (subs content 0 full-idx)
                 proposal-marker
                 (subs content (+ full-idx (count original-text))))
            ;; Not found - append at end
            (str content "\n" proposal-marker))))

      ;; No position - find first occurrence
      :else
      (if-let [idx (str/index-of content original-text)]
        (str (subs content 0 idx)
             proposal-marker
             (subs content (+ idx (count original-text))))
        (str content "\n" proposal-marker)))))

(defn- find-balanced-brace
  "Find the closing brace for EDN starting at pos (which should point to {).
   Returns the index of the closing }, or nil if not found."
  [s pos]
  (when (and s (< pos (count s)) (= (nth s pos) \{))
    (loop [i (inc pos)
           depth 1
           in-string false
           escape false]
      (if (>= i (count s))
        nil
        (let [c (nth s i)]
          (cond
            escape (recur (inc i) depth in-string false)
            (= c \\) (recur (inc i) depth in-string true)
            (= c \") (recur (inc i) depth (not in-string) false)
            in-string (recur (inc i) depth in-string false)
            (= c \{) (recur (inc i) (inc depth) in-string false)
            (= c \}) (if (= depth 1) i (recur (inc i) (dec depth) in-string false))
            :else (recur (inc i) depth in-string false)))))))

(defn- find-proposal-at-position
  "Find a PROPOSAL annotation at or near position.
   Returns {:start int :end int :original string :proposed string :user string} or nil.

   Supports EDN format: [!PROPOSAL{:text \"...\" :proposed \"...\" :from \"...\" :sel N}]"
  [content position]
  ;; Find EDN format proposals
  (let [pattern #"\[!PROPOSAL\{"
        matcher (re-matcher pattern content)]
    (loop [matches []]
      (if (.find matcher)
        (let [start (.start matcher)
              brace-start (+ start 10)  ;; "[!PROPOSAL{" is 11 chars, brace at index 10
              brace-end (find-balanced-brace content brace-start)]
          (if (and brace-end
                   (< (inc brace-end) (count content))
                   (= (nth content (inc brace-end)) \]))
            (let [end-pos (+ brace-end 2)
                  edn-str (subs content brace-start (inc brace-end))
                  data (try (edn/read-string edn-str) (catch Exception _ nil))]
              (if (map? data)
                (recur (conj matches {:start start
                                      :end end-pos
                                      :original (:text data)
                                      :proposed (:proposed data)
                                      :user (:from data)
                                      :sel (or (:sel data) 0)
                                      :data data}))
                (recur matches)))
            (recur matches)))
        ;; Find match near position (within 5 chars tolerance)
        (first (filter #(<= (Math/abs (- (:start %) position)) 5) matches))))))

(defn- accept-proposal-in-content
  "Accept a proposal: replace the annotation with the selected text.
   If sel=0, uses original. If sel=1, uses proposed.
   Returns {:ok true :content new-content :proposal-data {...}} or {:ok false :error msg}."
  [content position]
  (println "=== accept-proposal-in-content ===")
  (println "position:" position)
  (println "content length:" (count content))
  (println "content preview:" (subs content 0 (min 100 (count content))))
  (let [proposal (find-proposal-at-position content position)]
    (println "found proposal:" proposal)
    (if proposal
      (let [sel (or (:sel proposal) 0)
            ;; Use proposed text if sel=1, otherwise original
            replacement (if (pos? sel)
                          (:proposed proposal)
                          (:original proposal))]
        {:ok true
         :content (str (subs content 0 (:start proposal))
                       replacement
                       (subs content (:end proposal)))
         :proposal-data {:original-text (:original proposal)
                         :proposed-text (:proposed proposal)
                         :author (:user proposal)}})
      {:ok false :error "Proposal not found at position"})))

(defn- reject-proposal-in-content
  "Reject a proposal: replace the annotation with the original text.
   Returns {:ok true :content new-content :proposal-data {...}} or {:ok false :error msg}."
  [content position]
  (if-let [proposal (find-proposal-at-position content position)]
    {:ok true
     :content (str (subs content 0 (:start proposal))
                   (:original proposal)
                   (subs content (:end proposal)))
     :proposal-data {:original-text (:original proposal)
                     :proposed-text (:proposed proposal)
                     :author (:user proposal)}}
    {:ok false :error "Proposal not found at position"}))

(defn- add-proposal-to-discussion
  "Add a resolved proposal entry to chunk's discussion.
   answer should be :accepted or :rejected"
  [chunk proposal-data answer decided-by]
  (let [entry {:author (:author proposal-data)
               :timestamp (.toString (java.time.Instant/now))
               :type "proposal"
               :previous-text (:original-text proposal-data)
               :proposed-text (:proposed-text proposal-data)
               :answer (name answer)
               :decided-by decided-by}
        discussion (or (:discussion chunk) [])]
    (conj discussion entry)))

(defn create-proposal-handler
  "Create a proposal annotation in a chunk's content.
   POST /api/projects/:id/chunks/:chunk-id/proposals
   Body: {:original-text string, :proposed-text string, :position int}"
  [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        {:keys [original-text proposed-text position]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      (str/blank? original-text)
      {:status 400 :body {:error "original-text is required"}}

      (str/blank? proposed-text)
      {:status 400 :body {:error "proposed-text is required"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            ;; Anyone with access can create proposals
            :else
            (let [;; Push undo before modifying (only for owner)
                  _ (when (= role :owner)
                      (undo/push-undo! project-id current-content))
                  new-content (insert-proposal (:content chunk) original-text
                                               proposed-text position username username)
                  updated-chunks (trmd/update-chunk chunks chunk-id {:content new-content})
                  new-file-content (trmd/serialize-trmd metadata updated-chunks)
                  new-hash (storage/content-hash new-file-content)]
              (storage/save-project-content! project-id new-file-content)
              (update-metadata-cache! project-id new-file-content)
              {:status 201
               :body {:success true
                      :chunk (trmd/find-chunk updated-chunks chunk-id)
                      :content-hash new-hash
                      :can-undo (undo/can-undo? project-id)
                      :can-redo (undo/can-redo? project-id)}})))))))

(defn accept-proposal-handler
  "Accept a proposal: replace annotation with proposed text.
   POST /api/projects/:id/chunks/:chunk-id/proposals/accept
   Body: {:position int}"
  [request]
  (try
    (println "=== accept-proposal-handler ===")
    (println "path-params:" (:path-params request))
    (println "body-params:" (:body-params request))
    (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        {:keys [position]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            (not (can-edit-chunk? role chunk username))
            {:status 403 :body {:error "Cannot edit this chunk"}}

            :else
            (let [result (accept-proposal-in-content (:content chunk) position)]
              (if (:ok result)
                (let [;; Push undo before modifying (only for owner)
                      _ (when (= role :owner)
                          (undo/push-undo! project-id current-content))
                      ;; Add proposal resolution to discussion
                      new-discussion (add-proposal-to-discussion chunk (:proposal-data result) :accepted username)
                      ;; Update chunk with new content and discussion
                      updated-chunks (trmd/update-chunk chunks chunk-id
                                                        {:content (:content result)
                                                         :discussion new-discussion})
                      new-file-content (trmd/serialize-trmd metadata updated-chunks)
                      new-hash (storage/content-hash new-file-content)]
                  (storage/save-project-content! project-id new-file-content)
                  (update-metadata-cache! project-id new-file-content)
                  {:status 200
                   :body {:success true
                          :chunk (trmd/find-chunk updated-chunks chunk-id)
                          :content-hash new-hash
                          :can-undo (undo/can-undo? project-id)
                          :can-redo (undo/can-redo? project-id)}})
                {:status 404 :body {:error (:error result)}})))))))
    (catch Exception e
      (println "ERROR in accept-proposal-handler:" (.getMessage e))
      (.printStackTrace e)
      {:status 500 :body {:error (str "Server error: " (.getMessage e))}})))

(defn reject-proposal-handler
  "Reject a proposal: replace annotation with original text.
   POST /api/projects/:id/chunks/:chunk-id/proposals/reject
   Body: {:position int}"
  [request]
  (try
    (println "=== reject-proposal-handler ===")
    (println "path-params:" (:path-params request))
    (println "body-params:" (:body-params request))
    (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        chunk-id (-> request :path-params :chunk-id)
        {:keys [position]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              {:keys [metadata chunks]} (trmd/parse-trmd (or current-content ""))
              chunk (trmd/find-chunk chunks chunk-id)]
          (cond
            (nil? chunk)
            {:status 404 :body {:error "Chunk not found"}}

            (not (can-edit-chunk? role chunk username))
            {:status 403 :body {:error "Cannot edit this chunk"}}

            :else
            (let [result (reject-proposal-in-content (:content chunk) position)]
              (if (:ok result)
                (let [;; Push undo before modifying (only for owner)
                      _ (when (= role :owner)
                          (undo/push-undo! project-id current-content))
                      ;; Add proposal resolution to discussion
                      new-discussion (add-proposal-to-discussion chunk (:proposal-data result) :rejected username)
                      ;; Update chunk with new content and discussion
                      updated-chunks (trmd/update-chunk chunks chunk-id
                                                        {:content (:content result)
                                                         :discussion new-discussion})
                      new-file-content (trmd/serialize-trmd metadata updated-chunks)
                      new-hash (storage/content-hash new-file-content)]
                  (storage/save-project-content! project-id new-file-content)
                  (update-metadata-cache! project-id new-file-content)
                  {:status 200
                   :body {:success true
                          :chunk (trmd/find-chunk updated-chunks chunk-id)
                          :content-hash new-hash
                          :can-undo (undo/can-undo? project-id)
                          :can-redo (undo/can-redo? project-id)}})
                {:status 404 :body {:error (:error result)}})))))))
    (catch Exception e
      (println "ERROR in reject-proposal-handler:" (.getMessage e))
      (.printStackTrace e)
      {:status 500 :body {:error (str "Server error: " (.getMessage e))}})))

;; =============================================================================
;; Undo/Redo Handlers
;; =============================================================================

(defn undo-handler
  "Undo the last operation on a project.
   POST /api/projects/:id/undo"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      (not= role :owner)
      {:status 403 :body {:error "Only owner can undo"}}

      (not (undo/can-undo? project-id))
      {:status 400 :body {:error "Nothing to undo"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              previous-content (undo/pop-undo! project-id current-content)]
          (if previous-content
            (do
              (storage/save-project-content! project-id previous-content)
              (update-metadata-cache! project-id previous-content)
              (let [new-hash (storage/content-hash previous-content)
                    ;; Parse chunks to find what changed
                    current-parsed (trmd/parse-trmd current-content)
                    previous-parsed (trmd/parse-trmd previous-content)
                    changes (trmd/find-changed-chunks
                              (:chunks current-parsed)
                              (:chunks previous-parsed))]
                {:status 200
                 :body {:success true
                        :content previous-content
                        :content-hash new-hash
                        :can-undo (undo/can-undo? project-id)
                        :can-redo (undo/can-redo? project-id)
                        :changes changes}}))
            {:status 400 :body {:error "Undo failed"}}))))))

(defn redo-handler
  "Redo the last undone operation on a project.
   POST /api/projects/:id/redo"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      (not= role :owner)
      {:status 403 :body {:error "Only owner can redo"}}

      (not (undo/can-redo? project-id))
      {:status 400 :body {:error "Nothing to redo"}}

      :else
      (locking (storage/get-project-lock project-id)
        (let [current-content (storage/load-project-content project-id)
              next-content (undo/pop-redo! project-id current-content)]
          (if next-content
            (do
              (storage/save-project-content! project-id next-content)
              (update-metadata-cache! project-id next-content)
              (let [new-hash (storage/content-hash next-content)
                    ;; Parse chunks to find what changed
                    current-parsed (trmd/parse-trmd current-content)
                    next-parsed (trmd/parse-trmd next-content)
                    changes (trmd/find-changed-chunks
                              (:chunks current-parsed)
                              (:chunks next-parsed))]
                {:status 200
                 :body {:success true
                        :content next-content
                        :content-hash new-hash
                        :can-undo (undo/can-undo? project-id)
                        :can-redo (undo/can-redo? project-id)
                        :changes changes}}))
            {:status 400 :body {:error "Redo failed"}}))))))

(defn undo-status-handler
  "Get undo/redo status for a project.
   GET /api/projects/:id/undo-status"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        role (db/get-user-project-role user-id project-id)]
    (if (nil? role)
      {:status 403 :body {:error "Access denied"}}
      {:status 200
       :body {:can-undo (and (= role :owner) (undo/can-undo? project-id))
              :can-redo (and (= role :owner) (undo/can-redo? project-id))
              :undo-count (undo/get-undo-count project-id)
              :redo-count (undo/get-redo-count project-id)}})))

;; =============================================================================
;; Versioning Handlers
;; =============================================================================

(defn list-versions-handler
  "List all versions (commits) for a project.
   GET /api/projects/:id/versions"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        role (db/get-user-project-role user-id project-id)]
    (if (nil? role)
      {:status 403 :body {:error "Access denied"}}
      (let [versions (versioning/list-versions project-id)]
        {:status 200
         :body {:versions (or versions [])}}))))

(defn get-version-handler
  "Get content of a specific version.
   GET /api/projects/:id/versions/:ref"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        ref (-> request :path-params :ref)
        role (db/get-user-project-role user-id project-id)]
    (if (nil? role)
      {:status 403 :body {:error "Access denied"}}
      (if-let [content (versioning/get-version-content project-id ref)]
        {:status 200
         :body {:content content
                :ref ref}}
        {:status 404 :body {:error "Version not found"}}))))

(defn create-version-tag-handler
  "Create a tagged version (named snapshot).
   POST /api/projects/:id/versions
   Body: {:tag-name string :message string (optional)}"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [tag-name message]} (:body-params request)
        role (db/get-user-project-role user-id project-id)]
    (cond
      (not= role :owner)
      {:status 403 :body {:error "Only owner can create versions"}}

      (or (nil? tag-name) (str/blank? tag-name))
      {:status 400 :body {:error "Tag name required"}}

      ;; Validate tag name (no spaces, special chars)
      (not (re-matches #"^[a-zA-Z0-9_.-]+$" tag-name))
      {:status 400 :body {:error "Invalid tag name. Use only letters, numbers, underscore, dot, dash."}}

      :else
      (let [result (versioning/create-tagged-version! project-id tag-name message)]
        (if (:ok result)
          {:status 201 :body {:success true :tag tag-name}}
          {:status 500 :body {:error (or (:error result) "Failed to create version")}})))))

(defn fork-version-handler
  "Create a new project from a specific version.
   POST /api/projects/:id/versions/:ref/fork
   Body: {:name string (optional, defaults to 'Copy of ...')}"
  [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        ref (-> request :path-params :ref)
        {:keys [name]} (:body-params request)
        role (db/get-user-project-role user-id project-id)
        original-project (db/find-project-by-id project-id)
        user-quotas (db/get-user-quotas user-id)
        user-projects (db/find-projects-for-user user-id)]
    (cond
      (nil? role)
      {:status 403 :body {:error "Access denied"}}

      ;; Check project quota
      (>= (count user-projects) (:max_projects user-quotas))
      {:status 403 :body {:error (str "Limite progetti raggiunto (" (:max_projects user-quotas) ")")}}

      :else
      (if-let [content (versioning/get-version-content project-id ref)]
        (let [new-name (or name (str "Copia di " (:name original-project)))
              ;; Create new project with the versioned content
              new-project (db/create-project! {:name new-name :owner-id user-id})
              new-project-id (:id new-project)]
          ;; Save content to new project
          (storage/save-project-content! new-project-id content)
          ;; Initialize git repo for new project
          (versioning/init-repo! new-project-id content)
          {:status 201
           :body {:success true
                  :project new-project}})
        {:status 404 :body {:error "Version not found"}}))))

;; =============================================================================
;; Collaborator Handlers
;; =============================================================================

(defn list-collaborators-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      (let [collaborators (db/get-project-collaborators project-id)
            project (db/find-project-by-id project-id)
            owner (db/find-user-by-id (:owner_id project))]
        {:status 200
         :body {:owner {:id (:id owner)
                        :username (:username owner)
                        :display_name (:display_name owner)}
                :collaborators collaborators}}))))

(defn add-collaborator-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [username role]} (:body-params request)
        project (db/find-project-by-id project-id)
        owner-quotas (db/get-user-quotas (:owner_id project))
        current-collaborators (db/get-project-collaborators project-id)]
    (cond
      (not (db/user-is-project-admin? user-id project-id))
      {:status 403 :body {:error "Admin access required"}}

      (not (#{"admin" "collaborator"} role))
      {:status 400 :body {:error "Invalid role"}}

      ;; Check collaborators quota (only for new collaborators)
      (and (not (some #(= username (:username %)) current-collaborators))
           (>= (count current-collaborators) (:max_collaborators owner-quotas)))
      {:status 403 :body {:error (str "Limite collaboratori raggiunto (" (:max_collaborators owner-quotas) ")")}}

      :else
      (if-let [target-user (db/find-user-by-username username)]
        (do
          (db/add-collaborator! project-id (:id target-user) role)
          {:status 200 :body {:success true}})
        {:status 404 :body {:error "User not found"}}))))

(defn remove-collaborator-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        target-user-id (-> request :path-params :user-id Integer/parseInt)]
    (if-not (db/user-is-project-admin? user-id project-id)
      {:status 403 :body {:error "Admin access required"}}
      (do
        (db/remove-collaborator! project-id target-user-id)
        {:status 200 :body {:success true}}))))

(defn update-collaborator-role-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        target-user-id (-> request :path-params :user-id Integer/parseInt)
        {:keys [role]} (:body-params request)]
    (cond
      (not (db/user-is-project-admin? user-id project-id))
      {:status 403 :body {:error "Admin access required"}}

      (not (#{"admin" "collaborator"} role))
      {:status 400 :body {:error "Invalid role"}}

      :else
      (do
        (db/add-collaborator! project-id target-user-id role)
        {:status 200 :body {:success true}}))))

;; =============================================================================
;; Chat Handlers
;; =============================================================================

(defn get-chat-handler [request]
  (let [user-id (get-in request [:user :id])
        project-id (-> request :path-params :id Integer/parseInt)
        ;; Query params may come as string keys depending on middleware
        query-params (:query-params request)
        after-str (or (get query-params :after) (get query-params "after"))
        after-id (when after-str (Integer/parseInt after-str))]
    (if-not (db/user-can-access-project? user-id project-id)
      {:status 403 :body {:error "Access denied"}}
      {:status 200
       :body {:messages (db/get-chat-messages project-id after-id)}})))

(defn post-chat-handler [request]
  (let [user-id (get-in request [:user :id])
        username (get-in request [:user :username])
        project-id (-> request :path-params :id Integer/parseInt)
        {:keys [message]} (:body-params request)]
    (cond
      (not (db/user-can-access-project? user-id project-id))
      {:status 403 :body {:error "Access denied"}}

      (or (nil? message) (empty? (str/trim message)))
      {:status 400 :body {:error "Message cannot be empty"}}

      :else
      (let [msg (db/add-chat-message! project-id user-id username message)]
        {:status 201 :body {:message msg}}))))

;; =============================================================================
;; Users Handlers (for authenticated users)
;; =============================================================================

(defn list-users-basic-handler
  "List all active users with basic info (id, username, display_name).
   Available to all authenticated users for adding collaborators."
  [_request]
  (let [users (->> (db/list-all-users)
                   (filter #(= "active" (:status %)))
                   (map #(select-keys % [:id :username :display_name])))]
    {:status 200
     :body {:users users}}))

;; =============================================================================
;; Admin Handlers
;; =============================================================================

(defn list-users-handler [_request]
  (let [users (db/list-all-users)
        pending-count (db/count-pending-users)]
    {:status 200
     :body {:users users
            :pending_count pending-count}}))

(defn create-user-handler [request]
  (let [{:keys [username password is-super-admin]} (:body-params request)]
    (cond
      (< (count username) 3)
      {:status 400 :body {:error "Username must be at least 3 characters"}}

      (< (count password) 6)
      {:status 400 :body {:error "Password must be at least 6 characters"}}

      (db/find-user-by-username username)
      {:status 400 :body {:error "Username already exists"}}

      :else
      (let [password-hash (auth/hash-password password)
            ;; Users created by admin are active by default
            user (db/create-user! {:username username
                                   :password-hash password-hash
                                   :is-super-admin is-super-admin
                                   :status "active"})]
        {:status 201
         :body {:user (dissoc user :password_hash)}}))))

(defn delete-user-handler [request]
  (let [current-user-id (get-in request [:user :id])
        target-user-id (-> request :path-params :id Integer/parseInt)]
    (cond
      ;; Cannot delete yourself
      (= current-user-id target-user-id)
      {:status 400 :body {:error "Cannot delete yourself"}}

      ;; User doesn't exist
      (nil? (db/find-user-by-id target-user-id))
      {:status 404 :body {:error "User not found"}}

      :else
      (do
        (db/delete-user! target-user-id)
        {:status 200 :body {:success true}}))))

(defn update-user-admin-handler [request]
  (let [current-user-id (get-in request [:user :id])
        target-user-id (-> request :path-params :id Integer/parseInt)
        params (:body-params request)
        {:keys [is-super-admin display_name email status max_projects
                max_project_size_mb max_collaborators notes]} params]
    (cond
      ;; User doesn't exist
      (nil? (db/find-user-by-id target-user-id))
      {:status 404 :body {:error "User not found"}}

      ;; Cannot change your own admin status
      (and (some? is-super-admin) (= current-user-id target-user-id))
      {:status 400 :body {:error "Cannot change your own admin status"}}

      :else
      (do
        ;; Update admin status if provided
        (when (some? is-super-admin)
          (db/update-user-super-admin! target-user-id is-super-admin))
        ;; Update other fields
        (db/update-user! target-user-id
          {:display_name display_name
           :email email
           :status status
           :max_projects max_projects
           :max_project_size_mb max_project_size_mb
           :max_collaborators max_collaborators
           :notes notes})
        {:status 200 :body {:success true :user (db/find-user-by-id target-user-id)}}))))

;; =============================================================================
;; Profile Handlers
;; =============================================================================

(defn get-profile-handler [request]
  (let [user-id (get-in request [:user :id])
        user (db/find-user-by-id user-id)
        quotas (db/get-user-quotas user-id)]
    {:status 200
     :body {:user (dissoc user :password_hash)
            :quotas quotas}}))

(defn update-profile-handler [request]
  (let [user-id (get-in request [:user :id])
        {:keys [display_name email]} (:body-params request)
        updated-user (db/update-own-profile! user-id {:display_name display_name :email email})]
    {:status 200
     :body {:user (dissoc updated-user :password_hash)}}))

(defn change-password-handler
  "Handler for user to change their own password"
  [request]
  (let [user-id (get-in request [:user :id])
        {:keys [old_password new_password]} (:body-params request)
        user (db/find-user-by-id user-id)]
    (cond
      (< (count new_password) 6)
      {:status 400 :body {:error "La nuova password deve avere almeno 6 caratteri"}}

      (not (auth/check-password old_password (:password_hash user)))
      {:status 400 :body {:error "Password attuale non corretta"}}

      :else
      (do
        (db/update-user-password! user-id (auth/hash-password new_password))
        {:status 200 :body {:success true}}))))

(defn admin-reset-password-handler
  "Handler for admin to reset a user's password"
  [request]
  (let [target-user-id (-> request :path-params :id Integer/parseInt)
        {:keys [new_password]} (:body-params request)]
    (cond
      (nil? (db/find-user-by-id target-user-id))
      {:status 404 :body {:error "User not found"}}

      (< (count new_password) 6)
      {:status 400 :body {:error "La password deve avere almeno 6 caratteri"}}

      :else
      (do
        (db/update-user-password! target-user-id (auth/hash-password new_password))
        {:status 200 :body {:success true}}))))

;; =============================================================================
;; Presence Handlers
;; =============================================================================

(defn notify-editing-handler
  "Handler for notifying that user is editing a chunk."
  [request]
  (let [project-id (-> request :path-params :id)
        chunk-id (-> request :body-params :chunk_id)
        username (-> request :user :username)]
    (if chunk-id
      (do
        (presence/notify-editing! project-id chunk-id username)
        {:status 200 :body {:success true}})
      {:status 400 :body {:error "chunk_id required"}})))

(defn notify-stopped-editing-handler
  "Handler for notifying that user stopped editing a chunk."
  [request]
  (let [project-id (-> request :path-params :id)
        chunk-id (-> request :body-params :chunk_id)
        username (-> request :user :username)]
    (if chunk-id
      (do
        (presence/notify-stopped-editing! project-id chunk-id username)
        {:status 200 :body {:success true}})
      {:status 400 :body {:error "chunk_id required"}})))

(defn get-presence-handler
  "Handler for getting presence info for a project."
  [request]
  (let [project-id (-> request :path-params :id)
        username (-> request :user :username)
        ;; Exclude self from the response
        editing (presence/get-project-presence-excluding project-id username)]
    {:status 200 :body {:editing editing}}))

;; =============================================================================
;; Router
;; =============================================================================

(def app-routes
  [["/api"
    ["/register" {:post {:handler register-handler}}]
    ["/login" {:post {:handler login-handler}}]
    ["/me" {:get {:handler me-handler
                  :middleware [auth/require-auth]}}]

    ["/projects"
     ["" {:get {:handler list-projects-handler
                :middleware [auth/require-auth]}
          :post {:handler create-project-handler
                 :middleware [auth/require-auth]}}]
     ["/:id"
      ["" {:get {:handler get-project-handler
                 :middleware [auth/require-auth]}
           :put {:handler update-project-handler
                 :middleware [auth/require-auth]}
           :delete {:handler delete-project-handler
                    :middleware [auth/require-auth]}}]
      ["/restore" {:post {:handler restore-project-handler
                          :middleware [auth/require-auth]}}]
      ["/permanent" {:delete {:handler permanent-delete-handler
                              :middleware [auth/require-auth]}}]
      ["/hash" {:get {:handler get-project-hash-handler
                      :middleware [auth/require-auth]}}]
      ["/collaborators"
       ["" {:get {:handler list-collaborators-handler
                  :middleware [auth/require-auth]}
            :post {:handler add-collaborator-handler
                   :middleware [auth/require-auth]}}]
       ["/:user-id" {:delete {:handler remove-collaborator-handler
                              :middleware [auth/require-auth]}
                     :put {:handler update-collaborator-role-handler
                           :middleware [auth/require-auth]}}]]
      ["/chat" {:get {:handler get-chat-handler
                      :middleware [auth/require-auth]}
                :post {:handler post-chat-handler
                       :middleware [auth/require-auth]}}]
      ["/chunks"
       ["" {:post {:handler add-chunk-handler
                   :middleware [auth/require-auth]}}]
       ["/:chunk-id"
        ["" {:delete {:handler delete-chunk-handler
                      :middleware [auth/require-auth]}}]
        ["/aspects"
         ["" {:post {:handler add-aspect-to-chunk-handler
                     :middleware [auth/require-auth]}}]
         ["/:aspect-id" {:delete {:handler remove-aspect-from-chunk-handler
                                  :middleware [auth/require-auth]}}]]
        ["/annotations"
         ["" {:post {:handler add-annotation-handler
                     :middleware [auth/require-auth]}}]
         ["/:annotation-id" {:delete {:handler delete-annotation-handler
                                      :middleware [auth/require-auth]}}]]
        ["/proposals"
         ["" {:post {:handler create-proposal-handler
                     :middleware [auth/require-auth]}}]
         ["/accept" {:post {:handler accept-proposal-handler
                            :middleware [auth/require-auth]}}]
         ["/reject" {:post {:handler reject-proposal-handler
                            :middleware [auth/require-auth]}}]]]]
      ["/aspects"
       ["" {:post {:handler add-aspect-handler
                   :middleware [auth/require-auth]}}]
       ["/:aspect-id" {:delete {:handler delete-aspect-handler
                                :middleware [auth/require-auth]}}]]
      ["/undo" {:post {:handler undo-handler
                       :middleware [auth/require-auth]}}]
      ["/redo" {:post {:handler redo-handler
                       :middleware [auth/require-auth]}}]
      ["/undo-status" {:get {:handler undo-status-handler
                             :middleware [auth/require-auth]}}]
      ["/versions"
       ["" {:get {:handler list-versions-handler
                  :middleware [auth/require-auth]}
            :post {:handler create-version-tag-handler
                   :middleware [auth/require-auth]}}]
       ["/:ref" {:get {:handler get-version-handler
                       :middleware [auth/require-auth]}}]
       ["/:ref/fork" {:post {:handler fork-version-handler
                             :middleware [auth/require-auth]}}]]
      ["/presence"
       ["" {:get {:handler get-presence-handler
                  :middleware [auth/require-auth]}}]
       ["/editing" {:post {:handler notify-editing-handler
                           :middleware [auth/require-auth]}}]
       ["/stopped" {:post {:handler notify-stopped-editing-handler
                           :middleware [auth/require-auth]}}]]]]

    ;; Trash endpoint (separate to avoid conflict with /:id)
    ["/projects-trash" {:get {:handler list-trash-handler
                              :middleware [auth/require-auth]}}]

    ["/profile" {:get {:handler get-profile-handler
                       :middleware [auth/require-auth]}
                  :put {:handler update-profile-handler
                        :middleware [auth/require-auth]}}]
    ["/profile/password" {:put {:handler change-password-handler
                                :middleware [auth/require-auth]}}]

    ;; Public users list (basic info only, for adding collaborators)
    ["/users" {:get {:handler list-users-basic-handler
                     :middleware [auth/require-auth]}}]

    ["/admin/users"
     ["" {:get {:handler list-users-handler
                :middleware [auth/require-auth auth/require-super-admin]}
          :post {:handler create-user-handler
                 :middleware [auth/require-auth auth/require-super-admin]}}]
     ["/:id" {:delete {:handler delete-user-handler
                       :middleware [auth/require-auth auth/require-super-admin]}
              :put {:handler update-user-admin-handler
                    :middleware [auth/require-auth auth/require-super-admin]}}]
     ["/:id/password" {:put {:handler admin-reset-password-handler
                             :middleware [auth/require-auth auth/require-super-admin]}}]]
    ["/admin/pending-count" {:get {:handler (fn [_] {:status 200 :body {:count (db/count-pending-users)}})
                                   :middleware [auth/require-auth auth/require-super-admin]}}]]])

(def router
  (ring/router
    app-routes
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}}))
