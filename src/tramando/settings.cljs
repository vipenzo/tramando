(ns tramando.settings
  (:require [reagent.core :as r]
            [clojure.edn :as edn]))

;; =============================================================================
;; Default Themes
;; =============================================================================

(def default-themes
  {:dark {:background "#1a1a2e"
          :sidebar "#16213e"
          :text "#eeeeee"
          :text-muted "#888888"
          :accent "#e94560"
          :structure "#4a90d9"
          :personaggi "#e94560"
          :luoghi "#50c878"
          :temi "#ffd700"
          :sequenze "#9370db"
          :timeline "#ff6b6b"
          :editor-bg "#0f3460"
          :border "#0f3460"}

   :light {:background "#f5f5f5"
           :sidebar "#e8e8e8"
           :text "#333333"
           :text-muted "#666666"
           :accent "#d63031"
           :structure "#2980b9"
           :personaggi "#d63031"
           :luoghi "#27ae60"
           :temi "#f39c12"
           :sequenze "#8e44ad"
           :timeline "#e74c3c"
           :editor-bg "#ffffff"
           :border "#cccccc"}

   :sepia {:background "#f4ecd8"
           :sidebar "#ebe3d0"
           :text "#5c4b37"
           :text-muted "#8b7355"
           :accent "#8b4513"
           :structure "#4a6741"
           :personaggi "#8b4513"
           :luoghi "#228b22"
           :temi "#b8860b"
           :sequenze "#6b4423"
           :timeline "#cd5c5c"
           :editor-bg "#faf6eb"
           :border "#d4c4a8"}})

(def default-settings
  {:theme :dark
   :colors (:dark default-themes)
   :autosave-delay-ms 3000})

;; =============================================================================
;; Settings State
;; =============================================================================

(defonce settings (r/atom default-settings))

(defonce settings-open? (r/atom false))

;; =============================================================================
;; LocalStorage Persistence
;; =============================================================================

(def ^:private localstorage-key "tramando-settings")

(defn save-settings!
  "Save current settings to localStorage"
  []
  (let [data (pr-str @settings)]
    (.setItem js/localStorage localstorage-key data)))

(defn load-settings!
  "Load settings from localStorage"
  []
  (when-let [data (.getItem js/localStorage localstorage-key)]
    (try
      (let [loaded (edn/read-string data)]
        (reset! settings (merge default-settings loaded)))
      (catch :default e
        (js/console.warn "Failed to load settings:" e)))))

(defn has-saved-settings?
  "Check if there are saved settings in localStorage"
  []
  (some? (.getItem js/localStorage localstorage-key)))

;; =============================================================================
;; Settings Operations
;; =============================================================================

(defn get-color
  "Get a color from current settings"
  [color-key]
  (get-in @settings [:colors color-key]))

(defn set-theme!
  "Set the theme to a predefined theme"
  [theme-key]
  (when-let [theme-colors (get default-themes theme-key)]
    (swap! settings assoc
           :theme theme-key
           :colors theme-colors)))

(defn set-color!
  "Set a specific color"
  [color-key color-value]
  (swap! settings assoc-in [:colors color-key] color-value)
  ;; When manually changing colors, set theme to :custom
  (swap! settings assoc :theme :custom))

(defn set-autosave-delay!
  "Set autosave delay in milliseconds"
  [delay-ms]
  (swap! settings assoc :autosave-delay-ms delay-ms))

(defn reset-to-theme!
  "Reset colors to the currently selected theme defaults"
  []
  (let [current-theme (:theme @settings)
        theme-key (if (= current-theme :custom) :dark current-theme)]
    (set-theme! theme-key)))

(defn get-autosave-delay
  "Get current autosave delay in milliseconds"
  []
  (:autosave-delay-ms @settings))

;; =============================================================================
;; Export/Import settings.edn
;; =============================================================================

(defn export-settings-edn
  "Generate settings.edn content as string"
  []
  (let [{:keys [theme colors autosave-delay-ms]} @settings]
    (str "{:theme " theme "\n"
         " :colors {:background \"" (:background colors) "\"\n"
         "          :sidebar \"" (:sidebar colors) "\"\n"
         "          :text \"" (:text colors) "\"\n"
         "          :text-muted \"" (:text-muted colors) "\"\n"
         "          :accent \"" (:accent colors) "\"\n"
         "          :structure \"" (:structure colors) "\"\n"
         "          :personaggi \"" (:personaggi colors) "\"\n"
         "          :luoghi \"" (:luoghi colors) "\"\n"
         "          :temi \"" (:temi colors) "\"\n"
         "          :sequenze \"" (:sequenze colors) "\"\n"
         "          :timeline \"" (:timeline colors) "\"\n"
         "          :editor-bg \"" (:editor-bg colors) "\"\n"
         "          :border \"" (:border colors) "\"}\n"
         " :autosave-delay-ms " autosave-delay-ms "}\n")))

(defn download-settings!
  "Download settings.edn file"
  []
  (let [content (export-settings-edn)
        blob (js/Blob. #js [content] #js {:type "text/plain"})
        url (.createObjectURL js/URL blob)
        a (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) "settings.edn")
    (.click a)
    (.revokeObjectURL js/URL url)))

(defn import-settings!
  "Import settings from edn string"
  [edn-string]
  (try
    (let [imported (edn/read-string edn-string)]
      (reset! settings (merge default-settings imported))
      (save-settings!)
      {:ok true})
    (catch :default e
      {:error (str "Errore nel parsing: " (.-message e))})))

;; =============================================================================
;; Apply CSS Variables
;; =============================================================================

(defn apply-css-variables!
  "Apply current colors as CSS custom properties on :root"
  []
  (let [root (.-documentElement js/document)
        colors (:colors @settings)]
    (doseq [[k v] colors]
      (.setProperty (.-style root) (str "--color-" (name k)) v))))

;; Watch settings changes and apply CSS variables
(add-watch settings :css-vars
           (fn [_ _ _ _]
             (apply-css-variables!)))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initialize settings - load from localStorage if available"
  []
  (when (has-saved-settings?)
    (load-settings!))
  (apply-css-variables!))
