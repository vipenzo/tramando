# Tramando Server

Backend per la modalità collaborativa di Tramando.

## Requisiti

- Java 21+
- Clojure CLI tools

## Sviluppo

```bash
# Avvia il server
clojure -M:run

# Oppure con REPL
clojure -M:dev
```

Il server parte su http://localhost:3000

## Configurazione

Variabili d'ambiente:

| Variabile | Default | Descrizione |
|-----------|---------|-------------|
| `PORT` | 3000 | Porta HTTP |
| `TRAMANDO_DB_PATH` | data/tramando.db | Path database SQLite |
| `TRAMANDO_PROJECTS_PATH` | data/projects | Directory file .trmd |
| `TRAMANDO_JWT_SECRET` | change-me-in-production | Secret per JWT |
| `TRAMANDO_JWT_EXPIRATION` | 24 | Ore validità token |
| `TRAMANDO_ALLOW_REGISTRATION` | true | Abilita registrazione |

## Docker

```bash
# Build
docker build -t tramando-server .

# Run
docker run -p 3000:3000 -v tramando-data:/app/data \
  -e TRAMANDO_JWT_SECRET=your-secret-here \
  tramando-server
```

## API

### Auth
- `POST /api/register` - Registrazione (primo utente diventa super-admin)
- `POST /api/login` - Login, ritorna JWT
- `GET /api/me` - Utente corrente (richiede auth)

### Progetti
- `GET /api/projects` - Lista progetti accessibili
- `POST /api/projects` - Crea progetto
- `GET /api/projects/:id` - Carica progetto (metadati + contenuto)
- `PUT /api/projects/:id` - Salva progetto
- `DELETE /api/projects/:id` - Elimina progetto

### Collaboratori
- `GET /api/projects/:id/collaborators` - Lista collaboratori
- `POST /api/projects/:id/collaborators` - Invita collaboratore
- `DELETE /api/projects/:id/collaborators/:user-id` - Rimuovi collaboratore

### Admin
- `GET /api/admin/users` - Lista utenti (solo super-admin)
