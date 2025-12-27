(ns tramando.annotations
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]))

;; =============================================================================
;; Annotation State
;; =============================================================================

;; Toggle for showing/hiding markup (false = reading mode, true = markup mode)
(defonce show-markup? (r/atom false))

;; =============================================================================
;; Annotation Parsing
;; =============================================================================

;; Syntax: [!TYPE:selected text:priority:comment]
;; - priority: integer or decimal, can be empty
;; - comment: can be empty
;; Regex: \[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\]]*)\]
(def annotation-pattern #"\[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\]]*)\]")

(defn- parse-priority
  "Parse priority string to number. Returns nil if empty or invalid."
  [s]
  (when (and s (seq (str/trim s)))
    (let [trimmed (str/trim s)]
      (when (re-matches #"-?\d+\.?\d*" trimmed)
        (js/parseFloat trimmed)))))

(defn parse-annotations
  "Parse annotations from text content.
   Returns a vector of {:type :TODO/:NOTE/:FIX, :selected-text string, :priority number/nil, :comment string, :start int, :end int}"
  [content]
  (when content
    (loop [remaining content
           offset 0
           results []]
      (if-let [match (re-find annotation-pattern remaining)]
        (let [[full-match type-str selected-text priority-str comment-text] match
              start (+ offset (str/index-of remaining full-match))
              end (+ start (count full-match))]
          (recur (subs remaining (- end offset))
                 end
                 (conj results {:type (keyword type-str)
                                :selected-text (str/trim selected-text)
                                :priority (parse-priority priority-str)
                                :comment (str/trim (or comment-text ""))
                                :start start
                                :end end})))
        results))))

(defn get-chunk-annotations
  "Get all annotations from a specific chunk"
  [chunk-id]
  (when-let [chunk (first (filter #(= (:id %) chunk-id) (model/get-chunks)))]
    (map #(assoc % :chunk-id chunk-id)
         (parse-annotations (:content chunk)))))

(defn- sort-by-priority
  "Sort annotations by priority. Items with priority come first (ascending),
   items without priority go to the end."
  [annotations]
  (sort-by (fn [a]
             (if-let [p (:priority a)]
               [0 p]  ; has priority: sort first, by priority value
               [1 0])) ; no priority: sort last
           annotations))

(defn get-all-annotations
  "Get all annotations from all chunks, grouped by type and sorted by priority"
  []
  (let [chunks (model/get-chunks)
        all-annotations (mapcat (fn [chunk]
                                  (map #(assoc % :chunk-id (:id chunk))
                                       (parse-annotations (:content chunk))))
                                chunks)]
    {:TODO (sort-by-priority (filterv #(= (:type %) :TODO) all-annotations))
     :NOTE (sort-by-priority (filterv #(= (:type %) :NOTE) all-annotations))
     :FIX (sort-by-priority (filterv #(= (:type %) :FIX) all-annotations))}))

(defn count-annotations
  "Count total number of annotations"
  []
  (let [{:keys [TODO NOTE FIX]} (get-all-annotations)]
    (+ (count TODO) (count NOTE) (count FIX))))

(defn get-chunk-path
  "Get a readable path for a chunk (e.g., 'cap1/scena1' or 'mario')"
  [chunk-id]
  (let [chunks (model/get-chunks)
        chunk (first (filter #(= (:id %) chunk-id) chunks))
        parent-id (:parent-id chunk)
        parent (when parent-id
                 (first (filter #(= (:id %) parent-id) chunks)))]
    (if (and parent (not (model/is-aspect-container? parent-id)))
      (str (:id parent) "/" (:id chunk))
      (:id chunk))))

;; =============================================================================
;; Annotation Stripping (for export)
;; =============================================================================

(defn strip-annotations
  "Remove annotation markup, keeping only the selected text.
   [!TODO:testo:1:commento] becomes just 'testo'"
  [content]
  (if content
    (str/replace content annotation-pattern "$2")
    ""))
