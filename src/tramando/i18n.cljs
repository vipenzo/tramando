(ns tramando.i18n
  "Internationalization support for Tramando"
  (:require [reagent.core :as r]
            [clojure.string :as str]))

;; =============================================================================
;; Language State
;; =============================================================================

(defonce current-lang (r/atom :it))

;; =============================================================================
;; Translations Dictionary
;; =============================================================================

(def translations
  {:it
   {;; General
    :app-name "Tramando"
    :tagline "Tessi la tua storia"

    ;; Sidebar sections
    :structure "Struttura"
    :aspects "Aspetti"
    :annotations "Annotazioni"

    ;; Aspect categories
    :personaggi "Personaggi"
    :luoghi "Luoghi"
    :temi "Temi"
    :sequenze "Sequenze"
    :timeline "Timeline"

    ;; Actions
    :new-chunk "+ Nuovo Chunk"
    :new-aspect "+ Nuovo aspetto"
    :add-child "+ Figlio di \"{0}\""
    :add-aspect "+ Aspetto"
    :save "Salva"
    :load "Carica"
    :export "Esporta"
    :export-md "Esporta MD"
    :export-pdf "Esporta PDF"
    :delete "Elimina"
    :delete-chunk "Elimina chunk"
    :cancel "Annulla"
    :confirm "Confermi?"
    :close "Chiudi"
    :reset-theme "Reset tema"

    ;; Editor
    :edit "Modifica"
    :reading "Lettura"
    :used-by "Usato da"
    :children "Figli"
    :show-markup "Mostra markup"
    :chunk-title-placeholder "Titolo del chunk..."
    :no-title "(senza titolo)"
    :parent "Parent:"
    :root "(root)"
    :click-to-edit-id "Clicca per modificare l'ID"
    :remove "Rimuovi"

    ;; Status
    :modified "Modificato"
    :saving "Salvataggio..."
    :saved "Salvato"

    ;; Settings modal
    :settings "Impostazioni"
    :default-theme "Tema predefinito"
    :autosave-delay "Ritardo autosave: {0} secondi"
    :custom-colors "Colori personalizzati"
    :interface "Interfaccia"
    :categories "Categorie"
    :import-export "Import/Export"
    :import-settings "Importa settings.edn"
    :export-settings "Esporta settings.edn"
    :help "Aiuto"
    :review-tutorial "Rivedi tutorial"
    :language "Lingua"

    ;; Color labels
    :color-background "Sfondo"
    :color-sidebar "Sidebar"
    :color-editor "Editor"
    :color-border "Bordi"
    :color-text "Testo"
    :color-text-secondary "Testo secondario"
    :color-accent "Accento"
    :color-structure "Struttura"

    ;; Metadata modal
    :project-info "Informazioni progetto"
    :title "Titolo"
    :title-required "Titolo *"
    :project-title-placeholder "Titolo del progetto"
    :author "Autore"
    :author-placeholder "Nome autore"
    :year "Anno"
    :isbn "ISBN"
    :publisher "Editore"
    :publisher-placeholder "Nome editore"
    :custom-fields "Campi personalizzati"
    :key "Chiave"
    :value "Valore"

    ;; Splash screen
    :continue-work "Continua il lavoro in corso"
    :new-project "Nuovo progetto"
    :open-file "Apri file..."

    ;; Tutorial
    :tutorial-welcome-title "Benvenuto in Tramando"
    :tutorial-welcome-text "Tramando è uno strumento per scrittori che vogliono gestire storie complesse: personaggi, luoghi, temi, linee temporali.\n\nIl nome viene da \"trama\" — perché scrivere è tessere fili narrativi — e da \"tramando\", il gerundio: un'azione continua, un lavoro in divenire."
    :tutorial-chunks-title "Tutto è un Chunk"
    :tutorial-chunks-text "In Tramando, ogni elemento è un \"chunk\": un blocco di testo con un titolo e un'identità.\n\nUn capitolo è un chunk. Una scena è un chunk. Un personaggio è un chunk. Anche una singola nota può essere un chunk.\n\nI chunk possono contenere altri chunk, creando una struttura ad albero flessibile."
    :tutorial-story-title "La tua storia"
    :tutorial-story-text "La sezione STRUTTURA contiene la narrativa vera e propria: capitoli, scene, passaggi.\n\nPuoi organizzarla come preferisci: alcuni usano Parte → Capitolo → Scena, altri preferiscono strutture più piatte. Tramando si adatta al tuo modo di lavorare."
    :tutorial-aspects-title "Gli Aspetti"
    :tutorial-aspects-text "Gli ASPETTI sono gli elementi che attraversano la storia:\n\n• Personaggi — Chi abita il tuo mondo\n• Luoghi — Dove accadono le cose\n• Temi — Le idee ricorrenti\n• Sequenze — Catene di causa-effetto\n• Timeline — Eventi in ordine cronologico\n\nOgni aspetto può avere sotto-elementi (es. un personaggio può avere \"Aspetto fisico\", \"Psicologia\", ecc.)"
    :tutorial-weaving-title "Tessere la trama"
    :tutorial-weaving-text "Ecco la magia: puoi collegare le scene agli aspetti.\n\nScrivi [@elena] nel testo di una scena per indicare che Elena appare lì. Oppure [@roma] per un luogo, [@vendetta] per un tema.\n\nTramando terrà traccia di tutti i collegamenti: potrai vedere in quali scene appare ogni personaggio, quali luoghi sono più usati, come si sviluppano i temi."
    :tutorial-notes-title "Note per te stesso"
    :tutorial-notes-text "Mentre scrivi, puoi lasciare annotazioni nel testo:\n\n• TODO — Cose da fare\n• NOTE — Appunti e riflessioni\n• FIX — Problemi da correggere\n\nSeleziona del testo, click destro, e scegli il tipo. Le annotazioni appaiono nella sidebar ma non nell'export finale."
    :tutorial-search-title "Cerca e sostituisci"
    :tutorial-search-text "Tramando offre potenti strumenti di ricerca:\n\n• Filtro globale — Il campo in cima alla sidebar filtra tutti i chunk che contengono il testo cercato.\n\n• Ricerca locale — Premi Ctrl+F nell'editor per cercare nel chunk corrente, con navigazione tra i risultati.\n\n• Sostituisci — Premi Ctrl+H per sostituire testo, singolarmente o tutti insieme.\n\nEntrambi supportano la distinzione maiuscole/minuscole e le espressioni regolari."
    :tutorial-map-title "La mappa della storia"
    :tutorial-map-text "La vista Radiale mostra la tua storia come una mappa: la struttura narrativa al centro, gli aspetti intorno, e linee colorate che mostrano i collegamenti.\n\nÈ un modo per \"vedere\" la trama nel suo insieme e scoprire connessioni che non avevi notato."
    :tutorial-export-title "Dal caos al libro"
    :tutorial-export-text "Quando sei pronto, esporta in PDF. Tramando formatterà la tua storia con stili professionali: titoli dei capitoli, separatori tra scene, impaginazione curata.\n\nLe annotazioni, gli aspetti e i metadati restano nel file .trmd — l'export contiene solo la narrativa pulita."
    :tutorial-start-title "Buona scrittura!"
    :tutorial-start-text "Sei pronto per iniziare.\n\nCrea un nuovo progetto, oppure carica un file esistente. Se hai bisogno di aiuto, troverai il tutorial nel menu Impostazioni.\n\nBuona scrittura!"
    :skip "Salta"
    :back "Indietro"
    :next "Avanti"
    :start-writing "Inizia a scrivere"

    ;; View toggle
    :map "Mappa"
    :editor "Editor"

    ;; Empty states
    :no-chunk "Nessun chunk."
    :no-children "Nessun figlio"
    :no-aspects "Nessuno"
    :no-annotations "Nessuna annotazione"
    :none-fem "Nessuna"
    :all-aspects-added "Tutti gli aspetti già aggiunti"
    :no-chunk-uses-aspect "Nessun chunk usa questo aspetto"
    :select-chunk "Seleziona un chunk dall'outline o creane uno nuovo"

    ;; Refs view
    :used-in-n-chunks "Usato in {0} chunk"
    :n-children "{0} figli"
    :appears-in-n-chunks "Appare in {0} chunk:"

    ;; Outline
    :move-up "Sposta su"
    :move-down "Sposta giù"

    ;; Help texts (tooltips)
    :help-struttura "La narrativa vera e propria: capitoli, scene, il testo della tua storia. Organizzala come preferisci."
    :help-personaggi "I personaggi della storia. Usa [@id] nel testo delle scene per collegarli."
    :help-luoghi "I luoghi dove si svolge la storia. Usa [@id] per collegarli alle scene."
    :help-temi "Le idee e i motivi ricorrenti. Usa [@id] per tracciare dove appaiono."
    :help-sequenze "Catene di causa-effetto. I figli sono i passi ordinati della sequenza."
    :help-timeline "Eventi in ordine cronologico. Metti data e ora nel titolo dei figli (es. '2024-03-15 08:00')."
    :help-id "Identificatore unico. Usalo con [@id] nel testo per creare collegamenti."
    :help-summary "Il titolo del chunk. Usa [:ORD] per numerazione automatica (es. 'Capitolo [:ORD]')."
    :help-add-aspect "Collega questo chunk a personaggi, luoghi, temi, sequenze o timeline."
    :help-parent "Il chunk genitore nella gerarchia. Cambialo per spostare questo elemento."
    :help-tab-modifica "Scrivi e modifica il contenuto del chunk."
    :help-tab-usato-da "Le scene e i chunk che fanno riferimento a questo aspetto con [@id]."
    :help-tab-figli "I chunk contenuti in questo elemento (es. le scene di un capitolo)."
    :help-tab-lettura "Visualizza il testo formattato, senza markup visibile."
    :help-settings "Tema, colori, e preferenze dell'applicazione."
    :help-metadata "Titolo, autore, lingua e altri metadati del progetto."
    :help-carica "Apri un file .trmd esistente."
    :help-salva "Salva il progetto corrente."
    :help-esporta "Esporta il progetto in formato MD o PDF."
    :help-annotazioni "Note, cose da fare e problemi da correggere. Non appaiono nell'export PDF."
    :help-mappa-radiale "Mappa della storia: struttura al centro, aspetti intorno. Le linee mostrano i collegamenti. Clicca per selezionare, scrolla per zoomare."
    :help-filter "Filtra chunk per testo. Cerca nei titoli e nel contenuto. Usa [Aa] per distinguere maiuscole/minuscole, [.*] per espressioni regolari."
    :help-editor-search "Cerca nel chunk corrente. Usa ‹ › per navigare tra i risultati. Ctrl+F per aprire, Escape per chiudere."
    :help-replace "Sostituisci il testo trovato. 'Sostituisci' cambia solo il match corrente, 'Sostituisci tutti' cambia tutte le occorrenze. Ctrl+H per aprire."
    :help-language "Cambia la lingua dell'interfaccia. Il contenuto dei tuoi progetti non viene modificato."

    ;; Errors
    :error-parsing "Errore nel parsing"

    ;; Filter
    :filter-placeholder "Filtra..."
    :n-results "{0} risultati"
    :one-result "1 risultato"
    :no-results-filter "Nessun risultato"
    :case-sensitive "Maiuscole/minuscole"
    :regex "Espressione regolare"
    :in-content "(contenuto)"
    :in-annotation "(annotazione)"

    ;; Editor search
    :search-placeholder "Cerca..."
    :search-no-results "Nessun risultato"
    :search-match-count "{0}/{1}"
    :search-invalid-regex "Regex non valida"

    ;; Replace
    :replace-placeholder "Sostituisci con..."
    :replace-button "Sostituisci"
    :replace-all-button "Sostituisci tutti"
    :replaced-n-occurrences "Sostituite {0} occorrenze"
    :replaced-one-occurrence "Sostituita 1 occorrenza"}

   :en
   {;; General
    :app-name "Tramando"
    :tagline "Weave your story"

    ;; Sidebar sections
    :structure "Structure"
    :aspects "Aspects"
    :annotations "Annotations"

    ;; Aspect categories
    :personaggi "Characters"
    :luoghi "Places"
    :temi "Themes"
    :sequenze "Sequences"
    :timeline "Timeline"

    ;; Actions
    :new-chunk "+ New Chunk"
    :new-aspect "+ New aspect"
    :add-child "+ Child of \"{0}\""
    :add-aspect "+ Aspect"
    :save "Save"
    :load "Load"
    :export "Export"
    :export-md "Export MD"
    :export-pdf "Export PDF"
    :delete "Delete"
    :delete-chunk "Delete chunk"
    :cancel "Cancel"
    :confirm "Confirm?"
    :close "Close"
    :reset-theme "Reset theme"

    ;; Editor
    :edit "Edit"
    :reading "Reading"
    :used-by "Used by"
    :children "Children"
    :show-markup "Show markup"
    :chunk-title-placeholder "Chunk title..."
    :no-title "(no title)"
    :parent "Parent:"
    :root "(root)"
    :click-to-edit-id "Click to edit ID"
    :remove "Remove"

    ;; Status
    :modified "Modified"
    :saving "Saving..."
    :saved "Saved"

    ;; Settings modal
    :settings "Settings"
    :default-theme "Default theme"
    :autosave-delay "Autosave delay: {0} seconds"
    :custom-colors "Custom colors"
    :interface "Interface"
    :categories "Categories"
    :import-export "Import/Export"
    :import-settings "Import settings.edn"
    :export-settings "Export settings.edn"
    :help "Help"
    :review-tutorial "Review tutorial"
    :language "Language"

    ;; Color labels
    :color-background "Background"
    :color-sidebar "Sidebar"
    :color-editor "Editor"
    :color-border "Borders"
    :color-text "Text"
    :color-text-secondary "Secondary text"
    :color-accent "Accent"
    :color-structure "Structure"

    ;; Metadata modal
    :project-info "Project info"
    :title "Title"
    :title-required "Title *"
    :project-title-placeholder "Project title"
    :author "Author"
    :author-placeholder "Author name"
    :year "Year"
    :isbn "ISBN"
    :publisher "Publisher"
    :publisher-placeholder "Publisher name"
    :custom-fields "Custom fields"
    :key "Key"
    :value "Value"

    ;; Splash screen
    :continue-work "Continue previous work"
    :new-project "New project"
    :open-file "Open file..."

    ;; Tutorial
    :tutorial-welcome-title "Welcome to Tramando"
    :tutorial-welcome-text "Tramando is a tool for writers who want to manage complex stories: characters, places, themes, timelines.\n\nThe name comes from the Italian \"trama\" (plot) — because writing is weaving narrative threads — and \"tramando\", the gerund: a continuous action, a work in progress."
    :tutorial-chunks-title "Everything is a Chunk"
    :tutorial-chunks-text "In Tramando, every element is a \"chunk\": a block of text with a title and an identity.\n\nA chapter is a chunk. A scene is a chunk. A character is a chunk. Even a single note can be a chunk.\n\nChunks can contain other chunks, creating a flexible tree structure."
    :tutorial-story-title "Your story"
    :tutorial-story-text "The STRUCTURE section contains the actual narrative: chapters, scenes, passages.\n\nYou can organize it as you prefer: some use Part → Chapter → Scene, others prefer flatter structures. Tramando adapts to your way of working."
    :tutorial-aspects-title "Aspects"
    :tutorial-aspects-text "ASPECTS are elements that cross through the story:\n\n• Characters — Who lives in your world\n• Places — Where things happen\n• Themes — Recurring ideas\n• Sequences — Chains of cause and effect\n• Timeline — Events in chronological order\n\nEach aspect can have sub-elements (e.g., a character can have \"Physical appearance\", \"Psychology\", etc.)"
    :tutorial-weaving-title "Weaving the plot"
    :tutorial-weaving-text "Here's the magic: you can link scenes to aspects.\n\nWrite [@elena] in a scene's text to indicate Elena appears there. Or [@rome] for a place, [@revenge] for a theme.\n\nTramando will track all connections: you'll see which scenes each character appears in, which places are most used, how themes develop."
    :tutorial-notes-title "Notes to yourself"
    :tutorial-notes-text "While writing, you can leave annotations in the text:\n\n• TODO — Things to do\n• NOTE — Notes and reflections\n• FIX — Problems to fix\n\nSelect text, right-click, and choose the type. Annotations appear in the sidebar but not in the final export."
    :tutorial-search-title "Search and replace"
    :tutorial-search-text "Tramando offers powerful search tools:\n\n• Global filter — The field at the top of the sidebar filters all chunks containing the searched text.\n\n• Local search — Press Ctrl+F in the editor to search the current chunk, with navigation between results.\n\n• Replace — Press Ctrl+H to replace text, one at a time or all at once.\n\nBoth support case sensitivity and regular expressions."
    :tutorial-map-title "The story map"
    :tutorial-map-text "The Radial view shows your story as a map: the narrative structure in the center, aspects around it, and colored lines showing connections.\n\nIt's a way to \"see\" the plot as a whole and discover connections you hadn't noticed."
    :tutorial-export-title "From chaos to book"
    :tutorial-export-text "When you're ready, export to PDF. Tramando will format your story with professional styles: chapter titles, scene separators, careful layout.\n\nAnnotations, aspects, and metadata stay in the .trmd file — the export contains only the clean narrative."
    :tutorial-start-title "Happy writing!"
    :tutorial-start-text "You're ready to start.\n\nCreate a new project, or load an existing file. If you need help, you'll find the tutorial in the Settings menu.\n\nHappy writing!"
    :skip "Skip"
    :back "Back"
    :next "Next"
    :start-writing "Start writing"

    ;; View toggle
    :map "Map"
    :editor "Editor"

    ;; Empty states
    :no-chunk "No chunks."
    :no-children "No children"
    :no-aspects "None"
    :no-annotations "No annotations"
    :none-fem "None"
    :all-aspects-added "All aspects already added"
    :no-chunk-uses-aspect "No chunk uses this aspect"
    :select-chunk "Select a chunk from the outline or create a new one"

    ;; Refs view
    :used-in-n-chunks "Used in {0} chunks"
    :n-children "{0} children"
    :appears-in-n-chunks "Appears in {0} chunks:"

    ;; Outline
    :move-up "Move up"
    :move-down "Move down"

    ;; Help texts (tooltips)
    :help-struttura "The actual narrative: chapters, scenes, the text of your story. Organize it as you prefer."
    :help-personaggi "Story characters. Use [@id] in scene text to link them."
    :help-luoghi "Places where the story takes place. Use [@id] to link them to scenes."
    :help-temi "Recurring ideas and motifs. Use [@id] to track where they appear."
    :help-sequenze "Chains of cause and effect. Children are the ordered steps of the sequence."
    :help-timeline "Events in chronological order. Put date and time in children's title (e.g., '2024-03-15 08:00')."
    :help-id "Unique identifier. Use it with [@id] in text to create links."
    :help-summary "The chunk's title. Use [:ORD] for automatic numbering (e.g., 'Chapter [:ORD]')."
    :help-add-aspect "Link this chunk to characters, places, themes, sequences, or timeline."
    :help-parent "The parent chunk in the hierarchy. Change it to move this element."
    :help-tab-modifica "Write and edit the chunk's content."
    :help-tab-usato-da "Scenes and chunks that reference this aspect with [@id]."
    :help-tab-figli "Chunks contained in this element (e.g., scenes of a chapter)."
    :help-tab-lettura "View formatted text, without visible markup."
    :help-settings "Theme, colors, and application preferences."
    :help-metadata "Title, author, language, and other project metadata."
    :help-carica "Open an existing .trmd file."
    :help-salva "Save the current project."
    :help-esporta "Export the project to MD or PDF format."
    :help-annotazioni "Notes, todos, and issues to fix. They don't appear in PDF export."
    :help-mappa-radiale "Story map: structure in center, aspects around. Lines show connections. Click to select, scroll to zoom."
    :help-filter "Filter chunks by text. Searches in titles and content. Use [Aa] for case sensitive, [.*] for regular expressions."
    :help-editor-search "Search in current chunk. Use ‹ › to navigate results. Ctrl+F to open, Escape to close."
    :help-replace "Replace found text. 'Replace' changes only current match, 'Replace all' changes all occurrences. Ctrl+H to open."
    :help-language "Change interface language. Your project content is not affected."

    ;; Errors
    :error-parsing "Parsing error"

    ;; Filter
    :filter-placeholder "Filter..."
    :n-results "{0} results"
    :one-result "1 result"
    :no-results-filter "No results"
    :case-sensitive "Case sensitive"
    :regex "Regular expression"
    :in-content "(content)"
    :in-annotation "(annotation)"

    ;; Editor search
    :search-placeholder "Search..."
    :search-no-results "No results"
    :search-match-count "{0}/{1}"
    :search-invalid-regex "Invalid regex"

    ;; Replace
    :replace-placeholder "Replace with..."
    :replace-button "Replace"
    :replace-all-button "Replace all"
    :replaced-n-occurrences "Replaced {0} occurrences"
    :replaced-one-occurrence "Replaced 1 occurrence"}})

;; =============================================================================
;; Translation Functions
;; =============================================================================

(defn t
  "Translate a key to the current language.
   Supports interpolation with {0}, {1}, etc.
   Usage: (t :key) or (t :key \"arg1\" \"arg2\")"
  [key & args]
  (let [lang @current-lang
        text (or (get-in translations [lang key])
                 (get-in translations [:it key])  ;; Fallback to Italian
                 (str "?" (name key) "?"))]       ;; Missing key indicator
    (if (empty? args)
      text
      ;; Replace {0}, {1}, etc. with provided args
      (reduce-kv
       (fn [s idx arg]
         (str/replace s (str "{" idx "}") (str arg)))
       text
       (vec args)))))

(defn set-language!
  "Set the current language"
  [lang]
  (when (contains? translations lang)
    (reset! current-lang lang)))

(defn get-language
  "Get the current language keyword"
  []
  @current-lang)

(def available-languages
  "Map of language codes to display names"
  {:it "Italiano"
   :en "English"})
