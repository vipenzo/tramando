(ns tramando.help
  "Contextual help tooltips for the UI"
  (:require [tramando.i18n :as i18n :refer [t]]))

;; =============================================================================
;; Help Texts - Legacy map for backwards compatibility
;; Now all texts come from i18n translations
;; =============================================================================

(def texts
  "Deprecated: use i18n translations directly.
   This map is kept for backward compatibility with components that access it directly."
  {;; Sidebar sections
   :struttura :help-struttura
   :personaggi :help-personaggi
   :luoghi :help-luoghi
   :temi :help-temi
   :sequenze :help-sequenze
   :timeline :help-timeline

   ;; Editor fields
   :id :help-id
   :summary :help-summary
   :add-aspect :help-add-aspect
   :parent :help-parent

   ;; Editor tabs
   :tab-modifica :help-tab-modifica
   :tab-usato-da :help-tab-usato-da
   :tab-figli :help-tab-figli
   :tab-lettura :help-tab-lettura

   ;; Header/Toolbar
   :settings :help-settings
   :metadata :help-metadata
   :carica :help-carica
   :salva :help-salva
   :esporta :help-esporta

   ;; Annotations
   :annotazioni :help-annotazioni

   ;; Radial map
   :mappa-radiale :help-mappa-radiale})

(defn- get-help-text
  "Get translated help text for a key"
  [key-or-text]
  (if (keyword? key-or-text)
    ;; First check if it's a mapped key, then translate
    (let [mapped-key (get texts key-or-text)]
      (if mapped-key
        (t mapped-key)
        (t (keyword (str "help-" (name key-or-text))))))
    key-or-text))

;; =============================================================================
;; Tooltip Component
;; =============================================================================

(defn tooltip
  "A help tooltip component.
   Usage: [tooltip :key] or [tooltip \"Custom text\"]
   Can also wrap content: [tooltip :key [:span \"Label\"]]"
  ([key-or-text]
   (tooltip key-or-text nil))
  ([key-or-text content]
   (let [text (get-help-text key-or-text)]
     [:span.with-help
      content
      [:span.help-icon "?"]
      [:span.help-tooltip text]])))

(defn help-icon
  "Just the help icon with tooltip, no wrapper for custom layouts.
   Usage: [help-icon :key] or [help-icon :key {:below? true :left? true :right? true}]"
  ([key-or-text]
   (help-icon key-or-text {}))
  ([key-or-text {:keys [below? left? right?] :or {below? false left? false right? false}}]
   (let [text (get-help-text key-or-text)
         classes (str "help-tooltip"
                      (when below? " below")
                      (when left? " left")
                      (when right? " right"))]
     [:span.with-help {:style {:display "inline-flex"}}
      [:span.help-icon "?"]
      [:span {:class classes} text]])))
