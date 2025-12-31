(ns tramando.editor
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.annotations :as annotations]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]
            [tramando.context-menu :as context-menu]
            [tramando.events :as events]
            ["@codemirror/state" :refer [EditorState StateField StateEffect RangeSetBuilder]]
            ["@codemirror/view" :refer [EditorView keymap lineNumbers highlightActiveLine
                                        highlightActiveLineGutter drawSelection
                                        Decoration ViewPlugin MatchDecorator WidgetType]]
            ["@codemirror/commands" :refer [defaultKeymap history historyKeymap
                                            indentWithTab]]
            ["@codemirror/language" :refer [indentOnInput bracketMatching
                                            defaultHighlightStyle syntaxHighlighting]]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@codemirror/search" :refer [searchKeymap highlightSelectionMatches]]
            ["marked" :refer [marked]]))

;; =============================================================================
;; CodeMirror 6 Theme (uses CSS variables from settings)
;; =============================================================================

(def tramando-theme
  (.theme EditorView
          #js {".cm-content" #js {:color "var(--color-text)"
                                  :caretColor "var(--color-accent)"}
               ".cm-cursor" #js {:borderLeftColor "var(--color-accent)"}
               "&.cm-focused .cm-selectionBackground" #js {:backgroundColor "rgba(233, 69, 96, 0.3)"}
               ".cm-selectionBackground" #js {:backgroundColor "rgba(233, 69, 96, 0.2)"}
               ".cm-activeLine" #js {:backgroundColor "rgba(233, 69, 96, 0.08)"}
               ".cm-gutters" #js {:backgroundColor "var(--color-sidebar)"
                                  :color "var(--color-text-muted)"
                                  :borderRight "1px solid var(--color-border)"}
               ".cm-activeLineGutter" #js {:backgroundColor "var(--color-editor-bg)"}
               "&" #js {:backgroundColor "var(--color-background)"}
               ;; Annotation styles - full markup mode
               ".cm-annotation-todo" #js {:backgroundColor "rgba(255, 193, 7, 0.3)"
                                          :borderRadius "3px"}
               ".cm-annotation-note" #js {:backgroundColor "rgba(33, 150, 243, 0.25)"
                                          :borderRadius "3px"}
               ".cm-annotation-fix" #js {:backgroundColor "rgba(244, 67, 54, 0.25)"
                                         :borderRadius "3px"}
               ;; Reading mode - highlighted text only
               ".cm-annotation-text-todo" #js {:backgroundColor "rgba(255, 193, 7, 0.4)"
                                               :borderRadius "2px"
                                               :padding "0 2px"}
               ".cm-annotation-text-note" #js {:backgroundColor "rgba(33, 150, 243, 0.35)"
                                               :borderRadius "2px"
                                               :padding "0 2px"}
               ".cm-annotation-text-fix" #js {:backgroundColor "rgba(244, 67, 54, 0.35)"
                                              :borderRadius "2px"
                                              :padding "0 2px"}
               ;; Hidden parts in reading mode
               ".cm-annotation-hidden" #js {:fontSize "0"
                                            :color "transparent"
                                            :userSelect "none"}
               ;; AI alternative widget container
               ".cm-ai-alternative-container" #js {:display "inline"}
               ;; Original text (striked through)
               ".cm-ai-original-striked" #js {:textDecoration "line-through"
                                              :textDecorationColor "rgba(150, 150, 150, 0.7)"
                                              :opacity "0.6"
                                              :marginRight "4px"}
               ;; Alternative text (normal)
               ".cm-ai-alternative-text" #js {:borderBottom "2px dotted var(--color-accent)"}
               ;; AI selected annotation in markup mode
               ".cm-ai-selected" #js {:backgroundColor "rgba(76, 175, 80, 0.25)"
                                      :borderRadius "3px"}
               ;; Search match highlighting
               ".cm-search-match" #js {:backgroundColor "rgba(255, 230, 0, 0.3)"
                                       :borderRadius "2px"}
               ".cm-search-match-current" #js {:backgroundColor "rgba(255, 165, 0, 0.5)"
                                               :borderRadius "2px"
                                               :boxShadow "0 0 2px rgba(255, 165, 0, 0.8)"}
               ;; Flash highlight for navigation
               ".cm-annotation-flash" #js {:animation "annotation-flash 2.5s ease-out"
                                           :borderRadius "3px"}}))

;; =============================================================================
;; Editor Search State
;; =============================================================================

(defonce editor-search-state
  (r/atom {:visible false
           :text ""
           :case-sensitive false
           :regex false
           :matches []           ; vector of {:from :to}
           :current-index 0      ; 0-based index of current match
           :invalid-regex false
           :replace-visible false
           :replace-text ""}))

;; Toast state is defined in events namespace

(defonce editor-view-ref (atom nil))
(defonce search-input-ref (atom nil))
(defonce replace-input-ref (atom nil))
(defonce search-debounce-timer (atom nil))

;; =============================================================================
;; Editor Text Replacement (for proper undo support)
;; =============================================================================

(defn- replace-text-in-view!
  "Replace text in the CodeMirror view through a transaction (supports undo).
   Returns true if replacement was made, false otherwise."
  [search-text replacement-text]
  (when-let [view @editor-view-ref]
    (let [doc-text (.. view -state -doc (toString))
          idx (.indexOf doc-text search-text)]
      (when (>= idx 0)
        (let [from idx
              to (+ idx (count search-text))]
          (.dispatch view #js {:changes #js {:from from
                                             :to to
                                             :insert replacement-text}})
          true)))))

;; Register the replace function with events module
(events/set-editor-replace-fn! replace-text-in-view!)

;; Decoration marks
(def search-match-mark (.mark Decoration #js {:class "cm-search-match"}))
(def search-match-current-mark (.mark Decoration #js {:class "cm-search-match-current"}))

(defn- find-matches
  "Find all matches in text, returns vector of {:from :to}"
  [text search-text case-sensitive? regex?]
  (when (and (seq search-text) (seq text))
    (try
      (let [flags (if case-sensitive? "g" "gi")
            ;; Escape special regex characters for literal search
            escaped-text (-> search-text
                             (str/replace #"\\" "\\\\")
                             (str/replace #"\[" "\\[")
                             (str/replace #"\]" "\\]")
                             (str/replace #"\." "\\.")
                             (str/replace #"\*" "\\*")
                             (str/replace #"\+" "\\+")
                             (str/replace #"\?" "\\?")
                             (str/replace #"\^" "\\^")
                             (str/replace #"\$" "\\$")
                             (str/replace #"\{" "\\{")
                             (str/replace #"\}" "\\}")
                             (str/replace #"\(" "\\(")
                             (str/replace #"\)" "\\)")
                             (str/replace #"\|" "\\|"))
            pattern (if regex?
                      (js/RegExp. search-text flags)
                      (js/RegExp. escaped-text flags))
            matches (atom [])]
        (loop []
          (when-let [match (.exec pattern text)]
            (let [start (.-index match)
                  end (+ start (count (aget match 0)))]
              (when (> end start) ; avoid infinite loop on zero-width matches
                (swap! matches conj {:from start :to end})
                (recur)))))
        @matches)
      (catch js/Error _
        nil))))

(def ^:private annotation-regex-for-ranges
  "Regex to find annotations and their parts: [!TYPE:text:priority:comment]"
  (js/RegExp. "\\[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\\]]*)\\]" "g"))

(defn- get-hidden-ranges
  "Returns vector of {:from :to} for hidden parts of annotations.
   When show-markup is false, the prefix [!TYPE: and suffix :priority:comment] are hidden."
  [text]
  (let [ranges (atom [])]
    (set! (.-lastIndex annotation-regex-for-ranges) 0)
    (loop []
      (when-let [match (.exec annotation-regex-for-ranges text)]
        (let [full-match (aget match 0)
              type-str (aget match 1)
              selected-text (aget match 2)
              start (.-index match)
              end (+ start (count full-match))
              ;; Hidden prefix: [!TYPE: (from start to after first colon)
              prefix-end (+ start 2 (count type-str) 1)
              ;; Hidden suffix: :priority:comment] (from after selected text to end)
              text-end (+ prefix-end (count selected-text))]
          ;; Add hidden prefix range
          (swap! ranges conj {:from start :to prefix-end})
          ;; Add hidden suffix range
          (swap! ranges conj {:from text-end :to end})
          (recur))))
    @ranges))

(defn- match-overlaps-hidden?
  "Check if a match overlaps with any hidden range"
  [{:keys [from to]} hidden-ranges]
  (some (fn [{hfrom :from hto :to}]
          ;; Overlap if match intersects with hidden range
          (and (< from hto) (> to hfrom)))
        hidden-ranges))

(defn- filter-visible-matches
  "Filter out matches that overlap with hidden annotation ranges"
  [matches hidden-ranges]
  (if (empty? hidden-ranges)
    matches
    (filterv #(not (match-overlaps-hidden? % hidden-ranges)) matches)))

(defn- create-search-decorations
  "Create decorations for search matches.
   Matches are already sorted by position from find-matches."
  [matches current-index]
  (let [builder (RangeSetBuilder.)]
    (doseq [[idx {:keys [from to]}] (map-indexed vector matches)]
      (let [mark (if (= idx current-index)
                   search-match-current-mark
                   search-match-mark)]
        (.add builder from to mark)))
    (.finish builder)))

(defn- force-editor-update!
  "Force the editor to update decorations by dispatching an empty transaction"
  []
  ;; Use requestAnimationFrame to ensure state is updated before refresh
  (js/requestAnimationFrame
   (fn []
     (when-let [view @editor-view-ref]
       (.dispatch view #js {})))))

(defn update-search!
  "Update search matches based on current state"
  []
  (when-let [view @editor-view-ref]
    (let [{:keys [text case-sensitive regex]} @editor-search-state
          doc-text (.. view -state -doc (toString))
          show-markup? @annotations/show-markup?
          min-chars (if regex 1 2)]
      (if (< (count text) min-chars)
        ;; Not enough chars, clear matches
        (do
          (swap! editor-search-state assoc
                 :matches []
                 :current-index 0
                 :invalid-regex false)
          (force-editor-update!))
        ;; Find matches
        (let [all-matches (find-matches doc-text text case-sensitive regex)]
          (if (nil? all-matches)
            ;; Invalid regex
            (do
              (swap! editor-search-state assoc
                     :matches []
                     :current-index 0
                     :invalid-regex true)
              (force-editor-update!))
            ;; Valid search - filter out hidden matches when show-markup is false
            (let [matches (if show-markup?
                            all-matches
                            (let [hidden-ranges (get-hidden-ranges doc-text)]
                              (filter-visible-matches all-matches hidden-ranges)))]
              (swap! editor-search-state assoc
                     :matches (vec matches)
                     :current-index (if (empty? matches) 0 0)
                     :invalid-regex false)
              (force-editor-update!))))))))

(defn set-search-text!
  "Set search text with debounce"
  [text]
  (swap! editor-search-state assoc :text text)
  (when @search-debounce-timer
    (js/clearTimeout @search-debounce-timer))
  (reset! search-debounce-timer
          (js/setTimeout update-search! 150)))

(defn toggle-search-case-sensitive! []
  (swap! editor-search-state update :case-sensitive not)
  (update-search!))

(defn toggle-search-regex! []
  (swap! editor-search-state update :regex not)
  (update-search!))

(defn navigate-to-match!
  "Navigate to match at index and scroll into view"
  [index]
  (when-let [view @editor-view-ref]
    (let [matches (:matches @editor-search-state)]
      (when (and (seq matches) (<= 0 index) (< index (count matches)))
        (let [{:keys [from]} (nth matches index)]
          ;; Update current index first
          (swap! editor-search-state assoc :current-index index)
          ;; Scroll to match and force decoration update
          (.dispatch view
                     #js {:effects (.scrollIntoView EditorView from #js {:y "center"})}))))))

(defn next-match! []
  (let [{:keys [matches current-index]} @editor-search-state]
    (when (seq matches)
      (let [next-idx (mod (inc current-index) (count matches))]
        (navigate-to-match! next-idx)))))

(defn prev-match! []
  (let [{:keys [matches current-index]} @editor-search-state]
    (when (seq matches)
      (let [n (count matches)
            prev-idx (mod (+ (dec current-index) n) n)]
        (navigate-to-match! prev-idx)))))

(defn show-editor-search!
  "Show the search bar and optionally set text and options"
  ([]
   (swap! editor-search-state assoc :visible true)
   (js/setTimeout #(when @search-input-ref (.focus @search-input-ref)) 50))
  ([{:keys [text case-sensitive regex]}]
   (swap! editor-search-state assoc
          :visible true
          :text (or text "")
          :case-sensitive (or case-sensitive false)
          :regex (or regex false))
   (update-search!)
   (js/setTimeout #(when @search-input-ref (.focus @search-input-ref)) 50)))

(defn hide-editor-search! []
  (swap! editor-search-state assoc
         :visible false
         :matches []
         :current-index 0
         :replace-visible false)
  (force-editor-update!))

(defn focus-search! []
  (when @search-input-ref
    (.focus @search-input-ref)))

(defn toggle-replace-visible! []
  (swap! editor-search-state update :replace-visible not))

(defn set-replace-text! [text]
  (swap! editor-search-state assoc :replace-text text))

(defn show-editor-search-with-replace!
  "Show the search bar with replace expanded"
  []
  (swap! editor-search-state assoc :visible true :replace-visible true)
  (js/setTimeout #(when @search-input-ref (.focus @search-input-ref)) 50))

;; =============================================================================
;; Toast Component (delegates to events namespace)
;; =============================================================================

(def show-toast! events/show-toast!)

(def refresh-editor! events/refresh-editor!)

;; =============================================================================
;; Navigation Flash Effect
;; =============================================================================

(defonce flash-decoration-atom (atom nil))
(def flash-mark (.mark Decoration #js {:class "cm-annotation-flash"}))

(defn- create-flash-decorations [from to]
  (let [builder (RangeSetBuilder.)]
    (.add builder from to flash-mark)
    (.finish builder)))

(def flash-highlight-plugin
  (.define ViewPlugin
           (fn [view]
             #js {:decorations (.-none Decoration)
                  :update (fn [vu]
                            (this-as this
                              (if-let [range @flash-decoration-atom]
                                (set! (.-decorations this)
                                      (create-flash-decorations (:from range) (:to range)))
                                (set! (.-decorations this) (.-none Decoration)))))})
           #js {:decorations (fn [v] (.-decorations v))}))

(defn navigate-to-annotation!
  "Navigate to a specific annotation in the editor by selecting its text.
   Call after model/select-chunk! to scroll to and highlight the annotation.
   Shows a flash animation to indicate the annotation location."
  [selected-text]
  (js/setTimeout
   (fn []
     (when-let [view @editor-view-ref]
       (let [doc-text (.. view -state -doc (toString))
             ;; Find the annotation pattern containing this selected-text using JS regex for position
             escaped-text (str/replace selected-text #"[.*+?^${}()|\\[\\]\\\\]" "\\\\$&")
             js-pattern (js/RegExp. (str "\\[!(TODO|NOTE|FIX):" escaped-text ":[^\\]]*\\]") "g")
             match (.exec js-pattern doc-text)]
         (when match
           (let [idx (.-index match)
                 end-idx (+ idx (count (aget match 0)))]
             (when (>= idx 0)
               ;; Set flash decoration range and trigger update
               (reset! flash-decoration-atom {:from idx :to end-idx})
               ;; Scroll into view and set cursor at start
               (.dispatch view #js {:selection #js {:anchor idx :head idx}
                                    :scrollIntoView true})
               ;; Force decoration update
               (.dispatch view #js {})
               (.focus view)
               ;; Clear flash after animation (2.5s)
               (js/setTimeout
                (fn []
                  (reset! flash-decoration-atom nil)
                  (when @editor-view-ref
                    (.dispatch @editor-view-ref #js {})))
                2600))))))) 300))

(defn toast-component []
  (when-let [{:keys [message visible]} @events/toast-state]
    [:div.toast message]))

;; =============================================================================
;; Replace Functions
;; =============================================================================

(defn- apply-regex-replacement
  "Apply regex replacement with group support ($0, $1, $2, etc.)"
  [match-str replace-text regex-pattern case-sensitive?]
  (let [flags (if case-sensitive? "" "i")
        re (js/RegExp. regex-pattern flags)]
    (.replace match-str re replace-text)))

(defn replace-current-match!
  "Replace the current match with the replace text"
  []
  (when-let [view @editor-view-ref]
    (let [{:keys [matches current-index replace-text text regex case-sensitive]} @editor-search-state]
      (when (and (seq matches) (< current-index (count matches)))
        (let [{:keys [from to]} (nth matches current-index)
              doc-text (.. view -state -doc (toString))
              match-str (subs doc-text from to)
              ;; For regex mode, apply $1, $2 replacements
              actual-replace (if regex
                               (apply-regex-replacement match-str replace-text text case-sensitive)
                               replace-text)]
          ;; Apply the replacement
          (.dispatch view #js {:changes #js {:from from :to to :insert actual-replace}})
          ;; Update search after replacement
          (js/setTimeout
           (fn []
             (update-search!)
             ;; Navigate to next match (or stay at same index if there are more)
             (let [new-matches (:matches @editor-search-state)
                   new-count (count new-matches)]
               (when (pos? new-count)
                 (let [new-idx (min current-index (dec new-count))]
                   (navigate-to-match! new-idx)))))
           50))))))

(defn replace-all-matches!
  "Replace all matches with the replace text"
  []
  (when-let [view @editor-view-ref]
    (let [{:keys [matches replace-text text regex case-sensitive]} @editor-search-state
          match-count (count matches)]
      (when (pos? match-count)
        ;; Process matches from back to front to preserve indices
        (let [doc-text (.. view -state -doc (toString))
              changes (->> matches
                           (map-indexed (fn [idx m]
                                          (let [match-str (subs doc-text (:from m) (:to m))
                                                actual-replace (if regex
                                                                 (apply-regex-replacement match-str replace-text text case-sensitive)
                                                                 replace-text)]
                                            #js {:from (:from m) :to (:to m) :insert actual-replace})))
                           reverse
                           (into-array))]
          ;; Apply all changes in a single transaction
          (.dispatch view #js {:changes changes})
          ;; Show toast with count
          (show-toast! (if (= match-count 1)
                         (t :replaced-one-occurrence)
                         (t :replaced-n-occurrences match-count)))
          ;; Update search (should find 0 matches now)
          (js/setTimeout update-search! 50))))))

;; =============================================================================
;; Search Highlight ViewPlugin
;; =============================================================================

(defn- get-current-decorations []
  (let [{:keys [matches current-index visible]} @editor-search-state]
    (if (and visible (seq matches))
      (create-search-decorations matches current-index)
      (.-none Decoration))))

(def search-highlight-plugin
  (.define ViewPlugin
           (fn [view]
             #js {:decorations (get-current-decorations)
                  :update (fn [vu]
                            (this-as this
                              (set! (.-decorations this) (get-current-decorations))))})
           #js {:decorations (fn [v] (.-decorations v))}))

;; =============================================================================
;; Annotation Highlighting Extension
;; =============================================================================

;; Syntax: [!TYPE:selected text:priority:comment]
(def ^:private annotation-regex (js/RegExp. "\\[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\\]]*)\\]" "g"))

;; Create a widget class that properly extends WidgetType
;; Using JavaScript class syntax via eval for proper inheritance
(def TextWidget
  (js/eval "
    (function(WidgetType) {
      class TextWidget extends WidgetType {
        constructor(text, cssClass) {
          super();
          this.text = text;
          this.cssClass = cssClass;
        }
        toDOM() {
          let span = document.createElement('span');
          span.className = this.cssClass;
          // Handle newlines by creating text nodes and br elements
          let parts = this.text.split('\\n');
          for (let i = 0; i < parts.length; i++) {
            span.appendChild(document.createTextNode(parts[i]));
            if (i < parts.length - 1) {
              span.appendChild(document.createElement('br'));
            }
          }
          return span;
        }
        eq(other) {
          return other.text === this.text && other.cssClass === this.cssClass;
        }
        ignoreEvent() { return false; }
      }
      return TextWidget;
    })
  "))

(def TextWidgetClass (TextWidget WidgetType))

(defn- create-text-widget [text css-class]
  (TextWidgetClass. text css-class))

;; Widget for AI alternative: shows original (striked) + alternative (normal)
(def AiAlternativeWidget
  (js/eval "
    (function(WidgetType) {
      class AiAlternativeWidget extends WidgetType {
        constructor(originalText, alternativeText) {
          super();
          this.originalText = originalText;
          this.alternativeText = alternativeText;
        }
        toDOM() {
          let container = document.createElement('span');
          container.className = 'cm-ai-alternative-container';

          let original = document.createElement('span');
          original.className = 'cm-ai-original-striked';
          original.textContent = this.originalText;

          let alternative = document.createElement('span');
          alternative.className = 'cm-ai-alternative-text';
          alternative.textContent = this.alternativeText;

          container.appendChild(original);
          container.appendChild(alternative);
          return container;
        }
        eq(other) {
          return other.originalText === this.originalText &&
                 other.alternativeText === this.alternativeText;
        }
        ignoreEvent() { return false; }
      }
      return AiAlternativeWidget;
    })
  "))

(def AiAlternativeWidgetClass (AiAlternativeWidget WidgetType))

(defn- create-ai-alternative-widget [original-text alternative-text]
  (AiAlternativeWidgetClass. original-text alternative-text))

(defn- create-annotation-decorations [view show-markup?]
  (let [builder #js []
        doc (.. view -state -doc)
        text (.toString doc)]
    ;; Reset regex
    (set! (.-lastIndex annotation-regex) 0)
    ;; Find all matches
    (loop []
      (when-let [match (.exec annotation-regex text)]
        (let [full-match (aget match 0)
              type-str (aget match 1)
              selected-text (aget match 2)
              priority-str (aget match 3)
              comment-text (aget match 4)
              start (.-index match)
              end (+ start (count full-match))
              ;; Check if this is an AI-DONE annotation with a selection
              is-ai-done? (= priority-str "AI-DONE")
              ai-data (when is-ai-done?
                        (annotations/parse-ai-data comment-text))
              ai-sel (when ai-data (or (:sel ai-data) 0))
              ai-alts (when ai-data (or (:alts ai-data) []))
              has-ai-selection? (and is-ai-done? (pos? ai-sel) (<= ai-sel (count ai-alts)))
              ;; Get alternative text if selected
              alt-text (when has-ai-selection?
                         (nth ai-alts (dec ai-sel)))
              ;; Calculate positions of parts: [!TYPE:text:priority:comment]
              prefix-end (+ start 2 (count type-str) 1) ; after [!TYPE:
              text-start prefix-end
              text-end (+ text-start (count selected-text))
              suffix-start text-end ; from :priority:comment] to end
              type-lower (str/lower-case type-str)
              ;; Build tooltip
              tooltip (cond
                        has-ai-selection?
                        (str "AI alternative #" ai-sel " selected")
                        (and (seq priority-str) (seq comment-text))
                        (str "[" priority-str "] " comment-text)
                        (seq comment-text) comment-text
                        (seq priority-str) (str "[" priority-str "]")
                        :else (str type-str " annotation"))]
          (if show-markup?
            ;; Markup mode: highlight entire annotation
            (let [css-class (cond
                              has-ai-selection? "cm-ai-selected"
                              is-ai-done? "cm-annotation-note"
                              :else (str "cm-annotation-" type-lower))]
              (.push builder (.range (.mark Decoration #js {:class css-class})
                                     start end)))
            ;; Reading mode
            (let [;; Check if annotation spans multiple lines (contains newline)
                  annotation-text (subs text start end)
                  spans-lines? (str/includes? annotation-text "\n")
                  ;; Also check if alt-text contains newlines
                  alt-spans-lines? (and alt-text (str/includes? alt-text "\n"))]
              (cond
                ;; AI-DONE with selection (single line annotation AND alternative): use widget with strikethrough
                (and has-ai-selection? (not spans-lines?) (not alt-spans-lines?))
                (.push builder (.range (.replace Decoration
                                                 #js {:widget (create-ai-alternative-widget selected-text alt-text)})
                                       start end))

                ;; AI-DONE with selection but multiline: hide annotation, show alternative as plain text
                (and has-ai-selection? (or spans-lines? alt-spans-lines?))
                (do
                  ;; Hide the entire annotation
                  (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"})
                                         start end))
                  ;; Insert widget with alternative text at start position
                  (.push builder (.range (.widget Decoration
                                                  #js {:widget (create-text-widget alt-text "cm-ai-alternative-text")
                                                       :side 1})
                                         start)))

                ;; Normal annotation: hide prefix/suffix, highlight text
                :else
                (do
                  ;; Hide prefix [!TYPE:
                  (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"})
                                         start text-start))
                  ;; Highlight selected text
                  (.push builder (.range (.mark Decoration #js {:class (str "cm-annotation-text-" type-lower)
                                                                :attributes #js {:title tooltip}})
                                         text-start text-end))
                  ;; Hide suffix :priority:comment]
                  (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"})
                                         suffix-start end))))))
        (recur))))
    ;; Sort by position and create DecorationSet
    (.sort builder (fn [a b] (- (.-from a) (.-from b))))
    (.set Decoration builder true)))

(def annotation-highlight
  (.define ViewPlugin
           (fn [view]
             (let [show? @annotations/show-markup?]
               #js {:decorations (create-annotation-decorations view show?)
                    :showMarkup show?
                    :update (fn [vu] ; vu = ViewUpdate
                              (this-as this
                                (let [current-show? @annotations/show-markup?]
                                  (when (or (.-docChanged vu)
                                            (not= (.-showMarkup this) current-show?))
                                    (set! (.-showMarkup this) current-show?)
                                    (set! (.-decorations this)
                                          (create-annotation-decorations (.-view vu) current-show?))))))}))
           #js {:decorations (fn [v] (.-decorations v))}))

;; =============================================================================
;; Update Handler
;; =============================================================================

(defn make-update-listener [chunk-id]
  (.of (.-updateListener EditorView)
       (fn [update]
         (when (.-docChanged update)
           (let [content (.. update -state -doc (toString))]
             (model/update-chunk! chunk-id {:content content})))
         js/undefined)))

;; =============================================================================
;; Editor Creation
;; =============================================================================

;; Custom keymap for search
(def search-keymap
  #js [#js {:key "Escape"
            :run (fn [view]
                   (let [search-visible (:visible @editor-search-state)
                         ;; Access outline namespace dynamically to avoid circular deps
                         outline-filter-state (aget js/tramando "outline" "filter_state")
                         filter-active (when outline-filter-state
                                         (seq (:text @outline-filter-state)))]
                     (when search-visible
                       (hide-editor-search!))
                     (when filter-active
                       (when-let [clear-fn (aget js/tramando "outline" "clear_filter_BANG_")]
                         (clear-fn)))
                     (or search-visible filter-active)))}
       #js {:key "Mod-f"
            :run (fn [view]
                   (show-editor-search!)
                   true)}
       #js {:key "Mod-h"
            :run (fn [view]
                   (if (:visible @editor-search-state)
                     ;; If already visible, toggle replace panel
                     (toggle-replace-visible!)
                     ;; Otherwise show with replace expanded
                     (show-editor-search-with-replace!))
                   true)}
       #js {:key "F3"
            :run (fn [view]
                   (when (:visible @editor-search-state)
                     (next-match!)
                     true))}
       #js {:key "Shift-F3"
            :run (fn [view]
                   (when (:visible @editor-search-state)
                     (prev-match!)
                     true))}])

(defn create-editor-state [content chunk-id]
  (.create EditorState
           #js {:doc content
                :extensions #js [tramando-theme
                                 (lineNumbers)
                                 (highlightActiveLine)
                                 (highlightActiveLineGutter)
                                 (drawSelection)
                                 (.-lineWrapping EditorView)
                                 (history)
                                 (indentOnInput)
                                 (bracketMatching)
                                 (highlightSelectionMatches)
                                 (syntaxHighlighting defaultHighlightStyle)
                                 (markdown)
                                 annotation-highlight
                                 search-highlight-plugin
                                 flash-highlight-plugin
                                 (.of keymap search-keymap)  ;; Our search keymap first (higher priority)
                                 (.of keymap (.concat defaultKeymap historyKeymap searchKeymap #js [indentWithTab]))
                                 (make-update-listener chunk-id)]}))

(defn create-editor-view [parent-element state]
  (EditorView. #js {:state state
                    :parent parent-element}))

;; =============================================================================
;; Context Menu for Annotations
;; =============================================================================

(defonce local-editor-view (atom nil))

(defn- contains-annotation?
  "Check if text contains any annotation markers"
  [text]
  (boolean (re-find #"\[!(TODO|NOTE|FIX):" text)))

(defn- wrap-selection-with-annotation!
  "Wrap selected text with annotation syntax [!TYPE:text:priority:comment]"
  [annotation-type chunk selected-text]
  ;; Prevent nested annotations
  (if (contains-annotation? selected-text)
    (events/show-toast! (t :error-nested-annotation))
    (when-let [view @local-editor-view]
      (let [doc-text (.. view -state -doc (toString))
            ;; Find the position of selected text
            idx (str/index-of doc-text selected-text)]
        (when (and idx (>= idx 0))
          (let [from idx
                to (+ from (count selected-text))
                wrapped-text (str "[!" annotation-type ":" selected-text "::]")
                ;; Position cursor after the second : (where priority goes)
                cursor-pos (+ from 2 (count annotation-type) 1 (count selected-text) 1)]
            (.dispatch view #js {:changes #js {:from from :to to :insert wrapped-text}
                                 :selection #js {:anchor cursor-pos}})
            (.focus view)))))))

;; Set up handler for wrap annotation from context menu
(context-menu/set-wrap-annotation-handler! wrap-selection-with-annotation!)

(defn- delete-annotation!
  "Delete an annotation from a chunk, keeping original text"
  [annotation]
  (let [{:keys [type selected-text chunk-id]} annotation]
    (when-let [chunk (model/get-chunk chunk-id)]
      (let [content (:content chunk)
            ;; Pattern to match this annotation
            pattern (re-pattern (str "\\[!" (name type) ":"
                                     (str/replace selected-text #"[.*+?^${}()|\\[\\]\\\\]" "\\\\$&")
                                     ":[^:]*:[^\\]]*\\]"))
            new-content (str/replace content pattern selected-text)]
        (when (not= content new-content)
          (model/update-chunk! chunk-id {:content new-content})
          (refresh-editor!))))))

;; Set up handler for delete annotation from context menu
(context-menu/set-delete-annotation-handler! delete-annotation!)

;; Store reference to contextmenu handler for cleanup
(defonce contextmenu-handler (atom nil))

;; Forward declaration
(declare find-annotation-at-position)

(defn- parse-annotation-from-text
  "Try to parse an annotation from text that might be a complete annotation.
   Returns annotation map or nil."
  [text chunk-id]
  (when (and text (str/starts-with? text "[!"))
    (let [pattern (js/RegExp. "^\\[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\\]]*)\\]$")]
      (when-let [match (.exec pattern text)]
        {:type (keyword (aget match 1))
         :selected-text (str/trim (aget match 2))
         :priority (annotations/parse-priority (aget match 3))
         :comment (str/trim (or (aget match 4) ""))
         :chunk-id chunk-id}))))

(defn- create-contextmenu-handler
  "Create the right-click context menu handler function"
  [editor-view-atom]
  (fn [e]
    ;; Always prevent browser default menu first
    (.preventDefault e)
    (when-let [view @editor-view-atom]
      (let [current-chunk (model/get-selected-chunk)
            chunk-id (:id current-chunk)
            doc-text (.. view -state -doc (toString))
            ;; Get selection info
            sel (.. view -state -selection -main)
            sel-from (.-from sel)
            sel-to (.-to sel)
            has-selection? (not= sel-from sel-to)
            selected-text (when has-selection?
                            (.. view -state (sliceDoc sel-from sel-to)))
            ;; Try multiple ways to detect annotation:
            ;; 1. Check if cursor/selection start is within an annotation
            annotation-at-cursor (find-annotation-at-position doc-text sel-from chunk-id)
            ;; 2. If selection, also check if selection end is within an annotation
            annotation-at-sel-end (when (and has-selection? (not annotation-at-cursor))
                                    (find-annotation-at-position doc-text sel-to chunk-id))
            ;; 3. Check if selected text IS a complete annotation
            annotation-from-selection (when (and has-selection?
                                                  (not annotation-at-cursor)
                                                  (not annotation-at-sel-end))
                                        (parse-annotation-from-text selected-text chunk-id))
            ;; 4. Check at click position (important for widgets where selection may snap elsewhere)
            click-pos (try
                        (.. view (posAtCoords #js {:x (.-clientX e) :y (.-clientY e)}))
                        (catch :default _ nil))
            annotation-at-click (when (and click-pos (not annotation-at-cursor) (not annotation-at-sel-end))
                                  (find-annotation-at-position doc-text click-pos chunk-id))
            annotation (or annotation-at-cursor annotation-at-sel-end annotation-from-selection annotation-at-click)]
        (cond
          ;; Cursor/selection is on annotation - show annotation-specific menu
          annotation
          (context-menu/handle-annotation-context-menu e annotation)

          ;; Has meaningful selection - show selection menu
          (and has-selection? (seq (str/trim (or selected-text ""))))
          (context-menu/handle-context-menu e current-chunk selected-text)

          ;; Default: no menu shown (browser menu already prevented)
          :else nil)))))

(defn- setup-contextmenu-listener!
  "Set up the context menu listener, removing any previous listener"
  [editor-ref-atom editor-view-atom]
  (when-let [ref @editor-ref-atom]
    ;; Remove old listener if exists
    (when-let [old-handler @contextmenu-handler]
      (.removeEventListener ref "contextmenu" old-handler))
    ;; Create and store new handler
    (let [new-handler (create-contextmenu-handler editor-view-atom)]
      (reset! contextmenu-handler new-handler)
      (.addEventListener ref "contextmenu" new-handler))))

(defn- find-annotation-at-position
  "Find if there's an annotation at the given character position in the text.
   Uses JavaScript RegExp with lastIndex to accurately find match positions."
  [text pos chunk-id]
  ;; Use JavaScript RegExp with global flag to get match positions
  (let [pattern (js/RegExp. "\\[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\\]]*)\\]" "g")]
    (loop []
      (let [match (.exec pattern text)]
        (when match
          (let [full-match (aget match 0)
                type-str (aget match 1)
                selected-text (aget match 2)
                priority-str (aget match 3)
                comment-text (aget match 4)
                start (.-index match)
                end (+ start (count full-match))]
            (if (and (>= pos start) (< pos end))
              ;; Found annotation at position
              {:type (keyword type-str)
               :selected-text (str/trim selected-text)
               :priority (annotations/parse-priority priority-str)
               :comment (str/trim (or comment-text ""))
               :start start
               :end end
               :chunk-id chunk-id}
              ;; Keep looking
              (recur))))))))

;; =============================================================================
;; Markup Toggle Checkbox
;; =============================================================================

(defn markup-toggle []
  (let [colors (:colors @settings/settings)]
    [:label {:style {:display "flex"
                     :align-items "center"
                     :gap "6px"
                     :font-size "0.8rem"
                     :color (:text-muted colors)
                     :cursor "pointer"
                     :user-select "none"}}
     [:input {:type "checkbox"
              :checked @annotations/show-markup?
              :on-change (fn [_]
                           (swap! annotations/show-markup? not)
                           ;; Re-run search to update visible matches
                           (when (:visible @editor-search-state)
                             (update-search!)))
              :style {:cursor "pointer"}}]
     (t :show-markup)]))

;; =============================================================================
;; Editor Component
;; =============================================================================

(defn editor-component []
  (let [editor-view (r/atom nil)
        editor-ref (r/atom nil)
        last-chunk-id (r/atom nil)
        last-show-markup (r/atom nil)
        last-refresh-counter (r/atom 0)]
    (r/create-class
     {:display-name "tramando-editor"

      :component-did-mount
      (fn [this]
        (when-let [chunk (model/get-selected-chunk)]
          (let [state (create-editor-state (:content chunk) (:id chunk))
                view (create-editor-view @editor-ref state)]
            (reset! editor-view view)
            (reset! editor-view-ref view) ;; Store in global ref for search
            (reset! last-chunk-id (:id chunk))
            (reset! last-show-markup @annotations/show-markup?)
            ;; Store view in local-editor-view for annotation handler
            (reset! local-editor-view view)
            ;; Set up right-click handler
            (setup-contextmenu-listener! editor-ref editor-view))))

      :component-did-update
      (fn [this old-argv]
        (let [chunk (model/get-selected-chunk)
              show-markup? @annotations/show-markup?
              refresh-counter @events/editor-refresh-counter]
          ;; Recreate editor if chunk changed, show-markup changed, or refresh triggered
          (when (or (and chunk (not= (:id chunk) @last-chunk-id))
                    (not= show-markup? @last-show-markup)
                    (not= refresh-counter @last-refresh-counter))
            (when @editor-view
              (.destroy @editor-view))
            ;; Re-run search on new content
            (when (and chunk (not= (:id chunk) @last-chunk-id))
              (update-search!))
            (when chunk
              (let [state (create-editor-state (:content chunk) (:id chunk))
                    view (create-editor-view @editor-ref state)]
                (reset! editor-view view)
                (reset! editor-view-ref view) ;; Update global ref
                (reset! last-chunk-id (:id chunk))
                (reset! last-show-markup show-markup?)
                (reset! last-refresh-counter refresh-counter)
                ;; Store view in local-editor-view for annotation handler
                (reset! local-editor-view view)
                ;; Set up right-click handler (removes old listener if present)
                (setup-contextmenu-listener! editor-ref editor-view))))))

      :component-will-unmount
      (fn [this]
        ;; Clean up contextmenu listener
        (when-let [ref @editor-ref]
          (when-let [handler @contextmenu-handler]
            (.removeEventListener ref "contextmenu" handler)
            (reset! contextmenu-handler nil)))
        (when @editor-view
          (.destroy @editor-view))
        (reset! editor-view-ref nil))

      :reagent-render
      (fn []
        (let [_selected (model/get-selected-id) ; trigger re-render on selection change
              _show-markup @annotations/show-markup? ; trigger re-render on toggle
              _refresh @events/editor-refresh-counter] ; trigger re-render on external content change
          [:div.editor-container
           {:ref #(reset! editor-ref %)}]))})))

;; =============================================================================
;; ID Input Component
;; =============================================================================

(defn id-input []
  (let [editing? (r/atom false)
        temp-id (r/atom "")
        error-msg (r/atom nil)
        last-chunk-id (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            colors (:colors @settings/settings)
            is-aspect? (model/is-aspect-chunk? chunk)]
        ;; Reset editing state when chunk changes
        (when (and chunk (not= (:id chunk) @last-chunk-id))
          (reset! last-chunk-id (:id chunk))
          (reset! editing? false)
          (reset! error-msg nil))
        ;; Only show ID input for aspect chunks
        (when (and chunk is-aspect?)
          (if @editing?
            ;; Editing mode
            [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "8px"}}
             [:span {:style {:color (:text-muted colors) :font-size "0.85rem"}} "["]
             [:input {:type "text"
                      :value @temp-id
                      :auto-focus true
                      :style {:background "transparent"
                              :border (str "1px solid " (:border colors))
                              :border-radius "3px"
                              :color (:accent colors)
                              :font-size "0.85rem"
                              :font-family "monospace"
                              :padding "2px 6px"
                              :width "150px"
                              :outline "none"}
                      :on-change (fn [e]
                                   (reset! temp-id (.. e -target -value))
                                   (reset! error-msg nil))
                      :on-key-down (fn [e]
                                     (case (.-key e)
                                       "Enter" (let [result (model/rename-chunk-id! (:id chunk) @temp-id)]
                                                 (if (:ok result)
                                                   (reset! editing? false)
                                                   (reset! error-msg (:error result))))
                                       "Escape" (reset! editing? false)
                                       nil))
                      :on-blur (fn []
                                 (when-not @error-msg
                                   (let [result (model/rename-chunk-id! (:id chunk) @temp-id)]
                                     (when (:error result)
                                       (reset! error-msg (:error result))))))}]
             [:span {:style {:color (:text-muted colors) :font-size "0.85rem"}} "]"]
             (when @error-msg
               [:span {:style {:color "#ff6b6b" :font-size "0.75rem" :margin-left "8px"}}
                @error-msg])]

            ;; Display mode
            [:div {:style {:display "flex" :align-items "center" :gap "4px" :margin-bottom "8px"}}
             [:span {:style {:color (:text-muted colors)
                             :font-size "0.85rem"
                             :font-family "monospace"
                             :cursor "pointer"
                             :padding "2px 6px"
                             :border-radius "3px"
                             :background (:editor-bg colors)}
                     :title "Clicca per modificare l'ID"
                     :on-click (fn []
                                 (reset! temp-id (:id chunk))
                                 (reset! error-msg nil)
                                 (reset! editing? true))}
              (str "[" (:id chunk) "]")]
             [:span.help-icon {:title (t :help-id)} "?"]]))))))

;; =============================================================================
;; Summary Input Component
;; =============================================================================

(defn summary-input []
  (let [chunk (model/get-selected-chunk)
        colors (:colors @settings/settings)]
    (when chunk
      [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
       [:input {:type "text"
                :value (:summary chunk)
                :placeholder (t :chunk-title-placeholder)
                :style {:background "transparent"
                        :border (str "1px solid " (:border colors))
                        :border-radius "4px"
                        :color (:text colors)
                        :padding "8px 12px"
                        :font-size "1rem"
                        :flex 1
                        :outline "none"}
                :on-change (fn [e]
                             (model/update-chunk! (:id chunk)
                                                  {:summary (.. e -target -value)}))}]
       [:span.help-icon {:title (t :help-summary)} "?"]])))

;; =============================================================================
;; Aspects Display
;; =============================================================================

(defn aspects-display []
  (let [chunk (model/get-selected-chunk)
        colors (:colors @settings/settings)]
    (when (and chunk (seq (:aspects chunk)))
      [:div.aspects-list
       (doall
        (for [aspect-id (:aspects chunk)]
          (let [aspect (model/get-chunk aspect-id)
                display-name (or (:summary aspect) aspect-id)]
            ^{:key aspect-id}
            [:span.aspect-tag {:style {:background (:editor-bg colors)
                                       :color (:accent colors)
                                       :padding "2px 8px"
                                       :border-radius "3px"
                                       :font-size "0.8rem"
                                       :cursor "pointer"}
                               :title (t :click-to-navigate)
                               :on-click #(events/navigate-to-aspect! aspect-id)}
             (str "@" display-name)])))])))

;; =============================================================================
;; Parent Selector
;; =============================================================================

(defn parent-selector []
  (let [chunk (model/get-selected-chunk)
        error-msg (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            colors (:colors @settings/settings)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          (let [possible-parents (model/get-possible-parents (:id chunk))
                current-parent-id (:parent-id chunk)]
            [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-top "8px"}}
             [:span {:style {:color (:text-muted colors) :font-size "0.8rem" :display "flex" :align-items "center" :gap "4px"}}
              (t :parent)
              [:span.help-icon {:title (t :help-parent)} "?"]]
             [:select {:value (or current-parent-id "")
                       :style {:background (:editor-bg colors)
                               :color (:text colors)
                               :border (str "1px solid " (:border colors))
                               :border-radius "3px"
                               :padding "4px 8px"
                               :font-size "0.8rem"
                               :cursor "pointer"
                               :outline "none"}
                       :on-change (fn [e]
                                    (let [new-parent (let [v (.. e -target -value)]
                                                       (if (= v "") nil v))
                                          result (model/change-parent! (:id chunk) new-parent)]
                                      (if (:error result)
                                        (reset! error-msg (:error result))
                                        (reset! error-msg nil))))}
              [:option {:value ""} (t :root)]
              (doall
               (for [p possible-parents]
                 ^{:key (:id p)}
                 [:option {:value (:id p)}
                  (str (:id p) " - " (subs (:summary p) 0 (min 25 (count (:summary p)))))]))]
             (when @error-msg
               [:span {:style {:color "#ff6b6b" :font-size "0.75rem"}}
                @error-msg])]))))))

;; =============================================================================
;; Delete Button
;; =============================================================================

(defn delete-button []
  (let [confirming? (r/atom false)
        error-msg (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            colors (:colors @settings/settings)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          [:div {:style {:margin-top "12px" :padding-top "12px" :border-top (str "1px solid " (:border colors))}}
           (if @confirming?
             [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
              [:span {:style {:color "#ff6b6b" :font-size "0.85rem"}} (t :confirm)]
              [:button {:style {:background "#ff6b6b"
                                :color "white"
                                :border "none"
                                :padding "4px 12px"
                                :border-radius "3px"
                                :cursor "pointer"
                                :font-size "0.8rem"}
                        :on-click (fn []
                                    (let [result (model/try-delete-chunk! (:id chunk))]
                                      (if (:error result)
                                        (do (reset! error-msg (:error result))
                                            (reset! confirming? false))
                                        (reset! confirming? false))))}
               (t :delete)]
              [:button {:style {:background "transparent"
                                :color (:text-muted colors)
                                :border (str "1px solid " (:text-muted colors))
                                :padding "4px 12px"
                                :border-radius "3px"
                                :cursor "pointer"
                                :font-size "0.8rem"}
                        :on-click #(reset! confirming? false)}
               (t :cancel)]]
             [:div
              [:button {:style {:background "transparent"
                                :color "#ff6b6b"
                                :border "1px solid #ff6b6b"
                                :padding "6px 12px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.85rem"}
                        :on-click #(reset! confirming? true)}
               (t :delete-chunk)]
              (when @error-msg
                [:span {:style {:color "#ff6b6b" :font-size "0.75rem" :margin-left "8px"}}
                 @error-msg])])])))))

;; =============================================================================
;; Tab State
;; =============================================================================

(defonce active-tab (r/atom :edit)) ;; :edit, :refs, :read

(defn set-tab! [tab]
  (reset! active-tab tab))

;; =============================================================================
;; Tab Bar Component
;; =============================================================================

(defn tab-bar []
  (let [current @active-tab
        chunk (model/get-selected-chunk)
        has-children? (seq (model/get-children (:id chunk)))
        is-aspect? (model/is-aspect-chunk? chunk)
        colors (:colors @settings/settings)]
    [:div {:style {:display "flex" :gap "0" :border-bottom (str "1px solid " (:border colors)) :margin-bottom "12px" :align-items "center"}}
     [:button {:style {:background (if (= current :edit) (:editor-bg colors) "transparent")
                       :color (if (= current :edit) (:accent colors) (:text-muted colors))
                       :border "none"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :font-size "0.85rem"
                       :border-bottom (when (= current :edit) (str "2px solid " (:accent colors)))}
               :title (t :help-tab-modifica)
               :on-click #(set-tab! :edit)}
      (t :edit)]
     [:button {:style {:background (if (= current :refs) (:editor-bg colors) "transparent")
                       :color (if (= current :refs) (:accent colors) (:text-muted colors))
                       :border "none"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :font-size "0.85rem"
                       :border-bottom (when (= current :refs) (str "2px solid " (:accent colors)))}
               :title (if is-aspect? (t :help-tab-usato-da) (t :help-tab-figli))
               :on-click #(set-tab! :refs)}
      (if is-aspect? (t :used-by) (t :children))]
     [:button {:style {:background (if (= current :read) (:editor-bg colors) "transparent")
                       :color (if (= current :read) (:accent colors) (:text-muted colors))
                       :border "none"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :font-size "0.85rem"
                       :border-bottom (when (= current :read) (str "2px solid " (:accent colors)))}
               :title (t :help-tab-lettura)
               :on-click #(set-tab! :read)}
      (t :reading)]]))

;; =============================================================================
;; Aspects Manager (add/remove aspects from a chunk)
;; =============================================================================

(defn aspects-manager []
  (let [dropdown-open? (r/atom false)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            all-aspects (model/get-all-aspects)
            current-aspects (:aspects chunk)
            colors (:colors @settings/settings)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          [:div {:style {:margin-top "8px"}}
           [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px" :align-items "center"}}
            ;; Current aspects with remove button
            (doall
             (for [aspect-id current-aspects]
               (let [aspect (first (filter #(= (:id %) aspect-id) (model/get-chunks)))]
                 ^{:key aspect-id}
                 [:span {:style {:background (:editor-bg colors)
                                 :color (:accent colors)
                                 :padding "2px 8px"
                                 :border-radius "3px"
                                 :font-size "0.8rem"
                                 :display "flex"
                                 :align-items "center"
                                 :gap "4px"}}
                  [:span {:style {:cursor "pointer"}
                          :title (t :click-to-navigate)
                          :on-click #(events/navigate-to-aspect! aspect-id)}
                   (str "@" (or (:summary aspect) aspect-id))]
                  [:button {:style {:background "none"
                                    :border "none"
                                    :color (:text-muted colors)
                                    :cursor "pointer"
                                    :padding "0 2px"
                                    :font-size "0.9rem"
                                    :line-height "1"}
                            :title (t :remove)
                            :on-click #(model/remove-aspect-from-chunk! (:id chunk) aspect-id)}
                   "×"]])))

            ;; Add aspect dropdown
            [:div {:style {:position "relative"}}
             [:button {:style {:background "transparent"
                               :color (:text-muted colors)
                               :border (str "1px dashed " (:border colors))
                               :padding "2px 8px"
                               :border-radius "3px"
                               :font-size "0.8rem"
                               :cursor "pointer"}
                       :on-click #(swap! dropdown-open? not)}
              (t :add-aspect)]
             [help/help-icon :add-aspect]
             (when @dropdown-open?
               [:div {:style {:position "absolute"
                              :top "100%"
                              :left 0
                              :background (:sidebar colors)
                              :border (str "1px solid " (:border colors))
                              :border-radius "4px"
                              :min-width "200px"
                              :max-height "200px"
                              :overflow-y "auto"
                              :z-index 100
                              :margin-top "4px"}}
                (let [available (remove #(contains? current-aspects (:id %)) all-aspects)]
                  (if (empty? available)
                    [:div {:style {:padding "8px" :color (:text-muted colors) :font-size "0.8rem"}}
                     (t :all-aspects-added)]
                    (doall
                     (for [aspect available]
                      ^{:key (:id aspect)}
                      [:div {:style {:padding "6px 10px"
                                     :cursor "pointer"
                                     :font-size "0.8rem"
                                     :color (:text colors)}
                             :on-mouse-over #(set! (.. % -target -style -background) (:editor-bg colors))
                             :on-mouse-out #(set! (.. % -target -style -background) "transparent")
                             :on-click (fn []
                                         (model/add-aspect-to-chunk! (:id chunk) (:id aspect))
                                         (reset! dropdown-open? false))}
                       (str "@" (:id aspect) " - " (:summary aspect))]))))])]]])))))

;; =============================================================================
;; References View (who uses this aspect / children list)
;; =============================================================================

(defn refs-view []
  (let [chunk (model/get-selected-chunk)
        is-aspect? (model/is-aspect-chunk? chunk)
        colors (:colors @settings/settings)]
    [:div {:style {:padding "16px" :overflow-y "auto" :flex 1}}
     (if is-aspect?
       ;; Show chunks that use this aspect
       (let [users (model/chunks-using-aspect (:id chunk))]
         [:div
          [:h3 {:style {:color (:text-muted colors) :font-size "0.85rem" :margin-bottom "12px"}}
           (t :used-in-n-chunks (count users))]
          (if (empty? users)
            [:div {:style {:color (:text-muted colors) :font-style "italic"}}
             (t :no-chunk-uses-aspect)]
            [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
             (doall
              (for [c users]
                ^{:key (:id c)}
                [:div {:style {:background (:sidebar colors)
                               :padding "10px 14px"
                               :border-radius "4px"
                               :cursor "pointer"
                               :border-left (str "3px solid " (:accent colors))}
                       :on-click #(model/select-chunk! (:id c))}
                 [:div {:style {:color (:text colors)}}
                  (model/get-chunk-path c)]]))])])

       ;; Show children
       (let [children (model/get-children (:id chunk))
             display-summary (model/expand-summary-macros (:summary chunk) chunk)]
         [:div
          [:h3 {:style {:color (:text-muted colors) :font-size "0.85rem" :margin-bottom "12px"}}
           (t :n-children (count children))]
          (if (empty? children)
            [:div {:style {:color (:text-muted colors) :font-style "italic"}}
             (t :no-children)]
            [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
             (doall
              (for [c children]
               ^{:key (:id c)}
               [:div {:style {:background (:sidebar colors)
                              :padding "10px 14px"
                              :border-radius "4px"
                              :cursor "pointer"
                              :border-left (str "3px solid " (:border colors))}
                      :on-click #(model/select-chunk! (:id c))}
                [:div {:style {:color (:text colors) :margin-bottom "4px"}}
                 (model/expand-summary-macros (:summary c) c)]
                (when (seq (:content c))
                  [:div {:style {:color (:text-muted colors) :font-size "0.85rem"}}
                   (subs (:content c) 0 (min 100 (count (:content c))))
                   (when (> (count (:content c)) 100) "...")])]))])]))]))

;; =============================================================================
;; Markdown Rendering
;; =============================================================================

(defn- render-markdown
  "Convert markdown text to HTML using marked"
  [text]
  (when (seq text)
    ;; Configure marked for inline rendering (no wrapping <p> tags for single lines)
    (marked text #js {:breaks true    ; Convert \n to <br>
                      :gfm true})))   ; GitHub Flavored Markdown

(defn markdown-content
  "Component that renders markdown content safely"
  [content colors]
  (let [html (render-markdown content)]
    (when html
      [:div {:class "markdown-content"
             :style {:color (:text colors)
                     :line-height "1.6"
                     :font-size "0.95rem"
                     :opacity "0.85"}
             :dangerouslySetInnerHTML {:__html html}}])))

;; =============================================================================
;; Read View (expanded content, read-only)
;; =============================================================================

(defn- collect-content [chunk-id depth]
  "Recursively collect content from chunk and all descendants"
  (let [chunk (first (filter #(= (:id %) chunk-id) (model/get-chunks)))
        children (model/get-children chunk-id)]
    (into [{:chunk chunk :depth depth}]
          (mapcat #(collect-content (:id %) (inc depth)) children))))

(defn- render-chunk-block [{:keys [chunk depth]}]
  (let [colors (:colors @settings/settings)
        show-markup? @annotations/show-markup?
        content (if show-markup?
                  (:content chunk)
                  (annotations/strip-annotations (:content chunk)))
        display-summary (model/expand-summary-macros (:summary chunk) chunk)]
    [:div {:style {:margin-left (str (* depth 20) "px")
                   :margin-bottom "16px"
                   :padding-bottom "16px"
                   :border-bottom (when (zero? depth) (str "1px solid " (:border colors)))}}
     [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "8px"}}
      [:h3 {:style {:color (:text colors)
                    :font-size (case depth 0 "1.3rem" 1 "1.1rem" "1rem")
                    :font-weight (if (< depth 2) "600" "500")
                    :margin 0}}
       display-summary]
      (when (seq (:aspects chunk))
        [:span {:style {:color (:text-muted colors) :font-size "0.75rem"}}
         (str "(" (str/join ", " (map #(str "@" %) (:aspects chunk))) ")")])]
     (when (seq content)
       [markdown-content content colors])]))

(defn- render-user-content
  "Render all content for a user chunk with proper keys"
  [user-id colors]
  (let [user-chunk (first (filter #(= (:id %) user-id) (model/get-chunks)))
        all-content (collect-content user-id 0)
        path (model/get-chunk-path user-chunk)]
    [:div {:key user-id
           :style {:margin-bottom "24px" :padding "16px" :background (:sidebar colors) :border-radius "6px"}}
     ;; Path header
     [:div {:style {:color (:accent colors)
                    :font-size "0.85rem"
                    :font-weight "500"
                    :margin-bottom "12px"
                    :padding-bottom "8px"
                    :border-bottom (str "1px solid " (:border colors))}}
      path]
     (doall
      (for [{:keys [chunk depth] :as item} all-content]
        ^{:key (str user-id "-" (:id chunk) "-" depth)}
        [render-chunk-block item]))]))

(defn read-view []
  (let [chunk (model/get-selected-chunk)
        is-aspect? (model/is-aspect-chunk? chunk)
        colors (:colors @settings/settings)
        show-markup? @annotations/show-markup?
        aspect-content (if show-markup?
                         (:content chunk)
                         (annotations/strip-annotations (:content chunk)))
        display-summary (model/expand-summary-macros (:summary chunk) chunk)]
    [:div {:style {:padding "20px" :overflow-y "auto" :flex 1 :background (:background colors)}}
     (if is-aspect?
       ;; For aspects: show the aspect info, then all chunks that use it with their content
       (let [users (model/chunks-using-aspect (:id chunk))]
         [:div
          ;; Aspect header
          [:div {:style {:margin-bottom "24px" :padding-bottom "16px" :border-bottom (str "2px solid " (:accent colors))}}
           [:h2 {:style {:color (:text colors) :font-size "1.4rem" :margin "0 0 8px 0"}}
            display-summary]
           (when (seq aspect-content)
             [markdown-content aspect-content colors])]
          ;; Chunks using this aspect
          (if (empty? users)
            [:div {:style {:color (:text-muted colors) :font-style "italic"}}
             (t :no-chunk-uses-aspect)]
            [:div
             [:h3 {:style {:color (:text-muted colors) :font-size "0.85rem" :margin-bottom "16px"}}
              (t :appears-in-n-chunks (count users))]
             (doall
              (for [user users]
                ^{:key (:id user)}
                [render-user-content (:id user) colors]))])])

       ;; For structural chunks: show hierarchy as before
       (let [all-content (collect-content (:id chunk) 0)]
         (doall
          (for [{:keys [chunk depth] :as item} all-content]
            ^{:key (str (:id chunk) "-" depth)}
            [render-chunk-block item]))))]))

;; =============================================================================
;; Editor Search Bar Component
;; =============================================================================

(defn search-bar []
  (let [local-text (r/atom (:text @editor-search-state))
        local-replace-text (r/atom (:replace-text @editor-search-state))]
    (fn []
      (let [{:keys [visible text case-sensitive regex matches current-index invalid-regex replace-visible replace-text]} @editor-search-state
            colors (:colors @settings/settings)
            has-matches? (seq matches)
            can-replace? (and has-matches? (not invalid-regex))]
        (when visible
          [:div {:style {:background (:sidebar colors)
                         :border-bottom (str "1px solid " (:border colors))}}
           ;; Search row
           [:div {:style {:display "flex"
                          :align-items "center"
                          :gap "8px"
                          :padding "8px 12px"}}
            ;; Search icon
            [:span {:style {:color (:text-muted colors)
                            :font-size "0.9rem"}}
             "🔍"]
            ;; Input field
            [:input {:type "text"
                     :ref #(reset! search-input-ref %)
                     :value @local-text
                     :placeholder (t :search-placeholder)
                     :style {:flex 1
                             :background "transparent"
                             :border (str "1px solid " (if invalid-regex "#ff6b6b" (:border colors)))
                             :border-radius "4px"
                             :color (:text colors)
                             :padding "6px 10px"
                             :font-size "0.85rem"
                             :outline "none"
                             :min-width "120px"
                             :max-width "300px"}
                     :on-change (fn [e]
                                  (let [v (.. e -target -value)]
                                    (reset! local-text v)
                                    (set-search-text! v)))
                     :on-key-down (fn [e]
                                    (case (.-key e)
                                      "Escape" (hide-editor-search!)
                                      "Enter" (if (.-shiftKey e) (prev-match!) (next-match!))
                                      "ArrowDown" (do (.preventDefault e) (next-match!))
                                      "ArrowUp" (do (.preventDefault e) (prev-match!))
                                      "F3" (do (.preventDefault e)
                                               (if (.-shiftKey e) (prev-match!) (next-match!)))
                                      nil))}]
            ;; Case sensitive toggle
            [:button {:style {:background (if case-sensitive (:accent colors) "transparent")
                              :color (if case-sensitive "white" (:text-muted colors))
                              :border (str "1px solid " (if case-sensitive (:accent colors) (:border colors)))
                              :border-radius "3px"
                              :padding "4px 6px"
                              :font-size "0.75rem"
                              :font-weight "600"
                              :cursor "pointer"
                              :min-width "24px"}
                      :title (t :case-sensitive)
                      :on-click toggle-search-case-sensitive!}
             "Aa"]
            ;; Regex toggle
            [:button {:style {:background (if regex (:accent colors) "transparent")
                              :color (if regex "white" (:text-muted colors))
                              :border (str "1px solid " (if regex (:accent colors) (:border colors)))
                              :border-radius "3px"
                              :padding "4px 6px"
                              :font-size "0.75rem"
                              :font-family "monospace"
                              :cursor "pointer"
                              :min-width "24px"}
                      :title (t :regex)
                      :on-click toggle-search-regex!}
             ".*"]
            ;; Navigation arrows
            [:button {:style {:background "transparent"
                              :color (:text-muted colors)
                              :border "none"
                              :padding "4px 8px"
                              :font-size "1rem"
                              :cursor (if (empty? matches) "default" "pointer")
                              :opacity (if (empty? matches) "0.3" "1")}
                      :disabled (empty? matches)
                      :title "Previous (↑)"
                      :on-click prev-match!}
             "‹"]
            [:button {:style {:background "transparent"
                              :color (:text-muted colors)
                              :border "none"
                              :padding "4px 8px"
                              :font-size "1rem"
                              :cursor (if (empty? matches) "default" "pointer")
                              :opacity (if (empty? matches) "0.3" "1")}
                      :disabled (empty? matches)
                      :title "Next (↓)"
                      :on-click next-match!}
             "›"]
            ;; Match counter
            [:span {:style {:color (:text-muted colors)
                            :font-size "0.8rem"
                            :min-width "50px"
                            :text-align "center"}}
             (cond
               invalid-regex (t :search-invalid-regex)
               (< (count text) (if regex 1 2)) ""
               (empty? matches) (t :search-no-results)
               :else (t :search-match-count (inc current-index) (count matches)))]
            ;; Toggle replace button
            [:button {:style {:background "transparent"
                              :color (:text-muted colors)
                              :border "none"
                              :padding "4px 8px"
                              :font-size "0.9rem"
                              :cursor "pointer"}
                      :title "Toggle replace"
                      :on-click toggle-replace-visible!}
             (if replace-visible "↑" "↓")]
            ;; Close button
            [:button {:style {:background "transparent"
                              :color (:text-muted colors)
                              :border "none"
                              :padding "4px 8px"
                              :font-size "1rem"
                              :cursor "pointer"}
                      :title (t :close)
                      :on-click hide-editor-search!}
             "✕"]
            ;; Help icon
            [help/help-icon :help-editor-search]]

           ;; Replace row (only visible when replace-visible is true)
           (when replace-visible
             [:div {:style {:display "flex"
                            :align-items "center"
                            :gap "8px"
                            :padding "8px 12px"
                            :border-top (str "1px solid " (:border colors))}}
              ;; Replace icon
              [:span {:style {:color (:text-muted colors)
                              :font-size "0.9rem"}}
               "↺"]
              ;; Replace input field
              [:input {:type "text"
                       :ref #(reset! replace-input-ref %)
                       :value @local-replace-text
                       :placeholder (t :replace-placeholder)
                       :style {:flex 1
                               :background "transparent"
                               :border (str "1px solid " (:border colors))
                               :border-radius "4px"
                               :color (:text colors)
                               :padding "6px 10px"
                               :font-size "0.85rem"
                               :outline "none"
                               :min-width "120px"
                               :max-width "300px"}
                       :on-change (fn [e]
                                    (let [v (.. e -target -value)]
                                      (reset! local-replace-text v)
                                      (set-replace-text! v)))
                       :on-key-down (fn [e]
                                      (case (.-key e)
                                        "Escape" (hide-editor-search!)
                                        "Enter" (when can-replace? (replace-current-match!))
                                        nil))}]
              ;; Replace button
              [:button {:style {:background (if can-replace? (:accent colors) (:editor-bg colors))
                                :color (if can-replace? "white" (:text-muted colors))
                                :border "none"
                                :padding "6px 12px"
                                :border-radius "4px"
                                :font-size "0.8rem"
                                :cursor (if can-replace? "pointer" "default")
                                :opacity (if can-replace? "1" "0.5")}
                        :disabled (not can-replace?)
                        :on-click replace-current-match!}
               (t :replace-button)]
              ;; Replace all button
              [:button {:style {:background (if can-replace? "transparent" (:editor-bg colors))
                                :color (if can-replace? (:text colors) (:text-muted colors))
                                :border (str "1px solid " (if can-replace? (:border colors) "transparent"))
                                :padding "6px 12px"
                                :border-radius "4px"
                                :font-size "0.8rem"
                                :cursor (if can-replace? "pointer" "default")
                                :opacity (if can-replace? "1" "0.5")}
                        :disabled (not can-replace?)
                        :on-click replace-all-matches!}
               (t :replace-all-button)]
              ;; Help icon
              [help/help-icon :help-replace]])])))))

;; =============================================================================
;; Tab Content
;; =============================================================================

(defn tab-content []
  (case @active-tab
    :edit [:div {:style {:display "flex" :flex-direction "column" :flex 1 :overflow "hidden"}}
           [search-bar]
           [editor-component]]
    :refs [refs-view]
    :read [read-view]
    nil))
