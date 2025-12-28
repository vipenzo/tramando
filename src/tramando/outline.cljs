(ns tramando.outline
  (:require [reagent.core :as r]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.annotations :as annotations]
            [tramando.help :as help]
            [tramando.i18n :as i18n :refer [t]]))

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

;; Global state for collapsed nodes
(defonce collapsed-nodes (r/atom #{}))

(defn- toggle-collapsed! [id]
  (swap! collapsed-nodes #(if (contains? % id) (disj % id) (conj % id))))

(defn- collapsed? [id]
  (contains? @collapsed-nodes id))

(defn chunk-item
  "Render a single chunk item with its children"
  [{:keys [id summary children] :as chunk}]
  (let [selected-id (model/get-selected-id)
        selected? (= id selected-id)
        has-children? (seq children)
        is-collapsed? (collapsed? id)
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
                            (toggle-collapsed! id))}
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
        is-collapsed? (collapsed? id)
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
                            (toggle-collapsed! id))}
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
  (let [is-collapsed? (collapsed? id)
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
            :on-click #(toggle-collapsed! id)}
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
    "#888"))

(defn- format-priority
  "Format priority for display. Returns '1' or '20.5' or '--' if nil."
  [priority]
  (if priority
    (if (= priority (js/Math.floor priority))
      (str (int priority))  ; integer: no decimals
      (str priority))       ; decimal: show as-is
    "--"))

(defn- annotation-item [{:keys [chunk-id selected-text comment type priority]}]
  (let [colors (:colors @settings/settings)
        path (annotations/get-chunk-path chunk-id)
        display-text (if (> (count selected-text) 35)
                       (str (subs selected-text 0 32) "...")
                       selected-text)
        priority-str (format-priority priority)]
    [:div {:style {:padding "6px 8px"
                   :margin-bottom "4px"
                   :background (:editor-bg colors)
                   :border-radius "3px"
                   :border-left (str "3px solid " (annotation-type-color type))
                   :cursor "pointer"
                   :font-size "0.8rem"
                   :display "flex"
                   :gap "8px"}
           :on-click #(model/select-chunk! chunk-id)}
     ;; Priority column (fixed width)
     [:span {:style {:color (if priority (:accent colors) (:text-muted colors))
                     :font-family "monospace"
                     :font-size "0.75rem"
                     :min-width "24px"
                     :text-align "right"}}
      priority-str]
     ;; Content column
     [:div {:style {:flex 1 :overflow "hidden"}}
      [:div {:style {:color (:text-muted colors)
                     :font-size "0.7rem"
                     :margin-bottom "2px"}}
       path]
      [:div {:style {:color (:text colors)
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}}
       [:span {:style {:font-style "italic"}} (str "\"" display-text "\"")]
       (when (seq comment)
         [:span {:style {:color (:text-muted colors)}} (str " — " comment)])]]]))

(defn- annotation-type-section [type items]
  (let [is-collapsed? (collapsed? (str "ann-" (name type)))
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
            :on-click #(toggle-collapsed! (str "ann-" (name type)))}
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
        {:keys [TODO NOTE FIX]} (annotations/get-all-annotations)
        total (+ (count TODO) (count NOTE) (count FIX))
        is-collapsed? (collapsed? "annotations-section")
        colors (:colors @settings/settings)]
    [:div {:style {:margin-top "16px" :padding-top "16px" :border-top (str "1px solid " (:border colors))}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :margin-bottom "8px"
                    :cursor "pointer"}
            :on-click #(toggle-collapsed! "annotations-section")}
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
;; Outline Panel
;; =============================================================================

(defn outline-panel
  "The outline tree view showing all chunks"
  []
  (let [tree (model/get-structural-tree)
        _chunks @model/app-state ; subscribe to changes
        colors (:colors @settings/settings)
        current-theme (:theme @settings/settings)
        use-texture (= current-theme :tessuto)]
    [:div.outline-panel {:style {:position "relative"
                                 :border-right (str "1px solid " (:border colors))
                                 :background-color (:sidebar colors)
                                 :background-image (when use-texture "url('/images/logo_tramando.png')")
                                 :background-size "cover"
                                 :background-position "top left"}}
     ;; Semi-transparent overlay for texture (high opacity for readability)
     (when use-texture
       [:div {:style {:position "absolute"
                      :top 0 :left 0 :right 0 :bottom 0
                      :background (str (:sidebar colors) "e2")
                      :pointer-events "none"}}])
     ;; Content wrapper (needs position relative to sit above overlay)
     [:div {:style {:position "relative" :z-index 1 :height "100%" :overflow-y "auto" :padding "inherit"}}
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

     ;; + Figlio button
     (when-let [selected (model/get-selected-chunk)]
       (when-not (model/is-aspect-container? (:id selected))
         [:div {:style {:margin-top "16px" :padding-top "16px" :border-top (str "1px solid " (:border colors))}}
          [:button
           {:style {:background "transparent"
                    :color (:accent colors)
                    :border (str "1px solid " (:accent colors))
                    :padding "8px 16px"
                    :border-radius "4px"
                    :cursor "pointer"
                    :width "100%"
                    :font-size "0.9rem"}
            :on-click (fn []
                        (model/add-chunk! :parent-id (:id selected)))}
           (t :add-child (str (subs (:summary selected) 0 (min 20 (count (:summary selected))))
                                     (when (> (count (:summary selected)) 20) "...")))]]))

     ;; ANNOTAZIONI section
     [annotations-section]]]))

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
