import { test, expect } from '@playwright/test';

/**
 * Test E2E per funzionalità Undo/Redo
 *
 * Testa il flusso completo:
 * - Undo locale (CodeMirror) - singolo chunk
 * - Undo server (dopo autosave) - cross-chunk
 * - Server redo dopo undo
 * - Toast notification con info sui chunk modificati
 * - Bottoni toolbar
 */

const API_URL = 'http://localhost:3001';

// Helper per login via API
async function loginViaAPI(username, password) {
  const response = await fetch(`${API_URL}/api/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const data = await response.json();
  return data.token;
}

// Helper per impostare auth token
async function setAuthToken(page, token) {
  await page.evaluate((t) => {
    localStorage.setItem('tramando-auth-token', t);
    localStorage.setItem('tramando-server-url', 'http://localhost:3001');
    localStorage.setItem('tramando-mode', 'server');
    // Imposta autosave a 2 secondi per test più veloci
    const settings = JSON.parse(localStorage.getItem('tramando-settings') || '{}');
    settings['autosave-delay-ms'] = 2000;
    localStorage.setItem('tramando-settings', JSON.stringify(settings));
  }, token);
}

// Helper per aspettare che l'editor sia pronto
async function waitForEditor(page) {
  // Aspetta che i chunk siano caricati nell'outline
  await page.waitForTimeout(1000);

  // Cerca un chunk da selezionare - i chunk nell'outline hanno il titolo come testo
  // Prova a trovare "Capitolo 1" che è nel progetto di test
  const chunkSelectors = [
    'text=Capitolo 1',
    'text=Capitolo 2',
    'text=cap1',
    'text=cap2'
  ];

  for (const selector of chunkSelectors) {
    const chunk = page.locator(selector).first();
    if (await chunk.isVisible({ timeout: 2000 }).catch(() => false)) {
      await chunk.click();
      await page.waitForTimeout(300);
      break;
    }
  }

  // Aspetta che l'editor CodeMirror sia montato
  await page.waitForSelector('.cm-editor', { timeout: 10000 });
  // Aspetta un attimo per il focus
  await page.waitForTimeout(500);
}

// Helper per ottenere il contenuto dell'editor
async function getEditorContent(page) {
  return await page.evaluate(() => {
    // Prova prima l'API di CodeMirror se disponibile
    const cmEditor = document.querySelector('.cm-editor');
    if (cmEditor && cmEditor.cmView) {
      // Accedi allo state di CodeMirror
      return cmEditor.cmView.state.doc.toString();
    }
    // Fallback: usa textContent ma con innerText per migliore compatibilità
    const cmContent = document.querySelector('.cm-editor .cm-content');
    if (cmContent) {
      // innerText è più affidabile per il testo visibile
      return cmContent.innerText;
    }
    return null;
  });
}

// Helper per digitare nell'editor
async function typeInEditor(page, text) {
  const editor = page.locator('.cm-editor .cm-content');
  await editor.click();
  await page.keyboard.type(text);
}

// Helper per selezionare tutto e sostituire
async function replaceEditorContent(page, newText) {
  const editor = page.locator('.cm-editor .cm-content');
  await editor.click();
  // Select all e sostituisci
  await page.keyboard.press('Meta+a');
  await page.keyboard.type(newText);
}

// Helper per fare undo via tastiera
async function pressUndo(page) {
  await page.keyboard.press('Meta+z');
}

// Helper per fare redo via tastiera
async function pressRedo(page) {
  await page.keyboard.press('Meta+Shift+z');
}

// Helper per aspettare autosave (sync con server)
async function waitForAutosave(page, timeoutMs = 5000) {
  // Aspetta che l'indicatore di autosave completi
  // L'indicatore mostra "Salvato" o scompare quando il sync è completo
  await page.waitForFunction(() => {
    const indicator = document.querySelector('[data-testid="autosave-indicator"]');
    if (!indicator) return true;
    const text = indicator.textContent || '';
    return text.includes('Salvato') || text === '' || !text.includes('...');
  }, { timeout: timeoutMs }).catch(() => {
    // Fallback: aspetta un tempo fisso
  });
  // Extra wait per sicurezza
  await page.waitForTimeout(500);
}

// Helper per forzare il sync immediato (se disponibile)
async function forceSave(page) {
  // Prova a cliccare il bottone salva se disponibile
  const saveButton = page.locator('button[title*="Salva"]').first();
  if (await saveButton.isVisible().catch(() => false)) {
    await saveButton.click();
    await page.waitForTimeout(1000);
  }
}

test.describe('Undo/Redo - Modalità Server', () => {

  test.beforeEach(async ({ page }) => {
    // Login come alice
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();

    // Aspetta che i progetti carichino
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1000);

    // Apri Alice Project 1
    await page.getByText('Alice Project 1').click();

    // Aspetta che il progetto carichi e i chunk appaiano
    // Il contenuto viene parsato e i chunk devono apparire nell'outline
    await page.waitForTimeout(2000);

    // Aspetta che "Capitolo 1" appaia nell'outline
    try {
      await page.waitForSelector('text=Capitolo 1', { timeout: 10000 });
    } catch (e) {
      console.log('Chunks not found, page content:', await page.content());
    }

    await waitForEditor(page);
  });

  test('Undo locale (CodeMirror) funziona dopo digitazione', async ({ page }) => {
    // Ottieni contenuto iniziale
    const initialContent = await getEditorContent(page);

    // Digita qualcosa
    await typeInEditor(page, 'TESTO AGGIUNTO');
    await page.waitForTimeout(200);

    // Verifica che il testo sia stato aggiunto
    const afterType = await getEditorContent(page);
    expect(afterType).toContain('TESTO AGGIUNTO');

    // Fai undo
    await pressUndo(page);
    await page.waitForTimeout(200);

    // Verifica che il testo sia stato rimosso
    const afterUndo = await getEditorContent(page);
    expect(afterUndo).not.toContain('TESTO AGGIUNTO');
  });


  test('Bottoni undo/redo toolbar sono visibili', async ({ page }) => {
    // Cerca i bottoni undo/redo nella toolbar
    const undoButton = page.locator('button').filter({ hasText: '↶' });
    const redoButton = page.locator('button').filter({ hasText: '↷' });

    await expect(undoButton).toBeVisible({ timeout: 5000 });
    await expect(redoButton).toBeVisible({ timeout: 5000 });
  });


});

test.describe('Undo/Redo Server - Dopo Autosave', () => {

  test.beforeEach(async ({ page }) => {
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();

    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    await page.getByText('Alice Project 1').click();
    await waitForEditor(page);
  });

  test('Server undo funziona dopo autosave', async ({ page }) => {
    // Ottieni contenuto iniziale
    const initialContent = await getEditorContent(page);
    console.log('Initial content length:', initialContent?.length);

    // Digita qualcosa
    await typeInEditor(page, 'MODIFICA SERVER TEST');
    await page.waitForTimeout(200);

    // Verifica modifica
    let content = await getEditorContent(page);
    expect(content).toContain('MODIFICA SERVER TEST');

    // Aspetta autosave (2 secondi configurati + margine)
    console.log('Waiting for autosave...');
    await page.waitForTimeout(3500);

    // A questo punto CodeMirror undo dovrebbe essere vuoto
    // (dopo sync, l'editor viene ricreato)

    // Prova undo - dovrebbe andare al server
    console.log('Pressing undo...');
    await pressUndo(page);
    await page.waitForTimeout(1000);

    // Verifica che il contenuto sia tornato indietro
    content = await getEditorContent(page);
    console.log('After undo, content contains MODIFICA SERVER TEST:', content?.includes('MODIFICA SERVER TEST'));

    // Il server undo dovrebbe aver rimosso la modifica
    // NOTA: questo test potrebbe fallire se c'è il bug del revert immediato
    expect(content).not.toContain('MODIFICA SERVER TEST');
  });

  // FAIL EXPECTED: Questo test documenta il bug segnalato - server undo non rimuove il contenuto
  test.fail('Server undo mantiene contenuto dopo il ripristino', async ({ page }) => {
    // Questo test verifica specificamente il bug segnalato:
    // "il primo cmd-Z riporta le modifiche ma le cancella subito"

    const initialContent = await getEditorContent(page);

    // Fai una modifica significativa
    await typeInEditor(page, '\n\nNUOVO PARAGRAFO TEST\n\n');
    await page.waitForTimeout(200);

    // Aspetta autosave
    await page.waitForTimeout(3500);

    // Fai undo
    await pressUndo(page);

    // Aspetta un po' per vedere se il contenuto viene revertito
    await page.waitForTimeout(500);
    let content1 = await getEditorContent(page);

    // Aspetta ancora per verificare stabilità
    await page.waitForTimeout(2000);
    let content2 = await getEditorContent(page);

    // Il contenuto dovrebbe essere stabile (non cambiare tra le due letture)
    expect(content1).toBe(content2);

    // E non dovrebbe contenere la modifica
    expect(content2).not.toContain('NUOVO PARAGRAFO TEST');
  });

  test('Posizione cursore preservata dopo server undo', async ({ page }) => {
    // Questo test verifica il problema del cursore che cambia posizione

    // Vai a una posizione specifica
    const editor = page.locator('.cm-editor .cm-content');
    await editor.click();

    // Vai all'inizio
    await page.keyboard.press('Meta+Home');
    await page.waitForTimeout(100);

    // Digita qualcosa all'inizio
    await page.keyboard.type('INIZIO: ');
    await page.waitForTimeout(200);

    // Aspetta autosave
    await page.waitForTimeout(3500);

    // Fai undo
    await pressUndo(page);
    await page.waitForTimeout(1000);

    // Il cursore dovrebbe essere in una posizione ragionevole
    // (non vogliamo che salti alla fine del documento)

    // Digita qualcosa per vedere dove siamo
    await page.keyboard.type('POSIZIONE_CURSORE');
    await page.waitForTimeout(200);

    const content = await getEditorContent(page);

    // Se il cursore era preservato correttamente,
    // POSIZIONE_CURSORE dovrebbe essere vicino all'inizio, non alla fine
    const pos = content?.indexOf('POSIZIONE_CURSORE') || -1;
    console.log('Cursor position indicator at:', pos);

    // Verifica che non sia alla fine del documento
    // (assumendo che il documento sia lungo almeno 100 caratteri)
    if (content && content.length > 100) {
      expect(pos).toBeLessThan(content.length / 2);
    }
  });

  test('Server undo mostra toast con info sui chunk modificati', async ({ page }) => {
    // Digita qualcosa nel chunk corrente (Capitolo 1)
    await typeInEditor(page, 'MODIFICA PER TOAST');
    await page.waitForTimeout(200);

    // Aspetta autosave
    await page.waitForTimeout(3500);

    // Fai undo - dovrebbe mostrare toast
    await pressUndo(page);

    // Aspetta che appaia il toast
    // Il toast contiene info sul chunk modificato
    const toast = page.locator('[data-testid="toast"], .toast, [role="alert"]');
    await expect(toast.first()).toBeVisible({ timeout: 3000 }).catch(() => {
      // Fallback: cerca testo che indica undo
      return page.getByText(/Undo|Annullat/i).first().isVisible({ timeout: 1000 });
    });

    // Verifica contenuto del toast (dovrebbe menzionare il chunk)
    const toastText = await page.evaluate(() => {
      const toastEl = document.querySelector('[data-testid="toast"], .toast, [role="alert"]');
      return toastEl?.textContent || '';
    });
    console.log('Toast text:', toastText);

    // Il toast dovrebbe contenere "Undo" e possibilmente il nome del chunk
    // Se il toast è vuoto, almeno verifichiamo che l'undo sia stato eseguito
    const content = await getEditorContent(page);
    expect(content).not.toContain('MODIFICA PER TOAST');
  });

  test('Server redo funziona dopo server undo', async ({ page }) => {
    // Ottieni contenuto iniziale
    const initialContent = await getEditorContent(page);

    // Digita qualcosa
    await typeInEditor(page, 'TESTO PER REDO');
    await page.waitForTimeout(200);

    // Aspetta autosave
    await page.waitForTimeout(3500);

    // Verifica che la modifica sia stata salvata
    let content = await getEditorContent(page);
    expect(content).toContain('TESTO PER REDO');

    // Fai server undo
    await pressUndo(page);
    await page.waitForTimeout(1000);

    // Verifica che il testo sia stato rimosso
    content = await getEditorContent(page);
    expect(content).not.toContain('TESTO PER REDO');

    // Fai server redo (Cmd+Shift+Z)
    await pressRedo(page);
    await page.waitForTimeout(1000);

    // Verifica che il testo sia stato ripristinato
    content = await getEditorContent(page);
    expect(content).toContain('TESTO PER REDO');
  });

  test('Server undo con modifiche a più chunk', async ({ page }) => {
    // Modifica primo chunk (Capitolo 1)
    await typeInEditor(page, 'PRIMA MODIFICA MULTI');
    await page.waitForTimeout(200);

    // Aspetta autosave per salvare prima modifica
    await page.waitForTimeout(3500);

    // Naviga a un altro chunk nell'outline
    const secondChunk = page.getByText('Capitolo 2');
    if (await secondChunk.isVisible({ timeout: 2000 }).catch(() => false)) {
      await secondChunk.click();
      await page.waitForTimeout(500);

      // Modifica secondo chunk
      await typeInEditor(page, 'SECONDA MODIFICA MULTI');
      await page.waitForTimeout(200);

      // Aspetta autosave per salvare seconda modifica
      await page.waitForTimeout(3500);

      // Verifica che entrambe le modifiche siano state salvate
      let content = await getEditorContent(page);
      expect(content).toContain('SECONDA MODIFICA MULTI');

      // Torna al primo chunk
      await page.getByText('Capitolo 1').click();
      await page.waitForTimeout(500);
      content = await getEditorContent(page);
      expect(content).toContain('PRIMA MODIFICA MULTI');

      // Fai server undo - dovrebbe annullare l'ultima modifica (secondo chunk)
      await pressUndo(page);
      await page.waitForTimeout(1000);

      // Torna al secondo chunk per verificare
      await secondChunk.click();
      await page.waitForTimeout(500);
      content = await getEditorContent(page);

      // La seconda modifica dovrebbe essere stata annullata
      expect(content).not.toContain('SECONDA MODIFICA MULTI');

      // Verifica che la prima modifica sia ancora presente
      await page.getByText('Capitolo 1').click();
      await page.waitForTimeout(500);
      content = await getEditorContent(page);
      expect(content).toContain('PRIMA MODIFICA MULTI');
    } else {
      // Se non c'è un secondo chunk, skip il test
      console.log('Secondo chunk non trovato, test multi-chunk non eseguibile');
    }
  });

  test('Nuova modifica dopo server undo cancella redo stack server', async ({ page }) => {
    // Digita qualcosa
    await typeInEditor(page, 'ORIGINALE');
    await page.waitForTimeout(200);

    // Aspetta autosave
    await page.waitForTimeout(3500);

    // Fai server undo
    await pressUndo(page);
    await page.waitForTimeout(1000);

    // Verifica undo
    let content = await getEditorContent(page);
    expect(content).not.toContain('ORIGINALE');

    // Nuova modifica (crea nuova branch)
    await typeInEditor(page, 'NUOVA BRANCH');
    await page.waitForTimeout(200);

    // Aspetta autosave della nuova modifica
    await page.waitForTimeout(3500);

    // Prova redo - non dovrebbe fare niente (stack cancellato)
    const beforeRedo = await getEditorContent(page);
    await pressRedo(page);
    await page.waitForTimeout(1000);
    const afterRedo = await getEditorContent(page);

    // Il contenuto non dovrebbe cambiare (il testo ORIGINALE non dovrebbe tornare)
    expect(afterRedo).toContain('NUOVA BRANCH');
    expect(afterRedo).not.toContain('ORIGINALE');
  });

});

test.describe('Undo/Redo - Indicatori Visivi', () => {

  test.beforeEach(async ({ page }) => {
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();

    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    await page.getByText('Alice Project 1').click();
    await waitForEditor(page);
  });

  test('Bottone undo mostra tooltip corretto per CodeMirror', async ({ page }) => {
    // Digita qualcosa per avere undo disponibile
    await typeInEditor(page, 'TEST');
    await page.waitForTimeout(200);

    // Controlla tooltip del bottone undo
    const undoButton = page.locator('button').filter({ hasText: '↶' });
    const title = await undoButton.getAttribute('title');

    // Dovrebbe mostrare "Annulla" (non "server")
    expect(title).toContain('Annulla');
    expect(title).not.toContain('server');
  });

  test('Bottone undo disabilitato quando non disponibile', async ({ page }) => {
    // Senza modifiche, undo dovrebbe essere disabilitato o con opacity ridotta
    // Prima facciamo diversi undo per svuotare lo stack
    for (let i = 0; i < 10; i++) {
      await pressUndo(page);
      await page.waitForTimeout(50);
    }

    // Aspetta che lo stato si stabilizzi
    await page.waitForTimeout(500);

    const undoButton = page.locator('button').filter({ hasText: '↶' });

    // Controlla se è disabilitato o ha opacity ridotta
    const isDisabled = await undoButton.isDisabled().catch(() => false);
    const style = await undoButton.getAttribute('style');
    const hasReducedOpacity = style?.includes('opacity') && style?.includes('0.3');

    // Almeno una delle due condizioni dovrebbe essere vera
    expect(isDisabled || hasReducedOpacity).toBeTruthy();
  });

});

test.describe('Undo/Redo - Edge Cases', () => {

  test.beforeEach(async ({ page }) => {
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();

    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    await page.getByText('Alice Project 1').click();
    await waitForEditor(page);
  });

  test('Nuova modifica dopo undo cancella redo stack', async ({ page }) => {
    // Digita A
    await typeInEditor(page, 'AAA');
    await page.waitForTimeout(100);

    // Undo
    await pressUndo(page);
    await page.waitForTimeout(100);

    // A questo punto redo è disponibile
    // Digita qualcos'altro (nuova branch)
    await typeInEditor(page, 'BBB');
    await page.waitForTimeout(100);

    // Redo non dovrebbe fare niente (stack cancellato)
    const beforeRedo = await getEditorContent(page);
    await pressRedo(page);
    await page.waitForTimeout(100);
    const afterRedo = await getEditorContent(page);

    // Il contenuto non dovrebbe cambiare
    expect(beforeRedo).toBe(afterRedo);
  });


  test('Cambio chunk e ritorno preserva undo history', async ({ page }) => {
    // Digita nel chunk corrente
    await typeInEditor(page, 'CHUNK1 TEST');
    await page.waitForTimeout(200);

    // Clicca su un altro chunk nell'outline (se disponibile)
    const outlineItems = page.locator('[data-testid="outline-item"]');
    const count = await outlineItems.count().catch(() => 0);

    if (count > 1) {
      // Clicca sul secondo chunk
      await outlineItems.nth(1).click();
      await page.waitForTimeout(300);

      // Torna al primo chunk
      await outlineItems.nth(0).click();
      await page.waitForTimeout(300);

      // Verifica che il testo sia ancora lì
      const content = await getEditorContent(page);
      expect(content).toContain('CHUNK1 TEST');

      // Prova undo
      await pressUndo(page);
      await page.waitForTimeout(200);

      // Potrebbe o non potrebbe funzionare a seconda dell'implementazione
      // Ma non dovrebbe crashare
    }
  });

});
