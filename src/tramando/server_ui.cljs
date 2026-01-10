(ns tramando.server-ui
  "UI components for server mode: login, projects list, collaborators"
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.auth :as auth]
            [tramando.api :as api]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]
            [tramando.events :as events]
            [tramando.model :as model]))

;; =============================================================================
;; Markdown to TRMD Conversion
;; =============================================================================

(defn- md-to-trmd
  "Convert markdown content to TRMD format with hierarchy.
   # headings become chapters (root), ## and deeper become children.
   TRMD native format uses [C:id\"summary\"] headers with indentation for hierarchy."
  [md-content]
  ;; Remove BOM if present and normalize line endings
  (let [clean-content (-> md-content
                          (str/replace #"^\uFEFF" "")  ;; Remove UTF-8 BOM
                          (str/replace "\r\n" "\n")    ;; Windows -> Unix
                          (str/replace "\r" "\n"))     ;; Old Mac -> Unix
        lines (str/split-lines clean-content)
        _ (js/console.log "md-to-trmd: total lines =" (count lines))
        _ (js/console.log "md-to-trmd: first 3 lines =" (pr-str (take 3 lines)))
        ;; Parse lines into chunks with hierarchy info
        result (reduce
                (fn [{:keys [chunks current-chunk parent-stack counter]} line]
                  (if-let [[_ hashes title] (re-find #"^(#{1,6})\s+(.+)$" line)]
                    ;; Found a heading
                    (let [level (count hashes)
                          ;; Save current chunk if it has content
                          updated-chunks (if (and current-chunk
                                                   (or (seq (:summary current-chunk))
                                                       (seq (:lines current-chunk))))
                                           (conj chunks (assoc current-chunk
                                                               :content (str/trim (str/join "\n" (:lines current-chunk)))))
                                           chunks)
                          ;; Find parent: walk back through stack to find first chunk with level < current
                          new-parent-stack (vec (take-while #(< (:level %) level) parent-stack))
                          parent-id (when (seq new-parent-stack)
                                      (:id (last new-parent-stack)))
                          new-id (str "cap-" counter)
                          new-chunk {:id new-id
                                     :summary (str/trim title)
                                     :lines []
                                     :level level
                                     :parent-id parent-id}]
                      {:chunks updated-chunks
                       :current-chunk new-chunk
                       :parent-stack (conj new-parent-stack {:id new-id :level level})
                       :counter (inc counter)})
                    ;; Regular line - add to current chunk's content
                    {:chunks chunks
                     :current-chunk (if current-chunk
                                      (update current-chunk :lines conj line)
                                      current-chunk)
                     :parent-stack parent-stack
                     :counter counter}))
                {:chunks []
                 :current-chunk nil
                 :parent-stack []
                 :counter 1}
                lines)
        ;; Don't forget the last chunk
        final-chunks (if (and (:current-chunk result)
                              (or (seq (:summary (:current-chunk result)))
                                  (seq (:lines (:current-chunk result)))))
                       (conj (:chunks result)
                             (assoc (:current-chunk result)
                                    :content (str/trim (str/join "\n" (:lines (:current-chunk result))))))
                       (:chunks result))
        _ (js/console.log "md-to-trmd: found chunks =" (count final-chunks))
        _ (js/console.log "md-to-trmd: chunk summaries =" (pr-str (map :summary (take 5 final-chunks))))
        ;; Build parent lookup for indentation
        chunk-by-id (into {} (map (juxt :id identity) final-chunks))
        ;; Helper to get depth (number of ancestors)
        get-depth (fn get-depth [chunk]
                    (if-let [pid (:parent-id chunk)]
                      (inc (get-depth (chunk-by-id pid)))
                      0))
        ;; Serialize to TRMD native format: [C:id"summary"] with indentation
        output (str/join "\n\n"
                         (map (fn [chunk]
                                (let [{:keys [id summary content]} chunk
                                      depth (get-depth chunk)
                                      indent (apply str (repeat (* 2 depth) " "))
                                      ;; Format: [C:id"summary"]
                                      header (str indent "[C:" id "\"" summary "\"]")
                                      ;; Indent content lines too
                                      content-str (when (seq content)
                                                    (str/join "\n"
                                                              (map #(str indent %)
                                                                   (str/split-lines content))))]
                                  (if (seq content-str)
                                    (str header "\n" content-str)
                                    header)))
                              final-chunks))
        _ (js/console.log "md-to-trmd: output length =" (count output))
        _ (js/console.log "md-to-trmd: output preview =" (subs output 0 (min 500 (count output))))]
    output))

;; =============================================================================
;; Login/Register Form
;; =============================================================================

(defn login-form
  "Login or registration form"
  [{:keys [on-success]}]
  (let [mode (r/atom :login)  ;; :login or :register
        ;; Auto-detect server URL from current page location
        auto-server-url (let [loc js/window.location]
                          (str (.-protocol loc) "//" (.-hostname loc) ":3000"))
        server-url (r/atom auto-server-url)
        username (r/atom "")
        password (r/atom "")
        password2 (r/atom "")
        error (r/atom nil)
        pending-message (r/atom nil)  ;; Message shown when registration is pending
        loading? (r/atom false)]
    (fn [{:keys [on-success]}]
      [:div {:style {:max-width "360px"
                     :margin "0 auto"
                     :padding "40px 20px"}}
       ;; Logo/Title
       [:div {:style {:text-align "center" :margin-bottom "30px"}}
        [:h1 {:style {:font-size "2rem"
                      :font-weight "300"
                      :color (settings/get-color :text)
                      :margin-bottom "8px"}}
         "Tramando"]
        [:p {:style {:color (settings/get-color :text-muted)
                     :font-size "0.9rem"}}
         (if (= @mode :login)
           "Accedi al tuo account"
           "Crea un nuovo account")]]

       ;; Pending registration message (success)
       (when @pending-message
         [:div {:style {:background (settings/get-color :success)
                        :color "white"
                        :padding "15px"
                        :border-radius "4px"
                        :margin-bottom "20px"
                        :font-size "0.9rem"
                        :text-align "center"}}
          [:div {:style {:font-weight "500" :margin-bottom "5px"}} "Registrazione completata!"]
          [:div @pending-message]
          [:div {:style {:margin-top "10px"}}
           [:a {:href "#"
                :on-click (fn [e]
                            (.preventDefault e)
                            (reset! pending-message nil)
                            (reset! mode :login))
                :style {:color "white" :text-decoration "underline"}}
            "Torna al login"]]])

       ;; Error message
       (when (and @error (not @pending-message))
         [:div {:style {:background (settings/get-color :danger)
                        :color "white"
                        :padding "10px 15px"
                        :border-radius "4px"
                        :margin-bottom "20px"
                        :font-size "0.9rem"}}
          @error])

       ;; Form
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (reset! error nil)
                            (cond
                              (< (count @username) 3)
                              (reset! error "Username deve avere almeno 3 caratteri")

                              (< (count @password) 6)
                              (reset! error "Password deve avere almeno 6 caratteri")

                              (and (= @mode :register) (not= @password @password2))
                              (reset! error "Le password non corrispondono")

                              :else
                              (do
                                (reset! loading? true)
                                ;; Set server URL before login
                                (api/set-server-url! @server-url)
                                (-> (if (= @mode :login)
                                      (auth/login! @username @password)
                                      (auth/register! @username @password))
                                    (.then (fn [result]
                                             (reset! loading? false)
                                             (cond
                                               ;; Registration pending approval
                                               (:pending result)
                                               (reset! pending-message (:message result))
                                               ;; Success (login or first user registration)
                                               (:ok result)
                                               (when on-success (on-success))
                                               ;; Error
                                               :else
                                               (reset! error (:error result)))))))))}
        ;; Server URL
        [:div {:style {:margin-bottom "15px"}}
         [:label {:style {:display "block"
                          :margin-bottom "5px"
                          :font-size "0.85rem"
                          :color (settings/get-color :text-muted)}}
          "Server"]
         [:input {:type "text"
                  :value @server-url
                  :on-change #(reset! server-url (-> % .-target .-value))
                  :disabled @loading?
                  :style {:width "100%"
                          :padding "10px 12px"
                          :border (str "1px solid " (settings/get-color :border))
                          :border-radius "4px"
                          :background (settings/get-color :editor-bg)
                          :color (settings/get-color :text)
                          :font-size "0.9rem"
                          :box-sizing "border-box"}}]]

        ;; Username
        [:div {:style {:margin-bottom "15px"}}
         [:label {:style {:display "block"
                          :margin-bottom "5px"
                          :font-size "0.85rem"
                          :color (settings/get-color :text-muted)}}
          "Username"]
         [:input {:type "text"
                  :value @username
                  :on-change #(reset! username (-> % .-target .-value))
                  :disabled @loading?
                  :style {:width "100%"
                          :padding "10px 12px"
                          :border (str "1px solid " (settings/get-color :border))
                          :border-radius "4px"
                          :background (settings/get-color :editor-bg)
                          :color (settings/get-color :text)
                          :font-size "1rem"
                          :box-sizing "border-box"}}]]

        ;; Password
        [:div {:style {:margin-bottom "15px"}}
         [:label {:style {:display "block"
                          :margin-bottom "5px"
                          :font-size "0.85rem"
                          :color (settings/get-color :text-muted)}}
          "Password"]
         [:input {:type "password"
                  :value @password
                  :on-change #(reset! password (-> % .-target .-value))
                  :disabled @loading?
                  :style {:width "100%"
                          :padding "10px 12px"
                          :border (str "1px solid " (settings/get-color :border))
                          :border-radius "4px"
                          :background (settings/get-color :editor-bg)
                          :color (settings/get-color :text)
                          :font-size "1rem"
                          :box-sizing "border-box"}}]]

        ;; Confirm password (register only)
        (when (= @mode :register)
          [:div {:style {:margin-bottom "15px"}}
           [:label {:style {:display "block"
                            :margin-bottom "5px"
                            :font-size "0.85rem"
                            :color (settings/get-color :text-muted)}}
            "Conferma password"]
           [:input {:type "password"
                    :value @password2
                    :on-change #(reset! password2 (-> % .-target .-value))
                    :disabled @loading?
                    :style {:width "100%"
                            :padding "10px 12px"
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :background (settings/get-color :editor-bg)
                            :color (settings/get-color :text)
                            :font-size "1rem"
                            :box-sizing "border-box"}}]])

        ;; Submit button
        [:button {:type "submit"
                  :disabled @loading?
                  :style {:width "100%"
                          :padding "12px"
                          :background (settings/get-color :accent)
                          :color "white"
                          :border "none"
                          :border-radius "4px"
                          :font-size "1rem"
                          :cursor (if @loading? "wait" "pointer")
                          :opacity (if @loading? 0.7 1)}}
         (if @loading?
           "..."
           (if (= @mode :login) "Accedi" "Registrati"))]]

       ;; Switch mode link
       [:div {:style {:text-align "center" :margin-top "20px"}}
        [:span {:style {:color (settings/get-color :text-muted) :font-size "0.9rem"}}
         (if (= @mode :login)
           "Non hai un account? "
           "Hai già un account? ")]
        [:a {:href "#"
             :on-click (fn [e]
                         (.preventDefault e)
                         (reset! mode (if (= @mode :login) :register :login))
                         (reset! error nil))
             :style {:color (settings/get-color :accent)
                     :text-decoration "none"
                     :font-size "0.9rem"}}
         (if (= @mode :login) "Registrati" "Accedi")]]])))

;; =============================================================================
;; Projects List
;; =============================================================================

(defn- update-title-in-content
  "Update the title field in YAML frontmatter of TRMD content.
   If there's a title: line, replace it. Otherwise add it after ---"
  [content new-title]
  (if (and content (str/starts-with? (str/trim content) "---"))
    (let [lines (str/split-lines content)
          ;; Find the closing --- of frontmatter
          end-idx (loop [i 1]
                    (when (< i (count lines))
                      (if (str/starts-with? (str/trim (nth lines i)) "---")
                        i
                        (recur (inc i)))))]
      (if end-idx
        ;; We have frontmatter, update or add title
        (let [frontmatter-lines (subvec (vec lines) 0 (inc end-idx))
              rest-lines (subvec (vec lines) (inc end-idx))
              ;; Check if there's already a title line
              title-idx (loop [i 1]
                          (when (< i end-idx)
                            (if (re-find #"^title\s*:" (nth lines i))
                              i
                              (recur (inc i)))))
              updated-fm (if title-idx
                           ;; Replace existing title
                           (assoc frontmatter-lines title-idx (str "title: " new-title))
                           ;; Add title after first ---
                           (vec (concat [(first frontmatter-lines)
                                         (str "title: " new-title)]
                                        (subvec frontmatter-lines 1))))]
          (str/join "\n" (concat updated-fm rest-lines)))
        ;; No valid frontmatter end found
        content))
    ;; No frontmatter, add one with title
    (str "---\ntitle: " new-title "\n---\n\n" content)))

(defn- parse-metadata-cache
  "Parse metadata_cache JSON string into a map"
  [project]
  (if-let [cache (:metadata_cache project)]
    (try
      (js->clj (.parse js/JSON cache) :keywordize-keys true)
      (catch :default _
        {}))
    {}))

(defn- fuzzy-match?
  "Check if query matches any metadata value (case insensitive)"
  [query metadata]
  (let [q (str/lower-case (str/trim query))
        ;; Get all string values from metadata (excluding word_count and char_count)
        values (->> metadata
                    (filter (fn [[k v]]
                              (and (string? v)
                                   (not (#{:word_count :char_count} k)))))
                    (map (fn [[_ v]] (str/lower-case v))))]
    (some #(str/includes? % q) values)))

(defn- format-word-count
  "Format word count for display"
  [n]
  (cond
    (nil? n) ""
    (< n 1000) (str n)
    (< n 1000000) (str (quot n 1000) "k")
    :else (str (quot n 1000000) "M")))

(defn- project-card-trash
  "Project card for trashed projects with restore/permanent-delete actions"
  [{:keys [project confirm-permanent-delete-id load-trash! load-projects!]}]
  (let [metadata (parse-metadata-cache project)
        word-count (:word_count metadata)]
    [:div {:style {:background (settings/get-color :background)
                   :border (str "1px dashed " (settings/get-color :border))
                   :border-radius "6px"
                   :padding "15px"
                   :opacity 0.8}}
     ;; Project name
     [:div {:style {:font-weight "500"
                    :color (settings/get-color :text-muted)
                    :margin-bottom "8px"}}
      (:name project)]
     ;; Word count and date
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :font-size "0.8rem"
                    :color (settings/get-color :text-muted)}}
      [:span (t :trash)]
      [:span {:style {:display "flex" :gap "10px"}}
       (when (and word-count (pos? word-count))
         [:span {:title (str word-count " parole")}
          (str (format-word-count word-count) " parole")])
       [:span (when-let [date (:updated_at project)]
                (subs date 0 10))]]]
     ;; Action buttons
     [:div {:style {:display "flex"
                    :gap "8px"
                    :margin-top "12px"
                    :padding-top "12px"
                    :border-top (str "1px solid " (settings/get-color :border))}}
      ;; Restore button
      [:button {:on-click (fn [e]
                            (.stopPropagation e)
                            (-> (api/restore-project! (:id project))
                                (.then (fn [result]
                                         (when (:ok result)
                                           (events/show-toast! (t :project-restored))
                                           (load-trash!)
                                           (load-projects!))))))
                :style {:flex 1
                        :padding "6px 10px"
                        :background "transparent"
                        :color (settings/get-color :success)
                        :border (str "1px solid " (settings/get-color :success))
                        :border-radius "4px"
                        :cursor "pointer"
                        :font-size "0.8rem"}}
       (t :restore)]
      ;; Permanent delete button with confirmation
      (if (= @confirm-permanent-delete-id (:id project))
        [:<>
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (-> (api/permanent-delete-project! (:id project))
                                   (.then (fn [result]
                                            (reset! confirm-permanent-delete-id nil)
                                            (when (:ok result)
                                              (events/show-toast! (t :project-permanently-deleted))
                                              (load-trash!))))))
                   :style {:flex 1
                           :padding "6px 10px"
                           :background (settings/get-color :danger)
                           :color "white"
                           :border "none"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.8rem"}}
          (t :confirm)]
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (reset! confirm-permanent-delete-id nil))
                   :style {:flex 1
                           :padding "6px 10px"
                           :background "transparent"
                           :color (settings/get-color :text-muted)
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.8rem"}}
          (t :cancel)]]
        [:button {:on-click (fn [e]
                              (.stopPropagation e)
                              (reset! confirm-permanent-delete-id (:id project)))
                  :style {:flex 1
                          :padding "6px 10px"
                          :background "transparent"
                          :color (settings/get-color :danger)
                          :border (str "1px solid " (settings/get-color :danger))
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.8rem"}}
         (t :permanent-delete)])]]))

(defn- project-card
  "Single project card with duplicate/delete actions"
  [{:keys [project on-open duplicating-id confirm-delete-id load-projects!]}]
  (let [is-owner? (= (:user_role project) "owner")
        metadata (parse-metadata-cache project)
        word-count (:word_count metadata)]
    [:div {:style {:background (if is-owner?
                                 (settings/get-color :sidebar)
                                 (settings/get-color :background))
                   :border (str "1px solid " (settings/get-color :border))
                   :border-radius "6px"
                   :padding "15px"
                   :transition "border-color 0.2s"}}
   ;; Project name (clickable)
   [:div {:style {:font-weight "500"
                  :color (settings/get-color :text)
                  :margin-bottom "8px"
                  :cursor "pointer"}
          :on-click #(when on-open (on-open project))}
    (:name project)]
   ;; Role, word count and date
   [:div {:style {:display "flex"
                  :justify-content "space-between"
                  :font-size "0.8rem"
                  :color (settings/get-color :text-muted)}}
    [:span (case (:user_role project)
             "owner" "Proprietario"
             "admin" "Admin"
             "collaborator" "Collaboratore"
             (:user_role project))]
    [:span {:style {:display "flex" :gap "10px"}}
     (when (and word-count (pos? word-count))
       [:span {:title (str word-count " parole")}
        (str (format-word-count word-count) " parole")])
     [:span (when-let [date (:updated_at project)]
              (subs date 0 10))]]]
   ;; Action buttons
   [:div {:style {:display "flex"
                  :gap "8px"
                  :margin-top "12px"
                  :padding-top "12px"
                  :border-top (str "1px solid " (settings/get-color :border))}}
    ;; Duplicate button (available for everyone)
    [:button {:on-click (fn [e]
                          (.stopPropagation e)
                          (reset! duplicating-id (:id project))
                          (-> (api/get-project (:id project))
                              (.then (fn [result]
                                       (when (:ok result)
                                         (let [new-name (str (:name project) " (copia)")
                                               original-content (get-in result [:data :content])
                                               ;; Update title in YAML frontmatter to match new name
                                               updated-content (update-title-in-content original-content new-name)]
                                           (-> (api/create-project! new-name updated-content)
                                               (.then (fn [cr]
                                                        (reset! duplicating-id nil)
                                                        (when (:ok cr)
                                                          (events/show-toast! "Progetto duplicato")
                                                          (load-projects!)))))))))))
              :disabled (= @duplicating-id (:id project))
              :style {:flex 1
                      :padding "6px 10px"
                      :background "transparent"
                      :color (settings/get-color :text-muted)
                      :border (str "1px solid " (settings/get-color :border))
                      :border-radius "4px"
                      :cursor "pointer"
                      :font-size "0.8rem"}}
     (if (= @duplicating-id (:id project)) "..." "Duplica")]
    ;; Delete button (only for owners)
    (when is-owner?
      (if (= @confirm-delete-id (:id project))
        [:<>
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (-> (api/delete-project! (:id project))
                                   (.then (fn [result]
                                            (reset! confirm-delete-id nil)
                                            (when (:ok result)
                                              (events/show-toast! "Progetto eliminato")
                                              (load-projects!))))))
                   :style {:flex 1
                           :padding "6px 10px"
                           :background (settings/get-color :danger)
                           :color "white"
                           :border "none"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.8rem"}}
          "Conferma"]
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (reset! confirm-delete-id nil))
                   :style {:flex 1
                           :padding "6px 10px"
                           :background "transparent"
                           :color (settings/get-color :text-muted)
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.8rem"}}
          "Annulla"]]
        [:button {:on-click (fn [e]
                              (.stopPropagation e)
                              (reset! confirm-delete-id (:id project)))
                  :style {:flex 1
                          :padding "6px 10px"
                          :background "transparent"
                          :color (settings/get-color :danger)
                          :border (str "1px solid " (settings/get-color :danger))
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.8rem"}}
         "Elimina"]))]]))

(defn projects-list
  "List of server projects with create/open/delete actions"
  [{:keys [on-open on-create]}]
  (let [projects (r/atom nil)
        loading? (r/atom true)
        error (r/atom nil)
        creating? (r/atom false)
        show-import-form? (r/atom false)
        import-type (r/atom :trmd)
        importing? (r/atom false)
        import-file-input (r/atom nil)
        duplicating-id (r/atom nil)
        confirm-delete-id (r/atom nil)
        confirm-permanent-delete-id (r/atom nil)
        filter-text (r/atom "")
        ;; Trash state
        show-trash? (r/atom false)
        trash-projects (r/atom nil)
        trash-count (r/atom 0)
        load-trash! (fn []
                      (-> (api/list-trash)
                          (.then (fn [result]
                                   (when (:ok result)
                                     (let [trash (get-in result [:data :projects])]
                                       (reset! trash-projects trash)
                                       (reset! trash-count (count trash))))))))
        load-projects! (fn []
                         (reset! loading? true)
                         (-> (api/list-projects)
                             (.then (fn [result]
                                      (reset! loading? false)
                                      (if (:ok result)
                                        (reset! projects (get-in result [:data :projects]))
                                        (reset! error (:error result))))))
                         ;; Also load trash count
                         (load-trash!))
        generate-project-name (fn []
                                ;; Generate "Nuovo progetto" or "Nuovo progetto 2", etc.
                                (let [existing-names (set (map :name @projects))
                                      base-name "Nuovo progetto"]
                                  (if-not (existing-names base-name)
                                    base-name
                                    (loop [n 2]
                                      (let [name-n (str base-name " " n)]
                                        (if-not (existing-names name-n)
                                          name-n
                                          (recur (inc n))))))))]
    ;; Load on mount
    (load-projects!)
    (fn [{:keys [on-open on-create]}]
      [:div {:style {:padding "20px"}}
       ;; Header
       [:div {:style {:display "flex"
                      :justify-content "space-between"
                      :align-items "center"
                      :margin-bottom "20px"
                      :gap "15px"}}
        [:div {:style {:display "flex" :align-items "center" :gap "15px"}}
         [:h2 {:style {:margin 0
                       :font-weight "400"
                       :color (settings/get-color :text)
                       :white-space "nowrap"}}
          (if @show-trash? (t :trash) (t :your-projects))]
         ;; Trash toggle button
         [:button {:on-click #(swap! show-trash? not)
                   :style {:padding "6px 12px"
                           :background (if @show-trash?
                                         (settings/get-color :danger)
                                         "transparent")
                           :color (if @show-trash?
                                    "white"
                                    (settings/get-color :text-muted))
                           :border (str "1px solid " (if @show-trash?
                                                       (settings/get-color :danger)
                                                       (settings/get-color :border)))
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.85rem"
                           :display "flex"
                           :align-items "center"
                           :gap "6px"}}
          "🗑"
          (when (and (not @show-trash?) (pos? @trash-count))
            [:span {:style {:background (settings/get-color :danger)
                            :color "white"
                            :padding "2px 6px"
                            :border-radius "10px"
                            :font-size "0.75rem"}}
             @trash-count])]]
        ;; Filter input
        [:div {:style {:flex 1
                       :max-width "300px"}}
         [:input {:type "text"
                  :placeholder "Cerca nei metadati..."
                  :value @filter-text
                  :on-change #(reset! filter-text (-> % .-target .-value))
                  :on-key-down (fn [e]
                                 (when (= (.-key e) "Escape")
                                   (reset! filter-text "")))
                  :style {:width "100%"
                          :padding "8px 12px"
                          :border (str "1px solid " (settings/get-color :border))
                          :border-radius "4px"
                          :background (settings/get-color :editor-bg)
                          :color (settings/get-color :text)
                          :font-size "0.9rem"}}]]
        [:div {:style {:display "flex" :gap "10px"}}
         [:button {:on-click #(reset! show-import-form? true)
                   :style {:padding "8px 16px"
                           :background "transparent"
                           :color (settings/get-color :text-muted)
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :cursor "pointer"}}
          "↑ " (t :import)]
         [:button {:on-click (fn []
                              (when-not @creating?
                                (reset! creating? true)
                                (let [project-name (generate-project-name)]
                                  (-> (api/create-project! project-name "")
                                      (.then (fn [result]
                                               (reset! creating? false)
                                               (when (:ok result)
                                                 (load-projects!)
                                                 (when on-create
                                                   (on-create (get-in result [:data :project]))))))))))
                   :disabled @creating?
                   :style {:padding "8px 16px"
                           :background (settings/get-color :accent)
                           :color "white"
                           :border "none"
                           :border-radius "4px"
                           :cursor "pointer"
                           :opacity (if @creating? 0.5 1)}}
          (if @creating? "..." "+ Nuovo progetto")]]]

       ;; Import form
       (when @show-import-form?
         [:div {:style {:background (settings/get-color :sidebar)
                        :border (str "1px solid " (settings/get-color :border))
                        :border-radius "6px"
                        :padding "15px"
                        :margin-bottom "20px"}}
          [:div {:style {:margin-bottom "12px"
                         :font-size "0.9rem"
                         :color (settings/get-color :text)}}
           (t :import-select-file)]
          ;; Format selector
          [:div {:style {:display "flex" :gap "10px" :margin-bottom "12px"}}
           [:button {:on-click #(reset! import-type :trmd)
                     :style {:padding "6px 12px"
                             :background (if (= @import-type :trmd)
                                           (settings/get-color :accent)
                                           "transparent")
                             :color (if (= @import-type :trmd)
                                      "white"
                                      (settings/get-color :text-muted))
                             :border (str "1px solid " (if (= @import-type :trmd)
                                                         (settings/get-color :accent)
                                                         (settings/get-color :border)))
                             :border-radius "4px"
                             :cursor "pointer"
                             :font-size "0.85rem"}}
            ".trmd"]
           [:button {:on-click #(reset! import-type :md)
                     :style {:padding "6px 12px"
                             :background (if (= @import-type :md)
                                           (settings/get-color :accent)
                                           "transparent")
                             :color (if (= @import-type :md)
                                      "white"
                                      (settings/get-color :text-muted))
                             :border (str "1px solid " (if (= @import-type :md)
                                                         (settings/get-color :accent)
                                                         (settings/get-color :border)))
                             :border-radius "4px"
                             :cursor "pointer"
                             :font-size "0.85rem"}}
            ".md"]
           [:button {:on-click #(reset! import-type :docx)
                     :style {:padding "6px 12px"
                             :background (if (= @import-type :docx)
                                           (settings/get-color :accent)
                                           "transparent")
                             :color (if (= @import-type :docx)
                                      "white"
                                      (settings/get-color :text-muted))
                             :border (str "1px solid " (if (= @import-type :docx)
                                                         (settings/get-color :accent)
                                                         (settings/get-color :border)))
                             :border-radius "4px"
                             :cursor "pointer"
                             :font-size "0.85rem"}}
            ".docx"]]
          ;; Hidden file input
          [:input {:type "file"
                   :accept (case @import-type
                             :trmd ".trmd"
                             :md ".md,.txt"
                             :docx ".docx")
                   :style {:display "none"}
                   :ref #(reset! import-file-input %)
                   :on-change (fn [e]
                                (when-let [file (-> e .-target .-files (aget 0))]
                                  (reset! importing? true)
                                  (let [filename (.-name file)
                                        ;; Extract project name from filename
                                        project-name (-> filename
                                                         (str/replace #"\.(trmd|md|txt|docx)$" ""))
                                        reader (js/FileReader.)
                                        create-project-with-content!
                                        (fn [content]
                                          (js/console.log "Import: creating project with content length =" (count content))
                                          (-> (api/create-project! project-name content)
                                              (.then (fn [result]
                                                       (js/console.log "Import: create-project result =" (pr-str result))
                                                       (reset! importing? false)
                                                       (when (:ok result)
                                                         (reset! show-import-form? false)
                                                         (load-projects!)
                                                         (when on-create
                                                           (on-create (get-in result [:data :project]))))))))]
                                    (if (str/ends-with? filename ".docx")
                                      ;; DOCX: read as ArrayBuffer and convert with mammoth
                                      (do
                                        (set! (.-onload reader)
                                              (fn [evt]
                                                (let [array-buffer (-> evt .-target .-result)]
                                                  (-> (model/docx-to-markdown array-buffer)
                                                      (.then (fn [md-content]
                                                               ;; Convert markdown to TRMD
                                                               (create-project-with-content! (md-to-trmd md-content))))
                                                      (.catch (fn [err]
                                                                (reset! importing? false)
                                                                (js/alert (str "Errore importazione DOCX: " err))))))))
                                        (.readAsArrayBuffer reader file))
                                      ;; TRMD/MD/TXT: read as text
                                      (do
                                        (set! (.-onload reader)
                                              (fn [evt]
                                                (let [raw-content (-> evt .-target .-result)
                                                      ;; Convert MD to TRMD if needed
                                                      content (if (or (str/ends-with? filename ".md")
                                                                      (str/ends-with? filename ".txt"))
                                                                (md-to-trmd raw-content)
                                                                raw-content)]
                                                  (create-project-with-content! content))))
                                        (.readAsText reader file)))))
                                ;; Reset input
                                (set! (-> e .-target .-value) ""))}]
          ;; Buttons
          [:div {:style {:display "flex" :gap "10px"}}
           [:button {:on-click #(when @import-file-input (.click @import-file-input))
                     :disabled @importing?
                     :style {:padding "8px 16px"
                             :background (settings/get-color :accent)
                             :color "white"
                             :border "none"
                             :border-radius "4px"
                             :cursor "pointer"
                             :opacity (if @importing? 0.5 1)}}
            (if @importing? "..." (t :import-select-file))]
           [:button {:on-click #(reset! show-import-form? false)
                     :style {:padding "8px 12px"
                             :background "transparent"
                             :color (settings/get-color :text-muted)
                             :border (str "1px solid " (settings/get-color :border))
                             :border-radius "4px"
                             :cursor "pointer"}}
            "Annulla"]]])

       ;; Error
       (when @error
         [:div {:style {:color (settings/get-color :danger) :margin-bottom "15px"}}
          @error])

       ;; Loading
       (when @loading?
         [:div {:style {:text-align "center"
                        :padding "40px"
                        :color (settings/get-color :text-muted)}}
          "Caricamento..."])

       ;; Projects grid (normal or trash)
       (if @show-trash?
         ;; Trash view
         (if (empty? @trash-projects)
           [:div {:style {:text-align "center"
                          :padding "40px"
                          :color (settings/get-color :text-muted)}}
            (t :empty-trash)]
           [:div {:style {:display "grid"
                          :grid-template-columns "repeat(auto-fill, minmax(280px, 1fr))"
                          :gap "15px"}}
            (for [project @trash-projects]
              ^{:key (:id project)}
              [project-card-trash {:project project
                                   :confirm-permanent-delete-id confirm-permanent-delete-id
                                   :load-trash! load-trash!
                                   :load-projects! load-projects!}])])
         ;; Normal view
         (when (and (not @loading?) @projects)
           (let [query (str/trim @filter-text)
               filtered-projects (if (empty? query)
                                   @projects
                                   (filter (fn [p]
                                             (let [metadata (parse-metadata-cache p)
                                                   ;; Also search in project name
                                                   metadata-with-name (assoc metadata :name (:name p))]
                                               (fuzzy-match? query metadata-with-name)))
                                           @projects))]
           (if (empty? @projects)
             [:div {:style {:text-align "center"
                            :padding "40px"
                            :color (settings/get-color :text-muted)}}
              "Nessun progetto. Crea il tuo primo progetto!"]
             (if (and (seq query) (empty? filtered-projects))
               [:div {:style {:text-align "center"
                              :padding "40px"
                              :color (settings/get-color :text-muted)}}
                "Nessun progetto corrisponde alla ricerca."]
               [:div {:style {:display "grid"
                              :grid-template-columns "repeat(auto-fill, minmax(280px, 1fr))"
                              :gap "15px"}}
                (for [project filtered-projects]
                  ^{:key (:id project)}
                  [project-card {:project project
                                 :on-open on-open
                                 :duplicating-id duplicating-id
                                 :confirm-delete-id confirm-delete-id
                                 :load-projects! load-projects!}])])))))])))


;; =============================================================================
;; Modal Wrapper (reusable component for all modals)
;; =============================================================================

(defn modal-wrapper
  "Reusable modal wrapper that handles:
   - Overlay with optional click-outside to close
   - ESC key to close
   - Focus management (focus once on mount)
   - Stop propagation of keyboard events
   - Consistent styling

   Props:
   - on-close: function to call when closing
   - on-enter: optional function to call on Enter key (if not in input/textarea/select)
   - disable-esc?: optional atom or function that returns true to disable ESC handling
   - disable-click-outside?: if true, clicking outside won't close (default false)
   - z-index: z-index for the modal (default 2000)
   - width: width of the modal content (default \"450px\")
   - max-height: max height (default \"80vh\")
   - title: optional title for the header
   - show-close-button?: whether to show the X button (default true)
   - children: the modal content"
  [{:keys [_on-close _on-enter _disable-esc? _disable-click-outside? _z-index _width _max-height _title _show-close-button?]}]
  (let [el-ref (atom nil)
        mounted? (atom false)
        was-blocked? (atom false)]
    (fn [{:keys [on-close on-enter disable-esc? disable-click-outside? z-index width max-height title show-close-button?]
          :or {z-index 2000 width "450px" max-height "80vh" show-close-button? true disable-click-outside? false}}
         & children]
      ;; Re-focus only when child modal closes (disable-esc? transitions from true to false)
      (let [child-blocking? (cond
                              (nil? disable-esc?) false
                              (fn? disable-esc?) (disable-esc?)
                              (satisfies? IDeref disable-esc?) @disable-esc?
                              :else disable-esc?)]
        ;; Only re-focus when transitioning from blocked to unblocked
        (when (and @mounted? @el-ref @was-blocked? (not child-blocking?))
          ;; Schedule focus for next tick to let child modal fully unmount
          (js/setTimeout #(when @el-ref (.focus @el-ref)) 10))
        (reset! was-blocked? child-blocking?))
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index z-index}
             :tab-index -1
             :ref (fn [el] (when el
                             (reset! el-ref el)
                             ;; Only focus on initial mount, not on every render
                             (when-not @mounted?
                               (reset! mounted? true)
                               (.focus el))))
             ;; Use mousedown to avoid closing when text selection drag ends outside
             :on-mouse-down (fn [e]
                              (when (and (not disable-click-outside?)
                                         (= (.-target e) (.-currentTarget e)))
                                (on-close)))
             :on-key-down (fn [e]
                            ;; Always stop propagation
                            (.stopPropagation e)
                            (let [tag-name (.-tagName (.-target e))
                                  ;; Evaluate disable-esc? at event time, not render time
                                  esc-disabled? (cond
                                                  (nil? disable-esc?) false
                                                  (fn? disable-esc?) (disable-esc?)
                                                  (satisfies? IDeref disable-esc?) @disable-esc?
                                                  :else disable-esc?)]
                              (case (.-key e)
                                "Escape" (when-not esc-disabled?
                                           (.preventDefault e)
                                           (on-close))
                                "Enter" (when (and on-enter
                                                   (not (#{"TEXTAREA" "SELECT" "INPUT"} tag-name)))
                                          (.preventDefault e)
                                          (on-enter))
                                nil)))}
         [:div {:style {:background (settings/get-color :background)
                        :border-radius "8px"
                        :width width
                        :max-height max-height
                        :overflow "auto"
                        :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}}
          ;; Optional header with title
          (when (or title show-close-button?)
            [:div {:style {:display "flex"
                           :justify-content "space-between"
                           :align-items "center"
                           :padding "15px 20px"
                           :border-bottom (str "1px solid " (settings/get-color :border))}}
             (when title
               [:h2 {:style {:margin 0
                             :font-weight "400"
                             :font-size "1.2rem"
                             :color (settings/get-color :text)}}
                title])
             (when show-close-button?
               [:button {:on-click on-close
                         :style {:background "transparent"
                                 :border "none"
                                 :font-size "1.5rem"
                                 :cursor "pointer"
                                 :color (settings/get-color :text-muted)
                                 :margin-left "auto"}}
                "×"])])
          ;; Content
          (into [:<>] children)]])))

;; =============================================================================
;; Collaborators Panel
;; =============================================================================

(defn collaborators-panel
  "Panel to manage project collaborators"
  [{:keys [project-id]}]
  (let [data (r/atom nil)
        all-users (r/atom nil)  ;; All users for selection
        loading? (r/atom true)
        selected-user-id (r/atom "")  ;; Selected user ID from dropdown
        adding? (r/atom false)
        error (r/atom nil)
        load-data! (fn []
                     (reset! loading? true)
                     ;; Load collaborators
                     (-> (api/list-collaborators project-id)
                         (.then (fn [result]
                                  (if (:ok result)
                                    (let [collab-data (:data result)]
                                      (reset! data collab-data)
                                      ;; Cache display names globally
                                      (auth/cache-users-from-collaborators! collab-data))
                                    (reset! error (:error result))))))
                     ;; Load all users for selection (basic info, available to all users)
                     (-> (api/list-users-basic)
                         (.then (fn [result]
                                  (reset! loading? false)
                                  (when (:ok result)
                                    (reset! all-users (get-in result [:data :users])))))))]
    (load-data!)
    (fn [{:keys [project-id]}]
      [:div {:style {:padding "15px"}}
       [:h3 {:style {:margin "0 0 15px 0"
                     :font-weight "500"
                     :color (settings/get-color :text)}}
        "Collaboratori"]

       (when @loading?
         [:div {:style {:color (settings/get-color :text-muted)}} "Caricamento..."])

       (when @error
         [:div {:style {:color (settings/get-color :danger) :margin-bottom "10px"}} @error])

       (when @data
         [:div
          ;; Owner
          [:div {:style {:margin-bottom "15px"
                         :padding-bottom "15px"
                         :border-bottom (str "1px solid " (settings/get-color :border))}}
           [:div {:style {:font-size "0.75rem"
                          :text-transform "uppercase"
                          :color (settings/get-color :text-muted)
                          :margin-bottom "5px"}}
            "Proprietario"]
           [:div {:style {:color (settings/get-color :text)}}
            (auth/get-display-name (:owner @data))]]

          ;; Collaborators list
          (when (seq (:collaborators @data))
            [:div {:style {:margin-bottom "15px"}}
             [:div {:style {:font-size "0.75rem"
                            :text-transform "uppercase"
                            :color (settings/get-color :text-muted)
                            :margin-bottom "8px"}}
              "Collaboratori"]
             (for [collab (:collaborators @data)]
               ^{:key (:id collab)}
               [:div {:style {:display "flex"
                              :justify-content "space-between"
                              :align-items "center"
                              :padding "8px 0"
                              :border-bottom (str "1px solid " (settings/get-color :border))}}
                [:span {:style {:color (settings/get-color :text)}}
                 (auth/get-display-name collab)
                 [:span {:style {:margin-left "8px"
                                 :font-size "0.8rem"
                                 :color (settings/get-color :text-muted)}}
                  (str "(" (:role collab) ")")]]
                [:button {:on-click (fn []
                                      (-> (api/remove-collaborator! project-id (:id collab))
                                          (.then (fn [_] (load-data!)))))
                          :style {:background "transparent"
                                  :border "none"
                                  :color (settings/get-color :danger)
                                  :cursor "pointer"
                                  :font-size "0.9rem"}}
                 "Rimuovi"]])])

          ;; Add collaborator form
          [:div {:style {:margin-top "15px"
                         :padding-top "15px"
                         :border-top (str "1px solid " (settings/get-color :border))}}
           [:div {:style {:font-size "0.75rem"
                          :text-transform "uppercase"
                          :color (settings/get-color :text-muted)
                          :margin-bottom "8px"}}
            "Aggiungi collaboratore"]
           [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap"}}
            [:select {:value @selected-user-id
                      :on-change #(reset! selected-user-id (-> % .-target .-value))
                      :style {:flex "1"
                              :min-width "150px"
                              :padding "6px 10px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)}}
             [:option {:value ""} "-- Seleziona utente --"]
             (let [owner-id (get-in @data [:owner :id])
                   collab-ids (set (map :id (:collaborators @data)))
                   current-user-id (:id (auth/get-user))
                   available-users (->> @all-users
                                        (filter #(and (not= (:id %) owner-id)
                                                      (not (collab-ids (:id %)))
                                                      (not= (:id %) current-user-id)
                                                      (= "active" (or (:status %) "active")))))]
               (for [user available-users]
                 ^{:key (:id user)}
                 [:option {:value (:username user)}
                  (str (auth/get-display-name user) " (@" (:username user) ")")]))]
            [:button {:on-click (fn []
                                  (when (seq @selected-user-id)
                                    (reset! adding? true)
                                    (-> (api/add-collaborator! project-id @selected-user-id "collaborator")
                                        (.then (fn [result]
                                                 (reset! adding? false)
                                                 (if (:ok result)
                                                   (do (reset! selected-user-id "")
                                                       (load-data!))
                                                   (reset! error (:error result))))))))
                      :disabled (or @adding? (empty? @selected-user-id))
                      :style {:padding "6px 12px"
                              :background (settings/get-color :accent)
                              :color "white"
                              :border "none"
                              :border-radius "4px"
                              :cursor "pointer"}}
             (if @adding? "..." "Aggiungi")]]]])])))

;; =============================================================================
;; Mode Selector (Local vs Server)
;; =============================================================================

(defn mode-selector
  "Initial screen to choose between local and server mode"
  [{:keys [on-local on-server]}]
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :justify-content "center"
                 :min-height "100vh"
                 :padding "20px"
                 :background (settings/get-color :background)}}
   [:h1 {:style {:font-size "2.5rem"
                 :font-weight "300"
                 :color (settings/get-color :text)
                 :margin-bottom "40px"}}
    "Tramando"]

   [:div {:style {:display "flex"
                  :gap "20px"
                  :flex-wrap "wrap"
                  :justify-content "center"}}
    ;; Local mode
    [:div {:style {:width "280px"
                   :padding "30px"
                   :background (settings/get-color :sidebar)
                   :border (str "2px solid " (settings/get-color :border))
                   :border-radius "12px"
                   :cursor "pointer"
                   :transition "all 0.2s"}
           :on-mouse-over #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :accent))
           :on-mouse-out #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :border))
           :on-click on-local}
     [:div {:style {:font-size "2rem" :text-align "center" :margin-bottom "15px"}} "💻"]
     [:h3 {:style {:text-align "center"
                   :color (settings/get-color :text)
                   :margin "0 0 10px 0"}}
      "Lavora in locale"]
     [:p {:style {:text-align "center"
                  :color (settings/get-color :text-muted)
                  :font-size "0.9rem"
                  :margin 0}}
      "I file restano sul tuo computer. Nessun account richiesto."]]

    ;; Server mode
    [:div {:style {:width "280px"
                   :padding "30px"
                   :background (settings/get-color :sidebar)
                   :border (str "2px solid " (settings/get-color :border))
                   :border-radius "12px"
                   :cursor "pointer"
                   :transition "all 0.2s"}
           :on-mouse-over #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :accent))
           :on-mouse-out #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :border))
           :on-click on-server}
     [:div {:style {:font-size "2rem" :text-align "center" :margin-bottom "15px"}} "☁️"]
     [:h3 {:style {:text-align "center"
                   :color (settings/get-color :text)
                   :margin "0 0 10px 0"}}
      "Modalità collaborativa"]
     [:p {:style {:text-align "center"
                  :color (settings/get-color :text-muted)
                  :font-size "0.9rem"
                  :margin 0}}
      "Accedi per lavorare in team e sincronizzare i progetti."]]]])

;; =============================================================================
;; Collaborators Modal (wraps collaborators-panel in a modal)
;; =============================================================================

(defn collaborators-modal
  "Modal wrapper for collaborators panel"
  [{:keys [project-id on-close]}]
  [modal-wrapper {:on-close on-close
                  :title "Collaboratori"
                  :disable-click-outside? true}
   [collaborators-panel {:project-id project-id}]])

;; =============================================================================
;; User Edit Modal (used by admin panel)
;; =============================================================================

(defn- user-edit-modal
  "Modal for editing a user's details"
  [{:keys [user on-close on-save]}]
  (let [form-display-name (r/atom (or (:display_name user) ""))
        form-email (r/atom (or (:email user) ""))
        form-status (r/atom (or (:status user) "active"))
        form-max-projects (r/atom (or (:max_projects user) 10))
        form-max-project-size (r/atom (or (:max_project_size_mb user) 50))
        form-max-collaborators (r/atom (or (:max_collaborators user) 10))
        form-notes (r/atom (or (:notes user) ""))
        form-is-admin? (r/atom (= 1 (:is_super_admin user)))
        ;; Password reset
        new-password (r/atom "")
        confirm-password (r/atom "")
        resetting-password? (r/atom false)
        password-success (r/atom false)
        password-error (r/atom nil)
        saving? (r/atom false)
        form-error (r/atom nil)
        do-save! (fn [user on-close on-save]
                   (when-not @saving?
                     (reset! saving? true)
                     (reset! form-error nil)
                     (-> (api/update-user-admin! (:id user)
                           {:display_name @form-display-name
                            :email @form-email
                            :status @form-status
                            :max_projects @form-max-projects
                            :max_project_size_mb @form-max-project-size
                            :max_collaborators @form-max-collaborators
                            :notes @form-notes
                            :is-super-admin (when (not= (:id user) (:id (auth/get-user)))
                                              @form-is-admin?)})
                         (.then (fn [result]
                                  (reset! saving? false)
                                  (if (:ok result)
                                    (do
                                      (on-close)
                                      (on-save))
                                    (reset! form-error (:error result))))))))]
    (fn [{:keys [user on-close on-save]}]
      [modal-wrapper {:on-close on-close
                      :on-enter #(do-save! user on-close on-save)
                      :z-index 3000
                      :width "500px"
                      :max-height "85vh"
                      :title (str "Modifica: " (:username user))}
        ;; Form
        [:div {:style {:padding "20px"
                       :display "flex"
                       :flex-direction "column"
                       :gap "15px"}}
         ;; Error
         (when @form-error
           [:div {:style {:background (settings/get-color :danger)
                          :color "white"
                          :padding "10px"
                          :border-radius "4px"}}
            @form-error])

         ;; Display name
         [:div
          [:label {:style {:display "block"
                           :margin-bottom "5px"
                           :font-size "0.85rem"
                           :color (settings/get-color :text-muted)}}
           "Nome visualizzato"]
          [:input {:type "text"
                   :value @form-display-name
                   :on-change #(reset! form-display-name (-> % .-target .-value))
                   :placeholder (:username user)
                   :style {:width "100%"
                           :padding "8px 12px"
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :background (settings/get-color :editor-bg)
                           :color (settings/get-color :text)
                           :box-sizing "border-box"}}]]

         ;; Email
         [:div
          [:label {:style {:display "block"
                           :margin-bottom "5px"
                           :font-size "0.85rem"
                           :color (settings/get-color :text-muted)}}
           "Email"]
          [:input {:type "email"
                   :value @form-email
                   :on-change #(reset! form-email (-> % .-target .-value))
                   :placeholder "email@esempio.it"
                   :style {:width "100%"
                           :padding "8px 12px"
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :background (settings/get-color :editor-bg)
                           :color (settings/get-color :text)
                           :box-sizing "border-box"}}]]

         ;; Status
         [:div
          [:label {:style {:display "block"
                           :margin-bottom "5px"
                           :font-size "0.85rem"
                           :color (settings/get-color :text-muted)}}
           "Stato"]
          [:select {:value @form-status
                    :on-change #(reset! form-status (-> % .-target .-value))
                    :style {:width "100%"
                            :padding "8px 12px"
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :background (settings/get-color :editor-bg)
                            :color (settings/get-color :text)
                            :box-sizing "border-box"}}
           [:option {:value "active"} "Attivo"]
           [:option {:value "pending"} "In attesa"]
           [:option {:value "suspended"} "Sospeso"]]]

         ;; Quotas row
         [:div {:style {:display "grid"
                        :grid-template-columns "1fr 1fr 1fr"
                        :gap "10px"}}
          [:div
           [:label {:style {:display "block"
                            :margin-bottom "5px"
                            :font-size "0.85rem"
                            :color (settings/get-color :text-muted)}}
            "Max progetti"]
           [:input {:type "number"
                    :min 1
                    :value @form-max-projects
                    :on-change #(reset! form-max-projects (js/parseInt (-> % .-target .-value)))
                    :style {:width "100%"
                            :padding "8px 12px"
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :background (settings/get-color :editor-bg)
                            :color (settings/get-color :text)
                            :box-sizing "border-box"}}]]
          [:div
           [:label {:style {:display "block"
                            :margin-bottom "5px"
                            :font-size "0.85rem"
                            :color (settings/get-color :text-muted)}}
            "Max dim. (MB)"]
           [:input {:type "number"
                    :min 1
                    :value @form-max-project-size
                    :on-change #(reset! form-max-project-size (js/parseInt (-> % .-target .-value)))
                    :style {:width "100%"
                            :padding "8px 12px"
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :background (settings/get-color :editor-bg)
                            :color (settings/get-color :text)
                            :box-sizing "border-box"}}]]
          [:div
           [:label {:style {:display "block"
                            :margin-bottom "5px"
                            :font-size "0.85rem"
                            :color (settings/get-color :text-muted)}}
            "Max collab."]
           [:input {:type "number"
                    :min 0
                    :value @form-max-collaborators
                    :on-change #(reset! form-max-collaborators (js/parseInt (-> % .-target .-value)))
                    :style {:width "100%"
                            :padding "8px 12px"
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :background (settings/get-color :editor-bg)
                            :color (settings/get-color :text)
                            :box-sizing "border-box"}}]]]

         ;; Notes
         [:div
          [:label {:style {:display "block"
                           :margin-bottom "5px"
                           :font-size "0.85rem"
                           :color (settings/get-color :text-muted)}}
           "Note"]
          [:textarea {:value @form-notes
                      :on-change #(reset! form-notes (-> % .-target .-value))
                      :placeholder "Note interne sull'utente..."
                      :rows 2
                      :style {:width "100%"
                              :padding "8px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)
                              :box-sizing "border-box"
                              :resize "vertical"}}]]

         ;; Admin checkbox (only if not self)
         (when (not= (:id user) (:id (auth/get-user)))
           [:label {:style {:display "flex"
                            :align-items "center"
                            :gap "8px"
                            :color (settings/get-color :text)}}
            [:input {:type "checkbox"
                     :checked @form-is-admin?
                     :on-change #(reset! form-is-admin? (-> % .-target .-checked))}]
            "Super Admin"])

         ;; Password reset section (only if not self)
         (when (not= (:id user) (:id (auth/get-user)))
           [:div {:style {:margin-top "15px"
                          :padding-top "15px"
                          :border-top (str "1px solid " (settings/get-color :border))}}
            [:div {:style {:font-size "0.85rem"
                           :font-weight "500"
                           :color (settings/get-color :text)
                           :margin-bottom "10px"}}
             "Imposta nuova password"]

            ;; Success message
            (when @password-success
              [:div {:style {:background (settings/get-color :success)
                             :color "white"
                             :padding "8px 12px"
                             :border-radius "4px"
                             :margin-bottom "10px"
                             :font-size "0.85rem"}}
               "Password impostata con successo!"])

            ;; Error message
            (when @password-error
              [:div {:style {:background (settings/get-color :danger)
                             :color "white"
                             :padding "8px 12px"
                             :border-radius "4px"
                             :margin-bottom "10px"
                             :font-size "0.85rem"}}
               @password-error])

            [:div {:style {:display "flex" :gap "10px" :flex-wrap "wrap"}}
             [:input {:type "password"
                      :placeholder "Nuova password"
                      :value @new-password
                      :on-change #(reset! new-password (-> % .-target .-value))
                      :style {:flex "1"
                              :min-width "120px"
                              :padding "8px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)}}]
             [:input {:type "password"
                      :placeholder "Conferma"
                      :value @confirm-password
                      :on-change #(reset! confirm-password (-> % .-target .-value))
                      :style {:flex "1"
                              :min-width "120px"
                              :padding "8px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)}}]
             [:button {:on-click (fn []
                                   (reset! password-error nil)
                                   (reset! password-success false)
                                   (cond
                                     (< (count @new-password) 6)
                                     (reset! password-error "Minimo 6 caratteri")

                                     (not= @new-password @confirm-password)
                                     (reset! password-error "Le password non corrispondono")

                                     :else
                                     (do
                                       (reset! resetting-password? true)
                                       (-> (api/reset-user-password! (:id user) @new-password)
                                           (.then (fn [result]
                                                    (reset! resetting-password? false)
                                                    (if (:ok result)
                                                      (do
                                                        (reset! password-success true)
                                                        (reset! new-password "")
                                                        (reset! confirm-password ""))
                                                      (reset! password-error (:error result)))))))))
                       :disabled (or @resetting-password? (empty? @new-password))
                       :style {:padding "8px 16px"
                               :background (settings/get-color :accent)
                               :color "white"
                               :border "none"
                               :border-radius "4px"
                               :cursor "pointer"
                               :opacity (if (or @resetting-password? (empty? @new-password)) 0.5 1)}}
              (if @resetting-password? "..." "Imposta")]]])

         ;; Buttons
         [:div {:style {:display "flex"
                        :gap "10px"
                        :margin-top "10px"}}
          [:button {:on-click #(do-save! user on-close on-save)
                    :disabled @saving?
                    :style {:flex 1
                            :padding "10px 20px"
                            :background (settings/get-color :accent)
                            :color "white"
                            :border "none"
                            :border-radius "4px"
                            :cursor "pointer"
                            :opacity (if @saving? 0.5 1)}}
           (if @saving? "..." "Salva")]
          [:button {:on-click on-close
                    :style {:padding "10px 20px"
                            :background "transparent"
                            :color (settings/get-color :text-muted)
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :cursor "pointer"}}
           "Annulla"]]]])))

;; =============================================================================
;; Admin Users Panel (Super Admin only)
;; =============================================================================

(defn admin-users-panel
  "Panel for super-admin to manage all users"
  [{:keys [on-close]}]
  (let [users (r/atom nil)
        loading? (r/atom true)
        error (r/atom nil)
        ;; Create user form
        show-create? (r/atom false)
        new-username (r/atom "")
        new-password (r/atom "")
        new-is-admin? (r/atom false)
        creating? (r/atom false)
        ;; Delete confirmation
        confirm-delete-id (r/atom nil)
        ;; Edit user modal
        editing-user (r/atom nil)
        load-users! (fn []
                      (reset! loading? true)
                      (-> (api/list-users)
                          (.then (fn [result]
                                   (reset! loading? false)
                                   (if (:ok result)
                                     (reset! users (get-in result [:data :users]))
                                     (reset! error (:error result)))))))]
    (load-users!)
    (fn [{:keys [on-close]}]
      [modal-wrapper {:on-close on-close
                      :disable-esc? #(some? @editing-user)
                      :width "600px"
                      :title "Gestione Utenti"}
        [:div {:style {:padding "20px"}}
         ;; Error
         (when @error
           [:div {:style {:background (settings/get-color :danger)
                          :color "white"
                          :padding "10px 15px"
                          :border-radius "4px"
                          :margin-bottom "15px"}}
            @error])

         ;; Create user button/form
         (if @show-create?
           [:div {:style {:background (settings/get-color :sidebar)
                          :padding "15px"
                          :border-radius "6px"
                          :margin-bottom "20px"}}
            [:div {:style {:font-weight "500"
                           :margin-bottom "10px"
                           :color (settings/get-color :text)}}
             "Nuovo utente"]
            [:div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
             [:input {:type "text"
                      :placeholder "Username (min 3 caratteri)"
                      :value @new-username
                      :on-change #(reset! new-username (-> % .-target .-value))
                      :style {:padding "8px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)}}]
             [:input {:type "password"
                      :placeholder "Password (min 6 caratteri)"
                      :value @new-password
                      :on-change #(reset! new-password (-> % .-target .-value))
                      :style {:padding "8px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)}}]
             [:label {:style {:display "flex"
                              :align-items "center"
                              :gap "8px"
                              :color (settings/get-color :text)}}
              [:input {:type "checkbox"
                       :checked @new-is-admin?
                       :on-change #(reset! new-is-admin? (-> % .-target .-checked))}]
              "Super Admin"]
             [:div {:style {:display "flex" :gap "10px" :margin-top "5px"}}
              [:button {:on-click (fn []
                                    (reset! creating? true)
                                    (reset! error nil)
                                    (-> (api/create-user! @new-username @new-password @new-is-admin?)
                                        (.then (fn [result]
                                                 (reset! creating? false)
                                                 (if (:ok result)
                                                   (do
                                                     (reset! show-create? false)
                                                     (reset! new-username "")
                                                     (reset! new-password "")
                                                     (reset! new-is-admin? false)
                                                     (load-users!))
                                                   (reset! error (:error result)))))))
                        :disabled (or @creating?
                                      (< (count @new-username) 3)
                                      (< (count @new-password) 6))
                        :style {:padding "8px 16px"
                                :background (settings/get-color :accent)
                                :color "white"
                                :border "none"
                                :border-radius "4px"
                                :cursor "pointer"
                                :opacity (if (or @creating?
                                                 (< (count @new-username) 3)
                                                 (< (count @new-password) 6)) 0.5 1)}}
               (if @creating? "..." "Crea")]
              [:button {:on-click #(do (reset! show-create? false)
                                       (reset! new-username "")
                                       (reset! new-password "")
                                       (reset! new-is-admin? false))
                        :style {:padding "8px 16px"
                                :background "transparent"
                                :color (settings/get-color :text-muted)
                                :border (str "1px solid " (settings/get-color :border))
                                :border-radius "4px"
                                :cursor "pointer"}}
               "Annulla"]]]]
           [:button {:on-click #(reset! show-create? true)
                     :style {:padding "10px 20px"
                             :background (settings/get-color :accent)
                             :color "white"
                             :border "none"
                             :border-radius "4px"
                             :cursor "pointer"
                             :margin-bottom "20px"}}
            "+ Nuovo utente"])

         ;; Loading
         (when @loading?
           [:div {:style {:text-align "center"
                          :padding "20px"
                          :color (settings/get-color :text-muted)}}
            "Caricamento..."])

         ;; Users list
         (when (and (not @loading?) @users)
           ;; Deref confirm-delete-id OUTSIDE the for loop to ensure reactivity
           (let [current-confirm-delete @confirm-delete-id]
             [:div
              (for [user @users]
                ^{:key (:id user)}
                [:div {:style {:display "flex"
                             :justify-content "space-between"
                             :align-items "center"
                             :padding "12px 15px"
                             :background (settings/get-color :sidebar)
                             :border-radius "6px"
                             :margin-bottom "8px"
                             :cursor "pointer"
                             :transition "background 0.15s"}
                     :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                     :on-mouse-out #(set! (.. % -currentTarget -style -background) (settings/get-color :sidebar))
                     :on-click (fn [e]
                                 ;; Only open edit if not clicking on a button
                                 (when-not (= "BUTTON" (.-tagName (.-target e)))
                                   (reset! editing-user user)))}
               [:div
                [:div {:style {:display "flex" :align-items "center" :gap "6px" :flex-wrap "wrap"}}
                 [:span {:style {:color (settings/get-color :text)
                                 :font-weight "500"}}
                  (auth/get-display-name user)]
                 ;; Status badge (nil treated as "active" for pre-migration users)
                 (let [status (or (:status user) "active")
                       [bg-color status-text] (case status
                                                "active" [(settings/get-color :success) "Attivo"]
                                                "pending" ["#f59e0b" "In attesa"]
                                                "suspended" [(settings/get-color :danger) "Sospeso"]
                                                [(settings/get-color :text-muted) status])]
                   [:span {:style {:padding "2px 8px"
                                   :background bg-color
                                   :color "white"
                                   :border-radius "10px"
                                   :font-size "0.7rem"}}
                    status-text])
                 (when (= 1 (:is_super_admin user))
                   [:span {:style {:padding "2px 8px"
                                   :background (settings/get-color :accent)
                                   :color "white"
                                   :border-radius "10px"
                                   :font-size "0.7rem"}}
                    "Admin"])]
                [:div {:style {:font-size "0.8rem"
                               :color (settings/get-color :text-muted)
                               :margin-top "2px"}}
                 (str "@" (:username user)
                      " • Progetti: " (or (:projects_owned user) 0)
                      " • Creato: " (when-let [d (:created_at user)] (subs d 0 10)))]]
               ;; Actions (only for non-self users)
               (when (not= (:id user) (:id (auth/get-user)))
                 [:div {:style {:display "flex" :gap "8px"}
                        :on-click #(.stopPropagation %)}  ;; Prevent row click
                  ;; Quick approve for pending users
                  (when (= "pending" (:status user))
                    [:button {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (-> (api/update-user-admin! (:id user) {:status "active"})
                                              (.then (fn [result]
                                                       (when (:ok result)
                                                         (load-users!))))))
                              :style {:padding "4px 10px"
                                      :background (settings/get-color :success)
                                      :color "white"
                                      :border "none"
                                      :border-radius "4px"
                                      :cursor "pointer"
                                      :font-size "0.8rem"}}
                     "Approva"])
                  ;; Toggle admin
                  [:button {:on-click (fn [e]
                                        (.stopPropagation e)
                                        (-> (api/update-user-admin! (:id user) {:is-super-admin (zero? (:is_super_admin user))})
                                            (.then (fn [result]
                                                     (when (:ok result)
                                                       (load-users!))))))
                            :style {:padding "4px 10px"
                                    :background "transparent"
                                    :color (settings/get-color :text-muted)
                                    :border (str "1px solid " (settings/get-color :border))
                                    :border-radius "4px"
                                    :cursor "pointer"
                                    :font-size "0.8rem"}}
                   (if (= 1 (:is_super_admin user)) "Rimuovi Admin" "Rendi Admin")]
                  ;; Delete
                  (if (= current-confirm-delete (:id user))
                    [:<>
                     (when (pos? (or (:projects_owned user) 0))
                       [:span {:style {:font-size "0.75rem"
                                       :color (settings/get-color :danger)
                                       :margin-right "5px"}}
                        (str (:projects_owned user) " prog!")])
                     [:button {:on-click (fn [e]
                                           (.stopPropagation e)
                                           (-> (api/delete-user! (:id user))
                                               (.then (fn [result]
                                                        (reset! confirm-delete-id nil)
                                                        (when (:ok result)
                                                          (load-users!))))))
                               :style {:padding "4px 10px"
                                       :background (settings/get-color :danger)
                                       :color "white"
                                       :border "none"
                                       :border-radius "4px"
                                       :cursor "pointer"
                                       :font-size "0.8rem"}}
                      "Conferma"]
                     [:button {:on-click (fn [e]
                                           (.stopPropagation e)
                                           (reset! confirm-delete-id nil))
                               :style {:padding "4px 10px"
                                       :background "transparent"
                                       :color (settings/get-color :text-muted)
                                       :border (str "1px solid " (settings/get-color :border))
                                       :border-radius "4px"
                                       :cursor "pointer"
                                       :font-size "0.8rem"}}
                      "No"]]
                    [:button {:on-click (fn [e]
                                          (.stopPropagation e)
                                          (reset! confirm-delete-id (:id user)))
                              :style {:padding "4px 10px"
                                      :background "transparent"
                                      :color (settings/get-color :danger)
                                      :border (str "1px solid " (settings/get-color :danger))
                                      :border-radius "4px"
                                      :cursor "pointer"
                                      :font-size "0.8rem"}}
                     "Elimina"])])])]))

         ;; Edit user modal
         (when-let [u @editing-user]
           ^{:key (:id u)}
           [user-edit-modal {:user u
                             :on-close #(reset! editing-user nil)
                             :on-save load-users!}])]])))

;; =============================================================================
;; User Menu (header dropdown)
;; =============================================================================

;; Global atom for pending users count (polled periodically for super-admins)
(defonce pending-users-count (r/atom 0))
(defonce pending-poll-interval (atom nil))

(defn- start-pending-poll!
  "Start polling for pending users count (super-admin only)"
  []
  (when (and (auth/super-admin?) (nil? @pending-poll-interval))
    ;; Initial fetch
    (-> (api/get-pending-count)
        (.then (fn [result]
                 (when (:ok result)
                   (reset! pending-users-count (get-in result [:data :count] 0))))))
    ;; Poll every 60 seconds
    (reset! pending-poll-interval
            (js/setInterval
             (fn []
               (when (auth/super-admin?)
                 (-> (api/get-pending-count)
                     (.then (fn [result]
                              (when (:ok result)
                                (reset! pending-users-count (get-in result [:data :count] 0))))))))
             60000))))

(defn- stop-pending-poll!
  "Stop polling for pending users"
  []
  (when @pending-poll-interval
    (js/clearInterval @pending-poll-interval)
    (reset! pending-poll-interval nil)))

;; =============================================================================
;; Change Password Modal
;; =============================================================================

(defn- change-password-modal
  "Modal for user to change their own password"
  [{:keys [on-close]}]
  (let [old-password (r/atom "")
        new-password (r/atom "")
        confirm-password (r/atom "")
        error (r/atom nil)
        success (r/atom false)
        saving? (r/atom false)]
    (fn [{:keys [on-close]}]
      [modal-wrapper {:on-close on-close
                      :z-index 3000
                      :width "400px"
                      :title "Cambia Password"}
        ;; Form
        [:div {:style {:padding "20px"
                       :display "flex"
                       :flex-direction "column"
                       :gap "15px"}}
         ;; Success message
         (when @success
           [:div {:style {:background (settings/get-color :success)
                          :color "white"
                          :padding "10px 15px"
                          :border-radius "4px"}}
            "Password cambiata con successo!"])

         ;; Error message
         (when @error
           [:div {:style {:background (settings/get-color :danger)
                          :color "white"
                          :padding "10px 15px"
                          :border-radius "4px"}}
            @error])

         (when-not @success
           [:<>
            ;; Old password
            [:div
             [:label {:style {:display "block"
                              :margin-bottom "5px"
                              :font-size "0.85rem"
                              :color (settings/get-color :text-muted)}}
              "Password attuale"]
             [:input {:type "password"
                      :value @old-password
                      :on-change #(reset! old-password (-> % .-target .-value))
                      :style {:width "100%"
                              :padding "10px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)
                              :box-sizing "border-box"}}]]

            ;; New password
            [:div
             [:label {:style {:display "block"
                              :margin-bottom "5px"
                              :font-size "0.85rem"
                              :color (settings/get-color :text-muted)}}
              "Nuova password"]
             [:input {:type "password"
                      :value @new-password
                      :on-change #(reset! new-password (-> % .-target .-value))
                      :placeholder "Minimo 6 caratteri"
                      :style {:width "100%"
                              :padding "10px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)
                              :box-sizing "border-box"}}]]

            ;; Confirm password
            [:div
             [:label {:style {:display "block"
                              :margin-bottom "5px"
                              :font-size "0.85rem"
                              :color (settings/get-color :text-muted)}}
              "Conferma nuova password"]
             [:input {:type "password"
                      :value @confirm-password
                      :on-change #(reset! confirm-password (-> % .-target .-value))
                      :style {:width "100%"
                              :padding "10px 12px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)
                              :box-sizing "border-box"}}]]

            ;; Buttons
            [:div {:style {:display "flex"
                           :gap "10px"
                           :margin-top "10px"}}
             [:button {:on-click (fn []
                                   (reset! error nil)
                                   (cond
                                     (< (count @new-password) 6)
                                     (reset! error "La nuova password deve avere almeno 6 caratteri")

                                     (not= @new-password @confirm-password)
                                     (reset! error "Le password non corrispondono")

                                     :else
                                     (do
                                       (reset! saving? true)
                                       (-> (api/change-password! @old-password @new-password)
                                           (.then (fn [result]
                                                    (reset! saving? false)
                                                    (if (:ok result)
                                                      (reset! success true)
                                                      (reset! error (:error result)))))))))
                       :disabled @saving?
                       :style {:flex 1
                               :padding "10px 20px"
                               :background (settings/get-color :accent)
                               :color "white"
                               :border "none"
                               :border-radius "4px"
                               :cursor "pointer"
                               :opacity (if @saving? 0.5 1)}}
              (if @saving? "..." "Cambia Password")]
             [:button {:on-click on-close
                       :style {:padding "10px 20px"
                               :background "transparent"
                               :color (settings/get-color :text-muted)
                               :border (str "1px solid " (settings/get-color :border))
                               :border-radius "4px"
                               :cursor "pointer"}}
              "Annulla"]]])

         ;; Close button after success
         (when @success
           [:button {:on-click on-close
                     :style {:padding "10px 20px"
                             :background (settings/get-color :accent)
                             :color "white"
                             :border "none"
                             :border-radius "4px"
                             :cursor "pointer"}}
            "Chiudi"])]])))

(defn user-menu
  "Dropdown menu showing current user and logout option"
  [{:keys [on-logout on-admin-users]}]
  (let [open? (r/atom false)
        show-password-modal? (r/atom false)]
    (r/create-class
     {:component-did-mount
      (fn [_] (start-pending-poll!))

      :component-will-unmount
      (fn [_] (stop-pending-poll!))

      :reagent-render
      (fn [{:keys [on-logout on-admin-users]}]
        (when (auth/logged-in?)
          [:div {:style {:position "relative"}}
           ;; Trigger
           [:button {:on-click #(swap! open? not)
                     :style {:background "transparent"
                             :border "none"
                             :color (settings/get-color :text)
                             :cursor "pointer"
                             :display "flex"
                             :align-items "center"
                             :gap "6px"
                             :padding "6px 10px"
                             :border-radius "4px"}
                     :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :sidebar))
                     :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")}
            [:span "👤"]
            [:span (auth/get-display-name)]
            ;; Badge for pending users (super-admin only)
            (when (and (auth/super-admin?) (pos? @pending-users-count))
              [:span {:style {:background (settings/get-color :danger)
                              :color "white"
                              :font-size "0.7rem"
                              :padding "2px 6px"
                              :border-radius "10px"
                              :margin-left "4px"}}
               @pending-users-count])
            [:span {:style {:font-size "0.7rem"}} (if @open? "▲" "▼")]]

           ;; Dropdown
           (when @open?
             [:div {:style {:position "absolute"
                            :top "100%"
                            :right 0
                            :background (settings/get-color :sidebar)
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "6px"
                            :box-shadow "0 4px 12px rgba(0,0,0,0.3)"
                            :min-width "180px"
                            :z-index 1000}}
              (when (auth/super-admin?)
                [:<>
                 [:div {:style {:padding "10px 15px"
                                :font-size "0.8rem"
                                :color (settings/get-color :accent)
                                :border-bottom (str "1px solid " (settings/get-color :border))}}
                  "⭐ Super Admin"]
                 [:div {:style {:padding "10px 15px"
                                :cursor "pointer"
                                :color (settings/get-color :text)
                                :display "flex"
                                :align-items "center"
                                :justify-content "space-between"}
                        :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                        :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")
                        :on-click (fn []
                                    (reset! open? false)
                                    (when on-admin-users (on-admin-users)))}
                  [:span "Gestione Utenti"]
                  (when (pos? @pending-users-count)
                    [:span {:style {:background "#f59e0b"
                                    :color "white"
                                    :font-size "0.7rem"
                                    :padding "2px 8px"
                                    :border-radius "10px"}}
                     (str @pending-users-count " in attesa")])]])
              ;; Change password option
              [:div {:style {:padding "10px 15px"
                             :cursor "pointer"
                             :color (settings/get-color :text)
                             :border-top (when (auth/super-admin?) (str "1px solid " (settings/get-color :border)))}
                     :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                     :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")
                     :on-click (fn []
                                 (reset! open? false)
                                 (reset! show-password-modal? true))}
               "Cambia password"]
              ;; Logout
              [:div {:style {:padding "10px 15px"
                             :cursor "pointer"
                             :color (settings/get-color :text)
                             :border-top (str "1px solid " (settings/get-color :border))}
                     :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                     :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")
                     :on-click (fn []
                                 (reset! open? false)
                                 (auth/logout!)
                                 (when on-logout (on-logout)))}
               "Esci"]])

           ;; Change password modal
           (when @show-password-modal?
             [change-password-modal {:on-close #(reset! show-password-modal? false)}])]))})))
