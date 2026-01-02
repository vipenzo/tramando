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
            [tramando.events :as events]))

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

(defn- toggle-expanded! [id]
  (swap! expanded-nodes #(if (contains? % id) (disj % id) (conj % id))))

(defn- expanded? [id]
  (contains? @expanded-nodes id))

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
  "Get the path of parent chunks as a readable string"
  [chunk]
  (loop [current-id (:parent-id chunk)
         path []]
    (if (or (nil? current-id)
            (model/is-aspect-container? current-id))
      (if (seq path)
        (str/join " > " (reverse path))
        nil)
      (let [parent (first (filter #(= (:id %) current-id) (model/get-chunks)))]
        (recur (:parent-id parent)
               (conj path (or (:summary parent) (:id parent))))))))

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
        display-summary (model/expand-summary-macros summary chunk)]
    [:div
     [:div.chunk-item
      {:style {:background (when selected? (:editor-bg colors))
               :border-left (str "2px solid " (if selected? (:accent colors) "transparent"))}
       :on-click (fn [e]
                   (.stopPropagation e)
                   (model/select-chunk! id))}
      (when has-children?
        [:span {:style {:cursor "pointer" :margin-right "4px" :font-size "0.7rem" :color (:text-muted colors)}
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-expanded! id))}
         (if is-collapsed? "▶" "▼")])
      [:span.chunk-summary {:style {:flex 1 :color (:text colors)}}
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
  "Get the color for an aspect based on its parent container"
  [chunk]
  (let [colors (:colors @settings/settings)
        parent-id (:parent-id chunk)]
    (case parent-id
      "personaggi" (:personaggi colors)
      "luoghi" (:luoghi colors)
      "temi" (:temi colors)
      "sequenze" (:sequenze colors)
      "timeline" (:timeline colors)
      (:accent colors))))

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
        color (aspect-color chunk)
        display-summary (model/expand-summary-macros summary chunk)]
    [:div
     [:div.chunk-item
      {:style {:background (when selected? (:editor-bg colors))
               :border-left (str "2px solid " (if selected? color "transparent"))}
       :on-click (fn [e]
                   (.stopPropagation e)
                   (model/select-chunk! id))}
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

(defn- container-color [id]
  (let [colors (:colors @settings/settings)]
    (case id
      "personaggi" (:personaggi colors)
      "luoghi" (:luoghi colors)
      "temi" (:temi colors)
      "sequenze" (:sequenze colors)
      "timeline" (:timeline colors)
      (:accent colors))))

(defn aspect-container-section
  "Render an expandable aspect container with its children"
  [{:keys [id summary]}]
  (let [is-collapsed? (not (expanded? id))
        children (model/build-aspect-tree id)
        colors (:colors @settings/settings)
        color (container-color id)
        help-key (keyword id)
        ;; Use translated name if available
        display-name (t (keyword id))]
    [:div {:style {:margin-bottom "8px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :cursor "pointer"
                    :padding "4px 0"
                    :color color
                    :font-size "0.85rem"}
            :on-click #(toggle-expanded! id)}
      [:span {:style {:margin-right "4px" :font-size "0.7rem"}}
       (if is-collapsed? "▶" "▼")]
      display-name
      [help/help-icon help-key {:below? true}]]
     (when-not is-collapsed?
       (if (seq children)
         [:div {:style {:margin-left "8px"}}
          (doall
           (for [child children]
             ^{:key (:id child)}
             [aspect-item child]))]
         [:div {:style {:color (:text-muted colors) :font-size "0.8rem" :padding "4px 8px"}}
          (t :no-aspects)]))]))

;; =============================================================================
;; New Aspect Dropdown
;; =============================================================================

(defn new-aspect-dropdown []
  (let [open? (r/atom false)]
    (fn []
      (let [colors (:colors @settings/settings)]
        [:div {:style {:position "relative" :margin-top "8px"}}
         [:button
          {:style {:background "transparent"
                   :color (:accent colors)
                   :border (str "1px solid " (:accent colors))
                   :padding "8px 16px"
                   :border-radius "4px"
                   :cursor "pointer"
                   :width "100%"
                   :font-size "0.9rem"
                   :display "flex"
                   :justify-content "space-between"
                   :align-items "center"}
           :on-click #(swap! open? not)}
          [:span (t :new-aspect)]
          [:span {:style {:font-size "0.7rem"}} (if @open? "▲" "▼")]]
         (when @open?
           [:div {:style {:position "absolute"
                          :top "100%"
                          :left 0
                          :right 0
                          :background (:sidebar colors)
                          :border (str "1px solid " (:border colors))
                          :border-radius "4px"
                          :margin-top "4px"
                          :z-index 100}}
            (doall
             (for [{:keys [id]} model/aspect-containers]
               ^{:key id}
               [:div {:style {:padding "8px 12px"
                              :cursor "pointer"
                              :font-size "0.85rem"
                              :color (container-color id)}
                      :on-mouse-over (fn [e] (set! (.. e -target -style -background) (:editor-bg colors)))
                      :on-mouse-out (fn [e] (set! (.. e -target -style -background) "transparent"))
                      :on-click (fn []
                                  (model/add-aspect! id)
                                  (reset! open? false))}
                (t (keyword id))]))])]))))

;; =============================================================================
;; Annotations Section
;; =============================================================================

(defn- annotation-type-color [type]
  (case type
    :TODO "#f5a623"
    :NOTE "#2196f3"
    :FIX "#f44336"
    :PROPOSAL "#9c27b0"
    "#888"))

(defn- format-priority
  "Format priority for display. Returns '1' or '20.5' or 'AI' or 'AI-DONE' or '--' if nil."
  [priority]
  (cond
    (= priority :AI) "AI"
    (= priority :AI-DONE) "AI-DONE"
    (number? priority)
    (if (= priority (js/Math.floor priority))
      (str (int priority))  ; integer: no decimals
      (str priority))       ; decimal: show as-is
    :else "--"))

(defn- annotation-item [{:keys [chunk-id selected-text comment type priority]}]
  (let [colors (:colors @settings/settings)
        path (annotations/get-chunk-path chunk-id)
        display-text (if (> (count selected-text) 35)
                       (str (subs selected-text 0 32) "...")
                       selected-text)
        priority-str (format-priority priority)
        is-ai-done? (= priority :AI-DONE)
        is-ai-pending? (= priority :AI)
        is-ai? (or is-ai-pending? is-ai-done?)
        ;; For AI-DONE, show selected alternative if any
        ai-data (when is-ai-done? (annotations/parse-ai-data comment))
        current-selection (or (:sel ai-data) 0)
        alternatives (or (:alts ai-data) [])
        selected-alt (when (and (pos? current-selection) (<= current-selection (count alternatives)))
                       (nth alternatives (dec current-selection)))]
    [:div {:style {:padding "6px 8px"
                   :margin-bottom "4px"
                   :background (:editor-bg colors)
                   :border-radius "3px"
                   :border-left (str "3px solid " (annotation-type-color type))
                   :cursor "pointer"
                   :font-size "0.8rem"}
           :on-click (fn []
                       (editor/set-tab! :edit)  ;; Switch to edit mode to see annotations
                       (model/select-chunk! chunk-id)
                       (editor/navigate-to-annotation! selected-text))}
     ;; Header row with path and badge
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "8px"
                    :margin-bottom "4px"}}
      ;; Priority column - show AI badge for AI annotations
      (if is-ai?
        [:span {:style {:background (if is-ai-pending?
                                      (:text-muted colors)
                                      (:accent colors))
                        :color "white"
                        :font-size "0.65rem"
                        :padding "1px 4px"
                        :border-radius "2px"
                        :font-weight "600"}}
         (if is-ai-pending? "AI..." "AI")]
        (when priority
          [:span {:style {:color (:accent colors)
                          :font-family "monospace"
                          :font-size "0.75rem"}}
           priority-str]))
      ;; Path
      [:span {:style {:color (:text-muted colors)
                      :font-size "0.7rem"
                      :flex 1}}
       path]]
     ;; Selected text
     [:div {:style {:color (:text colors)
                    :overflow "hidden"
                    :text-overflow "ellipsis"
                    :white-space "nowrap"}}
      [:span {:style {:font-style "italic"}} (str "\"" display-text "\"")]
      (when (and (seq comment) (not is-ai?))
        [:span {:style {:color (:text-muted colors)}} (str " — " comment)])]
     ;; AI pending message
     (when is-ai-pending?
       [:div {:style {:color (:text-muted colors)
                      :font-size "0.7rem"
                      :font-style "italic"
                      :margin-top "4px"}}
        (t :ai-processing)])
     ;; AI-DONE: show selected alternative preview
     (when selected-alt
       [:div {:style {:color (:accent colors)
                      :font-size "0.75rem"
                      :margin-top "4px"
                      :font-style "italic"
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
        (str "→ " (if (> (count selected-alt) 40)
                    (str (subs selected-alt 0 37) "...")
                    selected-alt))])]))

(defn- annotation-type-section [type items]
  (let [is-collapsed? (not (expanded? (str "ann-" (name type))))
        colors (:colors @settings/settings)
        type-label (name type)
        type-color (annotation-type-color type)]
    [:div {:style {:margin-bottom "8px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :cursor "pointer"
                    :padding "4px 0"
                    :color type-color
                    :font-size "0.85rem"}
            :on-click #(toggle-expanded! (str "ann-" (name type)))}
      [:span {:style {:margin-right "4px" :font-size "0.7rem"}}
       (if is-collapsed? "▶" "▼")]
      (str type-label " (" (count items) ")")]
     (when-not is-collapsed?
       [:div {:style {:margin-left "8px"}}
        (if (empty? items)
          [:div {:style {:color (:text-muted colors) :font-size "0.75rem" :padding "4px"}}
           (t :none-fem)]
          (doall
           (for [[idx item] (map-indexed vector items)]
             ^{:key (str type "-" (:chunk-id item) "-" idx)}
             [annotation-item item])))])]))

(defn annotations-section []
  (let [_chunks @model/app-state ; subscribe to changes
        {:keys [TODO NOTE FIX PROPOSAL]} (annotations/get-all-annotations)
        total (+ (count TODO) (count NOTE) (count FIX) (count PROPOSAL))
        is-collapsed? (not (expanded? "annotations-section"))
        colors (:colors @settings/settings)]
    [:div {:style {:margin-top "16px" :padding-top "16px" :border-top (str "1px solid " (:border colors))}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :margin-bottom "8px"
                    :cursor "pointer"}
            :on-click #(toggle-expanded! "annotations-section")}
      [:h2 {:style {:color (:text colors) :margin 0 :display "flex" :align-items "center" :gap "8px"}}
       [:span {:style {:font-size "0.7rem" :color (:text-muted colors)}}
        (if is-collapsed? "▶" "▼")]
       (t :annotations)
       [help/help-icon :annotazioni {:below? true}]]
      (when (pos? total)
        [:span {:style {:background (:accent colors)
                        :color "white"
                        :padding "2px 8px"
                        :border-radius "10px"
                        :font-size "0.75rem"}}
         total])]
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
          [:div {:style {:color (:text-muted colors) :font-size "0.8rem" :padding "8px 0"}}
           (t :no-annotations)])])]))

;; =============================================================================
;; Filter Input Component
;; =============================================================================

(defn- type-color
  "Get color for a chunk type"
  [chunk-type]
  (let [colors (:colors @settings/settings)]
    (case chunk-type
      :structure (:structure colors)
      :personaggi (:personaggi colors)
      :luoghi (:luoghi colors)
      :temi (:temi colors)
      :sequenze (:sequenze colors)
      :timeline (:timeline colors)
      :aspect (:accent colors)
      (:text-muted colors))))

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
         [:h2 {:style {:color (:text colors) :display "flex" :align-items "center"}}
          (t :structure)
          [help/help-icon :struttura {:below? true}]]
         ;; Project title
         [:div {:style {:color (:accent colors)
                        :font-size "1rem"
                        :font-weight "500"
                        :padding "4px 0 8px 0"
                        :margin-bottom "8px"
                        :border-bottom (str "1px solid " (:border colors))}}
          (model/get-title)]
         (if (seq tree)
           [:div.outline-tree
            (doall
             (for [chunk tree]
               ^{:key (:id chunk)}
               [chunk-item chunk]))]
           [:div.outline-empty
            {:style {:color (:text-muted colors) :font-size "0.85rem" :padding "8px 0"}}
            (t :no-chunk)])

         [:div {:style {:margin-top "12px"}}
          [:button
           {:style {:background (:accent colors)
                    :color "white"
                    :border "none"
                    :padding "8px 16px"
                    :border-radius "4px"
                    :cursor "pointer"
                    :width "100%"
                    :font-size "0.9rem"}
            :on-click (fn []
                        (model/add-chunk!))}
           (t :new-chunk)]]

         ;; ASPETTI section
         [:div {:style {:margin-top "24px" :padding-top "16px" :border-top (str "1px solid " (:border colors))}}
          [:h2 {:style {:color (:text colors)}} (t :aspects)]
          (doall
           (for [container model/aspect-containers]
             ^{:key (:id container)}
             [aspect-container-section container]))
          [new-aspect-dropdown]]

         ;; ANNOTAZIONI section
         [annotations-section]])]]))

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
