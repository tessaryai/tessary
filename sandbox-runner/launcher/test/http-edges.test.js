// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The launcher's HTTP front door, before any sandbox backend is reached: the route table, the
 * bearer-key check, the per-request credential check, and the boot-time refusal of a docker
 * backend with no work volume. The e2b backend is used for the request cases because every one
 * of them is refused before a sandbox is created, so no daemon or SDK is involved.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SERVER_JS = path.join(__dirname, '..', 'server.js');

function spawnLauncher(env) {
  return spawn('node', [SERVER_JS], { env: { ...process.env, ...env, PORT: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
}

async function startLauncher(env) {
  const child = spawnLauncher(env);
  const port = await new Promise((resolve, reject) => {
    let out = '';
    child.stdout.on('data', (d) => {
      out += d.toString();
      const bound = /listening on :(\d+)/.exec(out);
      if (bound) resolve(Number(bound[1]));
    });
    child.on('exit', (code) => reject(new Error(`launcher exited early (code ${code}): ${out}`)));
  });
  return { child, port };
}

function request(port, method, urlPath, { body, auth } = {}) {
  return new Promise((resolve, reject) => {
    const headers = { 'Content-Type': 'application/json' };
    if (auth) headers.Authorization = auth;
    const req = http.request({ host: '127.0.0.1', port, path: urlPath, method, headers }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }));
    });
    req.on('error', reject);
    req.end(body === undefined ? undefined : JSON.stringify(body));
  });
}

const E2B_ENV = { SANDBOX_BACKEND: 'e2b', E2B_API_KEY: 'fake-e2b-key', SANDBOX_API_KEY: 'testkey' };

test('only POST /rca and POST /triage reach the analyzers; every other route is 404', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const [method, urlPath] of [['GET', '/rca'], ['POST', '/grade'], ['POST', '/rca/extra'], ['PUT', '/triage']]) {
      assert.deepEqual(
        await request(port, method, urlPath, { auth: 'Bearer testkey' }),
        { status: 404, body: { error: 'not found' } },
        `${method} ${urlPath}`,
      );
    }
  } finally {
    child.kill();
  }
});

test('a request without the launcher key is 401 before its body is read', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const auth of [undefined, 'Bearer wrong', 'testkey', 'Basic testkey']) {
      assert.deepEqual(
        await request(port, 'POST', '/rca', { auth, body: {} }),
        { status: 401, body: { error: 'unauthorized' } },
        `Authorization: ${auth}`,
      );
    }
  } finally {
    child.kill();
  }
});

const MCP = { url: 'https://tessary.example.com/mcp', token: 't' };
const BAD_CREDENTIALS = [
  [undefined, 'no credential object on the request'],
  [{ provider: 'NOPE', api_key: 'k' }, "unknown provider 'NOPE'"],
  [{ provider: 'BEDROCK', aws_access_key: 'a', aws_secret_key: 's' }, 'BEDROCK/BEDROCK_MANTLE credential is missing aws_region'],
  [{ provider: 'BEDROCK_MANTLE', aws_region: 'us-east-1' }, 'BEDROCK/BEDROCK_MANTLE credential is missing aws_access_key/aws_secret_key'],
  [{ provider: 'GEMINI', api_key: '  ' }, 'GEMINI credential is missing api_key'],
  [{ provider: 'CUSTOM', api_key: 'k' }, 'CUSTOM credential is missing base_url'],
];

test('an unusable credential is the caller\'s bad_request, named, before any sandbox is spent', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const [credential, why] of BAD_CREDENTIALS) {
      const res = await request(port, 'POST', '/rca', { auth: 'Bearer testkey', body: { mcp: MCP, credential } });
      assert.equal(res.status, 502);
      assert.equal(res.body.kind, 'bad_request', why);
      assert.ok(res.body.detail.startsWith(`rca.js: missing or invalid credential (${why}`), res.body.detail);
    }
  } finally {
    child.kill();
  }
});

test('a docker backend with no SANDBOX_WORK_VOLUME refuses to boot', async () => {
  const child = spawnLauncher({ SANDBOX_BACKEND: 'docker', SANDBOX_WORK_VOLUME: '', SANDBOX_API_KEY: 'testkey' });
  let stderr = '';
  child.stderr.on('data', (d) => { stderr += d.toString(); });
  const code = await new Promise((resolve) => child.on('exit', resolve));
  assert.equal(code, 1, 'it must exit rather than serve requests that will each fail at mount time');
  assert.match(stderr, /requires SANDBOX_WORK_VOLUME/);
});
