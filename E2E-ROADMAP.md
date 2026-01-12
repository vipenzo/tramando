# E2E Test Roadmap - Tramando

Roadmap per l'automazione dei test E2E con Playwright.

## Stato Attuale

### Test già implementati:

| File | Copertura |
|------|-----------|
| `basic-flow.spec.js` | BASIC-001, BASIC-002, BASIC-003, BASIC-004, PROJ-001 |
| `modals.spec.js` | BASIC-005, PROJ-012, KEY-008 |
| `server-mode.spec.js` | AUTH-003, AUTH-004, PROJ-007, PROJ-008, COLLAB-001, COLLAB-002, AUTH-007 |
| `undo-redo.spec.js` | EDIT-002, EDIT-003, EDIT-004, EDIT-005, EDIT-006, EDIT-007 |
| `editing.spec.js` | EDIT-001, EDIT-008, EDIT-011, EDIT-014, EDIT-015 |
| `structure.spec.js` | STRUCT-001, STRUCT-002, STRUCT-003, STRUCT-004, STRUCT-006, STRUCT-007, STRUCT-008, STRUCT-010, STRUCT-014 |
| `auth.spec.js` | AUTH-001, AUTH-005, AUTH-006 |
| `annotations.spec.js` | ANNOT-001, ANNOT-002, ANNOT-003, ANNOT-005, ANNOT-006 |
| `proposals.spec.js` | PROP-001 - PROP-011 |
| `discussions.spec.js` | DISCUSS-001 - DISCUSS-008 |
| `chat.spec.js` | CHAT-001 - CHAT-009 |
| `collab-advanced.spec.js` | COLLAB-003 - COLLAB-007 |

**Totale test esistenti:** ~135 test cases

---

## Roadmap per Categoria

### Fase 1: Funzionalità Core (Priorità Alta)

#### 1.1 Editing Base (`editing.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| EDIT-001 | Digitazione testo nell'editor | ✅ |
| EDIT-008 | Copia/incolla testo | ✅ |
| EDIT-009 | Cerca nel documento (Cmd+F) | ⚠️ Manuale (keybinding) |
| EDIT-010 | Sostituisci nel documento (Cmd+H) | ⚠️ Manuale (keybinding) |
| EDIT-011 | Markdown rendering in preview | ✅ |
| EDIT-014 | Word wrap funziona | ✅ |
| EDIT-015 | Font size da settings | ✅ |

#### 1.2 Struttura (`structure.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| STRUCT-001 | Outline mostra gerarchia chunk | ✅ |
| STRUCT-002 | Click su chunk nell'outline lo seleziona | ✅ |
| STRUCT-003 | Espandi/comprimi nodi outline | ✅ |
| STRUCT-004 | Aggiungi chunk figlio | ✅ |
| STRUCT-006 | Elimina chunk (con conferma) | ✅ |
| STRUCT-007 | Elimina chunk con figli bloccato | ✅ |
| STRUCT-008 | Rinomina chunk (summary) | ✅ |
| STRUCT-010 | Navigazione con frecce su/giu | ✅ |
| STRUCT-014 | Chunk corrente evidenziato nell'outline | ✅ |

#### 1.3 Autenticazione (`auth.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| AUTH-001 | Form registrazione accessibile | ✅ |
| AUTH-005 | Logout e gestione sessione | ✅ |
| AUTH-006 | Accesso non autorizzato bloccato | ✅ |
| AUTH-009 | Single-login invalida sessione | ⚠️ Manuale |
| AUTH-010 | Sessione invalidata torna splash | ⚠️ Manuale |
| AUTH-012 | Cambio display name | ⚠️ Manuale |
| AUTH-013 | Cambio password | ⚠️ Manuale |

---

### Fase 2: Collaborazione (Priorità Alta)

#### 2.1 Annotazioni (`annotations.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| ANNOT-001 | Aggiungi annotazione (menu contestuale) | ✅ |
| ANNOT-002 | Annotazione visibile come highlight | ✅ |
| ANNOT-003 | Click su highlight mostra popover | ✅ |
| ANNOT-004 | Modifica annotazione esistente | ⚠️ Manuale |
| ANNOT-005 | Elimina annotazione | ✅ |
| ANNOT-006 | Pannello annotazioni mostra lista | ✅ |
| ANNOT-007 | Click annotazione naviga al testo | ⚠️ Manuale |
| ANNOT-008 | Risolvi/chiudi annotazione | ⚠️ Manuale |
| ANNOT-009 | Display name su commenti server | ⚠️ Manuale |
| ANNOT-010 | Collaborator può commentare chunk altrui | ⚠️ Manuale |

#### 2.2 Proposte (`proposals.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| PROP-001 | Crea proposta su testo selezionato | ✅ |
| PROP-002 | Proposta visibile come highlight | ✅ |
| PROP-003 | Click su proposta mostra popover + diff | ✅ |
| PROP-004 | Owner accetta proposta (testo sostituito) | ✅ |
| PROP-005 | Owner rifiuta proposta | ✅ |
| PROP-006 | Proposta con caratteri speciali | ✅ |
| PROP-007 | Proposta su whitespace funziona | ✅ |
| PROP-008 | Pannello proposte mostra lista | ✅ |
| PROP-009 | Collaborator non può modificare diretto | ✅ |
| PROP-010 | Selezione con annotazione non crea nuove | ✅ |
| PROP-011 | Proposta di cancellazione (testo vuoto) | ❌ test.fail |

#### 2.3 Discussioni (`discussions.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| DISCUSS-001 | Apri pannello discussione chunk | ✅ |
| DISCUSS-002 | Aggiungi messaggio discussione | ✅ |
| DISCUSS-003 | Messaggi mostrano display name | ✅ |
| DISCUSS-004 | Timestamp messaggi corretto | ✅ |
| DISCUSS-005 | Scroll automatico a nuovo messaggio | ✅ |
| DISCUSS-006 | Discussione persiste dopo reload | ✅ |
| DISCUSS-007 | Caratteri speciali nei messaggi | ✅ |
| DISCUSS-008 | Indicatore discussioni nell'outline | ✅ |

#### 2.4 Chat (`chat.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| CHAT-001 | Chat panel visibile in modo server | ✅ |
| CHAT-002 | Invia messaggio chat | ✅ |
| CHAT-003 | Messaggi mostrano display name | ✅ |
| CHAT-005 | Badge unread quando panel chiuso | ✅ |
| CHAT-006 | Espandi chat a modal | ✅ |
| CHAT-007 | Comprimi chat panel | ✅ |
| CHAT-008 | Enter invia messaggio | ✅ |
| CHAT-009 | Messaggi propri allineati a destra | ✅ |

#### 2.5 Collaborazione Avanzata (`collab-advanced.spec.js`) - COMPLETATO
| ID | Test | Stato |
|----|------|-------|
| COLLAB-003 | Collaboratore vede progetto in lista | ✅ |
| COLLAB-004 | Collaboratore non può eliminare prog | ✅ |
| COLLAB-005 | Owner assegna chunk a collaboratore | ✅ |
| COLLAB-006 | Owner rilascia chunk da collaboratore | ✅ |
| COLLAB-007 | Collaboratore modifica solo suoi chunk | ✅ |

---

### Fase 3: Versioning e Aspetti (Priorità Media)

#### 3.1 Versioning (`versioning.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| VER-001 | Pannello versioni si apre | Bassa |
| VER-002 | Lista versioni mostra commit recenti | Media |
| VER-003 | Auto-versioning ogni 50 operazioni | Alta |
| VER-004 | Click su versione mostra diff preview | Media |
| VER-005 | Diff mostra righe aggiunte/rimosse | Media |
| VER-006 | Owner crea tag manuale | Media |
| VER-007 | Tag visibile nella lista versioni | Bassa |
| VER-008 | Restore versione precedente | Alta |
| VER-009 | Collaboratore non può creare tag | Media |

#### 3.2 Aspetti (`aspects.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| ASPECT-001 | Container aspetti visibili nell'outline | Bassa |
| ASPECT-002 | Aggiungi nuovo aspetto (pers/luogo/etc) | Media |
| ASPECT-003 | Assegna aspetto a chunk | Media |
| ASPECT-004 | Rimuovi aspetto da chunk | Media |
| ASPECT-005 | Filtro per aspetto nell'outline | Media |
| ASPECT-006 | Badge aspetti visibili su chunk | Bassa |
| ASPECT-007 | Click su badge aspetto lo evidenzia | Media |
| ASPECT-008 | Autocomplete aspetti quando si digita @ | Alta |
| ASPECT-009 | Elimina aspetto (rimuove da tutti) | Media |
| ASPECT-010 | Rinomina aspetto | Media |

---

### Fase 4: Import/Export (Priorità Media)

#### 4.1 Import (`import.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| IMP-001 | Importa file .trmd | Media |
| IMP-002 | Importa file .md (Markdown) | Media |
| IMP-003 | Importa file .docx (Word) | Alta |
| IMP-004 | Importa file .txt | Bassa |
| IMP-005 | Gestione errori file corrotto | Media |
| IMP-006 | Encoding UTF-8 preservato | Media |
| IMP-007 | Struttura heading da Markdown | Media |

#### 4.2 Export (`export.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| EXP-001 | Esporta come .trmd | Media |
| EXP-002 | Esporta come .md (Markdown) | Media |
| EXP-003 | Esporta come .docx (Word) | Alta |
| EXP-004 | Esporta come .pdf | Alta |
| EXP-005 | Esporta come .txt | Bassa |
| EXP-006 | Esporta selezione (solo chunk selez.) | Media |
| EXP-007 | Caratteri speciali preservati | Media |
| EXP-008 | Formattazione Markdown in export | Media |

---

### Fase 5: Menu e Shortcuts (Priorità Media)

#### 5.1 Context Menu (`context-menu.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| CTX-001 | Right-click su chunk mostra menu | Media |
| CTX-002 | Menu chunk: rinomina | Media |
| CTX-003 | Menu chunk: aggiungi figlio | Media |
| CTX-004 | Menu chunk: aggiungi fratello | Media |
| CTX-005 | Menu chunk: elimina | Media |
| CTX-006 | Menu chunk: assegna aspetto | Media |
| CTX-007 | Right-click su selezione testo | Media |
| CTX-008 | Menu selezione: aggiungi commento | Media |
| CTX-009 | Menu selezione: crea proposta (server) | Media |

#### 5.2 Keyboard Shortcuts (`keyboard.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| KEY-001 | Cmd+S salva (solo Tauri) | Media |
| KEY-002 | Cmd+Z undo | Bassa |
| KEY-003 | Cmd+Shift+Z redo | Bassa |
| KEY-004 | Cmd+F cerca | Media |
| KEY-006 | Cmd+M commento | Media |
| KEY-007 | Cmd+Enter nuovo chunk | Media |
| KEY-009 | Tab indenta chunk | Media |
| KEY-010 | Shift+Tab deindenta | Media |
| KEY-011 | Alt+Up/Down muove chunk | Media |
| KEY-012 | Cmd+1/2/3 cambia heading | Media |

---

### Fase 6: Progetti e Gestione Errori (Priorità Bassa)

#### 6.1 Progetti (`projects.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| PROJ-009 | Elimina progetto server (solo owner) | Media |
| PROJ-010 | Autosave funziona (3 secondi) | Media |
| PROJ-011 | Sync indicator mostra stato | Media |
| PROJ-013 | Metadata modal: modifica autore | Bassa |
| PROJ-014 | Metadata modal: aggiungi campo custom | Media |
| PROJ-015 | Click su logo torna a splash/lista | Bassa |

#### 6.2 Error Handling (`error-handling.spec.js`)
| ID | Test | Complessità |
|----|------|-------------|
| ERR-001 | Server non raggiungibile mostra errore | Media |
| ERR-002 | Timeout request gestito | Media |
| ERR-005 | JSON malformato gestito | Media |
| ERR-007 | 401 Unauthorized porta a logout | Media |
| ERR-008 | 403 Forbidden mostra errore | Media |
| ERR-009 | 404 Not Found gestito | Media |
| ERR-010 | 500 Server Error mostra messaggio | Media |

---

## Test NON Automatizzabili

Questi test richiedono verifica manuale o setup troppo complesso:

| Categoria | Test | Motivo |
|-----------|------|--------|
| COLLAB | COLLAB-008, COLLAB-009, COLLAB-010 | Multi-utente real-time |
| CHAT | CHAT-004 | Polling timing |
| AI | AI-001 - AI-008 | Richiede API key reale |
| RADIAL | RAD-001 - RAD-009 | Canvas/verifica visiva |
| PERF | PERF-001 - PERF-008 | Performance soggettiva |
| BASIC | BASIC-006, BASIC-007 | Tutorial/visual |
| KEYBOARD | KEY-005 (AI) | Richiede API key |
| CTX | CTX-010 (AI) | Richiede API key |
| PROJ (Tauri) | PROJ-002 - PROJ-006 | File system nativo |
| ERROR (file) | ERR-003, ERR-004, ERR-006 | Simulazione difficile |

**Totale test manuali:** ~30

---

## Priorità Implementazione

### Sprint 1: Foundation
- [ ] `structure.spec.js` - Struttura base
- [ ] `editing.spec.js` - Editing base
- [ ] `auth.spec.js` - Completare autenticazione

### Sprint 2: Collaboration
- [ ] `annotations.spec.js` - Commenti
- [ ] `proposals.spec.js` - Proposte
- [ ] `discussions.spec.js` - Discussioni

### Sprint 3: Features
- [ ] `chat.spec.js` - Chat
- [ ] `aspects.spec.js` - Aspetti
- [ ] `versioning.spec.js` - Versioning

### Sprint 4: I/O
- [ ] `import.spec.js` - Import
- [ ] `export.spec.js` - Export
- [ ] `context-menu.spec.js` - Menu contestuali

### Sprint 5: Polish
- [ ] `keyboard.spec.js` - Shortcuts
- [ ] `projects.spec.js` - Gestione progetti
- [ ] `error-handling.spec.js` - Errori

---

## Utility Helpers da Creare

```javascript
// e2e/helpers/auth.js
export async function loginViaAPI(username, password) { ... }
export async function setAuthToken(page, token) { ... }
export async function logout(page) { ... }

// e2e/helpers/editor.js
export async function waitForEditor(page) { ... }
export async function getEditorContent(page) { ... }
export async function typeInEditor(page, text) { ... }
export async function selectChunk(page, name) { ... }

// e2e/helpers/outline.js
export async function addChildChunk(page) { ... }
export async function addSiblingChunk(page) { ... }
export async function deleteChunk(page) { ... }
export async function renameChunk(page, newName) { ... }

// e2e/helpers/modals.js
export async function openSettingsModal(page) { ... }
export async function openMetadataModal(page) { ... }
export async function closeModal(page) { ... }
```

---

## Configurazione CI/CD

```yaml
# .github/workflows/e2e.yml
name: E2E Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      tramando-server:
        image: tramando/server:test
        ports:
          - 3001:3001
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
      - run: npm ci
      - run: npx playwright install
      - run: npm run test:e2e
```

---

## Metriche Target

| Metrica | Attuale | Target |
|---------|---------|--------|
| Test implementati | ~35 | ~155 |
| Copertura ID | ~15% | ~85% |
| Tempo esecuzione | ~2min | <10min |
| Flaky rate | ? | <2% |

---

*Ultimo aggiornamento: v1.8.0*
