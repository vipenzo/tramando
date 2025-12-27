(ns tramando.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [tramando.model :as model]
            [tramando.views :as views]
            [tramando.settings :as settings]))

(defonce root (atom nil))

(defn ^:dev/after-load reload! []
  (when @root
    (.render @root (r/as-element [views/app]))))

(defn init []
  (js/console.log "Tramando starting...")

  ;; Initialize settings
  (settings/init!)

  ;; Don't auto-restore or init data here - splash screen handles it

  ;; Mount React app
  (let [container (js/document.getElementById "app")
        react-root (rdom/create-root container)]
    (reset! root react-root)
    (.render react-root (r/as-element [views/app])))

  (js/console.log "Tramando ready!"))
