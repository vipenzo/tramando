(ns tramando.help
  "Contextual help tooltips for the UI")

;; =============================================================================
;; Help Texts
;; =============================================================================

(def texts
  {;; Sidebar sections
   :struttura "La narrativa vera e propria: capitoli, scene, il testo della tua storia. Organizzala come preferisci."
   :personaggi "I personaggi della storia. Usa [@id] nel testo delle scene per collegarli."
   :luoghi "I luoghi dove si svolge la storia. Usa [@id] per collegarli alle scene."
   :temi "Le idee e i motivi ricorrenti. Usa [@id] per tracciare dove appaiono."
   :sequenze "Catene di causa-effetto. I figli sono i passi ordinati della sequenza."
   :timeline "Eventi in ordine cronologico. Metti data e ora nel titolo dei figli (es. '2024-03-15 08:00')."

   ;; Editor fields
   :id "Identificatore unico. Usalo con [@id] nel testo per creare collegamenti."
   :summary "Il titolo del chunk. Usa [:ORD] per numerazione automatica (es. 'Capitolo [:ORD]')."
   :add-aspect "Collega questo chunk a personaggi, luoghi, temi, sequenze o timeline."
   :parent "Il chunk genitore nella gerarchia. Cambialo per spostare questo elemento."

   ;; Editor tabs
   :tab-modifica "Scrivi e modifica il contenuto del chunk."
   :tab-usato-da "Le scene e i chunk che fanno riferimento a questo aspetto con [@id]."
   :tab-figli "I chunk contenuti in questo elemento (es. le scene di un capitolo)."
   :tab-lettura "Visualizza il testo formattato, senza markup visibile."

   ;; Header/Toolbar
   :settings "Tema, colori, e preferenze dell'applicazione."
   :metadata "Titolo, autore, lingua e altri metadati del progetto."
   :carica "Apri un file .trmd esistente."
   :salva "Salva il progetto corrente."
   :esporta "Esporta il progetto in formato MD o PDF."

   ;; Annotations
   :annotazioni "Note, cose da fare e problemi da correggere. Non appaiono nell'export PDF."

   ;; Radial map
   :mappa-radiale "Mappa della storia: struttura al centro, aspetti intorno. Le linee mostrano i collegamenti. Clicca per selezionare, scrolla per zoomare."})

;; =============================================================================
;; Tooltip Component
;; =============================================================================

(defn tooltip
  "A help tooltip component.
   Usage: [tooltip :key] or [tooltip \"Custom text\"]
   Can also wrap content: [tooltip :key [:span \"Label\"]]"
  ([key-or-text]
   (tooltip key-or-text nil))
  ([key-or-text content]
   (let [text (if (keyword? key-or-text)
               (get texts key-or-text (str "Missing: " key-or-text))
               key-or-text)]
     [:span.with-help
      content
      [:span.help-icon "?"]
      [:span.help-tooltip text]])))

(defn help-icon
  "Just the help icon with tooltip, no wrapper for custom layouts.
   Usage: [help-icon :key] or [help-icon :key {:below? true :left? true :right? true}]"
  ([key-or-text]
   (help-icon key-or-text {}))
  ([key-or-text {:keys [below? left? right?] :or {below? false left? false right? false}}]
   (let [text (if (keyword? key-or-text)
               (get texts key-or-text (str "Missing: " key-or-text))
               key-or-text)
         classes (str "help-tooltip"
                      (when below? " below")
                      (when left? " left")
                      (when right? " right"))]
     [:span.with-help {:style {:display "inline-flex"}}
      [:span.help-icon "?"]
      [:span {:class classes} text]])))
