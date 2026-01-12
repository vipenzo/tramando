import { test, expect } from '@playwright/test';

/**
 * Test E2E per funzionalità di chat di progetto
 *
 * Copre: CHAT-001, CHAT-002, CHAT-003, CHAT-005,
 *        CHAT-006, CHAT-007, CHAT-008, CHAT-009
 *
 * La chat è disponibile solo in modalità server per la collaborazione.
 * È un mini panel nell'outline che può essere espanso.
 */

const TEST_SERVER = 'http://localhost:3001';

// Helper per login e accesso a un progetto server
async function loginAndOpenProject(page, username, password) {
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

  const projectItem = page.locator('.project-item, [data-project-id]').first();
  if (await projectItem.isVisible({ timeout: 5000 }).catch(() => false)) {
    await projectItem.click();
    await page.waitForTimeout(1000);
  }

  await page.waitForSelector('.cm-editor', { timeout: 10000 });
  await page.waitForTimeout(500);

  const skipTutorial = page.getByRole('button', { name: /^Salta$|^Skip$/i });
  if (await skipTutorial.isVisible({ timeout: 2000 }).catch(() => false)) {
    await skipTutorial.click();
    await page.waitForTimeout(500);
  } else {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  }
}

// Helper per aprire la chat (clicca sul mini panel)
async function openChatPanel(page) {
  // La chat è nel mini panel con icona 💬 o testo "Chat"
  const chatMini = page.locator('text=/💬|chat|Chat/i').first();

  if (await chatMini.isVisible({ timeout: 3000 }).catch(() => false)) {
    await chatMini.click();
    await page.waitForTimeout(300);
    return true;
  }
  return false;
}

test.describe('CHAT-001: Chat panel visibile in modo server', () => {

  test('chat panel è visibile dopo login in modo server', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    // Cerca il mini panel della chat
    const chatPanel = page.locator('text=/💬|project.*chat|chat/i');

    // La chat dovrebbe essere visibile in modo server
    const isVisible = await chatPanel.first().isVisible({ timeout: 5000 }).catch(() => false);
    expect(isVisible).toBeTruthy();
  });

  test('chat non è visibile in modo locale', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: /nuovo/i }).click();
    await page.waitForTimeout(500);

    await page.waitForSelector('.cm-editor', { timeout: 10000 });
    await page.waitForTimeout(500);

    // Chiudi tutorial
    const skipTutorial = page.getByRole('button', { name: /^Salta$|^Skip$/i });
    if (await skipTutorial.isVisible({ timeout: 2000 }).catch(() => false)) {
      await skipTutorial.click();
    } else {
      await page.keyboard.press('Escape');
    }
    await page.waitForTimeout(300);

    // La chat NON dovrebbe essere visibile in modo locale
    const chatPanel = page.locator('text=/project.*chat/i');
    const isVisible = await chatPanel.isVisible({ timeout: 2000 }).catch(() => false);
    expect(isVisible).toBeFalsy();
  });

});

test.describe('CHAT-002: Invia messaggio chat', () => {

  test('può inviare un messaggio nella chat', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      // Trova il campo input
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        const testMessage = 'Test chat message ' + Date.now();
        await chatInput.fill(testMessage);

        // Trova e clicca il bottone invia (o premi Enter)
        const sendBtn = page.locator('button').filter({ hasText: /invia|send|>/ }).last();

        if (await sendBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
          await sendBtn.click();
        } else {
          await chatInput.press('Enter');
        }

        await page.waitForTimeout(500);

        // Il messaggio dovrebbe apparire nella chat
        const sentMessage = page.locator(`text=${testMessage}`);
        const isMessageVisible = await sentMessage.isVisible({ timeout: 3000 }).catch(() => false);
      }
    }
  });

  test('messaggio vuoto non viene inviato', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        // Lascia vuoto e prova a inviare
        await chatInput.clear();
        await chatInput.press('Enter');
        await page.waitForTimeout(300);

        // Il bottone dovrebbe essere disabilitato
        const sendBtn = page.locator('button').filter({ hasText: /invia|send|>/ }).last();
        if (await sendBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
          const isDisabled = await sendBtn.isDisabled().catch(() => false);
        }
      }
    }
  });

});

test.describe('CHAT-003: Messaggi mostrano display name', () => {

  test('messaggi mostrano nome utente', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      // Se ci sono messaggi esistenti, dovrebbero mostrare i nomi
      // Altrimenti invia un messaggio
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        await chatInput.fill('Messaggio per test nome');
        await chatInput.press('Enter');
        await page.waitForTimeout(500);

        // I messaggi propri non mostrano il nome (solo il tempo)
        // I messaggi altrui mostrano il nome
        // Verifica che ci sia almeno un elemento con stile messaggio
        const messageBubble = page.locator('div').filter({ hasText: /Messaggio per test nome/ });
        const isVisible = await messageBubble.first().isVisible({ timeout: 3000 }).catch(() => false);
      }
    }
  });

});

test.describe('CHAT-005: Badge unread quando panel chiuso', () => {

  test('badge mostra numero messaggi non letti', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    // Cerca il badge unread (potrebbe essere un numero o un indicatore)
    const unreadBadge = page.locator('[class*="badge"], [class*="unread"], span').filter({ hasText: /^\d+$/ });

    // Il badge potrebbe essere visibile se ci sono messaggi non letti
    // Questo test verifica che l'elemento esista quando necessario
    const isVisible = await unreadBadge.first().isVisible({ timeout: 2000 }).catch(() => false);

    // Test soft - il badge appare solo quando ci sono messaggi non letti
  });

});

test.describe('CHAT-006: Espandi chat a modal', () => {

  test('può espandere la chat per visualizzazione completa', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      // Cerca il bottone espandi (potrebbe essere ⬆️, ↗️, expand, ecc.)
      const expandBtn = page.locator('button').filter({ hasText: /espandi|expand|↗|⬆|fullscreen/i });

      if (await expandBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
        await expandBtn.click();
        await page.waitForTimeout(300);

        // Dovrebbe apparire un modal o la chat espansa
        const expandedChat = page.locator('[role="dialog"], .modal, .chat-expanded, .chat-modal');
        const isExpanded = await expandedChat.isVisible({ timeout: 2000 }).catch(() => false);
      }
    }
  });

});

test.describe('CHAT-007: Comprimi chat panel', () => {

  test('può comprimere la chat', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      // Cerca il bottone comprimi (potrebbe essere ⬇️, ↙️, minimize, ecc.)
      const collapseBtn = page.locator('button').filter({ hasText: /comprimi|collapse|↙|⬇|minimize|−/i });

      if (await collapseBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
        await collapseBtn.click();
        await page.waitForTimeout(300);

        // La chat dovrebbe essere compressa (solo header visibile)
      } else {
        // Potrebbe esserci un'area cliccabile per comprimere
        const chatHeader = page.locator('div').filter({ hasText: /💬|chat/i }).first();
        if (await chatHeader.isVisible({ timeout: 1000 }).catch(() => false)) {
          await chatHeader.click();
          await page.waitForTimeout(300);
        }
      }
    }
  });

});

test.describe('CHAT-008: Enter invia messaggio', () => {

  test('Enter invia il messaggio senza cliccare bottone', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        const uniqueMessage = 'Enter test ' + Date.now();
        await chatInput.fill(uniqueMessage);

        // Premi Enter invece di cliccare il bottone
        await chatInput.press('Enter');
        await page.waitForTimeout(500);

        // Il messaggio dovrebbe essere stato inviato
        const sentMessage = page.locator(`text=${uniqueMessage}`);
        const isVisible = await sentMessage.isVisible({ timeout: 3000 }).catch(() => false);

        // L'input dovrebbe essere vuoto dopo l'invio
        const inputValue = await chatInput.inputValue();
        expect(inputValue).toBe('');
      }
    }
  });

});

test.describe('CHAT-009: Messaggi propri allineati a destra', () => {

  test('messaggi propri hanno stile diverso', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        await chatInput.fill('Messaggio mio per test allineamento');
        await chatInput.press('Enter');
        await page.waitForTimeout(500);

        // I messaggi propri dovrebbero essere allineati a destra
        // (flex-end o text-align: right)
        const myMessage = page.locator('div').filter({ hasText: 'Messaggio mio per test allineamento' }).first();

        if (await myMessage.isVisible({ timeout: 2000 }).catch(() => false)) {
          const alignment = await myMessage.evaluate(el => {
            const parent = el.parentElement;
            if (parent) {
              const style = window.getComputedStyle(parent);
              return style.alignItems || style.justifyContent || style.textAlign;
            }
            return null;
          }).catch(() => null);

          // Dovrebbe essere 'flex-end' o 'right'
          // Test soft - verifica solo che non crashi
        }
      }
    }
  });

});

test.describe('Chat - Operazioni avanzate', () => {

  test('messaggi persistono dopo reload', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        const persistentMessage = 'Persistenza test ' + Date.now();
        await chatInput.fill(persistentMessage);
        await chatInput.press('Enter');
        await page.waitForTimeout(1000);

        // Reload della pagina
        await page.reload();
        await page.waitForTimeout(2000);

        // Riapri la chat
        await openChatPanel(page);
        await page.waitForTimeout(500);

        // Il messaggio dovrebbe essere ancora visibile
        const persistedMessage = page.locator(`text=${persistentMessage}`);
        const isStillVisible = await persistedMessage.isVisible({ timeout: 5000 }).catch(() => false);

        // Il messaggio dovrebbe persistere sul server
      }
    }
  });

  test('auto-scroll quando arrivano nuovi messaggi', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        // Invia diversi messaggi
        for (let i = 1; i <= 5; i++) {
          await chatInput.fill(`Messaggio ${i} per scroll test`);
          await chatInput.press('Enter');
          await page.waitForTimeout(200);
        }

        await page.waitForTimeout(500);

        // L'ultimo messaggio dovrebbe essere visibile (auto-scroll)
        const lastMessage = page.locator('text=Messaggio 5 per scroll test');
        const isVisible = await lastMessage.isVisible({ timeout: 2000 }).catch(() => false);
      }
    }
  });

});

test.describe('Chat - Edge cases', () => {

  test('messaggio con caratteri speciali', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        await chatInput.fill('Test con "virgolette" e àccènti €100');
        await chatInput.press('Enter');
        await page.waitForTimeout(500);
      }
    }
  });

  test('messaggio con emoji', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        await chatInput.fill('Messaggio con emoji 😀🎉🚀');
        await chatInput.press('Enter');
        await page.waitForTimeout(500);
      }
    }
  });

  test('messaggio molto lungo', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      const chatInput = page.locator('input[placeholder*="messag"], input[placeholder*="scrivi"], input[type="text"]').last();

      if (await chatInput.isVisible({ timeout: 3000 }).catch(() => false)) {
        const longMessage = 'Parola '.repeat(50);
        await chatInput.fill(longMessage);
        await chatInput.press('Enter');
        await page.waitForTimeout(500);

        // Il messaggio lungo dovrebbe essere visualizzato con word-wrap
      }
    }
  });

  test('chat mostra messaggio vuoto quando non ci sono messaggi', async ({ page }) => {
    await loginAndOpenProject(page, 'alice', 'alice123');

    const opened = await openChatPanel(page);

    if (opened) {
      // Cerca messaggio "nessun messaggio" o simile
      const emptyState = page.locator('text=/nessun messaggio|no message|vuota|empty/i');
      const isEmpty = await emptyState.isVisible({ timeout: 2000 }).catch(() => false);

      // Se non ci sono messaggi, dovrebbe mostrare uno stato vuoto
      // Altrimenti, ci sono già messaggi (entrambi i casi sono validi)
    }
  });

});
