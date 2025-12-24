(ns tramando.outline
  (:require [reagent.core :as r]
            [tramando.model :as model]))

;; =============================================================================
;; Chunk Tree Item
;; =============================================================================

(def ^:private btn-style
  {:background "transparent"
   :border "none"
   :color "#888"
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
        can-move-down? (and idx (< idx (dec (count siblings))))]
    [:div
     [:div.chunk-item
      {:class (when selected? "selected")
       :on-click (fn [e]
                   (.stopPropagation e)
                   (model/select-chunk! id))}
      (when has-children?
        [:span {:style {:cursor "pointer" :margin-right "4px" :font-size "0.7rem" :color "#888"}
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-collapsed! id))}
         (if is-collapsed? "▶" "▼")])
      [:span.chunk-id (subs id 0 (min 8 (count id)))]
      " "
      [:span.chunk-summary {:style {:flex 1}} (or (when (seq summary) summary) "(senza titolo)")]
      (when selected?
        [:span {:style {:margin-left "auto" :display "flex" :gap "2px"}
                :on-click #(.stopPropagation %)}
         [:button {:style (merge btn-style (when-not can-move-up? {:opacity 0.3 :cursor "default"}))
                   :title "Sposta su"
                   :on-click #(when can-move-up? (model/move-chunk-up! id))}
          "↑"]
         [:button {:style (merge btn-style (when-not can-move-down? {:opacity 0.3 :cursor "default"}))
                   :title "Sposta giù"
                   :on-click #(when can-move-down? (model/move-chunk-down! id))}
          "↓"]])]
     (when (and has-children? (not is-collapsed?))
       [:div.chunk-children
        (for [child children]
          ^{:key (:id child)}
          [chunk-item child])])]))

;; =============================================================================
;; Aspect Item (similar to chunk-item but for aspects)
;; =============================================================================

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
        can-move-down? (and idx (< idx (dec (count siblings))))]
    [:div
     [:div.chunk-item
      {:class (when selected? "selected")
       :on-click (fn [e]
                   (.stopPropagation e)
                   (model/select-chunk! id))}
      (when has-children?
        [:span {:style {:cursor "pointer" :margin-right "4px" :font-size "0.7rem" :color "#888"}
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-collapsed! id))}
         (if is-collapsed? "▶" "▼")])
      [:span.chunk-summary {:style {:flex 1}} (or (when (seq summary) summary) "(senza titolo)")]
      (when (pos? usage-count)
        [:span {:style {:margin-left "4px" :color "#888" :font-size "0.75rem"}}
         (str "(" usage-count ")")])
      (when selected?
        [:span {:style {:margin-left "auto" :display "flex" :gap "2px"}
                :on-click #(.stopPropagation %)}
         [:button {:style (merge btn-style (when-not can-move-up? {:opacity 0.3 :cursor "default"}))
                   :title "Sposta su"
                   :on-click #(when can-move-up? (model/move-chunk-up! id))}
          "↑"]
         [:button {:style (merge btn-style (when-not can-move-down? {:opacity 0.3 :cursor "default"}))
                   :title "Sposta giù"
                   :on-click #(when can-move-down? (model/move-chunk-down! id))}
          "↓"]])]
     (when (and has-children? (not is-collapsed?))
       [:div.chunk-children
        (for [child children]
          ^{:key (:id child)}
          [aspect-item child])])]))

;; =============================================================================
;; Aspect Container Section
;; =============================================================================

(defn aspect-container-section
  "Render an expandable aspect container with its children"
  [{:keys [id summary]}]
  (let [is-collapsed? (collapsed? id)
        children (model/build-aspect-tree id)]
    [:div {:style {:margin-bottom "8px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :cursor "pointer"
                    :padding "4px 0"
                    :color "#888"
                    :font-size "0.85rem"}
            :on-click #(toggle-collapsed! id)}
      [:span {:style {:margin-right "4px" :font-size "0.7rem"}}
       (if is-collapsed? "▶" "▼")]
      summary]
     (when-not is-collapsed?
       (if (seq children)
         [:div {:style {:margin-left "8px"}}
          (for [child children]
            ^{:key (:id child)}
            [aspect-item child])]
         [:div {:style {:color "#555" :font-size "0.8rem" :padding "4px 8px"}}
          "Nessuno"]))]))

;; =============================================================================
;; New Aspect Dropdown
;; =============================================================================

(defn new-aspect-dropdown []
  (let [open? (r/atom false)]
    (fn []
      [:div {:style {:position "relative" :margin-top "8px"}}
       [:button
        {:style {:background "transparent"
                 :color "#e94560"
                 :border "1px solid #e94560"
                 :padding "8px 16px"
                 :border-radius "4px"
                 :cursor "pointer"
                 :width "100%"
                 :font-size "0.9rem"
                 :display "flex"
                 :justify-content "space-between"
                 :align-items "center"}
         :on-click #(swap! open? not)}
        [:span "+ Nuovo aspetto"]
        [:span {:style {:font-size "0.7rem"}} (if @open? "▲" "▼")]]
       (when @open?
         [:div {:style {:position "absolute"
                        :top "100%"
                        :left 0
                        :right 0
                        :background "#16213e"
                        :border "1px solid #0f3460"
                        :border-radius "4px"
                        :margin-top "4px"
                        :z-index 100}}
          (for [{:keys [id summary]} model/aspect-containers]
            ^{:key id}
            [:div {:style {:padding "8px 12px"
                           :cursor "pointer"
                           :font-size "0.85rem"}
                   :on-mouse-over (fn [e] (set! (.. e -target -style -background) "#0f3460"))
                   :on-mouse-out (fn [e] (set! (.. e -target -style -background) "transparent"))
                   :on-click (fn []
                               (model/add-aspect! id)
                               (reset! open? false))}
             summary])])])))

;; =============================================================================
;; Outline Panel
;; =============================================================================

(defn outline-panel
  "The outline tree view showing all chunks"
  []
  (let [tree (model/get-structural-tree)
        _chunks @model/app-state] ; subscribe to changes
    [:div.outline-panel
     ;; STRUTTURA section
     [:h2 "Struttura"]
     (if (seq tree)
       [:div.outline-tree
        (for [chunk tree]
          ^{:key (:id chunk)}
          [chunk-item chunk])]
       [:div.outline-empty
        {:style {:color "#666" :font-size "0.85rem" :padding "8px 0"}}
        "Nessun chunk."])

     [:div {:style {:margin-top "12px"}}
      [:button
       {:style {:background "#e94560"
                :color "white"
                :border "none"
                :padding "8px 16px"
                :border-radius "4px"
                :cursor "pointer"
                :width "100%"
                :font-size "0.9rem"}
        :on-click (fn []
                    (model/add-chunk!))}
       "+ Nuovo Chunk"]]

     ;; ASPETTI section
     [:div {:style {:margin-top "24px" :padding-top "16px" :border-top "1px solid #0f3460"}}
      [:h2 "Aspetti"]
      (for [container model/aspect-containers]
        ^{:key (:id container)}
        [aspect-container-section container])
      [new-aspect-dropdown]]

     ;; + Figlio button at the bottom
     (when-let [selected (model/get-selected-chunk)]
       (when-not (model/is-aspect-container? (:id selected))
         [:div {:style {:margin-top "16px" :padding-top "16px" :border-top "1px solid #0f3460"}}
          [:button
           {:style {:background "transparent"
                    :color "#e94560"
                    :border "1px solid #e94560"
                    :padding "8px 16px"
                    :border-radius "4px"
                    :cursor "pointer"
                    :width "100%"
                    :font-size "0.9rem"}
            :on-click (fn []
                        (model/add-chunk! :parent-id (:id selected)))}
           (str "+ Figlio di \"" (subs (:summary selected) 0 (min 20 (count (:summary selected))))
                (when (> (count (:summary selected)) 20) "...") "\"")]]))]))

;; =============================================================================
;; Outline Stats
;; =============================================================================

(defn outline-stats
  "Display stats about the document"
  []
  (let [chunks (model/get-chunks)
        total (count chunks)
        roots (count (filter #(nil? (:parent-id %)) chunks))]
    [:div.outline-stats
     {:style {:font-size "0.8rem" :color "#666" :padding "8px 0"}}
     (str total " chunk" (when (not= total 1) "s")
          " · " roots " root" (when (not= roots 1) "s"))]))
