(ns tramando.model
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.settings :as settings]
            ["js-yaml" :as yaml]))

;; =============================================================================
;; Chunk Model
;; =============================================================================
;; A Chunk is: {:id           string
;;              :summary      string
;;              :content      string (markdown)
;;              :parent-id    string or nil
;;              :aspects      #{set of chunk ids}
;;              :ordered-refs [{:ref "id"} or {:ref "id" :when "timestamp"}]}

(def aspect-containers
  "Fixed root containers for aspects"
  [{:id "personaggi" :summary "Personaggi"}
   {:id "luoghi" :summary "Luoghi"}
   {:id "temi" :summary "Temi"}
   {:id "sequenze" :summary "Sequenze"}
   {:id "timeline" :summary "Timeline"}])

(def aspect-container-ids
  (set (map :id aspect-containers)))

;; Forward declaration for functions used before definition
(declare ensure-aspect-containers!)
(declare load-file-content!)

(defn make-chunk
  "Create a new chunk with the given properties"
  [{:keys [id summary content parent-id aspects ordered-refs]
    :or {summary "" content "" parent-id nil aspects #{} ordered-refs []}}]
  {:id id
   :summary summary
   :content content
   :parent-id parent-id
   :aspects (set aspects)
   :ordered-refs (vec ordered-refs)})

(defn- id-prefix-for-parent
  "Get the ID prefix based on parent-id"
  [parent-id]
  (case parent-id
    "personaggi" "pers"
    "luoghi" "luogo"
    "temi" "tema"
    "sequenze" "seq"
    "timeline" "time"
    nil "cap"
    ;; For other parents (children of chapters), use "scene"
    "scene"))

(defn- extract-number
  "Extract the number from an ID like 'cap-12' -> 12"
  [id prefix]
  (when id
    (let [pattern (re-pattern (str "^" prefix "-(\\d+)$"))]
      (when-let [[_ num] (re-matches pattern id)]
        (js/parseInt num 10)))))

(defn- next-number-for-prefix
  "Find the next available number for a given prefix"
  [prefix chunks]
  (let [existing-numbers (->> chunks
                              (map :id)
                              (keep #(extract-number % prefix))
                              set)]
    (loop [n 1]
      (if (contains? existing-numbers n)
        (recur (inc n))
        n))))

(defn generate-id
  "Generate a readable id based on parent category (e.g., cap-1, pers-3, scene-12)"
  ([]
   (generate-id nil))
  ([parent-id]
   (generate-id parent-id []))
  ([parent-id existing-chunks]
   (let [prefix (id-prefix-for-parent parent-id)
         n (next-number-for-prefix prefix existing-chunks)]
     (str prefix "-" n))))

(defn new-chunk
  "Create a new chunk with auto-generated id"
  [& {:keys [summary content parent-id aspects ordered-refs existing-chunks]
      :or {summary "Nuovo chunk" content "" parent-id nil aspects #{} ordered-refs [] existing-chunks []}}]
  (make-chunk {:id (generate-id parent-id existing-chunks)
               :summary summary
               :content content
               :parent-id parent-id
               :aspects aspects
               :ordered-refs ordered-refs}))

;; =============================================================================
;; Parsing
;; =============================================================================
;; File format (.trmd):
;; ---
;; title: "Book Title"
;; author: "Author Name"
;; ... (YAML frontmatter)
;; ---
;; [C:id"summary"][@aspect1][@aspect2]
;; markdown content...
;;
;; Indentation determines parent/child hierarchy

(def ^:private chunk-header-re
  #"^\[C:([^\]\"]+)\"([^\"]*)\"\](.*)$")

(def ^:private aspect-re
  #"\[@([^\]]+)\]")

(defn- count-indent
  "Count leading spaces (2 spaces = 1 level)"
  [line]
  (let [spaces (count (re-find #"^  *" line))]
    (quot spaces 2)))

(defn- parse-header
  "Parse a chunk header line, returns {:id :summary :aspects} or nil"
  [line]
  (when-let [[_ id summary rest] (re-matches chunk-header-re (str/trim line))]
    (let [aspects (->> (re-seq aspect-re rest)
                       (map second)
                       set)]
      {:id id
       :summary summary
       :aspects aspects})))

(defn- parse-yaml-frontmatter
  "Extract YAML frontmatter from text. Returns {:metadata map :content remaining-text}"
  [text]
  (if (str/starts-with? (str/trim text) "---")
    ;; Has frontmatter
    (let [trimmed (str/trim text)
          ;; Find the closing ---
          after-first (subs trimmed 3) ;; Skip first ---
          end-idx (str/index-of after-first "\n---")]
      (if end-idx
        (let [yaml-content (str/trim (subs after-first 0 end-idx))
              remaining (str/trim (subs after-first (+ end-idx 4))) ;; Skip \n---
              parsed (try
                       (js->clj (.load yaml yaml-content) :keywordize-keys true)
                       (catch :default e
                         (js/console.warn "Failed to parse YAML:" e)
                         {}))]
          {:metadata (merge default-metadata parsed)
           :content remaining})
        ;; No closing ---, treat as no frontmatter
        {:metadata default-metadata
         :content text}))
    ;; No frontmatter
    {:metadata default-metadata
     :content text}))

(defn- parse-chunks
  "Parse chunks from content (without frontmatter)"
  [text]
  (let [lines (str/split-lines text)]
    (loop [lines lines
           chunks []
           current-chunk nil
           content-lines []
           indent-stack []]  ; stack of {:id :indent}
      (if (empty? lines)
        ;; Finalize last chunk
        (if current-chunk
          (conj chunks (assoc current-chunk :content (str/trim (str/join "\n" content-lines))))
          chunks)
        (let [line (first lines)
              indent (count-indent line)
              header (parse-header line)]
          (if header
            ;; New chunk header found
            (let [;; Save previous chunk if any
                  chunks (if current-chunk
                           (conj chunks (assoc current-chunk :content (str/trim (str/join "\n" content-lines))))
                           chunks)
                  ;; Find parent based on indentation
                  parent-stack (take-while #(< (:indent %) indent) indent-stack)
                  parent-id (when (seq parent-stack)
                              (:id (last parent-stack)))
                  new-stack (conj (vec parent-stack) {:id (:id header) :indent indent})]
              (recur (rest lines)
                     chunks
                     (make-chunk {:id (:id header)
                                  :summary (:summary header)
                                  :parent-id parent-id
                                  :aspects (:aspects header)})
                     []
                     new-stack))
            ;; Content line
            (recur (rest lines)
                   chunks
                   current-chunk
                   (conj content-lines (str/trim line))
                   indent-stack)))))))

(defn parse-file
  "Parse a tramando .trmd file into {:metadata ... :chunks [...]}"
  [text]
  (let [{:keys [metadata content]} (parse-yaml-frontmatter text)
        chunks (parse-chunks content)]
    {:metadata metadata
     :chunks chunks}))

;; =============================================================================
;; Serialization
;; =============================================================================

(defn- serialize-chunk
  "Serialize a single chunk to string"
  [chunk depth]
  (let [indent (apply str (repeat (* 2 depth) " "))
        aspects-str (str/join "" (map #(str "[@" % "]") (:aspects chunk)))
        header (str indent "[C:" (:id chunk) "\"" (:summary chunk) "\"]" aspects-str)
        content-lines (when (seq (:content chunk))
                        (map #(str indent %) (str/split-lines (:content chunk))))]
    (str/join "\n" (cons header content-lines))))

(defn- serialize-tree
  "Recursively serialize a chunk and all its children"
  [chunk depth chunks-by-parent]
  (let [chunk-str (serialize-chunk chunk depth)
        children (get chunks-by-parent (:id chunk) [])
        children-strs (map #(serialize-tree % (inc depth) chunks-by-parent) children)]
    (str/join "\n\n" (cons chunk-str children-strs))))

(defn- serialize-metadata
  "Serialize metadata to YAML frontmatter"
  [metadata]
  (let [{:keys [title author language year isbn publisher custom]} metadata
        lines (cond-> []
                (seq title) (conj (str "title: \"" title "\""))
                (seq author) (conj (str "author: \"" author "\""))
                (seq language) (conj (str "language: \"" language "\""))
                year (conj (str "year: " year))
                (seq isbn) (conj (str "isbn: \"" isbn "\""))
                (seq publisher) (conj (str "publisher: \"" publisher "\""))
                (seq custom) (conj (str "custom:\n"
                                         (str/join "\n"
                                                   (map (fn [[k v]]
                                                          (str "  " (name k) ": \"" v "\""))
                                                        custom)))))]
    (if (seq lines)
      (str "---\n" (str/join "\n" lines) "\n---\n\n")
      "")))

(defn serialize-chunks
  "Serialize chunks only (without frontmatter)"
  [chunks]
  (let [chunks-by-parent (group-by :parent-id chunks)
        roots (get chunks-by-parent nil [])]
    (->> roots
         (map #(serialize-tree % 0 chunks-by-parent))
         (str/join "\n\n"))))

(defn serialize-file
  "Serialize chunks with metadata to tramando .trmd format"
  [chunks metadata]
  (let [frontmatter (serialize-metadata metadata)
        content (serialize-chunks chunks)]
    (str frontmatter content)))

;; =============================================================================
;; Metadata Defaults
;; =============================================================================

(def default-metadata
  {:title ""
   :author ""
   :language "it"
   :year nil
   :isbn ""
   :publisher ""
   :custom {}})

(def available-languages
  [["it" "Italiano"]
   ["en" "English"]
   ["fr" "Français"]
   ["de" "Deutsch"]
   ["es" "Español"]
   ["pt" "Português"]])

;; =============================================================================
;; App State
;; =============================================================================

(defonce app-state
  (r/atom {:chunks []
           :selected-id nil
           :filename "untitled.trmd"
           :metadata default-metadata}))

;; =============================================================================
;; History (Undo/Redo)
;; =============================================================================

(def ^:private max-history-size 100)

(defonce history
  (r/atom {:states []      ; vector of {:chunks [...] :selected-id ...}
           :current -1     ; index of current state (-1 = no history yet)
           :checkpoints []}))

(defn- get-snapshot
  "Get a snapshot of the current state for history"
  []
  {:chunks (:chunks @app-state)
   :selected-id (:selected-id @app-state)})

(defn- restore-snapshot!
  "Restore app state from a snapshot (without triggering history push)"
  [snapshot]
  (swap! app-state merge (select-keys snapshot [:chunks :selected-id])))

(defn push-history!
  "Push current state to history. Called after state changes."
  []
  (let [snapshot (get-snapshot)
        {:keys [states current]} @history
        ;; Discard any future states if we're not at the end
        ;; Handle case where current is -1 (no history yet)
        new-states (if (neg? current)
                     []
                     (subvec states 0 (inc current)))
        ;; Add new state
        new-states (conj new-states snapshot)
        ;; Trim if over max size
        new-states (if (> (count new-states) max-history-size)
                     (subvec new-states (- (count new-states) max-history-size))
                     new-states)]
    (reset! history {:states new-states
                     :current (dec (count new-states))
                     :checkpoints (:checkpoints @history)})))

(defn can-undo? []
  (> (:current @history) 0))

(defn can-redo? []
  (let [{:keys [states current]} @history]
    (< current (dec (count states)))))

(defn undo!
  "Go to previous state. Returns true if successful."
  []
  (when (can-undo?)
    (swap! history update :current dec)
    (let [snapshot (nth (:states @history) (:current @history))]
      (restore-snapshot! snapshot))
    true))

(defn redo!
  "Go to next state. Returns true if successful."
  []
  (when (can-redo?)
    (swap! history update :current inc)
    (let [snapshot (nth (:states @history) (:current @history))]
      (restore-snapshot! snapshot))
    true))

;; =============================================================================
;; Save Status & Autosave
;; =============================================================================

;; :saved, :modified, :saving
(defonce save-status (r/atom :saved))

;; Timer ID for debounced autosave
(defonce ^:private autosave-timer (atom nil))

;; Timer ID for "Salvato" fade
(defonce ^:private saved-fade-timer (atom nil))

(def ^:private saved-fade-delay-ms 2000)

(def ^:private localstorage-key "tramando-autosave")

(defn- do-autosave!
  "Perform autosave to localStorage"
  []
  (reset! save-status :saving)
  (let [content (serialize-file (:chunks @app-state) (:metadata @app-state))
        filename (:filename @app-state)
        data (js/JSON.stringify #js {:content content :filename filename})]
    (.setItem js/localStorage localstorage-key data))
  ;; Show "Salvato" then fade
  (reset! save-status :saved)
  (when @saved-fade-timer
    (js/clearTimeout @saved-fade-timer))
  (reset! saved-fade-timer
          (js/setTimeout #(reset! save-status :idle) saved-fade-delay-ms)))

(defn has-autosave?
  "Check if there's an autosave in localStorage"
  []
  (boolean (.getItem js/localStorage localstorage-key)))

(defn restore-autosave!
  "Restore from localStorage autosave"
  []
  (when-let [data-str (.getItem js/localStorage localstorage-key)]
    (let [data (js/JSON.parse data-str)
          content (.-content data)
          filename (.-filename data)]
      (load-file-content! content filename))))

(defn clear-autosave!
  "Clear the autosave from localStorage"
  []
  (.removeItem js/localStorage localstorage-key))

(defn- schedule-autosave!
  "Schedule an autosave after the debounce delay"
  []
  ;; Cancel any pending autosave
  (when @autosave-timer
    (js/clearTimeout @autosave-timer))
  ;; Mark as modified
  (reset! save-status :modified)
  ;; Schedule new autosave with delay from settings
  (reset! autosave-timer
          (js/setTimeout do-autosave! (settings/get-autosave-delay))))

(defn mark-modified!
  "Mark the document as modified and schedule autosave"
  []
  (schedule-autosave!))

(defn get-chunks []
  (:chunks @app-state))

(defn get-all-chunks
  "Get all chunks as a map indexed by id"
  []
  (into {} (map (juxt :id identity) (get-chunks))))

(defn get-chunk
  "Get a chunk by id"
  [id]
  (first (filter #(= (:id %) id) (get-chunks))))

(defn get-selected-id []
  (:selected-id @app-state))

(defn get-selected-chunk []
  (let [id (get-selected-id)
        chunks (get-chunks)]
    (first (filter #(= (:id %) id) chunks))))

(defn select-chunk! [id]
  (swap! app-state assoc :selected-id id))

(defn update-chunk!
  "Update a chunk by id with the given changes"
  [id changes]
  (push-history!)
  (swap! app-state update :chunks
         (fn [chunks]
           (mapv (fn [c]
                   (if (= (:id c) id)
                     (merge c changes)
                     c))
                 chunks)))
  (mark-modified!))

(defn add-chunk!
  "Add a new chunk, optionally with a parent, summary, and content.
   If :id is provided, uses that ID instead of auto-generating.
   If :select? is false, doesn't select the new chunk (default true)."
  [& {:keys [id parent-id summary content select?]
      :or {id nil parent-id nil summary nil content nil select? true}}]
  (push-history!)
  (let [base-chunk (new-chunk :parent-id parent-id
                              :summary (or summary "Nuovo chunk")
                              :content (or content "")
                              :existing-chunks (get-chunks))
        ;; Override ID if provided
        chunk (if id
                (assoc base-chunk :id id)
                base-chunk)]
    (swap! app-state update :chunks conj chunk)
    (when select?
      (select-chunk! (:id chunk)))
    (mark-modified!)
    chunk))

(defn delete-chunk! [id]
  (push-history!)
  (swap! app-state update :chunks
         (fn [chunks]
           (filterv #(not= (:id %) id) chunks)))
  (mark-modified!))

(defn rename-chunk-id!
  "Rename a chunk's ID and update all references.
   Returns {:ok true} or {:error \"message\"}"
  [old-id new-id]
  (cond
    ;; Validate new ID
    (or (nil? new-id) (= "" (str/trim new-id)))
    {:error "L'ID non può essere vuoto"}

    ;; Check if new ID already exists
    (some #(= (:id %) new-id) (get-chunks))
    {:error (str "ID \"" new-id "\" già esistente")}

    ;; Can't rename aspect containers
    (contains? aspect-container-ids old-id)
    {:error "Non puoi rinominare i contenitori"}

    :else
    (do
      (push-history!)
      (swap! app-state update :chunks
             (fn [chunks]
               (mapv (fn [c]
                       (cond-> c
                         ;; Update the chunk's own ID
                         (= (:id c) old-id)
                         (assoc :id new-id)

                         ;; Update parent-id references
                         (= (:parent-id c) old-id)
                         (assoc :parent-id new-id)

                         ;; Update aspects references
                         (contains? (:aspects c) old-id)
                         (update :aspects #(-> % (disj old-id) (conj new-id)))

                         ;; Update ordered-refs
                         (some #(= (:ref %) old-id) (:ordered-refs c))
                         (update :ordered-refs
                                 (fn [refs]
                                   (mapv #(if (= (:ref %) old-id)
                                            (assoc % :ref new-id)
                                            %)
                                         refs)))))
                     chunks)))
      ;; Update selection if needed
      (when (= (get-selected-id) old-id)
        (select-chunk! new-id))
      (mark-modified!)
      {:ok true})))

;; =============================================================================
;; Reordering and moving chunks
;; =============================================================================

(defn get-siblings
  "Get all chunks with the same parent-id, in order"
  [chunk]
  (let [parent-id (:parent-id chunk)]
    (filterv #(= (:parent-id %) parent-id) (get-chunks))))

(defn- swap-positions
  "Swap two chunks in the chunks vector"
  [chunks id1 id2]
  (let [idx1 (first (keep-indexed #(when (= (:id %2) id1) %1) chunks))
        idx2 (first (keep-indexed #(when (= (:id %2) id2) %1) chunks))]
    (if (and idx1 idx2)
      (-> chunks
          (assoc idx1 (nth chunks idx2))
          (assoc idx2 (nth chunks idx1)))
      chunks)))

(defn move-chunk-up!
  "Move a chunk up among its siblings"
  [id]
  (let [chunk (first (filter #(= (:id %) id) (get-chunks)))
        siblings (get-siblings chunk)
        idx (first (keep-indexed #(when (= (:id %2) id) %1) siblings))]
    (when (and idx (pos? idx))
      (push-history!)
      (let [prev-id (:id (nth siblings (dec idx)))]
        (swap! app-state update :chunks swap-positions id prev-id))
      (mark-modified!))))

(defn move-chunk-down!
  "Move a chunk down among its siblings"
  [id]
  (let [chunk (first (filter #(= (:id %) id) (get-chunks)))
        siblings (get-siblings chunk)
        idx (first (keep-indexed #(when (= (:id %2) id) %1) siblings))]
    (when (and idx (< idx (dec (count siblings))))
      (push-history!)
      (let [next-id (:id (nth siblings (inc idx)))]
        (swap! app-state update :chunks swap-positions id next-id))
      (mark-modified!))))

(defn change-parent!
  "Move a chunk to a new parent. Returns {:ok true} or {:error \"message\"}"
  [chunk-id new-parent-id]
  (let [chunk (first (filter #(= (:id %) chunk-id) (get-chunks)))]
    (cond
      ;; Can't move aspect containers
      (contains? aspect-container-ids chunk-id)
      {:error "Non puoi spostare i contenitori"}

      ;; Can't make a chunk its own parent
      (= chunk-id new-parent-id)
      {:error "Un chunk non può essere figlio di sé stesso"}

      ;; Can't move to a descendant (would create a cycle)
      (loop [current new-parent-id]
        (if (nil? current)
          false
          (if (= current chunk-id)
            true
            (let [parent (first (filter #(= (:id %) current) (get-chunks)))]
              (recur (:parent-id parent))))))
      {:error "Non puoi spostare un chunk sotto un suo discendente"}

      :else
      (do
        (push-history!)
        (swap! app-state update :chunks
               (fn [chunks]
                 (mapv #(if (= (:id %) chunk-id)
                          (assoc % :parent-id new-parent-id)
                          %)
                       chunks)))
        (mark-modified!)
        {:ok true}))))

(defn get-possible-parents
  "Get all chunks that could be a parent for the given chunk (excludes self and descendants)"
  [chunk-id]
  (let [chunks (get-chunks)
        ;; Find all descendants of chunk-id
        descendants (loop [to-check [chunk-id]
                           found #{}]
                      (if (empty? to-check)
                        found
                        (let [id (first to-check)
                              children-ids (map :id (filter #(= (:parent-id %) id) chunks))]
                          (recur (into (vec (rest to-check)) children-ids)
                                 (conj found id)))))]
    ;; Return all chunks except descendants
    (remove #(contains? descendants (:id %)) chunks)))

(defn load-content!
  "Load content from parsed text"
  [text]
  (let [chunks (parse-file text)]
    (swap! app-state assoc :chunks chunks)
    (when (seq chunks)
      (select-chunk! (:id (first chunks))))))

(defn get-file-content
  "Get serialized content for saving"
  []
  (serialize-file (get-chunks) (:metadata @app-state)))

(defn set-filename! [name]
  (swap! app-state assoc :filename name))

(defn get-filename []
  (:filename @app-state))

(defn save-file!
  "Download the current state as a .trmd file"
  []
  ;; Cancel any pending autosave
  (when @autosave-timer
    (js/clearTimeout @autosave-timer)
    (reset! autosave-timer nil))
  (let [content (get-file-content)
        filename (get-filename)
        blob (js/Blob. #js [content] #js {:type "text/plain"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.click a)
    (js/URL.revokeObjectURL url))
  ;; Show "Salvato" then fade
  (reset! save-status :saved)
  (when @saved-fade-timer
    (js/clearTimeout @saved-fade-timer))
  (reset! saved-fade-timer
          (js/setTimeout #(reset! save-status :idle) saved-fade-delay-ms)))

(defn export-md!
  "Export as .md file (without frontmatter)"
  []
  (let [content (serialize-chunks (get-chunks))
        filename (str/replace (get-filename) #"\.trmd$" ".md")
        blob (js/Blob. #js [content] #js {:type "text/markdown"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.click a)
    (js/URL.revokeObjectURL url)))

(defn load-file-content!
  "Load content from a string (called after file is read)"
  [content filename]
  ;; Clear current state and load new content
  (swap! app-state assoc :chunks [] :selected-id nil :metadata default-metadata)
  ;; Reset history
  (reset! history {:states [] :current -1 :checkpoints []})
  ;; Ensure aspect containers exist
  (ensure-aspect-containers!)
  ;; Parse and load the file (now returns {:metadata ... :chunks ...})
  (let [{:keys [metadata chunks]} (parse-file content)
        existing-ids (set (map :id (get-chunks)))]
    ;; Set metadata
    (swap! app-state assoc :metadata metadata)
    ;; Add parsed chunks (skip if already exists - e.g. aspect containers)
    (doseq [chunk chunks]
      (when-not (contains? existing-ids (:id chunk))
        (swap! app-state update :chunks conj chunk))))
  ;; Set filename
  (set-filename! filename)
  ;; Select first non-container chunk
  (let [structural (filter #(not (contains? aspect-container-ids (:id %))) (get-chunks))]
    (when (seq structural)
      (select-chunk! (:id (first structural)))))
  ;; Push initial state to history
  (push-history!)
  (reset! save-status :idle))

;; =============================================================================
;; Tree helpers
;; =============================================================================

(defn get-root-chunks
  "Get chunks with no parent"
  []
  (filter #(nil? (:parent-id %)) (get-chunks)))

(defn get-children
  "Get direct children of a chunk"
  [parent-id]
  (filter #(= (:parent-id %) parent-id) (get-chunks)))

(defn build-tree
  "Build a tree structure from flat chunks"
  []
  (let [chunks (get-chunks)
        by-parent (group-by :parent-id chunks)]
    (letfn [(build-node [chunk]
              (assoc chunk :children (map build-node (get by-parent (:id chunk) []))))]
      (map build-node (get by-parent nil [])))))

;; =============================================================================
;; Aspect helpers
;; =============================================================================

(defn is-aspect-container?
  "Check if a chunk id is one of the fixed aspect containers"
  [id]
  (contains? aspect-container-ids id))

(defn is-aspect?
  "Check if a chunk is an aspect (direct child of an aspect container)"
  [chunk]
  (is-aspect-container? (:parent-id chunk)))

(defn is-aspect-chunk?
  "Check if a chunk belongs to the aspect hierarchy (any level).
   Walks up the parent chain to see if any ancestor is an aspect container.
   Returns true for:
   - Direct children of personaggi, luoghi, temi, sequenze, timeline
   - Descendants of sequenze or timeline (nested aspects)"
  [chunk]
  (let [chunks (get-chunks)]
    (loop [current-parent-id (:parent-id chunk)]
      (cond
        ;; Reached root - not an aspect
        (nil? current-parent-id) false
        ;; Found an aspect container - it's an aspect chunk
        (is-aspect-container? current-parent-id) true
        ;; Keep walking up
        :else (let [parent (first (filter #(= (:id %) current-parent-id) chunks))]
                (recur (:parent-id parent)))))))

(defn get-aspect-container-children
  "Get children of a specific aspect container"
  [container-id]
  (filter #(= (:parent-id %) container-id) (get-chunks)))

(defn build-aspect-tree
  "Build tree for a specific aspect container"
  [container-id]
  (let [chunks (get-chunks)
        by-parent (group-by :parent-id chunks)]
    (letfn [(build-node [chunk]
              (assoc chunk :children (map build-node (get by-parent (:id chunk) []))))]
      (map build-node (get by-parent container-id [])))))

(defn get-structural-tree
  "Build tree excluding aspect containers and their children"
  []
  (let [chunks (get-chunks)
        ;; Get all chunks that are under aspect containers (recursively)
        aspect-chunk-ids (loop [to-check (vec aspect-container-ids)
                                found #{}]
                           (if (empty? to-check)
                             found
                             (let [id (first to-check)
                                   children-ids (map :id (filter #(= (:parent-id %) id) chunks))]
                               (recur (into (vec (rest to-check)) children-ids)
                                      (conj found id)))))
        structural-chunks (remove #(contains? aspect-chunk-ids (:id %)) chunks)
        by-parent (group-by :parent-id structural-chunks)]
    (letfn [(build-node [chunk]
              (assoc chunk :children (map build-node (get by-parent (:id chunk) []))))]
      (map build-node (get by-parent nil [])))))

(defn chunks-using-aspect
  "Return chunks that reference this aspect id in their :aspects set (excludes self)"
  [aspect-id]
  (filter #(and (contains? (:aspects %) aspect-id)
                (not= (:id %) aspect-id))
          (get-chunks)))

(defn aspect-usage-count
  "Count how many chunks reference this aspect (excludes self)"
  [aspect-id]
  (count (chunks-using-aspect aspect-id)))

(defn can-delete-aspect?
  "Check if an aspect can be deleted (not used by any chunk)"
  [aspect-id]
  (zero? (aspect-usage-count aspect-id)))

(defn add-aspect-to-chunk!
  "Add an aspect reference to a chunk"
  [chunk-id aspect-id]
  (push-history!)
  (swap! app-state update :chunks
         (fn [chunks]
           (mapv #(if (= (:id %) chunk-id)
                    (update % :aspects conj aspect-id)
                    %)
                 chunks)))
  (mark-modified!))

(defn remove-aspect-from-chunk!
  "Remove an aspect reference from a chunk"
  [chunk-id aspect-id]
  (push-history!)
  (swap! app-state update :chunks
         (fn [chunks]
           (mapv #(if (= (:id %) chunk-id)
                    (update % :aspects disj aspect-id)
                    %)
                 chunks)))
  (mark-modified!))

(defn get-all-aspects
  "Get all aspect chunks (children of aspect containers)"
  []
  (filter #(contains? aspect-container-ids (:parent-id %)) (get-chunks)))

(defn add-aspect!
  "Add a new aspect under the specified container"
  [container-id]
  (push-history!)
  (let [container (first (filter #(= (:id %) container-id) aspect-containers))
        type-name (case container-id
                    "personaggi" "Personaggio"
                    "luoghi" "Luogo"
                    "temi" "Tema"
                    "sequenze" "Sequenza"
                    "timeline" "Timeline"
                    "Aspetto")
        chunk (new-chunk :summary (str "Nuovo " type-name)
                         :parent-id container-id
                         :existing-chunks (get-chunks))]
    (swap! app-state update :chunks conj chunk)
    (select-chunk! (:id chunk))
    (mark-modified!)
    chunk))

(defn try-delete-chunk!
  "Try to delete a chunk. Returns {:ok true} or {:error \"message\"}"
  [id]
  (let [usage (aspect-usage-count id)]
    (if (pos? usage)
      {:error (str "Impossibile cancellare: usato in " usage " chunk")}
      (do
        (delete-chunk! id)
        {:ok true}))))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn- ensure-aspect-containers!
  "Ensure all aspect containers exist"
  []
  (let [existing-ids (set (map :id (get-chunks)))]
    (doseq [{:keys [id summary]} aspect-containers]
      (when-not (contains? existing-ids id)
        (swap! app-state update :chunks conj
               (make-chunk {:id id :summary summary}))))))

;; Initialize with sample data
(defn init-sample-data! []
  ;; First ensure aspect containers exist
  (ensure-aspect-containers!)
  ;; Reset history
  (reset! history {:states [] :current -1 :checkpoints []})

  ;; Add sample aspects
  (let [existing-ids (set (map :id (get-chunks)))]
    (when-not (contains? existing-ids "pers-1")
      (swap! app-state update :chunks conj
             (make-chunk {:id "pers-1" :summary "Mario Rossi" :parent-id "personaggi"
                          :content "Il protagonista della storia."})))
    (when-not (contains? existing-ids "pers-2")
      (swap! app-state update :chunks conj
             (make-chunk {:id "pers-2" :summary "Lucia Bianchi" :parent-id "personaggi"
                          :content "La co-protagonista."})))
    (when-not (contains? existing-ids "luogo-1")
      (swap! app-state update :chunks conj
             (make-chunk {:id "luogo-1" :summary "Casa di Mario" :parent-id "luoghi"
                          :content "Un appartamento in centro città."})))
    (when-not (contains? existing-ids "tema-1")
      (swap! app-state update :chunks conj
             (make-chunk {:id "tema-1" :summary "Redenzione" :parent-id "temi"
                          :content "Il tema centrale della storia."}))))

  ;; Add sample structural chunks
  (let [existing-ids (set (map :id (get-chunks)))]
    (when-not (contains? existing-ids "cap-1")
      (swap! app-state update :chunks conj
             (make-chunk {:id "cap-1" :summary "Capitolo 1: L'inizio"
                          :content "Un nuovo giorno sorge sulla città."
                          :aspects #{"pers-1" "luogo-1"}})))
    (when-not (contains? existing-ids "scene-1")
      (swap! app-state update :chunks conj
             (make-chunk {:id "scene-1" :summary "La sveglia" :parent-id "cap-1"
                          :content "Il protagonista si sveglia di soprassalto."
                          :aspects #{"pers-1"}})))
    (when-not (contains? existing-ids "scene-2")
      (swap! app-state update :chunks conj
             (make-chunk {:id "scene-2" :summary "La colazione" :parent-id "cap-1"
                          :content "Una tazza di caffè fumante."
                          :aspects #{"pers-1" "pers-2"}})))
    (when-not (contains? existing-ids "cap-2")
      (swap! app-state update :chunks conj
             (make-chunk {:id "cap-2" :summary "Capitolo 2: Lo sviluppo"
                          :content "Le cose si complicano."
                          :aspects #{"tema-1"}})))
    (when-not (contains? existing-ids "scene-3")
      (swap! app-state update :chunks conj
             (make-chunk {:id "scene-3" :summary "L'incontro" :parent-id "cap-2"
                          :content "Un incontro inaspettato."
                          :aspects #{"pers-1" "pers-2"}}))))

  ;; Select first structural chunk
  (select-chunk! "cap-1")

  ;; Push initial state to history
  (push-history!)
  (reset! save-status :idle))

;; =============================================================================
;; Metadata Functions
;; =============================================================================

(defn get-metadata
  "Get current metadata"
  []
  (:metadata @app-state))

(defn get-title
  "Get project title"
  []
  (or (not-empty (:title (:metadata @app-state))) "Senza titolo"))

(defn update-metadata!
  "Update metadata with given changes"
  [changes]
  (swap! app-state update :metadata merge changes)
  (mark-modified!))

(defn set-custom-field!
  "Set a custom metadata field"
  [key value]
  (swap! app-state update-in [:metadata :custom] assoc (keyword key) value)
  (mark-modified!))

(defn remove-custom-field!
  "Remove a custom metadata field"
  [key]
  (swap! app-state update-in [:metadata :custom] dissoc (keyword key))
  (mark-modified!))

;; =============================================================================
;; Ordinal Macros
;; =============================================================================

(defn- int->roman
  "Convert integer to Roman numeral string"
  [n]
  (let [roman-map [[1000 "M"] [900 "CM"] [500 "D"] [400 "CD"]
                   [100 "C"] [90 "XC"] [50 "L"] [40 "XL"]
                   [10 "X"] [9 "IX"] [5 "V"] [4 "IV"] [1 "I"]]]
    (loop [num n
           result ""]
      (if (zero? num)
        result
        (let [[value sym] (first (filter #(<= (first %) num) roman-map))]
          (recur (- num value) (str result sym)))))))

(defn- int->alpha
  "Convert integer to letter (1=A, 2=B, ... 26=Z, 27=AA, etc.)"
  [n]
  (loop [num n
         result ""]
    (if (<= num 0)
      result
      (let [rem (mod (dec num) 26)
            char (char (+ 65 rem))]
        (recur (quot (dec num) 26) (str char result))))))

(defn get-chunk-ordinal
  "Get the 1-based ordinal position of a chunk among its siblings.
   For structural chunks, excludes aspect containers from the count."
  [chunk]
  (let [all-siblings (get-siblings chunk)
        ;; If this is a structural chunk (not in aspect hierarchy),
        ;; filter out aspect containers from siblings
        siblings (if (is-aspect-chunk? chunk)
                   all-siblings
                   (remove #(is-aspect-container? (:id %)) all-siblings))]
    (inc (or (first (keep-indexed #(when (= (:id %2) (:id chunk)) %1) siblings)) 0))))

(defn expand-summary-macros
  "Expand ordinal macros in a summary string.
   Supported: [:ORD], [:ORD-ROM], [:ORD-rom], [:ORD-ALPHA], [:ORD-alpha]"
  [summary chunk]
  (if (or (nil? summary) (nil? chunk))
    summary
    (let [ord (get-chunk-ordinal chunk)]
      (-> summary
          (str/replace "[:ORD]" (str ord))
          (str/replace "[:ORD-ROM]" (int->roman ord))
          (str/replace "[:ORD-rom]" (str/lower-case (int->roman ord)))
          (str/replace "[:ORD-ALPHA]" (int->alpha ord))
          (str/replace "[:ORD-alpha]" (str/lower-case (int->alpha ord)))))))

(defn get-chunk-path
  "Get the full path of a chunk as a string (e.g., 'Capitolo 2 > Scena 1 > Il dialogo').
   Excludes aspect containers from the path."
  [chunk]
  (let [chunks (get-chunks)]
    (loop [current chunk
           path []]
      (if (or (nil? current) (is-aspect-container? (:id current)))
        (str/join " — " (reverse path))
        (let [display-name (expand-summary-macros (:summary current) current)
              parent-id (:parent-id current)
              parent (when parent-id
                       (first (filter #(= (:id %) parent-id) chunks)))]
          (recur parent (conj path (or display-name "(senza titolo)"))))))))
