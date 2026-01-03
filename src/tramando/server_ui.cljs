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
                                (-> (if (= @mode :login)
                                      (auth/login! @username @password)
                                      (auth/register! @username @password))
                                    (.then (fn [result]
                                             (reset! loading? false)
                                             (if (:ok result)
                                               (when on-success (on-success))
                                               (reset! error (:error result)))))))))}
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

(defn projects-list
  "List of server projects with create/open/delete actions"
  [{:keys [on-open on-create]}]
  (let [projects (r/atom nil)
        loading? (r/atom true)
        error (r/atom nil)
        new-project-name (r/atom "")
        creating? (r/atom false)
        show-create-form? (r/atom false)
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
              [:div {:style {:background (settings/get-color :sidebar)
                             :border (str "1px solid " (settings/get-color :border))
                             :border-radius "6px"
                             :padding "15px"
                             :cursor "pointer"
                             :transition "border-color 0.2s"}
                     :on-mouse-over #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :accent))
                     :on-mouse-out #(set! (.. % -currentTarget -style -borderColor) (settings/get-color :border))
                     :on-click #(when on-open (on-open project))}
               [:div {:style {:font-weight "500"
                              :color (settings/get-color :text)
                              :margin-bottom "8px"}}
                (:name project)]
               [:div {:style {:display "flex"
                              :justify-content "space-between"
                              :font-size "0.8rem"
                              :color (settings/get-color :text-muted)}}
                [:span (case (:user_role project)
                         "owner" "👤 Proprietario"
                         "admin" "🔧 Admin"
                         "collaborator" "✏️ Collaboratore"
                         "")]
                [:span (when-let [date (:updated_at project)]
                         (subs date 0 10))]]])]))])))

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
;; User Menu (header dropdown)
;; =============================================================================

(defn user-menu
  "Dropdown menu showing current user and logout option"
  []
  (let [open? (r/atom false)]
    (fn []
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
                          :min-width "150px"
                          :z-index 1000}}
            (when (auth/super-admin?)
              [:div {:style {:padding "10px 15px"
                             :font-size "0.8rem"
                             :color (settings/get-color :accent)
                             :border-bottom (str "1px solid " (settings/get-color :border))}}
               "⭐ Super Admin"])
            [:div {:style {:padding "10px 15px"
                           :cursor "pointer"
                           :color (settings/get-color :text)}
                   :on-mouse-over #(set! (.. % -currentTarget -style -background) (settings/get-color :editor-bg))
                   :on-mouse-out #(set! (.. % -currentTarget -style -background) "transparent")
                   :on-click (fn []
                               (reset! open? false)
                               (auth/logout!))}
             "Esci"]])]))))
