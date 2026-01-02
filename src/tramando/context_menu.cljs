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
            [tramando.events :as events]))

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

(defn show-edit-modal!
  "Show the annotation edit modal"
  [annotation]
  (let [priority (:priority annotation)
        ;; Convert priority to string for the select (handles nil, numbers, strings, keywords)
        priority-str (cond
                       (nil? priority) ""
                       (keyword? priority) (name priority)
                       (number? priority) (str priority)
                       :else (str priority))]
    (reset! edit-modal-state {:visible true
                              :annotation annotation
                              :priority priority-str
                              :comment (or (:comment annotation) "")})))

(defn hide-edit-modal!
  "Hide the annotation edit modal"
  []
  (reset! edit-modal-state {:visible false
                            :annotation nil
                            :priority ""
                            :comment ""}))

(defn- build-annotation-markup
  "Build the full annotation markup string"
  [type selected-text priority comment]
  (str "[!" (str/upper-case (name type)) ":" selected-text ":" (or priority "") ":" (or comment "") "]"))

(defn save-annotation-edit!
  "Save the edited annotation"
  []
  (let [{:keys [annotation priority comment]} @edit-modal-state
        chunk-id (:chunk-id annotation)
        ann-type (:type annotation)
        selected-text (:selected-text annotation)
        ;; Convert old priority to string for markup building
        raw-priority (:priority annotation)
        old-priority-str (cond
                           (nil? raw-priority) ""
                           (keyword? raw-priority) (name raw-priority)
                           (number? raw-priority) (str raw-priority)
                           :else (str raw-priority))
        old-comment (or (:comment annotation) "")
        ;; Build old and new markup
        old-markup (build-annotation-markup ann-type selected-text old-priority-str old-comment)
        new-markup (build-annotation-markup ann-type selected-text priority comment)]
    (when (not= old-markup new-markup)
      ;; Use editor dispatch for undo support
      (if (events/replace-text-in-editor! old-markup new-markup)
        (events/show-toast! (t :annotation-updated))
        ;; Fallback: direct model update
        (when-let [chunk (model/get-chunk chunk-id)]
          (let [content (:content chunk)
                new-content (str/replace-first content old-markup new-markup)]
            (when (not= content new-content)
              (model/update-chunk! chunk-id {:content new-content})
              (events/refresh-editor!)
              (events/show-toast! (t :annotation-updated)))))))
    (hide-edit-modal!)))

(defn annotation-edit-modal
  "Modal for editing annotation priority and comment"
  []
  (let [{:keys [visible annotation priority comment]} @edit-modal-state
        colors (:colors @settings/settings)]
    (when visible
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 10002}
             ;; Use mousedown to avoid closing when text selection drag ends outside
             :on-mouse-down #(hide-edit-modal!)}
       [:div {:style {:background (:sidebar colors)
                      :border-radius "8px"
                      :padding "20px"
                      :min-width "320px"
                      :max-width "450px"
                      :box-shadow "0 4px 20px rgba(0,0,0,0.4)"}
              :on-mouse-down #(.stopPropagation %)}
        ;; Title
        [:h3 {:style {:margin "0 0 16px 0"
                      :color (:text colors)
                      :font-size "1.1rem"}}
         (t :annotation-edit-title)]

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
          (:selected-text annotation)]]

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

        ;; Comment field
        [:div {:style {:margin-bottom "20px"}}
         [:label {:style {:display "block"
                          :color (:text-muted colors)
                          :font-size "0.85rem"
                          :margin-bottom "4px"}}
          (t :annotation-comment)]
         [:input {:type "text"
                  :value comment
                  :on-change #(swap! edit-modal-state assoc :comment (.. % -target -value))
                  :placeholder (t :comment-placeholder)
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
  "Handle wrapping selected text with an annotation"
  [annotation-type]
  (when @on-wrap-annotation
    (@on-wrap-annotation annotation-type
                         (:chunk @menu-state)
                         (:selected-text @menu-state)))
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

(defn- menu-section-header
  "Section header in menu"
  [label]
  [:div {:style {:padding "8px 12px"
                 :font-size "11px"
                 :font-weight "600"
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"
                 :color (settings/get-color :accent)
                 :border-bottom (str "1px solid " (settings/get-color :border))}}
   label])

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
                     :color "#f5a623"
                     :action #(handle-wrap-annotation! "TODO")}
                    {:id :note
                     :label "NOTE"
                     :icon "●"
                     :color "#2196f3"
                     :action #(handle-wrap-annotation! "NOTE")}
                    {:id :fix
                     :label "FIX"
                     :icon "●"
                     :color "#f44336"
                     :action #(handle-wrap-annotation! "FIX")}]}])

(defn- tone-submenu
  "Submenu for tone options"
  [parent-rect]
  [submenu {:parent-rect parent-rect
            :items [{:id :tone-dark
                     :label (t :ai-tone-dark)
                     :action #(handle-action! :tone-dark)}
                    {:id :tone-light
                     :label (t :ai-tone-light)
                     :action #(handle-action! :tone-light)}
                    {:id :tone-formal
                     :label (t :ai-tone-formal)
                     :action #(handle-action! :tone-formal)}
                    {:id :tone-casual
                     :label (t :ai-tone-casual)
                     :action #(handle-action! :tone-casual)}
                    {:id :tone-poetic
                     :label (t :ai-tone-poetic)
                     :action #(handle-action! :tone-poetic)}]}])

(defn- aspects-submenu
  "Submenu for updating linked aspects"
  [parent-rect chunk]
  (let [linked-aspects (templates/get-linked-aspects chunk)]
    [submenu {:parent-rect parent-rect
              :items (for [aspect linked-aspects]
                       {:id (:id aspect)
                        :label (or (:summary aspect) (:id aspect))
                        :action #(handle-action! :update-aspect :aspect-id (:id aspect))})}]))

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
        selected-text (:selected-text annotation)
        chunk-id (:chunk-id annotation)
        ;; Read fresh data from chunk to get current selection state
        fresh-chunk (model/get-chunk chunk-id)
        fresh-content (:content fresh-chunk)
        ;; Find the annotation in fresh content and parse its data
        fresh-annotation (first (filter #(= (:selected-text %) selected-text)
                                        (annotations/parse-annotations fresh-content)))
        fresh-comment (or (:comment fresh-annotation) (:comment annotation))
        ai-data (annotations/parse-ai-data fresh-comment)
        alternatives (or (:alts ai-data) [])
        current-sel (or (:sel ai-data) 0)
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
  [{:keys [x y annotation chunk]}]
  (let [menu-width 200
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
     [menu-item {:label (t :edit-annotation)
                 :on-click (fn []
                             (show-edit-modal! annotation)
                             (hide-menu!))}]
     [menu-separator]
     [menu-item {:label (t :delete-annotation)
                 :color "#f44336"
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
                 :color "#f44336"
                 :on-click (fn []
                             (ai-handlers/cancel-ai-annotation! chunk-id selected-text)
                             (hide-menu!))}]]))

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

(defn- selection-context-menu
  "Context menu for selected text"
  [{:keys [x y chunk selected-text open-submenu]}]
  (let [menu-width 220
        linked-aspects (when chunk (templates/get-linked-aspects chunk))
        has-linked-aspects? (seq linked-aspects)
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

     ;; Annotation section
     [:div {:style {:position "relative"}
            :on-mouse-enter #(open-submenu! :annotation)
            :on-mouse-leave #(when (= open-submenu :annotation)
                               (close-submenu!))}
      [menu-item {:label (t :add-annotation)
                  :has-submenu? true
                  :submenu-key :annotation}]
      (when (= open-submenu :annotation)
        [annotation-submenu (calculate-submenu-rect x y 0 menu-width)])]

     ;; AI section (only if configured)
     (when ai-configured?
       [:<>
        [menu-separator]
        [menu-section-header (t :ai-assistant)]

        ;; Main AI actions
        [menu-item {:label (t :ai-expand)
                    :on-click #(handle-action! :expand)}]
        [menu-item {:label (t :ai-rephrase)
                    :on-click #(handle-action! :rephrase)}]

        ;; Tone submenu
        [:div {:style {:position "relative"}
               :on-mouse-enter #(open-submenu! :tone)
               :on-mouse-leave #(when (= open-submenu :tone)
                                  (close-submenu!))}
         [menu-item {:label (t :ai-tone)
                     :has-submenu? true
                     :submenu-key :tone}]
         (when (= open-submenu :tone)
           [tone-submenu (calculate-submenu-rect x y 4 menu-width)])]

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

        ;; Extract info submenu (only if chunk has linked aspects and text is selected)
        (when (and has-linked-aspects? (seq selected-text))
          [:<>
           [menu-separator]
           [:div {:style {:position "relative"}
                  :on-mouse-enter #(open-submenu! :aspects)
                  :on-mouse-leave #(when (= open-submenu :aspects)
                                     (close-submenu!))}
            [menu-item {:label (t :ai-extract-info)
                        :has-submenu? true
                        :submenu-key :aspects}]
            (when (= open-submenu :aspects)
              [aspects-submenu (calculate-submenu-rect x y 10 menu-width) chunk])]])

        [menu-separator]

        ;; Ask (free input)
        [menu-item {:label (t :ai-ask)
                    :on-click #(handle-action! :ask)}]])]))

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

          :aspect-link
          [aspect-link-menu {:x x :y y :aspect-link aspect-link}]

          ;; Default: selection menu
          [selection-context-menu {:x x :y y :chunk chunk :selected-text selected-text :open-submenu open-submenu}])])

     ;; Annotation edit modal (rendered independently)
     [annotation-edit-modal]]))

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
                    :else :annotation-normal)]
    (show-menu! adjusted-x adjusted-y nil nil
                :annotation annotation
                :menu-type menu-type)))
