# Tramando - Guida per Claude

## Stack tecnologico

- **Frontend**: ClojureScript con Reagent, compilato con shadow-cljs
- **Backend**: Clojure con Ring/Reitit, SQLite
- **Formato dati**: TRMD (formato proprietario per chunk di testo)

## Modifiche a codice Clojure/ClojureScript

Quando modifichi funzioni Clojure/ClojureScript:

1. **Sostituisci sempre funzioni intere** - Non fare edit parziali che richiedono di aggiustare le parentesi. Leggi l'intera funzione, riscrivila con le modifiche, e sostituisci tutto il blocco dal `(defn` alla chiusura finale.

2. **Verifica la compilazione** - Dopo modifiche ClojureScript, verifica con:
   ```bash
   npx shadow-cljs compile app
   ```

3. **Test server Clojure**:
   ```bash
   cd server && clj -M:test
   ```

## Struttura del progetto

```
src/tramando/          # ClojureScript frontend
  model.cljs           # Stato applicazione, logica chunk
  editor.cljs          # Editor CodeMirror, UI editing
  outline.cljs         # Sidebar con albero struttura

server/src/tramando/server/  # Clojure backend
  routes.clj           # API REST endpoints
  storage.clj          # Persistenza file .trmd
  db.clj               # SQLite per utenti/progetti
```

## Ownership e permessi

- **Owner**: può fare tutto (modificare struttura, aspetti, cancellare chunk)
- **Collaborator**: può solo modificare contenuto dei propri chunk, aggiungere commenti/proposals

Funzioni chiave per i permessi in `model.cljs`:
- `is-project-owner?` - true se owner o modo locale
- `can-edit-chunk?` - verifica se l'utente può modificare un chunk specifico
