(ns tramando.export-pdf
  (:require ["pdfmake/build/pdfmake" :as pdfmake]
            ["pdfmake/build/vfs_fonts" :as vfs-fonts]
            [tramando.model :as model]
            [tramando.annotations :as annotations]
            [clojure.string :as str]))

;; =============================================================================
;; Initialize pdfmake fonts
;; =============================================================================

;; Initialize fonts - vfs-fonts directly exports the font data
(defonce ^:private init-fonts
  (do
    (set! (.-vfs pdfmake) vfs-fonts)
    true))

;; =============================================================================
;; Default Style Configuration
;; =============================================================================

(def default-style
  {:name "Default"

   :page
   {:size "A5"
    :margins {:top 60 :bottom 70 :left 50 :right 50}
    :footer {:odd {:text "{page}" :align :right :margin-right 50}
             :even {:text "{page}" :align :left :margin-left 50}}}

   :fonts
   {:main "Roboto"
    :bold "Roboto"
    :italic "Roboto"}

   :styles
   {:chapter-title
    {:font :main :size 18 :align :center
     :page-break :before
     :spacing-before 72 :spacing-after 36}

    :scene-separator
    {:content "***" :align :center
     :spacing-before 24 :spacing-after 24}

    :paragraph
    {:font :main :size 11 :align :justify
     :line-height 1.4}

    :first-paragraph
    {:font :main :size 11 :align :justify
     :line-height 1.4}

    :body-paragraph
    {:font :main :size 11 :align :justify
     :line-height 1.4
     :indent-first 20}

    :h1-in-scene
    {:font :bold :size 13
     :spacing-before 18 :spacing-after 9}

    :h2-in-scene
    {:font :italic :size 11
     :spacing-before 12 :spacing-after 6}}

   :structure-mapping
   {:chapter :chapter-title
    :scene :scene-separator}})

;; =============================================================================
;; Markdown Parsing
;; =============================================================================

(defn- parse-inline-formatting
  "Parse inline **bold** and *italic* formatting into pdfmake text array"
  [text]
  (if (str/blank? text)
    []
    (let [;; Pattern to match **bold** or *italic*
          pattern #"(\*\*([^*]+)\*\*|\*([^*]+)\*)"
          parts (str/split text pattern)]
      (loop [remaining text
             result []]
        (if (str/blank? remaining)
          result
          (if-let [match (re-find #"^(.*?)(\*\*([^*]+)\*\*|\*([^*]+)\*)(.*)" remaining)]
            (let [[_ before _ bold-text italic-text after] match
                  before-text (when-not (str/blank? before) before)
                  formatted (cond
                              bold-text {:text bold-text :bold true}
                              italic-text {:text italic-text :italics true}
                              :else nil)]
              (recur after
                     (cond-> result
                       before-text (conj before-text)
                       formatted (conj formatted))))
            ;; No more matches, add remaining text
            (conj result remaining)))))))

(defn- parse-markdown-block
  "Parse a single markdown block (paragraph or heading)"
  [line style-config]
  (let [styles (:styles style-config)]
    (cond
      ;; ## Heading 2
      (str/starts-with? line "## ")
      (let [heading-text (subs line 3)
            h2-style (:h2-in-scene styles)]
        {:type :h2
         :content {:text (parse-inline-formatting heading-text)
                   :fontSize (:size h2-style 11)
                   :italics true
                   :alignment "left"
                   :margin [0 (:spacing-before h2-style 12) 0 (:spacing-after h2-style 6)]}})

      ;; # Heading 1
      (str/starts-with? line "# ")
      (let [heading-text (subs line 2)
            h1-style (:h1-in-scene styles)]
        {:type :h1
         :content {:text (parse-inline-formatting heading-text)
                   :fontSize (:size h1-style 13)
                   :bold true
                   :alignment "left"
                   :margin [0 (:spacing-before h1-style 18) 0 (:spacing-after h1-style 9)]}})

      ;; Regular paragraph
      :else
      {:type :paragraph
       :text line})))

(defn- parse-markdown-content
  "Parse markdown content into structured blocks"
  [content style-config]
  (let [lines (str/split-lines content)
        ;; Group lines into paragraphs (separated by empty lines)
        paragraphs (loop [lines lines
                          current-para []
                          result []]
                     (if (empty? lines)
                       (if (seq current-para)
                         (conj result (str/join " " current-para))
                         result)
                       (let [line (str/trim (first lines))]
                         (if (str/blank? line)
                           (recur (rest lines)
                                  []
                                  (if (seq current-para)
                                    (conj result (str/join " " current-para))
                                    result))
                           ;; Check if it's a heading (don't merge with previous)
                           (if (or (str/starts-with? line "# ")
                                   (str/starts-with? line "## "))
                             (recur (rest lines)
                                    []
                                    (-> result
                                        (cond-> (seq current-para) (conj (str/join " " current-para)))
                                        (conj line)))
                             (recur (rest lines)
                                    (conj current-para line)
                                    result))))))]
    (map #(parse-markdown-block % style-config) paragraphs)))

;; =============================================================================
;; PDF Content Generation
;; =============================================================================

(defn- make-chapter-title
  "Create a chapter title block"
  [chapter style-config first-chapter?]
  (let [styles (:styles style-config)
        chapter-style (:chapter-title styles)
        title (model/expand-summary-macros (:summary chapter) chapter)]
    {:text title
     :fontSize (:size chapter-style 18)
     :alignment "center"
     :pageBreak (when-not first-chapter? "before")
     :margin [0 (:spacing-before chapter-style 72) 0 (:spacing-after chapter-style 36)]}))

(defn- make-scene-separator
  "Create a scene separator block"
  [style-config]
  (let [styles (:styles style-config)
        sep-style (:scene-separator styles)]
    {:text (:content sep-style "***")
     :alignment "center"
     :margin [0 (:spacing-before sep-style 24) 0 (:spacing-after sep-style 24)]}))

(defn- make-paragraph
  "Create a paragraph block with proper formatting"
  [text style-config first-para?]
  (let [styles (:styles style-config)
        para-style (if first-para?
                     (:first-paragraph styles)
                     (:body-paragraph styles))
        indent (if first-para? 0 (:indent-first para-style 20))
        inline-content (parse-inline-formatting text)]
    {:text inline-content
     :fontSize (:size para-style 11)
     :alignment "justify"
     :lineHeight (:line-height para-style 1.4)
     :margin [indent 0 0 6]}))

(defn- convert-markdown-blocks-to-pdf
  "Convert parsed markdown blocks to pdfmake content"
  [blocks style-config]
  (loop [blocks blocks
         result []
         first-para? true]
    (if (empty? blocks)
      result
      (let [block (first blocks)]
        (case (:type block)
          :h1 (recur (rest blocks)
                     (conj result (:content block))
                     true)  ; Next paragraph is first after heading
          :h2 (recur (rest blocks)
                     (conj result (:content block))
                     true)
          :paragraph (recur (rest blocks)
                            (conj result (make-paragraph (:text block) style-config first-para?))
                            false)
          ;; Default case
          (recur (rest blocks) result first-para?))))))

(defn- process-scene
  "Process a scene chunk into PDF content blocks"
  [scene style-config is-first-scene?]
  (let [separator (when-not is-first-scene?
                    [(make-scene-separator style-config)])
        ;; Strip annotations from content before processing
        content (annotations/strip-annotations (:content scene))
        blocks (parse-markdown-content content style-config)
        pdf-blocks (convert-markdown-blocks-to-pdf blocks style-config)]
    (vec (concat separator pdf-blocks))))

(defn- process-chapter
  "Process a chapter and its scenes into PDF content blocks"
  [chapter children style-config first-chapter?]
  (let [title-block (make-chapter-title chapter style-config first-chapter?)
        ;; If chapter has direct content (no scenes), process it
        ;; Strip annotations from content before processing
        chapter-content (when (and (seq (:content chapter))
                                   (empty? children))
                          (let [content (annotations/strip-annotations (:content chapter))
                                blocks (parse-markdown-content content style-config)]
                            (convert-markdown-blocks-to-pdf blocks style-config)))
        ;; Process scenes
        scene-blocks (loop [scenes children
                            result []
                            first? true]
                       (if (empty? scenes)
                         result
                         (let [scene (first scenes)
                               scene-content (process-scene scene style-config first?)]
                           (recur (rest scenes)
                                  (into result scene-content)
                                  false))))]
    (vec (concat [title-block]
                 chapter-content
                 scene-blocks))))

(defn- build-document-content
  "Build the full document content from structural tree"
  [style-config]
  (let [tree (model/get-structural-tree)]
    (loop [chapters tree
           result []
           first? true]
      (if (empty? chapters)
        result
        (let [chapter (first chapters)
              children (:children chapter)
              chapter-content (process-chapter chapter children style-config first?)]
          (recur (rest chapters)
                 (into result chapter-content)
                 false))))))

;; =============================================================================
;; PDF Document Definition
;; =============================================================================

(defn- make-footer
  "Create footer function for pdfmake"
  [style-config]
  (let [page-config (:page style-config)
        footer-config (:footer page-config)
        margin-left (get-in footer-config [:even :margin-left] 50)
        margin-right (get-in footer-config [:odd :margin-right] 50)]
    (fn [current-page page-count]
      (if (even? current-page)
        ;; Even page: number on left
        #js {:text (str current-page)
             :alignment "left"
             :margin #js [margin-left 0 0 0]}
        ;; Odd page: number on right
        #js {:text (str current-page)
             :alignment "right"
             :margin #js [0 0 margin-right 0]}))))

(defn- make-title-page
  "Create a title page with project metadata"
  [metadata]
  (let [title (:title metadata)
        author (:author metadata)]
    (when (seq title)
      [{:text title
        :fontSize 28
        :bold true
        :alignment "center"
        :margin [0 150 0 20]}
       (when (seq author)
         {:text author
          :fontSize 16
          :alignment "center"
          :margin [0 0 0 0]})
       {:text ""
        :pageBreak "after"}])))

(defn- build-doc-definition
  "Build the complete pdfmake document definition"
  [style-config]
  (let [page-config (:page style-config)
        margins (:margins page-config)
        metadata (model/get-metadata)
        title-page (make-title-page metadata)
        body-content (build-document-content style-config)
        content (vec (concat (remove nil? title-page) body-content))]
    #js {:pageSize (:size page-config "A5")
         :pageMargins #js [(:left margins 50)
                           (:top margins 60)
                           (:right margins 50)
                           (:bottom margins 70)]
         :footer (make-footer style-config)
         :content (clj->js content)
         :defaultStyle #js {:font "Roboto"}
         ;; PDF document metadata
         :info #js {:title (or (:title metadata) "Untitled")
                    :author (or (:author metadata) "")
                    :subject ""
                    :keywords ""}}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn export-pdf!
  "Export the current document as PDF"
  ([]
   (export-pdf! default-style))
  ([style-config]
   (let [doc-definition (build-doc-definition style-config)
         filename (-> (model/get-filename)
                      (str/replace #"\.[^.]+$" "")
                      (str ".pdf"))]
     (.download (.createPdf pdfmake doc-definition) filename))))
