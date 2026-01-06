(ns tramando.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [tramando.model :as model]
            [tramando.views :as views]
            [tramando.settings :as settings]
            [tramando.store.local :as local-store]))

(defonce root (atom nil))

;; =============================================================================
;; Memory Monitor (for debugging)
;; =============================================================================

(defonce memory-monitor-interval (atom nil))

(defn get-memory-info
  "Get memory info if available (Chrome only)"
  []
  (when-let [mem (.-memory js/performance)]
    {:used-mb (/ (.-usedJSHeapSize mem) 1048576)
     :total-mb (/ (.-totalJSHeapSize mem) 1048576)
     :limit-mb (/ (.-jsHeapSizeLimit mem) 1048576)}))

(defn ^:export start-memory-monitor
  "Start logging memory usage every N seconds (default 5).
   Call from browser console: tramando.core.start_memory_monitor()"
  ([] (start-memory-monitor 5))
  ([interval-sec]
   (when @memory-monitor-interval
     (js/clearInterval @memory-monitor-interval))
   (let [start-time (js/Date.now)
         initial-mem (get-memory-info)]
     (js/console.log "Memory monitor started. Initial:" (clj->js initial-mem))
     (reset! memory-monitor-interval
             (js/setInterval
              (fn []
                (when-let [mem (get-memory-info)]
                  (let [elapsed-sec (/ (- (js/Date.now) start-time) 1000)
                        delta (when initial-mem
                                (- (:used-mb mem) (:used-mb initial-mem)))]
                    (js/console.log
                     (str "[" (.toFixed elapsed-sec 0) "s] "
                          "Used: " (.toFixed (:used-mb mem) 2) " MB"
                          (when delta
                            (str " (Δ " (if (pos? delta) "+" "") (.toFixed delta 2) " MB)")))))))
              (* interval-sec 1000))))))

(defn ^:export stop-memory-monitor
  "Stop the memory monitor.
   Call from browser console: tramando.core.stop_memory_monitor()"
  []
  (when @memory-monitor-interval
    (js/clearInterval @memory-monitor-interval)
    (reset! memory-monitor-interval nil)
    (js/console.log "Memory monitor stopped.")))

(defn ^:dev/after-load reload! []
  (when @root
    (.render @root (r/as-element [views/app]))))

(defn init []
  (js/console.log "Tramando starting...")

  ;; Initialize settings
  (settings/init!)

  ;; Initialize state store (LocalStore for now)
  (local-store/init!)

  ;; Don't auto-restore or init data here - splash screen handles it

  ;; Mount React app
  (let [container (js/document.getElementById "app")
        react-root (rdom/create-root container)]
    (reset! root react-root)
    (.render react-root (r/as-element [views/app])))

  (js/console.log "Tramando ready!"))
