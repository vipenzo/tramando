(ns tramando.export-common
  "Common utilities for export modules (PDF, DOCX).
   Contains shared markdown parsing and document structure traversal."
  (:require [tramando.model :as model]
            [tramando.annotations :as annotations]
            [clojure.string :as str]))

;; =============================================================================
;; Inline Formatting Parsing
;; =============================================================================

(defn parse-inline-formatting
  "Parse inline **bold** and *italic* formatting.
   Returns a vector of segments, each being either:
   - a plain string
   - {:text \"...\" :bold true}
   - {:text \"...\" :italic true}"
  [text]
  (if (str/blank? text)
    []
    (loop [remaining text
           result []]
      (if (str/blank? remaining)
        result
        (if-let [match (re-find #"^(.*?)(\*\*([^*]+)\*\*|\*([^*]+)\*)(.*)" remaining)]
          (let [[_ before _ bold-text italic-text after] match
                before-text (when-not (str/blank? before) before)
                formatted (cond
                            bold-text {:text bold-text :bold true}
                            italic-text {:text italic-text :italic true}
                            :else nil)]
            (recur after
                   (cond-> result
                     before-text (conj before-text)
                     formatted (conj formatted))))
          ;; No more matches, add remaining text
          (conj result remaining))))))

;; =============================================================================
;; Block-level Parsing
;; =============================================================================

(defn- blockquote-line?
  "Check if a line is a blockquote (> text)"
  [line]
  (str/starts-with? line "> "))

(defn- strip-blockquote
  "Remove the > prefix from a blockquote line"
  [line]
  (subs line 2))

(defn parse-markdown-blocks
  "Parse markdown content into structured blocks.
   Each line becomes a separate paragraph, except:
   - Empty lines create :empty blocks for spacing
   - Consecutive blockquote lines (> ...) are grouped together
   - Headings (# or ##) are separate :heading blocks

   Returns a vector of blocks:
   - {:type :paragraph :text \"...\"}
   - {:type :heading :level 1|2 :text \"...\"}
   - {:type :blockquote :lines [\"line1\" \"line2\" ...]}
   - {:type :empty}"
  [content]
  (let [lines (str/split-lines content)]
    (loop [lines lines
           current-blockquote []
           result []]
      (if (empty? lines)
        ;; Flush any remaining blockquote
        (if (seq current-blockquote)
          (conj result {:type :blockquote :lines current-blockquote})
          result)
        (let [line (str/trim (first lines))]
          (cond
            ;; Empty line - flush blockquote if any, add empty block for spacing
            (str/blank? line)
            (recur (rest lines)
                   []
                   (-> result
                       (cond-> (seq current-blockquote)
                         (conj {:type :blockquote :lines current-blockquote}))
                       (conj {:type :empty})))

            ;; Heading ## (check first, more specific)
            (str/starts-with? line "## ")
            (recur (rest lines)
                   []
                   (-> result
                       (cond-> (seq current-blockquote)
                         (conj {:type :blockquote :lines current-blockquote}))
                       (conj {:type :heading :level 2 :text (subs line 3)})))

            ;; Heading #
            (str/starts-with? line "# ")
            (recur (rest lines)
                   []
                   (-> result
                       (cond-> (seq current-blockquote)
                         (conj {:type :blockquote :lines current-blockquote}))
                       (conj {:type :heading :level 1 :text (subs line 2)})))

            ;; Blockquote line - accumulate
            (blockquote-line? line)
            (recur (rest lines)
                   (conj current-blockquote (strip-blockquote line))
                   result)

            ;; Normal line - each line is a separate paragraph
            :else
            (recur (rest lines)
                   []
                   (-> result
                       ;; First flush any blockquote
                       (cond-> (seq current-blockquote)
                         (conj {:type :blockquote :lines current-blockquote}))
                       ;; Then add this line as a paragraph
                       (conj {:type :paragraph :text line})))))))))

;; =============================================================================
;; Document Structure Traversal
;; =============================================================================

(defn get-document-structure
  "Get the full document structure for export.
   Returns {:metadata {...} :chapters [{:chapter ... :scenes [...]}]}"
  []
  (let [tree (model/get-structural-tree)
        metadata (model/get-metadata)]
    {:metadata metadata
     :chapters (mapv (fn [chapter]
                       {:chunk chapter
                        :scenes (:children chapter)})
                     tree)}))

(defn strip-content-annotations
  "Remove annotations from chunk content"
  [content]
  (annotations/strip-annotations content))
