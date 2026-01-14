(ns tramando.outline
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.annotations :as annotations]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]
            [tramando.editor :as editor]
            [tramando.context-menu :as context-menu]
            [tramando.events :as events]
            [tramando.chat :as chat]
            [tramando.auth :as auth]
            [tramando.store.remote :as remote-store]
            [tramando.versions :as versions]))

;; =============================================================================
;; Chunk Tree Item
;; =============================================================================

(defn- btn-style []
  {:background "transparent"
   :border "none"
   :color (settings/get-color :text-muted)
   :cursor "pointer"
   :padding "0 4px"
   :font-size "0.75rem"})

;; Global state for expanded nodes (collapsed by default)
(defonce expanded-nodes (r/atom #{}))

;; =============================================================================
;; Aspect Priority Thresholds
;; =============================================================================

;; Discrete steps for threshold widget (max 9 for single digit display)
(def threshold-steps [0 1 2 3 5 7 9])

(defn get-threshold
  "Get the current threshold for a container (from settings)"
  [container-id]
  (settings/get-aspect-threshold container-id))

(defn set-threshold!
  "Set the threshold for a container (saves to settings)"
  [container-id value]
  (settings/set-aspect-threshold! container-id value))

(defn- toggle-expanded! [id]
  (swap! expanded-nodes #(if (contains? % id) (disj % id) (conj % id))))

(defn- expanded? [id]
  (contains? @expanded-nodes id))

(defn chunk-expanded?
  "Public function to check if a chunk is expanded in the sidebar.
   Used by radial map to filter visible chapters."
  [chunk-id]
  (contains? @expanded-nodes chunk-id))

(defn- expand-node! [id]
  "Expand a node (add to expanded set)"
  (swap! expanded-nodes conj id))

(defn navigate-to-chunk!
  "Navigate to any chunk: expand its entire ancestor hierarchy and select it."
  [chunk-id]
  (when-let [chunk (model/get-chunk chunk-id)]
    ;; Expand all ancestors in the hierarchy
    (doseq [ancestor-id (model/get-ancestors chunk-id)]
      (expand-node! ancestor-id))
    ;; Select the chunk
    (model/select-chunk! chunk-id)
    true))

(defn navigate-to-aspect!
  "Navigate to an aspect: expand its parent hierarchy and select it.
   aspect-id can be either the aspect ID or its summary name."
  [aspect-id]
  (navigate-to-chunk! aspect-id))

;; Register the navigation functions with events module
(events/set-navigate-to-chunk-fn! navigate-to-chunk!)
(events/set-navigate-to-aspect-fn! navigate-to-aspect!)

;; =============================================================================
;; Filter State and Logic
;; =============================================================================

(defonce filter-state (r/atom {:text ""
                               :case-sensitive false
                               :regex false}))

(defonce filter-input-ref (atom nil))
(defonce debounce-timer (atom nil))

(defn- debounce [f delay-ms]
  "Debounce a function call"
  (when @debounce-timer
    (js/clearTimeout @debounce-timer))
  (reset! debounce-timer
          (js/setTimeout f delay-ms)))

(defn set-filter-text! [text]
  "Set the filter text with debounce"
  (debounce
   #(swap! filter-state assoc :text text)
   300))

(defn toggle-case-sensitive! []
  (swap! filter-state update :case-sensitive not))

(defn toggle-regex! []
  (swap! filter-state update :regex not))

(defn clear-filter! []
  (swap! filter-state assoc :text ""))

(defn focus-filter! []
  (when @filter-input-ref
    (.focus @filter-input-ref)))

(defn- matches-filter?
  "Check if a chunk matches the current filter"
  [chunk filter-text case-sensitive? regex?]
  (let [summary (or (:summary chunk) "")
        content (or (:content chunk) "")
        text-to-search (str summary " " content)]
    (if regex?
      ;; Regex search
      (try
        (let [pattern (js/RegExp. filter-text (if case-sensitive? "g" "gi"))]
          (some? (.match text-to-search pattern)))
        (catch js/Error _
          false)) ; Invalid regex
      ;; Simple text search
      (let [target (if case-sensitive? text-to-search (.toLowerCase text-to-search))
            query (if case-sensitive? filter-text (.toLowerCase filter-text))]
        (.includes target query)))))

(defn- get-chunk-type
  "Get the type/category of a chunk for display"
  [chunk]
  (cond
    (nil? (:parent-id chunk)) :structure
    (= (:parent-id chunk) "personaggi") :personaggi
    (= (:parent-id chunk) "luoghi") :luoghi
    (= (:parent-id chunk) "temi") :temi
    (= (:parent-id chunk) "sequenze") :sequenze
    (= (:parent-id chunk) "timeline") :timeline
    ;; Check if parent is an aspect
    (model/is-aspect-chunk? chunk) :aspect
    :else :structure))

(defn- get-chunk-path
  "Get the path of parent chunks as a readable string (with macros expanded)"
  [chunk]
  (loop [current-id (:parent-id chunk)
         path []]
    (if (or (nil? current-id)
            (model/is-aspect-container? current-id))
      (if (seq path)
        (str/join " > " (reverse path))
        nil)
      (let [parent (first (filter #(= (:id %) current-id) (model/get-chunks)))
            ;; Expand macros in parent summary
            display-name (model/expand-summary-macros (or (:summary parent) "") parent)]
        (recur (:parent-id parent)
               (conj path (if (seq display-name) display-name (:id parent))))))))

(defn- match-location
  "Determine where the match was found: :summary, :content, or :annotation"
  [chunk filter-text case-sensitive? regex?]
  (let [summary (or (:summary chunk) "")
        content (or (:content chunk) "")
        check-match (fn [text]
                      (if regex?
                        (try
                          (let [pattern (js/RegExp. filter-text (if case-sensitive? "g" "gi"))]
                            (some? (.match text pattern)))
                          (catch js/Error _ false))
                        (let [target (if case-sensitive? text (.toLowerCase text))
                              query (if case-sensitive? filter-text (.toLowerCase filter-text))]
                          (.includes target query))))]
    (cond
      (check-match summary) :summary
      ;; Check if match is in annotation
      (and (check-match content)
           (re-find #"\[!(TODO|NOTE|FIX|PROPOSAL):" content)
           (let [stripped (annotations/strip-annotations content)]
             (not (check-match stripped)))) :annotation
      (check-match content) :content
      :else nil)))

(defn- get-filtered-chunks
  "Get all chunks that match the current filter"
  []
  (let [{:keys [text case-sensitive regex]} @filter-state
        min-chars (if regex 1 2)]
    (when (>= (count text) min-chars)
      (let [all-chunks (model/get-chunks)
            ;; Filter out aspect containers
            searchable-chunks (remove #(model/is-aspect-container? (:id %)) all-chunks)]
        (->> searchable-chunks
             (filter #(matches-filter? % text case-sensitive regex))
             (map (fn [chunk]
                    (assoc chunk
                           :chunk-type (get-chunk-type chunk)
                           :chunk-path (get-chunk-path chunk)
                           :match-location (match-location chunk text case-sensitive regex))))
             (sort-by (fn [c]
                        ;; Sort by type then by summary
                        [(case (:chunk-type c)
                           :structure 0
                           :personaggi 1
                           :luoghi 2
                           :temi 3
                           :sequenze 4
                           :timeline 5
                           :aspect 6
                           7)
                         (.toLowerCase (or (:summary c) ""))])))))))

(defn filter-active?
  "Check if the filter is active"
  []
  (let [{:keys [text regex]} @filter-state
        min-chars (if regex 1 2)]
    (>= (count text) min-chars)))

(defn chunk-item
  "Render a single chunk item with its children"
  [{:keys [id summary children] :as chunk}]
  (let [selected-id (model/get-selected-id)
        selected? (= id selected-id)
        has-children? (seq children)
        is-collapsed? (not (expanded? id))
        siblings (model/get-siblings chunk)
        idx (first (keep-indexed #(when (= (:id %2) id) %1) siblings))
        can-move-up? (and idx (pos? idx))
        can-move-down? (and idx (< idx (dec (count siblings))))
        colors (:colors @settings/settings)
        display-summary (model/expand-summary-macros summary chunk)
        ;; Presence: check if others are editing this chunk
        editors (when (remote-store/get-project-id)
                  (remote-store/get-chunk-editors id))
        being-edited? (seq editors)]
    [:div
     [:div.chunk-item
      {:style {:background (when selected? (:accent-muted colors))
               :border-left (str "2px solid " (if selected? (:accent colors) "transparent"))
               :color (if selected? (:accent colors) (:text-muted colors))}
       :on-click (fn [e]
                   (.stopPropagation e)
                   (model/select-chunk! id))
       :on-mouse-over (fn [e]
                        (when-not selected?
                          (set! (.. e -currentTarget -style -background) (:hover colors))))
       :on-mouse-out (fn [e]
                       (when-not selected?
                         (set! (.. e -currentTarget -style -background) "transparent")))}
      (when has-children?
        [:span {:style {:cursor "pointer" :margin-right "4px" :font-size "0.7rem" :color (:text-muted colors)}
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-expanded! id))}
         (if is-collapsed? "▶" "▼")])
      ;; Presence indicator
      (when being-edited?
        [:span {:style {:margin-right "4px" :font-size "0.7rem"}
                :title (str (str/join ", " editors) " sta scrivendo...")}
         "✏️"])
      [:span.chunk-summary {:style {:flex 1 :color (if selected? (:accent colors) (:text colors))}}
       (or (when (seq display-summary) display-summary) (t :no-title))]
      (when selected?
        [:span {:style {:margin-left "auto" :display "flex" :gap "2px"}
                :on-click #(.stopPropagation %)}
         [:button {:style (merge (btn-style) (when-not can-move-up? {:opacity 0.3 :cursor "default"}))
                   :title (t :move-up)
                   :on-click #(when can-move-up? (model/move-chunk-up! id))}
          "↑"]
         [:button {:style (merge (btn-style) (when-not can-move-down? {:opacity 0.3 :cursor "default"}))
                   :title (t :move-down)
                   :on-click #(when can-move-down? (model/move-chunk-down! id))}
          "↓"]])]
     (when (and has-children? (not is-collapsed?))
       [:div.chunk-children
        (doall
         (for [child children]
           ^{:key (:id child)}
           [chunk-item child]))])]))

;; =============================================================================
;; Aspect Item (similar to chunk-item but for aspects)
;; =============================================================================

(defn- aspect-color
  "Get the color for an aspect - neutral by default, accent when selected"
  [selected?]
  (let [colors (:colors @settings/settings)]
    (if selected?
      (:accent colors)
      (:text-muted colors))))

(defn aspect-item
  "Render a single aspect item with its children"
  [{:keys [id summary children] :as chunk}]
  (let [selected-id (model/get-selected-id)
        selected? (= id selected-id)
        has-children? (seq children)
        is-collapsed? (not (expanded? id))
        usage-count (model/aspect-usage-count id)
        siblings (model/get-siblings chunk)
        idx (first (keep-indexed #(when (= (:id %2) id) %1) siblings))
        can-move-up? (and idx (pos? idx))
        can-move-down? (and idx (< idx (dec (count siblings))))
        colors (:colors @settings/settings)
        color (aspect-color selected?)
        display-summary (model/expand-summary-macros summary chunk)]
    [:div
     [:div.chunk-item
      {:style {:background (when selected? (:accent-muted colors))
               :border-left (str "2px solid " (if selected? (:accent colors) "transparent"))
               :color (if selected? (:accent colors) (:text-muted colors))}
       :on-click (fn [e]
                   (.stopPropagation e)
                   (model/select-chunk! id))
       :on-mouse-over (fn [e]
                        (when-not selected?
                          (set! (.. e -currentTarget -style -background) (:hover colors))))
       :on-mouse-out (fn [e]
                       (when-not selected?
                         (set! (.. e -currentTarget -style -background) "transparent")))}
      (when has-children?
        [:span {:style {:cursor "pointer" :margin-right "4px" :font-size "0.7rem" :color (:text-muted colors)}
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-expanded! id))}
         (if is-collapsed? "▶" "▼")])
      [:span.chunk-summary {:style {:flex 1 :color color}}
       (or (when (seq display-summary) display-summary) (t :no-title))]
      (when (pos? usage-count)
        [:span {:style {:margin-left "4px" :color (:text-muted colors) :font-size "0.75rem"}}
         (str "(" usage-count ")")])
      (when selected?
        [:span {:style {:margin-left "auto" :display "flex" :gap "2px"}
                :on-click #(.stopPropagation %)}
         [:button {:style (merge (btn-style) (when-not can-move-up? {:opacity 0.3 :cursor "default"}))
                   :title (t :move-up)
                   :on-click #(when can-move-up? (model/move-chunk-up! id))}
          "↑"]
         [:button {:style (merge (btn-style) (when-not can-move-down? {:opacity 0.3 :cursor "default"}))
                   :title (t :move-down)
                   :on-click #(when can-move-down? (model/move-chunk-down! id))}
          "↓"]])]
     (when (and has-children? (not is-collapsed?))
       [:div.chunk-children
        (doall
         (for [child children]
           ^{:key (:id child)}
           [aspect-item child]))])]))

;; =============================================================================
;; Aspect Container Section
;; =============================================================================

(defn- container-color [_id]
  ;; All aspects use text-muted color (neutral), accent only when selected
  (settings/get-color :text-muted))

(defn- aspect-icon
  "Get icon for aspect container"
  [id]
  (case id
    "personaggi" "👤"
    "luoghi" "📍"
    "temi" "💡"
    "sequenze" "🔗"
    "timeline" "📅"
    "📁"))

(defn- filter-and-sort-aspects
  "Filter aspects by threshold and sort by priority (descending), then alphabetically"
  [aspects threshold]
  (->> aspects
       (filter #(>= (or (:priority %) 0) threshold))
       (sort-by (fn [a] [(- (or (:priority a) 0))
                         (.toLowerCase (or (:summary a) ""))]))))

(defn- threshold-widget
  "Widget to adjust priority threshold for an aspect container"
  [container-id total-count filtered-count]
  (let [colors (:colors @settings/settings)
        threshold (get-threshold container-id)
        current-idx (.indexOf (to-array threshold-steps) threshold)
        can-decrease? (> current-idx 0)
        can-increase? (< current-idx (dec (count threshold-steps)))
        is-filtering? (< filtered-count total-count)
        tooltip (if is-filtering?
                  (str filtered-count "/" total-count " " (t :priority) " ≥" threshold)
                  (str (t :priority) " ≥" threshold))]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "2px"
                   :background "rgba(0,0,0,0.3)"
                   :border-radius "4px"
                   :padding "2px 4px"
                   :font-size "0.75rem"}
           :on-click #(.stopPropagation %)}
     ;; Decrease button
     [:button {:style {:background "transparent"
                       :border "none"
                       :color (if can-decrease? (:text colors) (:text-dim colors))
                       :cursor (if can-decrease? "pointer" "default")
                       :padding "0 3px"
                       :font-size "0.85rem"
                       :line-height 1}
               :disabled (not can-decrease?)
               :title (t :decrease-threshold)
               :on-click (fn [e]
                           (.stopPropagation e)
                           (when can-decrease?
                             (set-threshold! container-id (nth threshold-steps (dec current-idx)))))}
      "−"]
     ;; Current value (with tooltip showing filtered count)
     [:span {:style {:color (if (pos? threshold) (:accent colors) (:text colors))
                     :min-width "14px"
                     :text-align "center"
                     :font-weight "600"
                     :cursor "default"}
             :title tooltip}
      threshold]
     ;; Increase button
     [:button {:style {:background "transparent"
                       :border "none"
                       :color (if can-increase? (:text colors) (:text-dim colors))
                       :cursor (if can-increase? "pointer" "default")
                       :padding "0 3px"
                       :font-size "0.85rem"
                       :line-height 1}
               :disabled (not can-increase?)
               :title (t :increase-threshold)
               :on-click (fn [e]
                           (.stopPropagation e)
                           (when can-increase?
                             (set-threshold! container-id (nth threshold-steps (inc current-idx)))))}
      "+"]]))

(defn aspect-container-section
  "Render an expandable aspect container with its children"
  [{:keys [id summary]}]
  (let [is-collapsed? (not (expanded? id))
        all-children (model/build-aspect-tree id)
        threshold (get-threshold id)
        ;; Filter and sort children by priority
        filtered-children (filter-and-sort-aspects all-children threshold)
        total-count (count all-children)
        filtered-count (count filtered-children)
        colors (:colors @settings/settings)
        color (container-color id)
        help-key (keyword id)
        icon (aspect-icon id)
        ;; Use translated name if available
        display-name (t (keyword id))]
    [:div {:style {:margin-bottom "8px"
                   :background "rgba(255,255,255,0.03)"
                   :border-radius "6px"
                   :padding "6px 8px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :color color
                    :font-size "0.85rem"}}
      ;; Left side: arrow, icon, name
      [:div {:style {:display "flex"
                     :align-items "center"
                     :gap "6px"}}
       [:span {:style {:font-size "0.7rem" :cursor "pointer"}
               :on-click #(toggle-expanded! id)}
        (if is-collapsed? "▶" "▼")]
       [:span {:style {:cursor "pointer"}
               :on-click #(toggle-expanded! id)} icon]
       [:span {:style {:cursor "pointer"}
               :title (t (get help/texts help-key))
               :on-click #(toggle-expanded! id)}
        display-name]]
      ;; Right side: add button + threshold widget
      [:div {:style {:display "flex"
                     :align-items "center"
                     :gap "6px"}}
       ;; Add button (only for owners)
       (when (model/is-project-owner?)
         [:button {:style {:background (:text-muted colors)
                           :border "none"
                           :color (:sidebar colors)
                           :cursor "pointer"
                           :font-size "0.75rem"
                           :font-weight "bold"
                           :width "18px"
                           :height "18px"
                           :line-height "16px"
                           :text-align "center"
                           :padding "0"
                           :border-radius "3px"}
                   :title (str (t :new-aspect) " " display-name)
                   :on-mouse-over (fn [e]
                                    (set! (.. e -target -style -background) (:accent colors)))
                   :on-mouse-out (fn [e]
                                   (set! (.. e -target -style -background) (:text-muted colors)))
                   :on-click (fn [e]
                               (.stopPropagation e)
                               (model/add-aspect! id))}
          "+"])
       ;; Threshold widget
       [threshold-widget id total-count filtered-count]]]
     (when-not is-collapsed?
       (if (seq filtered-children)
         [:div {:style {:margin-left "8px"}}
          (doall
           (for [child filtered-children]
             ^{:key (:id child)}
             [aspect-item child]))]
         [:div {:style {:color (:text-muted colors) :font-size "0.8rem" :padding "4px 8px"}}
          (if (pos? total-count)
            (t :all-filtered)  ;; All aspects are below threshold
            (t :no-aspects))]))]))


;; =============================================================================
;; Annotations Section
;; =============================================================================

(defn- annotation-type-color
  "Get the color for an annotation type"
  [type colors]
  (case type
    :TODO (:accent colors)
    :NOTE (:luoghi colors)
    :FIX (:danger colors)
    :PROPOSAL (:accent colors)
    (:accent colors)))

(defn- annotation-item [annotation]
  (let [colors (:colors @settings/settings)
        type (:type annotation)
        chunk-id (:chunk-id annotation)
        type-color (annotation-type-color type colors)
        path (annotations/get-chunk-path chunk-id)
        selected-text (annotations/get-text annotation)
        display-text (if (> (count selected-text) 50)
                       (str (subs selected-text 0 47) "...")
                       selected-text)
        is-ai-done? (annotations/is-ai-done? annotation)
        is-ai-pending? (annotations/is-ai-pending? annotation)
        is-ai? (or is-ai-pending? is-ai-done?)
        ;; For AI-DONE, show selected alternative if any
        current-selection (or (annotations/get-ai-selection annotation) 0)
        alternatives (or (annotations/get-ai-alternatives annotation) [])
        selected-alt (when (and (pos? current-selection) (<= current-selection (count alternatives)))
                       (nth alternatives (dec current-selection)))
        ;; Get comment and author
        ann-comment (annotations/get-comment annotation)
        author (annotations/get-author annotation)
        author-display (when author (auth/get-cached-display-name author))]
    [:div.annotation-item
     {:style {:border-left-color type-color}
      :on-click (fn []
                  (editor/set-tab! :edit)
                  (model/select-chunk! chunk-id)
                  (editor/navigate-to-annotation! selected-text))}
     ;; Type label with optional AI badge and author
     [:div.annotation-type {:style {:color type-color}}
      (when is-ai?
        [:span {:class (str "ai-badge" (when is-ai-pending? " pending"))}
         (if is-ai-pending? "AI..." "AI")])
      (name type)
      (when author-display
        [:span {:style {:font-weight "normal" :opacity "0.7"}} (str " @" author-display)])]
     ;; Annotation text
     [:div.annotation-text
      [:span {:style {:font-style "italic"}} (str "\"" display-text "\"")]
      (when (and (seq ann-comment) (not is-ai?))
        [:span {:style {:opacity "0.7"}} (str " — " ann-comment)])]
     ;; Location path
     [:div.annotation-location path]
     ;; AI pending message
     (when is-ai-pending?
       [:div {:style {:color (:text-muted colors)
                      :font-size "10px"
                      :font-style "italic"
                      :margin-top "4px"}}
        (t :ai-processing)])
     ;; AI-DONE: show selected alternative preview
     (when selected-alt
       [:div {:style {:color (:accent colors)
                      :font-size "11px"
                      :margin-top "4px"
                      :font-style "italic"
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
        (str "→ " (if (> (count selected-alt) 40)
                    (str (subs selected-alt 0 37) "...")
                    selected-alt))])]))

(defn- annotation-type-section [type items]
  (let [colors (:colors @settings/settings)
        type-color (annotation-type-color type colors)
        is-collapsed? (not (expanded? (str "ann-" (name type))))
        type-label (name type)]
    [:div {:style {:margin-bottom "8px"}}
     [:div.annotation-type-header {:style {:color type-color}
                                   :on-click #(toggle-expanded! (str "ann-" (name type)))}
      [:span.collapse-arrow (if is-collapsed? "▶" "▼")]
      (str type-label " (" (count items) ")")]
     (when-not is-collapsed?
       [:div {:style {:margin-left "8px"}}
        (if (empty? items)
          [:div.annotations-empty (t :none-fem)]
          (doall
           (for [[idx item] (map-indexed vector items)]
             ^{:key (str type "-" (:chunk-id item) "-" idx)}
             [annotation-item item])))])]))

(defn annotations-section []
  (let [_chunks @model/app-state ; subscribe to changes
        {:keys [TODO NOTE FIX PROPOSAL]} (annotations/get-all-annotations)
        total (+ (count TODO) (count NOTE) (count FIX) (count PROPOSAL))
        is-collapsed? (not (expanded? "annotations-section"))]
    [:div.annotations-section
     [:div.annotations-header {:on-click #(toggle-expanded! "annotations-section")}
      [:h2.section-title
       [:span.collapse-arrow (if is-collapsed? "▶" "▼")]
       (t :annotations)
       [help/help-icon :annotazioni {:below? true}]]
      (when (pos? total)
        [:span.count-badge total])]
     (when-not is-collapsed?
       [:div
        (when (pos? (count PROPOSAL))
          [annotation-type-section :PROPOSAL PROPOSAL])
        (when (pos? (count TODO))
          [annotation-type-section :TODO TODO])
        (when (pos? (count FIX))
          [annotation-type-section :FIX FIX])
        (when (pos? (count NOTE))
          [annotation-type-section :NOTE NOTE])
        (when (zero? total)
          [:div.annotations-empty (t :no-annotations)])])]))

;; =============================================================================
;; Right Panel (Annotations)
;; =============================================================================

(defn toggle-right-panel! []
  (swap! model/right-panel-collapsed? not))

(defn annotations-panel
  "Standalone annotations panel for the right side"
  []
  (let [collapsed? @model/right-panel-collapsed?
        colors (:colors @settings/settings)
        _chunks @model/app-state ; subscribe to changes
        {:keys [TODO NOTE FIX PROPOSAL]} (annotations/get-all-annotations)
        total (+ (count TODO) (count NOTE) (count FIX) (count PROPOSAL))]
    [:aside {:class (str "right-panel" (when collapsed? " collapsed"))
             :style {:background (:sidebar colors)
                     :display "flex"
                     :flex-direction "column"}}
     ;; Header
     [:div.panel-header
      [:span.panel-title (t :annotations)]
      (when (pos? total)
        [:span.panel-badge total])
      [:button.panel-collapse-btn
       {:on-click toggle-right-panel!
        :title (if collapsed? (t :expand) (t :collapse))}
       (if collapsed? "◀" "▶")]]
     ;; Collapsed state icon
     (when collapsed?
       [:div.collapsed-icon {:on-click toggle-right-panel!}
        "📝"
        (when (pos? total)
          [:span {:style {:font-size "10px"
                          :background (:accent colors)
                          :color "white"
                          :padding "1px 4px"
                          :border-radius "6px"
                          :margin-top "4px"}}
           total])])
     ;; Content (flex-grow to push chat to bottom)
     [:div.panel-content {:style {:flex 1 :overflow-y "auto"}}
      (when (pos? (count PROPOSAL))
        [annotation-type-section :PROPOSAL PROPOSAL])
      (when (pos? (count TODO))
        [annotation-type-section :TODO TODO])
      (when (pos? (count FIX))
        [annotation-type-section :FIX FIX])
      (when (pos? (count NOTE))
        [annotation-type-section :NOTE NOTE])
      (when (zero? total)
        [:div.annotations-empty (t :no-annotations)])]
     ;; Chat panel (only in collaborative mode, at bottom)
     (when-not collapsed?
       [chat/chat-panel])]))

;; =============================================================================
;; Filter Input Component
;; =============================================================================

(defn- type-color
  "Get color for a chunk type - all use text-muted, accent only for selected"
  [_chunk-type]
  ;; All types use text-muted for neutral appearance
  (settings/get-color :text-muted))

(defn- type-label
  "Get translated label for a chunk type"
  [chunk-type]
  (case chunk-type
    :structure (t :structure)
    :personaggi (t :personaggi)
    :luoghi (t :luoghi)
    :temi (t :temi)
    :sequenze (t :sequenze)
    :timeline (t :timeline)
    :aspect (t :aspects)
    ""))

(defn filter-input []
  (let [local-text (r/atom (:text @filter-state))]
    (fn []
      (let [colors (:colors @settings/settings)
            {:keys [case-sensitive regex]} @filter-state]
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "6px"
                       :padding "8px 0"
                       :margin-bottom "8px"
                       :border-bottom (str "1px solid " (:border colors))}}
         ;; Search icon
         [:span {:style {:color (:text-muted colors)
                         :font-size "0.9rem"}}
          "🔍"]
         ;; Input field
         [:input {:type "text"
                  :ref #(reset! filter-input-ref %)
                  :value @local-text
                  :placeholder (t :filter-placeholder)
                  :style {:flex 1
                          :background "transparent"
                          :border (str "1px solid " (:border colors))
                          :border-radius "4px"
                          :color (:text colors)
                          :padding "6px 10px"
                          :font-size "0.85rem"
                          :outline "none"
                          :min-width 0}
                  :on-change (fn [e]
                               (let [v (.. e -target -value)]
                                 (reset! local-text v)
                                 (set-filter-text! v)))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Escape")
                                   (reset! local-text "")
                                   (clear-filter!)
                                   (.blur (.-target e))))}]
         ;; Case sensitive toggle
         [:button {:style {:background (if case-sensitive (:accent colors) "transparent")
                           :color (if case-sensitive "white" (:text-muted colors))
                           :border (str "1px solid " (if case-sensitive (:accent colors) (:border colors)))
                           :border-radius "3px"
                           :padding "4px 6px"
                           :font-size "0.75rem"
                           :font-weight "600"
                           :cursor "pointer"
                           :min-width "24px"}
                   :title (t :case-sensitive)
                   :on-click toggle-case-sensitive!}
          "Aa"]
         ;; Regex toggle
         [:button {:style {:background (if regex (:accent colors) "transparent")
                           :color (if regex "white" (:text-muted colors))
                           :border (str "1px solid " (if regex (:accent colors) (:border colors)))
                           :border-radius "3px"
                           :padding "4px 6px"
                           :font-size "0.75rem"
                           :font-family "monospace"
                           :cursor "pointer"
                           :min-width "24px"}
                   :title (t :regex)
                   :on-click toggle-regex!}
         ".*"]
         ;; Help icon (use native title tooltip to avoid clipping)
         [:span.help-icon {:title (t :help-filter)} "?"]]))))

;; =============================================================================
;; Filtered Results View
;; =============================================================================

(defn- filter-result-item
  "Render a single search result"
  [{:keys [id summary chunk-type chunk-path match-location] :as chunk}]
  (let [colors (:colors @settings/settings)
        selected? (= id (model/get-selected-id))
        color (type-color chunk-type)
        display-summary (model/expand-summary-macros (or summary "") chunk)
        {:keys [text case-sensitive regex]} @filter-state]
    [:div {:style {:padding "8px 10px"
                   :margin-bottom "4px"
                   :background (if selected? (:editor-bg colors) "transparent")
                   :border-left (str "3px solid " color)
                   :border-radius "0 4px 4px 0"
                   :cursor "pointer"}
           :on-click (fn []
                       (model/select-chunk! id)
                       ;; Open editor search with filter settings
                       (js/setTimeout
                        #(editor/show-editor-search!
                          {:text text
                           :case-sensitive case-sensitive
                           :regex regex})
                        100))
           :on-mouse-over (fn [e]
                            (when-not selected?
                              (set! (.. e -currentTarget -style -background) (:editor-bg colors))))
           :on-mouse-out (fn [e]
                           (when-not selected?
                             (set! (.. e -currentTarget -style -background) "transparent")))}
     ;; Title row
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "6px"}}
      [:span {:style {:color color
                      :font-size "0.7rem"
                      :font-weight "600"
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
       (type-label chunk-type)]
      [:span {:style {:color (:text colors)
                      :font-size "0.85rem"
                      :flex 1
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
       (or (when (seq display-summary) display-summary) (t :no-title))]
      ;; Match location indicator
      (when (= match-location :content)
        [:span {:style {:color (:text-muted colors) :font-size "0.7rem"}}
         (t :in-content)])
      (when (= match-location :annotation)
        [:span {:style {:color (:text-muted colors) :font-size "0.7rem"}}
         (t :in-annotation)])]
     ;; Path row
     (when chunk-path
       [:div {:style {:color (:text-muted colors)
                      :font-size "0.75rem"
                      :margin-top "2px"
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
        chunk-path])]))

(defn filtered-results-view []
  (let [results (get-filtered-chunks)
        colors (:colors @settings/settings)
        count (count results)]
    [:div {:style {:padding "8px 0"}}
     ;; Results count
     [:div {:style {:color (:text-muted colors)
                    :font-size "0.8rem"
                    :margin-bottom "12px"
                    :font-style "italic"}}
      (cond
        (zero? count) (t :no-results-filter)
        (= count 1) (t :one-result)
        :else (t :n-results count))]
     ;; Results list
     (when (pos? count)
       [:div
        (doall
         (for [chunk results]
           ^{:key (:id chunk)}
           [filter-result-item chunk]))])]))

;; =============================================================================
;; Outline Panel
;; =============================================================================

(defn outline-panel
  "The outline tree view showing all chunks"
  []
  (let [tree (model/get-structural-tree)
        _chunks @model/app-state ; subscribe to changes
        _filter @filter-state ; subscribe to filter changes
        colors (:colors @settings/settings)
        current-theme (:theme @settings/settings)
        use-texture (or (get colors :background-texture) (= current-theme :tessuto))
        is-filtering (filter-active?)]
    [:div.outline-panel {:style {:position "relative"
                                 :border-right (str "1px solid " (:border colors))
                                 :background (if use-texture
                                               (str "linear-gradient(" (:sidebar colors) "e6," (:sidebar colors) "e6), url('/images/logo_tramando_transparent.png')")
                                               (:sidebar colors))
                                 :background-size "cover"
                                 :background-position "top left"}}
     ;; Content wrapper (needs position relative to sit above overlay)
     [:div {:style {:position "relative" :z-index 1 :height "100%" :overflow "visible" :padding "inherit"}}

      ;; Filter input (always visible)
      [filter-input]

      ;; Show filtered results OR normal tree view
      (if is-filtering
        ;; Filtered results view
        [filtered-results-view]

        ;; Normal tree view
        [:<>
         ;; STRUTTURA section
         [:h2 {:style {:color (:text-muted colors)
                       :display "flex"
                       :align-items "center"
                       :font-size "11px"
                       :font-weight "600"
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :margin-bottom "8px"}}
          (t :structure)
          [help/help-icon :struttura {:below? true}]]
         ;; Project title with versions button
         [:div {:style {:display "flex"
                        :align-items "center"
                        :justify-content "space-between"
                        :padding "4px 0 8px 0"
                        :margin-bottom "8px"
                        :border-bottom (str "1px solid " (:border colors))}}
          [:span {:style {:color (:accent colors)
                          :font-size "1rem"
                          :font-weight "500"}}
           (model/get-title)]
          ;; Versions button (only in remote mode)
          (when (remote-store/get-project-id)
            [:button {:style {:background "transparent"
                              :border "none"
                              :color (:text-muted colors)
                              :cursor "pointer"
                              :font-size "0.9rem"
                              :padding "2px 6px"
                              :border-radius "4px"}
                      :title "Cronologia versioni"
                      :on-click versions/toggle-versions!
                      :on-mouse-over (fn [e]
                                       (set! (.. e -target -style -background) (:hover colors)))
                      :on-mouse-out (fn [e]
                                      (set! (.. e -target -style -background) "transparent"))}
             "🕐"])]
         (if (seq tree)
           [:div.outline-tree
            (doall
             (for [chunk tree]
               ^{:key (:id chunk)}
               [chunk-item chunk]))]
           [:div.outline-empty
            {:style {:color (:text-muted colors) :font-size "0.85rem" :padding "8px 0"}}
            (t :no-chunk)])

         ;; Show "+ Nuovo Chunk" only if user can create at root level
         (when (model/can-create-chunk-at? nil)
           [:div {:style {:margin-top "12px"}}
            [:button
             {:style {:background "transparent"
                      :color (:text-muted colors)
                      :border (str "1px dashed " (:border colors))
                      :padding "6px 12px"
                      :border-radius "4px"
                      :cursor "pointer"
                      :width "100%"
                      :font-size "0.8rem"
                      :transition "all 0.15s"}
              :on-mouse-over (fn [e]
                               (set! (.. e -target -style -borderColor) (:accent colors))
                               (set! (.. e -target -style -color) (:accent colors)))
              :on-mouse-out (fn [e]
                              (set! (.. e -target -style -borderColor) (:border colors))
                              (set! (.. e -target -style -color) (:text-muted colors)))
              :on-click (fn []
                          (model/add-chunk!))}
             (str "+ " (t :new-chunk))]])

         ;; ASPETTI section
         [:div {:style {:margin-top "24px" :padding-top "16px" :border-top (str "1px solid " (:border colors))}}
          [:h2 {:style {:color (:text-muted colors)
                        :font-size "11px"
                        :font-weight "600"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :margin-bottom "12px"}} (t :aspects)]
          (doall
           (for [container model/aspect-containers]
             ^{:key (:id container)}
             [aspect-container-section container]))]])]]))

;; =============================================================================
;; Outline Stats
;; =============================================================================

(defn outline-stats
  "Display stats about the document"
  []
  (let [chunks (model/get-chunks)
        total (count chunks)
        roots (count (filter #(nil? (:parent-id %)) chunks))
        colors (:colors @settings/settings)]
    [:div.outline-stats
     {:style {:font-size "0.8rem" :color (:text-muted colors) :padding "8px 0"}}
     (str total " chunk" (when (not= total 1) "s")
          " · " roots " root" (when (not= roots 1) "s"))]))
