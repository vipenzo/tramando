(ns tramando.server-ui
  "UI components for server mode: login, projects list, collaborators"
  (:require [reagent.core :as r]
            [tramando.auth :as auth]
            [tramando.api :as api]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]
            [tramando.events :as events]))

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

       ;; Error message
       (when @error
         [:div {:style {:background "#ff5252"
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
                                             (if (:ok result)
                                               (when on-success (on-success))
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

(defn- project-card
  "Single project card with duplicate/delete actions"
  [{:keys [project on-open duplicating-id confirm-delete-id load-projects!]}]
  [:div {:style {:background (settings/get-color :sidebar)
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
   ;; Role and date
   [:div {:style {:display "flex"
                  :justify-content "space-between"
                  :font-size "0.8rem"
                  :color (settings/get-color :text-muted)}}
    [:span (case (:user_role project)
             "owner" "Proprietario"
             "admin" "Admin"
             "collaborator" "Collaboratore"
             (:user_role project))]
    [:span (when-let [date (:updated_at project)]
             (subs date 0 10))]]
   ;; Buttons for owners
   (when (= (:user_role project) "owner")
     [:div {:style {:display "flex"
                    :gap "8px"
                    :margin-top "12px"
                    :padding-top "12px"
                    :border-top (str "1px solid " (settings/get-color :border))}}
      ;; Duplicate button
      [:button {:on-click (fn [e]
                            (.stopPropagation e)
                            (reset! duplicating-id (:id project))
                            (-> (api/get-project (:id project))
                                (.then (fn [result]
                                         (when (:ok result)
                                           (-> (api/create-project! (str (:name project) " (copia)")
                                                                    (get-in result [:data :content]))
                                               (.then (fn [cr]
                                                        (reset! duplicating-id nil)
                                                        (when (:ok cr)
                                                          (events/show-toast! "Progetto duplicato")
                                                          (load-projects!))))))))))
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
      ;; Delete button (or confirm/cancel)
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
                           :background "#ff5252"
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
                          :color "#ff5252"
                          :border "1px solid #ff5252"
                          :border-radius "4px"
                          :cursor "pointer"
                          :font-size "0.8rem"}}
         "Elimina"])])])

(defn projects-list
  "List of server projects with create/open/delete actions"
  [{:keys [on-open on-create]}]
  (let [projects (r/atom nil)
        loading? (r/atom true)
        error (r/atom nil)
        new-project-name (r/atom "")
        creating? (r/atom false)
        show-create-form? (r/atom false)
        duplicating-id (r/atom nil)
        confirm-delete-id (r/atom nil)
        load-projects! (fn []
                         (reset! loading? true)
                         (-> (api/list-projects)
                             (.then (fn [result]
                                      (reset! loading? false)
                                      (if (:ok result)
                                        (reset! projects (get-in result [:data :projects]))
                                        (reset! error (:error result)))))))]
    ;; Load on mount
    (load-projects!)
    (fn [{:keys [on-open on-create]}]
      [:div {:style {:padding "20px"}}
       ;; Header
       [:div {:style {:display "flex"
                      :justify-content "space-between"
                      :align-items "center"
                      :margin-bottom "20px"}}
        [:h2 {:style {:margin 0
                      :font-weight "400"
                      :color (settings/get-color :text)}}
         "I tuoi progetti"]
        [:button {:on-click #(reset! show-create-form? true)
                  :style {:padding "8px 16px"
                          :background (settings/get-color :accent)
                          :color "white"
                          :border "none"
                          :border-radius "4px"
                          :cursor "pointer"}}
         "+ Nuovo progetto"]]

       ;; Create form
       (when @show-create-form?
         [:div {:style {:background (settings/get-color :sidebar)
                        :border (str "1px solid " (settings/get-color :border))
                        :border-radius "6px"
                        :padding "15px"
                        :margin-bottom "20px"}}
          [:div {:style {:display "flex" :gap "10px"}}
           [:input {:type "text"
                    :placeholder "Nome del progetto"
                    :value @new-project-name
                    :on-change #(reset! new-project-name (-> % .-target .-value))
                    :disabled @creating?
                    :style {:flex 1
                            :padding "8px 12px"
                            :border (str "1px solid " (settings/get-color :border))
                            :border-radius "4px"
                            :background (settings/get-color :editor-bg)
                            :color (settings/get-color :text)}}]
           [:button {:on-click (fn []
                                 (when (seq @new-project-name)
                                   (reset! creating? true)
                                   (-> (api/create-project! @new-project-name "")
                                       (.then (fn [result]
                                                (reset! creating? false)
                                                (when (:ok result)
                                                  (reset! new-project-name "")
                                                  (reset! show-create-form? false)
                                                  (load-projects!)
                                                  (when on-create
                                                    (on-create (get-in result [:data :project])))))))))
                     :disabled (or @creating? (empty? @new-project-name))
                     :style {:padding "8px 16px"
                             :background (settings/get-color :accent)
                             :color "white"
                             :border "none"
                             :border-radius "4px"
                             :cursor "pointer"
                             :opacity (if (or @creating? (empty? @new-project-name)) 0.5 1)}}
            (if @creating? "..." "Crea")]
           [:button {:on-click #(do (reset! show-create-form? false)
                                    (reset! new-project-name ""))
                     :style {:padding "8px 12px"
                             :background "transparent"
                             :color (settings/get-color :text-muted)
                             :border (str "1px solid " (settings/get-color :border))
                             :border-radius "4px"
                             :cursor "pointer"}}
            "Annulla"]]])

       ;; Error
       (when @error
         [:div {:style {:color "#ff5252" :margin-bottom "15px"}}
          @error])

       ;; Loading
       (when @loading?
         [:div {:style {:text-align "center"
                        :padding "40px"
                        :color (settings/get-color :text-muted)}}
          "Caricamento..."])

       ;; Projects grid
       (when (and (not @loading?) @projects)
         (if (empty? @projects)
           [:div {:style {:text-align "center"
                          :padding "40px"
                          :color (settings/get-color :text-muted)}}
            "Nessun progetto. Crea il tuo primo progetto!"]
           [:div {:style {:display "grid"
                          :grid-template-columns "repeat(auto-fill, minmax(280px, 1fr))"
                          :gap "15px"}}
            (for [project @projects]
              ^{:key (:id project)}
              [project-card {:project project
                             :on-open on-open
                             :duplicating-id duplicating-id
                             :confirm-delete-id confirm-delete-id
                             :load-projects! load-projects!}])]))])))


;; =============================================================================
;; Collaborators Panel
;; =============================================================================

(defn collaborators-panel
  "Panel to manage project collaborators"
  [{:keys [project-id]}]
  (let [data (r/atom nil)
        loading? (r/atom true)
        new-username (r/atom "")
        new-role (r/atom "collaborator")
        adding? (r/atom false)
        error (r/atom nil)
        load-data! (fn []
                     (reset! loading? true)
                     (-> (api/list-collaborators project-id)
                         (.then (fn [result]
                                  (reset! loading? false)
                                  (if (:ok result)
                                    (reset! data (:data result))
                                    (reset! error (:error result)))))))]
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
         [:div {:style {:color "#ff5252" :margin-bottom "10px"}} @error])

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
            (:username (:owner @data))]]

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
                 (:username collab)
                 [:span {:style {:margin-left "8px"
                                 :font-size "0.8rem"
                                 :color (settings/get-color :text-muted)}}
                  (str "(" (:role collab) ")")]]
                [:button {:on-click (fn []
                                      (-> (api/remove-collaborator! project-id (:id collab))
                                          (.then (fn [_] (load-data!)))))
                          :style {:background "transparent"
                                  :border "none"
                                  :color "#ff5252"
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
            [:input {:type "text"
                     :placeholder "Username"
                     :value @new-username
                     :on-change #(reset! new-username (-> % .-target .-value))
                     :style {:flex "1"
                             :min-width "120px"
                             :padding "6px 10px"
                             :border (str "1px solid " (settings/get-color :border))
                             :border-radius "4px"
                             :background (settings/get-color :editor-bg)
                             :color (settings/get-color :text)}}]
            [:select {:value @new-role
                      :on-change #(reset! new-role (-> % .-target .-value))
                      :style {:padding "6px 10px"
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)}}
             [:option {:value "collaborator"} "Collaboratore"]
             [:option {:value "admin"} "Admin"]]
            [:button {:on-click (fn []
                                  (when (seq @new-username)
                                    (reset! adding? true)
                                    (-> (api/add-collaborator! project-id @new-username @new-role)
                                        (.then (fn [result]
                                                 (reset! adding? false)
                                                 (if (:ok result)
                                                   (do (reset! new-username "")
                                                       (load-data!))
                                                   (reset! error (:error result))))))))
                      :disabled (or @adding? (empty? @new-username))
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
                 :background (settings/get-color :bg)}}
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
  [:div {:style {:position "fixed"
                 :top 0 :left 0 :right 0 :bottom 0
                 :background "rgba(0,0,0,0.5)"
                 :display "flex"
                 :align-items "center"
                 :justify-content "center"
                 :z-index 2000}
         :on-click (fn [e]
                     (when (= (.-target e) (.-currentTarget e))
                       (on-close)))}
   [:div {:style {:background (settings/get-color :bg)
                  :border-radius "8px"
                  :width "450px"
                  :max-height "80vh"
                  :overflow "auto"
                  :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}}
    ;; Header
    [:div {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :padding "15px 20px"
                   :border-bottom (str "1px solid " (settings/get-color :border))}}
     [:h2 {:style {:margin 0
                   :font-weight "400"
                   :font-size "1.2rem"
                   :color (settings/get-color :text)}}
      "Collaboratori"]
     [:button {:on-click on-close
               :style {:background "transparent"
                       :border "none"
                       :font-size "1.5rem"
                       :cursor "pointer"
                       :color (settings/get-color :text-muted)}}
      "×"]]
    ;; Content
    [collaborators-panel {:project-id project-id}]]])

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
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.5)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 2000}
             :on-click (fn [e]
                         (when (= (.-target e) (.-currentTarget e))
                           (on-close)))}
       [:div {:style {:background (settings/get-color :bg)
                      :border-radius "8px"
                      :width "600px"
                      :max-height "80vh"
                      :overflow "auto"
                      :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}}
        ;; Header
        [:div {:style {:display "flex"
                       :justify-content "space-between"
                       :align-items "center"
                       :padding "20px"
                       :border-bottom (str "1px solid " (settings/get-color :border))}}
         [:h2 {:style {:margin 0
                       :font-weight "400"
                       :color (settings/get-color :text)}}
          "Gestione Utenti"]
         [:button {:on-click on-close
                   :style {:background "transparent"
                           :border "none"
                           :font-size "1.5rem"
                           :cursor "pointer"
                           :color (settings/get-color :text-muted)}}
          "×"]]

        [:div {:style {:padding "20px"}}
         ;; Error
         (when @error
           [:div {:style {:background "#ff5252"
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
           [:div
            (for [user @users]
              ^{:key (:id user)}
              [:div {:style {:display "flex"
                             :justify-content "space-between"
                             :align-items "center"
                             :padding "12px 15px"
                             :background (settings/get-color :sidebar)
                             :border-radius "6px"
                             :margin-bottom "8px"}}
               [:div
                [:span {:style {:color (settings/get-color :text)
                                :font-weight "500"}}
                 (:username user)]
                (when (= 1 (:is_super_admin user))
                  [:span {:style {:margin-left "10px"
                                  :padding "2px 8px"
                                  :background (settings/get-color :accent)
                                  :color "white"
                                  :border-radius "10px"
                                  :font-size "0.75rem"}}
                   "Super Admin"])
                [:div {:style {:font-size "0.8rem"
                               :color (settings/get-color :text-muted)
                               :margin-top "2px"}}
                 (str "Creato: " (when-let [d (:created_at user)] (subs d 0 10)))]]
               ;; Actions (only for non-self users)
               (when (not= (:id user) (:id (auth/get-user)))
                 [:div {:style {:display "flex" :gap "8px"}}
                  ;; Toggle admin
                  [:button {:on-click (fn []
                                        (-> (api/update-user-admin! (:id user) (zero? (:is_super_admin user)))
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
                  (if (= @confirm-delete-id (:id user))
                    [:<>
                     [:button {:on-click (fn []
                                           (-> (api/delete-user! (:id user))
                                               (.then (fn [result]
                                                        (reset! confirm-delete-id nil)
                                                        (when (:ok result)
                                                          (load-users!))))))
                               :style {:padding "4px 10px"
                                       :background "#ff5252"
                                       :color "white"
                                       :border "none"
                                       :border-radius "4px"
                                       :cursor "pointer"
                                       :font-size "0.8rem"}}
                      "Conferma"]
                     [:button {:on-click #(reset! confirm-delete-id nil)
                               :style {:padding "4px 10px"
                                       :background "transparent"
                                       :color (settings/get-color :text-muted)
                                       :border (str "1px solid " (settings/get-color :border))
                                       :border-radius "4px"
                                       :cursor "pointer"
                                       :font-size "0.8rem"}}
                      "No"]]
                    [:button {:on-click #(reset! confirm-delete-id (:id user))
                              :style {:padding "4px 10px"
                                      :background "transparent"
                                      :color "#ff5252"
                                      :border "1px solid #ff5252"
                                      :border-radius "4px"
                                      :cursor "pointer"
                                      :font-size "0.8rem"}}
                     "Elimina"])])])])]]])))

;; =============================================================================
;; User Menu (header dropdown)
;; =============================================================================

(defn user-menu
  "Dropdown menu showing current user and logout option"
  [{:keys [on-logout on-admin-users]}]
  (let [open? (r/atom false)]
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
          [:span (auth/get-username)]
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
                              :color (settings/get-color :text)}
                      :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                      :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")
                      :on-click (fn []
                                  (reset! open? false)
                                  (when on-admin-users (on-admin-users)))}
                "Gestione Utenti"]])
            [:div {:style {:padding "10px 15px"
                           :cursor "pointer"
                           :color (settings/get-color :text)
                           :border-top (when (auth/super-admin?) (str "1px solid " (settings/get-color :border)))}
                   :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                   :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")
                   :on-click (fn []
                               (reset! open? false)
                               (auth/logout!)
                               (when on-logout (on-logout)))}
             "Esci"]])]))))
