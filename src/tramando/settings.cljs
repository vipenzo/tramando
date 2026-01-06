(ns tramando.settings
  (:require [reagent.core :as r]
            [clojure.edn :as edn]
            [tramando.i18n :as i18n]))

;; =============================================================================
;; Default Themes
;; =============================================================================

(def default-themes
  {:dark {:background "#1e1e1e"
          :sidebar "#252526"
          :text "#cccccc"
          :text-muted "#858585"
          :text-dim "#5a5a5a"
          :accent "#4a9f8e"
          :accent-hover "#5bbfab"
          :accent-muted "rgba(74, 159, 142, 0.15)"
          :logo-accent "#5b9aa9"
          :structure "#858585"
          ;; Colori aspetti per la mappa radiale
          :personaggi "#e06c75"    ; rosso/rosa
          :luoghi "#61afef"        ; blu
          :temi "#c678dd"          ; viola
          :sequenze "#e5c07b"      ; giallo/oro
          :timeline "#98c379"      ; verde
          :editor-bg "#1e1e1e"
          :tertiary "#2d2d30"
          :hover "#3c3c3c"
          :border "#3c3c3c"
          :ai-panel "#252526"
          :danger "#e57373"}

   :light {:background "#ffffff"
           :sidebar "#f5f5f5"
           :text "#333333"
           :text-muted "#666666"
           :text-dim "#999999"
           :accent "#4a9f8e"
           :accent-hover "#3d8a7a"
           :accent-muted "rgba(74, 159, 142, 0.1)"
           :logo-accent "#5b9aa9"
           :structure "#666666"
           ;; Colori aspetti per la mappa radiale
           :personaggi "#c0392b"    ; rosso scuro
           :luoghi "#2980b9"        ; blu
           :temi "#8e44ad"          ; viola
           :sequenze "#d68910"      ; oro scuro
           :timeline "#27ae60"      ; verde
           :editor-bg "#ffffff"
           :tertiary "#ebebeb"
           :hover "#e0e0e0"
           :border "#e0e0e0"
           :ai-panel "#f5f5f5"
           :danger "#e57373"}})

(def default-settings
  {:theme :dark
   :colors (:dark default-themes)
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
              :always-use-folder false}
   ;; Aspect priority thresholds (0 = show all)
   :aspect-thresholds {:personaggi 0
                       :luoghi 0
                       :temi 0
                       :sequenze 0
                       :timeline 0}})

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
            ;; Migrate old themes to dark/light
            raw-theme (or (:theme loaded) :dark)
            theme-key (case raw-theme
                        (:tessuto :sepia) :light  ; light-ish themes -> light
                        (:ulysses :dark :custom) :dark  ; dark-ish themes -> dark
                        :light :light
                        :dark)  ; default
            default-colors (get default-themes theme-key)
            merged (-> (merge default-settings loaded)
                       (assoc :theme theme-key)
                       (assoc :colors default-colors)
                       (assoc :ai (merge (:ai default-settings) (:ai loaded)))
                       (assoc :projects (merge (:projects default-settings) (:projects loaded)))
                       (assoc :aspect-thresholds (merge (:aspect-thresholds default-settings) (:aspect-thresholds loaded))))]
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
  "Set the theme to :dark or :light"
  [theme-key]
  (when-let [theme-colors (get default-themes theme-key)]
    (swap! settings assoc
           :theme theme-key
           :colors theme-colors)
    (save-settings!)))

(defn toggle-theme!
  "Toggle between dark and light themes"
  []
  (let [current-theme (:theme @settings)
        new-theme (if (= current-theme :light) :dark :light)]
    (set-theme! new-theme)))

(defn is-light-theme?
  "Check if current theme is light"
  []
  (= (:theme @settings) :light))

(defn set-autosave-delay!
  "Set autosave delay in milliseconds"
  [delay-ms]
  (swap! settings assoc :autosave-delay-ms delay-ms))

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
;; Aspect Threshold Operations
;; =============================================================================

(defn get-aspect-thresholds
  "Get all aspect thresholds"
  []
  (get @settings :aspect-thresholds))

(defn get-aspect-threshold
  "Get threshold for a specific container"
  [container-id]
  (get-in @settings [:aspect-thresholds (keyword container-id)] 0))

(defn set-aspect-threshold!
  "Set threshold for a specific container and save"
  [container-id value]
  (swap! settings assoc-in [:aspect-thresholds (keyword container-id)] value)
  (save-settings!))

(defn set-aspect-thresholds!
  "Set all thresholds at once (for sync from outline atom)"
  [thresholds]
  (swap! settings assoc :aspect-thresholds thresholds)
  (save-settings!))

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
         "          :text-dim \"" (:text-dim colors) "\"\n"
         "          :accent \"" (:accent colors) "\"\n"
         "          :accent-hover \"" (:accent-hover colors) "\"\n"
         "          :accent-muted \"" (:accent-muted colors) "\"\n"
         "          :tertiary \"" (:tertiary colors) "\"\n"
         "          :hover \"" (:hover colors) "\"\n"
         "          :editor-bg \"" (:editor-bg colors) "\"\n"
         "          :border \"" (:border colors) "\"\n"
         "          :danger \"" (:danger colors) "\"}\n"
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
