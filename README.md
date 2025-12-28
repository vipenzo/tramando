# Tramando

**Tessi la tua storia**

Tramando è uno strumento per scrittori che vogliono gestire storie complesse: personaggi, luoghi, temi, linee temporali.

## Caratteristiche

- Editor con supporto Markdown
- Organizzazione gerarchica flessibile (tutto è un "chunk")
- Gestione Personaggi, Luoghi, Temi, Sequenze, Timeline
- Collegamenti tra scene e aspetti con sintassi `[@id]`
- Annotazioni (TODO, NOTE, FIX) integrate nel testo
- Mappa radiale interattiva
- Export PDF professionale
- Temi personalizzabili

## Installazione

### Requisiti
- Node.js 18+
- Java (per shadow-cljs)

### Sviluppo
```bash
npm install
npx shadow-cljs watch app
```

Apri http://localhost:8080

### Build applicazione desktop

Richiede anche:
- Rust (https://rustup.rs)
- Per Mac: Xcode Command Line Tools
- Per Windows: Visual Studio Build Tools + WebView2
- Per Linux: build-essential, libwebkit2gtk-4.0-dev

```bash
npm run tauri:build
```

## Formato file

I progetti sono salvati in formato `.trmd`, un file di testo leggibile con:
- Frontmatter YAML per i metadati
- Sintassi Markdown per la formattazione
- Sintassi speciale per chunk, riferimenti e annotazioni

## Licenza

[Da definire]

## Autore

Vincenzo
