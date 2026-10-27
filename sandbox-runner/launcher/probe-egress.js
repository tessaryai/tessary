// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Release probe: one real agent run per Tessary AI model, on real E2B, under the egress posture
 * (`egressNetwork()`: allowOut, denyOut 0.0.0.0/0, the key injected at E2B's proxy). The unit tests
 * fake E2B and opencode, so they assert what the launcher asks for, never what opencode needs; a
 * host opencode reaches at run time and the allowlist leaves out fails only here. That gap shipped
 * once: opencode resolves a new model id against models.opencode.ai, and every egress run failed
 * with a 500 while own-key runs, which have no network rules, worked.
 *
 * It starts this directory's real server.js and sends it a /runs request, the same path production
 * takes. The models are read from the backend's ModelCatalog, so a new Tessary AI model is probed
 * without an edit here.
 *
 *   E2B_API_KEY=... node probe-egress.js --template=tessary/tessary-agent-sandbox:<version>
 *                                         [--secret=tessary-ai-anthropic]
 *
 * The secret is a name in the E2B team's own secret store, holding an Anthropic key. Exit 0 when
 * every model replies; 1 otherwise, with the launcher's classified 502 body and its log.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SERVER_JS = path.join(__dirname, 'server.js');
const CATALOG = path.join(__dirname, '..', '..', 'backend', 'llm-runtime', 'src', 'main', 'java', 'ai', 'tessary', 'llm', 'ModelCatalog.java');
const RUN_TIMEOUT_MS = 180_000;
// Never contacted for a prose reply, but the launcher refuses a run without a public MCP door.
const MCP = { url: 'https://mcp.example.com/mcp', token: 'probe' };

function argOf(flag) {
  const hit = process.argv.slice(2).find((a) => a.startsWith(`--${flag}=`));
  return hit && hit.slice(flag.length + 3);
}

function platformModels() {
  const java = fs.readFileSync(CATALOG, 'utf8');
  const models = [...java.matchAll(/new CatalogEntry\(ModelProvider\.PLATFORM,\s*"[^"]*",\s*"([^"]+)"/g)].map((m) => m[1]);
  if (!models.length) throw new Error(`no ModelProvider.PLATFORM entry in ${CATALOG}; the pattern here no longer matches it`);
  return models;
}

function startLauncher(template, apiKey) {
  const child = spawn('node', [SERVER_JS], {
    env: { ...process.env, SANDBOX_BACKEND: 'e2b', E2B_ANALYZER_TEMPLATE: template, SANDBOX_API_KEY: apiKey, PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  const port = new Promise((resolve, reject) => {
    child.stdout.on('data', (d) => {
      log += d;
      const bound = /listening on :(\d+)/.exec(log);
      if (bound) resolve(Number(bound[1]));
    });
    child.stderr.on('data', (d) => { log += d; });
    child.on('exit', (code) => reject(new Error(`launcher exited early (code ${code}):\n${log}`)));
  });
  return { child, port, log: () => log };
}

function post(port, apiKey, route, payload) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(payload));
    const req = http.request(
      { host: '127.0.0.1', port, path: route, method: 'POST', timeout: RUN_TIMEOUT_MS + 60_000,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` } },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
      },
    );
    req.on('timeout', () => req.destroy(new Error(`no answer from the launcher in ${RUN_TIMEOUT_MS + 60_000}ms`)));
    req.on('error', reject);
    req.end(data);
  });
}

async function main() {
  const template = argOf('template');
  const secret = argOf('secret') || 'tessary-ai-anthropic';
  if (!template) throw new Error('--template=<e2b template ref> is required');
  if (!process.env.E2B_API_KEY) throw new Error('E2B_API_KEY is not set');

  const apiKey = crypto.randomBytes(16).toString('hex');
  const launcher = startLauncher(template, apiKey);
  const failed = [];
  try {
    const port = await launcher.port;
    for (const model of platformModels()) {
      const started = Date.now();
      const { status, body } = await post(port, apiKey, '/runs', {
        prompt: 'Reply with the single word ok.',
        model,
        mcp: MCP,
        timeout_ms: RUN_TIMEOUT_MS,
        max_turns: 30,
        credential: { provider: 'ANTHROPIC', egress_secret: secret },
      });
      const took = `${Math.round((Date.now() - started) / 1000)}s`;
      if (status === 200) {
        console.log(`  ok    ${model} under the egress posture (${took})`);
      } else {
        console.error(`  FAIL  ${model}: HTTP ${status} after ${took}\n    ${body.slice(0, 2000)}`);
        failed.push(model);
      }
    }
  } finally {
    launcher.child.kill();
  }
  if (failed.length) {
    console.error(`--- launcher log ---\n${launcher.log().slice(-8000)}`);
    throw new Error(`${template}: ${failed.length} model(s) failed under the egress posture: ${failed.join(', ')}`);
  }
  console.log(`OK: ${template} runs every Tessary AI model under the egress posture`);
}

main().catch((e) => {
  console.error(`error: ${e.message}`);
  process.exit(1);
});
