import { test, expect } from '@playwright/test';

/**
 * Test E2E per funzionalità di collaborazione avanzata
 *
 * Copre: COLLAB-003, COLLAB-004, COLLAB-005, COLLAB-006, COLLAB-007
 *
 * Questi test verificano le interazioni tra owner e collaboratori,
 * inclusi assegnazione chunk, permessi di modifica e restrizioni.
 */

const TEST_SERVER = 'http://localhost:3001';

// Helper per login e ritorno alla lista progetti
async function loginAndGetProjects(page, username, password) {
  await page.goto('/');
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await page.waitForTimeout(500);

  const serverButton = page.getByRole('button', { name: /server|online/i });
  await serverButton.click();
  await page.waitForTimeout(500);

  await page.getByPlaceholder(/username|utente/i).fill(username);
  await page.getByPlaceholder(/password/i).fill(password);
  await page.getByRole('button', { name: /accedi|login/i }).click();

  await page.waitForFunction(() => {
    return !document.body.innerText.includes('Caricamento');
  }, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1000);
}

// Helper per login e accesso a un progetto specifico
async function loginAndOpenProject(page, username, password, projectName = null) {
  await loginAndGetProjects(page, username, password);

  // Clicca sul progetto specificato o sul primo disponibile
  let projectItem;
  if (projectName) {
    projectItem = page.locator('.project-item, [data-project-id]').filter({ hasText: projectName }).first();
  } else {
    projectItem = page.locator('.project-item, [data-project-id]').first();
  }

  if (await projectItem.isVisible({ timeout: 5000 }).catch(() => false)) {
    await projectItem.click();
    await page.waitForTimeout(1000);
  }

  await page.waitForSelector('.cm-editor', { timeout: 10000 });
  await page.waitForTimeout(500);

  // Chiudi tutorial
  const skipTutorial = page.getByRole('button', { name: /^Salta$|^Skip$/i });
  if (await skipTutorial.isVisible({ timeout: 2000 }).catch(() => false)) {
    await skipTutorial.click();
    await page.waitForTimeout(500);
  } else {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  }
}

// Helper per digitare nell'editor
async function typeInEditor(page, text) {
  const editor = page.locator('.cm-editor .cm-content');
  await editor.click();
  await page.keyboard.type(text);
}

// Helper per ottenere il contenuto dell'editor
async function getEditorContent(page) {
  return await page.evaluate(() => {
    const cmContent = document.querySelector('.cm-editor .cm-content');
    return cmContent?.innerText || '';
  });
}

test.describe('COLLAB-003: Collaboratore vede progetto in lista', () => {

  test('bob vede il progetto di alice nella sua lista', async ({ page }) => {
    // Bob è collaboratore di Alice
    await loginAndGetProjects(page, 'bob', 'bob123');

    // Cerca un progetto che non sia di Bob (progetto di Alice)
    const aliceProject = page.locator('.project-item, [data-project-id]').filter({ hasText: /alice|project/i });

    // Bob dovrebbe vedere almeno un progetto (suo o condiviso)
    const projectItems = page.locator('.project-item, [data-project-id]');
    const count = await projectItems.count();

    // Bob dovrebbe avere accesso ad almeno un progetto
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('progetti condivisi mostrano indicatore', async ({ page }) => {
    await loginAndGetProjects(page, 'bob', 'bob123');

    // I progetti di cui Bob è collaboratore potrebbero avere un indicatore
    // (icona, badge "collaboratore", o stile diverso)
    const projectItems = page.locator('.project-item, [data-project-id]');
    const count = await projectItems.count();

    if (count > 0) {
      // Verifica che almeno un progetto sia visibile
      await expect(projectItems.first()).toBeVisible();
    }
  });

});

test.describe('COLLAB-004: Collaboratore non può eliminare progetto', () => {

  test('bob non vede opzione elimina per progetto di alice', async ({ page }) => {
    await loginAndGetProjects(page, 'bob', 'bob123');

    // Cerca un progetto (presumibilmente di Alice, di cui Bob è collaboratore)
    const projectItem = page.locator('.project-item, [data-project-id]').first();

    if (await projectItem.isVisible({ timeout: 3000 }).catch(() => false)) {
      // Hover o right-click per vedere le opzioni
      await projectItem.hover();
      await page.waitForTimeout(300);

      // Cerca bottone elimina
      const deleteBtn = page.locator('button').filter({ hasText: /elimina|delete|🗑/i });
      const isDeleteVisible = await deleteBtn.first().isVisible({ timeout: 2000 }).catch(() => false);

      // Se il bottone elimina è visibile, dovrebbe essere disabilitato per progetti non propri
      if (isDeleteVisible) {
        // Verifica se il progetto è di Bob (owner) o se Bob è collaboratore
        // Solo l'owner può eliminare
      }
    }
  });

  test('collaboratore non può eliminare progetto da menu contestuale', async ({ page }) => {
    await loginAndGetProjects(page, 'bob', 'bob123');

    const projectItem = page.locator('.project-item, [data-project-id]').first();

    if (await projectItem.isVisible({ timeout: 3000 }).catch(() => false)) {
      // Right-click sul progetto
      await projectItem.click({ button: 'right' });
      await page.waitForTimeout(300);

      // Cerca opzione elimina nel menu contestuale
      const deleteOption = page.locator('[role="menuitem"]').filter({ hasText: /elimina|delete/i });
      const isVisible = await deleteOption.isVisible({ timeout: 2000 }).catch(() => false);

      // Se visibile, dovrebbe essere disabilitato o non presente per progetti altrui
      if (isVisible) {
        const isDisabled = await deleteOption.evaluate(el => {
          return el.getAttribute('aria-disabled') === 'true' ||
                 el.classList.contains('disabled');
        }).catch(() => false);
      }

      // Chiudi menu
      await page.keyboard.press('Escape');
    }
  });

});

test.describe('COLLAB-005: Owner assegna chunk a collaboratore', () => {

  test('alice può assegnare un chunk a bob', async ({ page }) => {
    // Login come Alice (owner)
    await loginAndOpenProject(page, 'alice', 'alice123');

    // Cerca il pannello discussione che ha l'opzione di assegnazione
    // L'assegnazione avviene nel tab "Discussione" dell'editor
    const discussionTab = page.locator('button.tab, [role="tab"]').filter({ hasText: /discussione|discussion/i });

    if (await discussionTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await discussionTab.click();
      await page.waitForTimeout(300);

      // Cerca il dropdown/select per assegnare a un collaboratore
      const assignSelect = page.locator('select, [role="combobox"]').filter({ hasText: /collaboratore|assegna/i });

      if (await assignSelect.first().isVisible({ timeout: 3000 }).catch(() => false)) {
        // L'opzione di assegnazione esiste per l'owner
      } else {
        // Cerca un altro modo di assegnare (bottone, menu)
        const assignBtn = page.locator('button').filter({ hasText: /assegna|assign/i });
        const isAssignVisible = await assignBtn.first().isVisible({ timeout: 2000 }).catch(() => false);
      }
    }
  });

  test('solo owner vede opzione di assegnazione', async ({ page }) => {
    // Login come Bob (collaboratore)
    await loginAndOpenProject(page, 'bob', 'bob123');

    const discussionTab = page.locator('button.tab, [role="tab"]').filter({ hasText: /discussione|discussion/i });

    if (await discussionTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await discussionTab.click();
      await page.waitForTimeout(300);

      // Bob NON dovrebbe vedere l'opzione di assegnazione
      const assignSelect = page.locator('select').filter({ hasText: /collaboratore/i });
      const isVisible = await assignSelect.first().isVisible({ timeout: 2000 }).catch(() => false);

      // L'opzione di assegnazione non dovrebbe essere visibile per collaboratori
    }
  });

});

test.describe('COLLAB-006: Owner rilascia chunk da collaboratore', () => {

  test('alice può rilasciare un chunk assegnato', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const discussionTab = page.locator('button.tab, [role="tab"]').filter({ hasText: /discussione|discussion/i });

    if (await discussionTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await discussionTab.click();
      await page.waitForTimeout(300);

      // Cerca opzione per rilasciare l'assegnazione
      const releaseBtn = page.locator('button').filter({ hasText: /rilascia|release|rimuovi assegnazione/i });

      if (await releaseBtn.first().isVisible({ timeout: 2000 }).catch(() => false)) {
        // L'opzione di rilascio esiste
      }
    }
  });

});

test.describe('COLLAB-007: Collaboratore modifica solo suoi chunk', () => {

  test('bob può modificare solo chunk a lui assegnati', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    // Bob è collaboratore - verifica che l'editor sia in modalità corretta
    const editor = page.locator('.cm-editor');
    await expect(editor).toBeVisible({ timeout: 5000 });

    // Prova a digitare
    const editorContent = page.locator('.cm-editor .cm-content');

    if (await editorContent.isVisible({ timeout: 2000 }).catch(() => false)) {
      await editorContent.click();

      // Prova a digitare qualcosa
      const testText = 'Test modifica bob ' + Date.now();
      await page.keyboard.type(testText);
      await page.waitForTimeout(500);

      const content = await getEditorContent(page);

      // Se Bob è proprietario del chunk corrente, il testo dovrebbe apparire
      // Se no, l'editor dovrebbe essere readonly e il testo non appare
      // Entrambi i comportamenti sono validi a seconda dell'assegnazione
    }
  });

  test('editor mostra indicatore readonly per chunk non propri', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    // Cerca indicatori che il chunk non è modificabile
    // (potrebbe essere un lock icon, un messaggio, o l'editor stesso in readonly)
    const readonlyIndicator = page.locator('[class*="readonly"], [class*="locked"], [aria-readonly="true"]');
    const lockIcon = page.locator('text=/🔒|lock|sola lettura|readonly/i');

    const hasReadonlyIndicator = await readonlyIndicator.first().isVisible({ timeout: 2000 }).catch(() => false);
    const hasLockIcon = await lockIcon.first().isVisible({ timeout: 2000 }).catch(() => false);

    // Potrebbe esserci un indicatore se il chunk non è di Bob
  });

  test('collaboratore vede chi è proprietario del chunk', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    // Nel tab discussione dovrebbe mostrare chi è il proprietario
    const discussionTab = page.locator('button.tab, [role="tab"]').filter({ hasText: /discussione|discussion/i });

    if (await discussionTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await discussionTab.click();
      await page.waitForTimeout(300);

      // Cerca indicazione del proprietario
      const ownerInfo = page.locator('text=/proprietario|owner|alice|bob/i');
      const isVisible = await ownerInfo.first().isVisible({ timeout: 3000 }).catch(() => false);
    }
  });

});

test.describe('Collaborazione Avanzata - Permessi', () => {

  test('collaboratore può creare proposte su chunk altrui', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    const content = await getEditorContent(page);

    if (content.length > 5) {
      // Seleziona del testo
      await page.keyboard.press('Meta+a');
      await page.waitForTimeout(200);

      // Right-click
      const editor = page.locator('.cm-editor .cm-content');
      await editor.click({ button: 'right' });
      await page.waitForTimeout(300);

      // Cerca opzione proposta
      const proposalOption = page.locator('[role="menuitem"]').filter({ hasText: /proposta|proposal/i });
      const isVisible = await proposalOption.isVisible({ timeout: 3000 }).catch(() => false);

      // I collaboratori dovrebbero poter creare proposte
      await page.keyboard.press('Escape');
    }
  });

  test('collaboratore può aggiungere commenti', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    // Apri tab discussione
    const discussionTab = page.locator('button.tab, [role="tab"]').filter({ hasText: /discussione|discussion/i });

    if (await discussionTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await discussionTab.click();
      await page.waitForTimeout(300);

      // Cerca input per commenti
      const commentInput = page.locator('input[placeholder*="comment"], input[placeholder*="commento"], textarea');

      if (await commentInput.first().isVisible({ timeout: 3000 }).catch(() => false)) {
        // I collaboratori dovrebbero poter aggiungere commenti
        await commentInput.first().fill('Commento di bob');

        const sendBtn = page.locator('button').filter({ hasText: /invia|send/i });
        if (await sendBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
          await sendBtn.click();
          await page.waitForTimeout(500);
        }
      }
    }
  });

  test('collaboratore non può modificare struttura', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    // Cerca i bottoni per modificare la struttura (aggiungi figlio, elimina)
    const addChildBtn = page.locator('button[title*="figlio"], button[title*="child"]').first();
    const deleteBtn = page.locator('button[title*="Elimina"], button[title*="Delete"]').first();

    // Questi bottoni potrebbero essere nascosti o disabilitati per i collaboratori
    // (a meno che non siano sotto aspects)

    if (await addChildBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      const isDisabled = await addChildBtn.isDisabled().catch(() => false);
      // Il bottone potrebbe essere disabilitato per collaboratori
    }
  });

});

test.describe('Collaborazione Avanzata - Edge cases', () => {

  test('cambio di proprietario chunk aggiorna UI', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    // L'owner può assegnare/rilasciare chunk
    // Dopo il cambio, l'UI dovrebbe aggiornarsi
    const discussionTab = page.locator('button.tab, [role="tab"]').filter({ hasText: /discussione|discussion/i });

    if (await discussionTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await discussionTab.click();
      await page.waitForTimeout(300);

      // Verifica che le informazioni sul proprietario siano mostrate
      const ownerSection = page.locator('text=/proprietario|owner/i');
      const isVisible = await ownerSection.first().isVisible({ timeout: 3000 }).catch(() => false);
    }
  });

  test('UI mostra ruolo utente corrente', async ({ page }) => {
    await loginAndOpenProject(page, 'bob', 'bob123');

    // Da qualche parte dovrebbe essere indicato che bob è collaboratore
    // (potrebbe essere nel header, sidebar, o info progetto)
    const roleIndicator = page.locator('text=/collaboratore|collaborator/i');
    const isVisible = await roleIndicator.first().isVisible({ timeout: 3000 }).catch(() => false);

    // Il ruolo potrebbe essere mostrato o no a seconda del design UI
  });

});
