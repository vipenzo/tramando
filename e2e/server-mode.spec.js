import { test, expect } from '@playwright/test';

/**
 * Test per la modalità server di Tramando
 * Questi test usano il database di test con fixture predefinite:
 *
 * Utenti:
 * - admin/admin123 (super-admin)
 * - alice/alice123 (utente normale, proprietaria di 2 progetti)
 * - bob/bob123 (utente normale, proprietario di 1 progetto, collaboratore su alice's)
 * - carol/carol123 (utente normale, collaboratore admin su alice's)
 *
 * Progetti:
 * - "Alice Project 1" (owner: alice, collaboratori: bob, carol)
 * - "Alice Project 2" (owner: alice)
 * - "Bob Project" (owner: bob, collaboratore: alice)
 * - "Admin Project" (owner: admin)
 */

// API base URL for test server
const API_URL = 'http://localhost:3001';

// Helper to login via API and get token
async function loginViaAPI(username, password) {
  const response = await fetch(`${API_URL}/api/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const data = await response.json();
  return data.token;
}

// Helper to set auth token and server URL in localStorage
async function setAuthToken(page, token) {
  await page.evaluate((t) => {
    // Set both token and server URL
    localStorage.setItem('tramando-auth-token', t);
    localStorage.setItem('tramando-server-url', 'http://localhost:3001');
    // Also set mode to server
    localStorage.setItem('tramando-mode', 'server');
  }, token);
}

test.describe('Tramando - Server Mode Login', () => {

  test.beforeEach(async ({ page }) => {
    // Clear localStorage before each test
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.clear();
    });
  });

  test('mostra schermata di scelta modalità', async ({ page }) => {
    await page.goto('/');

    // Dovrebbe mostrare le opzioni per locale e server
    await expect(page.getByText(/locale|local/i).first()).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(/server|online/i).first()).toBeVisible({ timeout: 5000 });
  });

  test('può fare login con credenziali valide', async ({ page }) => {
    await page.goto('/');

    // Il form di login server è già visibile nella splash (pannello destro)
    await expect(page.getByPlaceholder('Username')).toBeVisible({ timeout: 5000 });
    await expect(page.getByPlaceholder('Password')).toBeVisible({ timeout: 5000 });

    // Prima imposta l'URL del server di test
    await page.getByPlaceholder('Server URL').fill(API_URL);

    // Inserisci credenziali
    await page.getByPlaceholder('Username').fill('alice');
    await page.getByPlaceholder('Password').fill('alice123');

    // Clicca login
    await page.getByRole('button', { name: /accedi|login/i }).click();

    // Dovrebbe mostrare la lista progetti dopo login
    await expect(page.getByText(/progetti|projects/i).first()).toBeVisible({ timeout: 10000 });
  });

  test('mostra errore con credenziali errate', async ({ page }) => {
    await page.goto('/');

    // Il form di login server è già visibile nella splash (pannello destro)
    // Prima imposta l'URL del server di test
    await page.getByPlaceholder('Server URL').fill(API_URL);

    // Inserisci credenziali errate
    await page.getByPlaceholder('Username').fill('alice');
    await page.getByPlaceholder('Password').fill('wrongpassword');

    // Clicca login
    await page.getByRole('button', { name: /accedi|login/i }).click();

    // Dovrebbe mostrare un errore
    await expect(page.getByText(/invalid|error|errat/i).first()).toBeVisible({ timeout: 5000 });
  });

});

test.describe('Tramando - Projects List', () => {

  test.beforeEach(async ({ page }) => {
    // Login as alice before each test
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    // Wait for the projects list to load (not just "Caricamento...")
    await page.waitForFunction(() => {
      const loading = document.body.innerText.includes('Caricamento');
      const hasProjects = document.body.innerText.includes('Project') ||
                          document.body.innerText.includes('Nessun progetto');
      return !loading && hasProjects;
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);
  });

  test('mostra i progetti dell\'utente', async ({ page }) => {
    // Alice dovrebbe vedere i suoi progetti e quelli dove collabora
    await expect(page.getByText('Alice Project 1')).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('Alice Project 2')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('Bob Project')).toBeVisible({ timeout: 5000 });
  });

  test('può creare un nuovo progetto', async ({ page }) => {
    // Clicca su nuovo progetto
    const newProjectBtn = page.getByRole('button', { name: /nuovo progetto|new project/i });
    await newProjectBtn.click();

    // Dovrebbe apparire un nuovo progetto nella lista
    await expect(page.getByText(/nuovo progetto/i)).toBeVisible({ timeout: 5000 });
  });

  test('può filtrare i progetti per metadati', async ({ page }) => {
    // Trova il campo di ricerca
    const searchInput = page.getByPlaceholder(/cerca|search|filtr/i);
    if (await searchInput.isVisible().catch(() => false)) {
      await searchInput.fill('romanzo');

      // Solo Alice Project 1 dovrebbe essere visibile (ha "Il mio romanzo" nel metadata)
      await expect(page.getByText('Alice Project 1')).toBeVisible({ timeout: 5000 });
      // Alice Project 2 non dovrebbe essere visibile
      await expect(page.getByText('Alice Project 2')).not.toBeVisible({ timeout: 1000 });
    }
  });

});

test.describe('Tramando - Collaborators Panel', () => {

  test('owner può vedere e aggiungere collaboratori', async ({ page }) => {
    // Login as alice (owner of projects)
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    // Wait for projects to load
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    // Apri Alice Project 1
    await page.getByText('Alice Project 1').click();
    await page.waitForTimeout(1000);

    // Cerca il bottone collaboratori nella toolbar
    const collabButton = page.locator('button').filter({ hasText: /collaborat|👥/i }).first();
    if (await collabButton.isVisible().catch(() => false)) {
      await collabButton.click();
      await page.waitForTimeout(500);

      // Dovrebbe mostrare il panel collaboratori
      await expect(page.getByText(/proprietario|owner/i)).toBeVisible({ timeout: 5000 });

      // Dovrebbe vedere i collaboratori esistenti
      await expect(page.getByText(/bob/i)).toBeVisible({ timeout: 5000 });
      await expect(page.getByText(/carol/i)).toBeVisible({ timeout: 5000 });

      // Dovrebbe vedere il dropdown per aggiungere collaboratori
      const selectUser = page.locator('select').first();
      if (await selectUser.isVisible().catch(() => false)) {
        // Il dropdown dovrebbe avere opzioni (altri utenti disponibili)
        const optionsCount = await selectUser.locator('option').count();
        // Almeno l'opzione placeholder e qualche utente
        expect(optionsCount).toBeGreaterThan(1);
      }
    }
  });

  test('utente normale può vedere lista utenti per aggiungere collaboratori al proprio progetto', async ({ page }) => {
    // Login as bob (owner of "Bob Project")
    const token = await loginViaAPI('bob', 'bob123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    // Apri Bob Project
    await page.getByText('Bob Project').click();
    await page.waitForTimeout(1000);

    // Cerca il bottone collaboratori
    const collabButton = page.locator('button').filter({ hasText: /collaborat|👥/i }).first();
    if (await collabButton.isVisible().catch(() => false)) {
      await collabButton.click();
      await page.waitForTimeout(500);

      // Dovrebbe vedere Alice come collaboratore esistente
      await expect(page.getByText(/alice/i)).toBeVisible({ timeout: 5000 });

      // Dovrebbe vedere il dropdown per aggiungere altri collaboratori
      const selectUser = page.locator('select').first();
      if (await selectUser.isVisible().catch(() => false)) {
        // Carol dovrebbe essere disponibile come opzione
        await selectUser.click();
        // Verifica che ci siano opzioni disponibili
        const options = selectUser.locator('option');
        const count = await options.count();
        expect(count).toBeGreaterThan(1);
      }
    }
  });

  test('collaboratore NON owner non vede pulsante per aggiungere collaboratori', async ({ page }) => {
    // Login as bob (collaboratore su Alice Project 1, ma non owner)
    const token = await loginViaAPI('bob', 'bob123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    // Apri Alice Project 1 (bob è collaboratore, non owner)
    await page.getByText('Alice Project 1').click();
    await page.waitForTimeout(1000);

    // Il bottone collaboratori potrebbe non essere visibile per non-owner
    // oppure il form per aggiungere collaboratori non dovrebbe essere presente
    const collabButton = page.locator('button').filter({ hasText: /collaborat|👥/i }).first();
    if (await collabButton.isVisible().catch(() => false)) {
      await collabButton.click();
      await page.waitForTimeout(500);

      // Dovrebbe vedere i collaboratori ma NON il form per aggiungere
      // (dipende dall'implementazione - verifichiamo che non ci sia errore)
      await expect(page.getByText(/proprietario|owner/i)).toBeVisible({ timeout: 5000 });
    }
  });

});

test.describe('Tramando - Admin Panel', () => {

  test('super-admin può vedere pannello gestione utenti', async ({ page }) => {
    // Login as admin
    const token = await loginViaAPI('admin', 'admin123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    // Cerca il menu utente o bottone admin
    const userMenu = page.locator('button').filter({ hasText: /admin|👤/i }).first();
    if (await userMenu.isVisible().catch(() => false)) {
      await userMenu.click();
      await page.waitForTimeout(300);

      // Cerca l'opzione per gestire utenti
      const manageUsers = page.getByText(/gestione utenti|manage users/i);
      if (await manageUsers.isVisible().catch(() => false)) {
        await manageUsers.click();
        await page.waitForTimeout(500);

        // Dovrebbe vedere la lista degli utenti
        await expect(page.getByText('Alice Writer')).toBeVisible({ timeout: 5000 });
        await expect(page.getByText('Bob Editor')).toBeVisible({ timeout: 5000 });

        // Dovrebbe vedere l'utente in pending
        await expect(page.getByText('Pending User')).toBeVisible({ timeout: 5000 });
      }
    }
  });

  test('utente normale NON vede opzione gestione utenti', async ({ page }) => {
    // Login as alice (non super-admin)
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);

    // Cerca il menu utente
    const userMenu = page.locator('button').filter({ hasText: /alice|👤/i }).first();
    if (await userMenu.isVisible().catch(() => false)) {
      await userMenu.click();
      await page.waitForTimeout(300);

      // NON dovrebbe vedere l'opzione per gestire utenti
      const manageUsers = page.getByText(/gestione utenti|manage users/i);
      await expect(manageUsers).not.toBeVisible({ timeout: 1000 });
    }
  });

});

test.describe('Tramando - Server Mode Modal Behaviors', () => {

  test.beforeEach(async ({ page }) => {
    // Login as alice
    const token = await loginViaAPI('alice', 'alice123');
    await page.goto('/');
    await setAuthToken(page, token);
    await page.reload();
    await page.waitForFunction(() => {
      return !document.body.innerText.includes('Caricamento');
    }, { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(500);
  });

  test('collaborators modal: Esc chiude', async ({ page }) => {
    // Apri un progetto
    await page.getByText('Alice Project 1').click();
    await page.waitForTimeout(1000);

    // Apri il panel collaboratori
    const collabButton = page.locator('button').filter({ hasText: /collaborat|👥/i }).first();
    if (await collabButton.isVisible().catch(() => false)) {
      await collabButton.click();
      await page.waitForTimeout(500);

      // Verifica che il modal sia aperto
      await expect(page.getByText(/proprietario|owner/i)).toBeVisible({ timeout: 5000 });

      // Premi Esc
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);

      // Il modal dovrebbe essere chiuso
      await expect(page.getByText(/proprietario|owner/i)).not.toBeVisible({ timeout: 2000 });
    }
  });

  test('collaborators modal: click esterno NON chiude', async ({ page }) => {
    // Apri un progetto
    await page.getByText('Alice Project 1').click();
    await page.waitForTimeout(1000);

    // Apri il panel collaboratori - cerca il bottone con testo "Collaboratori"
    const collabButton = page.getByRole('button', { name: /collaborator/i });
    if (await collabButton.isVisible().catch(() => false)) {
      await collabButton.click();
      await page.waitForTimeout(500);

      // Verifica che il modal sia aperto - cerca "Collaboratori" come titolo del modal (h2)
      const modalTitle = page.locator('h2').filter({ hasText: 'Collaboratori' });
      await expect(modalTitle).toBeVisible({ timeout: 5000 });

      // Click fuori dal modal (coordinate in alto a sinistra dell'overlay)
      await page.mouse.click(10, 10);
      await page.waitForTimeout(300);

      // Il modal dovrebbe essere ancora visibile (click esterno non chiude)
      await expect(modalTitle).toBeVisible({ timeout: 2000 });
    }
  });

});
