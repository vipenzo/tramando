(ns tramando.annotations
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [tramando.model :as model]))

;; =============================================================================
;; Annotation State
;; =============================================================================

(defonce show-markup? (r/atom false))

;; =============================================================================
;; Annotation Format - EDN
;; =============================================================================

;; Format: [!TYPE{:text "selected" :priority 5 :comment "note" :author "user"}]
;;
;; Common fields:
;;   :text     - the selected/annotated text (required)
;;   :author   - who created the annotation (optional)
;;   :priority - numeric priority (optional)
;;   :comment  - comment text (optional)
;;
;; TODO/NOTE/FIX specific:
;;   :ai       - :pending or :done for AI annotations (NOTE only)
;;   :alts     - vector of alternatives for AI-DONE (NOTE only)
;;   :sel      - selected alternative index for AI-DONE (NOTE only)
;;
;; PROPOSAL specific:
;;   :proposed - the proposed replacement text (required)
;;   :from     - who made the proposal (required)
;;   :sel      - 0 = show original, 1 = show proposed (default 0)

;; =============================================================================
;; EDN Parsing
;; =============================================================================

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
            escape
            (recur (inc i) depth in-string false)

            (= c \\)
            (recur (inc i) depth in-string true)

            (= c \")
            (recur (inc i) depth (not in-string) false)

            in-string
            (recur (inc i) depth in-string false)

            (= c \{)
            (recur (inc i) (inc depth) in-string false)

            (= c \})
            (if (= depth 1)
              i
              (recur (inc i) (dec depth) in-string false))

            :else
            (recur (inc i) depth in-string false)))))))

(defn- parse-edn-annotation
  "Parse a single EDN annotation at the given position.
   Returns {:type :TODO/:NOTE/:FIX/:PROPOSAL :data {...} :start int :end int} or nil."
  [content pos]
  (when (and content
             (>= pos 0)
             (< (+ pos 2) (count content))
             (= (subs content pos (+ pos 2)) "[!"))
    (let [type-end (str/index-of content "{" pos)]
      (when (and type-end (< type-end (+ pos 12)))
        (let [type-str (subs content (+ pos 2) type-end)
              brace-end (find-balanced-brace content type-end)]
          (when (and brace-end
                     (< brace-end (count content))
                     (= (nth content (inc brace-end)) \]))
            (let [edn-str (subs content type-end (inc brace-end))
                  end-pos (+ brace-end 2)]
              (try
                (let [data (reader/read-string edn-str)]
                  (when (map? data)
                    {:type (keyword type-str)
                     :data data
                     :start pos
                     :end end-pos}))
                (catch :default _ nil)))))))))

(defn parse-annotations
  "Parse all annotations from content.
   Returns vector of {:type :TODO/:NOTE/:FIX/:PROPOSAL :data {...} :start int :end int}"
  [content]
  (when content
    (let [pattern #"\[!(TODO|NOTE|FIX|PROPOSAL)\{"]
      (loop [remaining content
             offset 0
             results []]
        (if-let [match (re-find pattern remaining)]
          (let [match-str (first match)
                match-idx (str/index-of remaining match-str)
                abs-start (+ offset match-idx)]
            (if-let [parsed (parse-edn-annotation content abs-start)]
              (recur (subs content (:end parsed))
                     (:end parsed)
                     (conj results parsed))
              (recur (subs remaining (inc match-idx))
                     (+ offset match-idx 1)
                     results)))
          results)))))

;; =============================================================================
;; Annotation Accessors
;; =============================================================================

(defn get-text
  "Get the selected/annotated text from an annotation."
  [annotation]
  (get-in annotation [:data :text]))

(defn get-author
  "Get the author of an annotation."
  [annotation]
  (get-in annotation [:data :author]))

(defn get-priority
  "Get the priority of an annotation."
  [annotation]
  (get-in annotation [:data :priority]))

(defn get-comment
  "Get the comment of an annotation."
  [annotation]
  (get-in annotation [:data :comment]))

(defn is-ai-annotation?
  "Check if an annotation is an AI annotation."
  [annotation]
  (some? (get-in annotation [:data :ai])))

(defn is-ai-pending?
  "Check if an annotation is a pending AI annotation."
  [annotation]
  (= (get-in annotation [:data :ai]) :pending))

(defn is-ai-done?
  "Check if an annotation is a completed AI annotation."
  [annotation]
  (= (get-in annotation [:data :ai]) :done))

(defn is-proposal?
  "Check if an annotation is a proposal."
  [annotation]
  (= (:type annotation) :PROPOSAL))

(defn get-proposal-from
  "Get who made the proposal."
  [annotation]
  (when (is-proposal? annotation)
    (get-in annotation [:data :from])))

(defn get-proposed-text
  "Get the proposed replacement text."
  [annotation]
  (when (is-proposal? annotation)
    (get-in annotation [:data :proposed])))

(defn get-proposal-selection
  "Get which text is selected (0=original, 1=proposed)."
  [annotation]
  (when (is-proposal? annotation)
    (or (get-in annotation [:data :sel]) 0)))

(defn get-proposal-display-text
  "Get the text to display based on current selection."
  [annotation]
  (when (is-proposal? annotation)
    (if (zero? (get-proposal-selection annotation))
      (get-text annotation)
      (get-proposed-text annotation))))

(defn get-ai-alternatives
  "Get alternatives from an AI-DONE annotation."
  [annotation]
  (when (is-ai-done? annotation)
    (or (get-in annotation [:data :alts]) [])))

(defn get-ai-selection
  "Get selected alternative index (0=original, 1+=alternative)."
  [annotation]
  (when (is-ai-done? annotation)
    (or (get-in annotation [:data :sel]) 0)))

(defn get-selected-alternative-text
  "Get the text of the selected alternative."
  [annotation]
  (when (is-ai-done? annotation)
    (let [sel (get-ai-selection annotation)
          alts (get-ai-alternatives annotation)]
      (when (and (pos? sel) (<= sel (count alts)))
        (nth alts (dec sel))))))

;; =============================================================================
;; Annotation Creation
;; =============================================================================

(defn make-annotation
  "Create an annotation string in EDN format.
   Type is :TODO, :NOTE, :FIX, or :PROPOSAL.
   Data is a map with :text and optional :author, :priority, :comment, etc."
  [type data]
  (str "[!" (name type) (pr-str data) "]"))

(defn make-todo
  "Create a TODO annotation."
  [text & {:keys [author priority comment]}]
  (make-annotation :TODO
                   (cond-> {:text text}
                     author (assoc :author author)
                     priority (assoc :priority priority)
                     comment (assoc :comment comment))))

(defn make-note
  "Create a NOTE annotation."
  [text & {:keys [author priority comment]}]
  (make-annotation :NOTE
                   (cond-> {:text text}
                     author (assoc :author author)
                     priority (assoc :priority priority)
                     comment (assoc :comment comment))))

(defn make-fix
  "Create a FIX annotation."
  [text & {:keys [author priority comment]}]
  (make-annotation :FIX
                   (cond-> {:text text}
                     author (assoc :author author)
                     priority (assoc :priority priority)
                     comment (assoc :comment comment))))

(defn make-proposal
  "Create a PROPOSAL annotation."
  [original-text proposed-text from & {:keys [author sel]}]
  (make-annotation :PROPOSAL
                   (cond-> {:text original-text
                            :proposed proposed-text
                            :from from
                            :sel (or sel 0)}
                     author (assoc :author author))))

(defn make-ai-pending
  "Create a pending AI annotation (NOTE with :ai :pending)."
  [text & {:keys [author]}]
  (make-annotation :NOTE
                   (cond-> {:text text :ai :pending}
                     author (assoc :author author))))

(defn make-ai-done
  "Create a completed AI annotation with alternatives."
  [text alternatives & {:keys [author sel]}]
  (make-annotation :NOTE
                   (cond-> {:text text
                            :ai :done
                            :alts (vec alternatives)
                            :sel (or sel 0)}
                     author (assoc :author author))))

;; =============================================================================
;; Chunk-level Functions
;; =============================================================================

(defn get-chunk-annotations
  "Get all annotations from a specific chunk."
  [chunk-id]
  (when-let [chunk (first (filter #(= (:id %) chunk-id) (model/get-chunks)))]
    (map #(assoc % :chunk-id chunk-id)
         (parse-annotations (:content chunk)))))

(defn- sort-by-priority
  "Sort annotations by priority."
  [annotations]
  (sort-by (fn [a]
             (let [p (get-priority a)
                   ai (get-in a [:data :ai])]
               (cond
                 (number? p) [0 p]
                 (= ai :pending) [1 0]
                 (= ai :done) [1 1]
                 :else [2 0])))
           annotations))

(defn get-all-annotations
  "Get all annotations from all chunks, grouped by type and sorted by priority."
  []
  (let [chunks (model/get-chunks)
        all-annotations (mapcat (fn [chunk]
                                  (map #(assoc % :chunk-id (:id chunk))
                                       (parse-annotations (:content chunk))))
                                chunks)]
    {:TODO (sort-by-priority (filterv #(= (:type %) :TODO) all-annotations))
     :NOTE (sort-by-priority (filterv #(= (:type %) :NOTE) all-annotations))
     :FIX (sort-by-priority (filterv #(= (:type %) :FIX) all-annotations))
     :PROPOSAL (filterv #(= (:type %) :PROPOSAL) all-annotations)}))

(defn count-annotations
  "Count total number of annotations."
  []
  (let [{:keys [TODO NOTE FIX PROPOSAL]} (get-all-annotations)]
    (+ (count TODO) (count NOTE) (count FIX) (count PROPOSAL))))

(defn get-chunk-path
  "Get a readable path for a chunk."
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
  "Remove annotation markup, keeping only the display text.
   For proposals, shows the selected text (original or proposed).
   For AI-DONE, shows the selected alternative."
  [content]
  (if content
    (let [annotations (parse-annotations content)]
      (if (empty? annotations)
        content
        ;; Replace from end to start to preserve positions
        (reduce (fn [text ann]
                  (let [display-text (cond
                                       (is-proposal? ann)
                                       (get-proposal-display-text ann)

                                       (is-ai-done? ann)
                                       (or (get-selected-alternative-text ann)
                                           (get-text ann))

                                       :else
                                       (get-text ann))]
                    (str (subs text 0 (:start ann))
                         display-text
                         (subs text (:end ann)))))
                content
                (reverse annotations))))
    ""))
