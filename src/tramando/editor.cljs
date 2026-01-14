(ns tramando.editor
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.annotations :as annotations]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]
            [tramando.context-menu :as context-menu]
            [tramando.events :as events]
            [tramando.chunk-selector :as selector]
            [tramando.auth :as auth]
            [tramando.api :as api]
            [tramando.store.protocol :as protocol]
            [tramando.store.remote :as remote-store]
            [tramando.store.local :as local-store]
            ["@codemirror/state" :refer [EditorState StateField StateEffect RangeSetBuilder Prec]]
            ["@codemirror/view" :refer [EditorView keymap highlightActiveLine
                                        drawSelection Decoration ViewPlugin WidgetType]]
            ["@codemirror/commands" :refer [defaultKeymap history
                                            indentWithTab undo redo undoDepth redoDepth]]
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
                                  :caretColor "var(--color-accent)"
                                  :fontFamily "'SF Mono', 'Fira Code', 'Consolas', monospace"
                                  :fontSize "14px"
                                  :lineHeight "1.8"}
               ".cm-cursor" #js {:borderLeftColor "var(--color-accent)"}
               "&.cm-focused .cm-selectionBackground" #js {:backgroundColor "rgba(74, 159, 142, 0.3)"}
               ".cm-selectionBackground" #js {:backgroundColor "rgba(74, 159, 142, 0.2)"}
               ".cm-activeLine" #js {:backgroundColor "var(--color-accent-muted)"}
               ".cm-gutters" #js {:backgroundColor "var(--color-sidebar)"
                                  :color "var(--color-text-muted)"
                                  :borderRight "1px solid var(--color-border)"}
               ".cm-activeLineGutter" #js {:backgroundColor "var(--color-editor-bg)"}
               "&" #js {:backgroundColor "var(--color-background)"}
               ;; Annotation styles - full markup mode (all use accent color)
               ".cm-annotation-todo" #js {:backgroundColor "var(--color-accent-muted)"
                                          :borderRadius "3px"}
               ".cm-annotation-note" #js {:backgroundColor "var(--color-accent-muted)"
                                          :borderRadius "3px"}
               ".cm-annotation-fix" #js {:backgroundColor "var(--color-accent-muted)"
                                         :borderRadius "3px"}
               ".cm-annotation-proposal" #js {:backgroundColor "var(--color-accent-muted)"
                                              :borderRadius "3px"}
               ;; Reading mode - highlighted text only
               ".cm-annotation-text-todo" #js {:backgroundColor "rgba(74, 159, 142, 0.3)"
                                               :borderRadius "2px"
                                               :padding "0 2px"}
               ".cm-annotation-text-note" #js {:backgroundColor "rgba(74, 159, 142, 0.3)"
                                               :borderRadius "2px"
                                               :padding "0 2px"}
               ".cm-annotation-text-fix" #js {:backgroundColor "rgba(74, 159, 142, 0.3)"
                                              :borderRadius "2px"
                                              :padding "0 2px"}
               ".cm-annotation-text-proposal" #js {:backgroundColor "rgba(74, 159, 142, 0.3)"
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
               ;; Proposal text (when selected)
               ".cm-proposal-text" #js {:borderBottom "2px dotted var(--color-accent)"}
               ;; AI selected annotation in markup mode
               ".cm-ai-selected" #js {:backgroundColor "var(--color-accent-muted)"
                                      :borderRadius "3px"}
               ;; PROPOSAL selected annotation in markup mode
               ".cm-proposal-selected" #js {:backgroundColor "var(--color-accent-muted)"
                                            :borderRadius "3px"}
               ;; Search match highlighting
               ".cm-search-match" #js {:backgroundColor "rgba(74, 159, 142, 0.3)"
                                       :borderRadius "2px"}
               ".cm-search-match-current" #js {:backgroundColor "rgba(74, 159, 142, 0.5)"
                                               :borderRadius "2px"
                                               :boxShadow "0 0 2px rgba(74, 159, 142, 0.8)"}
               ;; Flash highlight for navigation
               ".cm-annotation-flash" #js {:animation "annotation-flash 2.5s ease-out"
                                           :borderRadius "3px"}
               ;; Aspect link styles - markup mode
               ".cm-aspect-link" #js {:backgroundColor "var(--color-accent-muted)"
                                      :borderRadius "3px"
                                      :padding "0 2px"}
               ;; Aspect link styles - reading mode (resolved name)
               ".cm-aspect-link-resolved" #js {:backgroundColor "rgba(74, 159, 142, 0.2)"
                                               :borderBottom "1px dashed var(--color-accent)"
                                               :borderRadius "2px"
                                               :padding "0 3px"
                                               :cursor "pointer"}
               ;; Aspect link - not found
               ".cm-aspect-link-missing" #js {:backgroundColor "rgba(74, 159, 142, 0.1)"
                                              :borderBottom "1px dashed var(--color-text-muted)"
                                              :borderRadius "2px"
                                              :padding "0 2px"
                                              :color "var(--color-text-muted)"}}))

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
;; editor-view-ref is defined in events namespace to avoid circular deps

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
  (when-let [view @events/editor-view-ref]
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

(defn- find-edn-text-value-range
  "Find the start and end positions of the :text value string in an EDN annotation.
   Returns [text-content-start text-content-end] where content excludes quotes.
   For proposals with sel=1, finds :proposed value instead."
  [ann-str is-proposal? sel]
  (let [;; Determine which key to look for
        key-to-find (if (and is-proposal? (= sel 1)) ":proposed" ":text")
        key-idx (str/index-of ann-str key-to-find)]
    (when key-idx
      ;; Find the opening quote after the key
      (let [after-key (+ key-idx (count key-to-find))
            quote-idx (str/index-of ann-str "\"" after-key)]
        (when quote-idx
          ;; Find the closing quote, handling escapes
          (loop [i (inc quote-idx)
                 escape false]
            (when (< i (count ann-str))
              (let [c (nth ann-str i)]
                (cond
                  escape (recur (inc i) false)
                  (= c \\) (recur (inc i) true)
                  (= c \") [(inc quote-idx) i]  ;; Return positions excluding quotes
                  :else (recur (inc i) false))))))))))

(defn- find-alt-value-range
  "Find the range of a specific alternative in the :alts vector.
   Returns [start end] positions within ann-str, or nil if not found."
  [ann-str alt-index]
  (let [alts-idx (str/index-of ann-str ":alts")]
    (when alts-idx
      ;; Find opening bracket of vector
      (let [bracket-idx (str/index-of ann-str "[" alts-idx)]
        (when bracket-idx
          ;; Skip through alternatives until we reach the right index
          (loop [i (inc bracket-idx)
                 current-alt 0
                 in-string false
                 escape false
                 string-start nil]
            (when (< i (count ann-str))
              (let [c (nth ann-str i)]
                (cond
                  ;; Handle escape
                  escape (recur (inc i) current-alt in-string false string-start)
                  (= c \\) (recur (inc i) current-alt in-string true string-start)

                  ;; Inside string
                  (and in-string (= c \"))
                  (if (= current-alt alt-index)
                    ;; Found our alternative - return range (excluding quotes)
                    [(inc string-start) i]
                    ;; Move to next alternative
                    (recur (inc i) (inc current-alt) false false nil))

                  in-string
                  (recur (inc i) current-alt in-string false string-start)

                  ;; Start of string
                  (= c \")
                  (recur (inc i) current-alt true false i)

                  ;; End of vector
                  (= c \]) nil

                  :else
                  (recur (inc i) current-alt in-string false string-start))))))))))

(defn- get-hidden-ranges
  "Returns vector of {:from :to} for hidden parts of annotations.
   When show-markup is false, only the :text content is shown.
   Supports EDN format [!TYPE{:text \"...\"}]"
  [text]
  (let [ranges (atom [])
        edn-annotations (annotations/parse-annotations text)]
    ;; Process EDN annotations - hide everything except the displayed text
    (doseq [ann edn-annotations]
      (let [start (:start ann)
            end (:end ann)
            ann-str (subs text start end)
            is-proposal? (annotations/is-proposal? ann)
            is-ai-done? (annotations/is-ai-done? ann)
            sel (cond
                  is-proposal? (annotations/get-proposal-selection ann)
                  is-ai-done? (annotations/get-ai-selection ann)
                  :else 0)
            ;; Find the range to display
            text-range (cond
                         ;; AI-DONE with sel > 0 - find in :alts vector
                         (and is-ai-done? (pos? sel))
                         (find-alt-value-range ann-str (dec sel))  ;; alts is 0-indexed, sel is 1-indexed

                         ;; Proposal with sel=1 - find :proposed
                         (and is-proposal? (= sel 1))
                         (find-edn-text-value-range ann-str true 1)

                         ;; Default - find :text
                         :else
                         (find-edn-text-value-range ann-str false 0))]
        (when-let [[rel-text-start rel-text-end] text-range]
          (let [text-start (+ start rel-text-start)
                text-end (+ start rel-text-end)]
            ;; Hide from annotation start to text start
            (swap! ranges conj {:from start :to text-start})
            ;; Hide from text end to annotation end
            (swap! ranges conj {:from text-end :to end})))))
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
     (when-let [view @events/editor-view-ref]
       (.dispatch view #js {})))))

(defn update-search!
  "Update search matches based on current state"
  []
  (when-let [view @events/editor-view-ref]
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
  (when-let [view @events/editor-view-ref]
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
     (when-let [view @events/editor-view-ref]
       (let [doc-text (.. view -state -doc (toString))
             ;; Use the annotation parser to find the annotation containing this text
             all-annotations (annotations/parse-annotations doc-text)
             matching-ann (first (filter #(= (annotations/get-text %) selected-text) all-annotations))]
         ;; DEBUG
         (js/console.log "=== navigate-to-annotation! ===")
         (js/console.log "selected-text:" selected-text)
         (js/console.log "all-annotations count:" (count all-annotations))
         (js/console.log "matching-ann:" (pr-str matching-ann))
         (when matching-ann
           (let [idx (:start matching-ann)
                 end-idx (:end matching-ann)]
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
                  (when @events/editor-view-ref
                    (.dispatch @events/editor-view-ref #js {})))
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
  (when-let [view @events/editor-view-ref]
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
  (when-let [view @events/editor-view-ref]
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
;; Scroll to Pattern (for navigation from "Usato da")
;; =============================================================================

(defonce pending-scroll-pattern (atom nil))

(defn scroll-to-pattern!
  "Scroll to the first occurrence of a pattern in the editor.
   Pattern should be a string (will be escaped for regex)."
  [pattern]
  (when-let [^js view @events/editor-view-ref]
    (let [doc-text (.. view -state -doc (toString))
          ;; Escape special regex characters and create pattern
          escaped (-> pattern
                      (str/replace #"[\[\]\\^$.|?*+(){}]" "\\$&"))
          regex (js/RegExp. escaped "i")
          match (.exec regex doc-text)]
      (when match
        (let [from (.-index match)
              to (+ from (count (aget match 0)))]
          ;; Select the match and scroll into view
          (.dispatch view #js {:selection #js {:anchor from :head to}
                               :scrollIntoView true})
          (.focus view))))))

(defn set-pending-scroll-pattern!
  "Set a pattern to scroll to after the next editor load."
  [pattern]
  (reset! pending-scroll-pattern pattern))

(defn execute-pending-scroll!
  "Execute pending scroll if there is one. Called after editor loads."
  []
  (when-let [pattern @pending-scroll-pattern]
    (reset! pending-scroll-pattern nil)
    ;; Small delay to ensure editor is fully rendered
    (js/setTimeout #(scroll-to-pattern! pattern) 100)))

;; Register the pending scroll function with events module
(events/set-pending-scroll-fn! set-pending-scroll-pattern!)

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

;; Syntax: [!TYPE@author:selected text:priority:comment]
(def ^:private annotation-regex (js/RegExp. "\\[!(TODO|NOTE|FIX|PROPOSAL)(?:@([^:]+))?:([^:]*):([^:]*):([^\\]]*)\\]" "g"))

;; Syntax: [@aspect-id] - links to aspects
(def ^:private aspect-link-regex (js/RegExp. "\\[@([^\\]]+)\\]" "g"))

(defn- find-aspect-by-id
  "Find an aspect chunk by its ID. Returns the chunk or nil."
  [aspect-id]
  (first (filter #(= (:id %) aspect-id) (model/get-all-aspects))))

(defn- get-aspect-display-name
  "Get display name for an aspect: 'Name - Summary' or just 'Summary'"
  [aspect]
  (when aspect
    (let [summary (:summary aspect)
          ;; Try to extract a title from content if it starts with #
          content (:content aspect)
          title-from-content (when (and content (str/starts-with? (str/trim content) "#"))
                               (-> content str/trim (str/split #"\n") first (str/replace #"^#+ *" "")))]
      (or title-from-content summary (:id aspect)))))

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
        text (.toString doc)
        ;; Parse all annotations using the EDN parser
        all-annotations (annotations/parse-annotations text)]
    ;; Process each annotation
    (doseq [ann all-annotations]
      (let [start (:start ann)
            end (:end ann)
            type (:type ann)
            type-lower (str/lower-case (name type))
            selected-text (annotations/get-text ann)
            author (annotations/get-author ann)
            comment (annotations/get-comment ann)
            priority (annotations/get-priority ann)
            ;; Check annotation types
            is-ai-done? (annotations/is-ai-done? ann)
            is-ai-pending? (annotations/is-ai-pending? ann)
            is-proposal? (annotations/is-proposal? ann)
            ;; AI-DONE data
            ai-sel (or (annotations/get-ai-selection ann) 0)
            ai-alts (or (annotations/get-ai-alternatives ann) [])
            has-ai-selection? (and is-ai-done? (pos? ai-sel) (<= ai-sel (count ai-alts)))
            alt-text (when has-ai-selection? (nth ai-alts (dec ai-sel)))
            ;; PROPOSAL data
            proposal-sel (or (annotations/get-proposal-selection ann) 0)
            has-proposal-selection? (and is-proposal? (pos? proposal-sel))
            proposal-text (when has-proposal-selection? (annotations/get-proposed-text ann))
            ;; Get author display name
            author-display (when author (auth/get-cached-display-name author))
            ;; Build tooltip
            base-tooltip (cond
                           has-ai-selection? (str "AI alternative #" ai-sel " selected")
                           is-proposal? (str (t :proposal) ": " (or (annotations/get-proposed-text ann) ""))
                           (and priority comment) (str "[" priority "] " comment)
                           comment comment
                           priority (str "[" priority "]")
                           :else (str (name type) " annotation"))
            tooltip (if author-display (str author-display ": " base-tooltip) base-tooltip)]
        (if show-markup?
          ;; Markup mode: highlight entire annotation
          (let [css-class (cond
                            has-ai-selection? "cm-ai-selected"
                            is-ai-done? "cm-annotation-note"
                            has-proposal-selection? "cm-proposal-selected"
                            :else (str "cm-annotation-" type-lower))]
            (.push builder (.range (.mark Decoration #js {:class css-class}) start end)))
          ;; Reading mode: hide markup, show only text
          (let [annotation-text (subs text start end)
                spans-lines? (str/includes? annotation-text "\n")
                alt-spans-lines? (and alt-text (str/includes? alt-text "\n"))
                proposal-spans-lines? (and proposal-text (str/includes? proposal-text "\n"))]
            (cond
              ;; AI-DONE with selection (single line): use widget with strikethrough
              (and has-ai-selection? (not spans-lines?) (not alt-spans-lines?))
              (.push builder (.range (.replace Decoration
                                               #js {:widget (create-ai-alternative-widget selected-text alt-text)})
                                     start end))

              ;; AI-DONE with selection but multiline: hide annotation, show alternative
              (and has-ai-selection? (or spans-lines? alt-spans-lines?))
              (do
                (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"}) start end))
                (.push builder (.range (.widget Decoration
                                                #js {:widget (create-text-widget alt-text "cm-ai-alternative-text")
                                                     :side 1})
                                       start)))

              ;; PROPOSAL with selection (sel=1): show proposed text
              (and has-proposal-selection? (not spans-lines?) (not proposal-spans-lines?))
              (.push builder (.range (.replace Decoration
                                               #js {:widget (create-ai-alternative-widget selected-text proposal-text)})
                                     start end))

              ;; PROPOSAL with selection but multiline
              (and has-proposal-selection? (or spans-lines? proposal-spans-lines?))
              (do
                (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"}) start end))
                (.push builder (.range (.widget Decoration
                                                #js {:widget (create-text-widget proposal-text "cm-proposal-text")
                                                     :side 1})
                                       start)))

              ;; Normal annotation: hide entire markup except text, highlight text
              :else
              (let [;; For EDN format, we need to find where :text "..." appears
                    ;; and show only that text. Use hidden ranges from get-hidden-ranges.
                    hidden-ranges (get-hidden-ranges annotation-text)]
                (if (seq hidden-ranges)
                  ;; Use pre-calculated hidden ranges
                  (doseq [{:keys [from to]} hidden-ranges]
                    (let [abs-from (+ start from)
                          abs-to (+ start to)]
                      (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"})
                                             abs-from abs-to))))
                  ;; Fallback: hide entire annotation and show text as widget
                  (do
                    (.push builder (.range (.mark Decoration #js {:class "cm-annotation-hidden"}) start end))
                    (.push builder (.range (.widget Decoration
                                                    #js {:widget (create-text-widget selected-text (str "cm-annotation-text-" type-lower))
                                                         :side 1
                                                         :attributes #js {:title tooltip}})
                                           start))))))))))
    ;; Process aspect links [@id]
    (set! (.-lastIndex aspect-link-regex) 0)
    (loop []
      (when-let [match (.exec aspect-link-regex text)]
        (let [full-match (aget match 0)
              aspect-id (aget match 1)
              start (.-index match)
              end (+ start (count full-match))
              aspect (find-aspect-by-id aspect-id)
              display-name (get-aspect-display-name aspect)]
          (if show-markup?
            ;; Markup mode: highlight the whole [@id]
            (.push builder (.range (.mark Decoration #js {:class "cm-aspect-link"
                                                          :attributes #js {:title (or display-name (str "Aspetto non trovato: " aspect-id))}})
                                   start end))
            ;; Reading mode: replace with aspect name or show as missing
            (if aspect
              ;; Found - replace with widget showing the name
              (.push builder (.range (.replace Decoration
                                               #js {:widget (create-text-widget display-name "cm-aspect-link-resolved")})
                                     start end))
              ;; Not found - show as missing
              (.push builder (.range (.replace Decoration
                                               #js {:widget (create-text-widget (str "?" aspect-id) "cm-aspect-link-missing")})
                                     start end)))))
        (recur)))
    ;; Sort by position and create DecorationSet
    (.sort builder (fn [a b] (- (.-from a) (.-from b))))
    (.set Decoration builder true)))

(def annotation-highlight
  (.define ViewPlugin
           (fn [^js view]
             (let [show? @annotations/show-markup?]
               #js {:decorations (create-annotation-decorations view show?)
                    :showMarkup show?
                    :update (fn [^js vu] ; vu = ViewUpdate
                              (this-as this
                                (let [^js this this
                                      current-show? @annotations/show-markup?]
                                  (when (or (.-docChanged vu)
                                            (not= (.-showMarkup this) current-show?))
                                    (set! (.-showMarkup this) current-show?)
                                    (set! (.-decorations this)
                                          (create-annotation-decorations (.-view vu) current-show?))))))}))
           #js {:decorations (fn [^js v] (.-decorations v))}))

;; =============================================================================
;; Update Handler
;; =============================================================================

(defn make-update-listener [chunk-id]
  (.of (.-updateListener EditorView)
       (fn [^js update]
         (when (.-docChanged update)
           (let [content (.. update -state -doc (toString))
                 chunk (model/get-chunk chunk-id)
                 old-content (:content chunk)]
             ;; Only process if content actually changed
             ;; (editor recreation can fire docChanged even without real change)
             (when (not= content old-content)
               (let [owner (:owner chunk)
                     current-user (model/get-current-owner)
                     ;; Assign ownership if chunk has no real owner yet (legacy chunks with "local")
                     should-claim? (and (= "local" owner)
                                        (not= "local" current-user))
                     changes (if should-claim?
                               {:content content :owner current-user}
                               {:content content})]
                 (model/update-chunk! chunk-id changes)
                 ;; Clear the synced flag - user made new changes that aren't synced yet
                 (when (remote-store/get-project-id)
                   (remote-store/clear-local-changes-synced!)
                   ;; Notify presence when editing in remote mode
                   (remote-store/notify-editing! chunk-id))))))
         js/undefined)))

;; Forward declarations for functions defined later in the file
(declare find-annotation-at-position)
(declare parse-annotation-from-text)
(declare build-parent-tree)
(declare build-aspects-tree)
(declare adjust-menu-position)

(defn- find-aspect-link-at-position
  "Find aspect link [@id] at a specific position in text.
   Returns {:aspect-id :start :end :aspect} or nil."
  [text pos]
  (let [pattern (js/RegExp. "\\[@([^\\]]+)\\]" "g")]
    (loop []
      (when-let [match (.exec pattern text)]
        (let [start (.-index match)
              end (+ start (count (aget match 0)))
              aspect-id (aget match 1)]
          (if (and (>= pos start) (< pos end))
            {:aspect-id aspect-id
             :start start
             :end end
             :aspect (find-aspect-by-id aspect-id)}
            (recur)))))))

(defn make-contextmenu-extension [chunk-id]
  "CodeMirror extension for handling right-click context menu"
  (.domEventHandlers EditorView
    #js {:contextmenu
         (fn [event ^js view]
           (let [^js state (.-state view)
                 sel (.. state -selection -main)
                 sel-from (.-from sel)
                 sel-to (.-to sel)
                 has-selection? (not= sel-from sel-to)
                 doc-text (.. state -doc (toString))
                 chunk (model/get-selected-chunk)
                 x (.-clientX event)
                 y (.-clientY event)
                 [adj-x adj-y] (adjust-menu-position x y)
                 ;; Get position where user actually clicked
                 click-pos (try
                             (.posAtCoords view #js {:x x :y y})
                             (catch :default _ nil))
                 ;; Check for aspect link at click position
                 aspect-link-at-click (when click-pos
                                        (find-aspect-link-at-position doc-text click-pos))
                 ;; Check for annotation at click position, cursor, or selection
                 annotation-at-click (when (and click-pos (not aspect-link-at-click))
                                       (find-annotation-at-position doc-text click-pos chunk-id))
                 annotation-at-cursor (when (not aspect-link-at-click)
                                        (find-annotation-at-position doc-text sel-from chunk-id))]
             (.preventDefault event)
             (cond
               ;; Aspect link at click position - show aspect link menu
               aspect-link-at-click
               (let [aspect-menu-height 60
                     viewport-height (.-innerHeight js/window)
                     aspect-adj-y (if (> (+ y aspect-menu-height) viewport-height)
                                    (- y aspect-menu-height 10)
                                    y)]
                 (context-menu/show-menu! adj-x aspect-adj-y nil nil
                                          :aspect-link aspect-link-at-click
                                          :menu-type :aspect-link))

               ;; Annotation at click position - show annotation menu
               annotation-at-click
               (let [priority (:priority annotation-at-click)
                     menu-type (cond
                                 (annotations/is-ai-done? annotation-at-click) :annotation-ai-done
                                 (= priority :AI) :annotation-ai-pending
                                 (annotations/is-proposal? annotation-at-click) :annotation-proposal
                                 :else :annotation-normal)
                     ;; Use appropriate menu height for position adjustment
                     menu-height (case menu-type
                                   :annotation-ai-done 300
                                   :annotation-ai-pending 60
                                   :annotation-proposal 350
                                   :annotation-normal 100
                                   300)
                     viewport-height (.-innerHeight js/window)
                     ann-adj-y (if (> (+ y menu-height) viewport-height)
                                 (- y menu-height 10)
                                 y)]
                 (context-menu/show-menu! adj-x ann-adj-y nil nil
                                          :annotation annotation-at-click
                                          :menu-type menu-type))

               ;; Annotation at cursor position - show annotation menu
               annotation-at-cursor
               (let [priority (:priority annotation-at-cursor)
                     menu-type (cond
                                 (annotations/is-ai-done? annotation-at-cursor) :annotation-ai-done
                                 (= priority :AI) :annotation-ai-pending
                                 (annotations/is-proposal? annotation-at-cursor) :annotation-proposal
                                 :else :annotation-normal)
                     menu-height (case menu-type
                                   :annotation-ai-done 300
                                   :annotation-ai-pending 60
                                   :annotation-proposal 350
                                   :annotation-normal 100
                                   300)
                     viewport-height (.-innerHeight js/window)
                     ann-adj-y (if (> (+ y menu-height) viewport-height)
                                 (- y menu-height 10)
                                 y)]
                 (context-menu/show-menu! adj-x ann-adj-y nil nil
                                          :annotation annotation-at-cursor
                                          :menu-type menu-type))

               ;; Has selection - show selection menu
               has-selection?
               (let [selected-text (.. state (sliceDoc sel-from sel-to))
                     annotation-at-end (find-annotation-at-position doc-text sel-to chunk-id)
                     annotation-from-text (parse-annotation-from-text selected-text chunk-id)
                     annotation (or annotation-at-end annotation-from-text)]
                 (when (seq (str/trim selected-text))
                   (if annotation
                     (let [priority (:priority annotation)
                           menu-type (cond
                                       (annotations/is-ai-done? annotation) :annotation-ai-done
                                       (= priority :AI) :annotation-ai-pending
                                       (annotations/is-proposal? annotation) :annotation-proposal
                                       :else :annotation-normal)
                           menu-height (case menu-type
                                         :annotation-ai-done 300
                                         :annotation-ai-pending 60
                                         :annotation-proposal 350
                                         :annotation-normal 100
                                         300)
                           viewport-height (.-innerHeight js/window)
                           ann-adj-y (if (> (+ y menu-height) viewport-height)
                                       (- y menu-height 10)
                                       y)]
                       (context-menu/show-menu! adj-x ann-adj-y nil nil
                                                :annotation annotation
                                                :menu-type menu-type))
                     (context-menu/show-menu! adj-x adj-y chunk selected-text))))

               ;; Nothing to show - no action needed
               :else nil)
             ;; Return true to indicate event was handled
             true))}))

;; =============================================================================
;; Selection-based Context Menu
;; =============================================================================

(defonce last-selection-range (atom nil))

(defn- adjust-menu-position
  "Adjust menu position to avoid clipping at screen edges"
  [x y]
  (let [viewport-width (.-innerWidth js/window)
        viewport-height (.-innerHeight js/window)
        menu-width 220  ;; approximate menu width
        menu-height 300 ;; approximate max menu height
        adjusted-x (if (> (+ x menu-width) viewport-width)
                     (- viewport-width menu-width 10)
                     x)
        adjusted-y (if (> (+ y menu-height) viewport-height)
                     (- y menu-height 10) ;; show above instead
                     y)]
    [adjusted-x adjusted-y]))

(defn open-context-menu-for-selection!
  "Open context menu for current selection or annotation at cursor.
   Called by keyboard shortcut (Cmd+M). Returns true if menu was opened."
  []
  (when-let [^js view @events/editor-view-ref]
    (let [state (.-state view)
          sel (.. state -selection -main)
          sel-from (.-from sel)
          sel-to (.-to sel)
          has-selection? (not= sel-from sel-to)
          chunk (model/get-selected-chunk)
          chunk-id (:id chunk)
          doc-text (.. state -doc (toString))
          ;; Check for annotation at cursor position OR at selection start
          annotation-at-cursor (find-annotation-at-position doc-text sel-from chunk-id)
          ;; Also check if selection end is on annotation
          annotation-at-end (when has-selection?
                              (find-annotation-at-position doc-text sel-to chunk-id))
          ;; Check if selected text itself is an annotation
          selected-text (when has-selection?
                          (.. state (sliceDoc sel-from sel-to)))
          annotation-from-text (when has-selection?
                                 (parse-annotation-from-text selected-text chunk-id))
          ;; Use any annotation found
          annotation (or annotation-at-cursor annotation-at-end annotation-from-text)]
      (cond
        ;; Found an annotation - show annotation menu
        annotation
        (when-let [coords (.coordsAtPos view sel-from)]
          (let [[adj-x adj-y] (adjust-menu-position (.-left coords) (+ (.-bottom coords) 5))]
            (context-menu/show-menu! adj-x adj-y
                                     nil nil
                                     :annotation annotation
                                     :menu-type :annotation)
            true))

        ;; Has selection (not an annotation) - show selection menu
        (and has-selection? (seq (str/trim (or selected-text ""))))
        (when-let [coords (.coordsAtPos view sel-to)]
          (let [[adj-x adj-y] (adjust-menu-position (.-left coords) (+ (.-bottom coords) 5))]
            (context-menu/show-menu! adj-x adj-y chunk selected-text)
            true))

        ;; No selection, no annotation
        :else false))))

(defn make-selection-listener [chunk-id]
  "Create a listener that shows context menu when text is selected.
   Uses selectionSet to detect selection changes."
  (.of (.-updateListener EditorView)
       (fn [^js update]
         ;; Only react when selection changes (not on every keystroke)
         (when (.-selectionSet update)
           (let [view (.-view update)
                 state (.-state update)
                 sel (.. state -selection -main)
                 sel-from (.-from sel)
                 sel-to (.-to sel)
                 has-selection? (not= sel-from sel-to)]
             (if has-selection?
               ;; Selection made - check what kind and show appropriate menu
               (let [selected-text (.. state (sliceDoc sel-from sel-to))
                     chunk (model/get-selected-chunk)
                     doc-text (.. state -doc (toString))
                     ;; Check for annotations
                     annotation-at-start (find-annotation-at-position doc-text sel-from chunk-id)
                     annotation-at-end (find-annotation-at-position doc-text sel-to chunk-id)
                     annotation-from-text (parse-annotation-from-text selected-text chunk-id)
                     annotation (or annotation-at-start annotation-at-end annotation-from-text)]
                 (when (seq (str/trim selected-text))
                   (when-let [coords (.coordsAtPos view sel-to)]
                     (let [[adj-x adj-y] (adjust-menu-position (.-left coords) (+ (.-bottom coords) 5))]
                       (if annotation
                         ;; Show annotation menu
                         (context-menu/show-menu! adj-x adj-y nil nil
                                                  :annotation annotation
                                                  :menu-type :annotation)
                         ;; Show selection menu
                         (context-menu/show-menu! adj-x adj-y chunk selected-text))))))
               ;; Selection cleared - hide menu
               (context-menu/hide-menu!))))
         js/undefined)))

;; =============================================================================
;; Editor Creation
;; =============================================================================

;; Custom keymap for seamless undo/redo
;; After autosave, local changes are synced to server - undo should go directly to server.
;; Otherwise, use CodeMirror undo first, then fall back to server/local undo.

(def undo-redo-keymap
  #js [#js {:key "Mod-z"
            :run (fn [view]
                   (let [depth (undoDepth (.-state view))
                         ;; If local changes were synced, bypass CodeMirror and go to server
                         synced? (remote-store/local-changes-synced?)
                         has-server-undo? (and (remote-store/get-project-id)
                                               (remote-store/server-can-undo?)
                                               (model/is-project-owner?))]
                     (cond
                       ;; Priority 1: If synced to server and server has undo, go directly to server
                       ;; This ensures undo after autosave reverts to pre-modification state
                       (and synced? has-server-undo?)
                       (do
                         (js/console.log "Local changes synced, using server undo...")
                         (remote-store/server-undo!)
                         true)

                       ;; Priority 2: CodeMirror has local undo (not yet synced)
                       ;; Just use standard CodeMirror undo
                       (pos? depth)
                       (undo view)

                       ;; Priority 3: Server undo available (CodeMirror exhausted)
                       has-server-undo?
                       (do
                         (js/console.log "CodeMirror undo exhausted, trying server undo...")
                         (remote-store/server-undo!)
                         true)

                       ;; Priority 4: Local mode with local undo available
                       (and (not (remote-store/get-project-id))
                            (local-store/local-can-undo?))
                       (do
                         (js/console.log "Using local undo stack...")
                         (local-store/pop-local-undo!)
                         true)

                       ;; No undo available anywhere
                       :else false)))}
       #js {:key "Mod-Shift-z"
            :run (fn [view]
                   (let [depth (redoDepth (.-state view))]
                     (if (pos? depth)
                       ;; CodeMirror has redo available, use it
                       (redo view)
                       ;; No local redo - check remote or local mode
                       (cond
                         ;; Remote mode with server redo available
                         (and (remote-store/get-project-id)
                              (remote-store/server-can-redo?)
                              (model/is-project-owner?))
                         (do
                           (js/console.log "CodeMirror redo exhausted, trying server redo...")
                           (remote-store/server-redo!)
                           true)

                         ;; Local mode with local redo available
                         (and (not (remote-store/get-project-id))
                              (local-store/local-can-redo?))
                         (do
                           (js/console.log "CodeMirror redo exhausted, trying local redo...")
                           (local-store/pop-local-redo!)
                           true)

                         ;; No redo available anywhere
                         :else false))))}
       ;; Also handle Mod-y for Windows-style redo
       #js {:key "Mod-y"
            :run (fn [view]
                   (let [depth (redoDepth (.-state view))]
                     (if (pos? depth)
                       (redo view)
                       (cond
                         (and (remote-store/get-project-id)
                              (remote-store/server-can-redo?)
                              (model/is-project-owner?))
                         (do
                           (remote-store/server-redo!)
                           true)

                         (and (not (remote-store/get-project-id))
                              (local-store/local-can-redo?))
                         (do
                           (local-store/pop-local-redo!)
                           true)

                         :else false))))}])

;; =============================================================================
;; Public Undo/Redo Functions (for toolbar buttons)
;; =============================================================================

(defn get-undo-source
  "Returns the source of the next undo operation:
   :codemirror - CodeMirror has local undo (not yet synced)
   :server - Server undo available (remote mode, or synced local changes)
   :local - Local undo available (local mode)
   nil - No undo available"
  []
  (let [cm-depth (when @events/editor-view-ref
                   (undoDepth (.-state @events/editor-view-ref)))
        synced? (remote-store/local-changes-synced?)
        has-server-undo? (and (remote-store/get-project-id)
                              (remote-store/server-can-undo?)
                              (model/is-project-owner?))]
    (cond
      ;; If synced to server and server has undo, go to server
      (and synced? has-server-undo?) :server
      ;; CodeMirror has local undo (not yet synced)
      (and cm-depth (pos? cm-depth) (not synced?)) :codemirror
      ;; Server undo available
      has-server-undo? :server
      ;; Local mode
      (and (not (remote-store/get-project-id))
           (local-store/local-can-undo?)) :local
      :else nil)))

(defn get-redo-source
  "Returns the source of the next redo operation:
   :codemirror - CodeMirror has local redo
   :server - Server redo available (remote mode)
   :local - Local redo available (local mode)
   nil - No redo available"
  []
  (let [cm-depth (when @events/editor-view-ref
                   (redoDepth (.-state @events/editor-view-ref)))]
    (cond
      (and cm-depth (pos? cm-depth)) :codemirror
      (and (remote-store/get-project-id)
           (remote-store/server-can-redo?)
           (model/is-project-owner?)) :server
      (and (not (remote-store/get-project-id))
           (local-store/local-can-redo?)) :local
      :else nil)))

(defn do-undo!
  "Perform undo from the appropriate source. Returns true if successful."
  []
  (case (get-undo-source)
    :codemirror (when @events/editor-view-ref
                  (undo @events/editor-view-ref))
    :server (do (remote-store/server-undo!) true)
    :local (do (local-store/pop-local-undo!) true)
    false))

(defn do-redo!
  "Perform redo from the appropriate source. Returns true if successful."
  []
  (case (get-redo-source)
    :codemirror (when @events/editor-view-ref
                  (redo @events/editor-view-ref))
    :server (do (remote-store/server-redo!) true)
    :local (do (local-store/pop-local-redo!) true)
    false))

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

(defn create-editor-state
  "Create CodeMirror editor state. If read-only? is true, editor will be non-editable."
  ([content chunk-id]
   (create-editor-state content chunk-id false))
  ([content chunk-id read-only?]
   (let [base-extensions #js [tramando-theme
                              ;; lineNumbers removed for cleaner writing experience
                              (highlightActiveLine)
                              (drawSelection)
                              (.-lineWrapping EditorView)
                              ;; Custom undo/redo keymap with HIGHEST precedence to intercept before history
                              (.highest Prec (.of keymap undo-redo-keymap))
                              (history)
                              (indentOnInput)
                              (bracketMatching)
                              (highlightSelectionMatches)
                              (syntaxHighlighting defaultHighlightStyle)
                              (markdown)
                              annotation-highlight
                              search-highlight-plugin
                              flash-highlight-plugin
                              (.of keymap search-keymap)
                              (.of keymap (.concat defaultKeymap searchKeymap #js [indentWithTab]))
                              (make-update-listener chunk-id)
                              (make-contextmenu-extension chunk-id)]
         extensions (if read-only?
                      (.concat base-extensions #js [(.of (.-readOnly EditorState) true)])
                      base-extensions)]
     (.create EditorState
              #js {:doc content
                   :extensions extensions}))))

(defn create-editor-view [parent-element state]
  (EditorView. #js {:state state
                    :parent parent-element}))

;; =============================================================================
;; Context Menu for Annotations
;; =============================================================================

(defonce local-editor-view (atom nil))

(defn- contains-annotation?
  "Check if text contains any annotation markers (EDN or legacy format)"
  [text]
  (boolean (or (re-find #"\[!(TODO|NOTE|FIX|PROPOSAL)\{" text)
               (re-find #"\[!(TODO|NOTE|FIX|PROPOSAL)(?:@[^:]+)?:" text))))

(defn- wrap-selection-with-annotation!
  "Wrap selected text with annotation in EDN format: [!TYPE{:text \"...\" :author \"...\" :priority N :comment \"...\"}]
   In server mode, adds current username. In Tauri/local mode, no author is added."
  [annotation-type chunk selected-text & [priority comment]]
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
                ;; Get author if logged in (server mode)
                username (auth/get-username)
                author (when (and username (not= username "local")) username)
                ;; Build EDN annotation data
                data (cond-> {:text selected-text}
                       author (assoc :author author)
                       (and priority (seq priority)) (assoc :priority (if (re-matches #"\d+" priority)
                                                                        (js/parseInt priority)
                                                                        priority))
                       (and comment (seq comment)) (assoc :comment comment))
                wrapped-text (str "[!" annotation-type (pr-str data) "]")
                ;; Position cursor at end of annotation
                cursor-pos (+ from (count wrapped-text))]
            (.dispatch view #js {:changes #js {:from from :to to :insert wrapped-text}
                                 :selection #js {:anchor cursor-pos}})
            (.focus view)))))))

;; Set up handler for wrap annotation from context menu
(context-menu/set-wrap-annotation-handler! wrap-selection-with-annotation!)

(defn- delete-annotation!
  "Delete an annotation from a chunk, keeping original text.
   Uses position-based replacement for reliability with both EDN and legacy formats."
  [annotation]
  (let [chunk-id (:chunk-id annotation)
        ;; Get text from new format (:data) or old format
        selected-text (or (get-in annotation [:data :text])
                          (:selected-text annotation))
        start (:start annotation)
        end (:end annotation)]
    (when (and chunk-id selected-text)
      (when-let [chunk (model/get-chunk chunk-id)]
        (let [content (:content chunk)]
          ;; Use position-based replacement if we have positions
          (if (and start end (<= 0 start) (<= end (count content)))
            (let [new-content (str (subs content 0 start) selected-text (subs content end))]
              (when (not= content new-content)
                (model/update-chunk! chunk-id {:content new-content})
                (refresh-editor!)))
            ;; Fallback: pattern-based replacement for legacy annotations without positions
            (let [type-name (name (:type annotation))
                  escaped-text (str/replace selected-text #"[.*+?^${}()|\\[\\]\\\\]" "\\\\$&")
                  ;; Try EDN pattern first, then legacy
                  edn-pattern (re-pattern (str "\\[!" type-name "\\{[^}]*:text\\s+\"" escaped-text "\"[^}]*\\}\\]"))
                  legacy-pattern (re-pattern (str "\\[!" type-name "(?:@[^:]+)?:" escaped-text ":[^:]*:[^\\]]*\\]"))
                  new-content (-> content
                                  (str/replace edn-pattern selected-text)
                                  (str/replace legacy-pattern selected-text))]
              (when (not= content new-content)
                (model/update-chunk! chunk-id {:content new-content})
                (refresh-editor!)))))))))

;; Set up handler for delete annotation from context menu
(context-menu/set-delete-annotation-handler! delete-annotation!)

(defn- parse-annotation-from-text
  "Try to parse an annotation from text that might be a complete annotation.
   Supports both EDN and legacy formats. Returns annotation map or nil."
  [text chunk-id]
  (when (and text (str/starts-with? text "[!"))
    ;; Try EDN format first: [!TYPE{...}]
    (if-let [edn-match (re-find #"^\[!(TODO|NOTE|FIX|PROPOSAL)\{(.+)\}\]$" text)]
      (try
        (let [type-str (nth edn-match 1)
              edn-str (str "{" (nth edn-match 2) "}")
              data (reader/read-string edn-str)]
          {:type (keyword type-str)
           :data data
           :chunk-id chunk-id})
        (catch :default _ nil))
      ;; Legacy format: [!TYPE@author:text:priority:comment]
      (let [pattern (js/RegExp. "^\\[!(TODO|NOTE|FIX|PROPOSAL)(?:@([^:]+))?:([^:]*):([^:]*):([^\\]]*)\\]$")]
        (when-let [match (.exec pattern text)]
          {:type (keyword (aget match 1))
           :data {:text (str/trim (aget match 3))
                  :author (when-let [a (aget match 2)] (str/trim a))
                  :priority (let [p (aget match 4)]
                              (when (and p (seq (str/trim p)))
                                (let [t (str/trim p)]
                                  (cond
                                    (= t "AI") :pending
                                    (= t "AI-DONE") :done
                                    (re-matches #"-?\d+\.?\d*" t) (js/parseFloat t)
                                    :else t))))
                  :comment (str/trim (or (aget match 5) ""))}
           :chunk-id chunk-id})))))

(defn- find-standard-annotation-at-position
  "Find a standard annotation (TODO/NOTE/FIX) at position.
   Uses the unified annotation parser that supports both EDN and legacy formats."
  [text pos chunk-id]
  (let [all-annotations (annotations/parse-annotations text)]
    (first (filter (fn [ann]
                     (and (contains? #{:TODO :NOTE :FIX} (:type ann))
                          (>= pos (:start ann))
                          (< pos (:end ann))))
                   (map #(assoc % :chunk-id chunk-id) all-annotations)))))

(defn- find-proposal-annotation-at-position
  "Find a PROPOSAL annotation at position.
   Uses the unified annotation parser that supports both EDN and legacy formats."
  [text pos chunk-id]
  (let [all-annotations (annotations/parse-annotations text)]
    (first (filter (fn [ann]
                     (and (= (:type ann) :PROPOSAL)
                          (>= pos (:start ann))
                          (< pos (:end ann))))
                   (map #(assoc % :chunk-id chunk-id) all-annotations)))))

(defn- find-annotation-at-position
  "Find if there's an annotation at the given character position in the text.
   Handles both standard annotations (TODO/NOTE/FIX) and PROPOSAL annotations separately
   because PROPOSAL text can contain colons."
  [text pos chunk-id]
  (or (find-standard-annotation-at-position text pos chunk-id)
      (find-proposal-annotation-at-position text pos chunk-id)))

;; =============================================================================
;; Editor Component
;; =============================================================================

(defn- can-edit-chunk?
  "Check if current user can edit the given chunk's main text.
   In local mode, always true. In remote mode, only if user is owner."
  [chunk-id]
  (if-let [store (protocol/get-store)]
    (protocol/can-edit? store chunk-id)
    true)) ;; Default to editable if no store

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
          (let [read-only? (not (can-edit-chunk? (:id chunk)))
                state (create-editor-state (:content chunk) (:id chunk) read-only?)
                view (create-editor-view @editor-ref state)
                saved-pos (model/get-cursor-pos (:id chunk))]
            (reset! editor-view view)
            (reset! events/editor-view-ref view) ;; Store in global ref for search
            (reset! last-chunk-id (:id chunk))
            (reset! last-show-markup @annotations/show-markup?)
            ;; Store view in local-editor-view for annotation handler
            (reset! local-editor-view view)
            ;; Restore cursor position if saved (and no pending scroll)
            (when (and saved-pos (nil? @pending-scroll-pattern))
              (let [doc-length (.. view -state -doc -length)
                    safe-pos (min saved-pos doc-length)]
                (.dispatch view #js {:selection #js {:anchor safe-pos}
                                     :scrollIntoView true})
                ;; Focus the editor after a small delay to ensure DOM is ready
                (js/setTimeout #(.focus view) 10)))
            ;; Execute any pending scroll (from "Usato da" navigation)
            (execute-pending-scroll!))))

      :component-did-update
      (fn [this old-argv]
        (let [chunk (model/get-selected-chunk)
              show-markup? @annotations/show-markup?
              refresh-counter @events/editor-refresh-counter]
          ;; Recreate editor if chunk changed, show-markup changed, or refresh triggered
          (when (or (and chunk (not= (:id chunk) @last-chunk-id))
                    (not= show-markup? @last-show-markup)
                    (not= refresh-counter @last-refresh-counter))
            ;; Save cursor position AND scroll position BEFORE destroying
            (let [current-pos (when @editor-view
                                (.. @editor-view -state -selection -main -head))
                  current-scroll-top (when @editor-view
                                       (.. @editor-view -scrollDOM -scrollTop))]
              ;; Save to model if chunk changed
              (when (and @editor-view @last-chunk-id (not= (:id chunk) @last-chunk-id))
                (model/set-cursor-pos! @last-chunk-id current-pos))
              (when @editor-view
                (.destroy @editor-view))
              ;; Re-run search on new content
              (when (and chunk (not= (:id chunk) @last-chunk-id))
                (update-search!))
              (when chunk
                (let [read-only? (not (can-edit-chunk? (:id chunk)))
                      state (create-editor-state (:content chunk) (:id chunk) read-only?)
                      view (create-editor-view @editor-ref state)
                      chunk-changed? (not= (:id chunk) @last-chunk-id)
                      has-pending-scroll? (some? @pending-scroll-pattern)
                      ;; For refresh on same chunk, use current-pos; for chunk change, use saved pos
                      saved-pos (if chunk-changed?
                                  (model/get-cursor-pos (:id chunk))
                                  current-pos)]
                  (reset! editor-view view)
                  (reset! events/editor-view-ref view) ;; Update global ref
                  (reset! last-chunk-id (:id chunk))
                  (reset! last-show-markup show-markup?)
                  (reset! last-refresh-counter refresh-counter)
                  ;; Store view in local-editor-view for annotation handler
                  (reset! local-editor-view view)
                  ;; Restore cursor and scroll position
                  (cond
                    ;; Pending scroll from navigation takes priority
                    has-pending-scroll?
                    (execute-pending-scroll!)

                    ;; Same chunk refresh: restore exact scroll position (no scrollIntoView)
                    (and (not chunk-changed?) saved-pos)
                    (do
                      (let [doc-length (.. view -state -doc -length)
                            safe-pos (min saved-pos doc-length)]
                        (.dispatch view #js {:selection #js {:anchor safe-pos}}))
                      ;; Restore scroll position after rendering is complete
                      ;; Use requestAnimationFrame for better timing with layout
                      (when current-scroll-top
                        (js/requestAnimationFrame
                         (fn []
                           (set! (.. view -scrollDOM -scrollTop) current-scroll-top)
                           ;; Apply again after another frame to handle any layout shifts
                           (js/requestAnimationFrame
                            (fn []
                              (set! (.. view -scrollDOM -scrollTop) current-scroll-top))))))
                      (js/setTimeout #(.focus view) 20))

                    ;; Chunk changed: restore saved position with scrollIntoView
                    saved-pos
                    (let [doc-length (.. view -state -doc -length)
                          safe-pos (min saved-pos doc-length)]
                      (.dispatch view #js {:selection #js {:anchor safe-pos}
                                           :scrollIntoView true})
                      (js/setTimeout #(.focus view) 10)))))))))

      :component-will-unmount
      (fn [this]
        ;; Save cursor position before destroying
        (when (and @editor-view @last-chunk-id)
          (let [pos (.. @editor-view -state -selection -main -head)]
            (model/set-cursor-pos! @last-chunk-id pos)))
        (when @editor-view
          (.destroy @editor-view))
        (reset! events/editor-view-ref nil))

      :reagent-render
      (fn []
        (let [selected-id (model/get-selected-id) ; trigger re-render on selection change
              _show-markup @annotations/show-markup? ; trigger re-render on toggle
              _refresh @events/editor-refresh-counter ; trigger re-render on external content change
              read-only? (and selected-id (not (can-edit-chunk? selected-id)))
              colors (:colors @settings/settings)]
          [:div {:style {:display "flex" :flex-direction "column" :height "100%"}}
           (when read-only?
             [:div {:style {:background-color "var(--color-accent-muted)"
                            :border-bottom (str "1px solid " (:border colors))
                            :padding "6px 12px"
                            :font-size "0.85rem"
                            :color (:text-muted colors)
                            :display "flex"
                            :align-items "center"
                            :gap "8px"}}
              [:span {:style {:font-size "1rem"}} "🔒"]
              [:span (t :read-only-not-owner)]])
           [:div.editor-container
            {:ref #(reset! editor-ref %)
             :style {:flex "1"}}]]))})))

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
               [:span {:style {:color (settings/get-color :danger) :font-size "0.75rem" :margin-left "8px"}}
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
  (let [error-msg (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            colors (:colors @settings/settings)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          (let [current-parent-id (:parent-id chunk)
                current-parent (when current-parent-id
                                 (first (filter #(= (:id %) current-parent-id) (model/get-chunks))))
                parent-display (if current-parent
                                 (or (:summary current-parent) current-parent-id)
                                 (t :root))]
            [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-top "8px"}}
             [:span {:style {:color (:text-muted colors) :font-size "0.8rem" :display "flex" :align-items "center" :gap "4px"}}
              (t :parent)
              [:span.help-icon {:title (t :help-parent)} "?"]]
             [:button {:style {:background (:editor-bg colors)
                               :color (:text colors)
                               :border (str "1px solid " (:border colors))
                               :border-radius "3px"
                               :padding "4px 12px"
                               :font-size "0.8rem"
                               :cursor "pointer"
                               :display "flex"
                               :align-items "center"
                               :gap "4px"}
                       :on-click (fn []
                                   (selector/open-selector!
                                    {:title (t :select-parent)
                                     :items (build-parent-tree (:id chunk))
                                     :filter-placeholder (t :search-placeholder)
                                     :show-categories-as-selectable true
                                     :on-select (fn [item]
                                                  (let [new-parent (:id item)
                                                        result (model/change-parent! (:id chunk) new-parent)]
                                                    (if (:error result)
                                                      (reset! error-msg (:error result))
                                                      (reset! error-msg nil))))}))}
              [:span (str parent-display)]
              [:span {:style {:font-size "0.7rem"}} "▼"]]
             (when @error-msg
               [:span {:style {:color (settings/get-color :danger) :font-size "0.75rem"}}
                @error-msg])]))))))

;; =============================================================================
;; Chunk Actions (Create Child + Delete)
;; =============================================================================

(defn chunk-actions []
  (let [confirming-delete? (r/atom false)
        error-msg (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            colors (:colors @settings/settings)
            has-children? (model/has-children? (:id chunk))]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          [:div {:style {:margin-top "12px" :padding-top "12px" :border-top (str "1px solid " (:border colors))}}
           (if @confirming-delete?
             ;; Confirm delete state
             [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
              [:span {:style {:color (settings/get-color :danger) :font-size "0.85rem"}} (t :confirm)]
              [:button {:style {:background (settings/get-color :danger)
                                :color "white"
                                :border "none"
                                :padding "6px 12px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.85rem"}
                        :on-click (fn []
                                    (let [result (model/try-delete-chunk! (:id chunk))]
                                      (if (:error result)
                                        (do (reset! error-msg (:error result))
                                            (reset! confirming-delete? false))
                                        (reset! confirming-delete? false))))}
               (t :delete)]
              [:button {:style {:background "transparent"
                                :color (:text-muted colors)
                                :border (str "1px solid " (:text-muted colors))
                                :padding "6px 12px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.85rem"}
                        :on-click #(reset! confirming-delete? false)}
               (t :cancel)]]
             ;; Normal state - both buttons on same line
             [:div
              [:div {:style {:display "flex" :gap "8px"}}
               ;; Only show create-child if user can create at this parent
               (when (model/can-create-chunk-at? (:id chunk))
                 [:button {:style {:background "transparent"
                                   :color (:accent colors)
                                   :border (str "1px solid " (:accent colors))
                                   :padding "6px 12px"
                                   :border-radius "4px"
                                   :cursor "pointer"
                                   :font-size "0.85rem"}
                           :on-click #(model/add-chunk! :parent-id (:id chunk))}
                  (t :create-child)])
               ;; Delete button - only visible to project owner
               (when (model/is-project-owner?)
                 [:button {:style {:background "transparent"
                                   :color (if has-children?
                                            (:text-muted colors)
                                            (settings/get-color :danger))
                                   :border (str "1px solid " (if has-children?
                                                               (:text-muted colors)
                                                               (settings/get-color :danger)))
                                   :padding "6px 12px"
                                   :border-radius "4px"
                                   :cursor (if has-children? "not-allowed" "pointer")
                                   :opacity (if has-children? 0.5 1)
                                   :font-size "0.85rem"}
                           :disabled has-children?
                           :title (when has-children? (t :cannot-delete-has-children))
                           :on-click #(when-not has-children?
                                        (reset! confirming-delete? true))}
                  (t :delete-chunk)])]
              (when @error-msg
                [:span {:style {:color (settings/get-color :danger) :font-size "0.75rem" :margin-top "4px" :display "block"}}
                 @error-msg])])])))))

;; Keep old name for backwards compatibility
(def delete-button chunk-actions)

;; =============================================================================
;; Compact Header (clean design with dropdown menu)
;; =============================================================================

(defn compact-header []
  (let [menu-open? (r/atom false)
        editing-id? (r/atom false)
        temp-id (r/atom "")
        id-error (r/atom nil)
        confirming-delete? (r/atom false)
        last-chunk-id (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            colors (:colors @settings/settings)
            is-aspect? (model/is-aspect-chunk? chunk)
            is-container? (model/is-aspect-container? (:id chunk))
            has-children? (model/has-children? (:id chunk))]

        ;; Reset state when chunk changes
        (when (and chunk (not= (:id chunk) @last-chunk-id))
          (reset! last-chunk-id (:id chunk))
          (reset! menu-open? false)
          (reset! editing-id? false)
          (reset! confirming-delete? false))

        (when chunk
          [:div.chunk-header
           ;; Row 1: Title + Menu button
           [:div.chunk-header-title
            ;; ID badge (for aspects only - read-only display)
            (when is-aspect?
              [:span {:style {:color (:text-muted colors)
                              :font-size "12px"
                              :font-family "monospace"
                              :padding "2px 6px"
                              :border-radius "3px"
                              :background (:tertiary colors)}}
               (str "[" (:id chunk) "]")])

            ;; Title input (clean, borderless)
            [:input {:type "text"
                     :value (:summary chunk)
                     :placeholder (t :chunk-title-placeholder)
                     :on-change #(model/update-chunk! (:id chunk) {:summary (.. % -target -value)})}]

            ;; Menu button (⋮) - only for non-containers
            (when-not is-container?
              [:div {:style {:position "relative"}}
               [:button {:style {:background "transparent"
                                 :border "none"
                                 :color (:text-muted colors)
                                 :font-size "18px"
                                 :cursor "pointer"
                                 :padding "4px 8px"
                                 :border-radius "4px"
                                 :line-height "1"}
                         :on-click #(swap! menu-open? not)}
                "⋮"]

               ;; Dropdown menu
               (when @menu-open?
                 [:div.dropdown-menu
                  {:on-mouse-leave #(when-not (or @editing-id? @confirming-delete?)
                                      (reset! menu-open? false))}

                  ;; Edit ID (for aspects only)
                  (when is-aspect?
                    (if @editing-id?
                      [:div {:style {:padding "8px 12px"}}
                       [:input {:type "text"
                                :value @temp-id
                                :auto-focus true
                                :placeholder "nuovo-id"
                                :style {:background (:editor-bg colors)
                                        :border (str "1px solid " (:border colors))
                                        :border-radius "3px"
                                        :color (:text colors)
                                        :font-size "12px"
                                        :padding "4px 8px"
                                        :width "100%"
                                        :outline "none"}
                                :on-change #(do (reset! temp-id (.. % -target -value))
                                                (reset! id-error nil))
                                :on-key-down (fn [e]
                                               (case (.-key e)
                                                 "Enter" (let [result (model/rename-chunk-id! (:id chunk) @temp-id)]
                                                           (if (:ok result)
                                                             (do (reset! editing-id? false)
                                                                 (reset! menu-open? false))
                                                             (reset! id-error (:error result))))
                                                 "Escape" (reset! editing-id? false)
                                                 nil))}]
                       (when @id-error
                         [:div {:style {:color (:danger colors) :font-size "10px" :margin-top "4px"}}
                          @id-error])]
                      [:button.dropdown-item
                       {:on-click #(do (reset! temp-id (:id chunk))
                                       (reset! id-error nil)
                                       (reset! editing-id? true))}
                       (t :edit-id)]))

                  ;; Priority field (for aspects only)
                  (when is-aspect?
                    [:div {:style {:padding "8px 12px"
                                   :display "flex"
                                   :align-items "center"
                                   :gap "8px"}}
                     [:span {:style {:color (:text-muted colors) :font-size "11px"}}
                      (t :priority)]
                     [:input {:type "number"
                              :min 0
                              :max 10
                              :value (or (:priority chunk) 0)
                              :style {:width "50px"
                                      :background (:editor-bg colors)
                                      :border (str "1px solid " (:border colors))
                                      :border-radius "3px"
                                      :color (:text colors)
                                      :font-size "12px"
                                      :padding "4px 6px"
                                      :text-align "center"}
                              :on-change (fn [e]
                                           (let [v (js/parseInt (.. e -target -value) 10)]
                                             (when (and (not (js/isNaN v)) (<= 0 v 10))
                                               (model/update-chunk! (:id chunk) {:priority (when (pos? v) v)}))))
                              :on-click #(.stopPropagation %)}]
                     [:span {:style {:color (:text-dim colors) :font-size "9px"}}
                      (t :priority-hint)]])

                  (when is-aspect?
                    [:div.dropdown-divider])

                  ;; Create child
                  (when (model/can-create-chunk-at? (:id chunk))
                    [:button.dropdown-item
                     {:on-click #(do (model/add-chunk! :parent-id (:id chunk))
                                     (reset! menu-open? false))}
                     (t :create-child)])

                  ;; Move to...
                  [:button.dropdown-item
                   {:on-click #(do (selector/open-selector!
                                    {:title (t :select-parent)
                                     :items (build-parent-tree (:id chunk))
                                     :filter-placeholder (t :search-placeholder)
                                     :show-categories-as-selectable true
                                     :on-select (fn [item]
                                                  (model/change-parent! (:id chunk) (:id item)))})
                                   (reset! menu-open? false))}
                   (t :move-to)]

                  [:div.dropdown-divider]

                  ;; Show in hierarchy
                  [:button.dropdown-item
                   {:on-click #(do (events/navigate-to-chunk! (:id chunk))
                                   (reset! menu-open? false))}
                   (t :show-in-hierarchy)]

                  ;; Delete - only visible to project owner
                  (when (model/is-project-owner?)
                    [:<>
                     [:div.dropdown-divider]
                     (if @confirming-delete?
                       [:div {:style {:padding "8px 12px"}}
                        [:div {:style {:color (:danger colors) :font-size "11px" :margin-bottom "8px"}}
                         (t :confirm)]
                        [:div {:style {:display "flex" :gap "8px"}}
                         [:button {:style {:background (:danger colors)
                                           :color "white"
                                           :border "none"
                                           :padding "4px 12px"
                                           :border-radius "4px"
                                           :cursor "pointer"
                                           :font-size "11px"}
                                   :on-click #(do (model/try-delete-chunk! (:id chunk))
                                                  (reset! confirming-delete? false)
                                                  (reset! menu-open? false))}
                          (t :delete)]
                         [:button {:style {:background "transparent"
                                           :color (:text-muted colors)
                                           :border (str "1px solid " (:border colors))
                                           :padding "4px 12px"
                                           :border-radius "4px"
                                           :cursor "pointer"
                                           :font-size "11px"}
                                   :on-click #(reset! confirming-delete? false)}
                          (t :cancel)]]]
                       [:button.dropdown-item.danger
                        {:on-click #(when-not has-children?
                                      (reset! confirming-delete? true))
                         :disabled has-children?
                         :title (when has-children? (t :cannot-delete-has-children))
                         :style (when has-children?
                                  {:opacity 0.5
                                   :cursor "not-allowed"})}
                        (t :delete-chunk)])])])])]

           ;; Row 2: Meta info (tags/aspects) - with reduced opacity
           (when-not is-container?
             [:div.chunk-meta-info
              {:style {:display "flex" :flex-wrap "wrap" :align-items "center" :gap "6px"}}

              ;; Aspect tags (pill style)
              (doall
               (for [aspect-id (:aspects chunk)]
                 (let [aspect (first (filter #(= (:id %) aspect-id) (model/get-chunks)))
                       container-id (:parent-id aspect)
                       threshold (settings/get-aspect-threshold container-id)
                       aspect-priority (or (:priority aspect) 0)
                       is-filtered-out? (< aspect-priority threshold)]
                   ^{:key aspect-id}
                   [:span.tag-pill {:class (when is-filtered-out? "tag-filtered")}
                    [:span {:on-click (when-not is-filtered-out?
                                        #(events/navigate-to-aspect! aspect-id))
                            :title (if is-filtered-out?
                                     (t :aspect-filtered-out)
                                     (t :click-to-navigate))}
                     (str "@" (or (:summary aspect) aspect-id))]
                    (when (can-edit-chunk? (:id chunk))
                      [:button.tag-remove
                       {:on-click #(model/remove-aspect-from-chunk! (:id chunk) aspect-id)
                        :title (t :remove)}
                       "×"])])))

              ;; Add aspect button (pill style, dashed) - only for owners
              (when (can-edit-chunk? (:id chunk))
                [:span.tag-pill.tag-pill-add
                 {:on-click #(selector/open-selector!
                              {:title (t :select-aspect)
                               :items (build-aspects-tree)
                               :filter-placeholder (t :search-placeholder)
                               :on-select (fn [item]
                                            (when (and (:id item) (not= :category (:type item)))
                                              (model/add-aspect-to-chunk! (:id chunk) (:id item))))})}
                 (str "+ " (t :aspect))])])])))))

;; =============================================================================
;; Tab State
;; =============================================================================

(defonce active-tab (r/atom :edit)) ;; :edit, :refs, :read, :discussion

(defn set-tab! [tab]
  (reset! active-tab tab))

;; =============================================================================
;; Tab Bar Component
;; =============================================================================

(defn tab-bar []
  (let [current @active-tab
        chunk (model/get-selected-chunk)
        is-aspect? (model/is-aspect-chunk? chunk)]
    ;; Se siamo sul tab refs ma il chunk non è un aspetto, torna a edit
    (when (and (= current :refs) (not is-aspect?))
      (set-tab! :edit))
    [:div.tab-bar
     [:button.tab {:class (when (= current :edit) "active")
                   :title (t :help-tab-modifica)
                   :on-click #(set-tab! :edit)}
      (t :edit)]
     ;; Tab "Usato da" solo per gli aspetti
     (when is-aspect?
       [:button.tab {:class (when (= current :refs) "active")
                     :title (t :help-tab-usato-da)
                     :on-click #(set-tab! :refs)}
        (t :used-by)])
     [:button.tab {:class (when (= current :read) "active")
                   :title (t :help-tab-lettura)
                   :on-click #(set-tab! :read)}
      (t :reading)]
     [:button.tab {:class (when (= current :discussion) "active")
                   :title (t :help-tab-discussion)
                   :on-click #(set-tab! :discussion)}
      (t :discussion)]]))

;; =============================================================================
;; Tree Builders for Chunk Selector
;; =============================================================================

(defn- get-children-tree
  "Build tree of children for a given parent id"
  [parent-id]
  (let [children (model/get-children parent-id)]
    (mapv (fn [c]
            {:id (:id c)
             :title (or (:summary c) (:id c))
             :type (keyword (:parent-id c))
             :children (get-children-tree (:id c))})
          children)))

(defn build-aspects-tree
  "Build tree structure for aspect selection"
  []
  [{:id "personaggi" :title (t :personaggi) :type :category :expanded true
    :children (get-children-tree "personaggi")}
   {:id "luoghi" :title (t :luoghi) :type :category :expanded false
    :children (get-children-tree "luoghi")}
   {:id "temi" :title (t :temi) :type :category :expanded false
    :children (get-children-tree "temi")}
   {:id "sequenze" :title (t :sequenze) :type :category :expanded false
    :children (get-children-tree "sequenze")}
   {:id "timeline" :title (t :timeline) :type :category :expanded false
    :children (get-children-tree "timeline")}])

(defn- build-structure-tree
  "Build tree of structural chunks (non-aspect)"
  []
  (let [roots (filter #(nil? (:parent-id %)) (model/get-chunks))
        structural-roots (remove #(model/is-aspect-container? (:id %)) roots)]
    (mapv (fn [c]
            {:id (:id c)
             :title (or (:summary c) (:id c))
             :type :structure
             :children (get-children-tree (:id c))})
          structural-roots)))

(defn build-parent-tree
  "Build full tree for parent selection (structure + aspects)"
  [exclude-id]
  (let [structure-tree (build-structure-tree)
        ;; Filter out the chunk itself and its descendants from the tree
        filter-tree (fn filter-tree [items]
                      (->> items
                           (remove #(= (:id %) exclude-id))
                           (mapv #(update % :children filter-tree))))]
    (concat
     [{:id nil :title (t :root) :type :root}]
     (filter-tree structure-tree))))

;; =============================================================================
;; Aspects Manager (add/remove aspects from a chunk)
;; =============================================================================

(defn aspects-manager []
  (fn []
    (let [chunk (model/get-selected-chunk)
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
                (when (can-edit-chunk? (:id chunk))
                  [:button {:style {:background "none"
                                    :border "none"
                                    :color (:text-muted colors)
                                    :cursor "pointer"
                                    :padding "0 2px"
                                    :font-size "0.9rem"
                                    :line-height "1"}
                            :title (t :remove)
                            :on-click #(model/remove-aspect-from-chunk! (:id chunk) aspect-id)}
                   "×"])])))

          ;; Add aspect button (opens modal) - only for owners
          [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
           [:button {:style {:background "transparent"
                             :color (:text-muted colors)
                             :border (str "1px dashed " (:border colors))
                             :padding "2px 8px"
                             :border-radius "3px"
                             :font-size "0.8rem"
                             :cursor "pointer"}
                     :on-click (fn []
                                 (selector/open-selector!
                                  {:title (t :select-aspect)
                                   :items (build-aspects-tree)
                                   :filter-placeholder (t :search-placeholder)
                                   :on-select (fn [item]
                                                (when (and (:id item)
                                                           (not= :category (:type item)))
                                                  (model/add-aspect-to-chunk! (:id chunk) (:id item))))}))}
            (t :add-aspect)]
           [help/help-icon :add-aspect]]]]))))

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
                (let [aspect-id (:id chunk)
                      scroll-pattern (str "[@" aspect-id "]")]
                  ^{:key (:id c)}
                  [:div {:style {:background (:sidebar colors)
                                 :padding "10px 14px"
                                 :border-radius "4px"
                                 :cursor "pointer"
                                 :border-left (str "3px solid " (:accent colors))}
                         :on-click #(do
                                      (set-tab! :edit)
                                      (events/navigate-to-chunk-and-scroll! (:id c) scroll-pattern))}
                   [:div {:style {:color (:text colors)}}
                    (model/get-chunk-path c)]])))])])

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
                      :on-click #(events/navigate-to-chunk! (:id c))}
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
                             :border (str "1px solid " (if invalid-regex (:danger colors) (:border colors)))
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
;; Discussion View
;; =============================================================================

(defn- format-timestamp
  "Format ISO timestamp to readable date/time"
  [iso-string]
  (try
    (let [date (js/Date. iso-string)]
      (str (.toLocaleDateString date) " " (.toLocaleTimeString date)))
    (catch :default _
      iso-string)))

(defn- discussion-entry
  "Render a single discussion entry (comment or resolved proposal)"
  [entry colors]
  ;; Handle both keyword (:proposal) and string ("proposal") formats
  (let [is-proposal? (#{:proposal "proposal"} (:type entry))
        is-accepted? (#{:accepted "accepted"} (:answer entry))
        ;; Convert usernames to display names
        author-display (auth/get-cached-display-name (:author entry))
        decided-by-display (when-let [db (:decided-by entry)]
                             (auth/get-cached-display-name db))]
    [:div {:style {:padding "12px"
                   :margin-bottom "8px"
                   :background (:sidebar-bg colors)
                   :border-radius "6px"
                   :border-left (str "3px solid " (if is-proposal?
                                                    (if is-accepted? "#3fb950" "#f85149")
                                                    (:accent colors)))}}
     ;; Header: author + timestamp
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :margin-bottom "8px"
                    :font-size "0.8rem"
                    :color (:text-muted colors)}}
      [:span {:style {:font-weight "500"}} author-display]
      [:span (format-timestamp (:timestamp entry))]]
     ;; Content based on type
     (if is-proposal?
       ;; Proposal entry
       [:div
        [:div {:style {:display "flex"
                       :align-items "baseline"
                       :gap "8px"
                       :margin-bottom "6px"}}
         [:span {:style {:font-size "0.75rem"
                         :text-transform "uppercase"
                         :color (if is-accepted? "#3fb950" "#f85149")}}
          (if is-accepted?
            (t :discussion-proposal-accepted)
            (t :discussion-proposal-rejected))]
         ;; Show who decided
         (when decided-by-display
           [:span {:style {:font-size "0.75rem"
                           :color (:text-muted colors)}}
            (str "da " decided-by-display)])]
        [:div {:style {:background (:editor-bg colors)
                       :padding "8px"
                       :border-radius "4px"
                       :font-size "0.85rem"
                       :margin-bottom "6px"}}
         [:div {:style {:text-decoration "line-through"
                        :color (:text-muted colors)
                        :margin-bottom "4px"}}
          (:previous-text entry)]
         [:div {:style {:color (:text colors)}}
          (:proposed-text entry)]]
        (when (:reason entry)
          [:div {:style {:font-style "italic"
                         :font-size "0.85rem"
                         :color (:text-muted colors)}}
           (:reason entry)])]
       ;; Comment entry
       [:div {:style {:font-size "0.9rem"
                      :color (:text colors)
                      :white-space "pre-wrap"}}
        (:text entry)])]))

(defn discussion-view []
  (let [new-comment (r/atom "")
        show-transfer? (r/atom false)
        transfer-to (r/atom "")
        collaborators (r/atom nil)
        load-collaborators! (fn [project-id]
                              (when project-id
                                (-> (api/list-collaborators project-id)
                                    (.then (fn [result]
                                             (when (:ok result)
                                               (let [data (:data result)]
                                                 (reset! collaborators data)
                                                 ;; Cache display names globally
                                                 (auth/cache-users-from-collaborators! data))))))))
        ;; Helper to find display name from username
        find-display-name (fn [username collabs-data]
                            (when (and username (not= username "local"))
                              (let [owner (:owner collabs-data)
                                    collabs (:collaborators collabs-data)
                                    all-users (cons owner collabs)
                                    user (first (filter #(= (:username %) username) all-users))]
                                (or (not-empty (:display_name user)) username))))]
    ;; Load collaborators on mount (for display names)
    (load-collaborators! (remote-store/get-project-id))
    (fn []
      (let [chunk (model/get-selected-chunk)
            discussion (or (:discussion chunk) [])
            colors (:colors @settings/settings)
            chunk-id (:id chunk)
            current-owner (or (:owner chunk) "local")
            previous-owner (:previous-owner chunk)
            current-user (auth/get-username)
            is-chunk-owner? (or (= current-owner "local")
                                (= current-owner current-user))
            is-project-owner? (model/is-project-owner?)
            ;; Only project owner can transfer ownership
            can-transfer? (and is-project-owner? (auth/logged-in?))
            ;; Only chunk owner can return ownership to previous owner
            can-return? (and is-chunk-owner?
                             previous-owner
                             (not= previous-owner "local")
                             (not= previous-owner current-owner))
            ;; Get display names for owner info
            owner-display (or (find-display-name current-owner @collaborators) current-owner)
            prev-owner-display (find-display-name previous-owner @collaborators)]
        [:div {:style {:display "flex"
                       :flex-direction "column"
                       :height "100%"
                       :overflow "hidden"}}
         ;; Header with owner info
         [:div {:style {:padding "12px 16px"
                        :border-bottom (str "1px solid " (:border colors))
                        :display "flex"
                        :justify-content "space-between"
                        :align-items "center"
                        :flex-wrap "wrap"
                        :gap "8px"}}
          [:div {:style {:font-size "0.85rem" :color (:text-muted colors)}}
           (str (t :discussion-owner) ": ")
           [:span {:style {:color (:text colors) :font-weight "500"}}
            owner-display]
           (when previous-owner
             [:span {:style {:margin-left "8px" :font-size "0.8rem"}}
              (str "(da " (or prev-owner-display previous-owner) ")")])]
          [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
           ;; Return ownership button (when there's a previous owner)
           (when can-return?
             [:button {:style {:background "transparent"
                               :border (str "1px solid " (:border colors))
                               :color (:text-muted colors)
                               :padding "4px 8px"
                               :border-radius "4px"
                               :font-size "0.75rem"
                               :cursor "pointer"}
                       :on-click #(do
                                    (model/update-chunk! chunk-id {:owner previous-owner
                                                                   :previous-owner current-owner})
                                    (events/show-toast! (str "Ownership restituita a " (or prev-owner-display previous-owner))))}
              "Restituisci"])
           ;; Claim ownership button (only for project owner when not already owner)
           (when (and is-project-owner? (not is-chunk-owner?))
             [:button {:style {:background (:accent colors)
                               :border "none"
                               :color "white"
                               :padding "4px 8px"
                               :border-radius "4px"
                               :font-size "0.75rem"
                               :cursor "pointer"}
                       :on-click #(do
                                    (model/set-chunk-owner! chunk-id current-user)
                                    (events/show-toast! (t :ownership-claimed)))}
              (t :claim-ownership)])
           ;; Transfer ownership button (only for project owner in remote mode)
           (when can-transfer?
             [:button {:style {:background "transparent"
                               :border (str "1px solid " (:border colors))
                               :color (:text-muted colors)
                               :padding "4px 8px"
                               :border-radius "4px"
                               :font-size "0.75rem"
                               :cursor "pointer"}
                       :on-click #(do
                                    (reset! show-transfer? true)
                                    (load-collaborators! (remote-store/get-project-id)))}
              "Trasferisci"])
           ;; Clear button (only if there are entries)
           (when (seq discussion)
             [:button {:style {:background "transparent"
                               :border (str "1px solid " (:border colors))
                               :color (:text-muted colors)
                               :padding "4px 8px"
                               :border-radius "4px"
                               :font-size "0.75rem"
                               :cursor "pointer"}
                       :on-click #(events/show-confirm!
                                    (t :discussion-clear-confirm)
                                    {:title (t :discussion-clear)
                                     :danger? true
                                     :confirm-text (t :discussion-clear)
                                     :on-confirm (fn [] (model/clear-discussion! chunk-id))})}
              (t :discussion-clear)])]]

         ;; Transfer ownership form
         (when @show-transfer?
           (let [collabs (get @collaborators :collaborators [])]
             [:div {:style {:padding "12px 16px"
                            :background (:sidebar colors)
                            :border-bottom (str "1px solid " (:border colors))}}
              [:div {:style {:font-size "0.85rem"
                             :color (:text colors)
                             :margin-bottom "8px"}}
               "Trasferisci ownership a:"]
              [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
               [:select {:value @transfer-to
                         :on-change #(reset! transfer-to (-> % .-target .-value))
                         :style {:flex "1"
                                 :min-width "150px"
                                 :padding "6px 10px"
                                 :border (str "1px solid " (:border colors))
                                 :border-radius "4px"
                                 :background (:editor-bg colors)
                                 :color (:text colors)
                                 :font-size "0.85rem"}}
                [:option {:value ""} "-- Seleziona collaboratore --"]
                (for [collab collabs]
                  ^{:key (:id collab)}
                  [:option {:value (:username collab)}
                   (or (not-empty (:display_name collab)) (:username collab))])]
               [:button {:on-click (fn []
                                     (when (seq @transfer-to)
                                       (let [collab (first (filter #(= (:username %) @transfer-to) collabs))
                                             display (or (not-empty (:display_name collab)) @transfer-to)]
                                         (model/update-chunk! chunk-id {:owner @transfer-to
                                                                        :previous-owner current-owner})
                                         (events/show-toast! (str "Ownership trasferita a " display))
                                         (reset! transfer-to "")
                                         (reset! show-transfer? false))))
                         :disabled (empty? @transfer-to)
                         :style {:padding "6px 12px"
                                 :background (:accent colors)
                                 :color "white"
                                 :border "none"
                                 :border-radius "4px"
                                 :cursor "pointer"
                                 :font-size "0.85rem"
                                 :opacity (if (empty? @transfer-to) 0.5 1)}}
                "Trasferisci"]
               [:button {:on-click #(do (reset! show-transfer? false)
                                        (reset! transfer-to ""))
                         :style {:padding "6px 12px"
                                 :background "transparent"
                                 :color (:text-muted colors)
                                 :border (str "1px solid " (:border colors))
                                 :border-radius "4px"
                                 :cursor "pointer"
                                 :font-size "0.85rem"}}
                "Annulla"]]]))
         ;; Scrollable content (discussion entries)
         [:div {:style {:flex 1
                        :overflow-y "auto"
                        :padding "16px"}}
          (if (empty? discussion)
            [:div {:style {:text-align "center"
                           :color (:text-muted colors)
                           :padding "32px"
                           :font-style "italic"}}
             (t :discussion-empty)]
            [:div
             (for [[idx entry] (map-indexed vector discussion)]
               ^{:key (str "disc-" idx)}
               [discussion-entry entry colors])])]
         ;; New comment input
         [:div {:style {:padding "12px"
                        :border-top (str "1px solid " (:border colors))
                        :display "flex"
                        :gap "8px"}}
          [:textarea {:style {:flex 1
                              :padding "8px 12px"
                              :border (str "1px solid " (:border colors))
                              :border-radius "4px"
                              :background (:editor-bg colors)
                              :color (:text colors)
                              :font-size "0.9rem"
                              :resize "none"
                              :min-height "60px"
                              :font-family "inherit"}
                      :placeholder (t :discussion-add-comment)
                      :value @new-comment
                      :on-change #(reset! new-comment (-> % .-target .-value))}]
          [:button {:style {:padding "8px 16px"
                            :background (:accent colors)
                            :color "white"
                            :border "none"
                            :border-radius "4px"
                            :cursor "pointer"
                            :font-size "0.85rem"
                            :align-self "flex-end"}
                    :disabled (empty? (str/trim @new-comment))
                    :on-click #(when-not (empty? (str/trim @new-comment))
                                 (model/add-comment! chunk-id {:text @new-comment})
                                 (reset! new-comment ""))}
           (t :discussion-send)]]]))))

;; =============================================================================
;; Tab Content
;; =============================================================================

(defn tab-content []
  (case @active-tab
    :edit [:div {:style {:display "flex" :flex-direction "column" :flex 1 :overflow "hidden"}}
           [search-bar]
           [:div.editor-content-wrapper
            [editor-component]]]
    :refs [refs-view]
    :read [read-view]
    :discussion [discussion-view]
    nil))
