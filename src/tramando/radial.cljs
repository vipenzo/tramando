(ns tramando.radial
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.help :as help]))

;; =============================================================================
;; Constants and State
;; =============================================================================

(def ^:private min-sector-angle (* 10 (/ js/Math.PI 180))) ; 10 degrees minimum
(def ^:private min-font-size 8)
(def ^:private max-font-size 16)
(def ^:private base-font-size 12)

(defonce view-state
  (r/atom {:zoom 1
           :pan-x 0
           :pan-y 0
           :dragging? false
           :drag-start nil
           :highlighted-chunk nil
           :locked-chunk nil
           :hovered-node nil
           ;; Filtri per tipo di aspetto (tutti attivi di default)
           :filters {:personaggi true
                     :luoghi true
                     :temi true
                     :sequenze true
                     :timeline true}}))

;; =============================================================================
;; Math Utilities
;; =============================================================================

(defn- deg->rad [deg]
  (* deg (/ js/Math.PI 180)))

(defn- polar->cartesian [cx cy r angle]
  [(+ cx (* r (js/Math.cos angle)))
   (+ cy (* r (js/Math.sin angle)))])

(defn- arc-path
  "Generate SVG arc path from start-angle to end-angle at radius r"
  [cx cy r start-angle end-angle]
  (let [[x1 y1] (polar->cartesian cx cy r start-angle)
        [x2 y2] (polar->cartesian cx cy r end-angle)
        large-arc (if (> (- end-angle start-angle) js/Math.PI) 1 0)]
    (str "M " x1 " " y1 " A " r " " r " 0 " large-arc " 1 " x2 " " y2)))

(defn- sector-path
  "Generate SVG path for a sector (wedge shape)"
  [cx cy r-inner r-outer start-angle end-angle]
  (let [[x1 y1] (polar->cartesian cx cy r-inner start-angle)
        [x2 y2] (polar->cartesian cx cy r-outer start-angle)
        [x3 y3] (polar->cartesian cx cy r-outer end-angle)
        [x4 y4] (polar->cartesian cx cy r-inner end-angle)
        large-arc (if (> (- end-angle start-angle) js/Math.PI) 1 0)]
    (str "M " x1 " " y1
         " L " x2 " " y2
         " A " r-outer " " r-outer " 0 " large-arc " 1 " x3 " " y3
         " L " x4 " " y4
         " A " r-inner " " r-inner " 0 " large-arc " 0 " x1 " " y1
         " Z")))

(defn- bezier-curve-path
  "Generate quadratic Bezier curve from point1 to point2 with control point toward center"
  [cx cy [x1 y1] [x2 y2]]
  (let [;; Control point is pulled toward center
        ctrl-factor 0.3
        ctrl-x (+ cx (* ctrl-factor (- (/ (+ x1 x2) 2) cx)))
        ctrl-y (+ cy (* ctrl-factor (- (/ (+ y1 y2) 2) cy)))]
    (str "M " x1 " " y1 " Q " ctrl-x " " ctrl-y " " x2 " " y2)))

(defn- mid-angle [start-angle end-angle]
  (/ (+ start-angle end-angle) 2))

(defn- hex->rgb
  "Parse hex color string to [r g b] vector"
  [hex]
  (let [hex (if (str/starts-with? hex "#") (subs hex 1) hex)
        r (js/parseInt (subs hex 0 2) 16)
        g (js/parseInt (subs hex 2 4) 16)
        b (js/parseInt (subs hex 4 6) 16)]
    [r g b]))

(defn- color-luminosity
  "Calculate relative luminosity of a color (0-1 scale).
   Uses the formula for perceived brightness."
  [hex-color]
  (let [[r g b] (hex->rgb hex-color)]
    ;; Using perceived brightness formula: (0.299*R + 0.587*G + 0.114*B) / 255
    (/ (+ (* 0.299 r) (* 0.587 g) (* 0.114 b)) 255)))

(defn- text-color-for-background
  "Return dark text for light backgrounds, white text for dark backgrounds"
  [bg-color]
  (if (> (color-luminosity bg-color) 0.5)
    "#333333"
    "#ffffff"))

(defn- clamped-font-size
  "Calculate SVG font size so that visual size on screen is clamped between min and max.
   Visual size = SVG size * zoom, so we clamp the visual and derive the SVG size."
  [base-size zoom]
  (let [;; What the visual size would be without clamping
        visual-size (* base-size zoom)
        ;; Clamp the visual size (what appears on screen)
        clamped-visual (-> visual-size
                           (max min-font-size)
                           (min max-font-size))]
    ;; Return SVG font size to achieve that clamped visual
    (/ clamped-visual zoom)))

;; =============================================================================
;; Layout Calculation
;; =============================================================================

(defn- calculate-structure-layout
  "Calculate layout for structural chunks (chapters and scenes)"
  [cx cy r-inner r-outer]
  (let [structural-tree (model/get-structural-tree)
        total-chapters (count structural-tree)
        chapter-angle (if (pos? total-chapters)
                        (/ (* 2 js/Math.PI) total-chapters)
                        0)
        r-chapter-outer (+ r-inner (* 0.5 (- r-outer r-inner)))
        r-scene-inner r-chapter-outer
        r-scene-outer r-outer]
    (loop [chapters structural-tree
           angle 0
           result []]
      (if (empty? chapters)
        result
        (let [chapter (first chapters)
              children (:children chapter)
              child-count (count children)
              end-angle (+ angle chapter-angle)
              ;; Chapter layout
              chapter-layout {:id (:id chapter)
                              :type :chapter
                              :start-angle angle
                              :end-angle end-angle
                              :r-inner r-inner
                              :r-outer r-chapter-outer
                              :center (polar->cartesian cx cy
                                                        (/ (+ r-inner r-chapter-outer) 2)
                                                        (mid-angle angle end-angle))}
              ;; Children (scenes) layout
              child-angle (if (pos? child-count)
                            (/ chapter-angle child-count)
                            0)
              children-layouts (loop [childs children
                                      child-a angle
                                      child-result []]
                                 (if (empty? childs)
                                   child-result
                                   (let [child (first childs)
                                         child-end (+ child-a child-angle)]
                                     (recur (rest childs)
                                            child-end
                                            (conj child-result
                                                  {:id (:id child)
                                                   :type :scene
                                                   :parent-id (:id chapter)
                                                   :start-angle child-a
                                                   :end-angle child-end
                                                   :r-inner r-scene-inner
                                                   :r-outer r-scene-outer
                                                   :center (polar->cartesian cx cy
                                                                             (/ (+ r-scene-inner r-scene-outer) 2)
                                                                             (mid-angle child-a child-end))})))))]
          (recur (rest chapters)
                 end-angle
                 (into (conj result chapter-layout) children-layouts)))))))

(defn- filter-aspects-by-threshold
  "Filter aspects by priority threshold"
  [aspects container-id]
  (let [threshold (settings/get-aspect-threshold container-id)]
    (filter #(>= (or (:priority %) 0) threshold) aspects)))

(defn- calculate-aspects-layout
  "Calculate layout for aspect containers and their children"
  [cx cy r-inner r-outer filters]
  (let [;; Filtra solo i container attivi
        active-containers (filter (fn [{:keys [id]}]
                                    (get filters (keyword id) true))
                                  model/aspect-containers)
        ;; Count children for each container (filtered by priority threshold)
        container-counts (map (fn [{:keys [id]}]
                                (let [all-children (model/build-aspect-tree id)
                                      filtered-children (filter-aspects-by-threshold all-children id)]
                                  {:id id
                                   :count (count filtered-children)}))
                              active-containers)
        total-items (reduce + (map :count container-counts))
        ;; Calculate angles - minimum 10 degrees per container
        base-angle (/ (* 2 js/Math.PI) (count active-containers))
        assign-angles (fn [counts]
                        (let [total-weighted (reduce + (map #(max 1 (:count %)) counts))
                              available-angle (* 2 js/Math.PI)]
                          (map (fn [{:keys [id] :as m}]
                                 (let [cnt (:count m)
                                       weight (max 1 cnt)
                                       angle (max min-sector-angle
                                                  (* available-angle (/ weight total-weighted)))]
                                   {:id id :angle angle :cnt cnt}))
                               counts)))
        container-angles (assign-angles container-counts)
        r-container-inner r-inner
        r-container-outer (+ r-inner (* 0.3 (- r-outer r-inner)))
        r-aspect-inner r-container-outer
        r-aspect-outer r-outer]
    (loop [containers-info container-angles
           angle (- (/ js/Math.PI 2)) ; Start from top
           result []]
      (if (empty? containers-info)
        result
        (let [{:keys [id cnt] :as info} (first containers-info)
              sector-angle (:angle info)
              end-angle (+ angle sector-angle)
              all-children (model/build-aspect-tree id)
              children (filter-aspects-by-threshold all-children id)
              child-count (count children)
              ;; Container sector layout
              container-layout {:id id
                                :type :container
                                :start-angle angle
                                :end-angle end-angle
                                :r-inner r-container-inner
                                :r-outer r-container-outer
                                :center (polar->cartesian cx cy
                                                          (/ (+ r-container-inner r-container-outer) 2)
                                                          (mid-angle angle end-angle))}
              ;; Aspect children layout
              child-angle (if (pos? child-count)
                            (/ sector-angle child-count)
                            0)
              children-layouts (loop [childs children
                                      child-a angle
                                      child-result []]
                                 (if (empty? childs)
                                   child-result
                                   (let [child (first childs)
                                         child-end (+ child-a child-angle)]
                                     (recur (rest childs)
                                            child-end
                                            (conj child-result
                                                  {:id (:id child)
                                                   :type :aspect
                                                   :container-id id
                                                   :start-angle child-a
                                                   :end-angle child-end
                                                   :r-inner r-aspect-inner
                                                   :r-outer r-aspect-outer
                                                   :center (polar->cartesian cx cy
                                                                             (/ (+ r-aspect-inner r-aspect-outer) 2)
                                                                             (mid-angle child-a child-end))})))))]
          (recur (rest containers-info)
                 end-angle
                 (into (conj result container-layout) children-layouts)))))))

(defn- calculate-connections
  "Calculate connection lines between structural chunks and their aspects"
  [structure-layout aspects-layout]
  (let [chunks (model/get-chunks)
        ;; Build lookup maps
        structure-map (into {} (map (fn [s] [(:id s) s]) structure-layout))
        aspects-map (into {} (map (fn [a] [(:id a) a]) aspects-layout))]
    (reduce
     (fn [connections chunk]
       (let [chunk-layout (get structure-map (:id chunk))
             chunk-aspects (:aspects chunk)]
         (if (and chunk-layout (seq chunk-aspects))
           (reduce
            (fn [conns aspect-id]
              (if-let [aspect-layout (get aspects-map aspect-id)]
                (conj conns {:from-id (:id chunk)
                             :to-id aspect-id
                             :from-center (:center chunk-layout)
                             :to-center (:center aspect-layout)
                             :container-id (:container-id aspect-layout)})
                conns))
            connections
            chunk-aspects)
           connections)))
     []
     chunks)))

;; =============================================================================
;; SVG Components
;; =============================================================================

(defn- center-circle [cx cy radius title zoom]
  (let [colors (:colors @settings/settings)
        font-size (clamped-font-size 14 zoom)
        ;; Text box is slightly smaller than circle diameter
        text-size (* radius 1.6)]
    [:g {:class "center-node"}
     [:circle {:cx cx :cy cy :r radius
               :fill (:sidebar colors)
               :stroke (:accent colors)
               :stroke-width 2}]
     [:foreignObject {:x (- cx (/ text-size 2))
                      :y (- cy (/ text-size 2))
                      :width text-size
                      :height text-size}
      [:div {:xmlns "http://www.w3.org/1999/xhtml"
             :style {:width "100%"
                     :height "100%"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :text-align "center"
                     :color (:text colors)
                     :font-size (str font-size "px")
                     :font-weight "600"
                     :line-height "1.2"
                     :overflow "hidden"
                     :word-wrap "break-word"
                     :hyphens "auto"
                     :padding "4px"}}
       title]]]))

(defn- structure-sector [cx cy {:keys [id type start-angle end-angle r-inner r-outer]} on-click on-hover zoom]
  (let [colors (:colors @settings/settings)
        chunk (first (filter #(= (:id %) id) (model/get-chunks)))
        selected? (= id (model/get-selected-id))
        {:keys [highlighted-chunk locked-chunk]} @view-state
        highlighted? (or (= id highlighted-chunk) (= id locked-chunk))
        fill-color (:structure colors)
        fill-opacity (cond selected? 0.8
                           highlighted? 0.7
                           (= type :scene) 0.35
                           :else 0.5)
        ;; Calculate effective color for text contrast
        text-color (text-color-for-background fill-color)
        ;; Clamped font sizes
        font-size (clamped-font-size (if (= type :chapter) 14 12) zoom)
        ;; Calculate available space for text
        mid-r (/ (+ r-inner r-outer) 2)
        angle-span (- end-angle start-angle)
        arc-length (* mid-r angle-span)
        radial-height (- r-outer r-inner)
        ;; Text box dimensions (slightly smaller than available space)
        text-width (* arc-length 0.85)
        text-height (* radial-height 0.8)]
    [:g {:class "structure-sector"
         :style {:cursor "pointer"}
         :on-click #(on-click id)
         :on-mouse-enter #(on-hover id chunk type)
         :on-mouse-leave #(on-hover nil nil nil)}
     [:path {:d (sector-path cx cy r-inner r-outer start-angle end-angle)
             :fill fill-color
             :fill-opacity fill-opacity
             :stroke (:background colors)
             :stroke-width 1}]
     (let [[tx ty] (polar->cartesian cx cy mid-r (mid-angle start-angle end-angle))
           angle-deg (* (mid-angle start-angle end-angle) (/ 180 js/Math.PI))
           tangent-angle (- angle-deg 90)
           rotation (if (and (>= tangent-angle 90) (< tangent-angle 270))
                      (+ tangent-angle 180)
                      tangent-angle)
           ;; Expand macros like [:ORD] -> "4"
           summary (model/expand-summary-macros (or (:summary chunk) "") chunk)]
       [:foreignObject {:x (- tx (/ text-width 2))
                        :y (- ty (/ text-height 2))
                        :width text-width
                        :height text-height
                        :transform (str "rotate(" rotation " " tx " " ty ")")}
        [:div {:xmlns "http://www.w3.org/1999/xhtml"
               :style {:width "100%"
                       :height "100%"
                       :display "flex"
                       :align-items "center"
                       :justify-content "center"
                       :text-align "center"
                       :color text-color
                       :font-size (str font-size "px")
                       :font-weight (if (= type :chapter) "600" "normal")
                       :line-height "1.1"
                       :overflow "hidden"
                       :word-wrap "break-word"
                       :hyphens "auto"}}
         summary]])]))

(defn- aspect-sector [cx cy {:keys [id type container-id start-angle end-angle r-inner r-outer]} on-click on-hover zoom]
  (let [colors (:colors @settings/settings)
        chunk (first (filter #(= (:id %) id) (model/get-chunks)))
        container-key (keyword (or container-id id))
        sector-color (get colors container-key (:accent colors))
        selected? (= id (model/get-selected-id))
        {:keys [highlighted-chunk locked-chunk]} @view-state
        highlighted? (or (= id highlighted-chunk) (= id locked-chunk))
        ;; Calculate text color based on background luminosity
        text-color (text-color-for-background sector-color)
        ;; Clamped font size
        font-size (clamped-font-size 12 zoom)
        ;; Determine the aspect type from container
        aspect-type (case container-id
                      "personaggi" :personaggio
                      "luoghi" :luogo
                      "temi" :tema
                      "sequenze" :sequenza
                      "timeline" :timeline
                      :container)
        ;; Calculate available space for text
        mid-r (/ (+ r-inner r-outer) 2)
        angle-span (- end-angle start-angle)
        arc-length (* mid-r angle-span)
        radial-height (- r-outer r-inner)
        text-width (* arc-length 0.85)
        text-height (* radial-height 0.8)]
    [:g {:class "aspect-sector"
         :style {:cursor "pointer"}
         :on-click #(on-click id)
         :on-mouse-enter #(on-hover id chunk (if (= type :container) :container aspect-type))
         :on-mouse-leave #(on-hover nil nil nil)}
     [:path {:d (sector-path cx cy r-inner r-outer start-angle end-angle)
             :fill sector-color
             :fill-opacity (cond selected? 0.7
                                 highlighted? 0.6
                                 (= type :container) 0.25
                                 :else 0.4)
             :stroke (:background colors)
             :stroke-width 1}]
     (when (= type :aspect)
       (let [[tx ty] (polar->cartesian cx cy mid-r (mid-angle start-angle end-angle))
             angle-deg (* (mid-angle start-angle end-angle) (/ 180 js/Math.PI))
             tangent-angle (- angle-deg 90)
             rotation (if (and (>= tangent-angle 90) (< tangent-angle 270))
                        (+ tangent-angle 180)
                        tangent-angle)
             ;; Expand macros like [:ORD] -> "4"
             summary (model/expand-summary-macros (or (:summary chunk) "") chunk)]
         [:foreignObject {:x (- tx (/ text-width 2))
                          :y (- ty (/ text-height 2))
                          :width text-width
                          :height text-height
                          :transform (str "rotate(" rotation " " tx " " ty ")")}
          [:div {:xmlns "http://www.w3.org/1999/xhtml"
                 :style {:width "100%"
                         :height "100%"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :text-align "center"
                         :color text-color
                         :font-size (str font-size "px")
                         :line-height "1.1"
                         :overflow "hidden"
                         :word-wrap "break-word"
                         :hyphens "auto"}}
           summary]]))]))

(defn- connection-line [cx cy {:keys [from-id to-id from-center to-center container-id]}]
  (let [colors (:colors @settings/settings)
        container-key (keyword container-id)
        line-color (get colors container-key (:accent colors))
        {:keys [highlighted-chunk locked-chunk]} @view-state
        ;; Distinguish between locked (selected) and hovered connections
        is-locked? (and locked-chunk
                        (or (= from-id locked-chunk)
                            (= to-id locked-chunk)))
        is-hovered? (and highlighted-chunk
                         (not= highlighted-chunk locked-chunk)  ; Don't double-highlight
                         (or (= from-id highlighted-chunk)
                             (= to-id highlighted-chunk)))
        has-lock? (some? locked-chunk)
        ;; Locked lines: full color, thick, with glow
        ;; Hovered lines: slightly desaturated, thinner, no glow
        ;; Other lines: very faint when there's a selection
        opacity (cond
                  is-locked? 1.0
                  is-hovered? 0.7
                  has-lock? 0.05
                  :else 0.3)
        stroke-width (cond
                       is-locked? 4
                       is-hovered? 2.5
                       :else 1.5)]
    [:g
     ;; Glow effect only for locked (selected) lines
     (when is-locked?
       [:path {:d (bezier-curve-path cx cy from-center to-center)
               :fill "none"
               :stroke line-color
               :stroke-width 8
               :stroke-opacity 0.3
               :stroke-linecap "round"}])
     ;; Main line
     [:path {:d (bezier-curve-path cx cy from-center to-center)
             :fill "none"
             :stroke line-color
             :stroke-width stroke-width
             :stroke-opacity opacity
             :stroke-linecap "round"
             :stroke-dasharray (when is-hovered? "6,3")}]]))

;; =============================================================================
;; Info Panel Component
;; =============================================================================

(defn- type-label [node-type]
  (case node-type
    :chapter "Capitolo"
    :scene "Scena"
    :personaggio "Personaggio"
    :luogo "Luogo"
    :tema "Tema"
    :sequenza "Sequenza"
    :timeline "Timeline"
    :container "Contenitore"
    "Elemento"))

(defn- count-connections [chunk-id chunks]
  "Count how many structural chunks reference this aspect, or how many aspects a structural chunk has"
  (let [chunk (first (filter #(= (:id %) chunk-id) chunks))]
    (if (model/is-aspect-chunk? chunk)
      ;; Count structural chunks that reference this aspect
      (count (filter (fn [c]
                       (and (not (model/is-aspect-chunk? c))
                            (some #{chunk-id} (:aspects c))))
                     chunks))
      ;; Count aspects this structural chunk references
      (count (:aspects chunk)))))

(defn- info-panel-content
  "Render the content of an info panel for a given node"
  [chunk node-type chunks colors]
  (let [connections (when chunk (count-connections (:id chunk) chunks))
        ;; Expand macros like [:ORD] -> "4"
        title (if chunk
                (model/expand-summary-macros (or (:summary chunk) "(senza titolo)") chunk)
                "(senza titolo)")]
    [:div
     ;; Title
     [:div {:style {:color (:text colors)
                    :font-size "1rem"
                    :font-weight "600"
                    :margin-bottom "8px"
                    :white-space "nowrap"
                    :overflow "hidden"
                    :text-overflow "ellipsis"}}
      title]
     ;; Type
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :margin-bottom "4px"}}
      [:span {:style {:color (:text-muted colors) :font-size "0.85rem"}} "Tipo"]
      [:span {:style {:color (:text colors) :font-size "0.85rem"}} (type-label node-type)]]
     ;; ID
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :margin-bottom "4px"}}
      [:span {:style {:color (:text-muted colors) :font-size "0.85rem"}} "ID"]
      [:span {:style {:color (:text colors)
                      :font-size "0.85rem"
                      :font-family "monospace"}}
       (str "@" (:id chunk))]]
     ;; Connections
     (when (and chunk (pos? connections))
       [:div {:style {:display "flex"
                      :justify-content "space-between"
                      :margin-top "8px"
                      :padding-top "8px"
                      :border-top (str "1px solid " (:border colors))}}
        [:span {:style {:color (:text-muted colors) :font-size "0.85rem"}} "Collegamenti"]
        [:span {:style {:color (:accent colors) :font-size "0.85rem" :font-weight "600"}}
         (if (model/is-aspect-chunk? chunk)
           (str "Usato in " connections " scene")
           (str "Collega " connections " aspetti"))]])]))

(defn- get-node-info
  "Get chunk and node-type for a given chunk-id"
  [chunk-id chunks]
  (when chunk-id
    (let [chunk (first (filter #(= (:id %) chunk-id) chunks))]
      (when chunk
        (let [node-type (cond
                          (model/is-aspect-container? chunk-id) :container
                          (model/is-aspect-chunk? chunk)
                          (let [parent-id (:parent-id chunk)
                                container-id (loop [pid parent-id]
                                               (if (model/is-aspect-container? pid)
                                                 pid
                                                 (let [parent (first (filter #(= (:id %) pid) chunks))]
                                                   (when parent (recur (:parent-id parent))))))]
                            (case container-id
                              "personaggi" :personaggio
                              "luoghi" :luogo
                              "temi" :tema
                              "sequenze" :sequenza
                              "timeline" :timeline
                              :aspect))
                          ;; Structural chunk - check if it has children
                          (some #(= (:parent-id %) chunk-id) chunks) :chapter
                          :else :scene)]
          {:chunk chunk :node-type node-type})))))

(defn- info-panel []
  (let [colors (:colors @settings/settings)
        {:keys [hovered-node locked-chunk]} @view-state
        chunks (model/get-chunks)
        locked-info (get-node-info locked-chunk chunks)
        ;; Don't show hover panel if it's the same as locked
        show-hover? (and hovered-node
                         (not= (:id (:chunk hovered-node)) locked-chunk))]
    [:div {:style {:position "absolute"
                   :bottom "16px"
                   :left "16px"
                   :display "flex"
                   :flex-direction "column"
                   :gap "12px"
                   :pointer-events "none"
                   :z-index 10}}
     ;; Hover panel (top)
     [:div {:style {:width "250px"
                    :background (str (:sidebar colors) "ee")
                    :border (str "1px solid " (:border colors))
                    :border-radius "8px"
                    :padding "12px 16px"}}
      [:div {:style {:color (:text-muted colors)
                     :font-size "0.7rem"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :margin-bottom "8px"}}
       "Hover"]
      (if show-hover?
        (let [{:keys [chunk node-type]} hovered-node]
          [info-panel-content chunk node-type chunks colors])
        [:div {:style {:color (:text-muted colors)
                       :font-size "0.85rem"
                       :font-style "italic"
                       :padding "4px 0"}}
         "Passa il mouse su un elemento"])]
     ;; Selection panel (bottom)
     [:div {:style {:width "250px"
                    :background (str (:sidebar colors) "ee")
                    :border (str "1px solid " (:accent colors))
                    :border-radius "8px"
                    :padding "12px 16px"}}
      [:div {:style {:color (:accent colors)
                     :font-size "0.7rem"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :margin-bottom "8px"}}
       "Selezione"]
      (if locked-info
        (let [{:keys [chunk node-type]} locked-info]
          [info-panel-content chunk node-type chunks colors])
        [:div {:style {:color (:text-muted colors)
                       :font-size "0.85rem"
                       :font-style "italic"
                       :padding "4px 0"}}
         "Clicca su un elemento per selezionarlo"])]]))

;; =============================================================================
;; Filters Panel Component
;; =============================================================================

(def ^:private filter-labels
  {:personaggi "Personaggi"
   :luoghi "Luoghi"
   :temi "Temi"
   :sequenze "Sequenze"
   :timeline "Timeline"})

(defn- filters-panel []
  (let [colors (:colors @settings/settings)
        filters (:filters @view-state)]
    [:div {:style {:position "absolute"
                   :top "16px"
                   :left "16px"
                   :background (str (:sidebar colors) "ee")
                   :border (str "1px solid " (:border colors))
                   :border-radius "8px"
                   :padding "12px 16px"
                   :z-index 10}}
     [:div {:style {:color (:text-muted colors)
                    :font-size "0.7rem"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :margin-bottom "8px"}}
      "Filtri"]
     (doall
      (for [filter-key [:personaggi :luoghi :temi :sequenze :timeline]]
        (let [active? (get filters filter-key true)
              filter-color (get colors filter-key (:accent colors))]
          ^{:key filter-key}
          [:label {:style {:display "flex"
                           :align-items "center"
                           :gap "8px"
                           :cursor "pointer"
                           :margin-bottom "4px"
                           :color (if active? (:text colors) (:text-muted colors))
                           :font-size "0.85rem"}
                   :on-click (fn [e]
                               (.preventDefault e)
                               (swap! view-state update-in [:filters filter-key] not))}
           [:div {:style {:width "16px"
                          :height "16px"
                          :border-radius "3px"
                          :border (str "2px solid " filter-color)
                          :background (if active? filter-color "transparent")
                          :display "flex"
                          :align-items "center"
                          :justify-content "center"}}
            (when active?
              [:span {:style {:color "#fff" :font-size "11px" :font-weight "bold"}} "✓"])]
           (get filter-labels filter-key)])))]))

;; =============================================================================
;; Main Radial Component
;; =============================================================================

(defn radial-view []
  (let [container-ref (r/atom nil)
        svg-ref (r/atom nil)
        wheel-handler-ref (r/atom nil)
        size (r/atom {:width 800 :height 600})
        ;; Wheel handler with passive: false to allow preventDefault
        handle-wheel (fn [e]
                       (.preventDefault e)
                       (let [delta (if (pos? (.-deltaY e)) -0.1 0.1)
                             new-zoom (-> (+ (:zoom @view-state) delta)
                                          (max 0.3)
                                          (min 3))]
                         (swap! view-state assoc :zoom new-zoom)))
        ;; Callback ref for SVG that sets up wheel listener
        svg-ref-callback (fn [el]
                           ;; Remove old listener if exists
                           (when-let [old-svg @svg-ref]
                             (.removeEventListener old-svg "wheel" handle-wheel))
                           ;; Set new ref and add listener
                           (reset! svg-ref el)
                           (when el
                             (.addEventListener el "wheel" handle-wheel #js {:passive false})))]
    (r/create-class
     {:display-name "radial-view"

      :component-did-mount
      (fn [^js this]
        (when-let [el @container-ref]
          (let [rect (.getBoundingClientRect el)]
            (reset! size {:width (.-width rect) :height (.-height rect)})))
        ;; Add resize listener
        (let [handle-resize (fn []
                              (when-let [el @container-ref]
                                (let [rect (.getBoundingClientRect el)]
                                  (reset! size {:width (.-width rect) :height (.-height rect)}))))]
          (.addEventListener js/window "resize" handle-resize)
          (set! (.-radialResizeHandler this) handle-resize)))

      :component-will-unmount
      (fn [^js this]
        (when-let [handler (.-radialResizeHandler this)]
          (.removeEventListener js/window "resize" handler))
        ;; Clean up wheel listener
        (when-let [svg @svg-ref]
          (.removeEventListener svg "wheel" handle-wheel)))

      :reagent-render
      (fn []
        (let [{:keys [width height]} @size
              {:keys [zoom pan-x pan-y dragging? filters]} @view-state
              ;; Subscribe to settings changes (includes thresholds)
              colors (:colors @settings/settings)

              ;; Calculate center and radii based on size
              cx (/ width 2)
              cy (/ height 2)
              base-size (min width height)
              scale (* 0.45 base-size)

              ;; Radii
              center-r (* 0.12 scale)
              struct-inner (* 0.18 scale)
              struct-outer (* 0.45 scale)
              aspect-inner (* 0.50 scale)
              aspect-outer (* 0.90 scale)

              ;; Calculate layouts
              structure-layout (calculate-structure-layout cx cy struct-inner struct-outer)
              aspects-layout (calculate-aspects-layout cx cy aspect-inner aspect-outer filters)
              connections (calculate-connections structure-layout aspects-layout)

              ;; Get project title from filename (without extension)
              filename (model/get-filename)
              project-title (if (and filename (not= filename "untitled.md"))
                              (str/replace filename #"\.[^.]+$" "")
                              "Tramando")

              ;; Event handlers
              on-click (fn [id]
                         (model/select-chunk! id)
                         (swap! view-state assoc :locked-chunk
                                (if (= id (:locked-chunk @view-state)) nil id)))
              on-hover (fn [id chunk node-type]
                         (swap! view-state assoc
                                :highlighted-chunk id
                                :hovered-node (when id {:chunk chunk :node-type node-type})))
              on-background-click (fn [e]
                                    (when (and (= (.-target e) (.-currentTarget e))
                                               (not dragging?))
                                      (swap! view-state assoc :locked-chunk nil)))
              ;; Pan handlers
              on-mouse-down (fn [e]
                              (when (= (.-button e) 0) ; left click only
                                (swap! view-state assoc
                                       :dragging? true
                                       :drag-start {:x (.-clientX e) :y (.-clientY e)
                                                    :pan-x pan-x :pan-y pan-y})))
              on-mouse-move (fn [e]
                              (when (:dragging? @view-state)
                                (let [{:keys [x y pan-x pan-y]} (:drag-start @view-state)
                                      dx (- (.-clientX e) x)
                                      dy (- (.-clientY e) y)
                                      ;; Scale movement by zoom level
                                      scale-factor (/ 1 zoom)]
                                  (swap! view-state assoc
                                         :pan-x (+ pan-x (* dx scale-factor))
                                         :pan-y (+ pan-y (* dy scale-factor))))))
              on-mouse-up (fn [_]
                            (swap! view-state assoc :dragging? false :drag-start nil))]

          [:div.radial-container
           {:ref #(reset! container-ref %)
            :style {:flex 1
                    :overflow "hidden"
                    :background (:background colors)
                    :position "relative"}}
           [:svg {:ref svg-ref-callback
                  :width "100%"
                  :height "100%"
                  :viewBox (let [vw (/ width zoom)
                                 vh (/ height zoom)
                                 vx (- cx (/ vw 2) pan-x)
                                 vy (- cy (/ vh 2) pan-y)]
                             (str vx " " vy " " vw " " vh))
                  :style {:cursor (if dragging? "grabbing" "grab")}
                  :on-click on-background-click
                  :on-mouse-down on-mouse-down
                  :on-mouse-move on-mouse-move
                  :on-mouse-up on-mouse-up
                  :on-mouse-leave on-mouse-up}

            ;; Background
            [:rect {:x 0 :y 0 :width width :height height
                    :fill (:background colors)}]

            ;; Connection lines (render first, behind sectors)
            [:g {:class "connections"}
             (doall
              (for [conn connections]
                ^{:key (str (:from-id conn) "-" (:to-id conn))}
                [connection-line cx cy conn]))]

            ;; Aspect sectors (outer ring)
            [:g {:class "aspects-ring"}
             (doall
              (for [sector aspects-layout]
                ^{:key (:id sector)}
                [aspect-sector cx cy sector on-click on-hover zoom]))]

            ;; Structure sectors (inner ring)
            [:g {:class "structure-ring"}
             (doall
              (for [sector structure-layout]
                ^{:key (:id sector)}
                [structure-sector cx cy sector on-click on-hover zoom]))]

            ;; Center circle
            [center-circle cx cy center-r project-title zoom]

            ;; Zoom indicator
            [:text {:x 20 :y (- height 20)
                    :fill (:text-muted colors)
                    :font-size "12px"}
             (str "Zoom: " (.toFixed zoom 1) "x")]]
           ;; Filters panel (top-left corner)
           [filters-panel]
           ;; Info panel (outside SVG, inside container)
           [info-panel]
           ;; Help tooltip (top-right corner - opens below and left to avoid clipping)
           [:div {:style {:position "absolute"
                          :top "16px"
                          :right "16px"
                          :z-index 10}}
            [help/help-icon :mappa-radiale {:below? true :right? true}]]]))})))
