/**
 * Playwright Global Setup
 * - Creates test database with fixtures
 * - Starts the backend server with test configuration
 */

import { execSync, spawn } from 'child_process';
import { existsSync, mkdirSync, copyFileSync, writeFileSync, rmSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = join(__dirname, '..');
const fixturesDir = join(__dirname, 'fixtures');
const testDbPath = join(fixturesDir, 'test-tramando.db');
const testProjectsDir = join(fixturesDir, 'test-projects');

/**
 * Create test database using Node.js (simpler than Clojure script)
 */
async function createTestDatabase() {
  console.log('Creating test database...');

  // Ensure fixtures directory exists
  if (!existsSync(fixturesDir)) {
    mkdirSync(fixturesDir, { recursive: true });
  }

  // Remove existing test database
  if (existsSync(testDbPath)) {
    rmSync(testDbPath);
    console.log('Removed existing test database');
  }

  // We'll use better-sqlite3 for synchronous operations
  // First check if it's installed, if not use the Clojure script
  try {
    const Database = (await import('better-sqlite3')).default;
    const db = new Database(testDbPath);

    // Create schema
    db.exec(`
      CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        display_name TEXT,
        email TEXT,
        is_super_admin INTEGER DEFAULT 0,
        status TEXT DEFAULT 'pending',
        max_projects INTEGER DEFAULT 10,
        max_project_size_mb INTEGER DEFAULT 50,
        max_collaborators INTEGER DEFAULT 5,
        notes TEXT,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS projects (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        owner_id INTEGER NOT NULL,
        metadata_cache TEXT,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (owner_id) REFERENCES users(id)
      );

      CREATE TABLE IF NOT EXISTS collaborators (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        project_id INTEGER NOT NULL,
        user_id INTEGER NOT NULL,
        role TEXT DEFAULT 'collaborator',
        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        UNIQUE(project_id, user_id)
      );

      CREATE TABLE IF NOT EXISTS chat_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        project_id INTEGER NOT NULL,
        user_id INTEGER NOT NULL,
        message TEXT NOT NULL,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
      );
    `);

    // We need bcrypt-compatible hashes. Use pre-computed hashes for test passwords.
    // These are bcrypt hashes generated with buddy-hashers for the test passwords.
    // Format: bcrypt+sha512$<salt>$12$<hash>
    // For simplicity, we'll use a simpler approach: run the Clojure setup script
    db.close();
    rmSync(testDbPath);
    throw new Error('Need Clojure for password hashing');

  } catch (e) {
    // Fall back to Clojure script for proper password hashing
    console.log('Using Clojure script for database setup (required for password hashing)...');

    try {
      execSync('cd server && clojure -M -e \'(load-file "../e2e/fixtures/setup-test-db.clj")\'', {
        cwd: rootDir,
        stdio: 'inherit',
        timeout: 60000
      });
    } catch (clojureError) {
      // Try alternative: create DB with pre-hashed passwords
      console.log('Clojure script failed, creating database with pre-hashed passwords...');
      await createDatabaseWithPrehashedPasswords();
    }
  }
}

/**
 * Create database with pre-computed bcrypt hashes
 * These hashes were generated using buddy-hashers
 */
async function createDatabaseWithPrehashedPasswords() {
  const Database = (await import('better-sqlite3')).default;
  const db = new Database(testDbPath);

  // Create schema - match the server's db.clj schema exactly
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      display_name TEXT,
      email TEXT,
      is_super_admin INTEGER DEFAULT 0,
      status TEXT DEFAULT 'pending',
      max_projects INTEGER DEFAULT 10,
      max_project_size_mb INTEGER DEFAULT 50,
      max_collaborators INTEGER DEFAULT 5,
      notes TEXT,
      created_at TEXT DEFAULT (datetime('now'))
    );

    CREATE TABLE IF NOT EXISTS projects (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      owner_id INTEGER NOT NULL,
      metadata_cache TEXT,
      disabled INTEGER DEFAULT 0,
      has_validation_errors INTEGER DEFAULT 0,
      validation_errors TEXT,
      created_at TEXT DEFAULT (datetime('now')),
      updated_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (owner_id) REFERENCES users(id)
    );

    CREATE TABLE IF NOT EXISTS permissions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      project_id INTEGER NOT NULL,
      role TEXT NOT NULL CHECK (role IN ('admin', 'collaborator')),
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (user_id) REFERENCES users(id),
      FOREIGN KEY (project_id) REFERENCES projects(id),
      UNIQUE(user_id, project_id)
    );

    CREATE TABLE IF NOT EXISTS chat_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      project_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      username TEXT NOT NULL,
      message TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (project_id) REFERENCES projects(id),
      FOREIGN KEY (user_id) REFERENCES users(id)
    );
  `);

  // Pre-computed bcrypt+sha512 hashes for test passwords
  // Generated with: (buddy.hashers/derive "password")
  const hashes = {
    'admin123': 'bcrypt+sha512$e2e-test-admin$12$a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6',
    'alice123': 'bcrypt+sha512$e2e-test-alice$12$a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p7',
    'bob123': 'bcrypt+sha512$e2e-test-bob$12$a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p8',
    'carol123': 'bcrypt+sha512$e2e-test-carol$12$a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p9',
  };

  // For E2E tests, we need real hashes. Generate them using Clojure inline
  console.log('Generating password hashes...');

  const passwords = ['admin123', 'alice123', 'bob123', 'carol123', 'pending123'];
  const generatedHashes = {};

  for (const pwd of passwords) {
    try {
      const result = execSync(
        `cd server && clojure -M -e '(require (quote [buddy.hashers :as h])) (print (h/derive "${pwd}"))'`,
        { cwd: rootDir, encoding: 'utf8', timeout: 30000 }
      );
      generatedHashes[pwd] = result.trim();
    } catch (e) {
      console.error(`Failed to hash ${pwd}:`, e.message);
      throw e;
    }
  }

  // Insert users
  const insertUser = db.prepare(`
    INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
    VALUES (?, ?, ?, ?, 'active')
  `);

  insertUser.run('admin', generatedHashes['admin123'], 'Admin User', 1);
  insertUser.run('alice', generatedHashes['alice123'], 'Alice Writer', 0);
  insertUser.run('bob', generatedHashes['bob123'], 'Bob Editor', 0);
  insertUser.run('carol', generatedHashes['carol123'], 'Carol Reviewer', 0);

  // Pending user
  db.prepare(`
    INSERT INTO users (username, password_hash, display_name, is_super_admin, status)
    VALUES (?, ?, ?, 0, 'pending')
  `).run('pending_user', generatedHashes['pending123'], 'Pending User');

  // Insert projects
  const insertProject = db.prepare(`
    INSERT INTO projects (name, owner_id, metadata_cache) VALUES (?, ?, ?)
  `);

  insertProject.run('Alice Project 1', 2, '{"title":"Il mio romanzo","author":"Alice","word_count":15000}');
  insertProject.run('Alice Project 2', 2, '{"title":"Racconti brevi","author":"Alice","word_count":5000}');
  insertProject.run('Bob Project', 3, '{"title":"Saggio","author":"Bob","word_count":8000}');
  insertProject.run('Admin Project', 1, '{"title":"Documentazione","author":"Admin","word_count":2000}');

  // Insert permissions (collaborators)
  const insertPerm = db.prepare(`
    INSERT INTO permissions (project_id, user_id, role) VALUES (?, ?, ?)
  `);

  insertPerm.run(1, 3, 'collaborator');  // Bob on Alice Project 1
  insertPerm.run(1, 4, 'admin');          // Carol on Alice Project 1 as admin
  insertPerm.run(3, 2, 'collaborator');   // Alice on Bob's project

  db.close();
  console.log('Test database created with pre-hashed passwords');

  // Create project files
  createProjectFiles();
}

/**
 * Create project content files
 * NOTA: Il formato TRMD interno usa [C:id"summary"] per i chunk
 */
function createProjectFiles() {
  if (!existsSync(testProjectsDir)) {
    mkdirSync(testProjectsDir, { recursive: true });
  }

  // Formato TRMD interno: [C:id"summary"][@aspect][#owner:user]
  // Indentazione con 2 spazi per chunk figli
  const projects = {
    '1.trmd': `---
title: "Il mio romanzo"
author: "Alice"
year: 2024
language: "it"
---

[C:cap-1"Capitolo 1"]
C'era una volta in un paese lontano, viveva una principessa coraggiosa.
Questa è la storia delle sue avventure.

[C:cap-2"Capitolo 2"]
E vissero felici e contenti.
La fine della storia è sempre lieta.`,
    '2.trmd': `---
title: "Racconti brevi"
author: "Alice"
language: "it"
---

[C:r-1"Racconto 1"]
Un breve racconto inizia qui.
Parla di un gatto curioso.

[C:r-2"Racconto 2"]
Un altro racconto interessante.
Questa volta parla di un cane.`,
    '3.trmd': `---
title: "Saggio"
author: "Bob"
language: "it"
---

[C:intro"Introduzione"]
Questo è un saggio accademico.
Tratta di argomenti importanti.

[C:concl"Conclusione"]
Fine del saggio con le conclusioni.
Abbiamo imparato molto.`,
    '4.trmd': `---
title: "Documentazione"
author: "Admin"
language: "it"
---

[C:doc-1"Documentazione"]
Contenuto della documentazione tecnica.
Include istruzioni dettagliate.

[C:app-1"Appendice"]
Materiale supplementare per approfondimenti.`
  };

  for (const [filename, content] of Object.entries(projects)) {
    // Il server cerca i file in <project-id>/project.trmd (formato git repo)
    // oppure <project-id>.trmd (formato legacy)
    // Usiamo il formato legacy
    writeFileSync(join(testProjectsDir, filename), content);

    // Ma il server con versioning usa directory, quindi creiamo anche quelle
    const projectId = filename.replace('.trmd', '');
    const projectDir = join(testProjectsDir, projectId);
    if (!existsSync(projectDir)) {
      mkdirSync(projectDir, { recursive: true });
    }
    writeFileSync(join(projectDir, 'project.trmd'), content);
  }

  console.log('Project files created');
}

/**
 * Start the backend server with test configuration
 */
async function startTestServer() {
  console.log('Starting test backend server...');

  const serverProcess = spawn('clojure', ['-M:run'], {
    cwd: join(rootDir, 'server'),
    env: {
      ...process.env,
      TRAMANDO_DB_PATH: testDbPath,
      TRAMANDO_PROJECTS_PATH: testProjectsDir,
      TRAMANDO_JWT_SECRET: 'e2e-test-secret-key',
      PORT: '3001',  // Use different port for test server
      TRAMANDO_ALLOW_REGISTRATION: 'true'
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: false
  });

  // Store server process for teardown
  global.__TEST_SERVER__ = serverProcess;

  // Wait for server to be ready
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error('Server startup timeout'));
    }, 60000);

    let output = '';

    serverProcess.stdout.on('data', (data) => {
      output += data.toString();
      console.log('[server]', data.toString().trim());
      // Il server Clojure logga "Server started." quando è pronto
      if (output.includes('Server started') || output.includes('Started server') || output.includes(':3001')) {
        clearTimeout(timeout);
        console.log('Test server started on port 3001');
        resolve();
      }
    });

    serverProcess.stderr.on('data', (data) => {
      console.error('[server error]', data.toString().trim());
    });

    serverProcess.on('error', (err) => {
      clearTimeout(timeout);
      reject(err);
    });

    serverProcess.on('exit', (code) => {
      if (code !== 0 && code !== null) {
        clearTimeout(timeout);
        reject(new Error(`Server exited with code ${code}`));
      }
    });
  });
}

/**
 * Check if test server is already running
 */
async function isServerRunning(port = 3001) {
  try {
    const response = await fetch(`http://localhost:${port}/api/me`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' }
    });
    // Server is running if we get any response (even 401)
    return response.status === 401 || response.status === 200;
  } catch {
    return false;
  }
}

/**
 * Main setup function
 */
export default async function globalSetup() {
  console.log('\n=== E2E Test Global Setup ===\n');

  // Create test database
  await createTestDatabase();

  // Check if server is already running
  if (await isServerRunning(3001)) {
    console.log('Test server already running on port 3001');
    global.__TEST_SERVER__ = null;  // Don't kill external server
    return;
  }

  // Start test server
  await startTestServer();

  // Give server a moment to fully initialize
  await new Promise(resolve => setTimeout(resolve, 2000));

  console.log('\n=== Setup Complete ===\n');
}
