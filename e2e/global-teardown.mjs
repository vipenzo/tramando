/**
 * Playwright Global Teardown
 * - Stops the backend server
 * - Cleans up test files (optional)
 */

export default async function globalTeardown() {
  console.log('\n=== E2E Test Global Teardown ===\n');

  // Kill the test server if we started it
  if (global.__TEST_SERVER__) {
    console.log('Stopping test server...');

    try {
      // Send SIGTERM to the process
      global.__TEST_SERVER__.kill('SIGTERM');

      // Wait a bit for graceful shutdown
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Force kill if still running
      if (!global.__TEST_SERVER__.killed) {
        global.__TEST_SERVER__.kill('SIGKILL');
      }

      console.log('Test server stopped');
    } catch (e) {
      console.error('Error stopping server:', e.message);
    }
  } else {
    console.log('No test server to stop (external server was used)');
  }

  // Optionally clean up test database and files
  // Uncomment if you want to clean up after tests
  /*
  const { rmSync, existsSync } = await import('fs');
  const { join, dirname } = await import('path');
  const { fileURLToPath } = await import('url');

  const __dirname = dirname(fileURLToPath(import.meta.url));
  const testDbPath = join(__dirname, 'fixtures', 'test-tramando.db');
  const testProjectsDir = join(__dirname, 'fixtures', 'test-projects');

  if (existsSync(testDbPath)) {
    rmSync(testDbPath);
    console.log('Cleaned up test database');
  }

  if (existsSync(testProjectsDir)) {
    rmSync(testProjectsDir, { recursive: true });
    console.log('Cleaned up test project files');
  }
  */

  console.log('\n=== Teardown Complete ===\n');
}
