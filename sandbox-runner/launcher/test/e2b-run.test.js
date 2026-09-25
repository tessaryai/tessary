// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The E2B backend past its pre-flight guard: create the microVM, write input.json, run the script,
 * kill the sandbox, and turn every failure into the classified 502 body. The real `e2b` SDK talks
 * to E2B's API, so the spawned launcher is preloaded with test/fixtures/fake-e2b, which stands in
 * for it and logs each call to a file this test reads back.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SERVER_JS = path.join(__dirname, '..', 'server.js');
const FAKE_E2B = path.join(__dirname, 'fixtures', 'fake-e2b');
const CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'test-akid', aws_secret_key: 'test-secret' };
const PAYLOAD = { prompt: 'why?', mcp: { url: 'https://tessary.example.com/mcp', token: 't' }, timeout_ms: 10000, credential: CREDENTIAL };

async function runOnce(mode, route = '/rca', extraEnv = {}) {
  const logPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'fake-e2b-')), 'calls.jsonl');
  fs.writeFileSync(logPath, '');
  const child = spawn('node', [SERVER_JS], {
    env: {
      ...process.env,
      NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --require ${FAKE_E2B}`,
      FAKE_E2B_MODE: mode,
      FAKE_E2B_LOG: logPath,
      SANDBOX_BACKEND: 'e2b',
      E2B_API_KEY: 'fake-e2b-key',
      SANDBOX_API_KEY: 'testkey',
      ...extraEnv,
      PORT: '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  try {
    const port = await new Promise((resolve, reject) => {
      let out = '';
      child.stdout.on('data', (d) => {
        out += d.toString();
        const bound = /listening on :(\d+)/.exec(out);
        if (bound) resolve(Number(bound[1]));
      });
      child.on('exit', (code) => reject(new Error(`launcher exited early (code ${code}): ${out}`)));
    });
    const res = await new Promise((resolve, reject) => {
      const data = Buffer.from(JSON.stringify(PAYLOAD));
      const req = http.request(
        { host: '127.0.0.1', port, path: route, method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer testkey' } },
        (r) => {
          const chunks = [];
          r.on('data', (c) => chunks.push(c));
          r.on('end', () => resolve({ status: r.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }));
        },
      );
      req.on('error', reject);
      req.end(data);
    });
    const calls = fs.readFileSync(logPath, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
    return { ...res, calls };
  } finally {
    child.kill();
  }
}

// The per-run timing is the only field that varies; everything else in the body is pinned.
function withoutElapsed(body) {
  assert.equal(typeof body.elapsed_ms, 'number');
  const { elapsed_ms: _elapsed, ...rest } = body;
  return rest;
}

test('a successful E2B run keeps the credential off the microVM disk, returns the output, and kills the sandbox', async () => {
  const { status, body, calls } = await runOnce('ok');

  assert.deepEqual({ status, body }, { status: 200, body: { verdict: 'supported' } });
  assert.deepEqual(calls.map((c) => c.op), ['create', 'write', 'run', 'kill']);
  assert.deepEqual(calls[0], { op: 'create', template: 'tessary/tessary-agent-sandbox:latest', apiKey: 'fake-e2b-key', timeoutMs: 10000 });
  assert.equal(calls[1].path, '/home/user/input.json');
  assert.ok(!calls[1].content.includes('test-secret'), 'the credential must never be written to the sandbox filesystem');
  assert.equal(JSON.parse(calls[1].content).credential, undefined);
  assert.equal(calls[2].cmd, 'node /home/user/rca.js /home/user/input.json');
  assert.ok(Object.values(calls[2].envs).includes('test-secret'), 'the credential reaches the agent through its env');
});

test('a script that exits non-zero answers script_exit with its spend and sandbox id, never its stderr', async () => {
  const { status, body, calls } = await runOnce('exit');

  assert.equal(status, 502);
  assert.deepEqual(withoutElapsed(body), {
    error: 'sandbox orchestration failed',
    kind: 'script_exit',
    timeout: false,
    error_class: 'CommandExitError',
    detail: 'rca.js exited 1 (output withheld; see launcher logs)',
    exit_code: 1,
    script: 'rca.js',
    timeout_ms: 10000,
    sandbox_id: 'sbx-fake-1',
    usage: { input_tokens: 120, output_tokens: 7 },
  });
  assert.deepEqual(calls.map((c) => c.op), ['create', 'write', 'run', 'getInfo', 'kill']);
});

test('a severed command stream still books the spend the launcher captured off the stream', async () => {
  const { status, body } = await runOnce('severed', '/triage');

  assert.equal(status, 502);
  assert.deepEqual(withoutElapsed(body), {
    error: 'sandbox orchestration failed',
    kind: 'orchestration',
    timeout: false,
    error_class: 'SandboxError',
    detail: '[unknown] terminated',
    script: 'triage.js',
    timeout_ms: 10000,
    sandbox_id: 'sbx-fake-1',
    usage: { input_tokens: 120, output_tokens: 7 },
  });
});

test('an SDK timeout well short of the deadline is a timeout, and says it gave up early', async () => {
  const { status, body } = await runOnce('timeout');

  assert.equal(status, 502);
  assert.equal(body.kind, 'timeout');
  assert.equal(body.timeout, true);
  assert.equal(body.detail, `timed out after ${body.elapsed_ms}ms (deadline 10000ms)`);
});

test('a sandbox that is never created is an orchestration failure with no sandbox to kill', async () => {
  const { status, body, calls } = await runOnce('create-fail');

  assert.equal(status, 502);
  assert.deepEqual(withoutElapsed(body), {
    error: 'sandbox orchestration failed',
    kind: 'orchestration',
    timeout: false,
    error_class: 'NotFoundError',
    detail: 'template not found',
    script: 'rca.js',
    timeout_ms: 10000,
  });
  assert.deepEqual(calls.map((c) => c.op), ['create']);
});

test('a post-mortem lookup that fails never replaces the run\'s own failure', async () => {
  const { status, body } = await runOnce('exit', '/rca', { FAKE_E2B_GETINFO: 'fail' });

  assert.equal(status, 502);
  assert.equal(body.kind, 'script_exit');
  assert.equal(body.exit_code, 1);
});
