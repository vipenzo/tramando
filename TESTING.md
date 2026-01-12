# Tramando - Checklist Test Manuali

Questo documento contiene la checklist dei test manuali da eseguire prima di ogni release.

**Legenda piattaforme:**
- **T** = Tauri (app desktop)
- **W** = Webapp (browser + server)
- **S** = Server-only (funzionalità collaborative)

**Formato ID:** `CATEGORIA-NUMERO` (es. `EDIT-001`)

---

## 1. BASIC - Funzionalità Base

| ID | Test | T | W | S |
|----|------|---|---|---|
| BASIC-001 | Avvio app senza errori | x | x | - |
| BASIC-002 | Splash screen visibile | x | x | - |
| BASIC-003 | Tema chiaro/scuro funziona | x | x | - |
| BASIC-004 | Cambio lingua (IT/EN) | x | x | - |
| BASIC-005 | Settings modal si apre/chiude | x | x | - |
| BASIC-006 | Tutorial si avvia e naviga | x | x | - |
| BASIC-007 | Keyboard shortcuts visibili in help | x | x | - |

---

## 2. AUTH - Autenticazione (solo Webapp)

| ID | Test | T | W | S |
|----|------|---|---|---|
| AUTH-001 | Registrazione primo utente (diventa admin) | - | x | x |
| AUTH-002 | Registrazione utente successivo (pending) | - | x | x |
| AUTH-003 | Login con credenziali valide | - | x | x |
| AUTH-004 | Login con credenziali errate mostra errore | - | x | x |
| AUTH-005 | Logout funziona | - | x | x |
| AUTH-006 | Utente pending non può accedere | - | x | x |
| AUTH-007 | Admin approva utente pending | - | x | x |
| AUTH-008 | Admin sospende utente | - | x | x |
| AUTH-009 | Single-login: nuovo login invalida sessione precedente | - | x | x |
| AUTH-010 | Sessione invalidata mostra alert e torna a splash | - | x | x |
| AUTH-011 | Rate limiting registrazione (max 5/ora per IP) | - | x | x |
| AUTH-012 | Cambio display name | - | x | x |
| AUTH-013 | Cambio password | - | x | x |

---

## 3. PROJECT - Gestione Progetti

| ID | Test | T | W | S |
|----|------|---|---|---|
| PROJ-001 | Nuovo progetto da splash | x | x | - |
| PROJ-002 | Apri progetto esistente (.trmd) | x | - | - |
| PROJ-003 | Salva progetto (Cmd+S) | x | - | - |
| PROJ-004 | Salva con nome (Cmd+Shift+S) | x | - | - |
| PROJ-005 | Dirty indicator (*) quando modificato | x | - | - |
| PROJ-006 | Conferma uscita con modifiche non salvate | x | - | - |
| PROJ-007 | Crea nuovo progetto server | - | x | x |
| PROJ-008 | Apri progetto server dalla lista | - | x | x |
| PROJ-009 | Elimina progetto server (solo owner) | - | x | x |
| PROJ-010 | Autosave funziona (3 secondi) | - | x | x |
| PROJ-011 | Sync indicator mostra stato | - | x | x |
| PROJ-012 | Metadata modal: modifica titolo | x | x | - |
| PROJ-013 | Metadata modal: modifica autore | x | x | - |
| PROJ-014 | Metadata modal: aggiungi campo custom | x | x | - |
| PROJ-015 | Click su logo torna a splash/lista progetti | x | x | - |

---

## 4. EDIT - Editing Testo

| ID | Test | T | W | S |
|----|------|---|---|---|
| EDIT-001 | Digitazione testo nell'editor | x | x | - |
| EDIT-002 | Undo locale (Cmd+Z) nel chunk corrente | x | x | - |
| EDIT-003 | Redo locale (Cmd+Shift+Z) nel chunk corrente | x | x | - |
| EDIT-004 | Undo server cross-chunk (dopo autosave) | - | x | x |
| EDIT-005 | Redo server dopo undo server | - | x | x |
| EDIT-006 | Toast mostra chunk modificato dopo undo | - | x | x |
| EDIT-007 | Nuova modifica cancella redo stack server | - | x | x |
| EDIT-008 | Copia/incolla testo | x | x | - |
| EDIT-009 | Cerca nel documento (Cmd+F) | x | x | - |
| EDIT-010 | Sostituisci nel documento (Cmd+H) | x | x | - |
| EDIT-011 | Markdown rendering in preview | x | x | - |
| EDIT-012 | Selezione testo con mouse | x | x | - |
| EDIT-013 | Selezione testo con shift+frecce | x | x | - |
| EDIT-014 | Word wrap funziona | x | x | - |
| EDIT-015 | Font size da settings | x | x | - |
| EDIT-016 | Cursore visibile e lampeggiante | x | x | - |

---

## 5. STRUCT - Struttura e Navigazione

| ID | Test | T | W | S |
|----|------|---|---|---|
| STRUCT-001 | Outline mostra gerarchia chunk | x | x | - |
| STRUCT-002 | Click su chunk nell'outline lo seleziona | x | x | - |
| STRUCT-003 | Espandi/comprimi nodi outline | x | x | - |
| STRUCT-004 | Aggiungi chunk figlio | x | x | - |
| STRUCT-005 | Aggiungi chunk fratello | x | x | - |
| STRUCT-006 | Elimina chunk (con conferma) | x | x | - |
| STRUCT-007 | Elimina chunk con figli (conferma speciale) | x | x | - |
| STRUCT-008 | Rinomina chunk (summary) | x | x | - |
| STRUCT-009 | Drag & drop chunk nell'outline | x | x | - |
| STRUCT-010 | Navigazione con frecce su/giu | x | x | - |
| STRUCT-011 | Alt+frecce per muovere chunk | x | x | - |
| STRUCT-012 | Tab/Shift+Tab per indentare/deindentare | x | x | - |
| STRUCT-013 | Separatore outline ridimensionabile | x | x | - |
| STRUCT-014 | Chunk corrente evidenziato nell'outline | x | x | - |

---

## 6. ASPECT - Aspetti

| ID | Test | T | W | S |
|----|------|---|---|---|
| ASPECT-001 | Container aspetti visibili nell'outline | x | x | - |
| ASPECT-002 | Aggiungi nuovo aspetto (personaggio/luogo/etc) | x | x | - |
| ASPECT-003 | Assegna aspetto a chunk | x | x | - |
| ASPECT-004 | Rimuovi aspetto da chunk | x | x | - |
| ASPECT-005 | Filtro per aspetto nell'outline | x | x | - |
| ASPECT-006 | Badge aspetti visibili su chunk | x | x | - |
| ASPECT-007 | Click su badge aspetto lo evidenzia | x | x | - |
| ASPECT-008 | Autocomplete aspetti quando si digita @ | x | x | - |
| ASPECT-009 | Elimina aspetto (rimuove da tutti i chunk) | x | x | - |
| ASPECT-010 | Rinomina aspetto | x | x | - |

---

## 7. ANNOT - Annotazioni e Commenti

| ID | Test | T | W | S |
|----|------|---|---|---|
| ANNOT-001 | Aggiungi commento inline (seleziona + Cmd+M) | x | x | - |
| ANNOT-002 | Commento visibile come highlight | x | x | - |
| ANNOT-003 | Click su highlight mostra popover | x | x | - |
| ANNOT-004 | Modifica commento esistente | x | x | - |
| ANNOT-005 | Elimina commento | x | x | - |
| ANNOT-006 | Pannello annotazioni mostra lista | x | x | - |
| ANNOT-007 | Click su annotazione nel pannello naviga al testo | x | x | - |
| ANNOT-008 | Risolvi/chiudi annotazione | x | x | - |
| ANNOT-009 | Username/display name su commenti server | - | x | x |
| ANNOT-010 | Collaborator può commentare chunk altrui | - | x | x |

---

## 8. PROP - Proposte (Collaborative)

| ID | Test | T | W | S |
|----|------|---|---|---|
| PROP-001 | Crea proposta su testo selezionato | - | x | x |
| PROP-002 | Proposta visibile come highlight diverso | - | x | x |
| PROP-003 | Click su proposta mostra popover con diff | - | x | x |
| PROP-004 | Owner accetta proposta (testo sostituito) | - | x | x |
| PROP-005 | Owner rifiuta proposta | - | x | x |
| PROP-006 | Proposta con caratteri speciali funziona | - | x | x |
| PROP-007 | Proposta su whitespace funziona | - | x | x |
| PROP-008 | Pannello proposte mostra lista | - | x | x |
| PROP-009 | Collaborator non può modificare testo direttamente | - | x | x |
| PROP-010 | Multiple proposte sullo stesso chunk | - | x | x |

---

## 9. DISCUSS - Discussioni (Collaborative)

| ID | Test | T | W | S |
|----|------|---|---|---|
| DISCUSS-001 | Apri pannello discussione chunk | - | x | x |
| DISCUSS-002 | Aggiungi messaggio discussione | - | x | x |
| DISCUSS-003 | Messaggi mostrano display name | - | x | x |
| DISCUSS-004 | Timestamp messaggi corretto | - | x | x |
| DISCUSS-005 | Scroll automatico a nuovo messaggio | - | x | x |
| DISCUSS-006 | Discussione persiste dopo reload | - | x | x |
| DISCUSS-007 | Caratteri speciali nei messaggi | - | x | x |
| DISCUSS-008 | Indicatore discussioni attive nell'outline | - | x | x |

---

## 10. CHAT - Chat Progetto (Collaborative)

| ID | Test | T | W | S |
|----|------|---|---|---|
| CHAT-001 | Chat panel visibile in modo server | - | x | x |
| CHAT-002 | Invia messaggio chat | - | x | x |
| CHAT-003 | Messaggi mostrano display name (non username) | - | x | x |
| CHAT-004 | Polling nuovi messaggi funziona | - | x | x |
| CHAT-005 | Badge unread quando panel chiuso | - | x | x |
| CHAT-006 | Espandi chat a modal | - | x | x |
| CHAT-007 | Comprimi chat panel | - | x | x |
| CHAT-008 | Enter invia messaggio | - | x | x |
| CHAT-009 | Messaggi propri allineati a destra | - | x | x |

---

## 11. COLLAB - Collaborazione

| ID | Test | T | W | S |
|----|------|---|---|---|
| COLLAB-001 | Owner aggiunge collaboratore | - | x | x |
| COLLAB-002 | Owner rimuove collaboratore | - | x | x |
| COLLAB-003 | Collaboratore vede progetto nella lista | - | x | x |
| COLLAB-004 | Collaboratore non può eliminare progetto | - | x | x |
| COLLAB-005 | Owner assegna chunk a collaboratore | - | x | x |
| COLLAB-006 | Owner rilascia chunk da collaboratore | - | x | x |
| COLLAB-007 | Collaboratore può modificare solo suoi chunk | - | x | x |
| COLLAB-008 | Sync real-time modifiche tra utenti | - | x | x |
| COLLAB-009 | Conflict resolution (last-write-wins) | - | x | x |
| COLLAB-010 | Presence indicator (chi sta editando) | - | x | x |

---

## 12. VERSION - Versioning (Collaborative)

| ID | Test | T | W | S |
|----|------|---|---|---|
| VER-001 | Pannello versioni si apre | - | x | x |
| VER-002 | Lista versioni mostra commit recenti | - | x | x |
| VER-003 | Auto-versioning ogni 50 operazioni | - | x | x |
| VER-004 | Click su versione mostra diff preview | - | x | x |
| VER-005 | Diff mostra righe aggiunte/rimosse | - | x | x |
| VER-006 | Owner crea tag manuale | - | x | x |
| VER-007 | Tag visibile nella lista versioni | - | x | x |
| VER-008 | Restore versione precedente | - | x | x |
| VER-009 | Collaboratore non può creare tag | - | x | x |

---

## 13. AI - Funzionalità AI

| ID | Test | T | W | S |
|----|------|---|---|---|
| AI-001 | Settings: configura API key | x | x | - |
| AI-002 | Settings: seleziona modello | x | x | - |
| AI-003 | Genera testo con AI (Cmd+G) | x | x | - |
| AI-004 | AI rispetta contesto chunk corrente | x | x | - |
| AI-005 | Annulla generazione in corso | x | x | - |
| AI-006 | Errore API key invalida gestito | x | x | - |
| AI-007 | Streaming risposta visibile | x | x | - |
| AI-008 | AI suggerimenti per aspetti | x | x | - |

---

## 14. IMPORT - Importazione

| ID | Test | T | W | S |
|----|------|---|---|---|
| IMP-001 | Importa file .trmd | x | x | - |
| IMP-002 | Importa file .md (Markdown) | x | x | - |
| IMP-003 | Importa file .docx (Word) | x | x | - |
| IMP-004 | Importa file .txt | x | x | - |
| IMP-005 | Gestione errori file corrotto | x | x | - |
| IMP-006 | Encoding UTF-8 preservato | x | x | - |
| IMP-007 | Struttura heading da Markdown | x | x | - |

---

## 15. EXPORT - Esportazione

| ID | Test | T | W | S |
|----|------|---|---|---|
| EXP-001 | Esporta come .trmd | x | x | - |
| EXP-002 | Esporta come .md (Markdown) | x | x | - |
| EXP-003 | Esporta come .docx (Word) | x | x | - |
| EXP-004 | Esporta come .pdf | x | x | - |
| EXP-005 | Esporta come .txt | x | x | - |
| EXP-006 | Esporta selezione (solo chunk selezionati) | x | x | - |
| EXP-007 | Caratteri speciali preservati | x | x | - |
| EXP-008 | Formattazione Markdown in export | x | x | - |

---

## 16. RADIAL - Mappa Radiale

| ID | Test | T | W | S |
|----|------|---|---|---|
| RAD-001 | Toggle vista radiale | x | x | - |
| RAD-002 | Nodi mostrano struttura documento | x | x | - |
| RAD-003 | Click su nodo seleziona chunk | x | x | - |
| RAD-004 | Zoom in/out funziona | x | x | - |
| RAD-005 | Pan (trascinamento) funziona | x | x | - |
| RAD-006 | Colori nodi per profondità | x | x | - |
| RAD-007 | Distribuzione leaf-weighted | x | x | - |
| RAD-008 | Resize finestra aggiorna mappa | x | x | - |
| RAD-009 | Evidenziazione chunk corrente | x | x | - |

---

## 17. CONTEXT - Menu Contestuali

| ID | Test | T | W | S |
|----|------|---|---|---|
| CTX-001 | Right-click su chunk mostra menu | x | x | - |
| CTX-002 | Menu chunk: rinomina | x | x | - |
| CTX-003 | Menu chunk: aggiungi figlio | x | x | - |
| CTX-004 | Menu chunk: aggiungi fratello | x | x | - |
| CTX-005 | Menu chunk: elimina | x | x | - |
| CTX-006 | Menu chunk: assegna aspetto | x | x | - |
| CTX-007 | Right-click su selezione testo | x | x | - |
| CTX-008 | Menu selezione: aggiungi commento | x | x | - |
| CTX-009 | Menu selezione: crea proposta (server) | - | x | x |
| CTX-010 | Menu selezione: genera con AI | x | x | - |

---

## 18. KEYBOARD - Shortcuts Tastiera

| ID | Test | T | W | S |
|----|------|---|---|---|
| KEY-001 | Cmd+S salva | x | - | - |
| KEY-002 | Cmd+Z undo | x | x | - |
| KEY-003 | Cmd+Shift+Z redo | x | x | - |
| KEY-004 | Cmd+F cerca | x | x | - |
| KEY-005 | Cmd+G genera AI | x | x | - |
| KEY-006 | Cmd+M commento | x | x | - |
| KEY-007 | Cmd+Enter nuovo chunk | x | x | - |
| KEY-008 | Escape chiude modal/popover | x | x | - |
| KEY-009 | Tab indenta chunk | x | x | - |
| KEY-010 | Shift+Tab deindenta | x | x | - |
| KEY-011 | Alt+Up/Down muove chunk | x | x | - |
| KEY-012 | Cmd+1/2/3 cambia heading | x | x | - |

---

## 19. PERF - Performance

| ID | Test | T | W | S |
|----|------|---|---|---|
| PERF-001 | Documento grande (100+ chunk) risponde | x | x | - |
| PERF-002 | Digitazione fluida senza lag | x | x | - |
| PERF-003 | Scroll outline fluido | x | x | - |
| PERF-004 | Autosave non blocca UI | - | x | x |
| PERF-005 | Polling non blocca UI | - | x | x |
| PERF-006 | Mappa radiale fluida | x | x | - |
| PERF-007 | Import file grande (<5s per 1MB) | x | x | - |
| PERF-008 | Export file grande (<5s per 1MB) | x | x | - |

---

## 20. ERROR - Gestione Errori

| ID | Test | T | W | S |
|----|------|---|---|---|
| ERR-001 | Server non raggiungibile mostra errore | - | x | - |
| ERR-002 | Timeout request gestito | - | x | - |
| ERR-003 | File non trovato gestito | x | - | - |
| ERR-004 | Permessi file insufficienti gestito | x | - | - |
| ERR-005 | JSON malformato gestito | x | x | - |
| ERR-006 | Network offline gestito | - | x | - |
| ERR-007 | 401 Unauthorized porta a logout | - | x | x |
| ERR-008 | 403 Forbidden mostra errore | - | x | x |
| ERR-009 | 404 Not Found gestito | - | x | x |
| ERR-010 | 500 Server Error mostra messaggio | - | x | x |

---

## Note per i Tester

### Setup Ambiente Test

**Tauri:**
```bash
npm run tauri:dev
```

**Webapp + Server:**
```bash
# Terminal 1: Server
cd server && clj -M:run

# Terminal 2: Frontend
npm run dev
```

### Reporting Bug

Quando trovi un bug, riporta:
1. **ID Test** che fallisce (es. `EDIT-003`)
2. **Piattaforma** (Tauri/Webapp)
3. **Passi per riprodurre**
4. **Comportamento atteso vs attuale**
5. **Screenshot/video se utile**
6. **Console errors** (F12 > Console)

### Priorità Test

Prima di ogni release:
1. **P0 - Critici:** AUTH, BASIC, PROJ, EDIT (funzionalità core)
2. **P1 - Importanti:** STRUCT, COLLAB, VERSION
3. **P2 - Normali:** Tutto il resto

---

*Ultimo aggiornamento: v1.7.0*
