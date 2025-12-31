(ns tramando.ai.ui
  "UI components for AI features (aspect update modal)"
  (:require [reagent.core :as r]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]
            [tramando.ai.handlers :as handlers]))

;; =============================================================================
;; Aspect Update Confirmation Modal
;; =============================================================================

(defn aspect-update-modal
  "Modal for confirming new info to append to aspect"
  []
  (let [{:keys [showing aspect-id aspect-name new-info]} @handlers/aspect-update-state]
    (when showing
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 10000}}
       [:div {:style {:background (settings/get-color :sidebar)
                      :border (str "1px solid " (settings/get-color :border))
                      :border-radius "8px"
                      :box-shadow "0 8px 32px rgba(0,0,0,0.4)"
                      :max-width "600px"
                      :width "90%"
                      :max-height "80vh"
                      :overflow "hidden"
                      :display "flex"
                      :flex-direction "column"}}

        ;; Header
        [:div {:style {:padding "16px 20px"
                       :border-bottom (str "1px solid " (settings/get-color :border))}}
         [:h3 {:style {:margin 0
                       :font-size "1rem"
                       :color (settings/get-color :accent)}}
          (str (t :ai-add-info-to) " " aspect-name)]]

        ;; Content preview
        [:div {:style {:padding "16px 20px"
                       :flex 1
                       :overflow-y "auto"}}
         [:div {:style {:font-size "0.8rem"
                        :font-weight "600"
                        :color (settings/get-color :text-muted)
                        :margin-bottom "8px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
          (t :ai-new-info-found)]
         [:pre {:style {:background (settings/get-color :editor-bg)
                        :padding "12px"
                        :border-radius "4px"
                        :font-size "0.85rem"
                        :color (settings/get-color :text)
                        :white-space "pre-wrap"
                        :max-height "300px"
                        :overflow-y "auto"
                        :margin 0
                        :font-family "inherit"}}
          new-info]
         [:div {:style {:font-size "0.8rem"
                        :color (settings/get-color :text-muted)
                        :margin-top "12px"
                        :font-style "italic"}}
          (t :ai-info-will-append)]]

        ;; Footer
        [:div {:style {:padding "12px 20px"
                       :border-top (str "1px solid " (settings/get-color :border))
                       :display "flex"
                       :justify-content "flex-end"
                       :gap "8px"}}
         [:button {:style {:background "transparent"
                           :border (str "1px solid " (settings/get-color :border))
                           :color (settings/get-color :text)
                           :padding "8px 16px"
                           :border-radius "4px"
                           :cursor "pointer"}
                   :on-click handlers/hide-aspect-update-confirmation!}
          (t :cancel)]
         [:button {:style {:background (settings/get-color :accent)
                           :border "none"
                           :color "white"
                           :padding "8px 16px"
                           :border-radius "4px"
                           :cursor "pointer"}
                   :on-click handlers/apply-aspect-update!}
          (t :ai-append)]]]])))
