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
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { spawnLauncher, startLauncher, request, tempDir } = require('./fixtures/launcher-harness');

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

test('only POST /rca, /triage and /runs reach the analyzers; every other route is 404, even one that is not a URL path (a // that crashed the launcher)', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const [method, urlPath] of [['GET', '/rca'], ['POST', '/grade'], ['POST', '/rca/extra'], ['PUT', '/triage'], ['GET', '/runs'], ['POST', '/runs/extra'], ['POST', '//']]) {
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
  [{ provider: 'OPENAI', egress_secret: 's' }, 'egress_secret is only supported on an ANTHROPIC credential'],
];

test('an unusable credential is the caller\'s bad_request, named, before any sandbox is spent', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const [credential, why] of BAD_CREDENTIALS) {
      const res = await request(port, 'POST', '/rca', { auth: 'Bearer testkey', body: { mcp: MCP, credential } });
      assert.equal(res.status, 502);
      assert.equal(res.body.kind, 'bad_request', why);
      assert.ok(res.body.detail.startsWith(`rca: missing or invalid credential (${why}`), res.body.detail);
    }
  } finally {
    child.kill();
  }
});

test('an egress credential on the docker backend is the caller\'s bad_request: only E2B injects at egress', async () => {
  const { child, port } = await startLauncher({ SANDBOX_BACKEND: 'docker', SANDBOX_WORK_VOLUME: 'vol', SANDBOX_API_KEY: 'testkey' });
  try {
    const credential = { provider: 'ANTHROPIC', egress_secret: 'tessary-ai-anthropic' };
    const res = await request(port, 'POST', '/rca', { auth: 'Bearer testkey', body: { mcp: MCP, credential } });
    assert.equal(res.status, 502);
    assert.equal(res.body.kind, 'bad_request');
    assert.equal(res.body.detail, 'rca: an egress_secret credential needs SANDBOX_BACKEND=e2b (got docker)');
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

/**
 * The real server.js in a directory of its own, beside the IMAGE_VERSION file the launcher image writes. Resolves once it
 * either listens or exits, with its output and exit code (null while it still runs).
 */
async function bootReleasedLauncher(imageVersion, template) {
  const dir = tempDir('launcher-release-');
  fs.copyFileSync(path.join(__dirname, '..', 'server.js'), path.join(dir, 'server.js'));
  fs.writeFileSync(path.join(dir, 'IMAGE_VERSION'), `${imageVersion}\n`);
  const env = { ...process.env, ...E2B_ENV, E2B_ANALYZER_TEMPLATE: template, PORT: '0' };
  const child = spawn('node', [path.join(dir, 'server.js')], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  let timer;
  const code = await new Promise((resolve) => {
    const onData = (d) => {
      output += d.toString();
      if (/listening on :\d+/.test(output)) resolve(null);
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', resolve);
    timer = setTimeout(() => resolve('timeout'), 5000);
  });
  clearTimeout(timer);
  child.kill();
  return { code, output };
}

const TEMPLATE = 'tessary/tessary-agent-sandbox';

test('an e2b template from another release refuses to boot (a 1.11.2 launcher runs lane.js, which a 1.6.0 template lacks, so every lane exits 1)', async () => {
  const { code, output } = await bootReleasedLauncher('1.11.2', `${TEMPLATE}:1.6.0`);
  assert.equal(code, 1, `it must exit rather than serve lanes the template cannot run: ${output}`);
  assert.match(output, /E2B_ANALYZER_TEMPLATE=tessary\/tessary-agent-sandbox:1\.6\.0 is not this launcher's release 1\.11\.2/);
});

test('an e2b template of the same release, a floating tag, or a local build boots (the release check must not block a matching install)', async () => {
  for (const [imageVersion, template] of [['1.11.2', `${TEMPLATE}:1.11.2`], ['1.11.2', `${TEMPLATE}:latest`], ['dev', `${TEMPLATE}:1.6.0`]]) {
    const { code, output } = await bootReleasedLauncher(imageVersion, template);
    assert.equal(code, null, `launcher ${imageVersion} with ${template} must boot: ${output}`);
  }
});

// ---- Launcher sessions: the front door ------------------------------------------------------

const SESSION_START = {
  lane: 'agent_run',
  system_prompt: 'You help.',
  files: {},
  model: 'global.anthropic.claude-sonnet-4-6',
  provider: 'BEDROCK',
  credential: { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'a', aws_secret_key: 's' },
  mcp_servers: [{ name: 'tessary', url: 'https://tessary.example.com/mcp', token: 'tsy_a_tok1' }],
  idle_timeout_ms: 60000,
  max_tool_calls_per_message: 40,
};

const SESSION_ROUTES = [
  ['POST', '/sessions'],
  ['POST', '/sessions/x/messages'],
  ['POST', '/sessions/x/abort'],
  ['GET', '/sessions/x/events'],
  ['DELETE', '/sessions/x'],
];

test('every session route needs the launcher key before its body is read (a session route open to anyone who reaches the launcher)', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const [method, urlPath] of SESSION_ROUTES) {
      for (const auth of [undefined, 'Bearer wrong']) {
        assert.deepEqual(
          await request(port, method, urlPath, { auth, body: method === 'POST' ? SESSION_START : undefined }),
          { status: 401, body: { error: 'unauthorized' } },
          `${method} ${urlPath} with ${auth}`,
        );
      }
    }
  } finally {
    child.kill();
  }
});

test('a session path with another method or segment is 404 (a malformed session path that reaches the session router or a one-shot route)', async () => {
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    for (const [method, urlPath] of [
      ['GET', '/sessions'], ['DELETE', '/sessions'], ['PUT', '/sessions/x'], ['POST', '/sessions/x'],
      ['GET', '/sessions/x/messages'], ['POST', '/sessions/x/events'], ['POST', '/sessions/x/other'], ['POST', '/sessions/x/abort/y'],
    ]) {
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

test('POST /sessions refuses a bad body before any sandbox is spent (a caller error that spends a sandbox and reads as a retryable outage)', async () => {
  // A docker backend whose socket does not exist: a start that got past validation would fail as
  // an orchestration error at its first daemon call, never as bad_request.
  const scratch = tempDir('http-edges-sessions-');
  const { child, port } = await startLauncher({
    SANDBOX_BACKEND: 'docker',
    DOCKER_SOCKET_PATH: path.join(scratch, 'missing.sock'),
    SANDBOX_WORK_VOLUME: 'vol',
    LAUNCHER_WORK_DIR: path.join(scratch, 'work'),
    SANDBOX_API_KEY: 'testkey',
  });
  try {
    const garbled = await rawPost(port, '/sessions', '{"lane": tsy_secret');
    assert.equal(garbled.status, 502);
    assert.equal(garbled.body.kind, 'bad_request');
    assert.equal(garbled.body.detail, 'invalid json body');

    const rows = [
      [{ lane: undefined }, 'session.js: lane must be a non-empty string'],
      [{ lane: '' }, 'session.js: lane must be a non-empty string'],
      [{ credential: undefined }, 'session.js: missing or invalid credential (no credential object on the request'],
      [{ credential: { provider: 'GEMINI' } }, 'session.js: missing or invalid credential (GEMINI credential is missing api_key'],
      [{ model: '' }, 'session.js: model must be a non-empty string'],
      [{ model: 7 }, 'session.js: model must be a non-empty string'],
      [{ idle_timeout_ms: undefined }, 'session.js: idle_timeout_ms must be a positive integer'],
      [{ idle_timeout_ms: 0 }, 'session.js: idle_timeout_ms must be a positive integer'],
      [{ idle_timeout_ms: 1.5 }, 'session.js: idle_timeout_ms must be a positive integer'],
      [{ idle_timeout_ms: '60000' }, 'session.js: idle_timeout_ms must be a positive integer'],
      [{ idle_timeout_ms: 86400001 }, 'session.js: idle_timeout_ms must be at most 86400000'],
      [{ credential: { provider: 'ANTHROPIC', egress_secret: 'tessary-ai-anthropic' } }, 'session.js: an egress_secret credential needs SANDBOX_BACKEND=e2b (got docker)'],
      [{ system_prompt: undefined }, 'session.js: system_prompt must be a non-empty string'],
      [{ system_prompt: '' }, 'session.js: system_prompt must be a non-empty string'],
      [{ files: [] }, 'session.js: files must map relative paths to strings'],
      [{ files: { 'a.md': 7 } }, 'session.js: files must map relative paths to strings'],
      [{ mcp_servers: undefined }, 'session.js: mcp_servers must be an array'],
      [{ mcp_servers: [{ name: 'Tessary', url: 'http://backend:8080/mcp', token: 't' }] }, 'session.js: each mcp_servers name must match ^[a-z][a-z0-9-]{0,31}$'],
      [{ mcp_servers: [{ name: 'x_y', url: 'http://backend:8080/mcp', token: 't' }] }, 'session.js: each mcp_servers name must match ^[a-z][a-z0-9-]{0,31}$'],
      [{ mcp_servers: [SESSION_START.mcp_servers[0], SESSION_START.mcp_servers[0]] }, 'session.js: mcp_servers name repeated: tessary'],
      [{ mcp_servers: [{ name: 'tessary', url: 'http://backend:8080/mcp' }] }, 'session.js: mcp_servers tessary needs a url and a token'],
      [{ max_tool_calls_per_message: undefined }, 'session.js: max_tool_calls_per_message must be an integer from 1 to 1000'],
      [{ max_tool_calls_per_message: 0 }, 'session.js: max_tool_calls_per_message must be an integer from 1 to 1000'],
      [{ max_tool_calls_per_message: 1001 }, 'session.js: max_tool_calls_per_message must be an integer from 1 to 1000'],
      [{ max_tool_calls_per_message: 2.5 }, 'session.js: max_tool_calls_per_message must be an integer from 1 to 1000'],
    ];
    for (const [change, detail] of rows) {
      const res = await request(port, 'POST', '/sessions', { auth: 'Bearer testkey', body: { ...SESSION_START, ...change } });
      assert.equal(res.status, 502, JSON.stringify(change));
      assert.equal(res.body.kind, 'bad_request', JSON.stringify(change));
      assert.ok(res.body.detail.startsWith(detail), `${JSON.stringify(change)}: ${res.body.detail}`);
    }
  } finally {
    child.kill();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

test('on the e2b backend a session whose MCP server has no url or points at localhost is refused before any sandbox (a session sandbox spent on a callback the microVM can never reach)', async () => {
  // No fake SDK: the refusal comes before the launcher requires `e2b`, which is not installed here.
  const { child, port } = await startLauncher(E2B_ENV);
  try {
    const publicServer = SESSION_START.mcp_servers[0];
    for (const url of ['http://localhost:8000', 'http://127.0.0.1:8000', 'http://[::1]:8000', 'http://0.0.0.0:8000', 'http://[::ffff:127.0.0.1]:8000', undefined, 7]) {
      const body = { ...SESSION_START, mcp_servers: [publicServer, { name: 'notes', url, token: 'tsy_a_tok1' }] };
      const res = await request(port, 'POST', '/sessions', { auth: 'Bearer testkey', body });
      assert.equal(res.status, 502, String(url));
      assert.equal(res.body.kind, 'bad_request', String(url));
      assert.equal(res.body.detail, 'session.js: mcp_servers[1].url is missing or unreachable from an E2B microVM', String(url));
    }
  } finally {
    child.kill();
  }
});

test('a session env value that is not a positive integer refuses to boot (a bad env that makes every session timer fire at once)', async () => {
  for (const [name, value] of [['SANDBOX_SESSION_CAP', 'abc'], ['SANDBOX_SESSION_BUFFER', '0'], ['SANDBOX_SESSION_MAX_MS', '-5'], ['SANDBOX_SESSION_PORT', '1.5']]) {
    const child = spawnLauncher({ ...E2B_ENV, [name]: value });
    let stderr = '';
    child.stderr.on('data', (d) => { stderr += d.toString(); });
    const killer = setTimeout(() => child.kill(), 3000);
    const code = await new Promise((resolve) => child.on('exit', resolve));
    clearTimeout(killer);
    assert.equal(code, 1, `${name}=${value} must stop the boot, not make every timer fire at once`);
    assert.match(stderr, new RegExp(`${name} must be a positive integer`));
  }
});
