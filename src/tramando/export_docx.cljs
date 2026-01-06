(ns tramando.export-docx
  "Export document to Microsoft Word DOCX format"
  (:require ["docx" :as docx]
            ["file-saver" :as file-saver]
            [tramando.model :as model]
            [tramando.export-common :as common]
            [clojure.string :as str]))

;; =============================================================================
;; DOCX Classes (from docx library)
;; =============================================================================

(def Document (.-Document docx))
(def Paragraph (.-Paragraph docx))
(def TextRun (.-TextRun docx))
(def PageBreak (.-PageBreak docx))
(def Packer (.-Packer docx))
(def AlignmentType (.-AlignmentType docx))
(def HeadingLevel (.-HeadingLevel docx))

;; =============================================================================
;; Text Run Conversion
;; =============================================================================

(defn- inline-segment->text-run
  "Convert a parsed inline segment to a docx TextRun"
  [segment]
  (cond
    (string? segment)
    (TextRun. #js {:text segment})

    (:bold segment)
    (TextRun. #js {:text (:text segment) :bold true})

    (:italic segment)
    (TextRun. #js {:text (:text segment) :italics true})

    :else
    (TextRun. #js {:text (str segment)})))

(defn- text->runs
  "Convert text with inline formatting to an array of TextRuns"
  [text]
  (let [segments (common/parse-inline-formatting text)]
    (into-array (map inline-segment->text-run segments))))

;; =============================================================================
;; Paragraph Creation
;; =============================================================================

(defn- make-paragraph
  "Create a normal paragraph"
  [text]
  (Paragraph. #js {:children (text->runs text)
                   :alignment (.-JUSTIFIED AlignmentType)
                   :spacing #js {:after 120}}))

(defn- make-empty-paragraph
  "Create an empty paragraph for spacing"
  []
  (Paragraph. #js {:children #js []
                   :spacing #js {:after 200}}))

(defn- make-blockquote-paragraph
  "Create a blockquote paragraph (indented, italic)"
  [text]
  (Paragraph. #js {:children (into-array
                               (map (fn [seg]
                                      (if (string? seg)
                                        (TextRun. #js {:text seg :italics true})
                                        (TextRun. #js {:text (:text seg)
                                                       :italics true
                                                       :bold (:bold seg)})))
                                    (common/parse-inline-formatting text)))
                   :alignment (.-LEFT AlignmentType)
                   :indent #js {:left 720}  ; 0.5 inch in twips
                   :spacing #js {:after 60}}))

(defn- make-heading
  "Create a heading paragraph"
  [text level]
  (let [heading-level (case level
                        1 (.-HEADING_1 HeadingLevel)
                        2 (.-HEADING_2 HeadingLevel)
                        (.-HEADING_3 HeadingLevel))]
    (Paragraph. #js {:children (text->runs text)
                     :heading heading-level
                     :spacing #js {:before 240 :after 120}})))

(defn- make-chapter-title
  "Create a chapter title with page break"
  [title first-chapter?]
  (Paragraph. #js {:children (text->runs title)
                   :heading (.-HEADING_1 HeadingLevel)
                   :alignment (.-CENTER AlignmentType)
                   :pageBreakBefore (not first-chapter?)
                   :spacing #js {:before 1440 :after 720}}))  ; 1 inch before, 0.5 after

(defn- make-scene-separator
  "Create a scene separator (***)"
  []
  (Paragraph. #js {:children #js [(TextRun. #js {:text "***"})]
                   :alignment (.-CENTER AlignmentType)
                   :spacing #js {:before 480 :after 480}}))

;; =============================================================================
;; Block Conversion
;; =============================================================================

(defn- block->paragraphs
  "Convert a parsed block to docx Paragraph(s)"
  [block]
  (case (:type block)
    :paragraph
    [(make-paragraph (:text block))]

    :heading
    [(make-heading (:text block) (:level block))]

    :blockquote
    (mapv make-blockquote-paragraph (:lines block))

    :empty
    [(make-empty-paragraph)]

    ;; Default
    []))

;; =============================================================================
;; Scene and Chapter Processing
;; =============================================================================

(defn- process-scene-content
  "Process scene content into paragraphs"
  [scene]
  (let [content (common/strip-content-annotations (:content scene))
        blocks (common/parse-markdown-blocks content)]
    (mapcat block->paragraphs blocks)))

(defn- process-scene
  "Process a scene into paragraphs with optional separator"
  [scene first-scene?]
  (let [content-paras (process-scene-content scene)]
    (if first-scene?
      content-paras
      (cons (make-scene-separator) content-paras))))

(defn- process-chapter
  "Process a chapter and its scenes into paragraphs"
  [chapter-data first-chapter?]
  (let [{:keys [chunk scenes]} chapter-data
        title (model/expand-summary-macros (:summary chunk) chunk)
        title-para (make-chapter-title title first-chapter?)
        ;; Process chapter's own content if no scenes
        chapter-content (when (and (seq (:content chunk)) (empty? scenes))
                          (process-scene-content chunk))
        ;; Process scenes
        scene-paras (mapcat (fn [[idx scene]]
                              (process-scene scene (zero? idx)))
                            (map-indexed vector scenes))]
    (concat [title-para]
            chapter-content
            scene-paras)))

;; =============================================================================
;; Title Page
;; =============================================================================

(defn- make-title-page
  "Create title page paragraphs"
  [metadata]
  (let [title (:title metadata)
        author (:author metadata)]
    (when (seq title)
      [(Paragraph. #js {:children #js []
                        :spacing #js {:before 2880}})  ; 2 inches down
       (Paragraph. #js {:children #js [(TextRun. #js {:text title
                                                       :bold true
                                                       :size 56})]  ; 28pt
                        :alignment (.-CENTER AlignmentType)
                        :spacing #js {:after 400}})
       (when (seq author)
         (Paragraph. #js {:children #js [(TextRun. #js {:text author
                                                         :size 32})]  ; 16pt
                          :alignment (.-CENTER AlignmentType)}))
       (Paragraph. #js {:children #js [(PageBreak.)]})  ; Page break after title
       ])))

;; =============================================================================
;; Document Building
;; =============================================================================

(defn- build-document-content
  "Build all document content as a flat array of paragraphs"
  []
  (let [{:keys [metadata chapters]} (common/get-document-structure)
        title-page (make-title-page metadata)
        chapter-paras (mapcat (fn [[idx chapter-data]]
                                (process-chapter chapter-data (zero? idx)))
                              (map-indexed vector chapters))]
    (into-array (filter some? (concat title-page chapter-paras)))))

(defn- create-document
  "Create the DOCX document"
  []
  (let [metadata (model/get-metadata)
        content (build-document-content)]
    (Document. #js {:creator (or (:author metadata) "Tramando")
                    :title (or (:title metadata) "Untitled")
                    :description ""
                    :sections #js [#js {:properties #js {:page #js {:size #js {:width 11906  ; A5 in twips
                                                                                :height 16838}
                                                                    :margin #js {:top 1134   ; ~2cm
                                                                                 :bottom 1134
                                                                                 :left 1134
                                                                                 :right 1134}}}
                                        :children content}]})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn export-docx!
  "Export the current document as DOCX"
  []
  (let [doc (create-document)
        filename (-> (model/get-filename)
                     (str/replace #"\.[^.]+$" "")
                     (str ".docx"))]
    (-> (Packer.toBlob doc)
        (.then (fn [blob]
                 (file-saver/saveAs blob filename))))))
