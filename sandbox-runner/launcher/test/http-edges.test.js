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

/** A POST with a raw body, resolving to the response or to the error that cut the connection. */
function rawPost(port, urlPath, raw) {
  return new Promise((resolve) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: urlPath, method: 'POST', headers: { Authorization: 'Bearer testkey' } },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }));
      },
    );
    req.on('error', (error) => resolve({ error }));
    req.end(raw);
  });
}

test('answers its health check', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    assert.deepEqual(await request(port, 'GET', '/healthz'), { status: 200, body: { ok: true } });
  } finally {
    child.kill();
  }
});

test('refuses a body that is not JSON, reads an empty one as no fields, and without echoing the input', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    const garbled = await rawPost(port, '/rca', '{"credential": tsy_secret');
    assert.equal(garbled.status, 502);
    assert.equal(garbled.body.kind, 'bad_request');
    assert.equal(garbled.body.detail, 'invalid json body');

    const empty = await rawPost(port, '/rca', '');
    assert.equal(empty.body.kind, 'bad_request');
    assert.match(empty.body.detail, /no credential object on the request/);
  } finally {
    child.kill();
  }
});

test('cuts off a body over the size limit rather than reading it all, and keeps serving', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    const res = await rawPost(port, '/rca', Buffer.alloc(9 * 1024 * 1024, 'a'));
    assert.ok(res.error || res.body.detail === 'body too large', JSON.stringify(res.body ?? String(res.error)));
    assert.deepEqual(await request(port, 'GET', '/healthz'), { status: 200, body: { ok: true } });
  } finally {
    child.kill();
  }
});

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
  [{ provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'a' }, 'BEDROCK/BEDROCK_MANTLE credential is missing aws_access_key/aws_secret_key'],
  [{ provider: 'GEMINI', api_key: '  ' }, 'GEMINI credential is missing api_key'],
  [{ provider: 'GEMINI' }, 'GEMINI credential is missing api_key'],
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
