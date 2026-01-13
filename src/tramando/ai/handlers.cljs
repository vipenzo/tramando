(ns tramando.ai.handlers
  "AI response handlers for different output types.

   For annotation templates:
   - Uses existing NOTE annotation type with priority 'AI' or 'AI-DONE'
   - Alternatives stored as EDN in the comment field: {:alts [...] :sel 0}
   - Everything integrates with existing annotation system"
  (:require [reagent.core :as r]
            [cljs.reader :as reader]
            [tramando.model :as model]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]
            [tramando.ai.templates :as templates]
            [tramando.events :as events]
            [tramando.auth :as auth]
            [clojure.string :as str]))

;; =============================================================================
;; AI Annotation EDN Format
;; =============================================================================
;; New format: [!NOTE{:text "selected" :ai :pending :author "user"}]
;;             [!NOTE{:text "selected" :ai :done :alts ["a" "b"] :sel 0 :author "user"}]

(defn make-ai-annotation
  "Create an AI annotation string in EDN format."
  [text ai-state & {:keys [author alts sel]}]
  (str "[!NOTE"
       (pr-str (cond-> {:text text :ai ai-state}
                 author (assoc :author author)
                 alts (assoc :alts (vec alts))
                 sel (assoc :sel sel)))
       "]"))

;; Legacy parsing for backwards compatibility
(defn parse-ai-data
  "Parse Base64-encoded EDN string (legacy format).
   Returns {:alts [...] :sel n} or nil if invalid."
  [encoded-string]
  (when (and encoded-string (not (str/blank? encoded-string)))
    (try
      (let [edn-str (-> encoded-string
                        js/atob
                        js/decodeURIComponent)
            data (reader/read-string edn-str)]
        (when (and (map? data) (:alts data))
          data))
      (catch :default _e
        nil))))

;; Legacy serialization (kept for transition period)
(defn serialize-ai-data
  "Serialize alternatives and selection to Base64-encoded EDN string (legacy)."
  [alternatives selected]
  (-> {:alts (vec alternatives) :sel selected}
      pr-str
      js/encodeURIComponent
      js/btoa))

;; =============================================================================
;; Regex Helpers
;; =============================================================================

(defn- escape-regex
  "Escape special regex characters in a string"
  [s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\$&"))

;; =============================================================================
;; AI Annotation Recognition
;; =============================================================================

(defn ai-annotation?
  "Check if an annotation is an AI annotation (pending or done)"
  [annotation]
  (let [p (:priority annotation)]
    (or (= p "AI") (= p :AI) (= p "AI-DONE") (= p :AI-DONE))))

(defn ai-annotation-pending?
  "Check if an annotation is pending AI response"
  [annotation]
  (let [p (:priority annotation)]
    (or (= p "AI") (= p :AI))))

(defn ai-annotation-done?
  "Check if an annotation has received AI alternatives"
  [annotation]
  (let [p (:priority annotation)]
    (or (= p "AI-DONE") (= p :AI-DONE))))

;; =============================================================================
;; Pending Annotation Tracking
;; =============================================================================
;; Track pending AI annotation requests (waiting for response)
;; Maps chunk-id+original-text to tracking info

(defonce pending-annotations
  (r/atom {}))  ;; key -> {:chunk-id :original-text}

(defn- make-pending-key [chunk-id original-text]
  (str chunk-id "::" (hash original-text)))

;; =============================================================================
;; Phase 1: Create Pending Annotation
;; =============================================================================

(defn- contains-annotation?
  "Check if text contains any annotation markers"
  [text]
  (boolean (or (re-find #"\[!(TODO|NOTE|FIX|PROPOSAL)\{" text)
               (re-find #"\[!(TODO|NOTE|FIX|PROPOSAL)(?:@[^:]+)?:" text))))

(defn insert-ai-annotation!
  "Insert AI pending annotation in chunk content (EDN format).
   Called when user selects text and chooses an annotation template.
   Returns the pending key for tracking, or nil if text contains nested annotations.
   Uses editor dispatch for proper content sync and undo support."
  [chunk-id selected-text]
  (cond
    ;; Prevent nested annotations
    (contains-annotation? selected-text)
    (do
      (events/show-toast! (t :error-nested-annotation))
      nil)

    ;; Normal case
    (and chunk-id (not (str/blank? selected-text)))
    (let [;; Get username if logged in (server mode)
          username (auth/get-username)
          author (when (and username (not= username "local")) username)
          ;; Create EDN annotation: [!NOTE{:text "..." :ai :pending :author "..."}]
          annotation-markup (make-ai-annotation selected-text :pending :author author)
          pending-key (make-pending-key chunk-id selected-text)]
      ;; Try editor dispatch first (handles content sync and undo)
      (if (events/replace-text-in-editor! selected-text annotation-markup)
        (do
          ;; Track pending annotation
          (swap! pending-annotations assoc pending-key
                 {:chunk-id chunk-id
                  :original-text selected-text})
          pending-key)
        ;; Fallback: direct model update if editor not available
        (when-let [chunk (model/get-chunk chunk-id)]
          (let [current-content (:content chunk)
                new-content (str/replace-first current-content selected-text annotation-markup)]
            ;; Only proceed if replacement actually happened
            (when (not= current-content new-content)
              (model/update-chunk! chunk-id {:content new-content})
              (swap! pending-annotations assoc pending-key
                     {:chunk-id chunk-id
                      :original-text selected-text})
              (events/refresh-editor!)
              pending-key)))))))

;; =============================================================================
;; Phase 2: Handle AI Response - Store Alternatives in EDN
;; =============================================================================

(defn- find-edn-pending-annotation
  "Find EDN format pending annotation: [!NOTE{:text \"...\" :ai :pending ...}]
   Returns the full match string or nil."
  [content original-text]
  ;; EDN format: [!NOTE{:text "..." :ai :pending :author "..."}]
  ;; We need to find an annotation where :text matches and :ai is :pending
  (let [;; Pattern to find any EDN annotation
        edn-pattern #"\[!NOTE\{[^\}]+\}\]"]
    (->> (re-seq edn-pattern content)
         (filter (fn [ann]
                   (try
                     (let [edn-str (subs ann 6 (dec (count ann)))  ;; Remove [!NOTE and ]
                           data (reader/read-string edn-str)]
                       (and (= (:text data) original-text)
                            (= (:ai data) :pending)))
                     (catch :default _ false))))
         first)))

(defn- parse-edn-annotation
  "Parse EDN annotation string to extract data map.
   Input: [!NOTE{:text \"...\" :ai :pending ...}]
   Returns the data map or nil."
  [annotation-str]
  (try
    (let [edn-str (subs annotation-str 6 (dec (count annotation-str)))]
      (reader/read-string edn-str))
    (catch :default _ nil)))

(defn- find-edn-done-annotation
  "Find EDN format done annotation: [!NOTE{:text \"...\" :ai :done ...}]
   Returns the full match string or nil."
  [content original-text]
  (let [edn-pattern #"\[!NOTE\{[^\}]+\}\]"]
    (->> (re-seq edn-pattern content)
         (filter (fn [ann]
                   (try
                     (let [edn-str (subs ann 6 (dec (count ann)))
                           data (reader/read-string edn-str)]
                       (and (= (:text data) original-text)
                            (= (:ai data) :done)))
                     (catch :default _ false))))
         first)))

(defn- find-edn-any-annotation
  "Find any EDN format AI annotation (pending or done).
   Returns the full match string or nil."
  [content original-text]
  (let [edn-pattern #"\[!NOTE\{[^\}]+\}\]"]
    (->> (re-seq edn-pattern content)
         (filter (fn [ann]
                   (try
                     (let [edn-str (subs ann 6 (dec (count ann)))
                           data (reader/read-string edn-str)]
                       (and (= (:text data) original-text)
                            (#{:pending :done} (:ai data))))
                     (catch :default _ false))))
         first)))

(defn complete-ai-annotation!
  "Complete an AI annotation by storing alternatives in EDN format.
   Called when AI response arrives for an :annotation template.

   Supports both:
   - New EDN format: [!NOTE{:text \"...\" :ai :pending}] -> [!NOTE{:text \"...\" :ai :done :alts [...] :sel 0}]
   - Legacy format: [!NOTE:text:AI:] -> [!NOTE:text:AI-DONE:base64]"
  [pending-key alternatives]
  (when-let [pending (get @pending-annotations pending-key)]
    (let [{:keys [chunk-id original-text]} pending
          chunk (model/get-chunk chunk-id)]
      (when (and chunk-id (seq alternatives) chunk)
        (let [current-content (:content chunk)
              edn-match (find-edn-pending-annotation current-content original-text)]
          (if edn-match
            ;; New EDN format
            (let [old-data (parse-edn-annotation edn-match)
                  new-data (-> old-data
                               (assoc :ai :done)
                               (assoc :alts (vec alternatives))
                               (assoc :sel 0))
                  new-annotation (str "[!NOTE" (pr-str new-data) "]")
                  new-content (str/replace current-content edn-match new-annotation)]
              (model/update-chunk! chunk-id {:content new-content})
              (swap! pending-annotations dissoc pending-key)
              (events/refresh-editor!)
              (events/show-toast! (str (t :ai-alternatives-received) " (" (count alternatives) ")")))
            ;; Fallback: try legacy format
            (let [pending-pattern (re-pattern
                                    (str "\\[!NOTE(@[^:]+)?:\\s*"
                                         (escape-regex original-text)
                                         "\\s*:AI:[^\\]]*\\]"))
                  match (re-find pending-pattern current-content)]
              (when match
                (let [author-part (or (second match) "")
                      edn-data (serialize-ai-data alternatives 0)
                      done-annotation (str "[!NOTE" author-part ":" original-text ":AI-DONE:" edn-data "]")
                      new-content (str/replace current-content pending-pattern done-annotation)]
                  (model/update-chunk! chunk-id {:content new-content})
                  (swap! pending-annotations dissoc pending-key)
                  (events/refresh-editor!)
                  (events/show-toast! (str (t :ai-alternatives-received) " (" (count alternatives) ")")))))))))))

;; =============================================================================
;; Update Selection in Annotation
;; =============================================================================

(defn update-ai-selection!
  "Update the :sel value in an AI-DONE annotation.
   Supports both EDN and legacy formats."
  [chunk-id original-text new-sel]
  (when-let [chunk (model/get-chunk chunk-id)]
    (let [current-content (:content chunk)
          edn-match (find-edn-done-annotation current-content original-text)]
      (if edn-match
        ;; EDN format
        (let [old-data (parse-edn-annotation edn-match)
              new-data (assoc old-data :sel new-sel)
              new-annotation (str "[!NOTE" (pr-str new-data) "]")]
          (if (events/replace-text-in-editor! edn-match new-annotation)
            (js/setTimeout #(events/refresh-editor!) 50)
            (do
              (model/update-chunk! chunk-id {:content (str/replace current-content edn-match new-annotation)})
              (events/refresh-editor!))))
        ;; Fallback: legacy format
        (let [pattern (re-pattern
                        (str "\\[!NOTE(@[^:]+)?:\\s*"
                             (escape-regex original-text)
                             "\\s*:AI-DONE:([A-Za-z0-9+/=]+)\\]"))
              match (re-find pattern current-content)]
          (when match
            (let [old-annotation (first match)
                  author-part (or (second match) "")
                  old-b64 (nth match 2)
                  old-data (parse-ai-data old-b64)]
              (when old-data
                (let [new-data (assoc old-data :sel new-sel)
                      new-b64 (serialize-ai-data (:alts new-data) (:sel new-data))
                      new-annotation (str "[!NOTE" author-part ":" original-text ":AI-DONE:" new-b64 "]")]
                  (if (events/replace-text-in-editor! old-annotation new-annotation)
                    (js/setTimeout #(events/refresh-editor!) 50)
                    (do
                      (model/update-chunk! chunk-id {:content (str/replace current-content pattern new-annotation)})
                      (events/refresh-editor!))))))))))))

;; =============================================================================
;; Confirm Alternative (Apply Selection)
;; =============================================================================

(defn confirm-ai-alternative!
  "Apply the selected alternative: replace original text with selected alternative.
   Supports both EDN and legacy formats."
  [chunk-id original-text]
  (when-let [chunk (model/get-chunk chunk-id)]
    (let [current-content (:content chunk)
          edn-match (find-edn-done-annotation current-content original-text)]
      (if edn-match
        ;; EDN format
        (let [data (parse-edn-annotation edn-match)]
          (when (and data (pos? (:sel data)))
            (let [selected-alt (nth (:alts data) (dec (:sel data)))]
              (if (events/replace-text-in-editor! edn-match selected-alt)
                (events/show-toast! (t :ai-alternative-applied))
                (do
                  (model/update-chunk! chunk-id {:content (str/replace current-content edn-match selected-alt)})
                  (events/refresh-editor!)
                  (events/show-toast! (t :ai-alternative-applied)))))))
        ;; Fallback: legacy format
        (let [pattern (re-pattern
                        (str "\\[!NOTE(@[^:]+)?:\\s*"
                             (escape-regex original-text)
                             "\\s*:AI-DONE:([A-Za-z0-9+/=]+)\\]"))
              match (re-find pattern current-content)]
          (when match
            (let [full-annotation (first match)
                  b64-data (nth match 2)
                  data (parse-ai-data b64-data)]
              (when (and data (pos? (:sel data)))
                (let [selected-alt (nth (:alts data) (dec (:sel data)))]
                  (if (events/replace-text-in-editor! full-annotation selected-alt)
                    (events/show-toast! (t :ai-alternative-applied))
                    (do
                      (model/update-chunk! chunk-id {:content (str/replace current-content full-annotation selected-alt)})
                      (events/refresh-editor!)
                      (events/show-toast! (t :ai-alternative-applied)))))))))))))

;; =============================================================================
;; Cancel AI Annotation
;; =============================================================================

(defn cancel-ai-annotation!
  "Remove an AI annotation, keeping the original text intact.
   Supports both EDN and legacy formats."
  [chunk-id original-text]
  (when-let [chunk (model/get-chunk chunk-id)]
    (let [current-content (:content chunk)
          edn-match (find-edn-any-annotation current-content original-text)]
      (if edn-match
        ;; EDN format - just replace with original text
        (if (events/replace-text-in-editor! edn-match original-text)
          (events/show-toast! (t :ai-annotation-removed))
          (do
            (model/update-chunk! chunk-id {:content (str/replace current-content edn-match original-text)})
            (events/refresh-editor!)
            (events/show-toast! (t :ai-annotation-removed))))
        ;; Fallback: legacy format
        (let [pattern-done (re-pattern
                             (str "\\[!NOTE(@[^:]+)?:\\s*"
                                  (escape-regex original-text)
                                  "\\s*:AI-DONE:[^\\]]*\\]"))
              pattern-pending (re-pattern
                                (str "\\[!NOTE(@[^:]+)?:\\s*"
                                     (escape-regex original-text)
                                     "\\s*:AI:[^\\]]*\\]"))
              annotation-to-remove (or (re-find pattern-done current-content)
                                       (re-find pattern-pending current-content))]
          (when annotation-to-remove
            (let [ann-str (if (vector? annotation-to-remove)
                           (first annotation-to-remove)
                           annotation-to-remove)]
              (if (events/replace-text-in-editor! ann-str original-text)
                (events/show-toast! (t :ai-annotation-removed))
                (do
                  (model/update-chunk! chunk-id {:content (-> current-content
                                                              (str/replace pattern-done original-text)
                                                              (str/replace pattern-pending original-text))})
                  (events/refresh-editor!)
                  (events/show-toast! (t :ai-annotation-removed)))))))))))

;; =============================================================================
;; Get Selected Alternative Text
;; =============================================================================

(defn get-selected-alternative
  "Get the selected alternative text for an AI-DONE annotation.
   Returns the alternative text if sel > 0, otherwise nil."
  [annotation]
  (when (ai-annotation-done? annotation)
    (let [comment (:comment annotation)
          data (parse-ai-data comment)
          sel (:sel data)
          alts (:alts data)]
      (when (and sel (pos? sel) alts)
        (nth alts (dec sel) nil)))))

;; =============================================================================
;; API for AI Panel
;; =============================================================================

(defn handle-annotation-response!
  "Called by AI panel when receiving response for an annotation template.
   Routes the response to the annotation system."
  [pending-key response-text]
  ;; Parse alternatives from response (split by newlines, filter empty)
  (let [alternatives (->> (str/split-lines response-text)
                          (map str/trim)
                          (filter #(not (str/blank? %)))
                          ;; Remove numbering prefixes like "1." or "1)"
                          (map #(str/replace % #"^\d+[\.\)]\s*" ""))
                          (take 3)  ;; Max 3 alternatives
                          vec)]
    (when (seq alternatives)
      (complete-ai-annotation! pending-key alternatives))))

;; =============================================================================
;; Aspect Creation from AI Response
;; =============================================================================

(defn create-aspect-from-response!
  "Create a new aspect chunk from AI response.
   aspect-type: :character, :place, :theme
   response: the AI response text containing name and description"
  [aspect-type response-text]
  (let [;; Parse response - first line is the name, rest is content
        lines (str/split-lines (str/trim response-text))
        raw-name (first lines)
        ;; Clean up name - remove prefixes like "Nome:" or "Name:"
        name (-> raw-name
                 (str/replace #"^(Nome|Name|Personaggio|Character|Luogo|Place|Tema|Theme)\s*:\s*" "")
                 str/trim)
        content (str/join "\n" (rest lines))
        ;; Determine parent based on aspect type
        parent-id (case aspect-type
                    :character "personaggi"
                    :place "luoghi"
                    :theme "temi"
                    nil)
        new-id (str (gensym "aspect-"))]
    (when (and parent-id (not (str/blank? name)))
      ;; Create the new aspect chunk
      (model/add-chunk! :id new-id
                        :parent-id parent-id
                        :summary name
                        :content content)
      ;; Select the new aspect
      (model/select-chunk! new-id)
      ;; Show toast
      (events/show-toast! (str (t :ai-aspect-created) ": " name)))))

;; =============================================================================
;; Aspect Info Extraction (append mode)
;; =============================================================================

(defn- no-new-info?
  "Check if AI response indicates no new information found"
  [response]
  (let [trimmed (str/trim (str/upper-case response))]
    (or (str/includes? trimmed "NESSUNA NUOVA INFORMAZIONE")
        (str/includes? trimmed "NO NEW INFORMATION"))))

(defonce aspect-update-state
  (r/atom {:showing false
           :aspect-id nil
           :aspect-name nil
           :new-info nil}))

(defn show-aspect-update-confirmation!
  "Show the update confirmation modal, or toast if no new info"
  [aspect-id new-info]
  (if (no-new-info? new-info)
    ;; No new info found - just show toast
    (events/show-toast! (t :ai-no-new-info))
    ;; New info found - show confirmation modal
    (let [aspect (model/get-chunk aspect-id)
          aspect-name (or (:summary aspect) aspect-id)]
      (reset! aspect-update-state
              {:showing true
               :aspect-id aspect-id
               :aspect-name aspect-name
               :new-info new-info}))))

(defn hide-aspect-update-confirmation!
  "Hide the update confirmation modal"
  []
  (reset! aspect-update-state {:showing false
                               :aspect-id nil
                               :aspect-name nil
                               :new-info nil}))

(defn apply-aspect-update!
  "Apply the pending aspect update - APPEND new info to existing content"
  []
  (let [{:keys [aspect-id new-info]} @aspect-update-state]
    (when (and aspect-id new-info)
      (let [aspect (model/get-chunk aspect-id)
            existing-content (or (:content aspect) "")
            ;; Append new info with a separator
            updated-content (str existing-content
                                 "\n\n---\n\n"
                                 "### " (t :ai-info-extracted) "\n\n"
                                 new-info)]
        (model/update-chunk! aspect-id {:content updated-content})
        (hide-aspect-update-confirmation!)
        (events/show-toast! (t :ai-info-extracted))))))

;; =============================================================================
;; Response Routing
;; =============================================================================

(defn handle-ai-response!
  "Route AI response based on template output-type.

   Parameters:
   - template-id: the template that was used
   - response-text: the AI response
   - context: map with :chunk-id, :selected-text, :pending-key, :aspect-id"
  [template-id response-text context]
  (let [template (templates/get-template template-id)
        output-type (:output-type template)]
    (case output-type
      ;; Create annotation with alternatives
      :annotation
      (let [{:keys [pending-key]} context
            alternatives (templates/parse-alternatives response-text)]
        (when (and pending-key (seq alternatives))
          (complete-ai-annotation! pending-key alternatives)))

      ;; Create new aspect
      :create-aspect
      (let [aspect-type (:aspect-type template)]
        (create-aspect-from-response! aspect-type response-text))

      ;; Update existing aspect
      :update-aspect
      (let [{:keys [aspect-id]} context]
        (when aspect-id
          (show-aspect-update-confirmation! aspect-id response-text)))

      ;; Default: chat response (handled by ai-panel)
      nil)))
