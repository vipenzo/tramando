(ns tramando.chat
  "Project chat component for collaborative mode.
   Shows a mini chat panel that can be expanded for better visibility."
  (:require [reagent.core :as r]
            [tramando.api :as api]
            [tramando.auth :as auth]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]
            [clojure.string :as str]))

;; =============================================================================
;; State
;; =============================================================================

(defonce chat-project-id (r/atom nil))  ;; Set by remote-store on project load
(defonce chat-messages (r/atom []))
(defonce chat-expanded? (r/atom false))
(defonce chat-collapsed? (r/atom true))
(defonce chat-loading? (r/atom false))
(defonce last-message-id (r/atom nil))
(defonce poll-timer (r/atom nil))
(defonce unread-count (r/atom 0))

(def ^:private poll-interval-ms 5000) ;; 5 seconds

;; =============================================================================
;; API Functions
;; =============================================================================

(defn- load-messages!
  "Load chat messages from server"
  []
  (when-let [project-id @chat-project-id]
    (reset! chat-loading? true)
    (let [is-initial-load? (nil? @last-message-id)]
      (-> (api/get-chat-messages project-id @last-message-id)
          (.then (fn [result]
                   (reset! chat-loading? false)
                   (when (:ok result)
                     (let [new-msgs (:messages (:data result))]
                       (if (seq new-msgs)
                         (do
                           ;; Append new messages
                           (swap! chat-messages into new-msgs)
                           ;; Update last message ID for polling
                           (reset! last-message-id (:id (last new-msgs)))
                           ;; Update unread count if panel is collapsed (only for non-initial loads)
                           (when (and @chat-collapsed? (not is-initial-load?))
                             (swap! unread-count + (count new-msgs))))
                         ;; No messages - set a sentinel value so we don't re-fetch all
                         (when is-initial-load?
                           (reset! last-message-id 0)))))))
          (.catch (fn [_err]
                    (reset! chat-loading? false)))))))

(defn- send-message!
  "Send a chat message"
  [message on-success]
  (when-let [project-id @chat-project-id]
    (-> (api/send-chat-message! project-id message)
        (.then (fn [result]
                 (when (:ok result)
                   ;; Add the message to our list
                   (let [msg (:message (:data result))]
                     (swap! chat-messages conj msg)
                     (reset! last-message-id (:id msg)))
                   (when on-success (on-success)))))
        (.catch (fn [err]
                  (js/console.error "Failed to send message:" err))))))

;; =============================================================================
;; Polling
;; =============================================================================

(defn- start-polling!
  "Start polling for new messages"
  []
  (when (nil? @poll-timer)
    (reset! poll-timer
            (js/setInterval load-messages! poll-interval-ms))))

(defn- stop-polling!
  "Stop polling for new messages"
  []
  (when @poll-timer
    (js/clearInterval @poll-timer)
    (reset! poll-timer nil)))

(defn init-chat!
  "Initialize chat for the current project"
  [project-id]
  (reset! chat-project-id project-id)
  (reset! chat-messages [])
  (reset! last-message-id nil)
  (reset! unread-count 0)
  (load-messages!)
  (start-polling!))

(defn cleanup-chat!
  "Cleanup chat state when leaving project"
  []
  (stop-polling!)
  (reset! chat-project-id nil)
  (reset! chat-messages [])
  (reset! last-message-id nil)
  (reset! unread-count 0)
  (reset! chat-collapsed? true)
  (reset! chat-expanded? false))

;; =============================================================================
;; UI Components
;; =============================================================================

(defn- format-time
  "Format timestamp for display"
  [timestamp]
  (when timestamp
    (let [date (js/Date. timestamp)]
      (str (.getHours date) ":"
           (.padStart (str (.getMinutes date)) 2 "0")))))

(defn- message-item
  "Single chat message"
  [{:keys [username message created_at]}]
  (let [current-user (auth/get-username)
        is-own (= username current-user)]
    [:div {:style {:display "flex"
                   :flex-direction "column"
                   :align-items (if is-own "flex-end" "flex-start")
                   :margin-bottom "8px"}}
     ;; Username and time
     [:div {:style {:font-size "10px"
                    :color (settings/get-color :text-muted)
                    :margin-bottom "2px"}}
      (when-not is-own
        [:span {:style {:font-weight "bold"
                        :margin-right "4px"}}
         username])
      (format-time created_at)]
     ;; Message bubble
     [:div {:style {:background (if is-own
                                  (settings/get-color :accent)
                                  (settings/get-color :editor-bg))
                    :color (if is-own "white" (settings/get-color :text))
                    :padding "6px 10px"
                    :border-radius "12px"
                    :max-width "85%"
                    :word-wrap "break-word"
                    :font-size "13px"}}
      message]]))

(defn- chat-input
  "Input field for sending messages"
  []
  (let [text (r/atom "")]
    (fn []
      [:div {:style {:display "flex"
                     :gap "6px"
                     :padding "8px"
                     :border-top (str "1px solid " (settings/get-color :border))}}
       [:input {:type "text"
                :value @text
                :placeholder (t :chat-placeholder)
                :style {:flex 1
                        :background (settings/get-color :editor-bg)
                        :border (str "1px solid " (settings/get-color :border))
                        :border-radius "16px"
                        :color (settings/get-color :text)
                        :padding "6px 12px"
                        :font-size "13px"
                        :outline "none"}
                :on-change #(reset! text (.. % -target -value))
                :on-key-down (fn [e]
                               (when (and (= (.-key e) "Enter")
                                          (not (empty? (str/trim @text))))
                                 (send-message! @text #(reset! text ""))))}]
       [:button {:style {:background (settings/get-color :accent)
                         :color "white"
                         :border "none"
                         :border-radius "50%"
                         :width "28px"
                         :height "28px"
                         :cursor "pointer"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :font-size "14px"}
                 :disabled (empty? (str/trim @text))
                 :on-click (fn []
                             (when-not (empty? (str/trim @text))
                               (send-message! @text #(reset! text ""))))}
        ">"]])))

(defn- messages-list
  "Scrollable list of messages"
  []
  (let [container-ref (r/atom nil)]
    (r/create-class
     {:component-did-update
      (fn [_this]
        ;; Auto-scroll to bottom on new messages
        (when-let [el @container-ref]
          (set! (.-scrollTop el) (.-scrollHeight el))))

      :reagent-render
      (fn []
        [:div {:ref #(reset! container-ref %)
               :style {:flex 1
                       :overflow-y "auto"
                       :padding "8px"
                       :display "flex"
                       :flex-direction "column"}}
         (if (empty? @chat-messages)
           [:div {:style {:text-align "center"
                          :color (settings/get-color :text-muted)
                          :padding "20px"
                          :font-size "12px"}}
            (t :chat-empty)]
           (for [msg @chat-messages]
             ^{:key (:id msg)}
             [message-item msg]))])})))

;; =============================================================================
;; Panel Components
;; =============================================================================

(defn chat-panel-mini
  "Collapsed chat panel showing just header with unread count"
  []
  (let [unread @unread-count]
    [:div {:style {:background (settings/get-color :sidebar)
                   :border-top (str "1px solid " (settings/get-color :border))
                   :cursor "pointer"}
           :on-click (fn []
                       (reset! chat-collapsed? false)
                       (reset! unread-count 0))}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :padding "8px 12px"}}
      [:span {:style {:font-size "12px"
                      :color (settings/get-color :text)
                      :display "flex"
                      :align-items "center"
                      :gap "6px"}}
       "💬"
       (t :project-chat)]
      (when (pos? unread)
        [:span {:style {:background (settings/get-color :accent)
                        :color "white"
                        :font-size "10px"
                        :padding "2px 6px"
                        :border-radius "10px"
                        :font-weight "bold"}}
         unread])]]))

(defn chat-panel-expanded
  "Expanded chat panel inside right sidebar"
  []
  [:div {:style {:display "flex"
                 :flex-direction "column"
                 :height "250px"
                 :background (settings/get-color :sidebar)
                 :border-top (str "1px solid " (settings/get-color :border))}}
   ;; Header
   [:div {:style {:display "flex"
                  :align-items "center"
                  :justify-content "space-between"
                  :padding "8px 12px"
                  :border-bottom (str "1px solid " (settings/get-color :border))}}
    [:span {:style {:font-size "12px"
                    :font-weight "bold"
                    :color (settings/get-color :text)
                    :display "flex"
                    :align-items "center"
                    :gap "6px"}}
     "💬"
     (t :project-chat)]
    [:div {:style {:display "flex"
                   :gap "4px"}}
     ;; Expand to modal button
     [:button {:style {:background "transparent"
                       :border "none"
                       :color (settings/get-color :text-muted)
                       :cursor "pointer"
                       :font-size "12px"
                       :padding "2px 6px"}
               :title (t :expand)
               :on-click #(reset! chat-expanded? true)}
      "⤢"]
     ;; Collapse button
     [:button {:style {:background "transparent"
                       :border "none"
                       :color (settings/get-color :text-muted)
                       :cursor "pointer"
                       :font-size "12px"
                       :padding "2px 6px"}
               :title (t :collapse)
               :on-click #(reset! chat-collapsed? true)}
      "▼"]]]
   ;; Messages
   [messages-list]
   ;; Input
   [chat-input]])

(defn chat-modal
  "Full-screen chat modal"
  []
  (when @chat-expanded?
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0,0,0,0.5)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}
           :on-click #(reset! chat-expanded? false)}
     [:div {:style {:background (settings/get-color :sidebar)
                    :width "500px"
                    :max-width "90vw"
                    :height "70vh"
                    :border-radius "8px"
                    :display "flex"
                    :flex-direction "column"
                    :box-shadow "0 4px 20px rgba(0,0,0,0.3)"}
            :on-click #(.stopPropagation %)}
      ;; Header
      [:div {:style {:display "flex"
                     :align-items "center"
                     :justify-content "space-between"
                     :padding "12px 16px"
                     :border-bottom (str "1px solid " (settings/get-color :border))}}
       [:span {:style {:font-size "14px"
                       :font-weight "bold"
                       :color (settings/get-color :text)
                       :display "flex"
                       :align-items "center"
                       :gap "8px"}}
        "💬"
        (t :project-chat)]
       [:button {:style {:background "transparent"
                         :border "none"
                         :color (settings/get-color :text-muted)
                         :cursor "pointer"
                         :font-size "18px"}
                 :on-click #(reset! chat-expanded? false)}
        "×"]]
      ;; Messages
      [messages-list]
      ;; Input
      [chat-input]]]))

(defn chat-panel
  "Main chat component - shows mini or expanded based on state.
   Only visible in server/collaborative mode."
  []
  (when @chat-project-id
    [:<>
     (if @chat-collapsed?
       [chat-panel-mini]
       [chat-panel-expanded])
     [chat-modal]]))
