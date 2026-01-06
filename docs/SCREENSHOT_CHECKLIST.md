# Checklist Screenshot per Manuale v2.0.0

## Istruzioni generali
- Risoluzione: 1280x800 o superiore
- Versione: **Tauri** per screenshot 1-8, **Webapp** per screenshot 9-15 (collaborazione)
- Lingua: catturare in IT, poi ripetere in EN
- Formato: PNG
- Cartelle: `docs/images/it/` e `docs/images/en/`

---

## Screenshot CORE (da rifare - UI cambiata)

### 1a. `splash_tauri.png` (Tauri)
**Cosa mostrare:** Schermata di benvenuto versione desktop
- Opzioni file locali (Continua, Nuovo, Apri)
- Aspetto nativo con barra del titolo macOS/Windows
- Dimensioni finestra: intera

### 1b. `splash_webapp.png` (Webapp)
**Cosa mostrare:** Schermata di benvenuto a due colonne
- Colonna sinistra: opzioni file locali (Continua, Nuovo, Apri)
- Colonna destra: form login (se non loggato) o lista progetti
- Per lo screenshot: mostrare versione NON loggata con form login visibile
- Dimensioni finestra: intera

### 2. `main.png`
**Cosa mostrare:** Interfaccia principale con progetto aperto
- Sidebar espansa con struttura e aspetti
- Almeno un container aspetti con widget soglia visibile (es. Personaggi con −0+)
- Editor con contenuto e tag aspetti
- Tab "Modifica" attivo
- Header con tutti i pulsanti visibili

### 3. `filter.png`
**Cosa mostrare:** Filtro globale in azione
- Campo filtro con testo di ricerca
- Risultati filtrati nella sidebar
- Match evidenziati nell'editor (opzionale)

### 4. `map.png`
**Cosa mostrare:** Mappa radiale
- Vista completa della mappa
- Alcuni collegamenti visibili tra struttura e aspetti
- Pannello info in basso a sinistra con dati hover/selezione

### 5. `settings.png`
**Cosa mostrare:** Pannello impostazioni
- Slider autosalvataggio
- Sezione colori visibile
- Almeno alcune opzioni di personalizzazione

---

## Screenshot NUOVI (funzionalità v2.0)

### 6. `priority_sidebar.png`
**Cosa mostrare:** Sistema priorità aspetti nella sidebar
- Container "Personaggi" espanso
- Widget soglia con valore > 0 (es. 3)
- Alcuni aspetti visibili, altri filtrati
- Tooltip visibile (opzionale) che mostra "5/12 Priorità ≥3"

### 7. `priority_editor.png`
**Cosa mostrare:** Campo priorità nell'editor aspetto
- Aspetto selezionato (es. un personaggio)
- Campo "Priorità" con valore numerico
- Hint "0=bassa, 10=alta" visibile

### 8. `tag_filtered.png`
**Cosa mostrare:** Tag filtrato (sbiadito)
- Editor con un chunk selezionato
- Almeno un tag normale e uno filtrato (sbiadito, tratteggiato)
- La differenza visiva deve essere chiara

---

## Screenshot COLLABORAZIONE (nuovo capitolo)

### 9. `collab_login.png`
**Cosa mostrare:** Form di login
- Splash screen con form login in evidenza
- Campi username e password
- Pulsanti Login e Registrati

### 10. `collab_projects.png`
**Cosa mostrare:** Lista progetti server
- Lista con almeno 2-3 progetti
- Per ogni progetto: nome, ruolo (owner/admin/collaborator)
- Pulsante "Nuovo progetto"
- Pulsante logout/menu utente visibile

### 11. `collab_header.png`
**Cosa mostrare:** Header in modalità collaborativa
- Nome progetto
- Badge/indicatore sync
- Pulsante collaboratori (👥)
- Menu utente

### 12. `collab_collaborators.png`
**Cosa mostrare:** Pannello gestione collaboratori
- Lista collaboratori con ruoli
- Campo per aggiungere nuovo collaboratore
- Dropdown selezione ruolo

### 13. `collab_discussion.png`
**Cosa mostrare:** Tab Discussion
- Tab "Discussion" attivo nell'editor
- Almeno 2-3 messaggi/commenti
- Campo input per nuovo messaggio
- Owner del chunk visibile

### 14. `collab_proposal.png`
**Cosa mostrare:** Proposta inline
- Testo con annotazione PROPOSAL evidenziata
- Colore distintivo della proposta
- Opzionale: menu contestuale con Accetta/Rifiuta

### 15. `collab_ownership.png`
**Cosa mostrare:** Trasferimento ownership
- Dialog/form per trasferire ownership
- Lista collaboratori selezionabili
- Pulsante conferma

---

## Screenshot AI (già esistenti, verificare se aggiornare)

Verificare se questi screenshot sono ancora accurati:
- `ai_settings_*.png` - Impostazioni AI
- `ai_panel_*.png` - Pannello AI
- `ai_annotation_*.png` - Annotazioni AI

---

## Ordine di cattura consigliato

### Sessione 1: Modalità locale (Tauri)
1. splash_tauri.png
2. main.png
3. filter.png
4. priority_sidebar.png
5. priority_editor.png
6. tag_filtered.png
7. map.png
8. settings.png

### Sessione 2: Modalità collaborativa (webapp con server)
1. splash_webapp.png (con form login visibile)
2. collab_login.png
3. collab_projects.png
3. collab_header.png
4. collab_collaborators.png
5. collab_discussion.png
6. collab_proposal.png
7. collab_ownership.png

---

## Note per la cattura

### Per gli splash:
- **Tauri**: splash semplice con solo opzioni file locali
- **Webapp**: splash a due colonne, catturare con form login visibile (non loggato)

### Per le priorità:
- Creare alcuni aspetti con priorità diverse (es. 0, 3, 5, 7)
- Impostare soglia a 3 per mostrare il filtro in azione

### Per la collaborazione:
- Avviare il server: `cd server && clojure -M:run`
- Creare almeno 2 utenti di test
- Creare un progetto con collaboratori

### Per i tag filtrati:
- Collegare un aspetto con priorità bassa a un chunk
- Alzare la soglia in modo che l'aspetto venga filtrato
- Il tag nell'editor apparirà sbiadito
