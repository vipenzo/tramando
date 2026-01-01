(ns tramando.chunk-selector
  "Modal tree selector with fuzzy filtering for aspects and parent selection"
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]))

;; =============================================================================
;; Fuzzy Match Algorithm
;; =============================================================================

(defn fuzzy-match?
  "Returns true if query matches text in a fuzzy way.
   Characters must appear in order but not necessarily contiguous.
   'fia ya' matches 'Sofia Okoya'"
  [query text]
  (when (and query text)
    (let [query (str/lower-case (str/replace (str query) #"\s+" ""))
          text (str/lower-case (str text))]
      (if (empty? query)
        true
        (loop [q-chars (seq query)
               t-index 0]
          (cond
            (empty? q-chars) true
            (>= t-index (count text)) false
            (= (first q-chars) (nth text t-index))
            (recur (rest q-chars) (inc t-index))
            :else
            (recur q-chars (inc t-index))))))))

(defn fuzzy-match-indices
  "Returns the indices of matched characters for highlighting.
   Returns nil if no match."
  [query text]
  (when (and query text (not (empty? query)))
    (let [query (str/lower-case (str/replace (str query) #"\s+" ""))
          text-lower (str/lower-case (str text))]
      (loop [q-chars (seq query)
             t-index 0
             indices []]
        (cond
          (empty? q-chars) (when (seq indices) (set indices))
          (>= t-index (count text-lower)) nil
          (= (first q-chars) (nth text-lower t-index))
          (recur (rest q-chars) (inc t-index) (conj indices t-index))
          :else
          (recur q-chars (inc t-index) indices))))))

(defn highlight-match
  "Returns hiccup with matched characters highlighted"
  [text indices]
  (if (and indices (seq indices))
    (into [:span]
          (map-indexed
           (fn [i char]
             (if (contains? indices i)
               ^{:key i} [:strong {:style {:color (settings/get-color :accent)}} char]
               ^{:key i} [:span char]))
           (str text)))
    [:span text]))

;; =============================================================================
;; Tree Filtering
;; =============================================================================

(defn filter-tree
  "Filters tree items by query, returns items that match or have matching children.
   Also marks which items matched directly."
  [items query]
  (if (or (nil? query) (empty? (str/trim (str query))))
    ;; No filter, return all items as-is
    items
    ;; Filter items
    (reduce
     (fn [acc item]
       (let [title-matches? (fuzzy-match? query (:title item))
             filtered-children (when (:children item)
                                 (filter-tree (:children item) query))
             has-matching-children? (seq filtered-children)]
         (if (or title-matches? has-matching-children?)
           (conj acc (-> item
                         (assoc :matches? title-matches?)
                         (assoc :match-indices (when title-matches?
                                                 (fuzzy-match-indices query (:title item))))
                         (assoc :expanded true) ;; Auto-expand when filtering
                         (assoc :children filtered-children)))
           acc)))
     []
     items)))

(defn count-descendants
  "Counts all selectable descendants of an item"
  [item]
  (if-let [children (:children item)]
    (reduce + (count children) (map count-descendants children))
    0))

(defn collect-expanded-ids
  "Collects IDs of items that have :expanded true (set by filter-tree)"
  [items]
  (reduce (fn [acc item]
            (let [acc (if (:expanded item)
                        (conj acc (:id item))
                        acc)]
              (if (:children item)
                (into acc (collect-expanded-ids (:children item)))
                acc)))
          #{}
          items))

;; =============================================================================
;; Flattened List for Navigation
;; =============================================================================

(defn flatten-visible-items
  "Flattens tree to a list of visible items for keyboard navigation.
   Returns [{:item item :depth depth :path path}]"
  [items expanded-set depth path]
  (reduce
   (fn [acc item]
     (let [item-path (conj path (:id item))
           current {:item item :depth depth :path item-path}
           children-visible? (and (:children item)
                                  (contains? expanded-set (:id item)))]
       (if children-visible?
         (into (conj acc current)
               (flatten-visible-items (:children item) expanded-set (inc depth) item-path))
         (conj acc current))))
   []
   items))

;; =============================================================================
;; Tree Item Component
;; =============================================================================

(defn tree-item-view
  "Renders a single tree item"
  [{:keys [item depth selected? expanded? on-select on-toggle query]}]
  (let [has-children? (seq (:children item))
        is-category? (= :category (:type item))
        child-count (count-descendants item)
        match-indices (:match-indices item)
        colors {:bg (settings/get-color :background)
                :hover (settings/get-color :sidebar)
                :selected (settings/get-color :accent)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)}]
    [:div.tree-item
     {:style {:padding "8px 12px"
              :padding-left (str (+ 12 (* depth 24)) "px")
              :cursor "pointer"
              :display "flex"
              :align-items "center"
              :gap "8px"
              :background (if selected?
                            (str (:selected colors) "22")
                            "transparent")
              :border-left (when selected?
                             (str "3px solid " (:selected colors)))}
      :on-click (fn [e]
                  (.stopPropagation e)
                  (if (and has-children? is-category?)
                    (on-toggle (:id item))
                    (on-select item)))}
     ;; Expand/collapse toggle
     (when has-children?
       [:span.tree-toggle
        {:style {:width "16px"
                 :color (:muted colors)
                 :font-size "10px"
                 :user-select "none"}
         :on-click (fn [e]
                     (.stopPropagation e)
                     (on-toggle (:id item)))}
        (if expanded? "▼" "▶")])
     ;; Spacer when no toggle
     (when-not has-children?
       [:span {:style {:width "16px"}}])
     ;; Title with optional highlight
     [:span {:style {:flex 1
                     :color (if is-category? (:muted colors) (:text colors))
                     :font-weight (if is-category? "600" "normal")}}
      (if match-indices
        (highlight-match (:title item) match-indices)
        (:title item))]
     ;; Child count for categories
     (when (and is-category? (pos? child-count))
       [:span {:style {:color (:muted colors)
                       :font-size "0.85em"}}
        (str "(" child-count ")")])]))

;; =============================================================================
;; Tree View Component
;; =============================================================================

(defn tree-view
  "Renders the tree structure"
  [{:keys [items expanded-ids selected-id on-select on-toggle query]}]
  [:div.tree-container
   (doall
    (for [item items]
      ^{:key (or (:id item) "__root__")}
      [:div
       [tree-item-view {:item item
                        :depth 0
                        :selected? (= (:id item) selected-id)
                        :expanded? (contains? expanded-ids (:id item))
                        :on-select on-select
                        :on-toggle on-toggle
                        :query query}]
       ;; Render children if expanded (use expanded-ids only)
       (when (and (:children item)
                  (contains? expanded-ids (:id item)))
         (doall
          (for [child (:children item)]
            ^{:key (:id child)}
            [:div
             [tree-item-view {:item child
                              :depth 1
                              :selected? (= (:id child) selected-id)
                              :expanded? (contains? expanded-ids (:id child))
                              :on-select on-select
                              :on-toggle on-toggle
                              :query query}]
             ;; Render grandchildren if expanded (use expanded-ids only)
             (when (and (:children child)
                        (contains? expanded-ids (:id child)))
               (doall
                (for [grandchild (:children child)]
                  ^{:key (:id grandchild)}
                  [tree-item-view {:item grandchild
                                   :depth 2
                                   :selected? (= (:id grandchild) selected-id)
                                   :expanded? (contains? expanded-ids (:id grandchild))
                                   :on-select on-select
                                   :on-toggle on-toggle
                                   :query query}])))])))]))])

;; =============================================================================
;; Main Modal Component
;; =============================================================================

(defn chunk-selector-modal
  "Modal component for selecting chunks from a tree structure.
   Props:
   - :title - Modal title
   - :items - Tree structure of items
   - :on-select - Callback when item is selected (fn [item])
   - :on-cancel - Callback when cancelled
   - :filter-placeholder - Placeholder for search field
   - :show-categories-as-selectable - If true, categories can be selected"
  [{:keys [title items on-select on-cancel filter-placeholder show-categories-as-selectable]
    :or {filter-placeholder (t :search-placeholder)
         show-categories-as-selectable false}}]
  (let [;; Collect initially expanded items from tree
        collect-expanded (fn collect-expanded [items]
                           (reduce (fn [acc item]
                                     (let [acc (if (:expanded item)
                                                 (conj acc (:id item))
                                                 acc)]
                                       (if (:children item)
                                         (into acc (collect-expanded (:children item)))
                                         acc)))
                                   #{}
                                   items))
        query (r/atom "")
        expanded-ids (r/atom (collect-expanded items))
        selected-id (r/atom nil)
        search-ref (r/atom nil)]

    (r/create-class
     {:component-did-mount
      (fn [_]
        ;; Focus search input on mount
        (when @search-ref
          (.focus @search-ref)))

      :reagent-render
      (fn [{:keys [title items on-select on-cancel filter-placeholder show-categories-as-selectable]
            :or {filter-placeholder (t :search-placeholder)
                 show-categories-as-selectable false}}]
        (let [filtered-items (filter-tree items @query)
              ;; When there's a query, auto-expand branches with matches
              auto-expanded (when (seq (str/trim (str @query)))
                              (collect-expanded-ids filtered-items))
              effective-expanded-ids (if auto-expanded
                                       (into @expanded-ids auto-expanded)
                                       @expanded-ids)
              flat-items (flatten-visible-items filtered-items effective-expanded-ids 0 [])
              selectable-items (if show-categories-as-selectable
                                 flat-items
                                 (filter #(not= :category (-> % :item :type)) flat-items))
              colors {:bg (settings/get-color :background)
                      :sidebar (settings/get-color :sidebar)
                      :text (settings/get-color :text)
                      :muted (settings/get-color :text-muted)
                      :border (settings/get-color :border)
                      :accent (settings/get-color :accent)
                      :editor-bg (settings/get-color :editor-bg)}

              ;; Find current selection index
              current-idx (when @selected-id
                            (first (keep-indexed
                                    (fn [i x] (when (= (:id (:item x)) @selected-id) i))
                                    selectable-items)))

              move-selection (fn [direction]
                               (let [items selectable-items
                                     n (count items)]
                                 (when (pos? n)
                                   (let [new-idx (cond
                                                   (nil? current-idx)
                                                   (if (= direction :down) 0 (dec n))

                                                   (= direction :down)
                                                   (min (inc current-idx) (dec n))

                                                   :else
                                                   (max (dec current-idx) 0))]
                                     (reset! selected-id (-> items (nth new-idx) :item :id))))))

              select-current (fn []
                               (when-let [sel-id @selected-id]
                                 (when-let [item (some #(when (= (:id (:item %)) sel-id) (:item %)) selectable-items)]
                                   (on-select item))))

              toggle-item (fn [id]
                            (swap! expanded-ids
                                   (fn [ids]
                                     (if (contains? ids id)
                                       (disj ids id)
                                       (conj ids id)))))

              handle-keydown (fn [e]
                               (case (.-key e)
                                 "ArrowDown" (do (.preventDefault e) (move-selection :down))
                                 "ArrowUp" (do (.preventDefault e) (move-selection :up))
                                 "Enter" (do (.preventDefault e) (select-current))
                                 "Escape" (do (.preventDefault e) (on-cancel))
                                 nil))]

          [:div.chunk-selector-backdrop
           {:style {:position "fixed"
                    :inset 0
                    :background "rgba(0,0,0,0.4)"
                    :z-index 999
                    :display "flex"
                    :align-items "center"
                    :justify-content "center"}
            ;; Use mousedown instead of click to avoid closing when
            ;; dragging text selection ends outside the modal
            :on-mouse-down on-cancel}

           [:div.chunk-selector-modal
            {:style {:background (:bg colors)
                     :border-radius "12px"
                     :box-shadow "0 8px 32px rgba(0,0,0,0.2)"
                     :width "420px"
                     :max-height "70vh"
                     :display "flex"
                     :flex-direction "column"
                     :overflow "hidden"}
             :on-mouse-down #(.stopPropagation %)
             :on-key-down handle-keydown}

            ;; Header with title
            [:div.chunk-selector-header
             {:style {:padding "16px"
                      :border-bottom (str "1px solid " (:border colors))}}
             [:h3 {:style {:margin 0
                           :font-size "1rem"
                           :font-weight "600"
                           :color (:text colors)}}
              title]]

            ;; Search input
            [:div {:style {:padding "12px 16px"
                           :border-bottom (str "1px solid " (:border colors))}}
             [:input {:type "text"
                      :ref #(reset! search-ref %)
                      :placeholder filter-placeholder
                      :value @query
                      :on-change #(do
                                    (reset! query (-> % .-target .-value))
                                    (reset! selected-id nil))
                      :on-key-down handle-keydown
                      :style {:width "100%"
                              :padding "10px 12px"
                              :border (str "1px solid " (:border colors))
                              :border-radius "6px"
                              :font-size "14px"
                              :background (:editor-bg colors)
                              :color (:text colors)
                              :outline "none"}}]]

            ;; Tree content
            [:div.chunk-selector-tree
             {:style {:flex 1
                      :overflow-y "auto"
                      :padding "8px 0"
                      :min-height "200px"
                      :max-height "400px"}}
             (if (empty? filtered-items)
               [:div {:style {:padding "24px"
                              :text-align "center"
                              :color (:muted colors)}}
                (t :no-results)]
               [tree-view {:items filtered-items
                           :expanded-ids effective-expanded-ids
                           :selected-id @selected-id
                           :on-select on-select
                           :on-toggle toggle-item
                           :query @query}])]

            ;; Footer with cancel button
            [:div.chunk-selector-footer
             {:style {:padding "12px 16px"
                      :border-top (str "1px solid " (:border colors))
                      :text-align "right"}}
             [:button {:style {:background "transparent"
                               :color (:muted colors)
                               :border (str "1px solid " (:border colors))
                               :padding "8px 16px"
                               :border-radius "6px"
                               :cursor "pointer"
                               :font-size "0.9rem"}
                       :on-click on-cancel}
              (t :cancel)]]]]))})))

;; =============================================================================
;; State for Global Modal
;; =============================================================================

(defonce selector-state (r/atom nil))

(defn open-selector!
  "Opens the chunk selector modal with given options"
  [opts]
  (reset! selector-state opts))

(defn close-selector!
  "Closes the chunk selector modal"
  []
  (reset! selector-state nil))

(defn selector-modal
  "Global selector modal component - place this in the root of your app"
  []
  (when-let [opts @selector-state]
    [chunk-selector-modal
     (assoc opts
            :on-cancel (fn []
                         (when-let [cancel-fn (:on-cancel opts)]
                           (cancel-fn))
                         (close-selector!))
            :on-select (fn [item]
                         (when-let [select-fn (:on-select opts)]
                           (select-fn item))
                         (close-selector!)))]))
