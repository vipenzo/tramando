(ns tramando.settings
  (:require [reagent.core :as r]
            [clojure.edn :as edn]
            [tramando.i18n :as i18n]))

;; =============================================================================
;; Default Themes
;; =============================================================================

(def default-themes
  {:tessuto {:background "#e8dcc8"
             :sidebar "#d4c4a8"
             :text "#3d3225"
             :text-muted "#7a6f5d"
             :accent "#c44a4a"
             :structure "#4a90c2"
             :personaggi "#c44a4a"
             :luoghi "#4a9a6a"
             :temi "#b87333"
             :sequenze "#8a5ac2"
             :timeline "#4a90c2"
             :editor-bg "#f5f0e6"
             :border "#c4b49a"
             :ai-panel "#c9b898"
             :background-texture true}

   :dark {:background "#1a1a2e"
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
          :border "#0f3460"
          :ai-panel "#1e2a4a"}

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
           :border "#cccccc"
           :ai-panel "#d8d8d8"}

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
           :border "#d4c4a8"
           :ai-panel "#e0d5c0"}

   :ulysses {:background "#1e1e2e"
             :sidebar "#252535"
             :text "#e0e0e0"
             :text-muted "#7a7a8c"
             :accent "#7c7cff"
             :structure "#7c7cff"
             :personaggi "#ff7c7c"
             :luoghi "#7cffb4"
             :temi "#ffd97c"
             :sequenze "#c77cff"
             :timeline "#7cc4ff"
             :editor-bg "#2a2a3e"
             :border "#3a3a4e"
             :ai-panel "#2e2e42"}})

(def default-settings
  {:theme :tessuto
   :colors (:tessuto default-themes)
   :autosave-delay-ms 3000
   :language :it
   :tutorial-completed false
   :ai {:enabled false
        :provider :anthropic  ; :anthropic | :openai | :groq | :ollama
        :anthropic-key ""
        :openai-key ""
        :groq-key ""
        :ollama-url "http://localhost:11434"
        :ollama-model "llama3"
        :model "claude-sonnet-4-20250514"
        :groq-model "llama-3.3-70b-versatile"
        :auto-send false}
   :projects {:default-folder ""
              :always-use-folder false}})

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
      (let [loaded (edn/read-string data)
            ;; Deep merge for nested maps (colors, ai, projects) to preserve new defaults
            theme-key (or (:theme loaded) :tessuto)
            default-colors (get default-themes theme-key (:tessuto default-themes))
            merged (-> (merge default-settings loaded)
                       (assoc :colors (merge default-colors (:colors loaded)))
                       (assoc :ai (merge (:ai default-settings) (:ai loaded)))
                       (assoc :projects (merge (:projects default-settings) (:projects loaded))))]
        (reset! settings merged)
        ;; Sync language with i18n module
        (when-let [lang (:language @settings)]
          (i18n/set-language! lang)))
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
    (let [;; Preserve background-texture setting if it was enabled
          had-texture (or (get-in @settings [:colors :background-texture])
                          (= (:theme @settings) :tessuto))]
      (swap! settings assoc
             :theme theme-key
             :colors (assoc theme-colors :background-texture had-texture)))))

(defn set-color!
  "Set a specific color"
  [color-key color-value]
  (let [current-theme (:theme @settings)
        ;; Preserve background-texture when switching to custom theme
        has-texture (or (get-in @settings [:colors :background-texture])
                        (= current-theme :tessuto))]
    (swap! settings assoc-in [:colors color-key] color-value)
    ;; When manually changing colors, set theme to :custom but preserve texture setting
    (swap! settings assoc :theme :custom)
    (swap! settings assoc-in [:colors :background-texture] has-texture)))

(defn set-autosave-delay!
  "Set autosave delay in milliseconds"
  [delay-ms]
  (swap! settings assoc :autosave-delay-ms delay-ms))

(defn reset-to-theme!
  "Reset colors to the currently selected theme defaults"
  []
  (let [current-theme (:theme @settings)
        theme-key (if (= current-theme :custom) :tessuto current-theme)]
    (set-theme! theme-key)))

(defn get-autosave-delay
  "Get current autosave delay in milliseconds"
  []
  (:autosave-delay-ms @settings))

(defn tutorial-completed?
  "Check if tutorial has been completed"
  []
  (:tutorial-completed @settings))

(defn complete-tutorial!
  "Mark tutorial as completed"
  []
  (swap! settings assoc :tutorial-completed true)
  (save-settings!))

(defn reset-tutorial!
  "Reset tutorial so it shows again"
  []
  (swap! settings assoc :tutorial-completed false)
  (save-settings!))

(defn set-language!
  "Set the UI language"
  [lang]
  (when (contains? i18n/available-languages lang)
    (swap! settings assoc :language lang)
    (i18n/set-language! lang)))

(defn get-language
  "Get the current language"
  []
  (:language @settings))

;; =============================================================================
;; Projects Settings Operations
;; =============================================================================

(defn get-projects-setting
  "Get a specific projects setting"
  [key]
  (get-in @settings [:projects key]))

(defn set-projects-setting!
  "Set a specific projects setting"
  [key value]
  (swap! settings assoc-in [:projects key] value))

(defn get-default-folder
  "Get the default projects folder path"
  []
  (get-in @settings [:projects :default-folder]))

(defn set-default-folder!
  "Set the default projects folder path"
  [path]
  (swap! settings assoc-in [:projects :default-folder] (or path "")))

(defn always-use-folder?
  "Check if should always use default folder for open/save"
  []
  (and (not (empty? (get-default-folder)))
       (get-in @settings [:projects :always-use-folder])))

(defn set-always-use-folder!
  "Set whether to always use default folder"
  [value]
  (swap! settings assoc-in [:projects :always-use-folder] value))

;; =============================================================================
;; AI Settings Operations
;; =============================================================================

(defn get-ai-setting
  "Get a specific AI setting"
  [key]
  (get-in @settings [:ai key]))

(defn set-ai-setting!
  "Set a specific AI setting"
  [key value]
  (swap! settings assoc-in [:ai key] value))

(defn ai-enabled?
  "Check if AI assistant is enabled"
  []
  (get-in @settings [:ai :enabled]))

(defn ai-configured?
  "Check if AI assistant is properly configured based on provider"
  []
  (let [ai (:ai @settings)]
    (and (:enabled ai)
         (case (:provider ai)
           :anthropic (not (empty? (:anthropic-key ai)))
           :openai (not (empty? (:openai-key ai)))
           :groq (not (empty? (:groq-key ai)))
           :ollama (not (empty? (:ollama-url ai)))
           false))))

(defn ai-auto-send?
  "Check if AI requests should be sent automatically"
  []
  (get-in @settings [:ai :auto-send] false))

(defn get-ai-api-key
  "Get the API key for the current provider"
  []
  (let [ai (:ai @settings)]
    (case (:provider ai)
      :anthropic (:anthropic-key ai)
      :openai (:openai-key ai)
      :groq (:groq-key ai)
      :ollama nil
      nil)))

(defn get-ai-model
  "Get the model for the current provider"
  []
  (let [ai (:ai @settings)]
    (case (:provider ai)
      :anthropic (:model ai)
      :openai (:model ai)
      :groq (:groq-model ai)
      :ollama (:ollama-model ai)
      nil)))

(defonce ollama-status (r/atom {:checking false :connected nil :models []}))

(defn check-ollama-connection!
  "Check if Ollama is running and get available models"
  []
  (let [url (get-ai-setting :ollama-url)]
    (reset! ollama-status {:checking true :connected nil :models []})
    (-> (js/fetch (str url "/api/tags"))
        (.then (fn [^js response]
                 (if (.-ok response)
                   (.json response)
                   (throw (js/Error. "Not OK")))))
        (.then (fn [^js data]
                 (let [models (mapv #(.-name ^js %) (.-models data))]
                   (reset! ollama-status {:checking false :connected true :models models}))))
        (.catch (fn [_]
                  (reset! ollama-status {:checking false :connected false :models []}))))))

;; =============================================================================
;; Export/Import settings.edn
;; =============================================================================

(defn export-settings-edn
  "Generate settings.edn content as string"
  []
  (let [{:keys [theme colors autosave-delay-ms language]} @settings]
    (str "{:theme " theme "\n"
         " :language " language "\n"
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
      ;; Sync language with i18n module
      (when-let [lang (:language @settings)]
        (i18n/set-language! lang))
      (save-settings!)
      {:ok true})
    (catch :default e
      {:error (str (i18n/t :error-parsing) ": " (.-message e))})))

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
