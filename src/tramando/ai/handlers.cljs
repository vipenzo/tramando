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
;; EDN Serialization for AI Annotation Data (Base64 encoded)
;; =============================================================================
;; Base64 encoding is used to avoid ] in EDN closing the annotation prematurely

(defn serialize-ai-data
  "Serialize alternatives and selection to Base64-encoded EDN string.
   Uses encodeURIComponent to handle Unicode characters."
  [alternatives selected]
  (-> {:alts (vec alternatives) :sel selected}
      pr-str
      js/encodeURIComponent
      js/btoa))

(defn parse-ai-data
  "Parse Base64-encoded EDN string to get alternatives and selection.
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
  (boolean (re-find #"\[!(TODO|NOTE|FIX|PROPOSAL)(?:@[^:]+)?:" text)))

(defn insert-ai-annotation!
  "Insert [!NOTE@user:text:AI:] annotation in chunk content.
   Called when user selects text and chooses an annotation template.
   Returns the pending key for tracking, or nil if text contains nested annotations.
   Uses editor dispatch for proper content sync and undo support.
   In server mode, adds @username. In Tauri/local mode, no @user is added."
  [chunk-id selected-text]
  (cond
    ;; Prevent nested annotations
    (contains-annotation? selected-text)
    (do
      (events/show-toast! (t :error-nested-annotation))
      nil)

    ;; Normal case
    (and chunk-id (not (str/blank? selected-text)))
    (let [;; Add @username if logged in (server mode)
          username (auth/get-username)
          author-part (if (and username (not= username "local"))
                        (str "@" username)
                        "")
          ;; Create the annotation markup: [!NOTE@user:text:AI:]
          annotation-markup (str "[!NOTE" author-part ":" selected-text ":AI:]")
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

(defn complete-ai-annotation!
  "Complete an AI annotation by storing alternatives in EDN format.
   Called when AI response arrives for an :annotation template.

   Updates the annotation to [!NOTE@user:text:AI-DONE:{:alts [...] :sel 0}]
   Preserves the @author part if present."
  [pending-key alternatives]
  (when-let [pending (get @pending-annotations pending-key)]
    (let [{:keys [chunk-id original-text]} pending]
      (when (and chunk-id (seq alternatives))
        (when-let [chunk (model/get-chunk chunk-id)]
          (let [current-content (:content chunk)
                ;; Find the pending annotation and update it
                ;; Allow optional @author and whitespace after [!NOTE(@user)?:
                pending-pattern (re-pattern
                                  (str "\\[!NOTE(@[^:]+)?:\\s*"
                                       (escape-regex original-text)
                                       "\\s*:AI:[^\\]]*\\]"))
                match (re-find pending-pattern current-content)]
            (when match
              (let [;; Capture the @author part if present
                    author-part (or (second match) "")
                    ;; New annotation with AI-DONE and EDN data (preserving @author)
                    edn-data (serialize-ai-data alternatives 0)
                    done-annotation (str "[!NOTE" author-part ":" original-text ":AI-DONE:" edn-data "]")
                    ;; Replace annotation in content
                    new-content (str/replace current-content
                                             pending-pattern
                                             done-annotation)]
                ;; Update chunk content with AI-DONE annotation
                (model/update-chunk! chunk-id {:content new-content})
                ;; Remove from pending
                (swap! pending-annotations dissoc pending-key)
                ;; Refresh editor to show changes
                (events/refresh-editor!)
                ;; Show toast
                (events/show-toast! (str (t :ai-alternatives-received) " (" (count alternatives) ")"))))))))))

;; =============================================================================
;; Update Selection in Annotation
;; =============================================================================

(defn update-ai-selection!
  "Update the :sel value in an AI-DONE annotation.
   chunk-id: the chunk containing the annotation
   original-text: the selected text in the annotation
   new-sel: the new selection index (0 = none, 1/2/3 = alternative)
   Uses editor dispatch for undo support.
   Preserves the @author part if present."
  [chunk-id original-text new-sel]
  (when-let [chunk (model/get-chunk chunk-id)]
    (let [current-content (:content chunk)
          ;; Pattern to find the AI-DONE annotation with this text (Base64 encoded data)
          ;; Allow optional @author and whitespace after [!NOTE(@user)?:
          pattern (re-pattern
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
              ;; Use editor replace for proper undo support
              (if (events/replace-text-in-editor! old-annotation new-annotation)
                ;; Editor transaction succeeded - force decoration refresh after short delay
                (js/setTimeout #(events/refresh-editor!) 50)
                ;; Fallback: direct model update if editor not available
                (do
                  (model/update-chunk! chunk-id {:content (str/replace current-content pattern new-annotation)})
                  (events/refresh-editor!))))))))))

;; =============================================================================
;; Confirm Alternative (Apply Selection)
;; =============================================================================

(defn confirm-ai-alternative!
  "Apply the selected alternative: replace original text with selected alternative.
   Removes the annotation completely. Uses editor dispatch for undo support."
  [chunk-id original-text]
  (when-let [chunk (model/get-chunk chunk-id)]
    (let [current-content (:content chunk)
          ;; Pattern to find the AI-DONE annotation (Base64 encoded data)
          ;; Allow optional @author and whitespace after [!NOTE(@user)?:
          pattern (re-pattern
                    (str "\\[!NOTE(?:@[^:]+)?:\\s*"
                         (escape-regex original-text)
                         "\\s*:AI-DONE:([A-Za-z0-9+/=]+)\\]"))
          match (re-find pattern current-content)]
      (when match
        (let [b64 (second match)
              data (parse-ai-data b64)
              sel (:sel data 0)
              alts (:alts data [])]
          (when (and (pos? sel) (<= sel (count alts)))
            (let [selected-alt (nth alts (dec sel))
                  full-annotation (first match)]
              ;; Use editor replace for proper undo support
              ;; This dispatches a transaction to CodeMirror which:
              ;; 1. Updates the editor content
              ;; 2. Goes through the update listener to sync to model
              ;; 3. Gets added to undo history
              (if (events/replace-text-in-editor! full-annotation selected-alt)
                (events/show-toast! (t :ai-alternative-applied))
                ;; Fallback: direct model update if editor not available
                (let [final-content (str/replace current-content full-annotation selected-alt)]
                  (model/update-chunk! chunk-id {:content final-content})
                  (events/refresh-editor!)
                  (events/show-toast! (t :ai-alternative-applied)))))))))))

;; =============================================================================
;; Cancel AI Annotation
;; =============================================================================

(defn cancel-ai-annotation!
  "Remove an AI annotation, keeping the original text intact.
   Uses editor dispatch for undo support."
  [chunk-id original-text]
  (when-let [chunk (model/get-chunk chunk-id)]
    (let [current-content (:content chunk)
          ;; Pattern to find any AI annotation (pending or done)
          ;; Allow optional @author and whitespace after [!NOTE(@user)?:
          pattern-done (re-pattern
                         (str "\\[!NOTE(?:@[^:]+)?:\\s*"
                              (escape-regex original-text)
                              "\\s*:AI-DONE:[^\\]]*\\]"))
          pattern-pending (re-pattern
                            (str "\\[!NOTE(?:@[^:]+)?:\\s*"
                                 (escape-regex original-text)
                                 "\\s*:AI:[^\\]]*\\]"))
          ;; Find the actual annotation text
          match-done (re-find pattern-done current-content)
          match-pending (re-find pattern-pending current-content)
          annotation-to-remove (or match-done match-pending)]
      (when annotation-to-remove
        ;; Use editor replace for proper undo support
        (if (events/replace-text-in-editor! annotation-to-remove original-text)
          (events/show-toast! (t :ai-annotation-removed))
          ;; Fallback: direct model update if editor not available
          (let [new-content (-> current-content
                                (str/replace pattern-done original-text)
                                (str/replace pattern-pending original-text))]
            (model/update-chunk! chunk-id {:content new-content})
            (events/refresh-editor!)
            (events/show-toast! (t :ai-annotation-removed))))))))

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
          sel (:sel data 0)
          alts (:alts data [])]
      (when (and (pos? sel) (<= sel (count alts)))
        (nth alts (dec sel))))))

;; =============================================================================
;; Aspect Creation (unchanged)
;; =============================================================================

(defn create-aspect-from-response!
  "Create a new aspect from AI response.
   aspect-type: :personaggi or :luoghi
   response-text: the AI response with the aspect sheet"
  [aspect-type response-text]
  (let [{:keys [name content]} (templates/parse-aspect-sheet response-text aspect-type)
        parent-id (case aspect-type
                    :personaggi "personaggi"
                    :luoghi "luoghi"
                    :temi "temi"
                    nil)
        ;; Generate a slug-style ID from the name
        new-id (-> name
                   str/lower-case
                   (str/replace #"\s+" "-")
                   (str/replace #"[^a-z0-9-]" "")
                   (subs 0 (min 30 (count name))))]
    (when parent-id
      ;; Create the new chunk
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
           :new-info nil}))  ;; renamed from new-content to new-info

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
