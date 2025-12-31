(ns tramando.ai.templates
  "AI assistant templates for context menu actions"
  (:require [tramando.settings :as settings]
            [tramando.model :as model]
            [clojure.string :as str]))

;; =============================================================================
;; Prompt Suffixes for Structured Output
;; =============================================================================

(def annotation-prompt-suffix
  "Suffix added to annotation prompts to request structured alternatives"
  {:it "\n\n---\nFORMATO RISPOSTA: Fornisci esattamente 3 alternative numerate. Ogni alternativa deve essere il testo completo riscritto, non una spiegazione. Formato:\n\n1. [testo alternativa 1]\n\n2. [testo alternativa 2]\n\n3. [testo alternativa 3]"
   :en "\n\n---\nRESPONSE FORMAT: Provide exactly 3 numbered alternatives. Each alternative must be the complete rewritten text, not an explanation. Format:\n\n1. [alternative text 1]\n\n2. [alternative text 2]\n\n3. [alternative text 3]"})

(def create-character-prompt
  "Structured prompt for character creation"
  {:it "Basandoti sul testo fornito, crea una scheda personaggio. Rispondi SOLO con la scheda nel formato seguente, senza spiegazioni:

NOME: [nome del personaggio]

DESCRIZIONE:
[descrizione generale, 2-3 frasi]

### Aspetto fisico
[dettagli aspetto]

### Personalità
[tratti caratteriali]

### Background
[storia passata, se deducibile]

### Note
[altri dettagli rilevanti]"

   :en "Based on the provided text, create a character sheet. Reply ONLY with the sheet in the following format, no explanations:

NAME: [character name]

DESCRIPTION:
[general description, 2-3 sentences]

### Physical appearance
[appearance details]

### Personality
[character traits]

### Background
[past history, if deducible]

### Notes
[other relevant details]"})

(def create-place-prompt
  "Structured prompt for place creation"
  {:it "Basandoti sul testo fornito, crea una scheda luogo. Rispondi SOLO con la scheda nel formato seguente, senza spiegazioni:

NOME: [nome del luogo]

DESCRIZIONE:
[descrizione generale, 2-3 frasi]

### Aspetto fisico
[architettura, dimensioni, caratteristiche]

### Atmosfera
[sensazioni, mood, impressioni]

### Dettagli sensoriali
[odori, suoni, luci, temperature]

### Storia
[storia del luogo, se deducibile]

### Significato narrativo
[ruolo nella storia]"

   :en "Based on the provided text, create a place sheet. Reply ONLY with the sheet in the following format, no explanations:

NAME: [place name]

DESCRIPTION:
[general description, 2-3 sentences]

### Physical appearance
[architecture, dimensions, characteristics]

### Atmosphere
[feelings, mood, impressions]

### Sensory details
[smells, sounds, lights, temperatures]

### History
[place history, if deducible]

### Narrative significance
[role in the story]"})

;; =============================================================================
;; Template Definitions
;; =============================================================================

(def ai-templates
  {:expand
   {:id :expand
    :output-type :annotation
    :label {:it "Espandi/sviluppa" :en "Expand/develop"}
    :context-preset :scena
    :prompt {:it "Espandi e sviluppa questo testo, mantenendo il tono e lo stile. Aggiungi dettagli sensoriali e approfondisci le emozioni dei personaggi."
             :en "Expand and develop this text, maintaining tone and style. Add sensory details and deepen the characters' emotions."}}

   :rephrase
   {:id :rephrase
    :output-type :annotation
    :label {:it "Riformula" :en "Rephrase"}
    :context-preset :minimo
    :prompt {:it "Riformula questo testo mantenendo lo stesso significato ma con parole diverse."
             :en "Rephrase this text keeping the same meaning but with different words."}}

   :tone-dark
   {:id :tone-dark
    :output-type :annotation
    :label {:it "Cupo" :en "Dark"}
    :context-preset :scena
    :prompt {:it "Riscrivi questo testo con un tono più cupo e atmosferico. Accentua le ombre, le tensioni sottese, il senso di inquietudine."
             :en "Rewrite this text with a darker, more atmospheric tone. Emphasize shadows, underlying tensions, sense of unease."}}

   :tone-light
   {:id :tone-light
    :output-type :annotation
    :label {:it "Leggero" :en "Light"}
    :context-preset :scena
    :prompt {:it "Riscrivi questo testo con un tono più leggero e scorrevole. Alleggerisci le frasi, aggiungi respiro."
             :en "Rewrite this text with a lighter, more flowing tone. Lighten sentences, add breathing room."}}

   :tone-formal
   {:id :tone-formal
    :output-type :annotation
    :label {:it "Formale" :en "Formal"}
    :context-preset :minimo
    :prompt {:it "Riscrivi questo testo con un registro più formale e letterario."
             :en "Rewrite this text with a more formal, literary register."}}

   :tone-casual
   {:id :tone-casual
    :output-type :annotation
    :label {:it "Colloquiale" :en "Casual"}
    :context-preset :minimo
    :prompt {:it "Riscrivi questo testo con un tono più colloquiale e naturale, come si parlerebbe nella vita reale."
             :en "Rewrite this text with a more casual, natural tone, as people would speak in real life."}}

   :tone-poetic
   {:id :tone-poetic
    :output-type :annotation
    :label {:it "Poetico" :en "Poetic"}
    :context-preset :scena
    :prompt {:it "Riscrivi questo testo con un tono più poetico e evocativo. Usa metafore, ritmo, immagini suggestive."
             :en "Rewrite this text with a more poetic, evocative tone. Use metaphors, rhythm, suggestive imagery."}}

   :create-character
   {:id :create-character
    :output-type :create-aspect
    :aspect-type :personaggi
    :label {:it "Crea scheda personaggio" :en "Create character sheet"}
    :context-preset :scena
    :structured-prompt true}

   :create-place
   {:id :create-place
    :output-type :create-aspect
    :aspect-type :luoghi
    :label {:it "Crea scheda luogo" :en "Create place sheet"}
    :context-preset :scena
    :structured-prompt true}

   :suggest-conflict
   {:id :suggest-conflict
    :output-type :chat
    :label {:it "Suggerisci conflitto" :en "Suggest conflict"}
    :context-preset :scena
    :prompt {:it "Analizza questa scena e i personaggi coinvolti. Suggerisci 3 possibili conflitti o tensioni che potrebbero emergere, considerando le personalità, gli obiettivi e il contesto narrativo."
             :en "Analyze this scene and the characters involved. Suggest 3 possible conflicts or tensions that could emerge, considering personalities, goals and narrative context."}}

   :analyze-consistency
   {:id :analyze-consistency
    :output-type :chat
    :label {:it "Analizza coerenza" :en "Analyze consistency"}
    :context-preset :completo
    :prompt {:it "Analizza questo testo rispetto al contesto fornito. Cerca eventuali incoerenze: timeline, caratterizzazione dei personaggi, dettagli già stabiliti. Segnala problemi e suggerisci correzioni."
             :en "Analyze this text against the provided context. Look for inconsistencies: timeline, character portrayal, established details. Flag problems and suggest corrections."}}

   :update-aspect
   {:id :update-aspect
    :output-type :update-aspect
    :label {:it "Aggiorna aspetto" :en "Update aspect"}
    :context-preset :scena
    :dynamic-prompt true}

   :ask
   {:id :ask
    :output-type :chat
    :label {:it "Chiedi..." :en "Ask..."}
    :context-preset :scena
    :prompt {:it "" :en ""}}})

;; =============================================================================
;; Template Helpers
;; =============================================================================

(defn get-template
  "Get a template by ID"
  [template-id]
  (get ai-templates template-id))

(defn get-template-label
  "Get the localized label for a template"
  [template-id]
  (let [lang (settings/get-language)
        template (get-template template-id)]
    (get-in template [:label lang] (get-in template [:label :en]))))

(defn get-template-prompt
  "Get the localized prompt for a template, including suffix for annotation types"
  [template-id]
  (let [lang (settings/get-language)
        template (get-template template-id)]
    (cond
      ;; Structured prompts for aspect creation
      (:structured-prompt template)
      (case template-id
        :create-character (get create-character-prompt lang)
        :create-place (get create-place-prompt lang)
        (get-in template [:prompt lang]))

      ;; Annotation prompts get suffix for structured alternatives
      (= (:output-type template) :annotation)
      (str (get-in template [:prompt lang] (get-in template [:prompt :en]))
           (get annotation-prompt-suffix lang))

      ;; Other prompts returned as-is
      :else
      (get-in template [:prompt lang] (get-in template [:prompt :en])))))

(defn build-extract-info-prompt
  "Build the dynamic prompt for extracting new info about an aspect"
  [aspect-name aspect-content]
  (let [lang (settings/get-language)]
    (if (= lang :it)
      (str "Dal testo selezionato, estrai SOLO le nuove informazioni su \"" aspect-name "\" che non sono già presenti nella scheda.\n\n"
           "SCHEDA ATTUALE:\n" aspect-content "\n\n"
           "ISTRUZIONI:\n"
           "- Elenca solo informazioni NUOVE (non già presenti nella scheda)\n"
           "- Usa un formato a punti elenco\n"
           "- Se non trovi informazioni nuove, rispondi esattamente: NESSUNA NUOVA INFORMAZIONE\n"
           "- Non riscrivere la scheda, fornisci solo le nuove info da aggiungere")
      (str "From the selected text, extract ONLY new information about \"" aspect-name "\" that is not already in the sheet.\n\n"
           "CURRENT SHEET:\n" aspect-content "\n\n"
           "INSTRUCTIONS:\n"
           "- List only NEW information (not already in the sheet)\n"
           "- Use bullet point format\n"
           "- If no new information found, reply exactly: NO NEW INFORMATION\n"
           "- Don't rewrite the sheet, just provide new info to append"))))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(defn parse-alternatives
  "Extract numbered alternatives from AI response.
   Returns a vector of alternative texts."
  [response-text]
  (let [;; Try to match numbered alternatives (1. ... 2. ... 3. ...)
        ;; This regex captures content between numbers
        lines (str/split response-text #"\n")
        ;; Find lines starting with numbers
        numbered-sections (reduce
                           (fn [acc line]
                             (if-let [match (re-find #"^(\d+)\.\s*(.*)$" line)]
                               (let [num (js/parseInt (second match))
                                     text (nth match 2)]
                                 (assoc acc num text))
                               ;; Continuation of previous numbered section
                               (if-let [last-num (last (sort (keys acc)))]
                                 (update acc last-num #(str % "\n" (str/trim line)))
                                 acc)))
                           {}
                           lines)
        alternatives (when (seq numbered-sections)
                       (->> (sort-by first numbered-sections)
                            (map second)
                            (map str/trim)
                            (filter #(not (str/blank? %)))
                            vec))]
    (if (seq alternatives)
      alternatives
      ;; Fallback: split by double newlines if no numbered format
      (let [parts (->> (str/split response-text #"\n\n+")
                       (map str/trim)
                       (filter #(not (str/blank? %)))
                       vec)]
        (if (> (count parts) 1)
          parts
          ;; Last fallback: return entire response as single alternative
          [response-text])))))

(defn parse-aspect-sheet
  "Extract name and content from a structured aspect sheet response"
  [response-text aspect-type]
  (let [;; Try to find NAME/NOME line
        name-pattern (if (= (settings/get-language) :it)
                       #"(?i)NOME:\s*(.+?)(?:\n|$)"
                       #"(?i)NAME:\s*(.+?)(?:\n|$)")
        name-match (re-find name-pattern response-text)
        name (if name-match
               (str/trim (second name-match))
               ;; Default name based on type
               (case aspect-type
                 :personaggi "Nuovo personaggio"
                 :luoghi "Nuovo luogo"
                 "Nuovo aspetto"))
        ;; Remove the NAME line from content
        content (-> response-text
                    (str/replace name-pattern "")
                    str/trim)]
    {:name name
     :content content}))

;; =============================================================================
;; Linked Aspects Detection
;; =============================================================================

(defn- extract-aspect-refs-from-text
  "Extract [@aspect-id] references from text content"
  [text]
  (when text
    (->> (re-seq #"\[@([^\]]+)\]" text)
         (map second)
         (distinct))))

(defn get-linked-aspects
  "Get aspects linked to a chunk via :aspects field or [@id] references in content"
  [chunk]
  (let [;; Get aspects from :aspects field (set of aspect IDs)
        aspect-ids (or (:aspects chunk) #{})
        ;; Get aspects from [@id] references in content
        text-aspect-ids (extract-aspect-refs-from-text (:content chunk))
        ;; Combine and get unique IDs
        all-ids (distinct (concat aspect-ids text-aspect-ids))
        ;; Get the actual aspect chunks
        all-chunks (model/get-chunks)]
    (->> all-ids
         (map (fn [id]
                (first (filter #(= (:id %) id) all-chunks))))
         (filter some?)
         (filter #(model/is-aspect-chunk? %)))))

(defn has-linked-aspects?
  "Check if a chunk has any linked aspects"
  [chunk]
  (seq (get-linked-aspects chunk)))
