(ns tramando.views
  (:require [reagent.core :as r]
            [tramando.model :as model]
            [tramando.editor :as editor]
            [tramando.outline :as outline]))

;; =============================================================================
;; Header
;; =============================================================================

(defonce file-input-ref (r/atom nil))

(defn header []
  [:div.header
   [:h1 "Tramando"]
   [:div {:style {:flex 1}}]
   [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
    ;; Hidden file input for loading
    [:input {:type "file"
             :accept ".md,.txt"
             :style {:display "none"}
             :ref #(reset! file-input-ref %)
             :on-change (fn [e]
                          (when-let [file (-> e .-target .-files (aget 0))]
                            (let [reader (js/FileReader.)]
                              (set! (.-onload reader)
                                    (fn [evt]
                                      (let [content (-> evt .-target .-result)]
                                        (model/load-file-content! content (.-name file)))))
                              (.readAsText reader file))
                            ;; Reset input so same file can be loaded again
                            (set! (-> e .-target .-value) "")))}]
    ;; Load button
    [:button
     {:style {:background "transparent"
              :color "#888"
              :border "1px solid #0f3460"
              :padding "6px 12px"
              :border-radius "4px"
              :cursor "pointer"
              :font-size "0.85rem"}
      :on-click #(when @file-input-ref (.click @file-input-ref))}
     "Carica"]
    ;; Save button
    [:button
     {:style {:background "#e94560"
              :color "white"
              :border "none"
              :padding "6px 12px"
              :border-radius "4px"
              :cursor "pointer"
              :font-size "0.85rem"}
      :on-click #(model/save-file!)}
     "Salva"]
    ;; Filename display (editable)
    [:input {:type "text"
             :value (:filename @model/app-state)
             :style {:background "transparent"
                     :border "1px solid #0f3460"
                     :border-radius "4px"
                     :color "#888"
                     :padding "4px 8px"
                     :font-size "0.85rem"
                     :width "150px"}
             :on-change #(model/set-filename! (-> % .-target .-value))}]]])

;; =============================================================================
;; Editor Panel
;; =============================================================================

(defn editor-panel []
  (let [chunk (model/get-selected-chunk)]
    [:div.editor-panel
     (if chunk
       [:<>
        [:div.editor-header
         [editor/id-input]
         [editor/summary-input]
         [editor/aspects-manager]
         [editor/parent-selector]
         [editor/delete-button]]
        [editor/tab-bar]
        [editor/tab-content]]
       [:div {:style {:display "flex"
                      :align-items "center"
                      :justify-content "center"
                      :height "100%"
                      :color "#666"}}
        "Seleziona un chunk dall'outline o creane uno nuovo"])]))

;; =============================================================================
;; Main Layout
;; =============================================================================

(defn main-layout []
  [:div#app
   [header]
   [:div.main-container
    [outline/outline-panel]
    [editor-panel]]])

;; =============================================================================
;; App Root
;; =============================================================================

(defn app []
  [main-layout])
