(ns tramando.views
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.editor :as editor]
            [tramando.outline :as outline]
            [tramando.settings :as settings]
            [tramando.radial :as radial]
            [tramando.export-pdf :as export-pdf]
            [tramando.export-docx :as export-docx]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]
            [tramando.ai-panel :as ai-panel]
            [tramando.context-menu :as context-menu]
            [tramando.ai.ui :as ai-ui]
            [tramando.chunk-selector :as selector]
            [tramando.versioning :as versioning]
            [tramando.platform :as platform]
            [tramando.auth :as auth]
            [tramando.api :as api]
            [tramando.server-ui :as server-ui]
            [tramando.store.remote :as remote-store]
            [tramando.store.protocol :as store]
            [tramando.annotations :as annotations]))

;; =============================================================================
;; App Mode State (for webapp routing)
;; =============================================================================

;; :local - working with local files
;; :login - showing login (unused now, login is in splash)
;; :projects - showing server projects list
;; :editor-remote - editing a server project
(defonce app-mode (r/atom nil))

;; =============================================================================
;; View Mode State
;; =============================================================================

(defonce view-mode (r/atom :editor)) ;; :editor or :radial

;; =============================================================================
;; Export Dropdown State
;; =============================================================================

(defonce export-dropdown-open? (r/atom false))
(defonce import-dropdown-open? (r/atom false))
(defonce import-file-input-ref (r/atom nil))

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
(defonce show-api-key? (r/atom false))

;; =============================================================================
;; Admin Panel State
;; =============================================================================

(defonce show-admin-users? (r/atom false))
(defonce show-collaborators? (r/atom false))

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
;; Settings Modal
;; =============================================================================

(defn settings-modal []
  (let [colors (:colors @settings/settings)
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
           ;; Use mousedown to avoid closing when text selection drag ends outside
           :on-mouse-down #(when (= (.-target %) (.-currentTarget %))
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

      ;; Projects Section
      [:div {:style {:margin-bottom "20px"
                     :padding-top "12px"
                     :border-top (str "1px solid " (settings/get-color :border))}}
       [:label {:style {:color (settings/get-color :text)
                        :font-size "0.9rem"
                        :font-weight "600"
                        :display "block"
                        :margin-bottom "12px"}}
        (t :settings-projects)]

       ;; Default folder label
       [:div {:style {:margin-bottom "8px"}}
        [:label {:style {:color (settings/get-color :text-muted)
                         :font-size "0.8rem"
                         :display "flex"
                         :align-items "center"
                         :gap "6px"
                         :margin-bottom "4px"}}
         (t :settings-default-folder)
         [help/help-icon :settings-folder-tooltip]]]

       ;; Folder path + Browse button
       [:div {:style {:display "flex" :gap "8px" :margin-bottom "12px"}}
        [:input {:type "text"
                 :value (or (settings/get-default-folder) "")
                 :placeholder (t :settings-no-folder)
                 :read-only true
                 :style {:flex 1
                         :background (settings/get-color :editor-bg)
                         :color (if (empty? (settings/get-default-folder))
                                  (settings/get-color :text-muted)
                                  (settings/get-color :text))
                         :border (str "1px solid " (settings/get-color :border))
                         :border-radius "4px"
                         :padding "8px 12px"
                         :font-size "0.85rem"}}]
        (if (platform/tauri?)
          [:button {:style {:background "transparent"
                            :color (settings/get-color :text)
                            :border (str "1px solid " (settings/get-color :border))
                            :padding "8px 16px"
                            :border-radius "4px"
                            :cursor "pointer"
                            :font-size "0.85rem"
                            :white-space "nowrap"}
                    :on-click (fn []
                                ;; Use Tauri dialog plugin to pick folder
                                (when-let [dialog (.-dialog (.-__TAURI__ js/window))]
                                  (-> (.open dialog #js {:directory true
                                                         :multiple false
                                                         :title (t :settings-default-folder)})
                                      (.then (fn [path]
                                               (when path
                                                 (settings/set-default-folder! path))))
                                      (.catch (fn [err]
                                                (js/console.error "Dialog error:" err))))))}
           (t :settings-browse)]
          ;; Webapp mode: folder picker not available
          [:span {:style {:color (settings/get-color :text-muted)
                          :font-size "0.85rem"
                          :font-style "italic"}}
           (t :not-available-in-webapp)])
        ;; Clear button (only shown if folder is set, Tauri only)
        (when (and (platform/tauri?) (not (empty? (settings/get-default-folder))))
          [:button {:style {:background "transparent"
                            :color (settings/get-color :text-muted)
                            :border (str "1px solid " (settings/get-color :border))
                            :padding "8px 12px"
                            :border-radius "4px"
                            :cursor "pointer"
                            :font-size "0.85rem"}
                    :on-click #(settings/set-default-folder! "")}
           "×"])]

       ;; Always use folder checkbox
       [:div {:style {:margin-bottom "8px"}}
        [:label {:style {:display "flex"
                         :align-items "center"
                         :gap "8px"
                         :cursor (if (empty? (settings/get-default-folder)) "not-allowed" "pointer")
                         :color (if (empty? (settings/get-default-folder))
                                  (settings/get-color :text-muted)
                                  (settings/get-color :text))
                         :opacity (if (empty? (settings/get-default-folder)) 0.5 1)}}
         [:input {:type "checkbox"
                  :checked (settings/get-projects-setting :always-use-folder)
                  :disabled (empty? (settings/get-default-folder))
                  :style {:cursor (if (empty? (settings/get-default-folder)) "not-allowed" "pointer")}
                  :on-change #(settings/set-always-use-folder! (-> % .-target .-checked))}]
         (t :settings-always-use-folder)]]]

      ;; AI Assistant Section
      (let [ai-settings (get @settings/settings :ai)
            ai-enabled (:enabled ai-settings)
            provider (:provider ai-settings)
            ollama-status @settings/ollama-status]
        [:div {:style {:margin-bottom "20px"
                       :padding-top "12px"
                       :border-top (str "1px solid " (settings/get-color :border))}}
         [:label {:style {:color (settings/get-color :text)
                          :font-size "0.9rem"
                          :font-weight "600"
                          :display "block"
                          :margin-bottom "12px"}}
          (t :ai-settings)]

         ;; Enable checkbox
         [:div {:style {:margin-bottom "12px"}}
          [:label {:style {:display "flex"
                           :align-items "center"
                           :gap "8px"
                           :cursor "pointer"
                           :color (settings/get-color :text)}}
           [:input {:type "checkbox"
                    :checked ai-enabled
                    :style {:cursor "pointer"}
                    :on-change #(settings/set-ai-setting! :enabled (-> % .-target .-checked))}]
           (t :ai-enabled)]]

         ;; Provider selection (only when enabled)
         (when ai-enabled
           [:<>
            ;; Provider dropdown
            [:div {:style {:margin-bottom "12px"}}
             [:label {:style {:color (settings/get-color :text-muted)
                              :font-size "0.8rem"
                              :display "flex"
                              :align-items "center"
                              :gap "6px"
                              :margin-bottom "4px"}}
              (t :ai-provider)
              [help/help-icon :ai-help-provider]]
             [:select {:value (name provider)
                       :style {:width "100%"
                               :background (settings/get-color :editor-bg)
                               :color (settings/get-color :text)
                               :border (str "1px solid " (settings/get-color :border))
                               :border-radius "4px"
                               :padding "8px 12px"
                               :font-size "0.9rem"
                               :cursor "pointer"}
                       :on-change #(settings/set-ai-setting! :provider (keyword (-> % .-target .-value)))}
              [:option {:value "anthropic"} (t :ai-provider-anthropic)]
              [:option {:value "openai"} (t :ai-provider-openai)]
              [:option {:value "groq"} (t :ai-provider-groq)]
              [:option {:value "ollama"} (t :ai-provider-ollama)]]]

            ;; Anthropic API Key
            (when (= provider :anthropic)
              [:<>
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "flex"
                                 :align-items "center"
                                 :gap "6px"
                                 :margin-bottom "4px"}}
                 (t :ai-api-key)
                 [help/help-icon :ai-help-api-key]]
                [:div {:style {:display "flex" :gap "8px"}}
                 [:input {:type (if @show-api-key? "text" "password")
                          :value (:anthropic-key ai-settings)
                          :placeholder (t :ai-api-key-placeholder)
                          :style {:flex 1
                                  :background (settings/get-color :editor-bg)
                                  :color (settings/get-color :text)
                                  :border (str "1px solid " (settings/get-color :border))
                                  :border-radius "4px"
                                  :padding "8px 12px"
                                  :font-size "0.9rem"}
                          :on-change #(settings/set-ai-setting! :anthropic-key (-> % .-target .-value))}]
                 [:button {:style {:background "transparent"
                                   :color (settings/get-color :text-muted)
                                   :border (str "1px solid " (settings/get-color :border))
                                   :padding "8px 12px"
                                   :border-radius "4px"
                                   :cursor "pointer"
                                   :font-size "0.8rem"}
                           :on-click #(swap! show-api-key? not)}
                  (if @show-api-key? (t :ai-hide-key) (t :ai-show-key))]]]
               ;; Model selection for Anthropic
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "block"
                                 :margin-bottom "4px"}}
                 (t :ai-model)]
                [:select {:value (:model ai-settings)
                          :style {:width "100%"
                                  :background (settings/get-color :editor-bg)
                                  :color (settings/get-color :text)
                                  :border (str "1px solid " (settings/get-color :border))
                                  :border-radius "4px"
                                  :padding "8px 12px"
                                  :font-size "0.9rem"
                                  :cursor "pointer"}
                          :on-change #(settings/set-ai-setting! :model (-> % .-target .-value))}
                 [:option {:value "claude-sonnet-4-20250514"} "Claude Sonnet 4"]
                 [:option {:value "claude-opus-4-20250514"} "Claude Opus 4"]
                 [:option {:value "claude-3-5-haiku-latest"} "Claude 3.5 Haiku"]]]])

            ;; OpenAI API Key
            (when (= provider :openai)
              [:<>
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "flex"
                                 :align-items "center"
                                 :gap "6px"
                                 :margin-bottom "4px"}}
                 (t :ai-api-key)
                 [help/help-icon :ai-help-api-key]]
                [:div {:style {:display "flex" :gap "8px"}}
                 [:input {:type (if @show-api-key? "text" "password")
                          :value (:openai-key ai-settings)
                          :placeholder (t :ai-api-key-placeholder)
                          :style {:flex 1
                                  :background (settings/get-color :editor-bg)
                                  :color (settings/get-color :text)
                                  :border (str "1px solid " (settings/get-color :border))
                                  :border-radius "4px"
                                  :padding "8px 12px"
                                  :font-size "0.9rem"}
                          :on-change #(settings/set-ai-setting! :openai-key (-> % .-target .-value))}]
                 [:button {:style {:background "transparent"
                                   :color (settings/get-color :text-muted)
                                   :border (str "1px solid " (settings/get-color :border))
                                   :padding "8px 12px"
                                   :border-radius "4px"
                                   :cursor "pointer"
                                   :font-size "0.8rem"}
                           :on-click #(swap! show-api-key? not)}
                  (if @show-api-key? (t :ai-hide-key) (t :ai-show-key))]]]
               ;; Model selection for OpenAI
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "block"
                                 :margin-bottom "4px"}}
                 (t :ai-model)]
                [:select {:value (:model ai-settings)
                          :style {:width "100%"
                                  :background (settings/get-color :editor-bg)
                                  :color (settings/get-color :text)
                                  :border (str "1px solid " (settings/get-color :border))
                                  :border-radius "4px"
                                  :padding "8px 12px"
                                  :font-size "0.9rem"
                                  :cursor "pointer"}
                          :on-change #(settings/set-ai-setting! :model (-> % .-target .-value))}
                 [:option {:value "gpt-4o"} "GPT-4o"]
                 [:option {:value "gpt-4o-mini"} "GPT-4o Mini"]
                 [:option {:value "gpt-4-turbo"} "GPT-4 Turbo"]]]])

            ;; Groq API Key
            (when (= provider :groq)
              [:<>
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "flex"
                                 :align-items "center"
                                 :gap "6px"
                                 :margin-bottom "4px"}}
                 (t :ai-api-key)
                 [help/help-icon :ai-groq-key-tooltip]]
                [:div {:style {:display "flex" :gap "8px"}}
                 [:input {:type (if @show-api-key? "text" "password")
                          :value (:groq-key ai-settings)
                          :placeholder (t :ai-api-key-placeholder)
                          :style {:flex 1
                                  :background (settings/get-color :editor-bg)
                                  :color (settings/get-color :text)
                                  :border (str "1px solid " (settings/get-color :border))
                                  :border-radius "4px"
                                  :padding "8px 12px"
                                  :font-size "0.9rem"}
                          :on-change #(settings/set-ai-setting! :groq-key (-> % .-target .-value))}]
                 [:button {:style {:background "transparent"
                                   :color (settings/get-color :text-muted)
                                   :border (str "1px solid " (settings/get-color :border))
                                   :padding "8px 12px"
                                   :border-radius "4px"
                                   :cursor "pointer"
                                   :font-size "0.8rem"}
                           :on-click #(swap! show-api-key? not)}
                  (if @show-api-key? (t :ai-hide-key) (t :ai-show-key))]]]
               ;; Model selection for Groq
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "block"
                                 :margin-bottom "4px"}}
                 (t :ai-model)]
                [:input {:type "text"
                         :value (:groq-model ai-settings)
                         :placeholder "llama-3.3-70b-versatile"
                         :style {:width "100%"
                                 :background (settings/get-color :editor-bg)
                                 :color (settings/get-color :text)
                                 :border (str "1px solid " (settings/get-color :border))
                                 :border-radius "4px"
                                 :padding "8px 12px"
                                 :font-size "0.9rem"
                                 :box-sizing "border-box"}
                         :on-change #(settings/set-ai-setting! :groq-model (-> % .-target .-value))}]
                [:div {:style {:font-size "0.75rem"
                               :color (settings/get-color :text-muted)
                               :margin-top "4px"}}
                 "Es: llama-3.3-70b-versatile, llama-3.1-8b-instant"]]])

            ;; Ollama settings
            (when (= provider :ollama)
              [:<>
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "flex"
                                 :align-items "center"
                                 :gap "6px"
                                 :margin-bottom "4px"}}
                 (t :ai-ollama-url)
                 [help/help-icon :ai-help-ollama]]
                [:div {:style {:display "flex" :gap "8px"}}
                 [:input {:type "text"
                          :value (:ollama-url ai-settings)
                          :style {:flex 1
                                  :background (settings/get-color :editor-bg)
                                  :color (settings/get-color :text)
                                  :border (str "1px solid " (settings/get-color :border))
                                  :border-radius "4px"
                                  :padding "8px 12px"
                                  :font-size "0.9rem"}
                          :on-change #(settings/set-ai-setting! :ollama-url (-> % .-target .-value))}]
                 [:button {:style {:background "transparent"
                                   :color (settings/get-color :text-muted)
                                   :border (str "1px solid " (settings/get-color :border))
                                   :padding "8px 12px"
                                   :border-radius "4px"
                                   :cursor "pointer"
                                   :font-size "0.8rem"
                                   :white-space "nowrap"}
                           :on-click #(settings/check-ollama-connection!)}
                  (if (:checking ollama-status)
                    (t :ai-ollama-checking)
                    (t :ai-ollama-check))]]]
               ;; Connection status
               (when (some? (:connected ollama-status))
                 [:div {:style {:margin-bottom "12px"
                                :padding "8px 12px"
                                :background (settings/get-color :accent-muted)
                                :border-radius "4px"
                                :font-size "0.85rem"
                                :color (settings/get-color :text)}}
                  (if (:connected ollama-status)
                    [:<>
                     [:span {:style {:color (settings/get-color :accent)}} "✓ "]
                     (t :ai-ollama-connected)
                     " - "
                     (t :ai-ollama-models-found (count (:models ollama-status)))]
                    [:<>
                     [:span {:style {:color (settings/get-color :danger)}} "✗ "]
                     (t :ai-ollama-not-connected)])])
               ;; Model selection for Ollama
               [:div {:style {:margin-bottom "12px"}}
                [:label {:style {:color (settings/get-color :text-muted)
                                 :font-size "0.8rem"
                                 :display "block"
                                 :margin-bottom "4px"}}
                 (t :ai-ollama-model)]
                (if (and (:connected ollama-status) (seq (:models ollama-status)))
                  ;; Show dropdown if connected and models available
                  [:select {:value (:ollama-model ai-settings)
                            :style {:width "100%"
                                    :background (settings/get-color :editor-bg)
                                    :color (settings/get-color :text)
                                    :border (str "1px solid " (settings/get-color :border))
                                    :border-radius "4px"
                                    :padding "8px 12px"
                                    :font-size "0.9rem"
                                    :cursor "pointer"}
                            :on-change #(settings/set-ai-setting! :ollama-model (-> % .-target .-value))}
                   (for [model (:models ollama-status)]
                     ^{:key model}
                     [:option {:value model} model])]
                  ;; Show text input if not connected
                  [:input {:type "text"
                           :value (:ollama-model ai-settings)
                           :style {:width "100%"
                                   :background (settings/get-color :editor-bg)
                                   :color (settings/get-color :text)
                                   :border (str "1px solid " (settings/get-color :border))
                                   :border-radius "4px"
                                   :padding "8px 12px"
                                   :font-size "0.9rem"}
                           :on-change #(settings/set-ai-setting! :ollama-model (-> % .-target .-value))}])]])

            ;; Auto-send checkbox (shown for all providers when enabled)
            [:div {:style {:margin-top "16px"
                           :padding-top "12px"
                           :border-top (str "1px solid " (settings/get-color :border))}}
             [:label {:style {:display "flex"
                              :align-items "center"
                              :gap "8px"
                              :cursor "pointer"
                              :color (settings/get-color :text)}}
              [:input {:type "checkbox"
                       :checked (:auto-send ai-settings)
                       :style {:cursor "pointer"}
                       :on-change #(settings/set-ai-setting! :auto-send (-> % .-target .-checked))}]
              (t :ai-auto-send)]]])])

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
           ;; Use mousedown to avoid closing when text selection drag ends outside
           :on-mouse-down #(when (= (.-target %) (.-currentTarget %))
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
                                    :modified (settings/get-color :accent)
                                    :saving (settings/get-color :accent)
                                    :saved (settings/get-color :accent)
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

(defn import-dropdown
  "Import dropdown for webapp/Tauri - imports .md files into current project"
  []
  (let [colors (:colors @settings/settings)
        _ @i18n/current-lang] ; subscribe to language changes
    [:<>
     ;; Click-outside overlay
     (when @import-dropdown-open?
       [:div {:style {:position "fixed"
                      :top 0 :left 0 :right 0 :bottom 0
                      :z-index 99}
              :on-click #(reset! import-dropdown-open? false)}])

     [:div {:style {:position "relative" :z-index 150}}
      ;; Hidden file input
      [:input {:type "file"
               :accept ".md,.txt"
               :style {:display "none"}
               :ref #(reset! import-file-input-ref %)
               :on-change (fn [e]
                            (when-let [file (-> e .-target .-files (aget 0))]
                              (let [reader (js/FileReader.)]
                                (set! (.-onload reader)
                                      (fn [evt]
                                        (let [content (-> evt .-target .-result)]
                                          ;; Import as new chunks appended to current project
                                          (model/import-md-content! content))))
                                (.readAsText reader file)))
                            ;; Reset input
                            (set! (-> e .-target .-value) "")
                            (reset! import-dropdown-open? false))}]

      ;; Import button - icon style
      [:button
       {:style {:background "transparent"
                :color (:text-muted colors)
                :border "none"
                :padding "6px 10px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "18px"
                :line-height "1"
                :min-width "32px"
                :height "32px"
                :display "flex"
                :align-items "center"
                :justify-content "center"
                :gap "2px"}
        :title (t :import)
        :on-click #(swap! import-dropdown-open? not)}
       "⬇"
       [:span {:style {:font-size "10px" :margin-left "2px"}} "▾"]]

      ;; Dropdown menu
      (when @import-dropdown-open?
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
         ;; Import .md
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
                               (when @import-file-input-ref
                                 (.click @import-file-input-ref)))}
          (t :import-md)]])]]))

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
      ;; Export button - icon style
      [:button
       {:style {:background "transparent"
                :color (:text-muted colors)
                :border "none"
                :padding "6px 10px"
                :border-radius "4px"
                :cursor "pointer"
                :font-size "18px"
                :line-height "1"
                :min-width "32px"
                :height "32px"
                :display "flex"
                :align-items "center"
                :justify-content "center"
                :gap "2px"}
        :title (t :export)
        :on-click #(swap! export-dropdown-open? not)}
       "⬆"
       [:span {:style {:font-size "10px" :margin-left "2px"}} "▾"]]

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
         ;; Export .trmd
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
                               (model/export-trmd!)
                               (reset! export-dropdown-open? false))}
          (t :export-trmd)]
         [:div {:style {:height "1px"
                        :background (:border colors)}}]
         ;; Export .md
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
          (t :export-pdf)]
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
                               (export-docx/export-docx!)
                               (reset! export-dropdown-open? false))}
          (t :export-docx)]])]]))

;; =============================================================================
;; Header
;; =============================================================================

(defonce file-input-ref (r/atom nil))

;; Header button styles - VSCode-like uniform icons
(defn- header-icon-style
  "Base style for icon-only header buttons (18px icons)"
  [colors & {:keys [active? large?]}]
  {:background (if active? (:accent-muted colors) "transparent")
   :color (if active? (:accent colors) (:text-muted colors))
   :border "none"
   :padding "6px 10px"
   :border-radius "4px"
   :cursor "pointer"
   :font-size (if large? "24px" "18px")
   :line-height "1"
   :min-width "32px"
   :height "32px"
   :display "flex"
   :align-items "center"
   :justify-content "center"})

(defn- header-separator
  "Vertical separator between button groups"
  [colors]
  [:div {:style {:width "1px"
                 :height "16px"
                 :background (:border colors)
                 :margin "0 8px"}}])

(defn- autosave-indicator
  "Progressive autosave indicator - 4 bars that light up toward save"
  [colors]
  (let [progress @model/autosave-progress
        bar-style (fn [level]
                    {:width "3px"
                     :height "12px"
                     :border-radius "1px"
                     :transition "background 0.2s ease"
                     :background (if (>= progress level)
                                   (case level
                                     1 "rgba(74, 159, 142, 0.4)"
                                     2 "rgba(74, 159, 142, 0.6)"
                                     3 "rgba(74, 159, 142, 0.8)"
                                     4 (:accent colors))
                                   (:border colors))})]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "1px"
                   :margin-left "8px"}
           :title (cond
                    (zero? progress) (t :all-saved)
                    :else (t :autosave-in-progress))}
     [:div {:style (bar-style 1)}]
     [:div {:style (bar-style 2)}]
     [:div {:style (bar-style 3)}]
     [:div {:style (bar-style 4)}]]))

(defn header []
  (let [colors (:colors @settings/settings)
        server-mode? (= @app-mode :editor-remote)
        file-dirty? @versioning/dirty?]
    [:div.header {:style {:display "flex"
                          :align-items "center"
                          :justify-content "space-between"
                          :padding "0 16px"
                          :height "40px"
                          :background (:sidebar colors)
                          :border-bottom (str "1px solid " (:border colors))}}
     ;; Left section: Logo + Project name
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "16px"}}
      ;; Logo
      [:div {:style {:display "flex"
                     :align-items "center"
                     :gap "8px"
                     :cursor "pointer"}
             :on-click #(reset! show-splash? true)}
       [:img {:src "/images/icon_32x32.png"
              :style {:width "20px"
                      :height "20px"}}]
       [:span {:style {:font-family "'Georgia', serif"
                       :font-style "italic"
                       :font-weight "400"
                       :font-size "16px"
                       :letter-spacing "0.5px"
                       :color (:text colors)}}
        [:span {:style {:color (:logo-accent colors)}} "T"]
        "ramando"]]
      ;; Project name
      [:span {:style {:color (:text-muted colors)
                      :font-size "13px"
                      :cursor "pointer"}
              :title (t :help-metadata)
              :on-click #(reset! metadata-open? true)}
       (model/get-title)]
      ;; Dirty indicator
      (when (and (not server-mode?) @versioning/dirty?)
        [:span {:style {:color (:accent colors)
                        :font-size "16px"
                        :line-height "1"}
                :title (t :unsaved-changes)}
         "•"])]
     ;; Right section: Actions - organized in logical groups
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "4px"}}
      ;; Hidden file input for loading
      (when-not server-mode?
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
                                (set! (-> e .-target .-value) "")))}])

      ;; === GROUP 1: File operations + autosave indicator ===
      (when-not server-mode?
        [:<>
         ;; Open file
         [:button
          {:style (header-icon-style colors)
           :title (t :help-carica)
           :on-click #(model/open-file!)}
          "📂"]
         ;; Save (to current file or Save As if new)
         [:button
          {:style (merge (header-icon-style colors)
                         (when-not file-dirty?
                           {:opacity "0.5"
                            :cursor "default"}))
           :title (t :help-salva)
           :disabled (not file-dirty?)
           :on-click #(when file-dirty? (model/save-file!))}
          "💾"]
         [autosave-indicator colors]])

      ;; === GROUP 2: Versions + Export ===
      (when-not server-mode?
        [:<>
         [header-separator colors]
         ;; Version dropdown
         [versioning/version-dropdown
          {:on-save-version #(versioning/open-save-version-dialog!)
           :on-list-versions #(versioning/open-version-list-dialog!)
           :on-restore-backup #(versioning/open-restore-backup-dialog! model/reload-file!)}]
         ;; Export dropdown
         [export-dropdown]
         ;; Import dropdown
         [import-dropdown]])

      ;; Export for server mode (no import - projects page handles that)
      (when server-mode?
        [export-dropdown])

      ;; === GROUP 3: View tools ===
      [header-separator colors]
      ;; View toggle (Mappa)
      [:button
       {:style (header-icon-style colors :active? (= @view-mode :radial))
        :title (if (= @view-mode :radial) (t :editor) (t :map))
        :on-click #(swap! view-mode (fn [m] (if (= m :editor) :radial :editor)))}
       "❃"]
      ;; AI Assistant button
      [ai-panel/toolbar-button]
      ;; Project info button
      [:button
       {:style (header-icon-style colors)
        :title (t :help-metadata)
        :on-click #(reset! metadata-open? true)}
       "≡"]

      ;; === GROUP 4: Settings ===
      [header-separator colors]
      ;; Theme toggle button
      [:button
       {:style (header-icon-style colors)
        :title (if (settings/is-light-theme?) (t :theme-dark) (t :theme-light))
        :on-click settings/toggle-theme!}
       (if (settings/is-light-theme?) "☽" "☀")]
      ;; Settings button (larger icon)
      [:button
       {:style (header-icon-style colors :large? true)
        :title (t :help-settings)
        :on-click #(reset! settings/settings-open? true)}
       "⚙"]]]))

;; =============================================================================
;; Status Bar
;; =============================================================================

(defn- count-words
  "Count words in a string"
  [text]
  (if (str/blank? text)
    0
    (count (re-seq #"\S+" text))))

(defn- format-number
  "Format number with thousand separators"
  [n]
  (let [s (str n)]
    (loop [result ""
           remaining s]
      (if (<= (count remaining) 3)
        (str remaining result)
        (recur (str "." (subs remaining (- (count remaining) 3)) result)
               (subs remaining 0 (- (count remaining) 3)))))))

(defn count-words-recursive
  "Count words in a chunk and all its descendants"
  [chunk-id]
  (let [chunk (model/get-chunk chunk-id)
        own-words (if chunk (count-words (:content chunk)) 0)
        children (model/get-children chunk-id)
        children-words (reduce + 0 (map #(count-words-recursive (:id %)) children))]
    (+ own-words children-words)))

(defn- status-bar-autosave-indicator
  "Compact autosave indicator with 4 bars for status bar"
  []
  (let [colors (:colors @settings/settings)
        progress @model/autosave-progress
        bar-style (fn [level]
                    {:width "2px"
                     :height "10px"
                     :border-radius "1px"
                     :transition "background 0.2s ease"
                     :background (if (>= progress level)
                                   (case level
                                     1 "rgba(74, 159, 142, 0.4)"
                                     2 "rgba(74, 159, 142, 0.6)"
                                     3 "rgba(74, 159, 142, 0.8)"
                                     4 (:accent colors))
                                   (:border colors))})]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "1px"}}
     [:div {:style (bar-style 1)}]
     [:div {:style (bar-style 2)}]
     [:div {:style (bar-style 3)}]
     [:div {:style (bar-style 4)}]]))

(defn status-bar []
  (let [chunk (model/get-selected-chunk)
        save-status @model/save-status
        autosave-progress @model/autosave-progress
        file-dirty? @versioning/dirty?
        show-markup? @annotations/show-markup?
        own-words (when chunk (count-words (:content chunk)))
        children (when chunk (model/get-children (:id chunk)))
        has-children? (and children (seq children))
        total-words (when (and chunk has-children?)
                      (count-words-recursive (:id chunk)))
        breadcrumb (when chunk (model/get-chunk-path chunk))
        colors (:colors @settings/settings)
        ;; Autosave status: pending while modifying, done after backup
        backup-pending? (or (= save-status :modified) (> autosave-progress 0))
        backup-saving? (= save-status :saving)
        ;; Remote mode: user-role is set (not nil) when using RemoteStore
        remote-mode? (some? (model/get-user-role))]
    [:div.status-bar
     ;; Left side: breadcrumb and word count
     [:div.status-item
      (when breadcrumb
        [:<>
         [:span.status-breadcrumb {:title breadcrumb} breadcrumb]
         [:span.status-separator "•"]])
      (when own-words
        [:span (if has-children?
                 (str (format-number total-words) " (" (format-number own-words) ") " (t :ai-context-words))
                 (str (format-number own-words) " " (t :ai-context-words)))])]
     ;; Right side: markup toggle, autosave status, file status
     [:div.status-item
      ;; Markup toggle
      [:div.toggle-switch {:on-click (fn []
                                       (swap! annotations/show-markup? not)
                                       (when (:visible @editor/editor-search-state)
                                         (editor/update-search!)))}
       [:span "Markup"]
       [:div {:class (str "toggle" (when show-markup? " active"))}]]
      [:span.status-separator "•"]
      ;; Autosave status (localStorage backup)
      [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
       [status-bar-autosave-indicator]
       [:span {:style {:color (if backup-pending? (:text-muted colors) (:accent colors))
                       :font-size "inherit"}}
        (cond
          backup-saving? (t :saving)
          backup-pending? (t :backup-pending)
          :else (t :backup-done))]]
      ;; File status (disk) - only show in local mode
      (when-not remote-mode?
        [:<>
         [:span.status-separator "•"]
         [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
          [:span {:style {:color (if file-dirty? (:danger colors) (:accent colors))
                          :font-size "inherit"}}
           (if file-dirty?
             (t :file-dirty)
             (t :file-clean))]
          (when-not file-dirty?
            [:span {:style {:color (:accent colors)}} "✓"])]])]]))

;; =============================================================================
;; Editor Panel
;; =============================================================================

(defn editor-panel []
  (let [chunk (model/get-selected-chunk)
        colors (:colors @settings/settings)
        current-theme (:theme @settings/settings)
        use-texture (or (get colors :background-texture) (= current-theme :tessuto))
        _ @i18n/current-lang] ; subscribe to language changes
    [:div.editor-panel {:style {:background (:background colors)
                                :position "relative"}}
     ;; Very subtle texture for visual continuity
     (when use-texture
       [:div {:style {:position "absolute"
                      :top 0 :left 0 :right 0 :bottom 0
                      :background-image "url('/images/logo_tramando_transparent.png')"
                      :background-size "cover"
                      :background-position "center"
                      :opacity 0.04
                      :pointer-events "none"}}])
     (if chunk
       [:div {:style {:position "relative" :z-index 1 :display "flex" :flex-direction "column" :height "100%"}}
        [:div.editor-header
         [editor/compact-header]]
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

      ;; Context menu for selection/annotation: Ctrl+M or Cmd+M
      (and ctrl-or-cmd (= key "m"))
      (do
        (.preventDefault e)
        (editor/open-context-menu-for-selection!))

      ;; Escape closes settings, export dropdown, editor search, and global filter
      (= key "escape")
      (do
        (when @settings/settings-open?
          (reset! settings/settings-open? false))
        (when @export-dropdown-open?
          (reset! export-dropdown-open? false))
        (when (:visible @editor/editor-search-state)
          (editor/hide-editor-search!))
        ;; Also close context menu
        (context-menu/hide-menu!)
        ;; Clear global outline filter
        (outline/clear-filter!)))))

;; =============================================================================
;; Splash Screen
;; =============================================================================

(defn- start-app! [action]
  "Start the app with the given action (local mode)"
  (case action
    :continue (model/restore-autosave!)
    :new (model/init-sample-data!)
    :open nil) ; file will be loaded via file input
  (reset! show-splash? false)
  (reset! app-mode :local)
  ;; Show tutorial on first launch
  (when-not (settings/tutorial-completed?)
    (open-tutorial!)))

;; Login form state for splash screen (webapp only)
(defonce splash-login-username (r/atom ""))
(defonce splash-login-password (r/atom ""))
(defonce splash-login-error (r/atom nil))
(defonce splash-login-loading? (r/atom false))
(defonce splash-register-mode? (r/atom false))
;; Auto-detect server URL from current page location (use same origin + path for subpath deployments)
(defonce splash-server-url (r/atom (let [loc js/window.location
                                          protocol (.-protocol loc)
                                          hostname (.-hostname loc)
                                          port (.-port loc)
                                          pathname (.-pathname loc)
                                          ;; Extract base path from pathname (e.g., /tramando from /tramando/)
                                          base-path (-> pathname
                                                        (str/replace #"/index\.html$" "")
                                                        (str/replace #"/$" ""))
                                          base-path (if (= base-path "/") "" base-path)]
                                      (str protocol "//" hostname
                                           (when (and port (not= port ""))
                                             (str ":" port))
                                           base-path))))

(defn- splash-local-panel
  "Left panel: local mode options"
  [colors has-autosave]
  [:div {:style {:flex "1"
                 :min-width "260px"
                 :max-width "300px"
                 :padding "20px"
                 :border (str "1px solid " (:border colors))
                 :border-radius "12px"
                 :background (str (:background colors) "80")
                 :text-align "left"}}
   ;; Header
   [:div {:style {:margin-bottom "20px"}}
    [:h3 {:style {:margin "0 0 6px 0"
                  :color (:text colors)
                  :font-weight "500"
                  :font-size "1.1rem"}}
     (t :work-local)]
    [:p {:style {:margin 0
                 :font-size "0.8rem"
                 :color (:text-muted colors)}}
     (t :work-local-desc)]]
   ;; Buttons
   [:div {:style {:display "flex"
                  :flex-direction "column"
                  :gap "10px"}}
    (when has-autosave
      [:button {:style {:background (:accent colors)
                        :color "white"
                        :border "none"
                        :padding "12px 20px"
                        :border-radius "6px"
                        :font-size "0.95rem"
                        :cursor "pointer"
                        :transition "transform 0.2s"}
                :on-mouse-over #(set! (.. % -target -style -transform) "scale(1.02)")
                :on-mouse-out #(set! (.. % -target -style -transform) "scale(1)")
                :on-click #(start-app! :continue)}
       (t :continue-work)])
    [:button {:style {:background (if has-autosave "transparent" (:accent colors))
                      :color (if has-autosave (:text colors) "white")
                      :border (str "1px solid " (if has-autosave (:border colors) (:accent colors)))
                      :padding "12px 20px"
                      :border-radius "6px"
                      :font-size "0.95rem"
                      :cursor "pointer"
                      :transition "transform 0.2s"}
              :on-mouse-over #(set! (.. % -target -style -transform) "scale(1.02)")
              :on-mouse-out #(set! (.. % -target -style -transform) "scale(1)")
              :on-click #(start-app! :new)}
     (t :new-project)]
    [:button {:style {:background "transparent"
                      :color (:text-muted colors)
                      :border (str "1px solid " (:border colors))
                      :padding "10px 20px"
                      :border-radius "6px"
                      :font-size "0.85rem"
                      :cursor "pointer"
                      :transition "transform 0.2s"}
              :on-mouse-over #(set! (.. % -target -style -transform) "scale(1.02)")
              :on-mouse-out #(set! (.. % -target -style -transform) "scale(1)")
              :on-click #(when @splash-file-input-ref (.click @splash-file-input-ref))}
     (t :open-file)]
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
                                        (reset! app-mode :local)
                                        (when-not (settings/tutorial-completed?)
                                          (open-tutorial!)))))
                              (.readAsText reader file))))}]]])

(defn- splash-server-panel
  "Right panel: server login (webapp only)"
  [colors]
  [:div {:style {:flex "1"
                 :min-width "260px"
                 :max-width "300px"
                 :padding "20px"
                 :border (str "1px solid " (:border colors))
                 :border-radius "12px"
                 :background (str (:background colors) "80")
                 :text-align "left"}}
   ;; Header
   [:div {:style {:margin-bottom "20px"}}
    [:h3 {:style {:margin "0 0 6px 0"
                  :color (:text colors)
                  :font-weight "500"
                  :font-size "1.1rem"}}
     (t :login-server)]
    [:p {:style {:margin 0
                 :font-size "0.8rem"
                 :color (:text-muted colors)}}
     (t :login-server-desc)]]
   ;; Error
   (when @splash-login-error
     [:div {:style {:background (settings/get-color :danger)
                    :color "white"
                    :padding "8px 12px"
                    :border-radius "4px"
                    :margin-bottom "15px"
                    :font-size "0.85rem"}}
      @splash-login-error])
   ;; Form
   [:form {:on-submit (fn [e]
                        (.preventDefault e)
                        (reset! splash-login-error nil)
                        (cond
                          (< (count @splash-login-username) 3)
                          (reset! splash-login-error (t :username-min-chars))
                          (< (count @splash-login-password) 6)
                          (reset! splash-login-error (t :password-min-chars))
                          :else
                          (do
                            (reset! splash-login-loading? true)
                            ;; Set server URL before login
                            (api/set-server-url! @splash-server-url)
                            (-> (if @splash-register-mode?
                                  (auth/register! @splash-login-username @splash-login-password)
                                  (auth/login! @splash-login-username @splash-login-password))
                                (.then (fn [result]
                                         (reset! splash-login-loading? false)
                                         (if (:ok result)
                                           (do
                                             (reset! show-splash? false)
                                             (reset! app-mode :projects))
                                           (reset! splash-login-error (:error result)))))))))}
    ;; Server URL field
    [:input {:type "text"
             :placeholder "Server URL"
             :value @splash-server-url
             :on-change #(reset! splash-server-url (-> % .-target .-value))
             :disabled @splash-login-loading?
             :style {:width "100%"
                     :padding "8px 12px"
                     :margin-bottom "10px"
                     :border (str "1px solid " (:border colors))
                     :border-radius "6px"
                     :background (:background colors)
                     :color (:text-muted colors)
                     :font-size "0.85rem"
                     :box-sizing "border-box"}}]
    [:input {:type "text"
             :placeholder "Username"
             :value @splash-login-username
             :on-change #(reset! splash-login-username (-> % .-target .-value))
             :disabled @splash-login-loading?
             :style {:width "100%"
                     :padding "10px 12px"
                     :margin-bottom "10px"
                     :border (str "1px solid " (:border colors))
                     :border-radius "6px"
                     :background (:background colors)
                     :color (:text colors)
                     :font-size "0.95rem"
                     :box-sizing "border-box"}}]
    [:input {:type "password"
             :placeholder "Password"
             :value @splash-login-password
             :on-change #(reset! splash-login-password (-> % .-target .-value))
             :disabled @splash-login-loading?
             :style {:width "100%"
                     :padding "10px 12px"
                     :margin-bottom "15px"
                     :border (str "1px solid " (:border colors))
                     :border-radius "6px"
                     :background (:background colors)
                     :color (:text colors)
                     :font-size "0.95rem"
                     :box-sizing "border-box"}}]
    [:button {:type "submit"
              :disabled @splash-login-loading?
              :style {:width "100%"
                      :padding "12px"
                      :background (:accent colors)
                      :color "white"
                      :border "none"
                      :border-radius "6px"
                      :font-size "0.95rem"
                      :cursor (if @splash-login-loading? "wait" "pointer")
                      :opacity (if @splash-login-loading? 0.7 1)}}
     (cond
       @splash-login-loading? "..."
       @splash-register-mode? (t :register)
       :else (t :login))]]
   ;; Toggle
   [:div {:style {:text-align "center" :margin-top "12px"}}
    [:span {:style {:color (:text-muted colors) :font-size "0.85rem"}}
     (if @splash-register-mode? (str (t :have-account) " ") (str (t :no-account) " "))]
    [:a {:href "#"
         :on-click (fn [e]
                     (.preventDefault e)
                     (swap! splash-register-mode? not)
                     (reset! splash-login-error nil))
         :style {:color (:accent colors)
                 :text-decoration "none"
                 :font-size "0.85rem"}}
     (if @splash-register-mode? (t :login) (t :register))]]])

(defn splash-screen []
  (r/create-class
   {:component-did-mount
    (fn [_]
      (js/setTimeout #(reset! splash-fade-in? true) 50))

    :reagent-render
    (fn []
      (let [colors (:colors @settings/settings)
            current-theme (:theme @settings/settings)
            has-autosave (model/has-autosave?)
            use-texture (or (get colors :background-texture) (= current-theme :tessuto))
            is-webapp? (not (platform/tauri?))
            _ @i18n/current-lang
            current-lang @i18n/current-lang
            lang-flags {:it "🇮🇹" :en "🇬🇧"}]
        [:div {:style {:position "fixed"
                       :top 0 :left 0 :right 0 :bottom 0
                       :background (:background colors)
                       :background-image (when use-texture "url('/images/logo_tramando_transparent.png')")
                       :background-size "cover"
                       :background-position "center"
                       :display "flex"
                       :flex-direction "column"
                       :align-items "center"
                       :justify-content "center"
                       :z-index 2000}}
         ;; Overlay
         [:div {:style {:position "absolute"
                        :top 0 :left 0 :right 0 :bottom 0
                        :background (str (:background colors) "e8")}}]
         ;; Logo background
         [:div {:style {:position "absolute"
                        :top 0 :left 0 :right 0 :bottom 0
                        :background-image "url('/images/logo_tramando_transparent.png')"
                        :background-size "cover"
                        :background-position "top left"
                        :opacity (if @splash-fade-in? 1 0)
                        :transition "opacity 0.8s ease-out"}}]
         ;; Content
         [:div {:style {:position "relative"
                        :z-index 1
                        :text-align "center"
                        :opacity (if @splash-fade-in? 1 0)
                        :transform (if @splash-fade-in? "translateY(0)" "translateY(20px)")
                        :transition "opacity 0.5s ease-out 0.3s, transform 0.5s ease-out 0.3s"
                        :background (str (:background colors) "cc")
                        :padding "40px"
                        :border-radius "16px"
                        :box-shadow "0 8px 32px rgba(0,0,0,0.3)"
                        :max-width (if is-webapp? "700px" "400px")}}
          ;; Title
          [:h1 {:style {:color (:accent colors)
                        :font-size "3rem"
                        :margin "0 0 8px 0"
                        :font-weight "300"
                        :letter-spacing "0.15em"}}
           (t :app-name)]
          [:p {:style {:color (:text-muted colors)
                       :font-size "1.1rem"
                       :margin "0 0 30px 0"
                       :font-style "italic"}}
           (t :tagline)]
          ;; Two-column layout
          [:div {:style {:display "flex"
                         :gap "30px"
                         :justify-content "center"
                         :flex-wrap "wrap"}}
           [splash-local-panel colors has-autosave]
           (when is-webapp?
             [splash-server-panel colors])]]
         ;; Language selector (bottom right) with flags
         [:div {:style {:position "absolute"
                        :bottom "20px"
                        :right "20px"
                        :z-index 10
                        :display "flex"
                        :gap "4px"
                        :background (str (:background colors) "e0")
                        :padding "6px 10px"
                        :border-radius "20px"
                        :border (str "1px solid " (:border colors))
                        :opacity (if @splash-fade-in? 1 0)
                        :transition "opacity 0.5s ease-out 0.5s"}}
          (doall
           (for [[lang-key _] i18n/available-languages]
             ^{:key lang-key}
             [:span {:style {:cursor "pointer"
                             :font-size "1.3rem"
                             :padding "2px 6px"
                             :border-radius "4px"
                             :opacity (if (= lang-key current-lang) 1 0.5)
                             :transform (if (= lang-key current-lang) "scale(1.1)" "scale(1)")
                             :transition "all 0.2s"}
                     :title (get i18n/available-languages lang-key)
                     :on-click #(settings/set-language! lang-key)}
              (get lang-flags lang-key)]))]]))}))

;; =============================================================================
;; Main Layout
;; =============================================================================

(defn main-layout []
  (r/create-class
   {:component-did-mount
    (fn [_]
      (.addEventListener js/document "keydown" handle-keydown)
      ;; Set up the context menu template action handler
      (context-menu/set-template-action-handler!
       (fn [template-id chunk selected-text aspect-id]
         (ai-panel/handle-template-action! template-id chunk selected-text aspect-id)))
      ;; Set up window focus listener for conflict detection
      (versioning/setup-focus-listener! #(versioning/show-file-changed-dialog!)))

    :component-will-unmount
    (fn [_]
      (.removeEventListener js/document "keydown" handle-keydown))

    :reagent-render
    (fn []
      (let [colors (:colors @settings/settings)
            current-view @view-mode
            ai-visible? (and (ai-panel/panel-visible?)
                             (settings/ai-configured?))
            ai-height (when ai-visible? (ai-panel/get-panel-height))]
        [:div#app {:style {:background (:background colors)
                           :color (:text colors)
                           :display "flex"
                           :flex-direction "column"
                           ;; Use flex: 1 and height: 100% to work both as root and nested
                           :flex 1
                           :height "100%"
                           :min-height 0}  ;; Needed for flex children to respect overflow
                   ;; Prevent browser context menu everywhere in the app
                   :on-context-menu #(.preventDefault %)}
         [header]
         ;; Main content area - three column layout
         [:div.main-container {:style {:flex 1
                                       :display "flex"
                                       :overflow "hidden"}}
          [outline/outline-panel]
          (case current-view
            :radial [radial/radial-view]
            [editor-panel])
          ;; Right panel (Annotations)
          [outline/annotations-panel]]
         ;; Status bar
         [status-bar]
         ;; AI Assistant Panel
         [ai-panel/ai-panel]
         ;; Context Menu (for right-click on selection and annotations)
         [context-menu/context-menu]
         ;; AI Aspect Update Modal
         [ai-ui/aspect-update-modal]
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
           [tutorial-modal])
         ;; Chunk Selector Modal (global)
         [selector/selector-modal]
         ;; Versioning dialogs (conflict, file changed, versions, etc.)
         [versioning/dialogs-container
          {:on-save-as model/save-file-as!
           :on-reload model/reload-file!
           :on-reload-from-version (fn [content filename]
                                     (model/load-version-copy! content filename))}]]))}))

;; =============================================================================
;; Server Project Helpers
;; =============================================================================

(defn- back-to-projects!
  "Return to projects list, clearing server mode state"
  []
  ;; Cleanup RemoteStore state
  (remote-store/cleanup!)
  ;; Navigate to projects
  (reset! app-mode :projects))

(defn- server-project-header
  "Header for server project mode"
  []
  [:div {:style {:display "flex"
                 :justify-content "space-between"
                 :align-items "center"
                 :padding "8px 15px"
                 :background (settings/get-color :sidebar)
                 :border-bottom (str "1px solid " (settings/get-color :border))}}
   [:button {:on-click back-to-projects!
             :style {:background "transparent"
                     :border "none"
                     :color (settings/get-color :text-muted)
                     :cursor "pointer"
                     :font-size "0.9rem"
                     :padding "6px 10px"
                     :border-radius "4px"}
             :on-mouse-over #(set! (.. % -currentTarget -style -color) (settings/get-color :text))
             :on-mouse-out #(set! (.. % -currentTarget -style -color) (settings/get-color :text-muted))}
    "<- Progetti"]
   [:span {:style {:color (settings/get-color :text) :font-weight "500"}}
    (remote-store/get-project-name)]
   [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
    [:button {:on-click #(reset! show-collaborators? true)
              :style {:background "transparent"
                      :border (str "1px solid " (settings/get-color :border))
                      :color (settings/get-color :text-muted)
                      :cursor "pointer"
                      :font-size "0.85rem"
                      :padding "5px 12px"
                      :border-radius "4px"
                      :display "flex"
                      :align-items "center"
                      :gap "5px"}
              :on-mouse-over #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :text))
              :on-mouse-out #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :border))}
     "👥 Collaboratori"]
    [server-ui/user-menu {:on-logout #(do (remote-store/cleanup!)
                                          (reset! app-mode nil)
                                          (reset! show-splash? true))
                          :on-admin-users #(reset! show-admin-users? true)}]]])

(defn- open-server-project! [project]
  ;; Initialize RemoteStore and load project
  (remote-store/init!)
  (-> (store/load-project (store/get-store) (:id project))
      (.then (fn [_result]
               (reset! app-mode :editor-remote)))
      (.catch (fn [err]
                (js/console.error "Failed to load project:" err)))))

;; =============================================================================
;; App Root
;; =============================================================================

(defn- determine-initial-mode! []
  (when-not (platform/tauri?)
    (auth/check-auth!)
    (js/setTimeout
      (fn []
        (when (auth/logged-in?)
          (reset! show-splash? false)
          (reset! app-mode :projects)))
      100)))

(defn app []
  ;; Check auth on first render
  (when (nil? @app-mode)
    (determine-initial-mode!))

  [:<>
   (cond
     ;; Splash screen
     @show-splash?
     [splash-screen]

     ;; Local mode
     (= @app-mode :local)
     [main-layout]

     ;; Projects list
     (= @app-mode :projects)
     [:div {:style {:min-height "100vh"
                    :background (settings/get-color :background)}}
      [:div {:style {:display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :padding "15px 20px"
                     :border-bottom (str "1px solid " (settings/get-color :border))}}
       [:h1 {:style {:margin 0
                     :font-size "1.5rem"
                     :font-weight "300"
                     :color (settings/get-color :text)}}
        "Tramando"]
       [server-ui/user-menu {:on-logout #(do (reset! app-mode nil)
                                               (reset! show-splash? true))
                             :on-admin-users #(reset! show-admin-users? true)}]]
      [server-ui/projects-list
       {:on-open open-server-project!
        :on-create open-server-project!}]]

     ;; Editor with server project
     (= @app-mode :editor-remote)
     [:div {:style {:display "flex"
                    :flex-direction "column"
                    :height "100vh"
                    :overflow "hidden"}}
      [server-project-header]
      ;; Wrapper that makes #app fill available space (uses flex: 1 in main-layout)
      [:div {:style {:flex 1
                     :display "flex"
                     :flex-direction "column"
                     :overflow "hidden"
                     :min-height 0}}
       [main-layout]]]

     ;; Fallback
     :else
     [main-layout])

   ;; Admin users panel overlay (shown when show-admin-users? is true)
   (when @show-admin-users?
     [server-ui/admin-users-panel {:on-close #(reset! show-admin-users? false)}])

   ;; Collaborators modal (shown when show-collaborators? is true)
   (when @show-collaborators?
     [server-ui/collaborators-modal {:project-id (remote-store/get-project-id)
                                     :on-close #(reset! show-collaborators? false)}])])
