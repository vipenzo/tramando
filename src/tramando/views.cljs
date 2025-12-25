(ns tramando.views
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.editor :as editor]
            [tramando.outline :as outline]
            [tramando.settings :as settings]))

;; =============================================================================
;; Settings File Input Ref
;; =============================================================================

(defonce settings-file-input-ref (r/atom nil))

;; =============================================================================
;; Color Picker Component
;; =============================================================================

(defn color-picker [label color-key]
  (let [colors (:colors @settings/settings)]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :justify-content "space-between"
                   :padding "6px 0"}}
     [:label {:style {:color (settings/get-color :text)
                      :font-size "0.85rem"}}
      label]
     [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
      [:input {:type "color"
               :value (get colors color-key)
               :style {:width "40px"
                       :height "28px"
                       :border "none"
                       :cursor "pointer"
                       :background "transparent"}
               :on-change #(settings/set-color! color-key (-> % .-target .-value))}]
      [:input {:type "text"
               :value (get colors color-key)
               :style {:width "80px"
                       :background (settings/get-color :editor-bg)
                       :border (str "1px solid " (settings/get-color :border))
                       :border-radius "3px"
                       :color (settings/get-color :text)
                       :padding "4px 6px"
                       :font-size "0.75rem"
                       :font-family "monospace"}
               :on-change #(let [v (-> % .-target .-value)]
                             (when (re-matches #"^#[0-9a-fA-F]{6}$" v)
                               (settings/set-color! color-key v)))}]]]))

;; =============================================================================
;; Settings Modal
;; =============================================================================

(defn settings-modal []
  (let [colors (:colors @settings/settings)
        current-theme (:theme @settings/settings)
        autosave-delay (/ (:autosave-delay-ms @settings/settings) 1000)]
    [:div {:style {:position "fixed"
                   :top 0 :left 0 :right 0 :bottom 0
                   :background "rgba(0,0,0,0.7)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}
           :on-click #(when (= (.-target %) (.-currentTarget %))
                        (reset! settings/settings-open? false))}
     [:div {:style {:background (settings/get-color :sidebar)
                    :border-radius "8px"
                    :padding "24px"
                    :width "500px"
                    :max-height "80vh"
                    :overflow-y "auto"
                    :box-shadow "0 10px 40px rgba(0,0,0,0.5)"}}
      ;; Header
      [:div {:style {:display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :margin-bottom "20px"
                     :padding-bottom "12px"
                     :border-bottom (str "1px solid " (settings/get-color :border))}}
       [:h2 {:style {:margin 0
                     :color (settings/get-color :text)
                     :font-size "1.3rem"}}
        "Impostazioni"]
       [:button {:style {:background "transparent"
                         :border "none"
                         :color (settings/get-color :text-muted)
                         :font-size "1.5rem"
                         :cursor "pointer"
                         :padding "0"
                         :line-height "1"}
                 :on-click #(reset! settings/settings-open? false)}
        "×"]]

      ;; Theme Selector
      [:div {:style {:margin-bottom "20px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "8px"}}
        "Tema predefinito"]
       [:select {:value (name (if (= current-theme :custom) :dark current-theme))
                 :style {:width "100%"
                         :background (settings/get-color :editor-bg)
                         :color (settings/get-color :text)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :padding "8px 12px"
                         :font-size "0.9rem"
                         :cursor "pointer"}
                 :on-change #(settings/set-theme! (keyword (-> % .-target .-value)))}
        [:option {:value "dark"} "Dark"]
        [:option {:value "light"} "Light"]
        [:option {:value "sepia"} "Sepia"]]]

      ;; Autosave Delay
      [:div {:style {:margin-bottom "20px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "8px"}}
        (str "Ritardo autosave: " autosave-delay " secondi")]
       [:input {:type "range"
                :min 1
                :max 10
                :value autosave-delay
                :style {:width "100%"
                        :cursor "pointer"}
                :on-change #(settings/set-autosave-delay!
                             (* 1000 (js/parseInt (-> % .-target .-value))))}]]

      ;; Colors Section
      [:div {:style {:margin-bottom "20px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "12px"}}
        "Colori personalizzati"]

       [:div {:style {:background (settings/get-color :background)
                      :border-radius "6px"
                      :padding "12px"}}
        ;; UI Colors
        [:div {:style {:margin-bottom "12px"
                       :padding-bottom "12px"
                       :border-bottom (str "1px solid " (settings/get-color :border))}}
         [:span {:style {:color (settings/get-color :text-muted)
                         :font-size "0.75rem"
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"}}
          "Interfaccia"]
         [color-picker "Sfondo" :background]
         [color-picker "Sidebar" :sidebar]
         [color-picker "Editor" :editor-bg]
         [color-picker "Bordi" :border]
         [color-picker "Testo" :text]
         [color-picker "Testo secondario" :text-muted]
         [color-picker "Accento" :accent]]

        ;; Category Colors
        [:div
         [:span {:style {:color (settings/get-color :text-muted)
                         :font-size "0.75rem"
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"}}
          "Categorie"]
         [color-picker "Struttura" :structure]
         [color-picker "Personaggi" :personaggi]
         [color-picker "Luoghi" :luoghi]
         [color-picker "Temi" :temi]
         [color-picker "Sequenze" :sequenze]
         [color-picker "Timeline" :timeline]]]]

      ;; Import/Export Section
      [:div {:style {:margin-bottom "20px"
                     :padding-top "12px"
                     :border-top (str "1px solid " (settings/get-color :border))}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "12px"}}
        "Import/Export"]
       [:div {:style {:display "flex" :gap "8px"}}
        ;; Hidden file input for importing
        [:input {:type "file"
                 :accept ".edn"
                 :style {:display "none"}
                 :ref #(reset! settings-file-input-ref %)
                 :on-change (fn [e]
                              (when-let [file (-> e .-target .-files (aget 0))]
                                (let [reader (js/FileReader.)]
                                  (set! (.-onload reader)
                                        (fn [evt]
                                          (let [content (-> evt .-target .-result)
                                                result (settings/import-settings! content)]
                                            (when (:error result)
                                              (js/alert (:error result))))))
                                  (.readAsText reader file))
                                (set! (-> e .-target .-value) "")))}]
        [:button {:style {:flex 1
                          :background "transparent"
                          :color (settings/get-color :text-muted)
                          :border (str "1px solid " (settings/get-color :border))
                          :padding "8px 12px"
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.85rem"}
                  :on-click #(when @settings-file-input-ref
                               (.click @settings-file-input-ref))}
         "Importa settings.edn"]
        [:button {:style {:flex 1
                          :background "transparent"
                          :color (settings/get-color :text-muted)
                          :border (str "1px solid " (settings/get-color :border))
                          :padding "8px 12px"
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.85rem"}
                  :on-click #(settings/download-settings!)}
         "Esporta settings.edn"]]]

      ;; Action Buttons
      [:div {:style {:display "flex" :gap "8px" :justify-content "flex-end"}}
       [:button {:style {:background "transparent"
                         :color (settings/get-color :text-muted)
                         :border (str "1px solid " (settings/get-color :border))
                         :padding "8px 16px"
                         :border-radius "4px"
                         :cursor "pointer"
                         :font-size "0.85rem"}
                 :on-click #(settings/reset-to-theme!)}
        "Reset tema"]
       [:button {:style {:background (settings/get-color :accent)
                         :color "white"
                         :border "none"
                         :padding "8px 16px"
                         :border-radius "4px"
                         :cursor "pointer"
                         :font-size "0.85rem"}
                 :on-click (fn []
                             (settings/save-settings!)
                             (reset! settings/settings-open? false))}
        "Salva"]]]]))

;; =============================================================================
;; Save Status Indicator
;; =============================================================================

(defn save-status-indicator []
  (let [status @model/save-status]
    (when (not= status :idle)
      [:span {:style {:padding "4px 8px"
                      :border-radius "4px"
                      :font-size "0.8rem"
                      :transition "opacity 0.3s"
                      :background (case status
                                    :modified "#f5a623"
                                    :saving "#f5a623"
                                    :saved "#4caf50"
                                    "transparent")
                      :color "#fff"}}
       (case status
         :modified "Modificato"
         :saving "Salvataggio..."
         :saved "Salvato"
         "")])))

;; =============================================================================
;; Header
;; =============================================================================

(defonce file-input-ref (r/atom nil))

(defn header []
  (let [colors (:colors @settings/settings)]
    [:div.header {:style {:background (:sidebar colors)
                          :border-bottom (str "1px solid " (:border colors))}}
     [:h1 {:style {:color (:accent colors)}} "Tramando"]
     [:div {:style {:flex 1}}]
     [save-status-indicator]
     [:div {:style {:display "flex" :gap "8px" :align-items "center" :margin-left "12px"}}
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
                :color (:text-muted colors)
                :border (str "1px solid " (:border colors))
                :padding "6px 12px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "0.85rem"}
        :on-click #(when @file-input-ref (.click @file-input-ref))}
       "Carica"]
      ;; Save button
      [:button
       {:style {:background (:accent colors)
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
                       :border (str "1px solid " (:border colors))
                       :border-radius "4px"
                       :color (:text-muted colors)
                       :padding "4px 8px"
                       :font-size "0.85rem"
                       :width "150px"}
               :on-change #(model/set-filename! (-> % .-target .-value))}]
      ;; Settings button
      [:button
       {:style {:background "transparent"
                :color (:text-muted colors)
                :border (str "1px solid " (:border colors))
                :padding "6px 10px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "1rem"}
        :title "Impostazioni"
        :on-click #(reset! settings/settings-open? true)}
       "⚙"]]]))

;; =============================================================================
;; Editor Panel
;; =============================================================================

(defn editor-panel []
  (let [chunk (model/get-selected-chunk)
        colors (:colors @settings/settings)]
    [:div.editor-panel {:style {:background (:background colors)}}
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
                      :color (:text-muted colors)}}
        "Seleziona un chunk dall'outline o creane uno nuovo"])]))

;; =============================================================================
;; Keyboard Shortcuts
;; =============================================================================

(defn- handle-keydown [e]
  (let [key (str/lower-case (.-key e))
        ctrl-or-cmd (or (.-ctrlKey e) (.-metaKey e))
        shift (.-shiftKey e)]
    (cond
      ;; Redo: Ctrl+Shift+Z or Cmd+Shift+Z
      (and ctrl-or-cmd shift (= key "z"))
      (do
        (.preventDefault e)
        (model/redo!))

      ;; Undo: Ctrl+Z or Cmd+Z (without shift)
      (and ctrl-or-cmd (not shift) (= key "z"))
      (do
        (.preventDefault e)
        (model/undo!))

      ;; Escape closes settings
      (and @settings/settings-open? (= key "escape"))
      (reset! settings/settings-open? false))))

;; =============================================================================
;; Main Layout
;; =============================================================================

(defn main-layout []
  (r/create-class
   {:component-did-mount
    (fn [_]
      (.addEventListener js/document "keydown" handle-keydown))

    :component-will-unmount
    (fn [_]
      (.removeEventListener js/document "keydown" handle-keydown))

    :reagent-render
    (fn []
      (let [colors (:colors @settings/settings)]
        [:div#app {:style {:background (:background colors)
                           :color (:text colors)}}
         [header]
         [:div.main-container
          [outline/outline-panel]
          [editor-panel]]
         ;; Settings Modal
         (when @settings/settings-open?
           [settings-modal])]))}))

;; =============================================================================
;; App Root
;; =============================================================================

(defn app []
  [main-layout])
