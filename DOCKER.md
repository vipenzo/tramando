# Installazione Tramando con Docker

Questa guida spiega come installare e configurare Tramando (versione collaborativa) usando Docker.

> **Nota**: Questa configurazione è per la versione **webapp/server**. La versione desktop (Tauri) non usa Docker.

## Requisiti

- Docker e Docker Compose
- (Opzionale) Reverse proxy (Caddy, nginx) per HTTPS

## Installazione rapida

1. **Clona il repository:**
   ```bash
   git clone https://github.com/vipenzo/tramando.git
   cd tramando
   ```

2. **Copia e configura le variabili d'ambiente:**
   ```bash
   cp .env.example .env
   ```

   Modifica `.env` secondo necessità (vedi sezione Configurazione).

3. **Avvia:**
   ```bash
   docker-compose up -d
   ```

4. **Accedi a** http://localhost:3000

## Configurazione

### Variabili d'ambiente

| Variabile | Default | Descrizione |
|-----------|---------|-------------|
| `PORT` | `3000` | Porta su cui gira il server |
| `BASE_PATH` | (vuoto) | Subpath se servito sotto prefisso (es. `/tramando`) |
| `TRAMANDO_JWT_SECRET` | `change-me...` | **Cambia in produzione!** Secret per token JWT |
| `TRAMANDO_ALLOW_REGISTRATION` | `true` | `true` = chiunque può registrarsi, `false` = solo admin |

### Generare un JWT secret sicuro

```bash
openssl rand -hex 32
```

## Configurazione con Caddy (subpath)

Per servire Tramando sotto un subpath (es. `https://tuosito.com/tramando`):

1. **In `.env` imposta:**
   ```
   BASE_PATH=/tramando
   ```

2. **Nel Caddyfile aggiungi:**
   ```
   tuosito.com {
       handle_path /tramando* {
           reverse_proxy 127.0.0.1:3000
       }
   }
   ```

3. **Riavvia i servizi:**
   ```bash
   docker-compose restart
   sudo systemctl reload caddy
   ```

## Configurazione con nginx

```nginx
location /tramando/ {
    proxy_pass http://127.0.0.1:3000/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection 'upgrade';
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_cache_bypass $http_upgrade;
}
```

## Comandi utili

```bash
# Avvia in background
docker-compose up -d

# Vedi i log
docker-compose logs -f

# Ferma
docker-compose down

# Ricostruisci dopo aggiornamenti
docker-compose build --no-cache
docker-compose up -d

# Accedi al container
docker-compose exec tramando sh
```

## Aggiornamento

```bash
git pull
docker-compose build
docker-compose up -d
```

## Dati persistenti

I dati sono salvati nella cartella `./data` (mappata come volume):

```
data/
├── tramando.db          # Database SQLite (utenti, permessi, chat)
└── projects/            # File .trmd dei progetti
    ├── 1.trmd
    ├── 2.trmd
    └── ...
```

**Fai backup regolari di questa cartella!**

### Backup manuale

```bash
# Crea backup
tar -czvf tramando-backup-$(date +%Y%m%d).tar.gz data/

# Ripristina backup
tar -xzvf tramando-backup-YYYYMMDD.tar.gz
```

## Primo utilizzo

1. **Accedi** all'URL configurato
2. **Registra** il primo utente (diventerà automaticamente admin se è il primo)
3. **Disabilita la registrazione** se vuoi un sistema chiuso:
   - Imposta `TRAMANDO_ALLOW_REGISTRATION=false` in `.env`
   - Riavvia con `docker-compose restart`
4. **Crea altri utenti** dal pannello admin (icona utenti nell'header)

## Troubleshooting

### Il container non parte

```bash
# Controlla i log
docker-compose logs tramando

# Verifica che la porta non sia già in uso
lsof -i :3000
```

### Errori di permessi sulla cartella data

```bash
# Assicurati che la cartella esista e abbia i permessi corretti
mkdir -p data/projects
chmod -R 755 data
```

### Reset completo

```bash
docker-compose down
rm -rf data/
docker-compose up -d
```

## Architettura

```
┌─────────────────────────────────────────────────────┐
│                   Reverse Proxy                      │
│              (Caddy/nginx - opzionale)              │
│                    ↓ HTTPS                          │
└─────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│                 Docker Container                     │
│  ┌───────────────────────────────────────────────┐  │
│  │           Clojure Server (Jetty)              │  │
│  │  ┌─────────────┐    ┌────────────────────┐   │  │
│  │  │  API REST   │    │  File statici      │   │  │
│  │  │  /api/*     │    │  (frontend JS/CSS) │   │  │
│  │  └─────────────┘    └────────────────────┘   │  │
│  └───────────────────────────────────────────────┘  │
│                         │                            │
│                         ▼                            │
│  ┌───────────────────────────────────────────────┐  │
│  │              Volume /data                      │  │
│  │  • tramando.db (SQLite)                       │  │
│  │  • projects/*.trmd                            │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

## Supporto

- **Issues**: https://github.com/vipenzo/tramando/issues
- **Documentazione**: Vedi i file README.md e ARCHITECTURE.md
