(ns tramando.ai-panel
  "AI Assistant Panel component and state management"
  (:require [reagent.core :as r]
            [tramando.settings :as settings]
            [tramando.model :as model]
            [tramando.i18n :refer [t get-language]]
            [tramando.ai.api :as ai-api]
            [tramando.ai.templates :as templates]
            [tramando.ai.handlers :as ai-handlers]
            [tramando.events :as events]
            [clojure.string :as str]))

;; =============================================================================
;; Panel State
;; =============================================================================

(defonce panel-state
  (r/atom {:visible false
           :minimized false
           :expanded false
           :messages []
           :input ""
           :loading false}))

;; State for tracking current template action (for response routing)
(defonce template-action-state
  (r/atom {:template-id nil       ;; The template being used
           :chunk-id nil          ;; The chunk context
           :selected-text nil     ;; Selected text (if any)
           :aspect-id nil         ;; For update-aspect: the aspect being updated
           :pending-key nil}))    ;; For annotation: key to find pending annotation

(defn clear-template-action-state! []
  (reset! template-action-state {:template-id nil
                                 :chunk-id nil
                                 :selected-text nil
                                 :aspect-id nil
                                 :pending-key nil}))

;; State for inject response modal
(defonce inject-modal-state
  (r/atom {:visible false
           :response-text ""}))

;; =============================================================================
;; Context Presets
;; =============================================================================

(def context-presets
  {:minimo {:current-chunk true
            :include-children false
            :parent-hierarchy false
            :linked-characters false
            :linked-places false
            :linked-themes false
            :linked-sequences :no
            :linked-timeline :no
            :previous-scene false
            :next-scene false
            :project-metadata false}

   :scena {:current-chunk true
           :include-children true
           :parent-hierarchy false
           :linked-characters true
           :linked-places true
           :linked-themes false
           :linked-sequences :titles
           :linked-timeline :events
           :previous-scene false
           :next-scene false
           :project-metadata false}

   :narrativo {:current-chunk true
               :include-children true
               :parent-hierarchy true
               :linked-characters true
               :linked-places true
               :linked-themes true
               :linked-sequences :titles-desc
               :linked-timeline :events-desc
               :previous-scene true
               :next-scene true
               :project-metadata false}

   :completo {:current-chunk true
              :include-children true
              :parent-hierarchy true
              :linked-characters true
              :linked-places true
              :linked-themes true
              :linked-sequences :full
              :linked-timeline :full
              :previous-scene true
              :next-scene true
              :project-metadata true}})

;; =============================================================================
;; Context State
;; =============================================================================

(defonce context-state
  (r/atom {:preset :scena
           :options (:scena context-presets)
           :custom-selection #{}
           :dropdown-open false
           :preview-open false
           :preview-text nil}))  ; nil = use current context, string = show specific text

;; Cached context calculation (updated via debounce)
(defonce context-cache
  (r/atom {:text ""
           :word-count 0
           :char-count 0
           :last-chunk-id nil}))

;; Debounce timer ref
(defonce context-debounce-timer (atom nil))

;; =============================================================================
;; State Operations
;; =============================================================================

(defn panel-visible? []
  (:visible @panel-state))

(defn show-panel! []
  (when (settings/ai-enabled?)
    (swap! panel-state assoc :visible true :minimized false)))

(defn hide-panel! []
  (swap! panel-state assoc :visible false))

(defn toggle-panel! []
  (if (panel-visible?)
    (hide-panel!)
    (show-panel!)))

(defn minimize-panel! []
  (swap! panel-state assoc :minimized true :expanded false))

(defn restore-panel! []
  (swap! panel-state assoc :minimized false))

(defn toggle-minimize! []
  (if (:minimized @panel-state)
    (restore-panel!)
    (minimize-panel!)))

(defn expand-panel! []
  (swap! panel-state assoc :expanded true :minimized false))

(defn collapse-panel! []
  (swap! panel-state assoc :expanded false))

(defn toggle-expand! []
  (if (:expanded @panel-state)
    (collapse-panel!)
    (expand-panel!)))

(defn set-input! [text]
  (swap! panel-state assoc :input text))

(defn clear-input! []
  (swap! panel-state assoc :input ""))

;; Global ref for the input textarea (for external focus control)
(defonce input-textarea-ref (atom nil))

(defn focus-input!
  "Focus the AI panel input textarea"
  []
  (when-let [textarea @input-textarea-ref]
    (js/setTimeout #(.focus textarea) 100)))

(defn add-message! [role content]
  (swap! panel-state update :messages conj
         {:role role
          :content content
          :timestamp (js/Date.now)}))

(defn clear-messages! []
  (swap! panel-state assoc :messages []))

(defn set-loading! [loading?]
  (swap! panel-state assoc :loading loading?))

;; =============================================================================
;; Context State Operations
;; =============================================================================

(defn set-preset! [preset-key]
  (when-let [preset-options (get context-presets preset-key)]
    (swap! context-state assoc
           :preset preset-key
           :options preset-options)))

(defn set-context-option! [option-key value]
  (swap! context-state
         (fn [state]
           (-> state
               (assoc-in [:options option-key] value)
               (assoc :preset :personalizzato)))))

(defn toggle-context-dropdown! []
  (swap! context-state update :dropdown-open not))

(defn close-context-dropdown! []
  (swap! context-state assoc :dropdown-open false))

(defn open-context-preview!
  "Open preview modal. If text is provided, show that text. Otherwise show current context."
  ([]
   (swap! context-state assoc :preview-open true :preview-text nil))
  ([text]
   (swap! context-state assoc :preview-open true :preview-text text)))

(defn close-context-preview! []
  (swap! context-state assoc :preview-open false :preview-text nil))

;; =============================================================================
;; Context Building
;; =============================================================================

(defn- extract-aspect-refs
  "Extract [@id] references from text content"
  [text]
  (when text
    (let [pattern #"\[@([^\]]+)\]"]
      (->> (re-seq pattern text)
           (map second)
           (into #{})))))

(defn- get-chunk-aspects
  "Get aspects attached to a chunk (both as tags and as [@id] refs in content)"
  [chunk]
  (let [explicit-aspects (set (:aspects chunk))
        content-refs (extract-aspect-refs (:content chunk))]
    (into explicit-aspects content-refs)))

;; -----------------------------------------------------------------------------
;; Chunk Type Detection
;; -----------------------------------------------------------------------------

(defn- get-aspect-category
  "Get the aspect category for a chunk (:personaggi, :luoghi, :temi, :sequenze, :timeline, or nil)"
  [chunk]
  (let [parent-id (:parent-id chunk)]
    (case parent-id
      "personaggi" :personaggi
      "luoghi" :luoghi
      "temi" :temi
      "sequenze" :sequenze
      "timeline" :timeline
      ;; Check if it's a nested aspect (child of another aspect)
      (when (model/is-aspect-chunk? chunk)
        ;; Walk up to find the root aspect container
        (loop [current-parent parent-id]
          (when current-parent
            (case current-parent
              "personaggi" :personaggi
              "luoghi" :luoghi
              "temi" :temi
              "sequenze" :sequenze
              "timeline" :timeline
              (when-let [parent-chunk (model/get-chunk current-parent)]
                (recur (:parent-id parent-chunk))))))))))

(defn- get-chunk-context-type
  "Determine how to build context for this chunk.
   Returns :structure, :aspect, or :container"
  [chunk]
  (cond
    ;; Aspect containers (personaggi, luoghi, etc.)
    (model/is-aspect-container? (:id chunk))
    :container

    ;; Aspect chunks (direct or nested children of aspect containers)
    (model/is-aspect-chunk? chunk)
    :aspect

    ;; Structure chunks with children but no content (chapters, parts)
    (and (not (str/blank? (:id chunk)))
         (seq (model/get-children (:id chunk)))
         (str/blank? (:content chunk)))
    :container

    ;; Everything else is structure (scenes, passages)
    :else
    :structure))

;; -----------------------------------------------------------------------------
;; Finding Chunks That Use an Aspect
;; -----------------------------------------------------------------------------

(defn- chunks-referencing-aspect
  "Find all structure chunks that reference this aspect (via :aspects or [@id] in content)"
  [aspect-id]
  (let [all-chunks (model/get-chunks)]
    (->> all-chunks
         (filter (fn [chunk]
                   (and
                    ;; Not the aspect itself
                    (not= (:id chunk) aspect-id)
                    ;; Not an aspect chunk (we want structure/scenes only)
                    (not (model/is-aspect-chunk? chunk))
                    ;; Not an aspect container
                    (not (model/is-aspect-container? (:id chunk)))
                    ;; References this aspect
                    (or (contains? (:aspects chunk) aspect-id)
                        (contains? (extract-aspect-refs (:content chunk)) aspect-id)))))
         (vec))))

(defn- get-sibling-chunks
  "Get previous and next sibling chunks"
  [chunk-id]
  (let [chunk (model/get-chunk chunk-id)
        parent-id (:parent chunk)
        siblings (if parent-id
                   (:children (model/get-chunk parent-id))
                   (model/get-root-chunks))
        idx (.indexOf (vec siblings) chunk-id)]
    {:previous (when (pos? idx) (nth siblings (dec idx)))
     :next (when (< (inc idx) (count siblings)) (nth siblings (inc idx)))}))

(defn- format-chunk-section
  "Format a chunk for context output"
  [chunk header-level]
  (let [header-prefix (apply str (repeat header-level "#"))]
    (str header-prefix " " (or (:summary chunk) "(senza titolo)") "\n"
         "ID: " (:id chunk) "\n"
         (when-let [content (:content chunk)]
           (str "\n" content "\n")))))

(defn- format-aspect-section
  "Format an aspect chunk with appropriate detail level"
  [aspect-id detail-level]
  (when-let [aspect (model/get-chunk aspect-id)]
    (case detail-level
      :no nil
      :titles (str "- " (or (:summary aspect) aspect-id) "\n")
      :events (str "- " (or (:summary aspect) aspect-id) "\n")
      :titles-desc (str "### " (or (:summary aspect) aspect-id) "\n"
                        (when-let [content (:content aspect)]
                          (let [first-para (first (str/split content #"\n\n"))]
                            (str first-para "\n\n"))))
      :events-desc (str "### " (or (:summary aspect) aspect-id) "\n"
                        (when-let [content (:content aspect)]
                          (let [first-para (first (str/split content #"\n\n"))]
                            (str first-para "\n\n"))))
      :full (format-chunk-section aspect 3)
      nil)))

(defn- get-ancestors
  "Get parent hierarchy of a chunk"
  [chunk-id]
  (loop [id chunk-id
         ancestors []]
    (if-let [chunk (model/get-chunk id)]
      (if-let [parent-id (:parent chunk)]
        (recur parent-id (conj ancestors parent-id))
        ancestors)
      ancestors)))

;; -----------------------------------------------------------------------------
;; Structure Context Builder (for scenes, chapters with content)
;; -----------------------------------------------------------------------------

(defn- build-structure-context
  "Build context for structure chunks (scenes, passages)"
  [chunk options add-section!]
  (let [chunk-aspects (get-chunk-aspects chunk)
        all-chunks (model/get-chunks)]

    ;; Characters - filter by parent-id = "personaggi"
    (when (:linked-characters options)
      (let [char-chunks (filter #(and (= (:parent-id %) "personaggi")
                                      (contains? chunk-aspects (:id %)))
                                all-chunks)]
        (when (seq char-chunks)
          (add-section! (t :ai-context-characters)
                        (str/join "\n"
                                  (for [c char-chunks]
                                    (str "### " (or (:summary c) (:id c)) "\n"
                                         (when-let [content (:content c)]
                                           (str content "\n")))))))))

    ;; Places - filter by parent-id = "luoghi"
    (when (:linked-places options)
      (let [place-chunks (filter #(and (= (:parent-id %) "luoghi")
                                       (contains? chunk-aspects (:id %)))
                                 all-chunks)]
        (when (seq place-chunks)
          (add-section! (t :ai-context-places)
                        (str/join "\n"
                                  (for [c place-chunks]
                                    (str "### " (or (:summary c) (:id c)) "\n"
                                         (when-let [content (:content c)]
                                           (str content "\n")))))))))

    ;; Themes - filter by parent-id = "temi"
    (when (:linked-themes options)
      (let [theme-chunks (filter #(and (= (:parent-id %) "temi")
                                       (contains? chunk-aspects (:id %)))
                                 all-chunks)]
        (when (seq theme-chunks)
          (add-section! (t :ai-context-themes)
                        (str/join "\n"
                                  (for [c theme-chunks]
                                    (str "### " (or (:summary c) (:id c)) "\n"
                                         (when-let [content (:content c)]
                                           (str content "\n")))))))))

    ;; Sequences - filter by parent-id = "sequenze"
    (when (and (:linked-sequences options)
               (not= (:linked-sequences options) :no))
      (let [detail (:linked-sequences options)
            seq-chunks (filter #(and (= (:parent-id %) "sequenze")
                                     (contains? chunk-aspects (:id %)))
                               all-chunks)]
        (when (seq seq-chunks)
          (add-section! (t :ai-context-sequences)
                        (str/join ""
                                  (for [c seq-chunks]
                                    (format-aspect-section (:id c) detail)))))))

    ;; Timeline - filter by parent-id = "timeline"
    (when (and (:linked-timeline options)
               (not= (:linked-timeline options) :no))
      (let [detail (:linked-timeline options)
            tl-chunks (filter #(and (= (:parent-id %) "timeline")
                                    (contains? chunk-aspects (:id %)))
                              all-chunks)]
        (when (seq tl-chunks)
          (add-section! (t :ai-context-timeline)
                        (str/join ""
                                  (for [c tl-chunks]
                                    (format-aspect-section (:id c) detail)))))))))

;; -----------------------------------------------------------------------------
;; Aspect Context Builder (for characters, places, themes, etc.)
;; -----------------------------------------------------------------------------

(defn- build-aspect-context
  "Build context for aspect chunks (characters, places, themes)"
  [chunk options add-section!]
  (let [aspect-id (:id chunk)
        category (get-aspect-category chunk)
        category-name (case category
                        :personaggi (t :personaggi)
                        :luoghi (t :luoghi)
                        :temi (t :temi)
                        :sequenze (t :sequenze)
                        :timeline (t :timeline)
                        "Aspetto")
        ;; Find scenes that use this aspect
        using-chunks (chunks-referencing-aspect aspect-id)
        using-count (count using-chunks)]

    ;; Show aspect type
    (add-section! (t :ai-context-aspect-type)
                  (str "**" category-name "**"))

    ;; Note: Children are now included via :include-children option in build-context

    ;; Show "Used in" section with scenes
    (when (seq using-chunks)
      (let [;; Use linked-sequences option level for detail (or default to :titles)
            detail-level (or (:linked-sequences options) :titles)
            scene-texts (case detail-level
                          :no nil
                          :titles (for [c using-chunks]
                                    (str "- " (or (:summary c) (:id c))))
                          :titles-desc (for [c using-chunks]
                                         (str "### " (or (:summary c) (:id c)) "\n"
                                              (when-let [content (:content c)]
                                                (let [first-para (first (str/split content #"\n\n"))]
                                                  (str first-para "\n")))))
                          ;; :full or default
                          (for [c using-chunks]
                            (str "### " (or (:summary c) (:id c)) "\n"
                                 (when-let [content (:content c)]
                                   (str content "\n")))))]
        (when scene-texts
          (add-section! (str (t :ai-context-used-in)
                             " (" (if (= using-count 1)
                                    (t :ai-context-used-in-1)
                                    (t :ai-context-used-in-n using-count)) ")")
                        (str/join "\n" scene-texts)))))))

;; -----------------------------------------------------------------------------
;; Container Context Builder (for chapters, parts, aspect containers)
;; -----------------------------------------------------------------------------

(defn- build-container-context
  "Build context for container chunks (chapters, parts, aspect containers)"
  [chunk options add-section!]
  (let [children (model/get-children (:id chunk))
        child-count (count children)]
    (when (seq children)
      (add-section! (str (t :ai-context-children)
                         " (" (t :ai-context-n-children child-count) ")")
                    (str/join "\n"
                              (for [c children]
                                (str "- **" (or (:summary c) (:id c)) "**"
                                     (when-let [content (:content c)]
                                       (when (not (str/blank? content))
                                         (let [preview (subs content 0 (min 100 (count content)))]
                                           (str "\n  " (str/replace preview #"\n" " ")
                                                (when (> (count content) 100) "..."))))))))))))

;; -----------------------------------------------------------------------------
;; Main Context Builder
;; -----------------------------------------------------------------------------

(defn build-context
  "Build the context text based on options and selected chunk.
   Adapts to chunk type: structure (scenes), aspects (characters), or containers.
   Returns {:text \"...\" :word-count N :char-count N}"
  []
  (let [selected-id (:selected-id @model/app-state)
        chunk (when selected-id (model/get-chunk selected-id))
        options (:options @context-state)]
    (if-not chunk
      {:text "" :word-count 0 :char-count 0}
      (let [sections (atom [])
            add-section! (fn [title content]
                           (when (and content (not (str/blank? content)))
                             (swap! sections conj (str "## " title "\n\n" content "\n"))))
            chunk-type (get-chunk-context-type chunk)

            ;; Current chunk (always show if enabled)
            _ (when (:current-chunk options)
                (add-section! (t :ai-context-current-chunk)
                              (str "**" (or (:summary chunk) "(senza titolo)") "**\n"
                                   "ID: " (:id chunk) "\n"
                                   (when-let [content (:content chunk)]
                                     (str "\n" content)))))

            ;; Include children content (sub-sections, sub-aspects, scenes in chapter)
            _ (when (:include-children options)
                (let [children (model/get-children (:id chunk))]
                  (doseq [child children]
                    (when (or (:content child) (seq (model/get-children (:id child))))
                      (add-section! (or (:summary child) (:id child))
                                    (or (:content child) ""))))))

            ;; Parent hierarchy (for structure chunks mainly)
            _ (when (and (:parent-hierarchy options)
                         (= chunk-type :structure))
                (let [ancestors (get-ancestors selected-id)
                      ancestor-texts (for [aid (reverse ancestors)
                                           :let [a (model/get-chunk aid)]
                                           :when (and a (not (model/is-aspect-container? aid)))]
                                       (str "- " (or (:summary a) aid)))]
                  (when (seq ancestor-texts)
                    (add-section! (t :ai-context-parent-hierarchy)
                                  (str/join "\n" ancestor-texts)))))

            ;; Type-specific context
            _ (case chunk-type
                :structure (build-structure-context chunk options add-section!)
                :aspect (build-aspect-context chunk options add-section!)
                :container (build-container-context chunk options add-section!)
                nil)

            ;; Previous/Next scene (only for structure chunks)
            _ (when (= chunk-type :structure)
                (let [siblings (get-sibling-chunks selected-id)]
                  (when (and (:previous-scene options) (:previous siblings))
                    (let [prev (model/get-chunk (:previous siblings))]
                      (when prev
                        (add-section! (t :ai-context-previous-scene)
                                      (str "**" (or (:summary prev) "(senza titolo)") "**\n"
                                           (when-let [content (:content prev)]
                                             (str "\n" content)))))))
                  (when (and (:next-scene options) (:next siblings))
                    (let [nxt (model/get-chunk (:next siblings))]
                      (when nxt
                        (add-section! (t :ai-context-next-scene)
                                      (str "**" (or (:summary nxt) "(senza titolo)") "**\n"
                                           (when-let [content (:content nxt)]
                                             (str "\n" content)))))))))

            ;; Project metadata (always available)
            _ (when (:project-metadata options)
                (let [metadata (model/get-metadata)]
                  (when (seq metadata)
                    (add-section! (t :ai-context-metadata)
                                  (str/join "\n"
                                            (for [[k v] metadata
                                                  :when (and v (not (str/blank? (str v))))]
                                              (str "- **" (name k) "**: " v)))))))

            ;; Build final text
            text (str/join "\n" @sections)
            words (if (str/blank? text)
                    0
                    (count (str/split (str/trim text) #"\s+")))]
        {:text text
         :word-count words
         :char-count (count text)}))))

(defn update-context-cache!
  "Update the context cache (call with debounce)"
  []
  (let [result (build-context)]
    (reset! context-cache
            (assoc result :last-chunk-id (:selected-id @model/app-state)))))

(defn schedule-context-update!
  "Schedule a debounced context update"
  []
  (when @context-debounce-timer
    (js/clearTimeout @context-debounce-timer))
  (reset! context-debounce-timer
          (js/setTimeout update-context-cache! 300)))

;; Watch for changes that affect context
(add-watch context-state :context-update
           (fn [_ _ old new]
             (when (not= (:options old) (:options new))
               (schedule-context-update!))))

(add-watch model/app-state :context-chunk-update
           (fn [_ _ old new]
             (when (not= (:selected-id old) (:selected-id new))
               (schedule-context-update!))))

;; =============================================================================
;; Conversation History
;; =============================================================================

(defn get-conversation-history
  "Get last N messages from conversation (without context, just for continuity)"
  []
  (let [messages (:messages @panel-state)
        ;; Take last 10 messages to avoid token explosion
        recent (take-last 10 messages)]
    (mapv (fn [{:keys [role content]}]
            {:role role :content content})
          recent)))

;; =============================================================================
;; Message Building
;; =============================================================================

(defn- build-messages-for-api
  "Build the full message array for API call"
  [user-message context-text]
  (let [system-prompt (ai-api/get-system-prompt)
        full-prompt (ai-api/build-full-prompt user-message context-text)
        history (get-conversation-history)]
    ;; System + history + new user message with context
    (concat
     [{:role :system :content system-prompt}]
     history
     [{:role :user :content full-prompt}])))

;; =============================================================================
;; Send Message
;; =============================================================================

(defn add-user-message!
  "Add a user message with optional context"
  [content context]
  (swap! panel-state update :messages conj
         {:role :user
          :content content
          :timestamp (js/Date.now)
          :context context}))

;; =============================================================================
;; Template Action Handler (for context menu)
;; =============================================================================

;; Forward declaration for auto-send
(declare send-message!)

(defn handle-template-action!
  "Handle a template action from the context menu.
   template-id - keyword identifying the template
   chunk - the current chunk (optional)
   selected-text - text selected in the editor (optional)
   aspect-id - for :update-aspect, the ID of the aspect to update"
  [template-id chunk selected-text aspect-id]
  (let [template (templates/get-template template-id)
        output-type (:output-type template)
        ;; Build the prompt using the template system (which handles output-type suffixes)
        base-prompt (cond
                      ;; Dynamic prompt for extract-info (update-aspect)
                      (:dynamic-prompt template)
                      (when aspect-id
                        (let [aspect (model/get-chunk aspect-id)
                              aspect-name (or (:summary aspect) aspect-id)
                              aspect-content (or (:content aspect) "")]
                          (templates/build-extract-info-prompt aspect-name aspect-content)))

                      ;; Use the template prompt system (includes annotation suffix)
                      :else
                      (templates/get-template-prompt template-id))

        ;; Add selected text to prompt if available
        full-prompt (if (and selected-text (not (str/blank? selected-text)))
                      (str base-prompt "\n\n---\n" (t :ai-selected-text) ":\n" selected-text)
                      base-prompt)

        ;; Get the context preset
        preset-key (:context-preset template)

        ;; For annotation templates, insert [!NOTE:text:AI:] NOW
        pending-key (when (and (= output-type :annotation)
                               chunk
                               (not (str/blank? selected-text)))
                      (ai-handlers/insert-ai-annotation! (:id chunk) selected-text))]

    ;; Store template context for response routing
    (reset! template-action-state
            {:template-id template-id
             :chunk-id (:id chunk)
             :selected-text selected-text
             :aspect-id aspect-id
             :pending-key pending-key})

    ;; 1. Show the panel
    (show-panel!)

    ;; 2. Set the context preset
    (when preset-key
      (set-preset! preset-key))

    ;; 3. Set the input
    (set-input! (or full-prompt ""))

    ;; 4. Auto-send if enabled, otherwise focus input for manual send
    (if (settings/ai-auto-send?)
      ;; Use js/setTimeout to ensure state is updated before sending
      (js/setTimeout send-message! 100)
      (focus-input!))))

(defn send-message! []
  (let [input (str/trim (:input @panel-state))]
    (when (and (not (str/blank? input))
               (not (:loading @panel-state)))
      ;; Check if AI is configured
      (if (not (settings/ai-configured?))
        ;; Not configured - show error message
        (add-message! :assistant (str "⚠️ " (t :ai-not-configured)))
        ;; Configured - proceed with API call
        (let [context-text (:text @context-cache)
              ;; Build the full prompt that will be sent to AI
              full-context (ai-api/build-full-prompt input context-text)
              config (ai-api/get-ai-config)
              messages (build-messages-for-api input context-text)
              ;; Capture template state before sending
              template-state @template-action-state
              template-id (:template-id template-state)
              template (when template-id (templates/get-template template-id))
              output-type (or (:output-type template) :chat)]

          ;; Add user message to chat (with full context for debug)
          (add-user-message! input full-context)
          (clear-input!)
          (set-loading! true)

          ;; Call AI API
          (ai-api/send-message
           config
           messages
           ;; on-success
           (fn [response]
             (set-loading! false)
             ;; Route response based on output-type
             (case output-type
               ;; Annotation: create AI annotation with alternatives
               :annotation
               (do
                 (ai-handlers/handle-ai-response!
                  template-id
                  response
                  {:chunk-id (:chunk-id template-state)
                   :selected-text (:selected-text template-state)
                   :pending-key (:pending-key template-state)})
                 ;; Also show response in chat for reference
                 (add-message! :assistant response))

               ;; Create aspect: parse and create new aspect
               :create-aspect
               (do
                 (ai-handlers/handle-ai-response!
                  template-id
                  response
                  {})
                 ;; Also show response in chat
                 (add-message! :assistant response))

               ;; Update aspect: show confirmation modal
               :update-aspect
               (do
                 (ai-handlers/handle-ai-response!
                  template-id
                  response
                  {:aspect-id (:aspect-id template-state)})
                 ;; Also show response in chat
                 (add-message! :assistant response))

               ;; Default: chat response (show in chat)
               (add-message! :assistant response))

             ;; Clear template state after handling
             (clear-template-action-state!))

           ;; on-error
           (fn [error]
             (set-loading! false)
             (add-message! :assistant (str "❌ " error))
             (clear-template-action-state!))))))))

;; =============================================================================
;; Manual Workflow: Copy for Chat & Inject Response
;; =============================================================================

(defn copy-for-chat!
  "Copy the full prompt to clipboard for use with external AI chat.
   Creates the annotation if there's a pending template action."
  []
  (let [input (str/trim (:input @panel-state))
        context-text (:text @context-cache)
        system-prompt (ai-api/get-system-prompt)
        ;; Build readable prompt
        full-prompt (str "## ISTRUZIONI\n\n"
                         system-prompt
                         "\n\n---\n\n"
                         (when (seq context-text)
                           (str "## CONTESTO DELLA STORIA\n\n"
                                context-text
                                "\n\n---\n\n"))
                         "## RICHIESTA\n\n"
                         input)]
    (when (not (str/blank? input))
      ;; Copy to clipboard
      (-> (js/navigator.clipboard.writeText full-prompt)
          (.then (fn []
                   ;; Show toast
                   (events/show-toast! (t :ai-copied-for-chat))
                   ;; Add as user message in chat (collapsed)
                   (add-user-message! input (ai-api/build-full-prompt input context-text))))
          (.catch (fn [err]
                    (js/console.error "Failed to copy:" err)))))))

(defn show-inject-modal! []
  (swap! inject-modal-state assoc :visible true :response-text ""))

(defn hide-inject-modal! []
  (swap! inject-modal-state assoc :visible false :response-text ""))

(defn set-inject-response-text! [text]
  (swap! inject-modal-state assoc :response-text text))

(defn process-injected-response!
  "Process a response pasted from external AI chat."
  []
  (let [response (str/trim (:response-text @inject-modal-state))
        template-state @template-action-state
        template-id (:template-id template-state)
        template (when template-id (templates/get-template template-id))
        output-type (or (:output-type template) :chat)]
    (when (not (str/blank? response))
      ;; Route response based on output-type (same logic as send-message!)
      (case output-type
        ;; Annotation: create AI annotation with alternatives
        :annotation
        (let [{:keys [pending-key]} template-state]
          (if pending-key
            (do
              (ai-handlers/handle-ai-response!
               template-id
               response
               {:pending-key pending-key})
              (add-message! :assistant response))
            ;; No pending annotation, just show in chat
            (add-message! :assistant (str "⚠️ " (t :ai-no-pending-annotation) "\n\n" response))))

        ;; Create aspect: parse and create new aspect
        :create-aspect
        (do
          (ai-handlers/handle-ai-response!
           template-id
           response
           {})
          (add-message! :assistant response))

        ;; Update aspect: show confirmation modal
        :update-aspect
        (do
          (ai-handlers/handle-ai-response!
           template-id
           response
           {:aspect-id (:aspect-id template-state)})
          (add-message! :assistant response))

        ;; Default: chat response (show in chat)
        (add-message! :assistant response))

      ;; Clear template state after handling
      (clear-template-action-state!)
      ;; Hide modal
      (hide-inject-modal!))))

;; =============================================================================
;; Watch for AI settings changes
;; =============================================================================

(add-watch settings/settings :ai-panel-watcher
           (fn [_ _ old-state new-state]
             ;; Close panel if AI gets disabled
             (when (and (get-in old-state [:ai :enabled])
                        (not (get-in new-state [:ai :enabled])))
               (hide-panel!))))

;; =============================================================================
;; Panel Heights
;; =============================================================================

(def panel-height-minimized 40)
(def panel-height-default 200)
(def panel-height-expanded 400)

(defn get-panel-height []
  (let [{:keys [minimized expanded]} @panel-state]
    (cond
      minimized panel-height-minimized
      expanded panel-height-expanded
      :else panel-height-default)))

;; =============================================================================
;; Simple Markdown Rendering
;; =============================================================================

(defn parse-inline-markdown
  "Parse simple inline markdown: **bold**, *italic*, `code`"
  [text]
  (let [;; Split by code blocks first
        parts (str/split text #"(`[^`]+`)")]
    (mapcat
     (fn [part]
       (if (and (str/starts-with? part "`")
                (str/ends-with? part "`"))
         ;; Code block
         [[:code {:key (str "code-" (hash part))
                  :style {:background "rgba(0,0,0,0.2)"
                          :padding "1px 4px"
                          :border-radius "3px"
                          :font-family "monospace"
                          :font-size "0.9em"}}
           (subs part 1 (dec (count part)))]]
         ;; Parse bold and italic
         (let [;; Bold: **text**
               bold-parts (str/split part #"(\*\*[^*]+\*\*)")]
           (mapcat
            (fn [bp]
              (if (and (str/starts-with? bp "**")
                       (str/ends-with? bp "**"))
                [[:strong {:key (str "bold-" (hash bp))}
                  (subs bp 2 (- (count bp) 2))]]
                ;; Italic: *text*
                (let [italic-parts (str/split bp #"(\*[^*]+\*)")]
                  (map
                   (fn [ip]
                     (if (and (str/starts-with? ip "*")
                              (str/ends-with? ip "*")
                              (> (count ip) 2))
                       [:em {:key (str "italic-" (hash ip))}
                        (subs ip 1 (dec (count ip)))]
                       ip))
                   italic-parts))))
            bold-parts))))
     parts)))

(defn render-message-content [content]
  [:div.message-content
   (for [[idx line] (map-indexed vector (str/split-lines content))]
     ^{:key idx}
     [:p {:style {:margin "0 0 0.3em 0"}}
      (parse-inline-markdown line)])])

;; =============================================================================
;; UI Components
;; =============================================================================

(defn message-bubble [{:keys [role content context]}]
  (let [is-user? (= role :user)
        has-context? (and is-user? (not (str/blank? context)))
        accent (settings/get-color :accent)
        bg-color (if is-user?
                   accent
                   (settings/get-color :editor-bg))
        text-color (if is-user?
                     "#ffffff"
                     (settings/get-color :text))]
    [:div {:style {:display "flex"
                   :flex-direction "column"
                   :align-items (if is-user? "flex-end" "flex-start")
                   :margin-bottom "8px"}}
     [:div {:style {:max-width "80%"
                    :background bg-color
                    :color text-color
                    :padding "8px 12px"
                    :border-radius "12px"
                    :border (when-not is-user?
                              (str "1px solid " (settings/get-color :border)))
                    :font-size "0.9rem"
                    :line-height "1.4"}}
      (if is-user?
        content
        [render-message-content content])]
     ;; Show context link for user messages
     (when has-context?
       [:button {:style {:background "transparent"
                         :border "none"
                         :color (settings/get-color :text-muted)
                         :font-size "0.75rem"
                         :cursor "pointer"
                         :padding "4px 8px"
                         :margin-top "2px"
                         :opacity 0.7
                         :display "flex"
                         :align-items "center"
                         :gap "4px"}
                 :on-click #(open-context-preview! context)}
        [:span "📋"]
        [:span (t :ai-show-context)]])]))

;; =============================================================================
;; Context Dropdown & Preview
;; =============================================================================

(defn- word-count-color
  "Get color for word count indicator"
  [_word-count]
  ;; All use accent color for consistency
  (settings/get-color :accent))

(defn- word-count-indicator []
  (let [{:keys [word-count]} @context-cache
        color (word-count-color word-count)]
    [:span {:style {:display "inline-flex"
                    :align-items "center"
                    :gap "6px"}}
     [:span (str word-count " " (t :ai-context-words))]
     [:span {:style {:width "8px"
                     :height "8px"
                     :border-radius "50%"
                     :background color}}]]))

(defn- checkbox-option [label option-key]
  (let [checked? (get-in @context-state [:options option-key])]
    [:label {:style {:display "flex"
                     :align-items "center"
                     :gap "8px"
                     :cursor "pointer"
                     :padding "4px 0"
                     :color (settings/get-color :text)
                     :font-size "0.85rem"}}
     [:input {:type "checkbox"
              :checked (boolean checked?)
              :style {:cursor "pointer"}
              :on-change #(set-context-option! option-key (-> % .-target .-checked))}]
     label]))

(defn- detail-select [label option-key options-list]
  (let [current-value (get-in @context-state [:options option-key])]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "8px"
                   :padding "4px 0"}}
     [:span {:style {:color (settings/get-color :text)
                     :font-size "0.85rem"
                     :min-width "80px"}}
      label]
     [:select {:value (name (or current-value :no))
               :style {:flex 1
                       :background (settings/get-color :editor-bg)
                       :color (settings/get-color :text)
                       :border (str "1px solid " (settings/get-color :border))
                       :border-radius "4px"
                       :padding "4px 8px"
                       :font-size "0.85rem"
                       :cursor "pointer"}
               :on-change #(set-context-option! option-key (keyword (-> % .-target .-value)))}
      (for [[value label-key] options-list]
        ^{:key value}
        [:option {:value (name value)} (t label-key)])]]))

(defn- section-divider [title]
  [:div {:style {:display "flex"
                 :align-items "center"
                 :gap "8px"
                 :margin "8px 0 4px 0"
                 :color (settings/get-color :text-muted)
                 :font-size "0.75rem"
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"}}
   [:span title]
   [:div {:style {:flex 1
                  :height "1px"
                  :background (settings/get-color :border)}}]])

(defn context-dropdown []
  (let [button-ref (r/atom nil)
        dropdown-pos (r/atom {:left 0 :bottom 0})]
    (fn []
      (let [{:keys [preset dropdown-open options]} @context-state
            presets-list [[:minimo :ai-context-preset-minimo :ai-context-preset-minimo-desc]
                          [:scena :ai-context-preset-scena :ai-context-preset-scena-desc]
                          [:narrativo :ai-context-preset-narrativo :ai-context-preset-narrativo-desc]
                          [:completo :ai-context-preset-completo :ai-context-preset-completo-desc]]]
        [:div {:style {:position "relative"}}
         ;; Trigger button
         [:button {:ref #(reset! button-ref %)
                   :style {:background "transparent"
                           :color (settings/get-color :text)
                           :border (str "1px solid " (settings/get-color :border))
                           :padding "8px 12px"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.85rem"
                           :white-space "nowrap"
                           :display "flex"
                           :align-items "center"
                           :gap "6px"}
                   :on-click (fn []
                               ;; Calculate position before opening
                               (when-let [btn @button-ref]
                                 (let [rect (.getBoundingClientRect btn)]
                                   (reset! dropdown-pos
                                           {:left (.-left rect)
                                            :bottom (- js/window.innerHeight (.-top rect))})))
                               (toggle-context-dropdown!))}
          (t :ai-context)
          [:span {:style {:font-size "0.7rem"}} "▼"]]

         ;; Dropdown panel (position: fixed to escape overflow: hidden)
         (when dropdown-open
           [:div {:style {:position "fixed"
                          :left (str (:left @dropdown-pos) "px")
                          :bottom (str (+ (:bottom @dropdown-pos) 4) "px")
                          :width "320px"
                          :max-height "400px"
                          :overflow-y "auto"
                          :background (settings/get-color :sidebar)
                          :border (str "1px solid " (settings/get-color :border))
                          :border-radius "8px"
                          :box-shadow "0 -4px 20px rgba(0,0,0,0.3)"
                          :padding "12px"
                          :z-index 1000}}

            ;; Click outside to close (use mousedown to avoid text selection issues)
            [:div {:style {:position "fixed"
                           :top 0 :left 0 :right 0 :bottom 0
                           :z-index -1}
                   :on-mouse-down close-context-dropdown!}]

        ;; Preset selector
        [:div {:style {:margin-bottom "12px"}}
         [:label {:style {:color (settings/get-color :text-muted)
                          :font-size "0.75rem"
                          :text-transform "uppercase"
                          :letter-spacing "0.5px"
                          :display "block"
                          :margin-bottom "6px"}}
          (t :ai-context-preset)]
         [:select {:value (name preset)
                   :style {:width "100%"
                           :background (settings/get-color :editor-bg)
                           :color (settings/get-color :text)
                           :border (str "1px solid " (settings/get-color :border))
                           :border-radius "4px"
                           :padding "8px 12px"
                           :font-size "0.9rem"
                           :cursor "pointer"}
                   :on-change #(set-preset! (keyword (-> % .-target .-value)))}
          (for [[key label-key desc-key] presets-list]
            ^{:key key}
            [:option {:value (name key)}
             (str (t label-key) " - " (t desc-key))])
          [:option {:value "personalizzato"} (t :ai-context-preset-personalizzato)]]]

        ;; Options
        [:div {:style {:border-top (str "1px solid " (settings/get-color :border))
                       :padding-top "8px"}}
         ;; Basic options
         [checkbox-option (t :ai-context-current-chunk) :current-chunk]
         [checkbox-option (t :ai-context-include-children) :include-children]
         [checkbox-option (t :ai-context-parent-hierarchy) :parent-hierarchy]

         ;; Linked aspects
         [section-divider (t :ai-context-linked-aspects)]
         [checkbox-option (t :ai-context-characters) :linked-characters]
         [checkbox-option (t :ai-context-places) :linked-places]
         [checkbox-option (t :ai-context-themes) :linked-themes]

         ;; Narrative flow
         [section-divider (t :ai-context-narrative-flow)]
         [detail-select (t :ai-context-sequences) :linked-sequences
          [[:no :ai-context-detail-no]
           [:titles :ai-context-detail-titles]
           [:titles-desc :ai-context-detail-titles-desc]
           [:full :ai-context-detail-full]]]
         [detail-select (t :ai-context-timeline) :linked-timeline
          [[:no :ai-context-detail-no]
           [:events :ai-context-detail-events]
           [:events-desc :ai-context-detail-events-desc]
           [:full :ai-context-detail-full]]]
         [checkbox-option (t :ai-context-previous-scene) :previous-scene]
         [checkbox-option (t :ai-context-next-scene) :next-scene]

         ;; Project
         [section-divider (t :ai-context-project)]
         [checkbox-option (t :ai-context-metadata) :project-metadata]]

        ;; Preview button
        [:div {:style {:margin-top "12px"
                       :padding-top "12px"
                       :border-top (str "1px solid " (settings/get-color :border))}}
         [:button {:style {:width "100%"
                           :background (settings/get-color :accent)
                           :color "#ffffff"
                           :border "none"
                           :padding "8px"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.85rem"
                           :font-weight "500"}
                   :on-click (fn []
                               (close-context-dropdown!)
                               (open-context-preview!))}
          (t :ai-context-preview)]]])]))))

(defn context-preview-modal []
  (let [copied? (r/atom false)]
    (fn []
      (let [{:keys [preview-open preview-text]} @context-state
            ;; Use preview-text if provided, otherwise use current context
            display-text (or preview-text (:text @context-cache))
            word-count (if (str/blank? display-text)
                         0
                         (count (str/split (str/trim display-text) #"\s+")))
            char-count (count display-text)]
        (when preview-open
          [:div {:style {:position "fixed"
                         :top 0 :left 0 :right 0 :bottom 0
                         :background "rgba(0,0,0,0.7)"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :z-index 1001}
                 :on-click #(when (= (.-target %) (.-currentTarget %))
                              (close-context-preview!))}
           [:div {:style {:background (settings/get-color :sidebar)
                          :border-radius "8px"
                          :width "700px"
                          :max-width "90vw"
                          :max-height "80vh"
                          :display "flex"
                          :flex-direction "column"
                          :box-shadow "0 10px 40px rgba(0,0,0,0.5)"}}
            ;; Header
            [:div {:style {:display "flex"
                           :justify-content "space-between"
                           :align-items "center"
                           :padding "16px 20px"
                           :border-bottom (str "1px solid " (settings/get-color :border))}}
             [:span {:style {:color (settings/get-color :text)
                             :font-weight "500"}}
              (t :ai-context-preview-title)]
             [:span {:style {:color (settings/get-color :text-muted)
                             :font-size "0.85rem"}}
              (str word-count " " (t :ai-context-words) " · " char-count " " (t :ai-context-chars))]]

            ;; Content
            [:div {:style {:flex 1
                           :overflow "hidden"
                           :padding "0"}}
             (if (str/blank? display-text)
               [:div {:style {:padding "40px"
                              :text-align "center"
                              :color (settings/get-color :text-muted)}}
                (t :ai-context-no-chunk)]
               [:textarea {:value display-text
                           :read-only true
                           :style {:width "100%"
                                   :height "100%"
                                   :min-height "350px"
                                   :background (settings/get-color :background)
                                   :color (settings/get-color :text)
                                   :border "none"
                                   :padding "16px 20px"
                                   :font-size "0.85rem"
                                   :font-family "'JetBrains Mono', 'Fira Code', monospace"
                                   :line-height "1.5"
                                   :resize "none"
                                   :outline "none"}}])]

            ;; Footer
            [:div {:style {:display "flex"
                           :justify-content "flex-end"
                           :gap "8px"
                           :padding "12px 20px"
                           :border-top (str "1px solid " (settings/get-color :border))}}
             [:button {:style {:background "transparent"
                               :color (settings/get-color :text-muted)
                               :border (str "1px solid " (settings/get-color :border))
                               :padding "8px 16px"
                               :border-radius "4px"
                               :cursor "pointer"
                               :font-size "0.85rem"}
                       :on-click close-context-preview!}
              (t :close)]
             [:button {:style {:background (settings/get-color :accent)
                               :color "#ffffff"
                               :border "none"
                               :padding "8px 16px"
                               :border-radius "4px"
                               :cursor "pointer"
                               :font-size "0.85rem"}
                       :on-click (fn []
                                   (.writeText js/navigator.clipboard display-text)
                                   (reset! copied? true)
                                   (js/setTimeout #(reset! copied? false) 2000))}
              (if @copied?
                (t :ai-context-copied)
                (t :ai-context-copy))]]]])))))

(defn welcome-message []
  [:div {:style {:display "flex"
                 :justify-content "center"
                 :padding "20px"
                 :color (settings/get-color :text-muted)
                 :font-size "0.9rem"
                 :text-align "center"}}
   [:div {:style {:max-width "400px"}}
    (t :ai-panel-welcome)]])

(defn messages-area []
  (let [messages-end-ref (r/atom nil)]
    (r/create-class
     {:component-did-update
      (fn [this]
        ;; Scroll to bottom when messages change
        (when-let [el @messages-end-ref]
          (.scrollIntoView el #js {:behavior "smooth"})))

      :reagent-render
      (fn []
        ;; Dereference inside render function for reactivity
        (let [messages (:messages @panel-state)
              loading? (:loading @panel-state)]
          [:div {:style {:flex 1
                         :overflow-y "auto"
                         :padding "12px 16px"
                         :display "flex"
                         :flex-direction "column"}}
           (if (empty? messages)
             [welcome-message]
             [:<>
              (for [[idx msg] (map-indexed vector messages)]
                ^{:key idx}
                [message-bubble msg])
              (when loading?
                [:div {:style {:display "flex"
                               :justify-content "flex-start"
                               :margin-bottom "8px"}}
                 [:div {:style {:background (settings/get-color :editor-bg)
                                :color (settings/get-color :text-muted)
                                :padding "8px 12px"
                                :border-radius "12px"
                                :border (str "1px solid " (settings/get-color :border))
                                :font-size "0.9rem"
                                :display "flex"
                                :align-items "center"
                                :gap "4px"}}
                  [:span (t :ai-thinking)]
                  [:span {:class "ai-loading-dots"} ""]]])])
           [:div {:ref #(reset! messages-end-ref %)}]]))})))

(defn input-area []
  (let [input-ref (r/atom nil)]
    (fn []
      (let [{:keys [input loading]} @panel-state
            has-input? (not (str/blank? input))
            api-configured? (settings/ai-configured?)
            can-send? (and has-input? (not loading) api-configured?)
            can-copy? (and has-input? (not loading))
            btn-style (fn [enabled?]
                        {:background (if enabled?
                                       (settings/get-color :editor-bg)
                                       (settings/get-color :border))
                         :color (if enabled?
                                  (settings/get-color :text)
                                  (settings/get-color :text-muted))
                         :border (str "1px solid " (settings/get-color :border))
                         :padding "8px 12px"
                         :border-radius "4px"
                         :cursor (if enabled? "pointer" "not-allowed")
                         :font-size "0.85rem"
                         :transition "background 0.15s"})]
        [:div {:style {:border-top (str "1px solid " (settings/get-color :border))
                       :padding "12px 16px"
                       :background (settings/get-color :sidebar)}}
         ;; Input row
         [:div {:style {:display "flex"
                        :gap "8px"
                        :align-items "flex-end"}}
          ;; Context dropdown
          [context-dropdown]

          ;; Text input
          [:textarea {:ref #(do (reset! input-ref %)
                               (reset! input-textarea-ref %))
                      :value input
                      :placeholder (t :ai-panel-placeholder)
                      :rows 1
                      :style {:flex 1
                              :background (settings/get-color :editor-bg)
                              :color (settings/get-color :text)
                              :border (str "1px solid " (settings/get-color :border))
                              :border-radius "4px"
                              :padding "8px 12px"
                              :font-size "0.9rem"
                              :font-family "inherit"
                              :resize "none"
                              :min-height "36px"
                              :max-height "80px"
                              :overflow-y "auto"}
                      :on-change #(set-input! (-> % .-target .-value))
                      :on-key-down (fn [e]
                                     (when (and (= (.-key e) "Enter")
                                                (not (.-shiftKey e)))
                                       (.preventDefault e)
                                       (when can-send?
                                         (send-message!))))}]]

         ;; Button row
         [:div {:style {:display "flex"
                        :gap "8px"
                        :margin-top "8px"
                        :justify-content "flex-end"}}
          ;; Copy for chat button (always visible)
          [:button {:style (btn-style can-copy?)
                    :disabled (not can-copy?)
                    :title (t :ai-copy-for-chat)
                    :on-click #(when can-copy? (copy-for-chat!))}
           (t :ai-copy-for-chat)]

          ;; Inject response button (always visible)
          [:button {:style (btn-style true)
                    :title (t :ai-inject-response)
                    :on-click #(show-inject-modal!)}
           (t :ai-inject-response)]

          ;; Send button (only when API is configured)
          (when api-configured?
            [:button {:style {:background (if can-send?
                                            (settings/get-color :accent)
                                            (settings/get-color :border))
                              :color (if can-send? "#ffffff" (settings/get-color :text-muted))
                              :border "none"
                              :padding "8px 16px"
                              :border-radius "4px"
                              :cursor (if can-send? "pointer" "not-allowed")
                              :font-size "0.9rem"
                              :font-weight "500"
                              :transition "background 0.15s"}
                      :disabled (not can-send?)
                      :on-click #(when can-send? (send-message!))}
             (t :ai-panel-send)])]

         ;; Word count indicator
         [:div {:style {:margin-top "6px"
                        :font-size "0.8rem"
                        :color (settings/get-color :text-muted)}}
          [word-count-indicator]]]))))

(defn panel-header []
  (let [{:keys [minimized expanded]} @panel-state]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :justify-content "space-between"
                   :padding "8px 16px"
                   ;; Slightly darker than sidebar using linear-gradient overlay
                   :background (str "linear-gradient(rgba(0,0,0,0.08), rgba(0,0,0,0.08)), " (settings/get-color :sidebar))
                   :border-bottom (when-not minimized
                                    (str "1px solid " (settings/get-color :border)))
                   :cursor (when minimized "pointer")}
           :on-click (when minimized #(restore-panel!))}
     ;; Title
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "8px"
                    :color (settings/get-color :text)
                    :font-weight "500"
                    :font-size "0.9rem"}}
      [:span "🤖"]
      [:span (t :ai-panel-title)]]

     ;; Control buttons
     [:div {:style {:display "flex"
                    :gap "4px"}}
      ;; Minimize button
      [:button {:style {:background "transparent"
                        :border "none"
                        :color (settings/get-color :text-muted)
                        :cursor "pointer"
                        :padding "4px 8px"
                        :font-size "1rem"
                        :line-height "1"
                        :border-radius "4px"}
                :title (t :ai-panel-minimize)
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-minimize!))}
       (if minimized "▢" "─")]

      ;; Expand button
      [:button {:style {:background "transparent"
                        :border "none"
                        :color (settings/get-color :text-muted)
                        :cursor "pointer"
                        :padding "4px 8px"
                        :font-size "1rem"
                        :line-height "1"
                        :border-radius "4px"}
                :title (t :ai-panel-expand)
                :on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-expand!))}
       (if expanded "▽" "△")]

      ;; Close button
      [:button {:style {:background "transparent"
                        :border "none"
                        :color (settings/get-color :text-muted)
                        :cursor "pointer"
                        :padding "4px 8px"
                        :font-size "1.1rem"
                        :line-height "1"
                        :border-radius "4px"}
                :title (t :ai-panel-close)
                :on-click (fn [e]
                            (.stopPropagation e)
                            (hide-panel!))}
       "×"]]]))

(defn inject-response-modal []
  (let [{:keys [visible response-text]} @inject-modal-state
        has-text? (not (str/blank? response-text))]
    (when visible
      [:div {:style {:position "fixed"
                     :top 0 :left 0 :right 0 :bottom 0
                     :background "rgba(0,0,0,0.7)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 1002}
             :on-click #(when (= (.-target %) (.-currentTarget %))
                          (hide-inject-modal!))}
       [:div {:style {:background (settings/get-color :sidebar)
                      :border-radius "8px"
                      :width "600px"
                      :max-width "90vw"
                      :max-height "80vh"
                      :display "flex"
                      :flex-direction "column"
                      :box-shadow "0 10px 40px rgba(0,0,0,0.5)"}}
        ;; Header
        [:div {:style {:display "flex"
                       :justify-content "space-between"
                       :align-items "center"
                       :padding "16px 20px"
                       :border-bottom (str "1px solid " (settings/get-color :border))}}
         [:span {:style {:color (settings/get-color :text)
                         :font-weight "500"}}
          (t :ai-inject-title)]
         [:button {:style {:background "transparent"
                           :border "none"
                           :color (settings/get-color :text-muted)
                           :cursor "pointer"
                           :font-size "1.2rem"
                           :padding "4px"}
                   :on-click hide-inject-modal!}
          "×"]]

        ;; Content
        [:div {:style {:flex 1
                       :padding "16px 20px"}}
         [:textarea {:value response-text
                     :placeholder (t :ai-inject-placeholder)
                     :style {:width "100%"
                             :min-height "250px"
                             :background (settings/get-color :editor-bg)
                             :color (settings/get-color :text)
                             :border (str "1px solid " (settings/get-color :border))
                             :border-radius "4px"
                             :padding "12px"
                             :font-size "0.9rem"
                             :font-family "'JetBrains Mono', 'Fira Code', monospace"
                             :line-height "1.5"
                             :resize "vertical"
                             :outline "none"}
                     :on-change #(set-inject-response-text! (-> % .-target .-value))}]]

        ;; Footer
        [:div {:style {:display "flex"
                       :justify-content "flex-end"
                       :gap "8px"
                       :padding "12px 20px"
                       :border-top (str "1px solid " (settings/get-color :border))}}
         [:button {:style {:background "transparent"
                           :color (settings/get-color :text-muted)
                           :border (str "1px solid " (settings/get-color :border))
                           :padding "8px 16px"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.85rem"}
                   :on-click hide-inject-modal!}
          (t :cancel)]
         [:button {:style {:background (if has-text?
                                         (settings/get-color :accent)
                                         (settings/get-color :border))
                           :color (if has-text? "#ffffff" (settings/get-color :text-muted))
                           :border "none"
                           :padding "8px 16px"
                           :border-radius "4px"
                           :cursor (if has-text? "pointer" "not-allowed")
                           :font-size "0.85rem"
                           :font-weight "500"}
                   :disabled (not has-text?)
                   :on-click #(when has-text? (process-injected-response!))}
          (t :ai-inject-process)]]]])))

(defn ai-panel []
  (let [input-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        ;; Focus input when panel opens
        (js/setTimeout
         (fn []
           (when-let [textarea (.querySelector js/document ".ai-panel textarea")]
             (.focus textarea)))
         100)
        ;; Trigger initial context calculation
        (schedule-context-update!))

      :reagent-render
      (fn []
        (let [{:keys [visible minimized]} @panel-state
              height (get-panel-height)]
          [:<>
           ;; Panel shows when visible AND AI is enabled (not just when configured)
           (when (and visible (settings/ai-enabled?))
             [:div.ai-panel
              {:style {:height (str height "px")
                       :background (or (settings/get-color :ai-panel) (settings/get-color :sidebar))
                       :border-top (str "1px solid " (settings/get-color :border))
                       :box-shadow "0 -4px 12px rgba(0,0,0,0.15)"
                       :display "flex"
                       :flex-direction "column"
                       :transition "height 0.2s ease"
                       :overflow "hidden"}
               :on-key-down (fn [e]
                              (when (= (.-key e) "Escape")
                                (hide-panel!)))}
              [panel-header]
              (when-not minimized
                [:<>
                 [messages-area]
                 [input-area]])])
           ;; Modals (always rendered, visibility controlled internally)
           [context-preview-modal]
           [inject-response-modal]]))})))

;; =============================================================================
;; Toolbar Button
;; =============================================================================

(defn toolbar-button []
  (when (settings/ai-enabled?)
    [:button {:style {:background (if (panel-visible?)
                                    (settings/get-color :accent-muted)
                                    "transparent")
                      :color (if (panel-visible?)
                               (settings/get-color :accent)
                               (settings/get-color :text-muted))
                      :border "none"
                      :padding "6px 10px"
                      :border-radius "4px"
                      :cursor "pointer"
                      :font-size "18px"
                      :line-height "1"
                      :min-width "32px"
                      :height "32px"
                      :display "flex"
                      :align-items "center"
                      :justify-content "center"}
              :title (str (t :ai-panel-title) " (Ctrl+Shift+A)")
              :on-click toggle-panel!}
     "✦"]))

;; =============================================================================
;; Keyboard Shortcut Handler
;; =============================================================================

(defn setup-keyboard-shortcuts! []
  (.addEventListener js/document "keydown"
                     (fn [e]
                       ;; Ctrl+Shift+A (or Cmd+Shift+A on Mac)
                       (when (and (or (.-ctrlKey e) (.-metaKey e))
                                  (.-shiftKey e)
                                  (= (str/lower-case (.-key e)) "a"))
                         (.preventDefault e)
                         (when (settings/ai-enabled?)
                           (toggle-panel!))))))

;; Initialize keyboard shortcuts
(defonce _init-shortcuts (setup-keyboard-shortcuts!))
