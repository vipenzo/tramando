(ns tramando.editor
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.annotations :as annotations]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap lineNumbers highlightActiveLine
                                        highlightActiveLineGutter drawSelection
                                        Decoration ViewPlugin MatchDecorator]]
            ["@codemirror/commands" :refer [defaultKeymap history historyKeymap
                                            indentWithTab]]
            ["@codemirror/language" :refer [indentOnInput bracketMatching
                                            defaultHighlightStyle syntaxHighlighting]]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@codemirror/search" :refer [searchKeymap highlightSelectionMatches]]))

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
                                            :userSelect "none"}}))

;; =============================================================================
;; Annotation Highlighting Extension
;; =============================================================================

;; Syntax: [!TYPE:selected text:priority:comment]
(def ^:private annotation-regex (js/RegExp. "\\[!(TODO|NOTE|FIX):([^:]*):([^:]*):([^\\]]*)\\]" "g"))

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
              ;; Calculate positions of parts: [!TYPE:text:priority:comment]
              prefix-end (+ start 2 (count type-str) 1) ; after [!TYPE:
              text-start prefix-end
              text-end (+ text-start (count selected-text))
              suffix-start text-end ; from :priority:comment] to end
              type-lower (str/lower-case type-str)
              ;; Build tooltip from priority and comment
              tooltip (cond
                        (and (seq priority-str) (seq comment-text))
                        (str "[" priority-str "] " comment-text)
                        (seq comment-text) comment-text
                        (seq priority-str) (str "[" priority-str "]")
                        :else (str type-str " annotation"))]
          (if show-markup?
            ;; Markup mode: highlight entire annotation
            (.push builder (.range (.mark Decoration #js {:class (str "cm-annotation-" type-lower)})
                                   start end))
            ;; Reading mode: hide prefix/suffix, highlight text
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
                                     suffix-start end)))))
        (recur)))
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
                                 (.of keymap (.concat defaultKeymap historyKeymap searchKeymap #js [indentWithTab]))
                                 (make-update-listener chunk-id)]}))

(defn create-editor-view [parent-element state]
  (EditorView. #js {:state state
                    :parent parent-element}))

;; =============================================================================
;; Context Menu for Annotations
;; =============================================================================

(defonce context-menu-state (r/atom nil)) ; {:x :y :selection :view}

(defn- wrap-selection-with-annotation! [view selection annotation-type]
  "Wrap the selected text with annotation syntax [!TYPE:text:priority:comment]"
  (let [from (.-from selection)
        to (.-to selection)
        selected-text (.. view -state -doc (sliceString from to))
        wrapped-text (str "[!" annotation-type ":" selected-text "::]")
        ;; Position cursor after the second : (where priority goes)
        cursor-pos (+ from 2 (count annotation-type) 1 (count selected-text) 1)]
    (.dispatch view #js {:changes #js {:from from :to to :insert wrapped-text}
                         :selection #js {:anchor cursor-pos}})
    (.focus view)))

(defn- close-context-menu! []
  (reset! context-menu-state nil))

(defn annotation-context-menu []
  (let [{:keys [x y selection view]} @context-menu-state
        colors (:colors @settings/settings)]
    (when (and x y selection view)
      [:div {:style {:position "fixed"
                     :left (str x "px")
                     :top (str y "px")
                     :background (:sidebar colors)
                     :border (str "1px solid " (:border colors))
                     :border-radius "6px"
                     :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                     :z-index 1000
                     :min-width "120px"}
             :on-mouse-leave close-context-menu!}
       (for [[type-str color] [["TODO" "#f5a623"] ["NOTE" "#2196f3"] ["FIX" "#f44336"]]]
         ^{:key type-str}
         [:div {:style {:padding "8px 14px"
                        :cursor "pointer"
                        :font-size "0.9rem"
                        :color (:text colors)
                        :display "flex"
                        :align-items "center"
                        :gap "8px"}
                :on-mouse-over #(set! (.. % -target -style -background) (:editor-bg colors))
                :on-mouse-out #(set! (.. % -target -style -background) "transparent")
                :on-click (fn []
                            (wrap-selection-with-annotation! view selection type-str)
                            (close-context-menu!))}
          [:span {:style {:color color}} "●"]
          type-str])])))

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
              :on-change #(swap! annotations/show-markup? not)
              :style {:cursor "pointer"}}]
     (t :show-markup)]))

;; =============================================================================
;; Editor Component
;; =============================================================================

(defn editor-component []
  (let [editor-view (r/atom nil)
        editor-ref (r/atom nil)
        last-chunk-id (r/atom nil)
        last-show-markup (r/atom nil)]
    (r/create-class
     {:display-name "tramando-editor"

      :component-did-mount
      (fn [this]
        (when-let [chunk (model/get-selected-chunk)]
          (let [state (create-editor-state (:content chunk) (:id chunk))
                view (create-editor-view @editor-ref state)]
            (reset! editor-view view)
            (reset! last-chunk-id (:id chunk))
            (reset! last-show-markup @annotations/show-markup?)
            ;; Add right-click handler
            (when @editor-ref
              (.addEventListener @editor-ref "contextmenu"
                                 (fn [e]
                                   (when-let [view @editor-view]
                                     (let [sel (.. view -state -selection -main)]
                                       (when (not= (.-from sel) (.-to sel))
                                         (.preventDefault e)
                                         (reset! context-menu-state {:x (.-clientX e)
                                                                     :y (.-clientY e)
                                                                     :selection sel
                                                                     :view view}))))))))))

      :component-did-update
      (fn [this old-argv]
        (let [chunk (model/get-selected-chunk)
              show-markup? @annotations/show-markup?]
          ;; Recreate editor if chunk changed or show-markup changed
          (when (or (and chunk (not= (:id chunk) @last-chunk-id))
                    (not= show-markup? @last-show-markup))
            (when @editor-view
              (.destroy @editor-view))
            (when chunk
              (let [state (create-editor-state (:content chunk) (:id chunk))
                    view (create-editor-view @editor-ref state)]
                (reset! editor-view view)
                (reset! last-chunk-id (:id chunk))
                (reset! last-show-markup show-markup?)
                ;; Re-add right-click handler
                (when @editor-ref
                  (.addEventListener @editor-ref "contextmenu"
                                     (fn [e]
                                       (when-let [view @editor-view]
                                         (let [sel (.. view -state -selection -main)]
                                           (when (not= (.-from sel) (.-to sel))
                                             (.preventDefault e)
                                             (reset! context-menu-state {:x (.-clientX e)
                                                                         :y (.-clientY e)
                                                                         :selection sel
                                                                         :view view}))))))))))))

      :component-will-unmount
      (fn [this]
        (when @editor-view
          (.destroy @editor-view)))

      :reagent-render
      (fn []
        (let [_selected (model/get-selected-id) ; trigger re-render on selection change
              _show-markup @annotations/show-markup?] ; trigger re-render on toggle
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
          ^{:key aspect-id}
          [:span.aspect-tag {:style {:background (:editor-bg colors)
                                     :color (:accent colors)
                                     :padding "2px 8px"
                                     :border-radius "3px"
                                     :font-size "0.8rem"}}
           (str "@" aspect-id)]))])))

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
                  (str "@" (or (:summary aspect) aspect-id))
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
       [:div {:style {:color (:text colors)
                      :line-height "1.6"
                      :white-space "pre-wrap"
                      :font-size "0.95rem"
                      :opacity "0.85"}}
        content])]))

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
             [:div {:style {:color (:text colors) :line-height "1.6" :white-space "pre-wrap" :opacity "0.85"}}
              aspect-content])]
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
;; Tab Content
;; =============================================================================

(defn tab-content []
  (case @active-tab
    :edit [:div {:style {:display "flex" :flex-direction "column" :flex 1 :overflow "hidden"}}
           [editor-component]]
    :refs [refs-view]
    :read [read-view]
    nil))
