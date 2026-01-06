# Tramando Collaborative - Architettura

## Obiettivo

Estendere Tramando con una modalità collaborativa (write-room) mantenendo pienamente funzionante la versione desktop locale con Tauri e la versione webapp con file locali.

## Tre modalità di funzionamento

### 1. Modalità desktop (Tauri)
- App compilata e distribuita come eseguibile
- File .trmd salvato su filesystem locale via Tauri API
- Nessuna autenticazione
- Owner implicito: "local"
- Feature collaborative disponibili ma single-user

### 2. Modalità webapp locale (browser, no login)
- Frontend servito dal server
- Utente accede senza registrazione/login
- File .trmd caricato/salvato su filesystem locale via browser File API
- Comportamento identico alla modalità desktop
- Owner implicito: "local"
- Nessun accesso ai progetti server

### 3. Modalità webapp collaborativa (browser, con login)
- Frontend servito dal server
- Utente si registra/logga
- Progetti salvati su server
- Multi-utente con ownership e ruoli
- Sync real-time via WebSocket

### Passaggio tra modalità (webapp)

L'utente che accede alla webapp vede:
- Opzione "Lavora in locale" → modalità 2, nessun login richiesto
- Opzione "Accedi" → login → modalità 3, accesso ai progetti server

Un utente loggato può comunque aprire/salvare file locali (utile per import/export), ma il lavoro collaborativo avviene solo sui progetti server.

### Implementazione

La scelta della modalità determina quale `IStateStore` viene usato:
- Modalità 1 e 2 → `LocalStore` (I/O locale, Tauri API o browser File API)
- Modalità 3 → `RemoteStore` (sync con server)

Il frontend rileva automaticamente:
- Se gira in Tauri → modalità 1
- Se gira in browser → mostra scelta, poi modalità 2 o 3

## Modello dati

### Chunk (esteso)
```clojure
{:id "cap-3"
 :title "Capitolo 3"
 :content "..."
 :owner "luigi"              ;; username, o "local" in modalità locale
 :previous-owner nil         ;; valorizzato solo se ownership temporanea
 :ownership-expires nil      ;; timestamp scadenza, o nil se permanente
 :discussion [...]}          ;; lista di commenti e proposte risolte
```

### Proposta inline (nel content del chunk, codificata in Base64)
```clojure
{:type :proposal
 :sender "mario"
 :status :pending            ;; :pending | :accepted | :rejected
 :original-text "..."
 :proposed-text "..."
 :created-at "..."}
```

Quando una proposta viene accettata o rifiutata e poi rimossa dal testo, migra nella discussion del chunk.

### Entry discussion
```clojure
;; Commento libero
{:author "mario"
 :timestamp "..."
 :type :comment
 :text "Qui non mi torna il tono"}

;; Proposta risolta (migrata qui dopo rimozione dal testo)
{:author "mario"
 :timestamp "..."
 :type :proposal
 :previous-text "..."
 :proposed-text "..."
 :answer :accepted           ;; :accepted | :rejected
 :reason "..."}              ;; commento opzionale di chi ha deciso
```

## Ownership

- Ogni chunk ha esattamente un owner
- Chi crea un chunk ne diventa automaticamente owner
- L'owner può:
  - Modificare direttamente il contenuto
  - Accettare/rifiutare proposte
  - Cedere ownership (permanente o temporanea)
- Chi non è owner può solo proporre modifiche (annotazioni)
- Un admin di progetto può riassegnare ownership
- Ownership temporanea: quando scade, torna a previous-owner

## Undo in modalità collaborativa

L'undo rispetta i confini dell'ownership e non crea mai conflitti cross-utente.

### Se sei owner del chunk:
- Undo completo, come in modalità locale
- Puoi annullare qualsiasi modifica, incluse quelle derivanti da proposte che hai accettato
- La responsabilità del contenuto è tua

### Se non sei owner del chunk:
- Puoi solo creare proposte
- Undo cancella la tua proposta pending (non ancora valutata dall'owner)
- Se la proposta è già stata accettata o rifiutata, non c'è nulla da annullare — il testo appartiene all'owner

### Principio generale

L'undo non attraversa mai il confine dell'ownership. Ogni utente annulla solo le proprie azioni, e le proprie azioni sono limitate dal ruolo che ha su quel chunk.

Questo elimina la necessità di merge complessi o gestione di conflitti in caso di undo concorrenti.

## Ruoli

### Livello sistema
- **Super-admin**: gestisce utenti, accede a tutti i progetti

### Livello progetto
- **Project-admin**: gestisce collaboratori e ownership nel progetto
- **Collaboratore**: può leggere, commentare, proporre modifiche

## Persistenza (modalità server)

### SQLite (metadati e relazioni)
```sql
users (id, username, password_hash, is_super_admin, created_at)
projects (id, name, filepath, created_at)
permissions (user_id, project_id, role)  -- role: 'admin' | 'collaborator'
```

### Filesystem (contenuto)
```
/data/
  tramando.db
  projects/
    <project-id>.trmd
```

## Architettura frontend

### Astrazione accesso stato

Protocol `IStateStore` con due implementazioni:
```clojure
(defprotocol IStateStore
  (load-project [this project-id])
  (get-state [this])
  (update-state! [this path f])
  (subscribe [this path callback]))
```

**LocalStore**: comportamento attuale, atom locale, I/O via Tauri o browser File API
**RemoteStore**: cache locale (ratom) + sync con server via API/WebSocket

Il frontend usa sempre il protocol, l'implementazione viene scelta a runtime in base alla modalità.

### Compatibilità Tauri e webapp locale

Il codice frontend resta lo stesso. La differenza:
- Build Tauri → usa LocalStore con Tauri API
- Build web senza login → usa LocalStore con browser File API
- Build web con login → usa RemoteStore

## API Backend (modalità server)

### Auth
- `POST /api/register` — registrazione (se abilitata)
- `POST /api/login` — login, ritorna JWT
- `GET /api/me` — utente corrente

### Progetti
- `GET /api/projects` — lista progetti accessibili
- `POST /api/projects` — crea progetto
- `GET /api/projects/:id` — carica progetto (metadati + contenuto)
- `PUT /api/projects/:id` — salva progetto
- `DELETE /api/projects/:id` — elimina progetto

### Collaboratori
- `GET /api/projects/:id/collaborators`
- `POST /api/projects/:id/collaborators` — invita
- `DELETE /api/projects/:id/collaborators/:user-id` — rimuovi

### Ownership
- `PUT /api/projects/:id/chunks/:chunk-id/owner` — cedi/riassegna

### Admin
- `GET /api/admin/users`
- `POST /api/admin/users`
- `DELETE /api/admin/users/:id`

## Sync real-time

WebSocket per notifiche push:
- Un utente modifica → server notifica altri client connessi
- Client riceve update → aggiorna cache locale → Reagent rirenderizza

## Note implementative

- Il formato .trmd resta retrocompatibile: file senza campi collaborativi si aprono normalmente
- In modalità locale, owner è sempre "local", le proposte funzionano ma sei sempre tu a decidere
- La Discussion è una tab dell'editor, come "Modifica", "Lettura", etc.
- Le proposte pending sono annotazioni inline nel content (Base64)
- Purge discussion: manuale, a discrezione dell'owner

## Piano di lavoro

### Fase 0: Preparazione ✅
- Branch `feature/collaborative`
- Questo file ARCHITECTURE.md

### Fase 1: Estensione formato .trmd (solo locale) ✅
- Campi ownership e discussion nei chunk
- Compatibilità retroattiva

### Fase 2: UI Discussion (solo locale) ✅
- Tab Discussion nell'editor
- Commenti leggibili e scrivibili

### Fase 3: Proposte inline (solo locale) ✅
- Annotazioni :proposal nel markdown
- UI per creare/accettare/rifiutare
- Migrazione in Discussion quando risolte

### Fase 4: Protocol IStateStore (refactoring) ✅
- Separare accesso stato da implementazione
- LocalStore che wrappa comportamento attuale

**Implementato:**
- `tramando.store.protocol` - Definizione protocol `IStateStore` con operazioni:
  - Gestione progetto: `load-project`, `save-project`, `save-project-as`
  - Accesso stato: `get-state`, `get-chunks`, `get-chunk`, `get-selected-id`, etc.
  - Mutazione: `select-chunk!`, `update-chunk!`, `add-chunk!`, `delete-chunk!`
  - History: `can-undo?`, `can-redo?`, `undo!`, `redo!`
  - Subscription: `subscribe` per reattività
  - Ownership: `get-current-user`, `is-owner?`, `can-edit?`
- `tramando.store.local` - Implementazione `LocalStore` che delega a `model.cljs`
- `tramando.store` - Facade con funzioni di convenienza
- Inizializzazione in `core.cljs`

**Note:** L'approccio è stato di wrappare il codice esistente senza modificarlo,
permettendo una migrazione graduale. I componenti possono continuare a usare
`tramando.model` oppure passare a `tramando.store`.

### Fase 5: Backend base + DB ✅
- Setup progetto Clojure in `server/`
- SQLite: tabelle users, projects, permissions
- API auth (register, login, me) con JWT
- API progetti (CRUD + collaboratori)
- Dockerfile

**Implementato:**
- `server/deps.edn` - Dipendenze: Ring, Reitit, SQLite, Buddy (auth)
- `server/src/tramando/server/`:
  - `config.clj` - Configurazione da environment
  - `db.clj` - Connessione SQLite, tabelle, query
  - `auth.clj` - Password hashing (bcrypt), JWT, middleware auth
  - `storage.clj` - Salvataggio file .trmd su filesystem
  - `routes.clj` - API REST complete
  - `core.clj` - Entry point server Jetty
- `server/Dockerfile` - Build multi-stage con Temurin 21

**Avvio:** `cd server && clojure -M:run`

### Fase 6: UI Amministrazione ✅
- Super-admin: gestione utenti
- Project-admin: inviti, ruoli, ownership chunk
- Creazione progetti

**Implementato:**
- `tramando.api` - Client HTTP per tutte le API server
- `tramando.auth` - Stato autenticazione con persistenza token
- `tramando.server-ui` - Componenti UI:
  - `login-form` - Login/registrazione
  - `projects-list` - Lista progetti con creazione
  - `collaborators-panel` - Gestione collaboratori
  - `mode-selector` - Scelta modalità locale/server
  - `user-menu` - Menu utente con logout

### Fase 7: RemoteStore + sync ✅
- Implementazione RemoteStore
- Caricamento/salvataggio da server
- Switch locale/remoto

**Implementato:**
- `tramando.store.remote` - Implementazione `RemoteStore` del protocol `IStateStore`:
  - `load-project` - Carica progetto da server, inizializza stato locale
  - `save-project` - Salva con optimistic locking (content-hash)
  - Delega a `model.cljs` per stato locale (chunks, metadata, history)
  - Sync automatico con debounce 3s
  - Gestione conflitti 409 con dialog sovrascrivi/ricarica
- `cleanup!` - Pulizia stato quando si esce da modalità remota
- `get-project-name`, `get-sync-status` - Accessori per UI
- `views.cljs` semplificato - usa `RemoteStore` invece di codice ad-hoc

**Architettura:**
```
┌─────────────────┐     ┌─────────────────┐
│   views.cljs    │────▶│  IStateStore    │
│  (componenti)   │     │   (protocol)    │
└─────────────────┘     └────────┬────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
              ┌─────▼─────┐           ┌───────▼───────┐
              │LocalStore │           │ RemoteStore   │
              │(file I/O) │           │ (API + sync)  │
              └───────────┘           └───────┬───────┘
                                              │
                                      ┌───────▼───────┐
                                      │  model.cljs   │
                                      │ (stato locale)│
                                      └───────────────┘
```

### Fase 8: Multi-utente ✅ (ownership enforced)
- Ownership enforced lato server

**Implementato:**
- Funzioni granulari per controllo permessi in `db.clj`:
  - `get-user-project-role` - Ritorna ruolo `:owner`, `:admin`, `:collaborator`, o `nil`
  - `user-can-edit-content?` - Tutti i ruoli possono editare contenuto
  - `user-can-edit-metadata?` - Solo owner e admin possono rinominare
  - `user-is-project-owner?` - Controllo owner
- `update-project-handler` rafforzato:
  - Cambio nome: solo owner o admin
  - Cambio contenuto: qualsiasi ruolo con accesso
- Nuovo endpoint `PUT /api/projects/:id/collaborators/:user-id` per cambiare ruolo

**Modello permessi progetto:**

| Azione | Owner | Admin | Collaborator |
|--------|-------|-------|--------------|
| Leggere progetto | ✓ | ✓ | ✓ |
| Modificare contenuto | ✓ | ✓ | ✓ |
| Rinominare progetto | ✓ | ✓ | ✗ |
| Eliminare progetto | ✓ | ✗ | ✗ |
| Gestire collaboratori | ✓ | ✓ | ✗ |
| Cambiare ruoli | ✓ | ✓ | ✗ |

**Nota:** I controlli sono enforced lato server - la UI può nascondere i bottoni, ma anche se un utente malevolo facesse chiamate API dirette, il server rifiuterebbe con 403.

**Controllo concorrenza (Optimistic Locking):**

Il server protegge da sovrascritture accidentali quando due utenti modificano lo stesso progetto:

1. `GET /api/projects/:id` ritorna `content-hash` (SHA-256 del contenuto)
2. `PUT /api/projects/:id` accetta `base-hash` (l'hash ricevuto al caricamento)
3. Se `base-hash` non corrisponde al contenuto attuale → 409 Conflict

Flusso:
```
Client A: GET project → content-hash: "abc123"
Client B: GET project → content-hash: "abc123"
Client A: PUT content + base-hash="abc123" → OK, nuovo hash: "def456"
Client B: PUT content + base-hash="abc123" → 409 Conflict!
```

In caso di conflitto, l'utente può:
- **Sovrascrivi**: forza il salvataggio (perde le modifiche dell'altro)
- **Ricarica**: scarica la versione server (perde le proprie modifiche)

Questo approccio (optimistic locking) funziona bene per editing collaborativo asincrono, dove i conflitti sono rari. Per real-time editing simultaneo servirebbe CRDT o OT.

**Testing:**
Il controllo concorrenza richiede test di integrazione che simulino due client:
```clojure
;; Pseudocodice test
(let [hash1 (get-project-hash 1)
      hash2 hash1]  ;; Secondo client legge stesso hash
  (save-project! 1 "content-A" hash1)  ;; → OK
  (save-project! 1 "content-B" hash2)) ;; → 409 Conflict
```
Da implementare con test automatici lato server (clojure.test) che verifichino:
- Salvataggio con hash corretto → 200
- Salvataggio con hash sbagliato → 409
- Salvataggio senza hash (force) → 200

### Fase 8b: UI Amministrazione completa ✅

**Implementato:**

1. **Super Admin - Gestione Utenti**
   - API backend: `POST/DELETE/PUT /api/admin/users`
   - Funzioni DB: `list-all-users`, `delete-user!`, `update-user-super-admin!`
   - UI: Pannello modale accessibile dal menu utente (solo per super admin)
   - Funzionalità: creare utenti, eliminare utenti, promuovere/degradare admin

2. **Gestione Collaboratori Progetto**
   - Bottone "👥 Collaboratori" nell'header del progetto
   - Pannello modale per aggiungere/rimuovere collaboratori
   - Selezione ruolo: admin o collaborator

3. **Trasferimento Ownership Chunk**
   - Bottone "Trasferisci" nella tab Discussion (solo per owner)
   - Form per inserire username destinatario
   - Lista collaboratori cliccabile per selezione rapida
   - Aggiorna i campi `:owner` e `:previous-owner` nel chunk

**Flusso trasferimento ownership:**
```
Owner corrente → clicca "Trasferisci" → inserisce username → conferma
                                         ↓
                 Chunk aggiornato con :owner = nuovo-utente
                                     :previous-owner = vecchio-owner
                                         ↓
                 Auto-sync al server (RemoteStore)
```

### Fase 9: Polish e deploy
- Edge case, conflitti
- Notifiche UI
- Documentazione
- Deploy

## Considerazioni future

### Supporto mobile

La webapp deve essere responsive e funzionare su tablet e smartphone. Casi d'uso prioritari:

- Lettura del testo
- Visualizzazione e gestione proposte (accetta/rifiuta)
- Partecipazione alle discussioni
- Scrittura di draft veloci
- Consultazione schede (personaggi, luoghi, timeline)

L'architettura (frontend unico + backend via API REST/WebSocket) si presta a un eventuale client mobile nativo in futuro. L'ordine di priorità è:

1. Webapp collaborativa funzionante
2. Webapp responsive (usabile da mobile browser)
3. Eventuale app nativa (React Native, Flutter, o altro) che usa le stesse API

Il passo 2 va considerato fin da subito nello sviluppo della UI. Il passo 3 è un progetto separato, da valutare in base alla domanda reale.

### Note

Alcune funzionalità potrebbero avere un'esperienza ridotta su schermi piccoli (es. mappe radiali), ma le funzioni core di lettura, commento e gestione proposte devono funzionare bene anche su smartphone.
