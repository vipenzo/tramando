import { test, expect } from '@playwright/test';

/**
 * Test E2E per funzionalità di autenticazione aggiuntive
 *
 * Copre: AUTH-001, AUTH-005, AUTH-006
 *
 * NOTA: I test base di autenticazione sono già in server-mode.spec.js:
 * - AUTH-003: Login con credenziali valide
 * - AUTH-004: Login con credenziali errate
 * - AUTH-007: Permessi collaboratore
 *
 * I test AUTH-009 (single-login), AUTH-010, AUTH-012, AUTH-013
 * sono stati spostati ai test manuali.
 */

test.describe('AUTH-001: Registrazione', () => {

  test('form di login è visibile dalla splash server', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.waitForTimeout(500);

    // Clicca su modalità server
    const serverButton = page.getByRole('button', { name: /server|online/i });
    if (await serverButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await serverButton.click();
      await page.waitForTimeout(500);

      // Dovrebbe mostrare il form di login
      const loginField = page.getByPlaceholder(/username|utente/i);
      await expect(loginField).toBeVisible({ timeout: 5000 });

      const passwordField = page.getByPlaceholder(/password/i);
      await expect(passwordField).toBeVisible({ timeout: 5000 });

      const loginBtn = page.getByRole('button', { name: /accedi|login/i });
      await expect(loginBtn).toBeVisible({ timeout: 5000 });
    }
  });

  test('registrazione richiede username e password', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.waitForTimeout(500);

    // Vai in server mode
    const serverButton = page.getByRole('button', { name: /server|online/i });
    if (await serverButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await serverButton.click();
      await page.waitForTimeout(500);

      // Cerca il bottone registra
      const registerBtn = page.getByRole('button', { name: /registra|register|sign up/i });
      if (await registerBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
        await registerBtn.click();
        await page.waitForTimeout(500);

        // Verifica che ci siano i campi necessari
        const usernameField = page.getByPlaceholder(/username|utente/i);
        const passwordField = page.getByPlaceholder(/password/i);

        await expect(usernameField).toBeVisible({ timeout: 3000 });
        await expect(passwordField).toBeVisible({ timeout: 3000 });
      }
    }
  });

});

test.describe('AUTH-005: Logout e sessione', () => {

  test('token viene salvato dopo login', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.waitForTimeout(500);

    // Vai in server mode
    const serverButton = page.getByRole('button', { name: /server|online/i });
    if (await serverButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await serverButton.click();
      await page.waitForTimeout(500);

      // Login con credenziali valide
      await page.getByPlaceholder(/username|utente/i).fill('alice');
      await page.getByPlaceholder(/password/i).fill('alice123');
      await page.getByRole('button', { name: /accedi|login/i }).click();

      // Aspetta che il login completi
      await page.waitForFunction(() => {
        return !document.body.innerText.includes('Caricamento');
      }, { timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(1000);

      // Verifica che il token sia stato salvato
      const hasToken = await page.evaluate(() => {
        return localStorage.getItem('tramando-auth-token') !== null;
      });

      expect(hasToken).toBeTruthy();
    }
  });

  test('rimozione token causa ritorno a splash', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.waitForTimeout(500);

    // Login prima
    const serverButton = page.getByRole('button', { name: /server|online/i });
    if (await serverButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await serverButton.click();
      await page.waitForTimeout(500);

      await page.getByPlaceholder(/username|utente/i).fill('alice');
      await page.getByPlaceholder(/password/i).fill('alice123');
      await page.getByRole('button', { name: /accedi|login/i }).click();

      // Aspetta che il login completi e vediamo i progetti
      await expect(page.getByText('Alice Project 1')).toBeVisible({ timeout: 15000 });

      // Ora rimuovi il token
      await page.evaluate(() => {
        localStorage.removeItem('tramando-auth-token');
      });

      // Ricarica
      await page.reload();
      await page.waitForTimeout(1000);

      // Dovremmo vedere la splash (scelta locale/server)
      const hasSplash = await page.evaluate(() => {
        const text = document.body.innerText.toLowerCase();
        return text.includes('locale') || text.includes('server') ||
               text.includes('local') || text.includes('nuovo');
      });

      expect(hasSplash).toBeTruthy();
    }
  });

});

test.describe('AUTH-006: Accesso non autorizzato', () => {

  test('senza token non si vedono i progetti', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.waitForTimeout(1000);

    // Senza token, dovremmo vedere la splash (scelta locale/server)
    // NON la lista progetti
    const hasProjects = await page.getByText('Alice Project').isVisible({ timeout: 2000 }).catch(() => false);
    expect(hasProjects).toBeFalsy();

    // Dovremmo vedere la splash
    const hasSplash = await page.evaluate(() => {
      const text = document.body.innerText.toLowerCase();
      return text.includes('locale') || text.includes('server') ||
             text.includes('nuovo') || text.includes('tramando');
    });
    expect(hasSplash).toBeTruthy();
  });

  test('credenziali errate non permettono accesso', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.waitForTimeout(500);

    // Vai in server mode
    const serverButton = page.getByRole('button', { name: /server|online/i });
    if (await serverButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await serverButton.click();
      await page.waitForTimeout(500);

      // Prova login con credenziali errate
      await page.getByPlaceholder(/username|utente/i).fill('alice');
      await page.getByPlaceholder(/password/i).fill('wrongpassword');
      await page.getByRole('button', { name: /accedi|login/i }).click();

      await page.waitForTimeout(2000);

      // NON dovremmo vedere i progetti
      const hasProjects = await page.getByText('Alice Project').isVisible({ timeout: 2000 }).catch(() => false);
      expect(hasProjects).toBeFalsy();

      // Dovrebbe mostrare un errore
      const hasError = await page.getByText(/error|errat|invalid|non valido/i).isVisible({ timeout: 3000 }).catch(() => false);
      expect(hasError).toBeTruthy();
    }
  });

});
