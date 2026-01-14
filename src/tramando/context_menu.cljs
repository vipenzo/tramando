(ns tramando.context-menu
  "Context menu component with annotations and AI assistant integration"
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [tramando.i18n :refer [t]]
            [tramando.settings :as settings]
            [tramando.model :as model]
            [tramando.annotations :as annotations]
            [tramando.ai.templates :as templates]
            [tramando.ai.handlers :as ai-handlers]
            [tramando.events :as events]
            [tramando.auth :as auth]
            [tramando.store.remote :as remote]))

;; =============================================================================
;; Context Menu State
;; =============================================================================

(defonce menu-state (r/atom {:visible false
                             :x 0
                             :y 0
                             :chunk nil
                             :selected-text nil
                             :open-submenu nil
                             ;; For annotation context menu
                             :annotation nil
                             :menu-type nil})) ;; :selection | :annotation-normal | :annotation-ai | :annotation-ai-done

;; State for annotation edit modal
(defonce edit-modal-state (r/atom {:visible false
                                   :annotation nil
                                   :priority ""
                                   :comment ""}))

;; State for annotation create modal (new annotation workflow)
(defonce create-modal-state (r/atom {:visible false
                                     :annotation-type nil  ;; :TODO, :NOTE, :FIX
                                     :chunk nil
                                     :selected-text nil
                                     :priority ""
                                     :comment ""}))

;; State for proposal create modal
(defonce proposal-modal-state (r/atom {:visible false
                                       :chunk nil
                                       :selected-text nil
                                       :proposed-text ""}))

;; Callbacks - these will be set by the main app
(defonce on-template-action (atom nil))
(defonce on-wrap-annotation (atom nil))
(defonce on-delete-annotation (atom nil))

(defn set-template-action-handler!
  "Set the callback for when a template action is selected"
  [handler]
  (reset! on-template-action handler))

(defn set-wrap-annotation-handler!
  "Set the callback for when an annotation should be created"
  [handler]
  (reset! on-wrap-annotation handler))

(defn set-delete-annotation-handler!
  "Set the callback for when an annotation should be deleted"
  [handler]
  (reset! on-delete-annotation handler))

;; =============================================================================
;; Menu Control Functions
;; =============================================================================

(defn show-menu!
  "Show the context menu at the specified position"
  [x y chunk selected-text & {:keys [annotation aspect-link menu-type]}]
  (reset! menu-state {:visible true
                      :x x
                      :y y
                      :chunk chunk
                      :selected-text selected-text
                      :open-submenu nil
                      :annotation annotation
                      :aspect-link aspect-link
                      :menu-type (or menu-type :selection)}))

(defn hide-menu!
  "Hide the context menu"
  []
  (swap! menu-state assoc :visible false :open-submenu nil))

(defn open-submenu!
  "Open a submenu by key"
  [submenu-key]
  (swap! menu-state assoc :open-submenu submenu-key))

(defn close-submenu!
  "Close any open submenu"
  []
  (swap! menu-state assoc :open-submenu nil))

;; =============================================================================
;; Annotation Edit Modal
;; =============================================================================

(defn- annotation-type-color
  "Get the color for an annotation type"
  [ann-type colors]
  (case ann-type
    :TODO (:accent colors)
    :NOTE (:luoghi colors)
    :FIX (:danger colors)
    :PROPOSAL (:accent colors)
    (:accent colors)))

(defn show-edit-modal!
  "Show the annotation edit modal"
  [annotation]
  ;; Get priority and comment from new EDN format (:data) or legacy format
  (let [priority (or (get-in annotation [:data :priority])
                     (:priority annotation))
        ann-comment (or (get-in annotation [:data :comment])
                        (:comment annotation))
        ;; Convert priority to string for the select (handles nil, numbers, strings, keywords)
        priority-str (cond
                       (nil? priority) ""
                       (keyword? priority) (name priority)
                       (number? priority) (str priority)
                       :else (str priority))]
    (reset! edit-modal-state {:visible true
                              :annotation annotation
                              :priority priority-str
                              :comment (or ann-comment "")})))

(defn hide-edit-modal!
  "Hide the annotation edit modal"
  []
  (reset! edit-modal-state {:visible false
                            :annotation nil
                            :priority ""
                            :comment ""}))

(defn- build-annotation-markup
  "Build the full annotation markup string in EDN format"
  [type text author priority comment]
  (let [data (cond-> {:text text}
               author (assoc :author author)
               (and priority (seq (str priority))) (assoc :priority (if (and (string? priority) (re-matches #"\d+" priority))
                                                                      (js/parseInt priority)
                                                                      priority))
               (and comment (seq comment)) (assoc :comment comment))]
    (str "[!" (str/upper-case (name type)) (pr-str data) "]")))

(defn save-annotation-edit!
  "Save the edited annotation"
  []
  (let [{:keys [annotation priority comment]} @edit-modal-state
        chunk-id (:chunk-id annotation)
        ann-type (:type annotation)
        ;; Get text and author from new format (:data) or old format
        selected-text (or (get-in annotation [:data :text])
                          (:selected-text annotation))
        author (or (get-in annotation [:data :author])
                   (:author annotation))]
    (when-let [chunk (model/get-chunk chunk-id)]
      (let [content (:content chunk)
            ;; Find the actual annotation in content by position
            start (:start annotation)
            end (:end annotation)
            old-markup (when (and start end (<= 0 start) (<= end (count content)))
                         (subs content start end))
            new-markup (build-annotation-markup ann-type selected-text author priority comment)]
        (when (and old-markup (not= old-markup new-markup))
          ;; Use editor dispatch for undo support
          (if (events/replace-text-in-editor! old-markup new-markup)
            (events/show-toast! (t :annotation-updated))
            ;; Fallback: direct model update
            (let [new-content (str (subs content 0 start) new-markup (subs content end))]
              (when (not= content new-content)
                (model/update-chunk! chunk-id {:content new-content})
                (events/refresh-editor!)
                (events/show-toast! (t :annotation-updated))))))))
    (hide-edit-modal!)))

(defn annotation-edit-modal
  "Modal for editing annotation priority and comment"
  []
  (let [{:keys [visible annotation priority comment]} @edit-modal-state
        colors (:colors @settings/settings)
        ann-type (:type annotation)
        ;; Get author from new EDN format (:data) or legacy format
        author (or (get-in annotation [:data :author])
                   (:author annotation))
        author-display (when author (auth/get-cached-display-name author))
        ;; Get text from new EDN format (:data) or legacy format
        selected-text (or (get-in annotation [:data :text])
                          (:selected-text annotation))
        type-color (annotation-type-color ann-type colors)]
    (when visible
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 10002}
             ;; Use mousedown to avoid closing when text selection drag ends outside
             :on-mouse-down #(hide-edit-modal!)
             :on-key-down (fn [e]
                            (case (.-key e)
                              "Escape" (hide-edit-modal!)
                              "Enter" (when-not (= "TEXTAREA" (.-tagName (.-target e)))
                                        (save-annotation-edit!))
                              nil))}
       [:div {:style {:background (:sidebar colors)
                      :border-radius "8px"
                      :padding "20px"
                      :min-width "320px"
                      :max-width "450px"
                      :box-shadow "0 4px 20px rgba(0,0,0,0.4)"}
              :on-mouse-down #(.stopPropagation %)}
        ;; Title with type and author
        [:h3 {:style {:margin "0 0 16px 0"
                      :font-size "1.1rem"
                      :display "flex"
                      :align-items "center"
                      :gap "8px"}}
         [:span {:style {:color type-color :font-weight "600"}}
          (when ann-type (name ann-type))]
         (when author-display
           [:span {:style {:color (:text-muted colors) :font-weight "normal"}}
            (str "from " author-display)])]

        ;; Show selected text (read-only)
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :selected-text-label)]
         [:div {:style {:background (:editor-bg colors)
                        :padding "8px 12px"
                        :border-radius "4px"
                        :color (:text colors)
                        :font-style "italic"}}
          selected-text]]

        ;; Priority field (decimal number with "." separator)
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :annotation-priority)]
         [:input {:type "text"
                  :value priority
                  :on-change #(let [v (.. % -target -value)
                                    ;; Allow only digits, dots, and empty
                                    clean (str/replace v #"[^0-9.]" "")]
                                (swap! edit-modal-state assoc :priority clean))
                  :placeholder "1, 2.5, 10..."
                  :style {:width "100%"
                          :padding "8px"
                          :border (str "1px solid " (:border colors))
                          :border-radius "4px"
                          :background (:editor-bg colors)
                          :color (:text colors)
                          :font-size "0.95rem"
                          :box-sizing "border-box"}}]]

        ;; Comment field (textarea for multi-line)
        [:div {:style {:margin-bottom "20px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :annotation-comment)]
         [:textarea {:value comment
                     :on-change #(swap! edit-modal-state assoc :comment (.. % -target -value))
                     :on-key-down (fn [e]
                                    (when (= (.-key e) "Escape")
                                      (hide-edit-modal!)))
                     :placeholder (t :comment-placeholder)
                     :rows 3
                     :style {:width "100%"
                             :padding "8px"
                             :border (str "1px solid " (:border colors))
                             :border-radius "4px"
                             :background (:editor-bg colors)
                             :color (:text colors)
                             :font-size "0.95rem"
                             :box-sizing "border-box"
                             :resize "vertical"
                             :font-family "inherit"}}]]

        ;; Buttons
        [:div {:style {:display "flex"
                       :justify-content "flex-end"
                       :gap "12px"}}
         [:button {:on-click #(hide-edit-modal!)
                   :style {:padding "8px 16px"
                           :border (str "1px solid " (:border colors))
                           :border-radius "4px"
                           :background "transparent"
                           :color (:text colors)
                           :cursor "pointer"}}
          (t :cancel)]
         [:button {:on-click #(save-annotation-edit!)
                   :style {:padding "8px 16px"
                           :border "none"
                           :border-radius "4px"
                           :background (:accent colors)
                           :color "#fff"
                           :cursor "pointer"
                           :font-weight "500"}}
          (t :save)]]]])))

;; =============================================================================
;; Annotation Create Modal (new workflow)
;; =============================================================================

(defn show-create-modal!
  "Show the annotation create modal"
  [annotation-type chunk selected-text]
  (reset! create-modal-state {:visible true
                              :annotation-type (keyword annotation-type)
                              :chunk chunk
                              :selected-text selected-text
                              :priority ""
                              :comment ""}))

(defn hide-create-modal!
  "Hide the annotation create modal"
  []
  (reset! create-modal-state {:visible false
                              :annotation-type nil
                              :chunk nil
                              :selected-text nil
                              :priority ""
                              :comment ""}))

(defn confirm-create-annotation!
  "Confirm and create the annotation"
  []
  (let [{:keys [annotation-type chunk selected-text priority comment]} @create-modal-state]
    (when (and @on-wrap-annotation chunk selected-text)
      (@on-wrap-annotation (name annotation-type) chunk selected-text priority comment))
    (hide-create-modal!)))

(defn annotation-create-modal
  "Modal for creating a new annotation with comment and priority"
  []
  (let [{:keys [visible annotation-type selected-text priority comment]} @create-modal-state
        colors (:colors @settings/settings)
        type-color (when annotation-type (annotation-type-color annotation-type colors))
        input-ref (atom nil)]
    (when visible
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 10002}
             :on-mouse-down #(hide-create-modal!)
             :on-key-down (fn [e]
                            (case (.-key e)
                              "Escape" (hide-create-modal!)
                              "Enter" (when-not (= "TEXTAREA" (.-tagName (.-target e)))
                                        (confirm-create-annotation!))
                              nil))}
       [:div {:style {:background (:sidebar colors)
                      :border-radius "8px"
                      :padding "20px"
                      :min-width "320px"
                      :max-width "450px"
                      :box-shadow "0 4px 20px rgba(0,0,0,0.4)"
                      :border-top (str "3px solid " type-color)}
              :on-mouse-down #(.stopPropagation %)}
        ;; Title with type badge
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "10px"
                       :margin-bottom "16px"}}
         [:span {:style {:background type-color
                         :color "#fff"
                         :padding "4px 10px"
                         :border-radius "4px"
                         :font-size "0.85rem"
                         :font-weight "600"}}
          (when annotation-type (name annotation-type))]
         [:span {:style {:color (:text colors)
                         :font-size "1rem"}}
          (t :new-annotation)]]

        ;; Show selected text (read-only)
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :selected-text-label)]
         [:div {:style {:background (:editor-bg colors)
                        :padding "8px 12px"
                        :border-radius "4px"
                        :color (:text colors)
                        :font-style "italic"
                        :max-height "60px"
                        :overflow-y "auto"}}
          selected-text]]

        ;; Comment field (textarea for multi-line, with auto-focus)
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :annotation-comment)]
         [:textarea {:ref (fn [el]
                            (when el
                              (reset! input-ref el)
                              ;; Auto-focus after a brief delay
                              (js/setTimeout #(.focus el) 50)))
                     :value comment
                     :on-change #(swap! create-modal-state assoc :comment (.. % -target -value))
                     :on-key-down (fn [e]
                                    (when (= (.-key e) "Escape")
                                      (hide-create-modal!)))
                     :placeholder (t :comment-placeholder)
                     :rows 2
                     :style {:width "100%"
                             :padding "8px"
                             :border (str "1px solid " (:border colors))
                             :border-radius "4px"
                             :background (:editor-bg colors)
                             :color (:text colors)
                             :font-size "0.95rem"
                             :box-sizing "border-box"
                             :resize "vertical"
                             :font-family "inherit"}}]]

        ;; Priority field
        [:div {:style {:margin-bottom "20px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (str (t :annotation-priority) " " (t :optional))]
         [:input {:type "text"
                  :value priority
                  :on-change #(let [v (.. % -target -value)
                                    clean (str/replace v #"[^0-9.]" "")]
                                (swap! create-modal-state assoc :priority clean))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Enter")
                                   (.preventDefault e)
                                   (confirm-create-annotation!)))
                  :placeholder "1, 2.5, 10..."
                  :style {:width "100%"
                          :padding "8px"
                          :border (str "1px solid " (:border colors))
                          :border-radius "4px"
                          :background (:editor-bg colors)
                          :color (:text colors)
                          :font-size "0.95rem"
                          :box-sizing "border-box"}}]]

        ;; Buttons
        [:div {:style {:display "flex"
                       :justify-content "flex-end"
                       :gap "12px"}}
         [:button {:on-click #(hide-create-modal!)
                   :style {:padding "8px 16px"
                           :border (str "1px solid " (:border colors))
                           :border-radius "4px"
                           :background "transparent"
                           :color (:text colors)
                           :cursor "pointer"}}
          (t :cancel)]
         [:button {:on-click #(confirm-create-annotation!)
                   :style {:padding "8px 16px"
                           :border "none"
                           :border-radius "4px"
                           :background type-color
                           :color "#fff"
                           :cursor "pointer"
                           :font-weight "500"}}
          (t :add)]]]])))

;; =============================================================================
;; Proposal Create Modal
;; =============================================================================

(defn show-proposal-modal!
  "Show the proposal create modal"
  [chunk selected-text]
  (reset! proposal-modal-state {:visible true
                                :chunk chunk
                                :selected-text selected-text
                                :proposed-text selected-text}))

(defn hide-proposal-modal!
  "Hide the proposal create modal"
  []
  (reset! proposal-modal-state {:visible false
                                :chunk nil
                                :selected-text nil
                                :proposed-text ""}))

(defn confirm-create-proposal!
  "Confirm and create the proposal"
  []
  (let [{:keys [chunk selected-text proposed-text]} @proposal-modal-state]
    (when (and chunk (seq selected-text) (seq proposed-text) (not= proposed-text selected-text))
      (let [content (:content chunk)
            start (.indexOf content selected-text)]
        (when (>= start 0)
          (let [end (+ start (count selected-text))]
            (model/create-proposal-in-chunk! (:id chunk) start end proposed-text)
            (js/setTimeout #(events/refresh-editor!) 50))))))
  (hide-proposal-modal!))

(defn proposal-create-modal
  "Modal for creating a new proposal"
  []
  (let [{:keys [visible selected-text proposed-text]} @proposal-modal-state
        colors (:colors @settings/settings)
        textarea-ref (atom nil)]
    (when visible
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 10002}
             :on-mouse-down #(hide-proposal-modal!)
             :on-key-down (fn [e]
                            (case (.-key e)
                              "Escape" (hide-proposal-modal!)
                              nil))}
       [:div {:style {:background (:sidebar colors)
                      :border-radius "8px"
                      :padding "20px"
                      :min-width "400px"
                      :max-width "600px"
                      :box-shadow "0 4px 20px rgba(0,0,0,0.4)"
                      :border-top (str "3px solid " (:accent colors))}
              :on-mouse-down #(.stopPropagation %)}
        ;; Title
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "10px"
                       :margin-bottom "16px"}}
         [:span {:style {:background (:accent colors)
                         :color "#fff"
                         :padding "4px 10px"
                         :border-radius "4px"
                         :font-size "0.85rem"
                         :font-weight "600"}}
          "PROPOSAL"]
         [:span {:style {:color (:text colors)
                         :font-size "1rem"}}
          (t :proposal-create)]]

        ;; Show original text (read-only)
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :proposal-original)]
         [:div {:style {:background (:editor-bg colors)
                        :padding "8px 12px"
                        :border-radius "4px"
                        :color (:text colors)
                        :font-style "italic"
                        :max-height "80px"
                        :overflow-y "auto"
                        :white-space "pre-wrap"}}
          selected-text]]

        ;; Proposed text field (textarea for multi-line)
        [:div {:style {:margin-bottom "20px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :proposal-proposed)]
         [:textarea {:ref (fn [el]
                            (when el
                              (reset! textarea-ref el)
                              (js/setTimeout #(.focus el) 50)))
                     :value proposed-text
                     :on-change #(swap! proposal-modal-state assoc :proposed-text (.. % -target -value))
                     :on-key-down (fn [e]
                                    (when (and (= (.-key e) "Enter") (.-metaKey e))
                                      (.preventDefault e)
                                      (confirm-create-proposal!)))
                     :placeholder (t :proposal-enter-text)
                     :style {:width "100%"
                             :min-height "100px"
                             :padding "8px"
                             :border (str "1px solid " (:border colors))
                             :border-radius "4px"
                             :background (:editor-bg colors)
                             :color (:text colors)
                             :font-size "0.95rem"
                             :font-family "inherit"
                             :resize "vertical"
                             :box-sizing "border-box"}}]]

        ;; Hint for Cmd+Enter
        [:div {:style {:color (:text-dim colors)
                       :font-size "0.75rem"
                       :margin-bottom "16px"
                       :text-align "center"}}
         "⌘+Enter " (t :press-enter-to-confirm)]

        ;; Buttons
        [:div {:style {:display "flex"
                       :justify-content "flex-end"
                       :gap "12px"}}
         [:button {:on-click #(hide-proposal-modal!)
                   :style {:padding "8px 16px"
                           :border (str "1px solid " (:border colors))
                           :border-radius "4px"
                           :background "transparent"
                           :color (:text colors)
                           :cursor "pointer"}}
          (t :cancel)]
         [:button {:on-click #(confirm-create-proposal!)
                   :disabled (or (empty? proposed-text) (= proposed-text selected-text))
                   :style {:padding "8px 16px"
                           :border "none"
                           :border-radius "4px"
                           :background (if (or (empty? proposed-text) (= proposed-text selected-text))
                                         (:text-muted colors)
                                         (:accent colors))
                           :color "#fff"
                           :cursor (if (or (empty? proposed-text) (= proposed-text selected-text))
                                     "not-allowed"
                                     "pointer")
                           :font-weight "500"}}
          (t :proposal-create)]]]])))

;; =============================================================================
;; AI Configuration Check
;; =============================================================================

(defn ai-configured?
  "Check if AI is configured and enabled"
  []
  (settings/ai-configured?))

;; =============================================================================
;; Menu Item Handlers
;; =============================================================================

(defn handle-action!
  "Handle a template action"
  [template-id & {:keys [aspect-id]}]
  (when @on-template-action
    (@on-template-action template-id
                         (:chunk @menu-state)
                         (:selected-text @menu-state)
                         aspect-id))
  (hide-menu!))

(defn handle-wrap-annotation!
  "Handle wrapping selected text with an annotation - shows create modal"
  [annotation-type]
  (let [chunk (:chunk @menu-state)
        selected-text (:selected-text @menu-state)]
    (when (and chunk selected-text)
      (show-create-modal! annotation-type chunk selected-text)))
  (hide-menu!))

(defn handle-create-proposal!
  "Handle creating a proposal for selected text"
  []
  (let [chunk (:chunk @menu-state)
        selected-text (:selected-text @menu-state)]
    (when (and chunk (seq selected-text))
      ;; Show modal for entering proposed text
      (show-proposal-modal! chunk selected-text)))
  (hide-menu!))

;; =============================================================================
;; Menu Components
;; =============================================================================

(defn- menu-item
  "Single menu item"
  [{:keys [label on-click disabled? has-submenu? submenu-key icon color]}]
  (let [hover-state (r/atom false)]
    (fn [{:keys [label on-click disabled? has-submenu? submenu-key icon color]}]
      [:div {:style {:padding "8px 12px"
                     :cursor (if disabled? "default" "pointer")
                     :display "flex"
                     :align-items "center"
                     :justify-content "space-between"
                     :color (if disabled?
                              (settings/get-color :text-muted)
                              (or color (settings/get-color :text)))
                     :background (when (and (not disabled?) @hover-state)
                                   (settings/get-color :editor-bg))}
             :on-mouse-enter (fn []
                               (reset! hover-state true)
                               (when submenu-key
                                 (open-submenu! submenu-key)))
             :on-mouse-leave #(reset! hover-state false)
             :on-click (when (and on-click (not disabled?))
                         (fn [e]
                           (.stopPropagation e)
                           (on-click)))}
       [:span {:style {:display "flex" :align-items "center" :gap "8px"}}
        (when icon
          [:span {:style {:width "16px" :text-align "center"}} icon])
        label]
       (when has-submenu?
         [:span {:style {:margin-left "8px"
                         :font-size "10px"}}
          "\u25B6"])])))

(defn- menu-separator
  "Menu separator line"
  []
  [:div {:style {:height "1px"
                 :background (settings/get-color :border)
                 :margin "4px 0"}}])

(defn- submenu
  "Submenu component that appears to the right"
  [{:keys [items parent-rect]}]
  [:div {:style {:position "fixed"
                 :left (str (+ (:right parent-rect) 2) "px")
                 :top (str (:top parent-rect) "px")
                 :background (settings/get-color :sidebar)
                 :border (str "1px solid " (settings/get-color :border))
                 :border-radius "6px"
                 :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                 :min-width "160px"
                 :z-index 10002
                 :overflow "hidden"}}
   (for [{:keys [id label action disabled? icon color]} items]
     ^{:key id}
     [menu-item {:label label
                 :on-click action
                 :disabled? disabled?
                 :icon icon
                 :color color}])])

(defn- annotation-submenu
  "Submenu for annotation type selection"
  [parent-rect]
  [submenu {:parent-rect parent-rect
            :items [{:id :todo
                     :label "TODO"
                     :icon "●"
                     :color (settings/get-color :accent)
                     :action #(handle-wrap-annotation! "TODO")}
                    {:id :note
                     :label "NOTE"
                     :icon "●"
                     :color (settings/get-color :luoghi)  ; blu per distinguere da TODO
                     :action #(handle-wrap-annotation! "NOTE")}
                    {:id :fix
                     :label "FIX"
                     :icon "●"
                     :color (settings/get-color :danger)
                     :action #(handle-wrap-annotation! "FIX")}]}])

(defn- ai-assistant-submenu
  "Submenu for AI assistant actions"
  [parent-rect chunk selected-text]
  (let [linked-aspects (when chunk (templates/get-linked-aspects chunk))
        has-linked-aspects? (seq linked-aspects)]
    [:div {:style {:position "fixed"
                   :left (str (+ (:right parent-rect) 2) "px")
                   :top (str (:top parent-rect) "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width "180px"
                   :z-index 10002
                   :overflow "hidden"}}
     ;; Main AI actions
     [menu-item {:label (t :ai-expand)
                 :on-click #(handle-action! :expand)}]
     [menu-item {:label (t :ai-rephrase)
                 :on-click #(handle-action! :rephrase)}]
     [menu-separator]
     ;; Tone options (inline, not nested)
     [menu-item {:label (t :ai-tone-dark)
                 :on-click #(handle-action! :tone-dark)}]
     [menu-item {:label (t :ai-tone-light)
                 :on-click #(handle-action! :tone-light)}]
     [menu-item {:label (t :ai-tone-formal)
                 :on-click #(handle-action! :tone-formal)}]
     [menu-item {:label (t :ai-tone-casual)
                 :on-click #(handle-action! :tone-casual)}]
     [menu-item {:label (t :ai-tone-poetic)
                 :on-click #(handle-action! :tone-poetic)}]
     [menu-separator]
     ;; Creation actions
     [menu-item {:label (t :ai-create-character)
                 :on-click #(handle-action! :create-character)}]
     [menu-item {:label (t :ai-create-place)
                 :on-click #(handle-action! :create-place)}]
     [menu-item {:label (t :ai-suggest-conflict)
                 :on-click #(handle-action! :suggest-conflict)}]
     [menu-item {:label (t :ai-analyze-consistency)
                 :on-click #(handle-action! :analyze-consistency)}]
     ;; Extract info for linked aspects
     (when (and has-linked-aspects? (seq selected-text))
       [:<>
        [menu-separator]
        (for [aspect linked-aspects]
          ^{:key (:id aspect)}
          [menu-item {:label (str (t :ai-extract-info) ": " (or (:summary aspect) (:id aspect)))
                      :on-click #(handle-action! :update-aspect :aspect-id (:id aspect))}])])
     [menu-separator]
     ;; Ask (free input)
     [menu-item {:label (t :ai-ask)
                 :on-click #(handle-action! :ask)}]]))

(defn- calculate-submenu-rect
  "Calculate the position rect for a submenu based on the menu item"
  [menu-x menu-y item-index menu-width]
  (let [item-height 34  ;; approximate height of menu item
        top (+ menu-y (* item-index item-height))]
    {:left menu-x
     :right (+ menu-x menu-width)
     :top top}))

;; =============================================================================
;; AI-DONE Annotation Menu (with alternatives)
;; =============================================================================

(defn- ai-done-annotation-menu
  "Context menu for AI-DONE annotations showing alternatives"
  [{:keys [x y annotation chunk]}]
  (let [menu-width 280
        selected-text (annotations/get-text annotation)
        chunk-id (:chunk-id annotation)
        ;; Read fresh data from chunk to get current selection state
        fresh-chunk (model/get-chunk chunk-id)
        fresh-content (:content fresh-chunk)
        ;; Find the annotation in fresh content and parse its data
        fresh-annotation (first (filter #(= (annotations/get-text %) selected-text)
                                        (annotations/parse-annotations fresh-content)))
        ann (or fresh-annotation annotation)
        alternatives (or (annotations/get-ai-alternatives ann) [])
        current-sel (or (annotations/get-ai-selection ann) 0)
        ;; Preview helper
        make-preview (fn [text]
                       (if (> (count text) 60)
                         (str (subs text 0 57) "...")
                         text))]
    [:div {:style {:position "fixed"
                   :left (str x "px")
                   :top (str y "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width (str menu-width "px")
                   :max-width "350px"
                   :z-index 10001
                   :overflow "hidden"}
           :on-click #(.stopPropagation %)}
     ;; Original text option (index 0) - always show clean, no strikethrough
     (let [is-original-selected? (zero? current-sel)]
       [:div {:style {:padding "8px 12px"
                      :cursor "pointer"
                      :display "flex"
                      :align-items "flex-start"
                      :gap "8px"
                      :background (when is-original-selected? (settings/get-color :editor-bg))
                      :border-bottom (str "1px solid " (settings/get-color :border))}
              :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
              :on-mouse-out #(set! (.. % -currentTarget -style -background) (if is-original-selected? (settings/get-color :editor-bg) "transparent"))
              :on-click (fn [e]
                          (.stopPropagation e)
                          (ai-handlers/update-ai-selection! chunk-id selected-text 0))}
        [:span {:style {:color (if is-original-selected? (settings/get-color :accent) (settings/get-color :text-muted))
                        :flex-shrink 0}}
         (if is-original-selected? "●" "○")]
        [:span {:style {:font-size "0.85rem"
                        :color (settings/get-color :text)}}
         (make-preview selected-text)]
        (when is-original-selected?
          [:span {:style {:color (settings/get-color :accent)
                          :margin-left "auto"
                          :flex-shrink 0}}
           "✓"])])
     ;; Alternatives as radio buttons
     (doall
      (for [[idx alt-text] (map-indexed vector alternatives)]
        (let [sel-idx (inc idx)
              is-selected? (= current-sel sel-idx)]
          ^{:key idx}
          [:div {:style {:padding "8px 12px"
                         :cursor "pointer"
                         :display "flex"
                         :align-items "flex-start"
                         :gap "8px"
                         :background (when is-selected? (settings/get-color :editor-bg))}
                 :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                 :on-mouse-out #(set! (.. % -currentTarget -style -background) (if is-selected? (settings/get-color :editor-bg) "transparent"))
                 :on-click (fn [e]
                             (.stopPropagation e)
                             (ai-handlers/update-ai-selection! chunk-id selected-text sel-idx))}
           [:span {:style {:color (if is-selected? (settings/get-color :accent) (settings/get-color :text-muted))
                           :flex-shrink 0}}
            (if is-selected? "●" "○")]
           [:span {:style {:font-size "0.85rem"
                           :color (settings/get-color :text)}}
            (make-preview alt-text)]
           (when is-selected?
             [:span {:style {:color (settings/get-color :accent)
                             :margin-left "auto"
                             :flex-shrink 0}}
              "✓"])])))
     ;; Separator
     [menu-separator]
     ;; Apply selection button
     [menu-item {:label (t :apply-selection)
                 :icon "✓"
                 :color (settings/get-color :accent)
                 :disabled? (zero? current-sel)
                 :on-click (fn []
                             (ai-handlers/confirm-ai-alternative! chunk-id selected-text)
                             (hide-menu!))}]
     ;; Cancel changes button
     [menu-item {:label (t :cancel-changes)
                 :icon "✗"
                 :on-click (fn []
                             (ai-handlers/cancel-ai-annotation! chunk-id selected-text)
                             (hide-menu!))}]]))

;; =============================================================================
;; Normal Annotation Menu (TODO, NOTE, FIX)
;; =============================================================================

(defn- normal-annotation-menu
  "Context menu for normal annotations (TODO, NOTE, FIX)"
  [{:keys [x y annotation]}]
  (let [menu-width 200
        ann-type (:type annotation)
        colors (:colors @settings/settings)
        type-color (annotation-type-color ann-type colors)]
    [:div {:style {:position "fixed"
                   :left (str x "px")
                   :top (str y "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width (str menu-width "px")
                   :z-index 10001
                   :overflow "hidden"}
           :on-click #(.stopPropagation %)}
     ;; Header with annotation type
     [:div {:style {:padding "8px 12px"
                    :font-weight "600"
                    :font-size "0.85rem"
                    :color type-color
                    :border-bottom (str "1px solid " (settings/get-color :border))
                    :background (settings/get-color :editor-bg)}}
      (name ann-type)]
     [menu-item {:label (t :edit-annotation)
                 :on-click (fn []
                             (show-edit-modal! annotation)
                             (hide-menu!))}]
     [menu-separator]
     [menu-item {:label (t :delete-annotation)
                 :color (settings/get-color :danger)
                 :on-click (fn []
                             ;; Delete annotation, keeping original text
                             (when @on-delete-annotation
                               (@on-delete-annotation annotation))
                             (hide-menu!))}]]))

;; =============================================================================
;; AI Pending Annotation Menu
;; =============================================================================

(defn- ai-pending-menu
  "Context menu for pending AI annotations"
  [{:keys [x y annotation chunk]}]
  (let [menu-width 180
        selected-text (:selected-text annotation)
        chunk-id (:chunk-id annotation)]
    [:div {:style {:position "fixed"
                   :left (str x "px")
                   :top (str y "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width (str menu-width "px")
                   :z-index 10001
                   :overflow "hidden"}
           :on-click #(.stopPropagation %)}
     [menu-item {:label (t :cancel-request)
                 :color (settings/get-color :danger)
                 :on-click (fn []
                             (ai-handlers/cancel-ai-annotation! chunk-id selected-text)
                             (hide-menu!))}]]))

;; =============================================================================
;; Proposal Annotation Menu (Interactive like AI-DONE)
;; =============================================================================

(defn- proposal-annotation-menu
  "Context menu for PROPOSAL annotations - interactive preview like AI-DONE"
  [{:keys [x y annotation]}]
  (let [menu-width 300
        chunk-id (:chunk-id annotation)
        original-text (annotations/get-text annotation)
        sender (annotations/get-proposal-from annotation)
        ;; Read fresh data from chunk to get current selection state
        fresh-chunk (model/get-chunk chunk-id)
        fresh-content (:content fresh-chunk)
        ;; Find the annotation in fresh content and parse its data
        fresh-annotation (first (filter #(and (= (annotations/get-text %) original-text)
                                              (annotations/is-proposal? %))
                                        (annotations/parse-annotations fresh-content)))
        ann (or fresh-annotation annotation)
        proposed-text (or (annotations/get-proposed-text ann) "")
        current-sel (or (annotations/get-proposal-selection ann) 0)
        ;; Preview helper
        make-preview (fn [text]
                       (if (> (count text) 60)
                         (str (subs text 0 57) "...")
                         text))]
    [:div {:style {:position "fixed"
                   :left (str x "px")
                   :top (str y "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width (str menu-width "px")
                   :max-width "400px"
                   :z-index 10001
                   :overflow "hidden"}
           :on-click #(.stopPropagation %)}
     ;; Header: who proposed
     [:div {:style {:padding "10px 12px"
                    :font-size "0.85rem"
                    :color (settings/get-color :text-muted)
                    :border-bottom (str "1px solid " (settings/get-color :border))}}
      [:span {:style {:font-weight "500" :color (settings/get-color :text)}}
       (str (t :proposal-from) " " (or sender "local"))]]

     ;; Original text option (sel=0) - selectable
     (let [is-original-selected? (zero? current-sel)]
       [:div {:style {:padding "8px 12px"
                      :cursor "pointer"
                      :display "flex"
                      :align-items "flex-start"
                      :gap "8px"
                      :background (when is-original-selected? (settings/get-color :editor-bg))
                      :border-bottom (str "1px solid " (settings/get-color :border))}
              :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
              :on-mouse-out #(set! (.. % -currentTarget -style -background) (if is-original-selected? (settings/get-color :editor-bg) "transparent"))
              :on-click (fn [e]
                          (.stopPropagation e)
                          (js/console.log "=== Clicking Original, chunk-id:" chunk-id "original-text:" original-text)
                          (model/update-proposal-selection! chunk-id original-text 0)
                          (js/setTimeout #(events/refresh-editor!) 50))}
        [:span {:style {:color (if is-original-selected? (settings/get-color :accent) (settings/get-color :text-muted))
                        :flex-shrink 0}}
         (if is-original-selected? "●" "○")]
        [:div {:style {:flex 1}}
         [:div {:style {:font-size "0.7rem"
                        :text-transform "uppercase"
                        :color (settings/get-color :text-muted)
                        :margin-bottom "2px"}}
          (t :proposal-original)]
         [:span {:style {:font-size "0.85rem"
                         :color (settings/get-color :text)}}
          (make-preview original-text)]]
        (when is-original-selected?
          [:span {:style {:color (settings/get-color :accent)
                          :flex-shrink 0}}
           "✓"])])

     ;; Proposed text option (sel=1) - selectable
     (let [is-proposed-selected? (pos? current-sel)]
       [:div {:style {:padding "8px 12px"
                      :cursor "pointer"
                      :display "flex"
                      :align-items "flex-start"
                      :gap "8px"
                      :background (when is-proposed-selected? (settings/get-color :editor-bg))
                      :border-bottom (str "1px solid " (settings/get-color :border))}
              :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
              :on-mouse-out #(set! (.. % -currentTarget -style -background) (if is-proposed-selected? (settings/get-color :editor-bg) "transparent"))
              :on-click (fn [e]
                          (.stopPropagation e)
                          (js/console.log "=== Clicking Proposed, chunk-id:" chunk-id "original-text:" original-text)
                          (model/update-proposal-selection! chunk-id original-text 1)
                          (js/setTimeout #(events/refresh-editor!) 50))}
        [:span {:style {:color (if is-proposed-selected? (settings/get-color :accent) (settings/get-color :text-muted))
                        :flex-shrink 0}}
         (if is-proposed-selected? "●" "○")]
        [:div {:style {:flex 1}}
         [:div {:style {:font-size "0.7rem"
                        :text-transform "uppercase"
                        :color (settings/get-color :text-muted)
                        :margin-bottom "2px"}}
          (t :proposal-proposed)]
         [:span {:style {:font-size "0.85rem"
                         :color (settings/get-color :accent)
                         :font-weight "500"}}
          (make-preview (if (seq proposed-text) proposed-text "(testo vuoto)"))]]
        (when is-proposed-selected?
          [:span {:style {:color (settings/get-color :accent)
                          :flex-shrink 0}}
           "✓"])])

     ;; Separator
     [menu-separator]

     ;; Confirm button (applies the current selection - original or proposed)
     ;; Re-read fresh annotation at click time to get current sel state
     [menu-item {:label (if (pos? current-sel)
                          (t :proposal-accept)      ; Proposed selected
                          (t :proposal-reject))     ; Original selected (reject proposal)
                 :icon "✓"
                 :color (if (pos? current-sel) (settings/get-color :accent) (settings/get-color :text))
                 :on-click (fn []
                             ;; Get fresh annotation at click time (sel may have changed)
                             (let [chunk (model/get-chunk chunk-id)
                                   content (:content chunk)
                                   all-annotations (annotations/parse-annotations content)
                                   proposals (filter annotations/is-proposal? all-annotations)
                                   current-annotation (first (filter #(= (annotations/get-text %) original-text) proposals))
                                   ann (or current-annotation annotation)
                                   sel-now (or (annotations/get-proposal-selection ann) 0)
                                   ;; Check if we're in remote mode
                                   user-role (model/get-user-role)
                                   remote-mode? (and user-role (not= user-role "local"))]
                               ;; DEBUG
                               (js/console.log "=== PROPOSAL DEBUG ===")
                               (js/console.log "user-role:" user-role)
                               (js/console.log "remote-mode?:" remote-mode?)
                               (js/console.log "original-text:" original-text)
                               (js/console.log "proposals count:" (count proposals))
                               (js/console.log "current-annotation found?" (some? current-annotation))
                               (js/console.log "ann :start" (:start ann))
                               (js/console.log "ann :end" (:end ann))
                               (js/console.log "ann :data" (pr-str (:data ann)))
                               (js/console.log "sel-now:" sel-now)
                               (js/console.log "content length:" (count content))
                               (if remote-mode?
                                 ;; Remote mode: use atomic REST API (async)
                                 (let [position (:start ann)]
                                   (hide-menu!)
                                   (if (pos? sel-now)
                                     (-> (remote/accept-proposal! chunk-id position)
                                         (.then (fn [success]
                                                  (when success
                                                    (events/show-toast! (t :discussion-proposal-accepted))
                                                    (events/refresh-editor!)))))
                                     (-> (remote/reject-proposal! chunk-id position)
                                         (.then (fn [success]
                                                  (when success
                                                    (events/show-toast! (t :discussion-proposal-rejected))
                                                    (events/refresh-editor!)))))))
                                 ;; Local mode: use model functions (sync)
                                 (do
                                   (if (pos? sel-now)
                                     (do
                                       (model/accept-proposal! chunk-id ann)
                                       (events/show-toast! (t :discussion-proposal-accepted)))
                                     (do
                                       (model/reject-proposal! chunk-id ann)
                                       (events/show-toast! (t :discussion-proposal-rejected))))
                                   (events/refresh-editor!)
                                   (hide-menu!)))))}]]))

;; =============================================================================
;; Aspect Link Context Menu
;; =============================================================================

(defn- aspect-link-menu
  "Context menu for aspect links [@id]"
  [{:keys [x y aspect-link]}]
  (let [menu-width 200
        aspect-id (:aspect-id aspect-link)
        aspect (:aspect aspect-link)
        aspect-name (when aspect (or (:summary aspect) aspect-id))]
    [:div {:style {:position "fixed"
                   :left (str x "px")
                   :top (str y "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width (str menu-width "px")
                   :z-index 10001
                   :overflow "hidden"}
           :on-click #(.stopPropagation %)}
     (if aspect
       ;; Aspect found - show go to option
       [menu-item {:label (str (t :go-to-aspect) ": " aspect-name)
                   :icon "→"
                   :on-click (fn []
                               (events/navigate-to-chunk! aspect-id)
                               (hide-menu!))}]
       ;; Aspect not found
       [menu-item {:label (str (t :aspect-not-found) ": " aspect-id)
                   :disabled? true
                   :color (settings/get-color :text-muted)}])]))

;; =============================================================================
;; Selection Context Menu (text selected, no annotation)
;; =============================================================================

(defn- handle-cut!
  "Cut selected text to clipboard"
  []
  (when-let [selected-text (:selected-text @menu-state)]
    (-> (js/navigator.clipboard.writeText selected-text)
        (.then (fn []
                 ;; Delete the selected text from editor
                 (when-let [view @events/editor-view-ref]
                   (let [state (.-state view)
                         selection (.-selection state)
                         main-range (.main selection)
                         from (.-from main-range)
                         to (.-to main-range)]
                     (when (< from to)
                       (.dispatch view #js {:changes #js {:from from :to to :insert ""}}))))
                 (events/show-toast! (t :copied))))))
  (hide-menu!))

(defn- handle-copy!
  "Copy selected text to clipboard"
  []
  (when-let [selected-text (:selected-text @menu-state)]
    (-> (js/navigator.clipboard.writeText selected-text)
        (.then (fn [] (events/show-toast! (t :copied))))))
  (hide-menu!))

(defn- handle-paste!
  "Paste from clipboard"
  []
  (-> (js/navigator.clipboard.readText)
      (.then (fn [text]
               (when (and text (seq text))
                 (when-let [view @events/editor-view-ref]
                   (let [state (.-state view)
                         selection (.-selection state)
                         main-range (.main selection)
                         from (.-from main-range)
                         to (.-to main-range)]
                     (.dispatch view #js {:changes #js {:from from :to to :insert text}})))))))
  (hide-menu!))

(defn- selection-context-menu
  "Context menu for selected text"
  [{:keys [x y chunk selected-text open-submenu]}]
  (let [menu-width 220
        ai-configured? (ai-configured?)]
    [:div {:style {:position "fixed"
                   :left (str x "px")
                   :top (str y "px")
                   :background (settings/get-color :sidebar)
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                   :min-width (str menu-width "px")
                   :z-index 10001
                   :overflow "visible"}
           :on-click #(.stopPropagation %)}

     ;; Clipboard section (Cut/Copy/Paste)
     [menu-item {:label (str (t :cut) "  ⌘X")
                 :on-click handle-cut!
                 :disabled? (empty? selected-text)}]
     [menu-item {:label (str (t :copy) "  ⌘C")
                 :on-click handle-copy!
                 :disabled? (empty? selected-text)}]
     [menu-item {:label (str (t :paste) "  ⌘V")
                 :on-click handle-paste!}]

     [menu-separator]

     ;; Annotation section
     [:div {:style {:position "relative"}
            :on-mouse-enter #(open-submenu! :annotation)
            :on-mouse-leave #(when (= open-submenu :annotation)
                               (close-submenu!))}
      [menu-item {:label (t :add-annotation)
                  :has-submenu? true
                  :submenu-key :annotation
                  :disabled? (empty? selected-text)}]
      (when (= open-submenu :annotation)
        [annotation-submenu (calculate-submenu-rect x y 4 menu-width)])]

     ;; Proposal section (collaborative)
     [menu-item {:label (t :proposal-create)
                 :on-click handle-create-proposal!
                 :disabled? (empty? selected-text)}]

     ;; AI section (only if configured) - as a submenu
     (when ai-configured?
       [:<>
        [menu-separator]
        [:div {:style {:position "relative"}
               :on-mouse-enter #(open-submenu! :ai-assistant)
               :on-mouse-leave #(when (= open-submenu :ai-assistant)
                                  (close-submenu!))}
         [menu-item {:label (t :ai-assistant)
                     :has-submenu? true
                     :submenu-key :ai-assistant
                     :disabled? (empty? selected-text)}]
         (when (= open-submenu :ai-assistant)
           [ai-assistant-submenu (calculate-submenu-rect x y 7 menu-width) chunk selected-text])]])]))

;; =============================================================================
;; Main Context Menu Component
;; =============================================================================

(defn context-menu
  "The main context menu component"
  []
  (let [{:keys [visible x y chunk selected-text open-submenu annotation aspect-link menu-type]} @menu-state]
    [:<>
     ;; Context menu overlay
     (when visible
       [:div {:style {:position "fixed"
                      :top 0
                      :left 0
                      :right 0
                      :bottom 0
                      :z-index 9999}
              :on-click hide-menu!
              :on-context-menu (fn [e]
                                 (.preventDefault e)
                                 (hide-menu!))}

        ;; Render appropriate menu based on type
        (case menu-type
          :annotation-ai-done
          [ai-done-annotation-menu {:x x :y y :annotation annotation :chunk chunk}]

          :annotation-ai-pending
          [ai-pending-menu {:x x :y y :annotation annotation :chunk chunk}]

          :annotation-normal
          [normal-annotation-menu {:x x :y y :annotation annotation :chunk chunk}]

          :annotation-proposal
          [proposal-annotation-menu {:x x :y y :annotation annotation}]

          :aspect-link
          [aspect-link-menu {:x x :y y :aspect-link aspect-link}]

          ;; Default: selection menu
          [selection-context-menu {:x x :y y :chunk chunk :selected-text selected-text :open-submenu open-submenu}])])

     ;; Annotation edit modal (rendered independently)
     [annotation-edit-modal]
     ;; Annotation create modal (for new annotation workflow)
     [annotation-create-modal]
     ;; Proposal create modal
     [proposal-create-modal]]))

;; =============================================================================
;; Event Handler for Context Menu
;; =============================================================================

(defn handle-context-menu
  "Handle right-click event on a chunk or editor.
   This is for general text selection (not on annotations)."
  [e chunk selected-text]
  (.preventDefault e)
  (let [x (.-clientX e)
        y (.-clientY e)
        ;; Adjust position if menu would go off screen
        viewport-width (.-innerWidth js/window)
        viewport-height (.-innerHeight js/window)
        menu-width 220
        menu-height 400  ;; approximate
        adjusted-x (if (> (+ x menu-width) viewport-width)
                     (- viewport-width menu-width 10)
                     x)
        adjusted-y (if (> (+ y menu-height) viewport-height)
                     (- viewport-height menu-height 10)
                     y)]
    (show-menu! adjusted-x adjusted-y chunk selected-text :menu-type :selection)))

(defn handle-annotation-context-menu
  "Handle right-click event on an annotation in the text.
   annotation should be a map with :type :selected-text :priority :comment :chunk-id"
  [e annotation]
  (.preventDefault e)
  (let [x (.-clientX e)
        y (.-clientY e)
        viewport-width (.-innerWidth js/window)
        viewport-height (.-innerHeight js/window)
        menu-width 280
        menu-height 300
        adjusted-x (if (> (+ x menu-width) viewport-width)
                     (- viewport-width menu-width 10)
                     x)
        adjusted-y (if (> (+ y menu-height) viewport-height)
                     (- viewport-height menu-height 10)
                     y)
        priority (:priority annotation)
        menu-type (cond
                    (annotations/is-ai-done? annotation) :annotation-ai-done
                    (= priority :AI) :annotation-ai-pending
                    (annotations/is-proposal? annotation) :annotation-proposal
                    :else :annotation-normal)]
    (show-menu! adjusted-x adjusted-y nil nil
                :annotation annotation
                :menu-type menu-type)))
