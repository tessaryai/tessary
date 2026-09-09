// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The E2B backend's localhost/blank MCP-callback-URL guard in runAgenticScript. An E2B
 * microVM is a separate machine on E2B's network — it cannot reach this host's `localhost`, so a
 * callback URL pointed there (docker-compose.dev.yml's own dev default) or missing entirely must be
 * rejected BEFORE the launcher spends an E2B sandbox create call, not discovered as an opaque
 * timeout/orchestration failure an agent-run's worth of minutes later.
 *
 * No fake E2B daemon needed here (contrast docker-backend.test.js): the rejection happens before
 * `Sandbox()` is even required, so `e2b`'s real HTTP client is never touched — these requests would
 * fail on a real E2B_API_KEY too, and this test asserts they fail FAST and for the right reason
 * instead. The companion 'docker backend accepts the same payload unchanged' case pins that this
 * guard is scoped to the e2b backend alone, per server.js's own comment at the call site.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SERVER_JS = path.join(__dirname, '..', 'server.js');

// Every /rca and /triage request now carries its own `credential` object — see
// docker-backend.test.js's identical constant for why.
const BEDROCK_CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'test-akid', aws_secret_key: 'test-secret' };

async function startLauncher(env) {
  const child = spawn('node', [SERVER_JS], {
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  await new Promise((resolve, reject) => {
    let out = '';
    const onData = (d) => {
      out += d.toString();
      if (/listening on/.test(out)) { child.stdout.off('data', onData); resolve(); }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', (d) => { out += d.toString(); });
    child.on('exit', (code) => reject(new Error(`launcher exited early (code ${code}): ${out}`)));
    setTimeout(() => reject(new Error(`launcher did not start in time: ${out}`)), 5000);
  });
  return child;
}

function postJson(port, urlPath, body, apiKey) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const req = http.request(
      { host: '127.0.0.1', port, path: urlPath, method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': data.length, Authorization: `Bearer ${apiKey}` } },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
      },
    );
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function tempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

const LOCALHOST_URLS = [
  'http://localhost:8000',
  'http://127.0.0.1:8000',
  'http://[::1]:8000',
  'http://0.0.0.0:8000',
  'http://[::ffff:127.0.0.1]:8000', // Node's URL parser normalizes this to [::ffff:7f00:1]
];

for (const route of ['/rca', '/triage']) {
  test(`e2b backend: ${route} rejects a localhost mcp.url before creating a sandbox`, async () => {
    const port = 18500 + Math.floor(Math.random() * 500);
    const child = await startLauncher({
      SANDBOX_BACKEND: 'e2b',
      E2B_API_KEY: 'fake-e2b-key',
      SANDBOX_API_KEY: 'testkey',
      PORT: String(port),
    });

    try {
      const payload = { files: {}, prompt: 'x', json_schema: {}, mcp: { url: 'http://localhost:8000', token: 't' }, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL };
      const res = await postJson(port, route, payload, 'testkey');
      assert.equal(res.status, 502, `expected the pre-flight reject, got: ${res.body}`);
      const parsed = JSON.parse(res.body);
      assert.equal(parsed.kind, 'bad_request');
      assert.match(parsed.detail, /localhost|unreachable|mcp\.url/i);
    } finally {
      child.kill();
    }
  });

  test(`e2b backend: ${route} rejects a missing mcp.url before creating a sandbox`, async () => {
    const port = 18500 + Math.floor(Math.random() * 500);
    const child = await startLauncher({
      SANDBOX_BACKEND: 'e2b',
      E2B_API_KEY: 'fake-e2b-key',
      SANDBOX_API_KEY: 'testkey',
      PORT: String(port),
    });

    try {
      const payload = { files: {}, prompt: 'x', json_schema: {}, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL }; // no mcp at all
      const res = await postJson(port, route, payload, 'testkey');
      assert.equal(res.status, 502, `expected the pre-flight reject, got: ${res.body}`);
      const parsed = JSON.parse(res.body);
      assert.equal(parsed.kind, 'bad_request');
    } finally {
      child.kill();
    }
  });
}

for (const rawUrl of LOCALHOST_URLS) {
  test(`e2b backend: rejects ${rawUrl} as a localhost form`, async () => {
    const port = 18500 + Math.floor(Math.random() * 500);
    const child = await startLauncher({
      SANDBOX_BACKEND: 'e2b',
      E2B_API_KEY: 'fake-e2b-key',
      SANDBOX_API_KEY: 'testkey',
      PORT: String(port),
    });

    try {
      const res = await postJson(port, '/triage', {
        files: {}, prompt: 'x', json_schema: {}, mcp: { url: rawUrl, token: 't' }, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL,
      }, 'testkey');
      assert.equal(res.status, 502, `expected the pre-flight reject for ${rawUrl}, got: ${res.body}`);
      assert.equal(JSON.parse(res.body).kind, 'bad_request');
    } finally {
      child.kill();
    }
  });
}

test('docker backend: the SAME localhost mcp.url is accepted unchanged (guard is e2b-only)', async () => {
  const scratch = tempDir('e2b-backend-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });

  // Minimal fake Docker Engine API — enough of the shapes runScriptInDockerInner needs to
  // complete one /triage round trip, mirroring docker-backend.test.js's own fake daemon.
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const url = req.url || '';
      const send = (status, obj) => {
        const text = obj === undefined ? '' : JSON.stringify(obj);
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(text);
      };
      if (req.method === 'GET' && /^\/images\//.test(url)) return send(200, { Id: 'sha256:fake' });
      if (req.method === 'GET' && /^\/networks\//.test(url)) return send(404, { message: 'not found' });
      if (req.method === 'POST' && url === '/networks/create') return send(201, { Id: 'net1' });
      if (req.method === 'GET' && url.startsWith('/containers/json')) return send(200, []);
      if (req.method === 'POST' && url === '/containers/create') return send(201, { Id: 'c1' });
      if (req.method === 'POST' && /\/containers\/[^/]+\/start$/.test(url)) return send(204);
      if (req.method === 'GET' && /\/containers\/[^/]+\/logs/.test(url)) {
        const payload = Buffer.from(JSON.stringify({ raw: 'fake-ok' }));
        const header = Buffer.alloc(8);
        header.writeUInt8(1, 0);
        header.writeUInt32BE(payload.length, 4);
        res.writeHead(200, { 'Content-Type': 'application/vnd.docker.raw-stream' });
        res.write(Buffer.concat([header, payload]));
        setTimeout(() => res.end(), 10);
        return;
      }
      if (req.method === 'POST' && /\/containers\/[^/]+\/wait$/.test(url)) return setTimeout(() => send(200, { StatusCode: 0 }), 20);
      if (req.method === 'DELETE' && /^\/containers\//.test(url)) return send(204);
      return send(404, { message: `unhandled ${req.method} ${url}` });
    });
  });
  await new Promise((resolve) => server.listen(socketPath, resolve));

  const port = 18500 + Math.floor(Math.random() * 500);
  const child = await startLauncher({
    SANDBOX_BACKEND: 'docker',
    DOCKER_SOCKET_PATH: socketPath,
    AGENT_IMAGE: 'test-agent:latest',
    LAUNCHER_WORK_DIR: workDir,
    SANDBOX_WORK_VOLUME: 'test-work-volume',
    SANDBOX_API_KEY: 'testkey',
    PORT: String(port),
    SANDBOX_DOCKER_CONCURRENCY: '1',
    AWS_REGION: 'us-east-1',
  });

  try {
    // docker-compose.dev.yml's own default (localhost:8000) — must keep working unchanged.
    const res = await postJson(port, '/triage', {
      files: [], prompt: 'x', json_schema: {}, mcp: { url: 'http://localhost:8000', token: 't' }, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL,
    }, 'testkey');
    assert.equal(res.status, 200, `expected the docker backend to accept a localhost mcp.url unchanged, got: ${res.body}`);
    assert.deepEqual(JSON.parse(res.body), { raw: 'fake-ok' });
  } finally {
    child.kill();
    server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});
