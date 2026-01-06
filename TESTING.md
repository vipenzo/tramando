# Testing in Tramando

## Quick Start

```bash
# Unit tests (logica ClojureScript)
npm test

# Unit tests in watch mode (durante sviluppo)
npm run test:watch

# E2E tests (interfaccia utente)
npm run test:e2e

# E2E tests con UI interattiva
npm run test:e2e:ui
```

## Unit Test

I test unitari verificano la logica core di Tramando:
- Parsing e serializzazione file `.trmd`
- Manipolazione chunk (creazione, modifica, spostamento)
- Merge e conflict resolution
- Logica di ownership collaborativa

### Struttura

```
test/
  tramando/
    model_test.cljs    # Test parsing, serializzazione, merge
```

### Eseguire i test

```bash
npm test           # esegue una volta
npm run test:watch # watch mode - riesegue ad ogni modifica
```

### Scrivere nuovi test

```clojure
(ns tramando.myfeature-test
  (:require [cljs.test :refer [deftest testing is are]]
            [tramando.myfeature :as feature]))

(deftest my-function-test
  (testing "descrizione del comportamento"
    (is (= expected (feature/my-function input)))))
```

## E2E Test (Playwright)

I test end-to-end verificano l'interfaccia utente completa:
- Caricamento pagina
- Navigazione
- Interazioni utente
- Modalità server/collaborativa

### Prerequisiti

```bash
# Installare i browser (una volta sola)
npx playwright install chromium
```

### Eseguire i test

```bash
npm run test:e2e      # headless (veloce)
npm run test:e2e:ui   # con UI interattiva (debug)
```

### Struttura

```
e2e/
  basic-flow.spec.js   # Test flusso base
```

### Scrivere nuovi test

```javascript
import { test, expect } from '@playwright/test';

test('descrizione del test', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.my-element')).toBeVisible();
  await page.click('button:has-text("Click me")');
  await expect(page.locator('.result')).toContainText('Success');
});
```

## Best Practices

### Quando scrivere test

1. **Bug fix**: Crea sempre un test che riproduce il bug PRIMA di fixarlo
2. **Nuova feature**: Almeno un test per l'happy path
3. **Refactoring**: Assicurati che i test esistenti passino

### Cosa testare dove

| Tipo di logica | Tipo di test |
|----------------|--------------|
| Parsing/serializzazione | Unit test |
| Manipolazione dati | Unit test |
| Business logic pura | Unit test |
| Interazione UI | E2E test |
| Flussi utente completi | E2E test |
| Integrazione server | E2E test |

### Naming conventions

- File test: `*_test.cljs` (unit) o `*.spec.js` (E2E)
- Namespace: termina con `-test` per essere rilevato da shadow-cljs
- Nome test: descrittivo del comportamento testato

## CI/CD

I test possono essere eseguiti in CI con:

```yaml
# GitHub Actions example
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: 18
      - run: npm ci
      - run: npm test
      - run: npx playwright install chromium
      - run: npm run test:e2e
```

## Troubleshooting

### Unit test non trovati
- Verifica che il namespace termini con `-test`
- Pulisci cache: `rm -rf .shadow-cljs out && npm test`

### E2E test timeout
- Assicurati che il dev server sia avviato: `npm run dev`
- Aumenta il timeout in `playwright.config.js`

### Shadow-cljs non trova i file test
- Verifica `shadow-cljs.edn`: `:source-paths ["src" "test"]`
- Il file deve essere `test/tramando/nome_test.cljs` (underscore nel nome file)
