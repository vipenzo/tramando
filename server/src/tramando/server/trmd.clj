(ns tramando.server.trmd
  "Parser and serializer for .trmd format.

   Format specification:
   - YAML frontmatter: ---\\n key: value \\n---
   - Chunks: [C:id\"summary\"][@aspect1][@aspect2][#owner:user]
   - Indentation (2 spaces) determines parent/child hierarchy
   - Discussion blocks: [!DISCUSSION:base64data]
   - Collaborative attributes: [#owner:x] [#prev-owner:x] [#expires:x] [#priority:N]"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cheshire.core :as json])
  (:import [java.util Base64]
           [java.net URLEncoder URLDecoder]))

;; =============================================================================
;; Regex Patterns
;; =============================================================================

(def ^:private chunk-header-re
  "Match chunk header: [C:id\"summary\"]...rest..."
  #"^\[C:([^\]\"]+)\"([^\"]*)\"\](.*)$")

(def ^:private aspect-re
  "Match aspect references: [@aspect-id]"
  #"\[@([^\]]+)\]")

(def ^:private owner-re #"\[#owner:([^\]]+)\]")
(def ^:private prev-owner-re #"\[#prev-owner:([^\]]+)\]")
(def ^:private expires-re #"\[#expires:([^\]]+)\]")
(def ^:private priority-re #"\[#priority:(\d+)\]")
(def ^:private discussion-re #"\[!DISCUSSION:([^\]]+)\]")

;; =============================================================================
;; Base64 Helpers
;; =============================================================================

(defn- encode-base64 [s]
  (.encodeToString (Base64/getEncoder) (.getBytes (URLEncoder/encode s "UTF-8") "UTF-8")))

(defn- decode-base64 [s]
  (try
    (URLDecoder/decode (String. (.decode (Base64/getDecoder) s) "UTF-8") "UTF-8")
    (catch Exception _ nil)))

;; =============================================================================
;; Discussion Encoding/Decoding
;; =============================================================================

(defn- encode-discussion [discussion]
  (when (seq discussion)
    (encode-base64 (json/generate-string discussion))))

(defn- decode-discussion [base64-str]
  (when-let [json-str (decode-base64 base64-str)]
    (try
      (json/parse-string json-str true)
      (catch Exception _ []))))

(defn- extract-discussion-from-content
  "Extract [!DISCUSSION:...] block from content"
  [content]
  (if-let [[full-match base64-data] (re-find discussion-re content)]
    {:content (str/trim (str/replace content full-match ""))
     :discussion (or (decode-discussion base64-data) [])}
    {:content content
     :discussion []}))

;; =============================================================================
;; Parsing
;; =============================================================================

(defn- count-indent
  "Count leading spaces (2 spaces = 1 level)"
  [line]
  (let [spaces (count (re-find #"^  *" (or line "")))]
    (quot spaces 2)))

(defn- parse-header
  "Parse a chunk header line"
  [line]
  (when-let [[_ id summary rest] (re-matches chunk-header-re (str/trim (or line "")))]
    (let [aspects (->> (re-seq aspect-re rest)
                       (map second)
                       set)
          owner (second (re-find owner-re rest))
          prev-owner (second (re-find prev-owner-re rest))
          expires (second (re-find expires-re rest))
          priority-str (second (re-find priority-re rest))
          priority (when priority-str (Integer/parseInt priority-str))]
      {:id id
       :summary summary
       :aspects aspects
       :owner (or owner "local")
       :previous-owner prev-owner
       :ownership-expires expires
       :priority priority})))

(defn make-chunk
  "Create a chunk map with defaults"
  [{:keys [id summary content parent-id aspects ordered-refs priority
           owner previous-owner ownership-expires discussion]
    :or {summary "" content "" parent-id nil aspects #{} ordered-refs []
         priority nil owner "local" previous-owner nil ownership-expires nil
         discussion []}}]
  {:id id
   :summary summary
   :content content
   :parent-id parent-id
   :aspects (set aspects)
   :ordered-refs (vec ordered-refs)
   :priority priority
   :owner owner
   :previous-owner previous-owner
   :ownership-expires ownership-expires
   :discussion (vec discussion)})

(defn- finalize-chunk [chunk content-lines]
  (when chunk
    (let [raw-content (str/trim (str/join "\n" content-lines))
          {:keys [content discussion]} (extract-discussion-from-content raw-content)]
      (assoc chunk :content content :discussion discussion))))

(defn- parse-chunks
  "Parse chunks from content (without frontmatter)"
  [text]
  (let [lines (str/split-lines (or text ""))]
    (loop [lines lines
           chunks []
           current-chunk nil
           content-lines []
           indent-stack []]
      (if (empty? lines)
        (if-let [final-chunk (finalize-chunk current-chunk content-lines)]
          (conj chunks final-chunk)
          chunks)
        (let [line (first lines)
              indent (count-indent line)
              header (parse-header line)]
          (if header
            (let [chunks (if-let [prev-chunk (finalize-chunk current-chunk content-lines)]
                           (conj chunks prev-chunk)
                           chunks)
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
                                  :owner (:owner header)
                                  :previous-owner (:previous-owner header)
                                  :ownership-expires (:ownership-expires header)})
                     []
                     new-stack))
            (recur (rest lines)
                   chunks
                   current-chunk
                   (conj content-lines (str/trim line))
                   indent-stack)))))))

(def default-metadata
  {:title ""
   :author ""
   :language "it"
   :year nil
   :isbn ""
   :publisher ""
   :custom {}})

(defn- parse-yaml-value [s]
  "Parse a YAML value, removing surrounding quotes if present"
  (let [trimmed (str/trim (or s ""))]
    (if (and (str/starts-with? trimmed "\"")
             (str/ends-with? trimmed "\""))
      (subs trimmed 1 (dec (count trimmed)))
      trimmed)))

(defn- parse-yaml-frontmatter
  "Extract YAML frontmatter from text"
  [text]
  (if (str/starts-with? (str/trim (or text "")) "---")
    (let [trimmed (str/trim text)
          after-first (subs trimmed 3)
          end-idx (str/index-of after-first "\n---")]
      (if end-idx
        (let [yaml-str (str/trim (subs after-first 0 end-idx))
              remaining (str/trim (subs after-first (+ end-idx 4)))
              ;; Simple YAML parsing
              lines (str/split-lines yaml-str)
              parsed (reduce
                       (fn [acc line]
                         (let [trimmed-line (str/trim line)]
                           (if (and (not (str/blank? trimmed-line))
                                    (str/includes? trimmed-line ":"))
                             (let [colon-idx (str/index-of trimmed-line ":")
                                   k (keyword (str/trim (subs trimmed-line 0 colon-idx)))
                                   v (parse-yaml-value (subs trimmed-line (inc colon-idx)))]
                               ;; Handle year as integer
                               (if (= k :year)
                                 (assoc acc k (try (Integer/parseInt v) (catch Exception _ nil)))
                                 (assoc acc k v)))
                             acc)))
                       {}
                       lines)]
          {:metadata (merge default-metadata parsed)
           :content remaining})
        {:metadata default-metadata :content text}))
    {:metadata default-metadata :content text}))

(defn parse-trmd
  "Parse a .trmd file into {:metadata {...} :chunks [...]}"
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
        priority (:priority chunk)
        priority-str (when (and priority (pos? priority))
                       (str "[#priority:" priority "]"))
        owner (:owner chunk)
        owner-str (when (and owner (not= owner "local"))
                    (str "[#owner:" owner "]"))
        prev-owner-str (when (:previous-owner chunk)
                         (str "[#prev-owner:" (:previous-owner chunk) "]"))
        expires-str (when (:ownership-expires chunk)
                      (str "[#expires:" (:ownership-expires chunk) "]"))
        collab-str (str priority-str owner-str prev-owner-str expires-str)
        header (str indent "[C:" (:id chunk) "\"" (:summary chunk) "\"]" aspects-str collab-str)
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

(defn serialize-trmd
  "Serialize chunks with metadata to .trmd format"
  [metadata chunks]
  (let [frontmatter (serialize-metadata metadata)
        content (serialize-chunks chunks)]
    (str frontmatter content)))

;; =============================================================================
;; Chunk Operations
;; =============================================================================

(defn find-chunk
  "Find a chunk by ID"
  [chunks id]
  (first (filter #(= (:id %) id) chunks)))

(defn add-chunk
  "Add a new chunk to the list"
  [chunks chunk]
  (conj (vec chunks) chunk))

(defn remove-chunk
  "Remove a chunk by ID. Also removes references in other chunks."
  [chunks id]
  (->> chunks
       ;; Remove the chunk itself
       (remove #(= (:id %) id))
       ;; Remove children (chunks with this as parent)
       (remove #(= (:parent-id %) id))
       ;; Clean up aspect references
       (mapv (fn [c]
               (update c :aspects disj id)))
       vec))

(defn update-chunk
  "Update a chunk by ID with the given changes"
  [chunks id changes]
  (mapv (fn [c]
          (if (= (:id c) id)
            (merge c changes)
            c))
        chunks))

;; =============================================================================
;; ID Generation
;; =============================================================================

(def ^:private aspect-container-ids
  #{"personaggi" "luoghi" "temi" "sequenze" "timeline"})

(defn- id-prefix-for-parent [parent-id]
  (case parent-id
    "personaggi" "pers"
    "luoghi" "luogo"
    "temi" "tema"
    "sequenze" "seq"
    "timeline" "time"
    nil "cap"
    "scene"))

(defn- extract-number [id prefix]
  (when id
    (let [pattern (re-pattern (str "^" prefix "-(\\d+)$"))]
      (when-let [[_ num] (re-matches pattern id)]
        (Integer/parseInt num)))))

(defn- next-number-for-prefix [prefix chunks]
  (let [existing-numbers (->> chunks
                              (map :id)
                              (keep #(extract-number % prefix))
                              set)]
    (loop [n 1]
      (if (contains? existing-numbers n)
        (recur (inc n))
        n))))

(defn generate-id
  "Generate a readable ID based on parent category"
  ([chunks] (generate-id chunks nil))
  ([chunks parent-id]
   (let [prefix (id-prefix-for-parent parent-id)
         n (next-number-for-prefix prefix chunks)]
     (str prefix "-" n))))

(defn is-aspect-container? [id]
  (contains? aspect-container-ids id))

(defn is-aspect?
  "Check if a chunk is an aspect (child of an aspect container)"
  [chunk]
  (contains? aspect-container-ids (:parent-id chunk)))

;; =============================================================================
;; Diff Operations
;; =============================================================================

(defn find-changed-chunks
  "Compare two sets of chunks and return info about what changed.
   Returns {:modified [{:id ... :summary ...}]
            :added [{:id ... :summary ...}]
            :removed [{:id ... :summary ...}]}"
  [old-chunks new-chunks]
  (let [old-by-id (into {} (map (juxt :id identity) old-chunks))
        new-by-id (into {} (map (juxt :id identity) new-chunks))
        old-ids (set (keys old-by-id))
        new-ids (set (keys new-by-id))
        ;; Find added/removed
        added-ids (set/difference new-ids old-ids)
        removed-ids (set/difference old-ids new-ids)
        ;; Find modified (same id but different content/summary)
        common-ids (set/intersection old-ids new-ids)
        modified-ids (filter (fn [id]
                               (let [old-chunk (get old-by-id id)
                                     new-chunk (get new-by-id id)]
                                 (or (not= (:content old-chunk) (:content new-chunk))
                                     (not= (:summary old-chunk) (:summary new-chunk)))))
                             common-ids)]
    {:modified (mapv (fn [id]
                       (let [chunk (get new-by-id id)]
                         {:id id :summary (:summary chunk)}))
                     modified-ids)
     :added (mapv (fn [id]
                    (let [chunk (get new-by-id id)]
                      {:id id :summary (:summary chunk)}))
                  added-ids)
     :removed (mapv (fn [id]
                      (let [chunk (get old-by-id id)]
                        {:id id :summary (:summary chunk)}))
                    removed-ids)}))
