(ns tramando.versions
  "Version history UI component.
   Shows git-based version history for collaborative projects.
   Allows viewing, tagging, and forking from versions."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [tramando.api :as api]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.i18n :as i18n :refer [t]]
            [tramando.store.remote :as remote-store]
            [tramando.events :as events]))

;; =============================================================================
;; State
;; =============================================================================

(defonce versions-state (r/atom {:visible? false
                                  :loading? false
                                  :versions []
                                  :error nil
                                  :selected-ref nil
                                  :diff-lines nil
                                  :creating-tag? false
                                  :tag-name ""
                                  :tag-message ""}))

;; =============================================================================
;; Simple Line-based Diff Algorithm
;; =============================================================================

(defn- compute-lcs-table
  "Compute LCS length table for two sequences."
  [old-lines new-lines]
  (let [m (count old-lines)
        n (count new-lines)
        ;; Use a mutable JS array for performance
        table (js/Array. (inc m))]
    ;; Initialize table
    (dotimes [i (inc m)]
      (aset table i (js/Array. (inc n)))
      (aset (aget table i) 0 0))
    (dotimes [j (inc n)]
      (aset (aget table 0) j 0))
    ;; Fill table
    (dotimes [i m]
      (dotimes [j n]
        (let [ii (inc i)
              jj (inc j)]
          (if (= (nth old-lines i) (nth new-lines j))
            (aset (aget table ii) jj (inc (aget (aget table i) j)))
            (aset (aget table ii) jj
                  (max (aget (aget table i) jj)
                       (aget (aget table ii) j)))))))
    table))

(defn- backtrack-diff
  "Backtrack through LCS table to produce diff lines."
  [table old-lines new-lines]
  (loop [i (count old-lines)
         j (count new-lines)
         result []]
    (cond
      (and (zero? i) (zero? j))
      (vec (reverse result))

      (zero? i)
      (recur i (dec j) (conj result {:type :added :text (nth new-lines (dec j))}))

      (zero? j)
      (recur (dec i) j (conj result {:type :removed :text (nth old-lines (dec i))}))

      (= (nth old-lines (dec i)) (nth new-lines (dec j)))
      (recur (dec i) (dec j) (conj result {:type :unchanged :text (nth old-lines (dec i))}))

      (> (aget (aget table (dec i)) j)
         (aget (aget table i) (dec j)))
      (recur (dec i) j (conj result {:type :removed :text (nth old-lines (dec i))}))

      :else
      (recur i (dec j) (conj result {:type :added :text (nth new-lines (dec j))})))))

(defn- compute-diff
  "Compute diff between old and new content.
   Returns vector of {:type :added/:removed/:unchanged :text string}"
  [old-content new-content]
  (let [old-lines (str/split-lines (or old-content ""))
        new-lines (str/split-lines (or new-content ""))]
    (if (= old-lines new-lines)
      [{:type :unchanged :text "(nessuna differenza)"}]
      (let [table (compute-lcs-table old-lines new-lines)]
        (backtrack-diff table old-lines new-lines)))))

(defn- filter-diff-changes
  "Filter diff to show only changes (with some context)."
  [diff-lines max-context]
  (let [indexed (map-indexed vector diff-lines)
        change-indices (set (keep (fn [[idx line]]
                                    (when (not= (:type line) :unchanged) idx))
                                  indexed))
        ;; Include context lines around changes
        context-indices (set (mapcat (fn [idx]
                                       (range (max 0 (- idx max-context))
                                              (min (count diff-lines) (+ idx max-context 1))))
                                     change-indices))]
    (->> indexed
         (filter (fn [[idx _]] (context-indices idx)))
         (map second)
         vec)))

;; =============================================================================
;; API Interactions
;; =============================================================================

(defn load-versions! []
  (when-let [project-id (remote-store/get-project-id)]
    (swap! versions-state assoc :loading? true :error nil)
    (-> (api/list-versions project-id)
        (.then (fn [result]
                 (if (:ok result)
                   (swap! versions-state assoc
                          :versions (get-in result [:data :versions] [])
                          :loading? false)
                   (swap! versions-state assoc
                          :error (or (:error result) "Failed to load versions")
                          :loading? false))))
        (.catch (fn [err]
                  (swap! versions-state assoc
                         :error (str "Error: " err)
                         :loading? false))))))

(defn load-version-preview! [ref]
  (when-let [project-id (remote-store/get-project-id)]
    (swap! versions-state assoc :selected-ref ref :diff-lines nil)
    (-> (api/get-version-content project-id ref)
        (.then (fn [result]
                 (when (:ok result)
                   (let [version-content (get-in result [:data :content])
                         current-content (model/serialize-file (model/get-chunks) (model/get-metadata))
                         diff (compute-diff version-content current-content)
                         ;; Show only changes with 1 line of context
                         filtered (filter-diff-changes diff 1)]
                     (swap! versions-state assoc
                            :diff-lines (if (seq filtered) filtered diff))))))
        (.catch (fn [_]
                  (swap! versions-state assoc :diff-lines nil))))))

(defn create-tag! []
  (when-let [project-id (remote-store/get-project-id)]
    (let [{:keys [tag-name tag-message]} @versions-state]
      (when (seq tag-name)
        (-> (api/create-version-tag! project-id tag-name tag-message)
            (.then (fn [result]
                     (if (:ok result)
                       (do
                         (swap! versions-state assoc
                                :creating-tag? false
                                :tag-name ""
                                :tag-message "")
                         (load-versions!))
                       (events/show-alert! (or (:error result) "Failed to create tag")
                                           {:title (t :error)}))))
            (.catch (fn [err]
                      (events/show-alert! (str "Error: " err)
                                          {:title (t :error)}))))))))

(defn restore-version! [ref]
  "Restore a version by reloading the content from that version."
  (when-let [project-id (remote-store/get-project-id)]
    (events/show-confirm!
     "Ripristinare questa versione? Le modifiche non salvate andranno perse."
     {:title "Ripristina versione"
      :danger? true
      :confirm-text "Ripristina"
      :on-confirm
      (fn []
        (-> (api/get-version-content project-id ref)
            (.then (fn [result]
                     (when (:ok result)
                       (let [content (get-in result [:data :content])]
                         ;; Reload the content and mark as modified to trigger save
                         (model/reload-from-remote! content (remote-store/get-project-name))
                         ;; Trigger a sync to save the restored version
                         (when-let [callback @model/on-modified-callback]
                           (callback))
                         (swap! versions-state assoc :visible? false)))))
            (.catch (fn [err]
                      (events/show-alert! (str "Error restoring version: " err)
                                          {:title (t :error)})))))})))

(defn fork-version! [ref]
  "Create a new project from a specific version."
  (when-let [project-id (remote-store/get-project-id)]
    (events/show-prompt!
     "Nome del nuovo progetto:"
     {:title "Fork versione"
      :default-value (str (remote-store/get-project-name) " (fork)")
      :submit-text "Crea"
      :on-submit
      (fn [new-name]
        (-> (api/fork-version! project-id ref new-name)
            (.then (fn [result]
                     (if (:ok result)
                       (events/show-alert! (str "Progetto '" new-name "' creato con successo!"))
                       (events/show-alert! (or (:error result) "Failed to fork version")
                                           {:title (t :error)}))))
            (.catch (fn [err]
                      (events/show-alert! (str "Error: " err)
                                          {:title (t :error)})))))})))

;; =============================================================================
;; UI Components
;; =============================================================================

(defn show-versions! []
  (swap! versions-state assoc :visible? true)
  (load-versions!))

(defn hide-versions! []
  (swap! versions-state assoc
         :visible? false
         :selected-ref nil
         :diff-lines nil
         :creating-tag? false))

(defn version-item [{:keys [ref short-ref message date is-tag tag]}]
  (let [colors (:colors @settings/settings)
        selected? (= ref (:selected-ref @versions-state))
        is-owner? (model/is-project-owner?)]
    [:div {:style {:padding "8px 12px"
                   :border-bottom (str "1px solid " (:border colors))
                   :background (when selected? (:accent-muted colors))
                   :cursor "pointer"}
           :on-click #(load-version-preview! ref)}
     [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
      ;; Tag badge or commit icon
      (if is-tag
        [:span {:style {:background (:accent colors)
                        :color (:bg colors)
                        :padding "2px 6px"
                        :border-radius "4px"
                        :font-size "0.7rem"
                        :font-weight "bold"}}
         tag]
        [:span {:style {:color (:text-muted colors)
                        :font-family "monospace"
                        :font-size "0.8rem"}}
         short-ref])
      ;; Message
      [:span {:style {:flex 1
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"
                      :color (:text colors)}}
       message]]
     ;; Date and actions
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :margin-top "4px"}}
      [:span {:style {:font-size "0.75rem"
                      :color (:text-muted colors)}}
       (when date (subs date 0 16))]
      (when selected?
        [:div {:style {:display "flex" :gap "8px"}}
         ;; Restore button (owner only)
         (when is-owner?
           [:button {:style {:background (:accent colors)
                             :color (:bg colors)
                             :border "none"
                             :padding "2px 8px"
                             :border-radius "4px"
                             :font-size "0.75rem"
                             :cursor "pointer"}
                     :on-click (fn [e]
                                 (.stopPropagation e)
                                 (restore-version! ref))}
            "Ripristina"])
         ;; Fork button
         [:button {:style {:background "transparent"
                           :color (:accent colors)
                           :border (str "1px solid " (:accent colors))
                           :padding "2px 8px"
                           :border-radius "4px"
                           :font-size "0.75rem"
                           :cursor "pointer"}
                   :on-click (fn [e]
                               (.stopPropagation e)
                               (fork-version! ref))}
          "Fork"]])]]))

(defn create-tag-form []
  (let [colors (:colors @settings/settings)
        {:keys [tag-name tag-message]} @versions-state
        text-color (:text colors)
        input-style {:width "100%"
                     :padding "6px 8px"
                     :border (str "1px solid " (:border colors))
                     :border-radius "4px"
                     :background (:sidebar colors)
                     :color text-color
                     :-webkit-text-fill-color text-color
                     :font-size "0.9rem"
                     :box-sizing "border-box"}]
    [:div {:style {:padding "12px"
                   :background (:tertiary colors)
                   :border-bottom (str "1px solid " (:border colors))}}
     [:div {:style {:margin-bottom "8px"}}
      [:input {:type "text"
               :placeholder "Nome tag (es. v1.0)"
               :value (or tag-name "")
               :on-change (fn [e]
                            (let [v (.. e -target -value)]
                              (swap! versions-state assoc :tag-name v)))
               :style input-style}]]
     [:div {:style {:margin-bottom "8px"}}
      [:input {:type "text"
               :placeholder "Descrizione (opzionale)"
               :value (or tag-message "")
               :on-change (fn [e]
                            (let [v (.. e -target -value)]
                              (swap! versions-state assoc :tag-message v)))
               :style input-style}]]
     [:div {:style {:display "flex" :gap "8px"}}
      [:button {:style {:flex 1
                        :background (if (seq tag-name)
                                      (:accent colors)
                                      (:text-muted colors))
                        :color (:bg colors)
                        :border "none"
                        :padding "6px"
                        :border-radius "4px"
                        :cursor (if (seq tag-name) "pointer" "not-allowed")}
                :on-click (when (seq tag-name) create-tag!)}
       "Crea Tag"]
      [:button {:style {:background "transparent"
                        :color (:text-muted colors)
                        :border (str "1px solid " (:border colors))
                        :padding "6px 12px"
                        :border-radius "4px"
                        :cursor "pointer"}
                :on-click #(swap! versions-state assoc :creating-tag? false)}
       "Annulla"]]]))

(defn- diff-line-component
  "Render a single diff line with appropriate styling."
  [{:keys [type text]} colors]
  (let [style (case type
                :added {:background "rgba(46, 160, 67, 0.15)"
                        :color "#3fb950"
                        :border-left "3px solid #3fb950"}
                :removed {:background "rgba(248, 81, 73, 0.15)"
                          :color "#f85149"
                          :border-left "3px solid #f85149"}
                :unchanged {:background "transparent"
                            :color (:text-muted colors)
                            :border-left "3px solid transparent"})
        prefix (case type
                 :added "+"
                 :removed "-"
                 :unchanged " ")]
    [:div {:style (merge style
                         {:padding "2px 8px"
                          :font-family "monospace"
                          :font-size "0.7rem"
                          :white-space "pre-wrap"
                          :word-break "break-word"})}
     (str prefix " " text)]))

(defn versions-panel []
  (let [colors (:colors @settings/settings)
        {:keys [visible? loading? versions error creating-tag? diff-lines]} @versions-state
        is-owner? (model/is-project-owner?)]
    (when visible?
      [:aside {:class "versions-panel"
               :style {:width "350px"
                       :min-width "350px"
                       :background (:bg colors)
                       :border-left (str "1px solid " (:border colors))
                       :display "flex"
                       :flex-direction "column"
                       :height "100%"
                       :overflow "hidden"}}
       ;; Header
       [:div.panel-header {:style {:padding "12px 16px"
                                   :border-bottom (str "1px solid " (:border colors))
                                   :display "flex"
                                   :align-items "center"
                                   :justify-content "space-between"
                                   :flex-shrink 0}}
        [:span {:style {:font-weight "bold" :color (:text colors)}}
         (t :versions)]
        [:button {:style {:background "transparent"
                          :border "none"
                          :color (:text-muted colors)
                          :cursor "pointer"
                          :font-size "1.2rem"}
                  :on-click hide-versions!}
         "×"]]

       ;; Create tag button (owner only)
       (when (and is-owner? (not creating-tag?))
         [:div {:style {:padding "8px 12px"
                        :border-bottom (str "1px solid " (:border colors))}}
          [:button {:style {:width "100%"
                            :background "transparent"
                            :color (:accent colors)
                            :border (str "1px solid " (:accent colors))
                            :padding "8px"
                            :border-radius "4px"
                            :cursor "pointer"}
                    :on-click #(swap! versions-state assoc :creating-tag? true)}
           "+ Crea Tag"]])

       ;; Create tag form
       (when creating-tag?
         [create-tag-form])

       ;; Loading/Error states
       (cond
         loading?
         [:div {:style {:padding "20px" :text-align "center" :color (:text-muted colors)}}
          "Caricamento..."]

         error
         [:div {:style {:padding "20px" :text-align "center" :color "#e74c3c"}}
          error]

         (empty? versions)
         [:div {:style {:padding "20px" :text-align "center" :color (:text-muted colors)}}
          "Nessuna versione disponibile"]

         :else
         [:div {:style {:flex 1 :overflow-y "auto"}}
          (for [version versions]
            ^{:key (:ref version)}
            [version-item version])])

       ;; Diff panel (instead of raw preview)
       (when (seq diff-lines)
         [:div {:style {:max-height "250px"
                        :overflow-y "auto"
                        :border-top (str "1px solid " (:border colors))
                        :background (:bg-secondary colors)}}
          [:div {:style {:font-size "0.75rem"
                         :color (:text-muted colors)
                         :padding "8px 12px"
                         :border-bottom (str "1px solid " (:border colors))}}
           "Diff (versione → attuale):"]
          [:div {:style {:max-height "200px" :overflow-y "auto"}}
           (for [[idx line] (map-indexed vector (take 50 diff-lines))]
             ^{:key idx}
             [diff-line-component line colors])
           (when (> (count diff-lines) 50)
             [:div {:style {:padding "8px"
                            :text-align "center"
                            :color (:text-muted colors)
                            :font-size "0.7rem"}}
              (str "... e altre " (- (count diff-lines) 50) " righe")])]])])))

;; =============================================================================
;; Public API
;; =============================================================================

(defn toggle-versions! []
  (if (:visible? @versions-state)
    (hide-versions!)
    (show-versions!)))

(defn versions-visible? []
  (:visible? @versions-state))
