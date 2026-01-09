(ns tramando.model
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [tramando.settings :as settings]
            [tramando.versioning :as versioning]
            [tramando.platform :as platform]
            [tramando.events :as events]
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

;; =============================================================================
;; Collaborative Mode State (defined early for cross-namespace access)
;; =============================================================================

;; Current user for collaborative mode (set by RemoteStore)
(defonce current-user (atom nil))

;; User's role in current project: :owner, :admin, :collaborator, or nil (local mode)
(defonce user-role (atom nil))

;; Forward declaration for functions used before definition
(declare ensure-aspect-containers!)
(declare load-file-content!)
(declare can-create-chunk-at?)
(declare get-chunk)

(defn make-chunk
  "Create a new chunk with the given properties"
  [{:keys [id summary content parent-id aspects ordered-refs priority
           owner previous-owner ownership-expires discussion]
    :or {summary "" content "" parent-id nil aspects #{} ordered-refs [] priority nil
         owner "local" previous-owner nil ownership-expires nil discussion []}}]
  {:id id
   :summary summary
   :content content
   :parent-id parent-id
   :aspects (set aspects)
   :ordered-refs (vec ordered-refs)
   :priority priority  ;; 0-10, nil = not set (treated as 0)
   ;; Collaborative fields
   :owner owner
   :previous-owner previous-owner
   :ownership-expires ownership-expires
   :discussion (vec discussion)})

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
  [& {:keys [summary content parent-id aspects ordered-refs existing-chunks owner]
      :or {summary "Nuovo chunk" content "" parent-id nil aspects #{} ordered-refs [] existing-chunks [] owner "local"}}]
  (make-chunk {:id (generate-id parent-id existing-chunks)
               :summary summary
               :content content
               :parent-id parent-id
               :aspects aspects
               :ordered-refs ordered-refs
               :owner owner}))

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

;; Collaborative attribute patterns: [#owner:value], [#prev-owner:value], [#expires:value]
(def ^:private owner-re #"\[#owner:([^\]]+)\]")
(def ^:private prev-owner-re #"\[#prev-owner:([^\]]+)\]")
(def ^:private expires-re #"\[#expires:([^\]]+)\]")
;; Priority pattern: [#priority:N] where N is 0-10
(def ^:private priority-re #"\[#priority:(\d+)\]")

;; Discussion block pattern: [!DISCUSSION:base64data]
(def ^:private discussion-re #"\[!DISCUSSION:([^\]]+)\]")

;; =============================================================================
;; Base64 and Discussion Helpers
;; =============================================================================

(defn- encode-base64
  "Encode a string to Base64"
  [s]
  (js/btoa (js/encodeURIComponent s)))

(defn- decode-base64
  "Decode a Base64 string"
  [s]
  (try
    (js/decodeURIComponent (js/atob s))
    (catch :default _
      nil)))

(defn- encode-discussion
  "Encode discussion list to Base64 JSON"
  [discussion]
  (when (seq discussion)
    (encode-base64 (js/JSON.stringify (clj->js discussion)))))

(defn- decode-discussion
  "Decode Base64 JSON to discussion list"
  [base64-str]
  (when-let [json-str (decode-base64 base64-str)]
    (try
      (js->clj (js/JSON.parse json-str) :keywordize-keys true)
      (catch :default _
        []))))

(defn- extract-discussion-from-content
  "Extract [!DISCUSSION:...] block from content, returns {:content :discussion}"
  [content]
  (if-let [[full-match base64-data] (re-find discussion-re content)]
    {:content (str/trim (str/replace content full-match ""))
     :discussion (or (decode-discussion base64-data) [])}
    {:content content
     :discussion []}))

;; =============================================================================
;; Inline Proposals (Annotation-based format)
;; =============================================================================
;; Format: [!PROPOSAL:original-text:Puser:base64(proposed-text)]
;; This integrates with the annotation system for consistent UI/UX

(defn- encode-proposal-data
  "Encode proposal data (text + selection) to Base64 EDN"
  [proposed-text sel]
  (js/btoa (js/encodeURIComponent (pr-str {:text proposed-text :sel sel}))))

(defn- parse-proposal-data
  "Parse Base64-encoded EDN data from a PROPOSAL annotation comment.
   Returns {:text string :sel 0|1} or nil.
   For backwards compatibility, also handles plain base64 text (old format)."
  [encoded-string]
  (when (and encoded-string (seq encoded-string))
    (try
      (let [decoded (-> encoded-string js/atob js/decodeURIComponent)
            data (reader/read-string decoded)]
        (if (map? data)
          {:text (or (:text data) "") :sel (or (:sel data) 0)}
          {:text decoded :sel 0}))
      (catch :default _
        (try
          {:text (js/decodeURIComponent (js/atob encoded-string)) :sel 0}
          (catch :default _ nil))))))

(defn- decode-proposed-text
  "Decode proposed text from PROPOSAL annotation comment"
  [base64-str]
  (:text (parse-proposal-data base64-str)))

(defn make-proposal-marker
  "Create a proposal annotation marker.
   Format: [!PROPOSAL:original-text:Puser:base64({:text \"...\" :sel 0})]"
  ([original-text proposed-text user]
   (make-proposal-marker original-text proposed-text user 0))
  ([original-text proposed-text user sel]
   (str "[!PROPOSAL:" original-text ":P" user ":" (encode-proposal-data proposed-text sel) "]")))

(defn insert-proposal-in-content
  "Replace selected text with a proposal annotation in content"
  [content start end proposed-text user]
  (let [original-text (subs content start end)
        marker (make-proposal-marker original-text proposed-text user)]
    (str (subs content 0 start) marker (subs content end))))

(defn- find-proposal-annotation-pattern
  "Create a regex pattern to find a PROPOSAL annotation by original text.
   Allows optional whitespace around the selected text (handles trimmed vs untrimmed)."
  [original-text user]
  (let [escaped-text (str/replace original-text #"[.*+?^${}()\[\]\\|]" (fn [m] (str "\\" m)))]
    (re-pattern (str "\\[!PROPOSAL:\\s*" escaped-text "\\s*:P" user ":([A-Za-z0-9+/=]+)\\]"))))

(defn accept-proposal-in-content
  "Accept a proposal: replace annotation with the currently selected text.
   If sel=0 (original), keeps original (preserving whitespace).
   If sel=1 (proposed), applies proposed text."
  [content annotation]
  (let [data (parse-proposal-data (:comment annotation))
        proposed-text (:text data)
        sel (or (:sel data) 0)
        user (or (:proposal-from (:priority annotation)) "local")
        ;; Escape the trimmed text for regex
        escaped-text (str/replace (:selected-text annotation) #"[.*+?^${}()\[\]\\|]" (fn [m] (str "\\" m)))
        ;; Capture the actual text including any surrounding whitespace
        pattern (re-pattern (str "\\[!PROPOSAL:(\\s*" escaped-text "\\s*):P" user ":([A-Za-z0-9+/=]+)\\]"))
        match (re-find pattern content)]
    (if match
      (let [original-with-spaces (second match)
            replacement (if (zero? sel) original-with-spaces proposed-text)]
        (str/replace content (first match) replacement))
      content)))

(defn reject-proposal-in-content
  "Reject a proposal: always replace annotation with original-text (preserving whitespace)"
  [content annotation]
  (let [user (or (:proposal-from (:priority annotation)) "local")
        ;; Escape the trimmed text for regex
        escaped-text (str/replace (:selected-text annotation) #"[.*+?^${}()\[\]\\|]" (fn [m] (str "\\" m)))
        ;; Capture the actual text including any surrounding whitespace
        pattern (re-pattern (str "\\[!PROPOSAL:(\\s*" escaped-text "\\s*):P" user ":([A-Za-z0-9+/=]+)\\]"))
        match (re-find pattern content)]
    (if match
      ;; Replace with the captured text (preserving original whitespace)
      (str/replace content (first match) (second match))
      content)))

(defn- count-indent
  "Count leading spaces (2 spaces = 1 level)"
  [line]
  (let [spaces (count (re-find #"^  *" line))]
    (quot spaces 2)))

(defn- parse-header
  "Parse a chunk header line, returns {:id :summary :aspects :owner :previous-owner :ownership-expires :priority} or nil"
  [line]
  (when-let [[_ id summary rest] (re-matches chunk-header-re (str/trim line))]
    (let [aspects (->> (re-seq aspect-re rest)
                       (map second)
                       set)
          ;; Extract collaborative attributes (optional)
          owner (second (re-find owner-re rest))
          prev-owner (second (re-find prev-owner-re rest))
          expires (second (re-find expires-re rest))
          ;; Extract priority (optional, 0-10)
          priority-str (second (re-find priority-re rest))
          priority (when priority-str (js/parseInt priority-str 10))]
      {:id id
       :summary summary
       :aspects aspects
       :owner (or owner "local")
       :previous-owner prev-owner
       :ownership-expires expires
       :priority priority})))

;; Default metadata (defined here for use in parse-yaml-frontmatter)
(def default-metadata
  {:title ""
   :author ""
   :language "it"
   :year nil
   :isbn ""
   :publisher ""
   :custom {}})

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

(defn- finalize-chunk
  "Finalize a chunk by processing its content and extracting discussion"
  [chunk content-lines]
  (when chunk
    (let [raw-content (str/trim (str/join "\n" content-lines))
          {:keys [content discussion]} (extract-discussion-from-content raw-content)]
      (assoc chunk :content content :discussion discussion))))

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
        (if-let [final-chunk (finalize-chunk current-chunk content-lines)]
          (conj chunks final-chunk)
          chunks)
        (let [line (first lines)
              indent (count-indent line)
              header (parse-header line)]
          (if header
            ;; New chunk header found
            (let [;; Save previous chunk if any
                  chunks (if-let [prev-chunk (finalize-chunk current-chunk content-lines)]
                           (conj chunks prev-chunk)
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
                                  :aspects (:aspects header)
                                  :priority (:priority header)
                                  ;; Collaborative fields from header
                                  :owner (:owner header)
                                  :previous-owner (:previous-owner header)
                                  :ownership-expires (:ownership-expires header)})
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
;; Conflict Resolution / Merge
;; =============================================================================

(defn- chunk-content-equal?
  "Check if two chunks have the same main content (excluding discussion).
   Discussion is handled separately with append-merge strategy."
  [chunk1 chunk2]
  (and (= (:summary chunk1) (:summary chunk2))
       (= (:content chunk1) (:content chunk2))
       (= (:parent-id chunk1) (:parent-id chunk2))
       (= (:aspects chunk1) (:aspects chunk2))
       (= (:owner chunk1) (:owner chunk2))))

(defn- merge-discussions
  "Merge two discussion lists.
   Strategy: combine entries, dedupe by timestamp+author+text, sort by timestamp.
   This allows append-only operations (comments) to never be lost in conflicts."
  [local-discussion server-discussion]
  (let [local-entries (or local-discussion [])
        server-entries (or server-discussion [])
        ;; Create a unique key for each entry to dedupe
        entry-key (fn [entry]
                    (str (:timestamp entry) "|" (:author entry) "|" (:type entry) "|"
                         (:text entry) (:previous-text entry) (:proposed-text entry)))
        ;; Combine and dedupe
        all-entries (vals (into {} (map (juxt entry-key identity)
                                        (concat server-entries local-entries))))
        ;; Sort by timestamp
        sorted-entries (sort-by :timestamp all-entries)]
    (vec sorted-entries)))

(defn merge-with-server-content
  "Merge local changes with server content.
   Returns {:merged-chunks [...] :conflicts [{:id ... :local-owner ... :server-owner ...}]}

   Strategy:
   - For each chunk, compare local vs server
   - If only one side changed from base → use that version
   - If both changed (true conflict) → server wins for content, but merge discussions
   - New chunks from either side are kept
   - Discussion entries are always merged (append-only, never lost)

   Since we don't have the original base, we use a simpler heuristic:
   - Server version is authoritative for content
   - Discussion entries from both sides are merged (union)"
  [local-chunks server-content]
  (let [;; Parse server content
        {:keys [chunks metadata]} (parse-file server-content)
        server-chunks chunks
        ;; Build lookup maps by ID
        local-by-id (into {} (map (juxt :id identity) local-chunks))
        server-by-id (into {} (map (juxt :id identity) server-chunks))
        ;; All unique IDs
        all-ids (set (concat (keys local-by-id) (keys server-by-id)))
        ;; Process each chunk
        result (reduce
                (fn [acc id]
                  (let [local-chunk (get local-by-id id)
                        server-chunk (get server-by-id id)]
                    (cond
                      ;; Only in local (new local chunk) → keep it
                      (and local-chunk (nil? server-chunk))
                      (update acc :merged-chunks conj local-chunk)

                      ;; Only in server (new server chunk or deleted locally)
                      ;; → use server version
                      (and server-chunk (nil? local-chunk))
                      (update acc :merged-chunks conj server-chunk)

                      ;; Both exist - check if they're different
                      (and local-chunk server-chunk)
                      (let [;; Always merge discussions (append-only, never lost)
                            merged-discussion (merge-discussions
                                               (:discussion local-chunk)
                                               (:discussion server-chunk))]
                        (if (chunk-content-equal? local-chunk server-chunk)
                          ;; Same content → use local with merged discussion
                          (update acc :merged-chunks conj
                                  (assoc local-chunk :discussion merged-discussion))
                          ;; Different content → conflict! Server wins, but merge discussions
                          (-> acc
                              (update :merged-chunks conj
                                      (assoc server-chunk :discussion merged-discussion))
                              (update :conflicts conj {:id id
                                                       :chunk-summary (:summary server-chunk)
                                                       :local-owner (:owner local-chunk)
                                                       :server-owner (:owner server-chunk)}))))

                      :else acc)))
                {:merged-chunks []
                 :conflicts []
                 :server-metadata metadata}
                all-ids)]
    ;; Sort merged chunks to maintain order (by parent-id hierarchy)
    ;; For now, just preserve server order for chunks that exist there
    (let [server-order (into {} (map-indexed (fn [i c] [(:id c) i]) server-chunks))
          sorted-chunks (sort-by #(get server-order (:id %) 999999) (:merged-chunks result))]
      (assoc result :merged-chunks (vec sorted-chunks)))))

;; =============================================================================
;; Serialization
;; =============================================================================

(defn- serialize-chunk
  "Serialize a single chunk to string"
  [chunk depth]
  (let [indent (apply str (repeat (* 2 depth) " "))
        aspects-str (str/join "" (map #(str "[@" % "]") (:aspects chunk)))
        ;; Priority (only include if set and > 0)
        priority (:priority chunk)
        priority-str (when (and priority (pos? priority))
                       (str "[#priority:" priority "]"))
        ;; Collaborative attributes (only include if non-default)
        owner (:owner chunk)
        owner-str (when (and owner (not= owner "local"))
                    (str "[#owner:" owner "]"))
        prev-owner-str (when (:previous-owner chunk)
                         (str "[#prev-owner:" (:previous-owner chunk) "]"))
        expires-str (when (:ownership-expires chunk)
                      (str "[#expires:" (:ownership-expires chunk) "]"))
        collab-str (str priority-str owner-str prev-owner-str expires-str)
        ;; Build header
        header (str indent "[C:" (:id chunk) "\"" (:summary chunk) "\"]" aspects-str collab-str)
        ;; Content with optional discussion block
        base-content (:content chunk)
        discussion-block (when (seq (:discussion chunk))
                           (str "\n\n[!DISCUSSION:" (encode-discussion (:discussion chunk)) "]"))
        full-content (str base-content discussion-block)
        content-lines (when (seq full-content)
                        (map #(str indent %) (str/split-lines full-content)))]
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
;; Available Languages
;; =============================================================================

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
           :filepath nil  ;; Full path to current file (nil if never saved)
           :metadata default-metadata}))

;; Collaborative mode state functions (atoms defined at top of file)

(defn set-current-user! [username]
  (reset! current-user username))

(defn set-user-role! [role]
  (reset! user-role role))

(defn get-user-role []
  @user-role)

(defn is-project-owner?
  "Check if current user is the project owner (or in local mode)"
  []
  (or (nil? @user-role)  ;; local mode
      (= @user-role :owner)))

(defn can-edit-chunk?
  "Check if current user can edit a specific chunk.
   - Project owner can edit ALL chunks (to handle imported files)
   - Chunks with nil or 'local' owner: only project owner can edit
   - Chunks with explicit owner: that owner OR project owner can edit"
  [chunk-id]
  (let [chunk (get-chunk chunk-id)
        chunk-owner (:owner chunk)]
    (cond
      ;; Project owner can edit everything
      (is-project-owner?) true
      ;; Unowned chunks: only project owner can edit (already handled above)
      (or (nil? chunk-owner) (= "local" chunk-owner)) false
      ;; Owned chunks: only the chunk owner can edit
      :else (= chunk-owner @current-user))))

(defn get-current-owner
  "Get the owner to use for new chunks. Returns username if in collaborative mode, 'local' otherwise."
  []
  (or @current-user "local"))

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
    (let [snapshot (nth (:states @history) (:current @history))]
      (restore-snapshot! snapshot))
    (swap! history update :current dec)
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

;; Autosave progress: 0 = idle/saved, 1-4 = progress toward autosave
(defonce autosave-progress (r/atom 0))

;; Optional external callback for modifications (used by server mode)
(defonce on-modified-callback (atom nil))

;; Optional callback when title changes (used by server mode to sync project name)
(defonce on-title-changed-callback (atom nil))

;; Timer ID for debounced autosave
(defonce ^:private autosave-timer (atom nil))

;; Timer ID for progress steps
(defonce ^:private progress-timer (atom nil))

;; Timer ID for "Salvato" fade
(defonce ^:private saved-fade-timer (atom nil))

(def ^:private saved-fade-delay-ms 2000)

(def ^:private localstorage-key "tramando-autosave")
(def ^:private selected-chunk-key "tramando-selected-chunk")

(defn- do-autosave!
  "Perform autosave to localStorage"
  []
  ;; Clear progress timer
  (when @progress-timer
    (js/clearInterval @progress-timer)
    (reset! progress-timer nil))
  ;; Reset progress indicator
  (reset! autosave-progress 0)
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
  ;; Cancel any pending autosave and progress timer
  (when @autosave-timer
    (js/clearTimeout @autosave-timer))
  (when @progress-timer
    (js/clearInterval @progress-timer)
    (reset! progress-timer nil))
  ;; Mark as modified and start progress at 1
  (reset! save-status :modified)
  (reset! autosave-progress 1)
  ;; Start progress timer - advances every 25% of the delay
  (let [delay-ms (settings/get-autosave-delay)
        step-ms (/ delay-ms 4)]
    (reset! progress-timer
            (js/setInterval
             (fn []
               (when (< @autosave-progress 4)
                 (swap! autosave-progress inc)))
             step-ms))
    ;; Schedule autosave
    (reset! autosave-timer
            (js/setTimeout do-autosave! delay-ms))))

(defn mark-modified!
  "Mark the document as modified and schedule autosave"
  []
  (versioning/mark-dirty!)
  (if @on-modified-callback
    (@on-modified-callback)
    (schedule-autosave!)))

;; =============================================================================
;; Ownership Management
;; =============================================================================

(defn get-chunk-owner
  "Get the owner of a chunk, or nil if unowned/local."
  [chunk-id]
  (let [chunk (first (filter #(= (:id %) chunk-id) (:chunks @app-state)))
        owner (:owner chunk)]
    (when (and owner (not= owner "local"))
      owner)))

(defn set-chunk-owner!
  "Change the owner of a chunk. Only project owners can do this.
   new-owner can be a username or nil to make it unowned (project owner only)."
  [chunk-id new-owner]
  (when (is-project-owner?)
    (push-history!)
    (swap! app-state update :chunks
           (fn [chunks]
             (mapv (fn [c]
                     (if (= (:id c) chunk-id)
                       (assoc c :owner (or new-owner "local"))
                       c))
                   chunks)))
    (mark-modified!)))

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

(defn- get-file-key
  "Get a unique key for the current file (for localStorage)"
  []
  (or (:filepath @app-state)
      (:filename @app-state)
      "untitled"))

(defn- save-selected-chunk!
  "Save selected chunk ID to localStorage for current file"
  []
  (when-let [id (get-selected-id)]
    (let [file-key (get-file-key)
          data (or (js/JSON.parse (or (.getItem js/localStorage selected-chunk-key) "{}")) #js {})
          _ (aset data file-key id)]
      (.setItem js/localStorage selected-chunk-key (js/JSON.stringify data)))))

(defn- load-selected-chunk
  "Load saved selected chunk ID for current file, returns nil if not found"
  []
  (try
    (let [file-key (get-file-key)
          data (js/JSON.parse (or (.getItem js/localStorage selected-chunk-key) "{}"))
          saved-id (aget data file-key)]
      (when (and saved-id (get-chunk saved-id))
        saved-id))
    (catch :default _ nil)))

(defn select-chunk! [id]
  (swap! app-state assoc :selected-id id)
  (save-selected-chunk!))

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

(defn update-chunk-transient!
  "Update a chunk with transient (non-persisted) changes.
   Does not push history or mark as modified.
   Useful for cursor position, scroll position, etc."
  [id changes]
  (swap! app-state update :chunks
         (fn [chunks]
           (mapv (fn [c]
                   (if (= (:id c) id)
                     (merge c changes)
                     c))
                 chunks))))

(defn get-cursor-pos
  "Get saved cursor position for a chunk"
  [chunk-id]
  (when-let [chunk (get-chunk chunk-id)]
    (:cursor-pos chunk)))

(defn set-cursor-pos!
  "Save cursor position for a chunk (transient, not persisted)"
  [chunk-id pos]
  (update-chunk-transient! chunk-id {:cursor-pos pos}))

;; =============================================================================
;; Discussion Management
;; =============================================================================

(defn get-discussion
  "Get the discussion for the currently selected chunk"
  []
  (when-let [chunk (get-selected-chunk)]
    (:discussion chunk)))

(defn add-comment!
  "Add a comment to the discussion of a chunk.
   comment-data should be {:text \"...\"}
   Author and timestamp are added automatically."
  [chunk-id comment-data]
  (let [comment {:author "local"  ;; In collaborative mode, this will be the username
                 :timestamp (.toISOString (js/Date.))
                 :type :comment
                 :text (:text comment-data)}]
    (push-history!)
    (swap! app-state update :chunks
           (fn [chunks]
             (mapv (fn [c]
                     (if (= (:id c) chunk-id)
                       (update c :discussion (fnil conj []) comment)
                       c))
                   chunks)))
    (mark-modified!)))

(defn add-resolved-proposal!
  "Add a resolved proposal to the discussion (migrated from inline).
   proposal-data should include :previous-text, :proposed-text, :answer, :reason"
  [chunk-id proposal-data]
  (let [entry {:author (:author proposal-data "local")
               :timestamp (.toISOString (js/Date.))
               :type :proposal
               :previous-text (:previous-text proposal-data)
               :proposed-text (:proposed-text proposal-data)
               :answer (:answer proposal-data)  ;; :accepted or :rejected
               :reason (:reason proposal-data)}]
    (push-history!)
    (swap! app-state update :chunks
           (fn [chunks]
             (mapv (fn [c]
                     (if (= (:id c) chunk-id)
                       (update c :discussion (fnil conj []) entry)
                       c))
                   chunks)))
    (mark-modified!)))

(defn clear-discussion!
  "Clear all discussion entries for a chunk (purge)"
  [chunk-id]
  (push-history!)
  (swap! app-state update :chunks
         (fn [chunks]
           (mapv (fn [c]
                   (if (= (:id c) chunk-id)
                     (assoc c :discussion [])
                     c))
                 chunks)))
  (mark-modified!))

;; =============================================================================
;; Proposal Actions (create, accept, reject with discussion migration)
;; =============================================================================

(defn create-proposal-in-chunk!
  "Create a proposal by replacing selected text in chunk content.
   Returns the new content with the proposal marker."
  [chunk-id start end proposed-text]
  (when-let [chunk (get-chunk chunk-id)]
    (let [content (:content chunk)
          new-content (insert-proposal-in-content content start end proposed-text "local")]
      (push-history!)
      (swap! app-state update :chunks
             (fn [chunks]
               (mapv (fn [c]
                       (if (= (:id c) chunk-id)
                         (assoc c :content new-content)
                         c))
                     chunks)))
      (mark-modified!)
      new-content)))

(defn update-proposal-selection!
  "Update the :sel value in a PROPOSAL annotation.
   chunk-id: the chunk containing the annotation
   original-text: the selected text in the annotation (may be trimmed)
   new-sel: 0 = original, 1 = proposed"
  [chunk-id original-text new-sel]
  (when-let [chunk (get-chunk chunk-id)]
    (let [content (:content chunk)
          ;; Pattern to find the PROPOSAL annotation - capture original text with any whitespace
          escaped-text (str/replace original-text #"[.*+?^${}()\[\]\\|]" (fn [m] (str "\\" m)))
          pattern (re-pattern (str "\\[!PROPOSAL:(\\s*" escaped-text "\\s*):P([^:]+):([A-Za-z0-9+/=]+)\\]"))
          match (re-find pattern content)]
      (when match
        (let [old-annotation (first match)
              original-with-spaces (second match)  ; Captured text with whitespace
              user (nth match 2)
              old-b64 (nth match 3)
              old-data (parse-proposal-data old-b64)]
          (when old-data
            (let [new-data (assoc old-data :sel new-sel)
                  new-b64 (encode-proposal-data (:text new-data) (:sel new-data))
                  ;; Preserve original whitespace in the new annotation
                  new-annotation (str "[!PROPOSAL:" original-with-spaces ":P" user ":" new-b64 "]")
                  new-content (str/replace content old-annotation new-annotation)]
              (push-history!)
              (swap! app-state update :chunks
                     (fn [chunks]
                       (mapv (fn [c]
                               (if (= (:id c) chunk-id)
                                 (assoc c :content new-content)
                                 c))
                             chunks)))
              (mark-modified!))))))))

(defn accept-proposal!
  "Accept a proposal annotation: apply selected text and add to discussion.
   annotation should be a parsed annotation with :type :PROPOSAL"
  [chunk-id annotation & {:keys [reason] :or {reason nil}}]
  (when-let [chunk (get-chunk chunk-id)]
    (let [content (:content chunk)
          proposed-text (decode-proposed-text (:comment annotation))
          sender (or (:proposal-from (:priority annotation)) "local")
          new-content (accept-proposal-in-content content annotation)
          ;; Create discussion entry
          discussion-entry {:author sender
                            :timestamp (.toISOString (js/Date.))
                            :type :proposal
                            :previous-text (:selected-text annotation)
                            :proposed-text proposed-text
                            :answer :accepted
                            :reason reason}]
      (push-history!)
      (swap! app-state update :chunks
             (fn [chunks]
               (mapv (fn [c]
                       (if (= (:id c) chunk-id)
                         (-> c
                             (assoc :content new-content)
                             (update :discussion (fnil conj []) discussion-entry))
                         c))
                     chunks)))
      (mark-modified!)
      new-content)))

(defn reject-proposal!
  "Reject a proposal annotation: replace marker with original-text and add to discussion.
   Options:
   - :reason - optional reason for rejection
   - :no-log - if true, don't add to discussion (for cancel without decision)"
  [chunk-id annotation & {:keys [reason no-log] :or {reason nil no-log false}}]
  (when-let [chunk (get-chunk chunk-id)]
    (let [content (:content chunk)
          proposed-text (decode-proposed-text (:comment annotation))
          sender (or (:proposal-from (:priority annotation)) "local")
          new-content (reject-proposal-in-content content annotation)
          ;; Create discussion entry (only if not suppressed)
          discussion-entry (when-not no-log
                             {:author sender
                              :timestamp (.toISOString (js/Date.))
                              :type :proposal
                              :previous-text (:selected-text annotation)
                              :proposed-text proposed-text
                              :answer :rejected
                              :reason reason})]
      (push-history!)
      (swap! app-state update :chunks
             (fn [chunks]
               (mapv (fn [c]
                       (if (= (:id c) chunk-id)
                         (-> c
                             (assoc :content new-content)
                             (cond-> discussion-entry
                               (update :discussion (fnil conj []) discussion-entry)))
                         c))
                     chunks)))
      (mark-modified!)
      new-content)))

(defn add-chunk!
  "Add a new chunk, optionally with a parent, summary, and content.
   If :id is provided, uses that ID instead of auto-generating.
   If :select? is false, doesn't select the new chunk (default true).
   Owner is automatically set to current user in collaborative mode.
   Returns nil if user doesn't have permission to create at parent-id."
  [& {:keys [id parent-id summary content select?]
      :or {id nil parent-id nil summary nil content nil select? true}}]
  ;; Check permission - collaborators can only create under aspects
  (if (and @user-role (not (can-create-chunk-at? parent-id)))
    (do
      (js/console.warn "Permission denied: collaborators can only create chunks under aspects")
      nil)
    (do
      (push-history!)
      (let [base-chunk (new-chunk :parent-id parent-id
                                  :summary (or summary "Nuovo chunk")
                                  :content (or content "")
                                  :existing-chunks (get-chunks)
                                  :owner (get-current-owner))
            ;; Override ID if provided
            chunk (if id
                    (assoc base-chunk :id id)
                    base-chunk)]
        (swap! app-state update :chunks conj chunk)
        (when select?
          (select-chunk! (:id chunk)))
        (mark-modified!)
        chunk))))

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

(defn get-ancestors
  "Get all ancestor IDs of a chunk, from immediate parent up to root.
   Returns a vector of IDs in order from closest parent to root."
  [chunk-id]
  (let [chunks (get-chunks)
        chunk-by-id (into {} (map (juxt :id identity) chunks))]
    (loop [current-id (:parent-id (chunk-by-id chunk-id))
           ancestors []]
      (if (or (nil? current-id) (not (chunk-by-id current-id)))
        ancestors
        (recur (:parent-id (chunk-by-id current-id))
               (conj ancestors current-id))))))

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

(defn- do-save-to-path!
  "Internal: save content to a specific path (with backup) - Tauri only"
  [filepath]
  (when (platform/tauri?)
    (let [content (get-file-content)]
      ;; Create backup first, then save
      (-> (versioning/create-backup! filepath)
          (.then #(platform/save-file! content (get-filename) filepath nil nil))
          (.then (fn []
                   ;; Update filepath and filename in state
                   (swap! app-state assoc :filepath filepath)
                   (let [filename (last (str/split filepath #"/"))]
                     (swap! app-state assoc :filename filename))
                   ;; Update file-info for conflict detection
                   (versioning/update-file-info! filepath content)
                   ;; Mark as clean (no unsaved changes)
                   (versioning/mark-clean!)
                   ;; Show "Salvato" then fade
                   (reset! save-status :saved)
                   (when @saved-fade-timer
                     (js/clearTimeout @saved-fade-timer))
                   (reset! saved-fade-timer
                           (js/setTimeout #(reset! save-status :idle) saved-fade-delay-ms))))
          (.catch (fn [err]
                    (js/console.error "Save error:" err)))))))

(defn save-file-as!
  "Show save dialog and save file (works on both Tauri and webapp)"
  []
  ;; Cancel any pending autosave
  (when @autosave-timer
    (js/clearTimeout @autosave-timer)
    (reset! autosave-timer nil))
  (let [default-folder (settings/get-default-folder)
        filename (get-filename)
        content (get-file-content)]
    (platform/save-file-as!
      content
      filename
      default-folder
      (fn [{:keys [success filepath filename]}]
        (when success
          ;; Update state
          (swap! app-state assoc :filepath filepath :filename filename)
          ;; In Tauri, update file-info for conflict detection
          (if (platform/tauri?)
            (when filepath
              (versioning/update-file-info! filepath content)
              (versioning/mark-clean!))
            ;; In webapp with File System Access API, mark clean
            (when (platform/has-file-handle?)
              (versioning/mark-clean!)))
          ;; Show "Salvato" then fade
          (reset! save-status :saved)
          (when @saved-fade-timer
            (js/clearTimeout @saved-fade-timer))
          (reset! saved-fade-timer
                  (js/setTimeout #(reset! save-status :idle) saved-fade-delay-ms)))))))

(defn save-file!
  "Save the current file. If filepath is known, check conflicts and save. Otherwise show save dialog."
  []
  ;; Cancel any pending autosave
  (when @autosave-timer
    (js/clearTimeout @autosave-timer)
    (reset! autosave-timer nil))
  (if (platform/tauri?)
    ;; Tauri mode: check filepath and conflicts
    (if-let [filepath (:filepath @app-state)]
      ;; File has been saved before, check for conflicts
      (-> (versioning/check-conflict)
          (.then (fn [{:keys [conflict?]}]
                   (if conflict?
                     ;; Show conflict dialog
                     (versioning/show-conflict-dialog!
                      {:on-overwrite (fn []
                                       (versioning/show-conflict-dialog! nil)
                                       (do-save-to-path! filepath))})
                     ;; No conflict, save directly
                     (do-save-to-path! filepath)))))
      ;; New file, show save dialog
      (save-file-as!))
    ;; Webapp mode: use file handle if available, otherwise show dialog
    (if (platform/has-file-handle?)
      ;; File has been saved before, save directly to the same file
      (let [content (get-file-content)
            filename (get-filename)]
        (platform/save-file!
          content filename nil nil
          (fn [{:keys [success]}]
            (when success
              (versioning/mark-clean!)
              ;; Show "Salvato" then fade
              (reset! save-status :saved)
              (when @saved-fade-timer
                (js/clearTimeout @saved-fade-timer))
              (reset! saved-fade-timer
                      (js/setTimeout #(reset! save-status :idle) saved-fade-delay-ms))))))
      ;; New file, show save dialog
      (save-file-as!))))

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

(defn export-trmd!
  "Export as .trmd file (with frontmatter) - downloads the file locally"
  []
  (let [content (serialize-file (get-chunks) (:metadata @app-state))
        filename (let [f (get-filename)]
                   (if (str/ends-with? f ".trmd") f (str f ".trmd")))
        blob (js/Blob. #js [content] #js {:type "text/plain"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.click a)
    (js/URL.revokeObjectURL url)))

(defn import-md-content!
  "Import markdown content, parsing it and appending chunks to current project.
   The content is parsed as markdown: headings become chunk summaries,
   text between headings becomes chunk content."
  [content]
  (push-history!)
  ;; Parse markdown content into chunks
  ;; Split by headings (# ## ### etc)
  (let [lines (str/split-lines content)
        ;; Find all structural chunks (non-aspect-containers) to get max numeric ID
        structural-chunks (filter #(not (contains? aspect-container-ids (:id %))) (get-chunks))
        ;; Find the highest numeric part of existing chunk IDs like "cap-1", "cap-2"
        max-num (reduce (fn [max-n chunk]
                          (if-let [[_ num-str] (re-find #"-(\d+)$" (:id chunk))]
                            (max max-n (js/parseInt num-str 10))
                            max-n))
                        0
                        structural-chunks)
        ;; Parse lines into chunks
        result (reduce
                (fn [{:keys [chunks current-summary current-lines counter]} line]
                  (if-let [[_ _hashes title] (re-find #"^(#{1,6})\s+(.+)$" line)]
                    ;; Found a heading - save previous chunk if any, start new one
                    (let [new-chunks (if (or (seq current-summary) (seq current-lines))
                                       (conj chunks {:summary (or current-summary "(importato)")
                                                     :content (str/join "\n" current-lines)})
                                       chunks)]
                      {:chunks new-chunks
                       :current-summary (str/trim title)
                       :current-lines []
                       :counter counter})
                    ;; Regular line - add to current content
                    {:chunks chunks
                     :current-summary current-summary
                     :current-lines (conj current-lines line)
                     :counter counter}))
                {:chunks []
                 :current-summary nil
                 :current-lines []
                 :counter (inc max-num)}
                lines)
        ;; Don't forget the last chunk
        final-chunks (if (or (seq (:current-summary result)) (seq (:current-lines result)))
                       (conj (:chunks result)
                             {:summary (or (:current-summary result) "(importato)")
                              :content (str/join "\n" (:current-lines result))})
                       (:chunks result))]
    ;; Add parsed chunks to the project
    (doseq [[idx chunk-data] (map-indexed vector final-chunks)]
      (let [chunk-id (str "imp-" (+ max-num idx 1))
            new-chunk (make-chunk (assoc chunk-data :id chunk-id))]
        (swap! app-state update :chunks conj new-chunk)))
    ;; Mark as modified
    (mark-modified!)
    ;; Select first imported chunk if any
    (when (seq final-chunks)
      (select-chunk! (str "imp-" (inc max-num))))))

(defn load-file-content!
  "Load content from a string (called after file is read).
   filepath is optional - if provided, enables direct Save without dialog."
  ([content filename]
   (load-file-content! content filename nil))
  ([content filename filepath]
   ;; Clear autosave to avoid confusion with localStorage version
   (clear-autosave!)
   ;; Parse the file FIRST to get all chunks including aspect containers
   (let [{:keys [metadata chunks]} (parse-file content)
         ;; Build a map of parsed chunks by ID for quick lookup
         parsed-by-id (into {} (map (juxt :id identity) chunks))]
     ;; Clear current state
     (swap! app-state assoc :chunks [] :selected-id nil :metadata metadata :filepath filepath)
     ;; Reset history
     (reset! history {:states [] :current -1 :checkpoints []})
     ;; Add aspect containers - use parsed version if available, else create empty
     (doseq [{:keys [id summary]} aspect-containers]
       (let [parsed-container (get parsed-by-id id)]
         (swap! app-state update :chunks conj
                (if parsed-container
                  parsed-container
                  (make-chunk {:id id :summary summary})))))
     ;; Add all other chunks (non-aspect-containers)
     (doseq [chunk chunks]
       (when-not (contains? aspect-container-ids (:id chunk))
         (swap! app-state update :chunks conj chunk))))
   ;; Set filename
   (set-filename! filename)
   ;; Restore saved selection or select first non-container chunk
   (let [saved-id (load-selected-chunk)
         structural (filter #(not (contains? aspect-container-ids (:id %))) (get-chunks))
         target-id (or saved-id
                       (when (seq structural) (:id (first structural))))]
     (when target-id
       (select-chunk! target-id)))
   ;; Push initial state to history
   (push-history!)
   (reset! save-status :idle)
   ;; Update file-info for conflict detection
   (if filepath
     (versioning/update-file-info! filepath content)
     (versioning/clear-file-info!))
   ;; Mark as clean (no unsaved changes)
   (versioning/mark-clean!)
   ;; Force editor to refresh (in case same chunk ID but different content)
   (events/refresh-editor!)))

(defn reload-from-remote!
  "Reload content from server during polling.
   Preserves current selection and doesn't trigger modified callback."
  [content filename]
  (js/console.log "reload-from-remote! called, content length:" (count content))
  (let [current-selected-id (get-selected-id)
        saved-callback @on-modified-callback]
    ;; Temporarily disable modified callback to avoid sync loop
    (reset! on-modified-callback nil)
    ;; Parse the new content
    (let [{:keys [metadata chunks]} (parse-file content)
          ;; Get current aspect containers
          current-aspects (filter #(contains? aspect-container-ids (:id %)) (get-chunks))
          parsed-ids (set (map :id chunks))
          ;; Keep aspect containers that aren't in parsed content
          extra-aspects (remove #(contains? parsed-ids (:id %)) current-aspects)
          final-chunks (vec (concat extra-aspects chunks))]
      (js/console.log "Updating chunks, new count:" (count final-chunks))
      ;; Clear selection first to force re-render
      (swap! app-state assoc :selected-id nil)
      ;; Update state with new chunks
      (swap! app-state assoc
             :chunks final-chunks
             :metadata metadata))
    ;; Set filename
    (set-filename! filename)
    ;; Restore selection
    (let [new-chunks (get-chunks)
          structural (filter #(not (contains? aspect-container-ids (:id %))) new-chunks)
          new-selected (if (and current-selected-id
                                (some #(= (:id %) current-selected-id) new-chunks))
                         current-selected-id
                         (when (seq structural)
                           (:id (first structural))))]
      (when new-selected
        (swap! app-state assoc :selected-id new-selected)))
    ;; Reset history with new state
    (reset! history {:states [] :current -1 :checkpoints []})
    (push-history!)
    (reset! save-status :idle)
    ;; Restore callback
    (reset! on-modified-callback saved-callback)
    ;; Force editor to refresh (triggers CodeMirror re-render)
    (events/refresh-editor!)
    ;; Force Reagent to flush pending updates
    (r/flush)))

(defn open-file!
  "Show open dialog and load selected file (works on both Tauri and webapp)"
  []
  (let [default-folder (settings/get-default-folder)]
    (platform/open-file!
      default-folder
      (fn [{:keys [content filename filepath]}]
        (when content
          (load-file-content! content filename filepath))))))

(defn reload-file!
  "Reload the current file from disk (Tauri only)"
  []
  (when (platform/tauri?)
    (when-let [filepath (:filepath @app-state)]
      (platform/read-file!
        filepath
        (fn [{:keys [content filepath filename error]}]
          (if error
            (js/console.error "Reload file error:" error)
            (load-file-content! content filename filepath)))))))

(defn load-version-copy!
  "Load content as a new unsaved document (for 'Open copy' from versions)"
  [content original-filename]
  (let [new-filename (str "copia-" original-filename)]
    (load-file-content! content new-filename nil)))

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

(defn has-children?
  "Check if a chunk has any children"
  [chunk-id]
  (some #(= (:parent-id %) chunk-id) (get-chunks)))

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

(defn is-under-aspects?
  "Check if a parent-id is under the aspects hierarchy (can create children there)"
  [parent-id]
  (or (is-aspect-container? parent-id)
      (when-let [parent (get-chunk parent-id)]
        (is-aspect-chunk? parent))))

(defn can-create-chunk-at?
  "Check if current user can create a chunk at the given parent-id.
   - Project owners can create anywhere
   - Collaborators can create:
     1. Under aspects hierarchy (always)
     2. Under chunks they own (transferred ownership)"
  [parent-id]
  (or (is-project-owner?)
      (is-under-aspects? parent-id)
      ;; Collaborator can create under chunks they own
      (when parent-id
        (let [parent (get-chunk parent-id)
              parent-owner (:owner parent)]
          (and parent-owner
               (not= "local" parent-owner)
               (= parent-owner @current-user))))))

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
  "Return chunks that reference this aspect id in their :aspects set
   OR via inline [@id] links in content (excludes self)"
  [aspect-id]
  (let [inline-pattern (js/RegExp. (str "\\[@" aspect-id "\\]") "i")]
    (filter #(and (not= (:id %) aspect-id)
                  (or (contains? (:aspects %) aspect-id)
                      (and (:content %)
                           (.test inline-pattern (:content %)))))
            (get-chunks))))

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

(defn get-aspect-priority
  "Get priority of an aspect (default 0 if not set)"
  [aspect-id]
  (let [chunk (get-chunk aspect-id)]
    (or (:priority chunk) 0)))

(defn set-aspect-priority!
  "Set priority for an aspect (0-10)"
  [aspect-id priority]
  (let [clamped (max 0 (min 10 (or priority 0)))]
    (update-chunk! aspect-id {:priority (when (pos? clamped) clamped)})))

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
  (cond
    (has-children? id)
    {:error :has-children}

    (pos? (aspect-usage-count id))
    {:error (str "Impossibile cancellare: usato in " (aspect-usage-count id) " chunk")}

    :else
    (do
      (delete-chunk! id)
      {:ok true})))

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
  ;; Clear webapp file handle for new project
  (platform/clear-file-handle!)
  ;; Reset filepath (new project has no file yet)
  (swap! app-state assoc :filepath nil)
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
  (reset! save-status :idle)
  ;; Clear versioning info and mark as dirty (new file not saved yet)
  (versioning/clear-file-info!)
  (versioning/mark-dirty!))

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
  (let [old-title (get-in @app-state [:metadata :title])]
    (swap! app-state update :metadata merge changes)
    ;; If title changed, notify callback (for server mode sync)
    (when (and (:title changes)
               (not= (:title changes) old-title)
               @on-title-changed-callback)
      (@on-title-changed-callback (:title changes)))
    (mark-modified!)))

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
