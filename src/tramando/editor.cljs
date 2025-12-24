(ns tramando.editor
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap lineNumbers highlightActiveLine
                                        highlightActiveLineGutter drawSelection]]
            ["@codemirror/commands" :refer [defaultKeymap history historyKeymap
                                            indentWithTab]]
            ["@codemirror/language" :refer [indentOnInput bracketMatching
                                            defaultHighlightStyle syntaxHighlighting]]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@codemirror/search" :refer [searchKeymap highlightSelectionMatches]]))

;; =============================================================================
;; CodeMirror 6 Theme
;; =============================================================================

(def tramando-theme
  (.theme EditorView
          #js {".cm-content" #js {:color "#eee"
                                  :caretColor "#e94560"}
               ".cm-cursor" #js {:borderLeftColor "#e94560"}
               "&.cm-focused .cm-selectionBackground" #js {:backgroundColor "rgba(233, 69, 96, 0.3)"}
               ".cm-selectionBackground" #js {:backgroundColor "rgba(233, 69, 96, 0.2)"}
               ".cm-activeLine" #js {:backgroundColor "rgba(233, 69, 96, 0.08)"}
               ".cm-gutters" #js {:backgroundColor "#16213e"
                                  :color "#666"
                                  :borderRight "1px solid #0f3460"}
               ".cm-activeLineGutter" #js {:backgroundColor "#0f3460"}
               "&" #js {:backgroundColor "#1a1a2e"}}))

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
                                 (history)
                                 (indentOnInput)
                                 (bracketMatching)
                                 (highlightSelectionMatches)
                                 (syntaxHighlighting defaultHighlightStyle)
                                 (markdown)
                                 (.of keymap (.concat defaultKeymap historyKeymap searchKeymap #js [indentWithTab]))
                                 (make-update-listener chunk-id)]}))

(defn create-editor-view [parent-element state]
  (EditorView. #js {:state state
                    :parent parent-element}))

;; =============================================================================
;; Editor Component
;; =============================================================================

(defn editor-component []
  (let [editor-view (r/atom nil)
        editor-ref (r/atom nil)
        last-chunk-id (r/atom nil)]
    (r/create-class
     {:display-name "tramando-editor"

      :component-did-mount
      (fn [this]
        (when-let [chunk (model/get-selected-chunk)]
          (let [state (create-editor-state (:content chunk) (:id chunk))
                view (create-editor-view @editor-ref state)]
            (reset! editor-view view)
            (reset! last-chunk-id (:id chunk)))))

      :component-did-update
      (fn [this old-argv]
        (let [chunk (model/get-selected-chunk)]
          (when (and chunk (not= (:id chunk) @last-chunk-id))
            ;; Chunk changed, recreate editor
            (when @editor-view
              (.destroy @editor-view))
            (let [state (create-editor-state (:content chunk) (:id chunk))
                  view (create-editor-view @editor-ref state)]
              (reset! editor-view view)
              (reset! last-chunk-id (:id chunk))))))

      :component-will-unmount
      (fn [this]
        (when @editor-view
          (.destroy @editor-view)))

      :reagent-render
      (fn []
        (let [_selected (model/get-selected-id)] ; trigger re-render on selection change
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
      (let [chunk (model/get-selected-chunk)]
        ;; Reset editing state when chunk changes
        (when (and chunk (not= (:id chunk) @last-chunk-id))
          (reset! last-chunk-id (:id chunk))
          (reset! editing? false)
          (reset! error-msg nil))
        (when chunk
          (if @editing?
            ;; Editing mode
            [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "8px"}}
             [:span {:style {:color "#666" :font-size "0.85rem"}} "["]
             [:input {:type "text"
                      :value @temp-id
                      :auto-focus true
                      :style {:background "transparent"
                              :border "1px solid #0f3460"
                              :border-radius "3px"
                              :color "#e94560"
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
             [:span {:style {:color "#666" :font-size "0.85rem"}} "]"]
             (when @error-msg
               [:span {:style {:color "#ff6b6b" :font-size "0.75rem" :margin-left "8px"}}
                @error-msg])]

            ;; Display mode
            [:div {:style {:display "flex" :align-items "center" :gap "4px" :margin-bottom "8px"}}
             [:span {:style {:color "#666"
                             :font-size "0.85rem"
                             :font-family "monospace"
                             :cursor "pointer"
                             :padding "2px 6px"
                             :border-radius "3px"
                             :background "#0f3460"}
                     :title "Clicca per modificare l'ID"
                     :on-click (fn []
                                 (reset! temp-id (:id chunk))
                                 (reset! error-msg nil)
                                 (reset! editing? true))}
              (str "[" (:id chunk) "]")]]))))))

;; =============================================================================
;; Summary Input Component
;; =============================================================================

(defn summary-input []
  (let [chunk (model/get-selected-chunk)]
    (when chunk
      [:input {:type "text"
               :value (:summary chunk)
               :placeholder "Titolo del chunk..."
               :on-change (fn [e]
                            (model/update-chunk! (:id chunk)
                                                 {:summary (.. e -target -value)}))}])))

;; =============================================================================
;; Aspects Display
;; =============================================================================

(defn aspects-display []
  (let [chunk (model/get-selected-chunk)]
    (when (and chunk (seq (:aspects chunk)))
      [:div.aspects-list
       (for [aspect-id (:aspects chunk)]
         ^{:key aspect-id}
         [:span.aspect-tag (str "@" aspect-id)])])))

;; =============================================================================
;; Parent Selector
;; =============================================================================

(defn parent-selector []
  (let [chunk (model/get-selected-chunk)
        error-msg (r/atom nil)]
    (fn []
      (let [chunk (model/get-selected-chunk)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          (let [possible-parents (model/get-possible-parents (:id chunk))
                current-parent-id (:parent-id chunk)]
            [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-top "8px"}}
             [:span {:style {:color "#666" :font-size "0.8rem"}} "Parent:"]
             [:select {:value (or current-parent-id "")
                       :style {:background "#0f3460"
                               :color "#eee"
                               :border "1px solid #0f3460"
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
              [:option {:value ""} "(root)"]
              (for [p possible-parents]
                ^{:key (:id p)}
                [:option {:value (:id p)}
                 (str (:id p) " - " (subs (:summary p) 0 (min 25 (count (:summary p)))))])]
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
      (let [chunk (model/get-selected-chunk)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          [:div {:style {:margin-top "12px" :padding-top "12px" :border-top "1px solid #0f3460"}}
           (if @confirming?
             [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
              [:span {:style {:color "#ff6b6b" :font-size "0.85rem"}} "Confermi?"]
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
               "Elimina"]
              [:button {:style {:background "transparent"
                                :color "#888"
                                :border "1px solid #888"
                                :padding "4px 12px"
                                :border-radius "3px"
                                :cursor "pointer"
                                :font-size "0.8rem"}
                        :on-click #(reset! confirming? false)}
               "Annulla"]]
             [:div
              [:button {:style {:background "transparent"
                                :color "#ff6b6b"
                                :border "1px solid #ff6b6b"
                                :padding "6px 12px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.85rem"}
                        :on-click #(reset! confirming? true)}
               "Elimina chunk"]
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
        is-aspect? (model/is-aspect? chunk)]
    [:div {:style {:display "flex" :gap "0" :border-bottom "1px solid #0f3460" :margin-bottom "12px"}}
     [:button {:style {:background (if (= current :edit) "#0f3460" "transparent")
                       :color (if (= current :edit) "#e94560" "#888")
                       :border "none"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :font-size "0.85rem"
                       :border-bottom (when (= current :edit) "2px solid #e94560")}
               :on-click #(set-tab! :edit)}
      "Modifica"]
     [:button {:style {:background (if (= current :refs) "#0f3460" "transparent")
                       :color (if (= current :refs) "#e94560" "#888")
                       :border "none"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :font-size "0.85rem"
                       :border-bottom (when (= current :refs) "2px solid #e94560")}
               :on-click #(set-tab! :refs)}
      (if is-aspect? "Usato da" "Figli")]
     [:button {:style {:background (if (= current :read) "#0f3460" "transparent")
                       :color (if (= current :read) "#e94560" "#888")
                       :border "none"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :font-size "0.85rem"
                       :border-bottom (when (= current :read) "2px solid #e94560")}
               :on-click #(set-tab! :read)}
      "Lettura"]]))

;; =============================================================================
;; Aspects Manager (add/remove aspects from a chunk)
;; =============================================================================

(defn aspects-manager []
  (let [dropdown-open? (r/atom false)]
    (fn []
      (let [chunk (model/get-selected-chunk)
            all-aspects (model/get-all-aspects)
            current-aspects (:aspects chunk)]
        (when (and chunk (not (model/is-aspect-container? (:id chunk))))
          [:div {:style {:margin-top "8px"}}
           [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px" :align-items "center"}}
            ;; Current aspects with remove button
            (for [aspect-id current-aspects]
              (let [aspect (first (filter #(= (:id %) aspect-id) (model/get-chunks)))]
                ^{:key aspect-id}
                [:span {:style {:background "#0f3460"
                                :color "#e94560"
                                :padding "2px 8px"
                                :border-radius "3px"
                                :font-size "0.8rem"
                                :display "flex"
                                :align-items "center"
                                :gap "4px"}}
                 (str "@" (or (:summary aspect) aspect-id))
                 [:button {:style {:background "none"
                                   :border "none"
                                   :color "#888"
                                   :cursor "pointer"
                                   :padding "0 2px"
                                   :font-size "0.9rem"
                                   :line-height "1"}
                           :title "Rimuovi"
                           :on-click #(model/remove-aspect-from-chunk! (:id chunk) aspect-id)}
                  "×"]]))

            ;; Add aspect dropdown
            [:div {:style {:position "relative"}}
             [:button {:style {:background "transparent"
                               :color "#888"
                               :border "1px dashed #0f3460"
                               :padding "2px 8px"
                               :border-radius "3px"
                               :font-size "0.8rem"
                               :cursor "pointer"}
                       :on-click #(swap! dropdown-open? not)}
              "+ Aspetto"]
             (when @dropdown-open?
               [:div {:style {:position "absolute"
                              :top "100%"
                              :left 0
                              :background "#16213e"
                              :border "1px solid #0f3460"
                              :border-radius "4px"
                              :min-width "200px"
                              :max-height "200px"
                              :overflow-y "auto"
                              :z-index 100
                              :margin-top "4px"}}
                (let [available (remove #(contains? current-aspects (:id %)) all-aspects)]
                  (if (empty? available)
                    [:div {:style {:padding "8px" :color "#666" :font-size "0.8rem"}}
                     "Tutti gli aspetti già aggiunti"]
                    (for [aspect available]
                      ^{:key (:id aspect)}
                      [:div {:style {:padding "6px 10px"
                                     :cursor "pointer"
                                     :font-size "0.8rem"}
                             :on-mouse-over #(set! (.. % -target -style -background) "#0f3460")
                             :on-mouse-out #(set! (.. % -target -style -background) "transparent")
                             :on-click (fn []
                                         (model/add-aspect-to-chunk! (:id chunk) (:id aspect))
                                         (reset! dropdown-open? false))}
                       (str "@" (:id aspect) " - " (:summary aspect))])))])]]])))))

;; =============================================================================
;; References View (who uses this aspect / children list)
;; =============================================================================

(defn refs-view []
  (let [chunk (model/get-selected-chunk)
        is-aspect? (model/is-aspect? chunk)]
    [:div {:style {:padding "16px" :overflow-y "auto" :flex 1}}
     (if is-aspect?
       ;; Show chunks that use this aspect
       (let [users (model/chunks-using-aspect (:id chunk))]
         [:div
          [:h3 {:style {:color "#888" :font-size "0.85rem" :margin-bottom "12px"}}
           (str "Chunk che usano @" (:id chunk) " (" (count users) ")")]
          (if (empty? users)
            [:div {:style {:color "#666" :font-style "italic"}}
             "Nessun chunk usa questo aspetto"]
            [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
             (for [c users]
               ^{:key (:id c)}
               [:div {:style {:background "#16213e"
                              :padding "10px 14px"
                              :border-radius "4px"
                              :cursor "pointer"
                              :border-left "3px solid #e94560"}
                      :on-click #(model/select-chunk! (:id c))}
                [:div {:style {:color "#e94560" :font-size "0.8rem" :margin-bottom "4px"}}
                 (:id c)]
                [:div {:style {:color "#eee"}}
                 (:summary c)]])])])

       ;; Show children
       (let [children (model/get-children (:id chunk))]
         [:div
          [:h3 {:style {:color "#888" :font-size "0.85rem" :margin-bottom "12px"}}
           (str "Figli di \"" (:summary chunk) "\" (" (count children) ")")]
          (if (empty? children)
            [:div {:style {:color "#666" :font-style "italic"}}
             "Nessun figlio"]
            [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
             (for [c children]
               ^{:key (:id c)}
               [:div {:style {:background "#16213e"
                              :padding "10px 14px"
                              :border-radius "4px"
                              :cursor "pointer"
                              :border-left "3px solid #0f3460"}
                      :on-click #(model/select-chunk! (:id c))}
                [:div {:style {:color "#e94560" :font-size "0.8rem" :margin-bottom "4px"}}
                 (:id c)]
                [:div {:style {:color "#eee" :margin-bottom "4px"}}
                 (:summary c)]
                (when (seq (:content c))
                  [:div {:style {:color "#888" :font-size "0.85rem"}}
                   (subs (:content c) 0 (min 100 (count (:content c))))
                   (when (> (count (:content c)) 100) "...")])])])]))]))

;; =============================================================================
;; Read View (expanded content, read-only)
;; =============================================================================

(defn- collect-content [chunk-id depth]
  "Recursively collect content from chunk and all descendants"
  (let [chunk (first (filter #(= (:id %) chunk-id) (model/get-chunks)))
        children (model/get-children chunk-id)]
    (cons {:chunk chunk :depth depth}
          (mapcat #(collect-content (:id %) (inc depth)) children))))

(defn- render-chunk-block [{:keys [chunk depth]}]
  [:div {:style {:margin-left (str (* depth 20) "px")
                 :margin-bottom "16px"
                 :padding-bottom "16px"
                 :border-bottom (when (zero? depth) "1px solid #0f3460")}}
   [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "8px"}}
    [:span {:style {:color "#e94560" :font-size "0.75rem" :font-family "monospace"}}
     (str "[" (:id chunk) "]")]
    [:h3 {:style {:color "#eee"
                  :font-size (case depth 0 "1.3rem" 1 "1.1rem" "1rem")
                  :font-weight (if (< depth 2) "600" "500")
                  :margin 0}}
     (:summary chunk)]
    (when (seq (:aspects chunk))
      [:span {:style {:color "#888" :font-size "0.75rem"}}
       (str "(" (str/join ", " (map #(str "@" %) (:aspects chunk))) ")")])]
   (when (seq (:content chunk))
     [:div {:style {:color "#ccc"
                    :line-height "1.6"
                    :white-space "pre-wrap"
                    :font-size "0.95rem"}}
      (:content chunk)])])

(defn read-view []
  (let [chunk (model/get-selected-chunk)
        is-aspect? (model/is-aspect? chunk)]
    [:div {:style {:padding "20px" :overflow-y "auto" :flex 1 :background "#1a1a2e"}}
     (if is-aspect?
       ;; For aspects: show the aspect info, then all chunks that use it with their content
       (let [users (model/chunks-using-aspect (:id chunk))]
         [:div
          ;; Aspect header
          [:div {:style {:margin-bottom "24px" :padding-bottom "16px" :border-bottom "2px solid #e94560"}}
           [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "8px"}}
            [:span {:style {:color "#e94560" :font-size "0.85rem" :font-family "monospace"}}
             (str "@" (:id chunk))]
            [:h2 {:style {:color "#eee" :font-size "1.4rem" :margin 0}}
             (:summary chunk)]]
           (when (seq (:content chunk))
             [:div {:style {:color "#ccc" :line-height "1.6" :white-space "pre-wrap"}}
              (:content chunk)])]
          ;; Chunks using this aspect
          (if (empty? users)
            [:div {:style {:color "#666" :font-style "italic"}}
             "Nessun chunk usa questo aspetto"]
            [:div
             [:h3 {:style {:color "#888" :font-size "0.85rem" :margin-bottom "16px"}}
              (str "Appare in " (count users) " chunk:")]
             (for [user users]
               (let [all-content (collect-content (:id user) 0)]
                 ^{:key (:id user)}
                 [:div {:style {:margin-bottom "24px" :padding "16px" :background "#16213e" :border-radius "6px"}}
                  (for [{:keys [chunk depth] :as item} all-content]
                    ^{:key (:id chunk)}
                    [render-chunk-block item])]))])])

       ;; For structural chunks: show hierarchy as before
       (let [all-content (collect-content (:id chunk) 0)]
         (for [{:keys [chunk depth] :as item} all-content]
           ^{:key (:id chunk)}
           [render-chunk-block item])))]))

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
