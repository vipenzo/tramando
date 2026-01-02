(ns tramando.ai.api
  "API integration for AI providers (Anthropic, OpenAI, Ollama)"
  (:require [tramando.i18n :refer [t]]
            [tramando.settings :as settings]))

;; =============================================================================
;; System Prompts
;; =============================================================================

(def system-prompt-it
  "Sei un assistente per scrittori che usa Tramando, uno strumento per gestire storie complesse.

Il tuo ruolo è aiutare lo scrittore a:
- Espandere o riformulare scene e passaggi
- Sviluppare schede personaggi, luoghi e temi
- Suggerire sviluppi narrativi coerenti con la storia
- Mantenere coerenza con gli elementi già stabiliti

Riceverai il contesto della storia (chunk corrente, personaggi, luoghi, sequenze, ecc.).
Rispondi sempre in italiano a meno che non ti venga chiesto diversamente.
Sii creativo ma rispetta il tono e lo stile del materiale fornito.
Quando proponi alternative, presentale in modo chiaro e numerato.")

(def system-prompt-en
  "You are an assistant for writers using Tramando, a tool for managing complex stories.

Your role is to help the writer:
- Expand or rephrase scenes and passages
- Develop character sheets, places and themes
- Suggest narrative developments consistent with the story
- Maintain consistency with established elements

You will receive story context (current chunk, characters, places, sequences, etc.).
Always respond in English unless asked otherwise.
Be creative but respect the tone and style of the provided material.
When proposing alternatives, present them clearly and numbered.")

(defn get-system-prompt
  "Get the system prompt for the current language"
  []
  (if (= (settings/get-language) :it)
    system-prompt-it
    system-prompt-en))

;; =============================================================================
;; Message Formatting
;; =============================================================================

(defn- format-messages-anthropic
  "Format messages for Anthropic API (excludes system, which is separate)"
  [messages]
  (->> messages
       (filter #(not= (:role %) :system))
       (mapv (fn [{:keys [role content]}]
               {:role (name role)
                :content content}))))

(defn- format-messages-openai
  "Format messages for OpenAI API (includes system as first message)"
  [messages]
  (mapv (fn [{:keys [role content]}]
          {:role (name role)
           :content content})
        messages))

(defn- format-messages-ollama
  "Format messages for Ollama API"
  [messages]
  (mapv (fn [{:keys [role content]}]
          {:role (name role)
           :content content})
        messages))

(defn- get-system-from-messages
  "Extract system message content from messages list"
  [messages]
  (some #(when (= (:role %) :system) (:content %)) messages))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defn- parse-error
  "Parse error response and return user-friendly message"
  [error-msg status-code]
  (cond
    (= status-code 401)
    (t :ai-error-invalid-key)

    (= status-code 429)
    (t :ai-error-rate-limit)

    (or (= status-code 408)
        (and error-msg (re-find #"(?i)timeout" error-msg)))
    (t :ai-error-timeout)

    (and error-msg (re-find #"(?i)connect|ECONNREFUSED|network" error-msg))
    (t :ai-error-connection)

    :else
    (str (t :ai-error-generic) ": " (or error-msg "Unknown error"))))

;; =============================================================================
;; Anthropic API
;; =============================================================================

(defn- call-anthropic
  "Call Anthropic Messages API"
  [api-key model messages on-success on-error]
  (let [system-content (get-system-from-messages messages)
        formatted-messages (format-messages-anthropic messages)]
    (-> (js/fetch "https://api.anthropic.com/v1/messages"
          (clj->js {:method "POST"
                    :headers {"Content-Type" "application/json"
                              "x-api-key" api-key
                              "anthropic-version" "2023-06-01"
                              "anthropic-dangerous-direct-browser-access" "true"}
                    :body (js/JSON.stringify
                            (clj->js (cond-> {:model model
                                              :max_tokens 4096
                                              :messages formatted-messages}
                                       system-content (assoc :system system-content))))}))
        (.then (fn [response]
                 (let [status (.-status response)]
                   (-> (.json response)
                       (.then (fn [data]
                                (if (.-error data)
                                  (on-error (parse-error (-> data .-error .-message) status))
                                  (on-success (-> data .-content first .-text)))))))))
        (.catch (fn [err]
                  (on-error (parse-error (.-message err) nil)))))))

;; =============================================================================
;; OpenAI API
;; =============================================================================

(defn- call-openai
  "Call OpenAI Chat Completions API"
  [api-key model messages on-success on-error]
  (let [formatted-messages (format-messages-openai messages)]
    (-> (js/fetch "https://api.openai.com/v1/chat/completions"
          (clj->js {:method "POST"
                    :headers {"Content-Type" "application/json"
                              "Authorization" (str "Bearer " api-key)}
                    :body (js/JSON.stringify
                            (clj->js {:model model
                                      :max_tokens 4096
                                      :messages formatted-messages}))}))
        (.then (fn [^js response]
                 (let [status (.-status response)]
                   (-> (.json response)
                       (.then (fn [^js data]
                                (if (.-error data)
                                  (on-error (parse-error (-> data .-error .-message) status))
                                  (on-success (-> data .-choices first .-message .-content)))))))))
        (.catch (fn [^js err]
                  (on-error (parse-error (.-message err) nil)))))))

;; =============================================================================
;; Groq API (OpenAI-compatible)
;; =============================================================================

(defn- call-groq
  "Call Groq API (OpenAI-compatible endpoint)"
  [api-key model messages on-success on-error]
  (let [formatted-messages (format-messages-openai messages)]
    (-> (js/fetch "https://api.groq.com/openai/v1/chat/completions"
          (clj->js {:method "POST"
                    :headers {"Content-Type" "application/json"
                              "Authorization" (str "Bearer " api-key)}
                    :body (js/JSON.stringify
                            (clj->js {:model model
                                      :max_tokens 4096
                                      :messages formatted-messages}))}))
        (.then (fn [^js response]
                 (let [status (.-status response)]
                   (-> (.json response)
                       (.then (fn [^js data]
                                (if (.-error data)
                                  (on-error (parse-error (-> data .-error .-message) status))
                                  (on-success (-> data .-choices first .-message .-content)))))))))
        (.catch (fn [^js err]
                  (on-error (parse-error (.-message err) nil)))))))

;; =============================================================================
;; Ollama API
;; =============================================================================

(defn- call-ollama
  "Call Ollama Chat API"
  [base-url model messages on-success on-error]
  (let [formatted-messages (format-messages-ollama messages)
        url (str (or base-url "http://localhost:11434") "/api/chat")]
    (-> (js/fetch url
          (clj->js {:method "POST"
                    :headers {"Content-Type" "application/json"}
                    :body (js/JSON.stringify
                            (clj->js {:model model
                                      :messages formatted-messages
                                      :stream false}))}))
        (.then (fn [response]
                 (let [status (.-status response)]
                   (-> (.json response)
                       (.then (fn [data]
                                (if (.-error data)
                                  (on-error (parse-error (.-error data) status))
                                  (on-success (-> data .-message .-content)))))))))
        (.catch (fn [err]
                  (on-error (parse-error (.-message err) nil)))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn send-message
  "Send a message to the configured AI provider.

   config - map with :provider, :api-key, :model, :ollama-url
   messages - vector of {:role :user/:assistant/:system :content \"...\"}
   on-success - callback (fn [response-text])
   on-error - callback (fn [error-message])"
  [{:keys [provider api-key model ollama-url]} messages on-success on-error]
  (case provider
    :anthropic (call-anthropic api-key model messages on-success on-error)
    :openai (call-openai api-key model messages on-success on-error)
    :groq (call-groq api-key model messages on-success on-error)
    :ollama (call-ollama ollama-url model messages on-success on-error)
    (on-error (str (t :ai-error-generic) ": Unknown provider"))))

(defn build-full-prompt
  "Build the full prompt combining user message with context"
  [user-message context]
  (if (or (nil? context) (empty? context))
    user-message
    (str "## CONTESTO DELLA STORIA\n\n"
         context
         "\n\n---\n\n"
         "## RICHIESTA DELL'AUTORE\n\n"
         user-message)))

(defn get-ai-config
  "Get the current AI configuration from settings"
  []
  (let [ai-settings (:ai @settings/settings)]
    {:provider (:provider ai-settings)
     :api-key (case (:provider ai-settings)
                :anthropic (:anthropic-key ai-settings)
                :openai (:openai-key ai-settings)
                :groq (:groq-key ai-settings)
                nil)
     :model (case (:provider ai-settings)
              :anthropic (:model ai-settings)
              :openai (:model ai-settings)
              :groq (:groq-model ai-settings)
              :ollama (:ollama-model ai-settings)
              nil)
     :ollama-url (:ollama-url ai-settings)}))
