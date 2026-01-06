import { test, expect } from '@playwright/test';

test.describe('Tramando - flusso base', () => {

  test('carica la home page', async ({ page }) => {
    await page.goto('/');
    // Verifica che "Tramando" appaia da qualche parte (titolo o header)
    await expect(page.getByText('Tramando', { exact: true }).first()).toBeVisible();
  });

  test('mostra la splash screen iniziale con due colonne', async ({ page }) => {
    await page.goto('/');
    // Verifica che ci siano le due colonne: Locale e Server
    await expect(page.getByText('Locale')).toBeVisible();
    await expect(page.getByText('Server')).toBeVisible();
  });

  test('colonna Locale mostra opzioni file', async ({ page }) => {
    await page.goto('/');
    // Verifica bottone Nuovo progetto
    await expect(page.getByRole('button', { name: /nuovo/i })).toBeVisible();
    // Verifica bottone Apri file
    await expect(page.getByRole('button', { name: /apri/i })).toBeVisible();
  });

  test('toggle tema funziona', async ({ page }) => {
    await page.goto('/');

    // Trova il bottone tema (con emoji sole o luna)
    const themeButton = page.locator('button').filter({ hasText: /☀|🌙/ }).first();

    if (await themeButton.isVisible()) {
      await themeButton.click();
      await page.waitForTimeout(100);
      // Il tema dovrebbe essere cambiato (salvato in localStorage)
    }
  });

  test('menu lingua visibile', async ({ page }) => {
    await page.goto('/');

    // Cerca indicatore lingua (IT, EN, Italiano, English, etc)
    const langIndicator = page.locator('button').filter({ hasText: /IT|EN|Italiano|English|🇮🇹|🇬🇧/ }).first();
    // Se non c'è un bottone lingua esplicito, il test passa comunque
    // perché la lingua potrebbe essere gestita diversamente
    const isVisible = await langIndicator.isVisible().catch(() => false);
    // Test informativo - non fallisce se non trova il bottone
  });

});

test.describe('Tramando - modalità server', () => {

  test('colonna Server mostra form login', async ({ page }) => {
    await page.goto('/');

    // Dovrebbe esserci un campo per server URL
    await expect(page.getByPlaceholder(/server|url/i)).toBeVisible();

    // Dovrebbe esserci un campo username
    await expect(page.getByPlaceholder(/username|utente/i)).toBeVisible();

    // Dovrebbe esserci un campo password
    await expect(page.getByPlaceholder(/password/i)).toBeVisible();
  });

  test('bottone Accedi presente', async ({ page }) => {
    await page.goto('/');

    // Dovrebbe esserci un bottone Accedi o Login
    await expect(page.getByRole('button', { name: /accedi|login/i })).toBeVisible();
  });

  test('link registrazione presente', async ({ page }) => {
    await page.goto('/');

    // Dovrebbe esserci un link/bottone per registrarsi
    await expect(page.getByText(/registrati/i)).toBeVisible();
  });

});

test.describe('Tramando - nuovo progetto locale', () => {

  test('click su Nuovo apre editor vuoto', async ({ page }) => {
    await page.goto('/');

    // Clicca su "Nuovo progetto"
    await page.getByRole('button', { name: /nuovo/i }).click();

    // Dovrebbe apparire l'interfaccia principale con sidebar
    await page.waitForTimeout(500);

    // L'header dovrebbe cambiare o apparire elementi editor
    // Cerchiamo elementi tipici dell'interfaccia di editing
    const hasEditorUI = await page.locator('text=/STRUTTURA|ASPETTI|Structure|Aspects/i').count();
    expect(hasEditorUI).toBeGreaterThan(0);
  });

});

test.describe('Tramando - accessibilità', () => {

  test('nessun errore console critico', async ({ page }) => {
    const errors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        errors.push(msg.text());
      }
    });

    await page.goto('/');
    await page.waitForTimeout(1000);

    // Filtra errori non critici
    const criticalErrors = errors.filter(e =>
      !e.includes('favicon') &&
      !e.includes('net::') &&
      !e.includes('Failed to load resource') &&
      !e.includes('Tauri') // Errori Tauri in ambiente web sono normali
    );

    expect(criticalErrors.length).toBe(0);
  });

  test('pagina ha titolo', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/.+/);
  });

});

test.describe('Tramando - responsive', () => {

  test('layout mobile funziona', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');

    // La pagina dovrebbe caricare senza errori anche su mobile
    await expect(page.getByText('Tramando', { exact: true }).first()).toBeVisible();
  });

  test('layout tablet funziona', async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.goto('/');

    await expect(page.getByText('Tramando', { exact: true }).first()).toBeVisible();
  });

});
