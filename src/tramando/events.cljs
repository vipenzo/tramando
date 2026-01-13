(ns tramando.events
  "Simple event system for decoupling modules.
   Used to break circular dependencies between editor, context-menu, and ai.handlers."
  (:require [reagent.core :as r]))

;; =============================================================================
;; Toast Messages
;; =============================================================================

(defonce toast-state (r/atom nil))

(defn show-toast!
  "Show a toast notification message"
  [message]
  (reset! toast-state {:message message :visible true})
  (js/setTimeout #(reset! toast-state nil) 3000))

;; =============================================================================
;; Editor Refresh
;; =============================================================================

(defonce editor-refresh-counter (r/atom 0))

(defn refresh-editor!
  "Signal that the editor should refresh its content.
   Called when chunk content changes from external sources."
  []
  (swap! editor-refresh-counter inc))

;; =============================================================================
;; Editor Text Replacement (for proper undo support)
;; =============================================================================

;; Store the dispatch function - set by editor when mounted
(defonce editor-replace-fn (atom nil))

(defn set-editor-replace-fn!
  "Register the editor's text replacement function"
  [f]
  (reset! editor-replace-fn f))

(defn replace-text-in-editor!
  "Replace text in the editor through CodeMirror (supports undo).
   Returns true if successful, false if editor not available."
  [search-text replacement-text]
  (if-let [replace-fn @editor-replace-fn]
    (replace-fn search-text replacement-text)
    false))

;; =============================================================================
;; Chunk Navigation (for clicking chunk links, expands parent hierarchy)
;; =============================================================================

;; Store the navigate function - set by outline when mounted
(defonce navigate-to-chunk-fn (atom nil))

(defn set-navigate-to-chunk-fn!
  "Register the outline's chunk navigation function"
  [f]
  (reset! navigate-to-chunk-fn f))

(defn navigate-to-chunk!
  "Navigate to a chunk: select it and expand its parent hierarchy.
   Returns true if successful, false if handler not available."
  [chunk-id]
  (if-let [nav-fn @navigate-to-chunk-fn]
    (nav-fn chunk-id)
    false))

;; Store the scroll-to-pattern function - set by editor when mounted
(defonce set-pending-scroll-fn (atom nil))

(defn set-pending-scroll-fn!
  "Register the editor's set-pending-scroll function"
  [f]
  (reset! set-pending-scroll-fn f))

(defn navigate-to-chunk-and-scroll!
  "Navigate to a chunk and scroll to a specific pattern (e.g., [@aspect-id]).
   The pattern will be searched and scrolled to after the editor loads."
  [chunk-id scroll-pattern]
  ;; Set the pending scroll pattern before navigating
  (when-let [scroll-fn @set-pending-scroll-fn]
    (scroll-fn scroll-pattern))
  ;; Then navigate
  (navigate-to-chunk! chunk-id))

;; =============================================================================
;; Aspect Navigation (for clicking aspect tags)
;; =============================================================================

;; Store the navigate function - set by outline when mounted
(defonce navigate-to-aspect-fn (atom nil))

(defn set-navigate-to-aspect-fn!
  "Register the outline's aspect navigation function"
  [f]
  (reset! navigate-to-aspect-fn f))

(defn navigate-to-aspect!
  "Navigate to an aspect: select it and expand its parent hierarchy.
   Returns true if successful, false if handler not available."
  [aspect-id]
  (if-let [nav-fn @navigate-to-aspect-fn]
    (nav-fn aspect-id)
    false))

;; =============================================================================
;; Global Dialog System (replaces js/alert, js/confirm, js/prompt)
;; =============================================================================

(defonce dialog-state (r/atom nil))

(defn show-alert!
  "Show an alert dialog with a message and OK button.
   Options:
   - :title - dialog title (optional)
   - :on-close - callback when dialog is closed (optional)"
  ([message] (show-alert! message {}))
  ([message {:keys [title on-close]}]
   (reset! dialog-state {:type :alert
                         :message message
                         :title title
                         :on-close on-close})))

(defn show-confirm!
  "Show a confirm dialog with a message and OK/Cancel buttons.
   Options:
   - :title - dialog title (optional)
   - :on-confirm - callback when user confirms
   - :on-cancel - callback when user cancels (optional)
   - :confirm-text - custom text for confirm button (optional)
   - :cancel-text - custom text for cancel button (optional)
   - :danger? - if true, confirm button is red (for destructive actions)"
  [message {:keys [title on-confirm on-cancel confirm-text cancel-text danger?]}]
  (reset! dialog-state {:type :confirm
                        :message message
                        :title title
                        :on-confirm on-confirm
                        :on-cancel on-cancel
                        :confirm-text confirm-text
                        :cancel-text cancel-text
                        :danger? danger?}))

(defn show-prompt!
  "Show a prompt dialog with a message, input field, and OK/Cancel buttons.
   Options:
   - :title - dialog title (optional)
   - :default-value - initial value for input (optional)
   - :placeholder - placeholder text for input (optional)
   - :on-submit - callback with input value when user submits
   - :on-cancel - callback when user cancels (optional)
   - :submit-text - custom text for submit button (optional)
   - :cancel-text - custom text for cancel button (optional)"
  [message {:keys [title default-value placeholder on-submit on-cancel submit-text cancel-text]}]
  (reset! dialog-state {:type :prompt
                        :message message
                        :title title
                        :default-value default-value
                        :placeholder placeholder
                        :on-submit on-submit
                        :on-cancel on-cancel
                        :submit-text submit-text
                        :cancel-text cancel-text}))

(defn close-dialog!
  "Close the current dialog"
  []
  (reset! dialog-state nil))
