(ns tramando.annotations
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
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

(defn parse-priority
  "Parse priority string. Returns:
   - number if it's a numeric priority
   - :AI or :AI-DONE for AI annotations
   - the string itself for text priorities (e.g., 'bassa', 'media', 'alta')
   - nil if empty."
  [s]
  (when (and s (seq (str/trim s)))
    (let [trimmed (str/trim s)]
      (cond
        (= trimmed "AI") :AI
        (= trimmed "AI-DONE") :AI-DONE
        (re-matches #"-?\d+\.?\d*" trimmed) (js/parseFloat trimmed)
        :else trimmed))))

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
   AI annotations come after numeric priorities, items without priority go to the end."
  [annotations]
  (sort-by (fn [a]
             (let [p (:priority a)]
               (cond
                 (number? p) [0 p]      ; numeric priority: sort first
                 (= p :AI) [1 0]        ; AI pending: after numeric
                 (= p :AI-DONE) [1 1]   ; AI done: after AI pending
                 :else [2 0])))         ; no priority: sort last
           annotations))

(defn is-ai-annotation?
  "Check if an annotation is an AI annotation"
  [annotation]
  (let [p (:priority annotation)]
    (or (= p :AI) (= p :AI-DONE))))

(defn is-ai-done?
  "Check if an annotation is a completed AI annotation with alternatives"
  [annotation]
  (= (:priority annotation) :AI-DONE))

(defn parse-ai-data
  "Parse Base64-encoded EDN data from an AI-DONE annotation comment.
   Returns {:alts [...] :sel n} or nil."
  [encoded-string]
  (when (and encoded-string (not (str/blank? encoded-string)))
    (try
      (let [edn-str (-> encoded-string
                        js/atob
                        js/decodeURIComponent)
            data (reader/read-string edn-str)]
        (when (and (map? data) (:alts data))
          data))
      (catch :default _e
        nil))))

(defn get-ai-alternatives
  "Get alternatives from an AI-DONE annotation.
   Returns vector of alternative strings or []."
  [annotation]
  (when (is-ai-done? annotation)
    (let [data (parse-ai-data (:comment annotation))]
      (or (:alts data) []))))

(defn get-ai-selection
  "Get the selected alternative index from an AI-DONE annotation.
   Returns 0 if no selection."
  [annotation]
  (when (is-ai-done? annotation)
    (let [data (parse-ai-data (:comment annotation))]
      (or (:sel data) 0))))

(defn get-selected-alternative-text
  "Get the text of the selected alternative, or nil if no selection."
  [annotation]
  (when (is-ai-done? annotation)
    (let [data (parse-ai-data (:comment annotation))
          sel (or (:sel data) 0)
          alts (or (:alts data) [])]
      (when (and (pos? sel) (<= sel (count alts)))
        (nth alts (dec sel))))))

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
;; Annotation Stripping (for export/reading mode)
;; =============================================================================

(defn strip-annotations
  "Remove annotation markup, keeping only the selected text.
   [!TODO:testo:1:commento] becomes just 'testo'
   For AI-DONE annotations with selection, shows the selected alternative."
  [content]
  (if content
    ;; First pass: handle AI-DONE with selected alternative (Base64 encoded EDN)
    (let [ai-done-pattern #"\[!NOTE:([^:]*):AI-DONE:([A-Za-z0-9+/=]+)\]"
          content-with-ai (str/replace content ai-done-pattern
                                       (fn [[_ selected-text b64-str]]
                                         (let [data (parse-ai-data b64-str)
                                               sel (or (:sel data) 0)
                                               alts (or (:alts data) [])]
                                           (if (and (pos? sel) (<= sel (count alts)))
                                             (nth alts (dec sel))
                                             selected-text))))]
      ;; Second pass: strip remaining annotations normally
      (str/replace content-with-ai annotation-pattern "$2"))
    ""))
