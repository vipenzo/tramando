(ns tramando.views
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.editor :as editor]
            [tramando.outline :as outline]
            [tramando.settings :as settings]
            [tramando.radial :as radial]
            [tramando.export-pdf :as export-pdf]
            [tramando.annotations :as annotations]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]))

;; =============================================================================
;; View Mode State
;; =============================================================================

(defonce view-mode (r/atom :editor)) ;; :editor or :radial

;; =============================================================================
;; Export Dropdown State
;; =============================================================================

(defonce export-dropdown-open? (r/atom false))

;; =============================================================================
;; Splash Screen State
;; =============================================================================

(defonce show-splash? (r/atom true))
(defonce splash-fade-in? (r/atom false))
(defonce splash-file-input-ref (r/atom nil))

;; =============================================================================
;; Settings File Input Ref
;; =============================================================================

(defonce settings-file-input-ref (r/atom nil))

;; =============================================================================
;; Metadata Modal State
;; =============================================================================

(defonce metadata-open? (r/atom false))
(defonce new-custom-key (r/atom ""))
(defonce new-custom-value (r/atom ""))

;; =============================================================================
;; Tutorial State
;; =============================================================================

(defonce tutorial-open? (r/atom false))
(defonce tutorial-step (r/atom 0))

(defn open-tutorial! []
  (reset! tutorial-step 0)
  (reset! tutorial-open? true))

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
        current-lang (:language @settings/settings)
        autosave-delay (/ (:autosave-delay-ms @settings/settings) 1000)
        _ @i18n/current-lang] ; subscribe to language changes
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
        (t :settings)]
       [:button {:style {:background "transparent"
                         :border "none"
                         :color (settings/get-color :text-muted)
                         :font-size "1.5rem"
                         :cursor "pointer"
                         :padding "0"
                         :line-height "1"}
                 :on-click #(reset! settings/settings-open? false)}
        "×"]]

      ;; Language Selector
      [:div {:style {:margin-bottom "20px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "flex"
                        :align-items "center"
                        :gap "6px"
                        :margin-bottom "8px"}}
        (t :language)
        [help/help-icon :help-language]]
       [:select {:value (name (or current-lang :it))
                 :style {:width "100%"
                         :background (settings/get-color :editor-bg)
                         :color (settings/get-color :text)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :padding "8px 12px"
                         :font-size "0.9rem"
                         :cursor "pointer"}
                 :on-change #(settings/set-language! (keyword (-> % .-target .-value)))}
        (for [[lang-key lang-name] i18n/available-languages]
          ^{:key lang-key}
          [:option {:value (name lang-key)} lang-name])]]

      ;; Theme Selector
      [:div {:style {:margin-bottom "20px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "8px"}}
        (t :default-theme)]
       [:select {:value (name (if (= current-theme :custom) :tessuto current-theme))
                 :style {:width "100%"
                         :background (settings/get-color :editor-bg)
                         :color (settings/get-color :text)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :padding "8px 12px"
                         :font-size "0.9rem"
                         :cursor "pointer"}
                 :on-change #(settings/set-theme! (keyword (-> % .-target .-value)))}
        [:option {:value "tessuto"} "Tessuto"]
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
        (t :autosave-delay autosave-delay)]
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
        (t :custom-colors)]

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
          (t :interface)]
         [color-picker (t :color-background) :background]
         [color-picker (t :color-sidebar) :sidebar]
         [color-picker (t :color-editor) :editor-bg]
         [color-picker (t :color-border) :border]
         [color-picker (t :color-text) :text]
         [color-picker (t :color-text-secondary) :text-muted]
         [color-picker (t :color-accent) :accent]]

        ;; Category Colors
        [:div
         [:span {:style {:color (settings/get-color :text-muted)
                         :font-size "0.75rem"
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"}}
          (t :categories)]
         [color-picker (t :color-structure) :structure]
         [color-picker (t :personaggi) :personaggi]
         [color-picker (t :luoghi) :luoghi]
         [color-picker (t :temi) :temi]
         [color-picker (t :sequenze) :sequenze]
         [color-picker (t :timeline) :timeline]]]]

      ;; Import/Export Section
      [:div {:style {:margin-bottom "20px"
                     :padding-top "12px"
                     :border-top (str "1px solid " (settings/get-color :border))}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "12px"}}
        (t :import-export)]
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
         (t :import-settings)]
        [:button {:style {:flex 1
                          :background "transparent"
                          :color (settings/get-color :text-muted)
                          :border (str "1px solid " (settings/get-color :border))
                          :padding "8px 12px"
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.85rem"}
                  :on-click #(settings/download-settings!)}
         (t :export-settings)]]]

      ;; Help Section
      [:div {:style {:margin-bottom "20px"
                     :padding-top "16px"
                     :border-top (str "1px solid " (settings/get-color :border))}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.85rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "12px"}}
        (t :help)]
       [:button {:style {:background "transparent"
                         :color (settings/get-color :text)
                         :border (str "1px solid " (settings/get-color :border))
                         :padding "10px 16px"
                         :border-radius "4px"
                         :cursor "pointer"
                         :font-size "0.9rem"
                         :width "100%"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :gap "8px"}
                 :on-click (fn []
                             (reset! settings/settings-open? false)
                             (open-tutorial!))}
        [:span "📖"]
        (t :review-tutorial)]]

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
        (t :reset-theme)]
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
        (t :save)]]]]))

;; =============================================================================
;; Metadata Modal
;; =============================================================================

(defn metadata-modal []
  (let [metadata (model/get-metadata)
        colors (:colors @settings/settings)
        _ @i18n/current-lang] ; subscribe to language changes
    [:div {:style {:position "fixed"
                   :top 0 :left 0 :right 0 :bottom 0
                   :background "rgba(0,0,0,0.7)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}
           :on-click #(when (= (.-target %) (.-currentTarget %))
                        (reset! metadata-open? false))}
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
        (t :project-info)]
       [:button {:style {:background "transparent"
                         :border "none"
                         :color (settings/get-color :text-muted)
                         :font-size "1.5rem"
                         :cursor "pointer"
                         :padding "0"
                         :line-height "1"}
                 :on-click #(reset! metadata-open? false)}
        "×"]]

      ;; Title (required)
      [:div {:style {:margin-bottom "16px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.85rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "6px"}}
        (t :title-required)]
       [:input {:type "text"
                :value (or (:title metadata) "")
                :placeholder (t :project-title-placeholder)
                :style {:width "100%"
                        :background (settings/get-color :editor-bg)
                        :border (str "1px solid " (settings/get-color :border))
                        :border-radius "4px"
                        :color (settings/get-color :text)
                        :padding "10px"
                        :font-size "1rem"
                        :box-sizing "border-box"}
                :on-change #(model/update-metadata! {:title (-> % .-target .-value)})}]]

      ;; Author
      [:div {:style {:margin-bottom "16px"}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.85rem"
                        :display "block"
                        :margin-bottom "6px"}}
        (t :author)]
       [:input {:type "text"
                :value (or (:author metadata) "")
                :placeholder (t :author-placeholder)
                :style {:width "100%"
                        :background (settings/get-color :editor-bg)
                        :border (str "1px solid " (settings/get-color :border))
                        :border-radius "4px"
                        :color (settings/get-color :text)
                        :padding "10px"
                        :font-size "0.9rem"
                        :box-sizing "border-box"}
                :on-change #(model/update-metadata! {:author (-> % .-target .-value)})}]]

      ;; Language and Year row
      [:div {:style {:display "flex" :gap "16px" :margin-bottom "16px"}}
       [:div {:style {:flex 1}}
        [:label {:style {:color (settings/get-color :text)
                         :font-size "0.85rem"
                         :display "block"
                         :margin-bottom "6px"}}
         (t :language)]
        [:select {:value (or (:language metadata) "it")
                  :style {:width "100%"
                          :background (settings/get-color :editor-bg)
                          :border (str "1px solid " (settings/get-color :border))
                          :border-radius "4px"
                          :color (settings/get-color :text)
                          :padding "10px"
                          :font-size "0.9rem"
                          :cursor "pointer"}
                  :on-change #(model/update-metadata! {:language (-> % .-target .-value)})}
         (for [[code label] model/available-languages]
           ^{:key code}
           [:option {:value code} label])]]
       [:div {:style {:flex 1}}
        [:label {:style {:color (settings/get-color :text)
                         :font-size "0.85rem"
                         :display "block"
                         :margin-bottom "6px"}}
         (t :year)]
        [:input {:type "number"
                 :value (or (:year metadata) "")
                 :placeholder "2024"
                 :style {:width "100%"
                         :background (settings/get-color :editor-bg)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :color (settings/get-color :text)
                         :padding "10px"
                         :font-size "0.9rem"
                         :box-sizing "border-box"}
                 :on-change #(let [v (-> % .-target .-value)]
                               (model/update-metadata!
                                {:year (when (seq v) (js/parseInt v))}))}]]]

      ;; ISBN and Publisher row
      [:div {:style {:display "flex" :gap "16px" :margin-bottom "16px"}}
       [:div {:style {:flex 1}}
        [:label {:style {:color (settings/get-color :text)
                         :font-size "0.85rem"
                         :display "block"
                         :margin-bottom "6px"}}
         (t :isbn)]
        [:input {:type "text"
                 :value (or (:isbn metadata) "")
                 :placeholder "978-..."
                 :style {:width "100%"
                         :background (settings/get-color :editor-bg)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :color (settings/get-color :text)
                         :padding "10px"
                         :font-size "0.9rem"
                         :box-sizing "border-box"}
                 :on-change #(model/update-metadata! {:isbn (-> % .-target .-value)})}]]
       [:div {:style {:flex 1}}
        [:label {:style {:color (settings/get-color :text)
                         :font-size "0.85rem"
                         :display "block"
                         :margin-bottom "6px"}}
         (t :publisher)]
        [:input {:type "text"
                 :value (or (:publisher metadata) "")
                 :placeholder (t :publisher-placeholder)
                 :style {:width "100%"
                         :background (settings/get-color :editor-bg)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :color (settings/get-color :text)
                         :padding "10px"
                         :font-size "0.9rem"
                         :box-sizing "border-box"}
                 :on-change #(model/update-metadata! {:publisher (-> % .-target .-value)})}]]]

      ;; Custom fields section
      [:div {:style {:margin-bottom "16px"
                     :padding-top "16px"
                     :border-top (str "1px solid " (settings/get-color :border))}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.85rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "12px"}}
        (t :custom-fields)]

       ;; Existing custom fields
       (for [[k v] (:custom metadata)]
         ^{:key (name k)}
         [:div {:style {:display "flex" :gap "8px" :margin-bottom "8px" :align-items "center"}}
          [:input {:type "text"
                   :value (name k)
                   :disabled true
                   :style {:flex 1
                           :background (settings/get-color :background)
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :color (settings/get-color :text-muted)
                           :padding "8px"
                           :font-size "0.85rem"}}]
          [:input {:type "text"
                   :value v
                   :style {:flex 2
                           :background (settings/get-color :editor-bg)
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :color (settings/get-color :text)
                           :padding "8px"
                           :font-size "0.85rem"}
                   :on-change #(model/set-custom-field! k (-> % .-target .-value))}]
          [:button {:style {:background "transparent"
                            :border "none"
                            :color (settings/get-color :accent)
                            :cursor "pointer"
                            :font-size "1.2rem"
                            :padding "4px 8px"}
                    :on-click #(model/remove-custom-field! k)}
           "×"]])

       ;; Add new custom field
       [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
        [:input {:type "text"
                 :value @new-custom-key
                 :placeholder (t :key)
                 :style {:flex 1
                         :background (settings/get-color :editor-bg)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :color (settings/get-color :text)
                         :padding "8px"
                         :font-size "0.85rem"}
                 :on-change #(reset! new-custom-key (-> % .-target .-value))}]
        [:input {:type "text"
                 :value @new-custom-value
                 :placeholder (t :value)
                 :style {:flex 2
                         :background (settings/get-color :editor-bg)
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :color (settings/get-color :text)
                         :padding "8px"
                         :font-size "0.85rem"}
                 :on-change #(reset! new-custom-value (-> % .-target .-value))}]
        [:button {:style {:background (settings/get-color :accent)
                          :color "white"
                          :border "none"
                          :padding "8px 12px"
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.85rem"}
                  :disabled (empty? @new-custom-key)
                  :on-click (fn []
                              (when (seq @new-custom-key)
                                (model/set-custom-field! @new-custom-key @new-custom-value)
                                (reset! new-custom-key "")
                                (reset! new-custom-value "")))}
         "+"]]]

      ;; Close button
      [:div {:style {:display "flex" :justify-content "flex-end" :margin-top "20px"}}
       [:button {:style {:background (settings/get-color :accent)
                         :color "white"
                         :border "none"
                         :padding "10px 20px"
                         :border-radius "4px"
                         :cursor "pointer"
                         :font-size "0.9rem"}
                 :on-click #(reset! metadata-open? false)}
        (t :close)]]]]))

;; =============================================================================
;; Tutorial Modal
;; =============================================================================

(defn get-tutorial-slides []
  [{:title (t :tutorial-welcome-title)
    :icon "logo"
    :text (t :tutorial-welcome-text)}
   {:title (t :tutorial-chunks-title)
    :icon "tree"
    :text (t :tutorial-chunks-text)}
   {:title (t :tutorial-story-title)
    :icon "book"
    :text (t :tutorial-story-text)}
   {:title (t :tutorial-aspects-title)
    :icon "aspects"
    :text (t :tutorial-aspects-text)}
   {:title (t :tutorial-weaving-title)
    :icon "network"
    :text (t :tutorial-weaving-text)}
   {:title (t :tutorial-notes-title)
    :icon "note"
    :text (t :tutorial-notes-text)}
   {:title (t :tutorial-search-title)
    :icon "search"
    :text (t :tutorial-search-text)}
   {:title (t :tutorial-map-title)
    :icon "radial"
    :text (t :tutorial-map-text)}
   {:title (t :tutorial-export-title)
    :icon "pdf"
    :text (t :tutorial-export-text)}
   {:title (t :tutorial-start-title)
    :icon "start"
    :text (t :tutorial-start-text)}])

(defn- tutorial-icon [icon-type colors]
  (case icon-type
    "logo" [:div {:style {:font-size "3rem" :text-align "center"}} "🧵"]
    "tree" [:div {:style {:font-size "3rem" :text-align "center"}} "🌳"]
    "book" [:div {:style {:font-size "3rem" :text-align "center"}} "📖"]
    "aspects" [:div {:style {:display "flex" :justify-content "center" :gap "12px" :font-size "1.5rem"}}
               [:span {:style {:color (:personaggi colors)}} "👤"]
               [:span {:style {:color (:luoghi colors)}} "🏛️"]
               [:span {:style {:color (:temi colors)}} "💡"]
               [:span {:style {:color (:sequenze colors)}} "🔗"]
               [:span {:style {:color (:timeline colors)}} "📅"]]
    "network" [:div {:style {:font-size "3rem" :text-align "center"}} "🕸️"]
    "note" [:div {:style {:font-size "3rem" :text-align "center"}} "📝"]
    "search" [:div {:style {:font-size "3rem" :text-align "center"}} "🔍"]
    "radial" [:div {:style {:font-size "3rem" :text-align "center"}} "🎯"]
    "pdf" [:div {:style {:font-size "3rem" :text-align "center"}} "📄"]
    "start" [:div {:style {:font-size "3rem" :text-align "center"}} "✨"]
    nil))

(defn tutorial-modal []
  (let [colors (:colors @settings/settings)
        step @tutorial-step
        slides (get-tutorial-slides)
        slide (nth slides step)
        total (count slides)
        last-step? (= step (dec total))
        first-step? (= step 0)
        _ @i18n/current-lang] ; subscribe to language changes
    [:div {:style {:position "fixed"
                   :top 0 :left 0 :right 0 :bottom 0
                   :background "rgba(0,0,0,0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 2000}}
     [:div {:style {:background (:sidebar colors)
                    :border-radius "12px"
                    :padding "32px"
                    :width "550px"
                    :max-height "500px"
                    :display "flex"
                    :flex-direction "column"
                    :box-shadow "0 20px 60px rgba(0,0,0,0.5)"}}

      ;; Icon
      [:div {:style {:margin-bottom "20px"}}
       [tutorial-icon (:icon slide) colors]]

      ;; Title
      [:h2 {:style {:color (:text colors)
                    :font-size "1.6rem"
                    :text-align "center"
                    :margin "0 0 20px 0"}}
       (:title slide)]

      ;; Text content
      [:div {:style {:color (:text colors)
                     :font-size "1rem"
                     :line-height "1.7"
                     :white-space "pre-line"
                     :flex 1
                     :overflow-y "auto"
                     :padding "0 10px"}}
       (:text slide)]

      ;; Progress dots
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :gap "8px"
                     :margin "24px 0 20px 0"}}
       (for [i (range total)]
         ^{:key i}
         [:div {:style {:width "10px"
                        :height "10px"
                        :border-radius "50%"
                        :background (if (= i step)
                                      (:accent colors)
                                      (:border colors))
                        :cursor "pointer"
                        :transition "background 0.2s"}
                :on-click #(reset! tutorial-step i)}])]

      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "space-between"
                     :align-items "center"}}
       ;; Skip button (left)
       [:button {:style {:background "transparent"
                         :color (:text-muted colors)
                         :border "none"
                         :padding "8px 16px"
                         :cursor "pointer"
                         :font-size "0.9rem"
                         :opacity (if last-step? 0 1)
                         :pointer-events (if last-step? "none" "auto")}
                 :on-click (fn []
                             (settings/complete-tutorial!)
                             (reset! tutorial-open? false)
                             (reset! tutorial-step 0))}
        (t :skip)]

       ;; Navigation buttons (right)
       [:div {:style {:display "flex" :gap "12px"}}
        ;; Back button
        (when-not first-step?
          [:button {:style {:background "transparent"
                            :color (:text colors)
                            :border (str "1px solid " (:border colors))
                            :padding "10px 20px"
                            :border-radius "6px"
                            :cursor "pointer"
                            :font-size "0.9rem"}
                    :on-click #(swap! tutorial-step dec)}
           (t :back)])

        ;; Next / Finish button
        [:button {:style {:background (:accent colors)
                          :color "white"
                          :border "none"
                          :padding "10px 24px"
                          :border-radius "6px"
                          :cursor "pointer"
                          :font-size "0.9rem"
                          :font-weight "500"}
                  :on-click (fn []
                              (if last-step?
                                (do
                                  (settings/complete-tutorial!)
                                  (reset! tutorial-open? false)
                                  (reset! tutorial-step 0))
                                (swap! tutorial-step inc)))}
         (if last-step? (t :start-writing) (t :next))]]]]]))

;; =============================================================================
;; Save Status Indicator
;; =============================================================================

(defn save-status-indicator []
  (let [status @model/save-status
        _ @i18n/current-lang] ; subscribe to language changes
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
         :modified (t :modified)
         :saving (t :saving)
         :saved (t :saved)
         "")])))

;; =============================================================================
;; Export Dropdown
;; =============================================================================

(defn export-dropdown []
  (let [colors (:colors @settings/settings)
        _ @i18n/current-lang] ; subscribe to language changes
    [:<>
     ;; Click-outside overlay
     (when @export-dropdown-open?
       [:div {:style {:position "fixed"
                      :top 0 :left 0 :right 0 :bottom 0
                      :z-index 99}
              :on-click #(reset! export-dropdown-open? false)}])

     [:div {:style {:position "relative" :z-index 150}}
      ;; Export button
      [:button
       {:style {:background "transparent"
                :color (:text-muted colors)
                :border (str "1px solid " (:border colors))
                :padding "6px 12px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "0.85rem"
                :display "flex"
                :align-items "center"
                :gap "4px"}
        :on-click #(swap! export-dropdown-open? not)}
       (t :export)
       [:span {:style {:font-size "0.6rem"}} "▼"]]

      ;; Dropdown menu
      (when @export-dropdown-open?
        [:div {:style {:position "absolute"
                       :top "100%"
                       :right 0
                       :margin-top "4px"
                       :background (:sidebar colors)
                       :border (str "1px solid " (:border colors))
                       :border-radius "4px"
                       :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                       :min-width "140px"
                       :z-index 200}}
         [:button {:style {:display "block"
                           :width "100%"
                           :text-align "left"
                           :background "transparent"
                           :border "none"
                           :color (:text colors)
                           :padding "10px 14px"
                           :cursor "pointer"
                           :font-size "0.85rem"}
                   :on-click (fn []
                               (model/export-md!)
                               (reset! export-dropdown-open? false))}
          (t :export-md)]
         [:div {:style {:height "1px"
                        :background (:border colors)}}]
         [:button {:style {:display "block"
                           :width "100%"
                           :text-align "left"
                           :background "transparent"
                           :border "none"
                           :color (:text colors)
                           :padding "10px 14px"
                           :cursor "pointer"
                           :font-size "0.85rem"}
                   :on-click (fn []
                               (export-pdf/export-pdf!)
                               (reset! export-dropdown-open? false))}
          (t :export-pdf)]])]]))

;; =============================================================================
;; Header
;; =============================================================================

(defonce file-input-ref (r/atom nil))

(defn header []
  (let [colors (:colors @settings/settings)
        current-theme (:theme @settings/settings)
        use-texture (= current-theme :tessuto)]
    [:div.header {:style {:background-color (if use-texture "#ddd0b8" (:sidebar colors))
                          :background-image (when use-texture "url('/images/logo_tramando.png')")
                          :background-size "auto 320%"
                          :background-position "left top"
                          :background-repeat "no-repeat"
                          :border-bottom (str "1px solid " (:border colors))
                          :position "relative"
                          :z-index 10}}
     ;; Logo area - only this opens splash
     [:div {:style {:width "200px"
                    :position "relative"
                    :z-index 1
                    :cursor (when use-texture "pointer")}
            :on-click (when use-texture #(reset! show-splash? true))}]
     ;; Project title
     [:div {:style {:flex 1
                    :display "flex"
                    :align-items "center"
                    :padding-left "20px"}}
      [:span {:style {:color (:text colors)
                      :font-size "1.1rem"
                      :font-weight "500"
                      :cursor "pointer"
                      :position "relative"
                      :z-index 1}
              :title "Modifica informazioni progetto"
              :on-click #(reset! metadata-open? true)}
       (model/get-title)]]
     [:div {:style {:position "relative" :z-index 1}}
      [save-status-indicator]]
     [:div {:style {:display "flex" :gap "8px" :align-items "center" :margin-left "12px" :position "relative" :z-index 1}}
      ;; Hidden file input for loading
      [:input {:type "file"
               :accept ".trmd,.md,.txt"
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
        :title (t :help-carica)
        :on-click #(when @file-input-ref (.click @file-input-ref))}
       (t :load)]
      ;; Save button
      [:button
       {:style {:background (:accent colors)
                :color "white"
                :border "none"
                :padding "6px 12px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "0.85rem"}
        :title (t :help-salva)
        :on-click #(model/save-file!)}
       (t :save)]
      ;; Export dropdown
      [export-dropdown]
      ;; Project info button
      [:button
       {:style {:background "transparent"
                :color (:text-muted colors)
                :border (str "1px solid " (:border colors))
                :padding "6px 10px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "0.9rem"}
        :title (t :help-metadata)
        :on-click #(reset! metadata-open? true)}
       "📄"]
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
      ;; Annotation counter
      (let [ann-count (annotations/count-annotations)]
        (when (pos? ann-count)
          [:span {:style {:color (:text-muted colors)
                          :font-size "0.85rem"
                          :display "flex"
                          :align-items "center"
                          :gap "4px"}
                  :title "Annotazioni nel progetto"}
           [:span "📝"]
           [:span (str ann-count)]]))
      ;; View toggle button (Mappa/Editor)
      [:button
       {:style {:background (if (= @view-mode :radial) (:accent colors) "transparent")
                :color (if (= @view-mode :radial) "white" (:text-muted colors))
                :border (str "1px solid " (if (= @view-mode :radial) (:accent colors) (:border colors)))
                :padding "6px 12px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "0.85rem"}
        :on-click #(swap! view-mode (fn [m] (if (= m :editor) :radial :editor)))}
       (if (= @view-mode :radial) (t :editor) (t :map))]
      ;; Settings button
      [:button
       {:style {:background "transparent"
                :color (:text-muted colors)
                :border (str "1px solid " (:border colors))
                :padding "6px 10px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "1rem"}
        :title (t :help-settings)
        :on-click #(reset! settings/settings-open? true)}
       "⚙"]]]))

;; =============================================================================
;; Editor Panel
;; =============================================================================

(defn editor-panel []
  (let [chunk (model/get-selected-chunk)
        colors (:colors @settings/settings)
        current-theme (:theme @settings/settings)
        use-texture (= current-theme :tessuto)
        _ @i18n/current-lang] ; subscribe to language changes
    [:div.editor-panel {:style {:background (:background colors)
                                :position "relative"}}
     ;; Very subtle texture for visual continuity
     (when use-texture
       [:div {:style {:position "absolute"
                      :top 0 :left 0 :right 0 :bottom 0
                      :background-image "url('/images/logo_tramando.png')"
                      :background-size "cover"
                      :background-position "center"
                      :opacity 0.04
                      :pointer-events "none"}}])
     (if chunk
       [:div {:style {:position "relative" :z-index 1 :display "flex" :flex-direction "column" :height "100%"}}
        [:div.editor-header
         [editor/id-input]
         [editor/summary-input]
         [editor/aspects-manager]
         [editor/parent-selector]
         [editor/delete-button]]
        ;; Markup toggle above editor
        [:div {:style {:display "flex" :justify-content "flex-end" :padding "8px 20px 8px 0"}}
         [editor/markup-toggle]]
        [editor/tab-bar]
        [editor/tab-content]]
       [:div {:style {:display "flex"
                      :align-items "center"
                      :justify-content "center"
                      :height "100%"
                      :color (:text-muted colors)
                      :position "relative"
                      :z-index 1}}
        (t :select-chunk)])]))

;; =============================================================================
;; Keyboard Shortcuts
;; =============================================================================

(defn- handle-keydown [e]
  (let [key (str/lower-case (.-key e))
        ctrl-or-cmd (or (.-ctrlKey e) (.-metaKey e))
        shift (.-shiftKey e)]
    (cond
      ;; Focus filter: Ctrl+Shift+F or Cmd+Shift+F
      (and ctrl-or-cmd shift (= key "f"))
      (do
        (.preventDefault e)
        (outline/focus-filter!))

      ;; Editor search: Ctrl+F or Cmd+F (without shift)
      (and ctrl-or-cmd (not shift) (= key "f"))
      (do
        (.preventDefault e)
        (editor/show-editor-search!))

      ;; Replace: Ctrl+H or Cmd+H
      (and ctrl-or-cmd (= key "h"))
      (do
        (.preventDefault e)
        (if (:visible @editor/editor-search-state)
          (editor/toggle-replace-visible!)
          (editor/show-editor-search-with-replace!)))

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

      ;; Escape closes settings, export dropdown, editor search, and global filter
      (= key "escape")
      (do
        (when @settings/settings-open?
          (reset! settings/settings-open? false))
        (when @export-dropdown-open?
          (reset! export-dropdown-open? false))
        (when (:visible @editor/editor-search-state)
          (editor/hide-editor-search!))
        ;; Clear global outline filter
        (outline/clear-filter!)))))

;; =============================================================================
;; Splash Screen
;; =============================================================================

(defn- start-app! [action]
  "Start the app with the given action"
  (case action
    :continue (model/restore-autosave!)
    :new (model/init-sample-data!)
    :open nil) ; file will be loaded via file input
  (reset! show-splash? false)
  ;; Show tutorial on first launch
  (when-not (settings/tutorial-completed?)
    (open-tutorial!)))

(defn splash-screen []
  (r/create-class
   {:component-did-mount
    (fn [_]
      ;; Trigger fade-in animation
      (js/setTimeout #(reset! splash-fade-in? true) 50))

    :reagent-render
    (fn []
      (let [colors (:colors @settings/settings)
            current-theme (:theme @settings/settings)
            has-autosave (model/has-autosave?)
            use-texture (= current-theme :tessuto)
            _ @i18n/current-lang] ; subscribe to language changes
        [:div {:style {:position "fixed"
                       :top 0 :left 0 :right 0 :bottom 0
                       :background (:background colors)
                       :background-image (when use-texture "url('/images/logo_tramando.png')")
                       :background-size "cover"
                       :background-position "center"
                       :display "flex"
                       :flex-direction "column"
                       :align-items "center"
                       :justify-content "center"
                       :z-index 2000}}
         ;; Overlay to soften the background
         [:div {:style {:position "absolute"
                        :top 0 :left 0 :right 0 :bottom 0
                        :background (str (:background colors) "e8")}}]
         ;; Full-screen logo background (aligned top-left to keep "Tramando" text visible)
         [:div {:style {:position "absolute"
                        :top 0 :left 0 :right 0 :bottom 0
                        :background-image "url('/images/logo_tramando.png')"
                        :background-size "cover"
                        :background-position "top left"
                        :opacity (if @splash-fade-in? 1 0)
                        :transition "opacity 0.8s ease-out"}}]
         ;; Content container with fade-in
         [:div {:style {:position "relative"
                        :z-index 1
                        :text-align "center"
                        :opacity (if @splash-fade-in? 1 0)
                        :transform (if @splash-fade-in? "translateY(0)" "translateY(20px)")
                        :transition "opacity 0.5s ease-out 0.3s, transform 0.5s ease-out 0.3s"
                        :background (str (:background colors) "cc")
                        :padding "40px 60px"
                        :border-radius "16px"
                        :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}}
          ;; Title
          [:h1 {:style {:color (:accent colors)
                        :font-size "3.5rem"
                        :margin "0 0 8px 0"
                        :font-weight "300"
                        :letter-spacing "0.15em"}}
           (t :app-name)]
          ;; Subtitle
          [:p {:style {:color (:text-muted colors)
                       :font-size "1.2rem"
                       :margin "0 0 40px 0"
                       :font-style "italic"}}
           (t :tagline)]
          ;; Buttons
          [:div {:style {:display "flex"
                         :flex-direction "column"
                         :gap "12px"
                         :min-width "280px"}}
           ;; Continue button (only if autosave exists)
           (when has-autosave
             [:button {:style {:background (:accent colors)
                               :color "white"
                               :border "none"
                               :padding "14px 28px"
                               :border-radius "8px"
                               :font-size "1rem"
                               :cursor "pointer"
                               :transition "transform 0.2s, box-shadow 0.2s"
                               :box-shadow "0 4px 12px rgba(0,0,0,0.15)"}
                       :on-mouse-over #(set! (.. % -target -style -transform) "scale(1.02)")
                       :on-mouse-out #(set! (.. % -target -style -transform) "scale(1)")
                       :on-click #(start-app! :continue)}
              (t :continue-work)])
           ;; New project button
           [:button {:style {:background (if has-autosave "transparent" (:accent colors))
                             :color (if has-autosave (:text colors) "white")
                             :border (str "2px solid " (if has-autosave (:border colors) (:accent colors)))
                             :padding "14px 28px"
                             :border-radius "8px"
                             :font-size "1rem"
                             :cursor "pointer"
                             :transition "transform 0.2s, box-shadow 0.2s"
                             :box-shadow (when-not has-autosave "0 4px 12px rgba(0,0,0,0.15)")}
                     :on-mouse-over #(set! (.. % -target -style -transform) "scale(1.02)")
                     :on-mouse-out #(set! (.. % -target -style -transform) "scale(1)")
                     :on-click #(start-app! :new)}
            (t :new-project)]
           ;; Open file button
           [:button {:style {:background "transparent"
                             :color (:text-muted colors)
                             :border (str "1px solid " (:border colors))
                             :padding "12px 24px"
                             :border-radius "8px"
                             :font-size "0.9rem"
                             :cursor "pointer"
                             :transition "transform 0.2s"}
                     :on-mouse-over #(set! (.. % -target -style -transform) "scale(1.02)")
                     :on-mouse-out #(set! (.. % -target -style -transform) "scale(1)")
                     :on-click #(when @splash-file-input-ref
                                  (.click @splash-file-input-ref))}
            (t :open-file)]
           ;; Hidden file input
           [:input {:type "file"
                    :accept ".trmd,.md,.txt"
                    :style {:display "none"}
                    :ref #(reset! splash-file-input-ref %)
                    :on-change (fn [e]
                                 (when-let [file (-> e .-target .-files (aget 0))]
                                   (let [reader (js/FileReader.)]
                                     (set! (.-onload reader)
                                           (fn [evt]
                                             (let [content (-> evt .-target .-result)]
                                               (model/load-file-content! content (.-name file))
                                               (reset! show-splash? false)
                                               ;; Show tutorial on first launch
                                               (when-not (settings/tutorial-completed?)
                                                 (open-tutorial!)))))
                                     (.readAsText reader file))))}]]]]))}))

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
      (let [colors (:colors @settings/settings)
            current-view @view-mode]
        [:div#app {:style {:background (:background colors)
                           :color (:text colors)}}
         [header]
         [:div.main-container
          [outline/outline-panel]
          (case current-view
            :radial [radial/radial-view]
            [editor-panel])]
         ;; Annotation context menu (for right-click on selection)
         [editor/annotation-context-menu]
         ;; Toast notification
         [editor/toast-component]
         ;; Settings Modal
         (when @settings/settings-open?
           [settings-modal])
         ;; Metadata Modal
         (when @metadata-open?
           [metadata-modal])
         ;; Tutorial Modal
         (when @tutorial-open?
           [tutorial-modal])]))}))

;; =============================================================================
;; App Root
;; =============================================================================

(defn app []
  (if @show-splash?
    [splash-screen]
    [main-layout]))
