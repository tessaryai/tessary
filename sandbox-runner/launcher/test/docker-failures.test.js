// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * How the docker backend fails, and what it cleans up when it does. Same approach as
 * docker-backend.test.js (the real server.js as a child process, a fake Engine API on a temp unix
 * socket), with a daemon each test can reshape: a container that exits non-zero, one that outlives
 * its deadline, one that floods stdout, a pull that fails mid-stream, a create the daemon refuses,
 * and the startup reconciliation pass against a daemon holding stale sandboxes.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SERVER_JS = path.join(__dirname, '..', 'server.js');
const CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'test-akid', aws_secret_key: 'test-secret' };
const FAILURE_ENVELOPE = JSON.stringify({ is_error: true, error: 'agent gave up', usage: { input_tokens: 90, cache_read_input_tokens: 4 } });

function frame(streamType, text) {
  const payload = Buffer.isBuffer(text) ? text : Buffer.from(text);
  const header = Buffer.alloc(8);
  header.writeUInt8(streamType, 0);
  header.writeUInt32BE(payload.length, 4);
  return Buffer.concat([header, payload]);
}

// `shape` overrides one behaviour at a time; anything it leaves alone answers as a healthy daemon.
function startDaemon(socketPath, shape = {}) {
  const calls = [];
  let waiter = null;
  let imagePresent = !shape.imageMissing;
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const url = req.url || '';
      calls.push(`${req.method} ${url}`);
      const send = (status, obj) => {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(obj === undefined ? '' : JSON.stringify(obj));
      };
      if (req.method === 'GET' && /^\/images\//.test(url)) return send(imagePresent ? 200 : 404, {});
      if (req.method === 'POST' && url.startsWith('/images/create')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end((shape.pullFrames || [{ status: 'Pull complete' }]).map((f) => JSON.stringify(f)).join('\n') + '\n');
      }
      if (req.method === 'GET' && /^\/networks\//.test(url)) return send(shape.networkExists ? 200 : 404, {});
      if (req.method === 'POST' && url === '/networks/create') return send(shape.networkCreateStatus || 201, {});
      if (req.method === 'GET' && url.startsWith('/containers/json')) {
        return shape.listStatus ? send(shape.listStatus, {}) : send(200, shape.containers || []);
      }
      if (req.method === 'GET' && /^\/containers\/[^/]+\/json$/.test(url)) {
        return send(200, { NetworkSettings: { Networks: { tessary_tessary: {} } } });
      }
      if (req.method === 'POST' && url === '/containers/create') return send(shape.createStatus || 201, { Id: 'c1' });
      if (req.method === 'POST' && /\/start$/.test(url)) return send(204);
      if (req.method === 'GET' && /\/logs/.test(url)) {
        res.writeHead(200, { 'Content-Type': 'application/vnd.docker.raw-stream' });
        for (const [streamType, text] of shape.logs || [[1, JSON.stringify({ raw: 'ok' })]]) res.write(frame(streamType, text));
        return res.end();
      }
      if (req.method === 'POST' && /\/wait$/.test(url)) {
        if (shape.waitUntilKilled) { waiter = () => send(200, { StatusCode: 137 }); return undefined; }
        return send(200, { StatusCode: shape.exitCode || 0 });
      }
      if (req.method === 'POST' && /\/kill$/.test(url)) {
        send(204);
        if (waiter) { waiter(); waiter = null; }
        return undefined;
      }
      if (req.method === 'POST' && /\/stop/.test(url)) return send(204);
      if (req.method === 'DELETE') return send(204);
      return send(404, { message: `unhandled ${req.method} ${url}` });
    });
  });
  return new Promise((resolve) => server.listen(socketPath, () => resolve({ server, calls })));
}

async function withLauncher(shape, env, fn) {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'docker-failures-'));
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir);
  const daemon = await startDaemon(socketPath, shape);
  const child = spawn('node', [SERVER_JS], {
    env: {
      ...process.env,
      SANDBOX_BACKEND: 'docker',
      DOCKER_SOCKET_PATH: socketPath,
      AGENT_IMAGE: 'test-agent:latest',
      LAUNCHER_WORK_DIR: workDir,
      SANDBOX_WORK_VOLUME: 'test-work-volume',
      SANDBOX_API_KEY: 'testkey',
      HOSTNAME: 'self',
      ...(typeof env === 'function' ? env(scratch) : env),
      PORT: '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  child.stderr.on('data', (d) => { log += d.toString(); });
  try {
    const port = await new Promise((resolve, reject) => {
      let out = '';
      child.stdout.on('data', (d) => {
        out += d.toString();
        const bound = /listening on :(\d+)/.exec(out);
        if (bound) resolve(Number(bound[1]));
      });
      child.on('exit', (code) => reject(new Error(`launcher exited early (code ${code}): ${out}${log}`)));
    });
    const post = (body) => new Promise((resolve, reject) => {
      const req = http.request(
        { host: '127.0.0.1', port, path: '/triage', method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer testkey' } },
        (res) => {
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }));
        },
      );
      req.on('error', reject);
      req.end(JSON.stringify({ prompt: 'x', mcp: {}, timeout_ms: 10000, credential: CREDENTIAL, ...body }));
    });
    return await fn({ post, calls: daemon.calls, workDir, stderr: () => log });
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
}

async function until(condition, what) {
  const deadline = Date.now() + 5000;
  while (!condition()) {
    assert.ok(Date.now() < deadline, `timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

function withoutElapsed(body) {
  assert.equal(typeof body.elapsed_ms, 'number');
  const { elapsed_ms: _elapsed, ...rest } = body;
  return rest;
}

test('a container that exits non-zero answers script_exit with its spend, and its stderr stays on the console', async () => {
  await withLauncher(
    { exitCode: 3, logs: [[2, 'fatal: https://x-access-token:ghs_secret@github.com/acme/app'], [1, FAILURE_ENVELOPE]] },
    {},
    async ({ post, calls, workDir, stderr }) => {
      const { status, body } = await post({});

      assert.equal(status, 502);
      assert.deepEqual(withoutElapsed(body), {
        error: 'sandbox orchestration failed',
        kind: 'script_exit',
        timeout: false,
        error_class: 'Error',
        detail: 'triage.js exited 3 (output withheld; see launcher logs)',
        exit_code: 3,
        script: 'triage.js',
        timeout_ms: 10000,
        usage: { input_tokens: 90, cache_read_input_tokens: 4 },
      });
      assert.ok(calls.includes('DELETE /containers/c1?force=true'), 'the container is removed after a failed run');
      assert.deepEqual(fs.readdirSync(workDir), [], 'and so is its work dir');
      assert.match(stderr(), /x-access-token:\*\*\*@github\.com/, 'the console copy is scrubbed');
      assert.doesNotMatch(stderr(), /ghs_secret/);
    },
  );
});

test('a truncated failure envelope books no spend and still reports the exit', async () => {
  await withLauncher({ exitCode: 2, logs: [[1, '{"is_error":true,"usage":{"input_tok']] }, {}, async ({ post }) => {
    const { status, body } = await post({});

    assert.equal(status, 502);
    assert.equal(body.kind, 'script_exit');
    assert.equal(body.exit_code, 2);
    assert.equal(body.usage, undefined);
  });
});

test('a container still running at its deadline is killed and reported as the deadline', async () => {
  await withLauncher({ waitUntilKilled: true }, {}, async ({ post, calls }) => {
    const { status, body } = await post({ timeout_ms: 300 });

    assert.equal(status, 502);
    assert.equal(body.kind, 'timeout');
    assert.equal(body.timeout, true);
    assert.equal(body.detail, `deadline hit: no result after ${body.elapsed_ms}ms of 300ms`);
    assert.ok(calls.includes('POST /containers/c1/kill'));
  });
});

test('a container flooding stdout past the cap is killed rather than buffered', async () => {
  const oneMiB = Buffer.alloc(1024 * 1024, 'a');
  await withLauncher({ logs: Array.from({ length: 17 }, () => [1, oneMiB]) }, {}, async ({ post, calls }) => {
    const { status, body } = await post({});

    assert.equal(status, 502);
    assert.equal(body.detail, 'triage.js stdout exceeded 16777216 bytes');
    assert.ok(calls.includes('POST /containers/c1/kill'), 'a runaway agent must not keep running');
  });
});

test('stdout that is not JSON is bad_output, and none of it reaches the response', async () => {
  await withLauncher({ logs: [[1, 'I think the answer is yes']] }, {}, async ({ post }) => {
    const { status, body } = await post({});

    assert.equal(status, 502);
    assert.equal(body.kind, 'bad_output');
    assert.equal(body.detail, 'triage.js returned unparseable output (25 bytes)');
    assert.doesNotMatch(JSON.stringify(body), /answer is yes/);
  });
});

test('a create the daemon refuses leaves no work dir behind', async () => {
  await withLauncher({ createStatus: 500 }, {}, async ({ post, workDir }) => {
    const { status, body } = await post({});

    assert.equal(status, 502);
    assert.equal(body.kind, 'orchestration');
    assert.equal(body.detail, 'docker POST /containers/create -> 500');
    assert.deepEqual(fs.readdirSync(workDir), []);
  });
});

test('a work dir that cannot be created fails the run before any container exists', async () => {
  await withLauncher({}, (scratch) => {
    const notADir = path.join(scratch, 'occupied');
    fs.writeFileSync(notADir, '');
    return { LAUNCHER_WORK_DIR: path.join(notADir, 'work') };
  }, async ({ post, calls }) => {
    const { status, body } = await post({});

    assert.equal(status, 502);
    assert.equal(body.kind, 'orchestration');
    assert.equal(body.script, 'triage.js', 'the failure still names the run it belongs to');
    assert.equal(body.timeout_ms, 10000);
    assert.ok(!calls.includes('POST /containers/create'));
  });
});

test('a pull that fails mid-stream fails the run, is retried by the next one, and pulls :latest for an untagged image', async () => {
  await withLauncher(
    { imageMissing: true, pullFrames: [{ status: 'Pulling from test-agent' }, { error: 'manifest unknown' }, { error: 'second' }] },
    { AGENT_IMAGE: 'test-agent' },
    async ({ post, calls }) => {
      const first = await post({});
      const second = await post({});

      assert.equal(first.status, 502);
      assert.equal(first.body.detail, 'docker image pull test-agent -> 200: manifest unknown');
      assert.equal(second.status, 502, 'a failed pull is never cached as a present image');
      const pulls = calls.filter((c) => c.startsWith('POST /images/create'));
      assert.deepEqual(pulls, ['POST /images/create?fromImage=test-agent&tag=latest', 'POST /images/create?fromImage=test-agent&tag=latest']);
    },
  );
});

test('startup reaps only the sandboxes older than their own declared deadline, and serves regardless', async () => {
  const nowS = Math.floor(Date.now() / 1000);
  const containers = [
    { Id: 'stale', Created: nowS - 3600, Labels: { 'tessary.sandbox.timeout-ms': '60000' } },
    { Id: 'live', Created: nowS - 60, Labels: { 'tessary.sandbox.timeout-ms': '600000' } },
    { Id: 'unlabelled', Created: 0 },
  ];
  await withLauncher({ containers }, {}, async ({ calls, post }) => {
    await until(() => calls.includes('DELETE /containers/unlabelled?force=true'), 'the reaper to finish');

    assert.deepEqual(
      calls.filter((c) => /stop|DELETE/.test(c)),
      ['POST /containers/stale/stop?t=1', 'DELETE /containers/stale?force=true',
        'POST /containers/unlabelled/stop?t=1', 'DELETE /containers/unlabelled?force=true'],
      'the live run is left alone',
    );
    assert.equal((await post({})).status, 200);
  });
});

test('startup survives a daemon that cannot list containers or create the isolated bridge', async () => {
  await withLauncher({ listStatus: 500, networkCreateStatus: 500 }, { SANDBOX_NETWORK_ISOLATION: '1' }, async ({ calls, post, stderr }) => {
    await until(() => /could not list sandbox containers/.test(stderr()), 'the reaper warning');

    assert.match(stderr(), /could not ensure sandbox network 'tessary-sandbox'/);
    assert.ok(calls.includes('POST /networks/create'));
    assert.equal((await post({})).status, 200, 'both are per-run problems at worst, never a boot failure');
  });
});

test('an isolated bridge that already exists is not created again', async () => {
  await withLauncher({ networkExists: true }, { SANDBOX_NETWORK_ISOLATION: '1' }, async ({ calls, post }) => {
    assert.equal((await post({})).status, 200);
    assert.ok(calls.includes('GET /networks/tessary-sandbox'));
    assert.ok(!calls.includes('POST /networks/create'));
  });
});
