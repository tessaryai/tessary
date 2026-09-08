// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * First test coverage for server.js (none existed before #855). Pure `node:test`, zero new
 * dependency — matches the repo's own "no new npm dependency" constraint on the docker backend
 * itself (see server.js's Docker-backend header comment).
 *
 * Rather than mocking server.js's internals (which are module-private, deliberately — this file
 * is a single flat script, not a library), this test runs the REAL server.js as a child process
 * against a FAKE Docker daemon: a plain node:http server listening on a temp unix socket, given
 * to the child via DOCKER_SOCKET_PATH. That fake daemon is what asserts the two things the plan
 * called out as needing coverage:
 *   1. The container-create hardening flags (CapDrop, SecurityOpt, memory/cpu/pids limits,
 *      network not "none", exactly one work-dir mount) — by capturing the real POST body
 *      runScriptInDockerInner sends.
 *   2. SANDBOX_DOCKER_CONCURRENCY=1 actually serializes two concurrent requests — by timing when
 *      each request's /containers/create arrives at the fake daemon.
 *
 * Manual/documented end-to-end proof against a REAL Docker daemon (this test's necessary
 * complement, not a redundant duplicate — a fake daemon cannot prove the container that gets
 * created actually runs, only that the launcher ASKED for the right thing) lives in
 * sandbox-runner/README.md, per #855's own plan.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SERVER_JS = path.join(__dirname, '..', 'server.js');

// #939 D4: every /rca and /triage request now carries its own `credential` object — the launcher
// no longer reads a provider secret from process.env, so every payload below needs one. A BEDROCK
// credential mirrors what these tests' AWS_REGION env var used to feed providerConfig() directly.
const BEDROCK_CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'test-akid', aws_secret_key: 'test-secret' };

// Minimal fake Docker Engine API: just enough of the five request shapes runScriptInDockerInner
// makes (create/start/wait/logs/remove) plus the two startup-reconciliation calls
// (ensureSandboxNetwork/reapOrphanSandboxContainers) for the launcher to boot cleanly.
function startFakeDaemon(
  socketPath,
  { waitDelayMs = 0, imageMissing = false, waitStatusCode = 0, selfNetworks = ['evals-platform_evals'] } = {},
) {
  const createBodies = [];
  const networkCreates = [];
  const createTimestamps = [];
  const imagePullUrls = [];
  let nextId = 1;
  let imagePresent = !imageMissing;

  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const bodyText = Buffer.concat(chunks).toString('utf8');
      const url = req.url || '';
      const send = (status, obj) => {
        const text = obj === undefined ? '' : JSON.stringify(obj);
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(text);
      };

      // By default AGENT_IMAGE is always "present" on the fake daemon — the two tests below are
      // about the container-create shape and concurrency serialization, not the image-pull path.
      // The `imageMissing` option (used by the pull test further down) starts it absent and
      // flips it present once /images/create is hit, mirroring a real daemon after a pull.
      if (req.method === 'GET' && /^\/images\//.test(url)) return send(imagePresent ? 200 : 404, imagePresent ? { Id: 'sha256:fake' } : { message: 'no such image' });
      if (req.method === 'POST' && url.startsWith('/images/create')) {
        imagePullUrls.push(url);
        imagePresent = true;
        // Newline-delimited pull-progress JSON, same shape a real daemon streams.
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'Pull complete' }) + '\n');
        return;
      }
      if (req.method === 'GET' && /^\/networks\//.test(url)) return send(404, { message: 'not found' });
      if (req.method === 'POST' && url === '/networks/create') {
        networkCreates.push(JSON.parse(bodyText));
        return send(201, { Id: 'net1' });
      }
      if (req.method === 'GET' && url.startsWith('/containers/json')) return send(200, []);
      // Self-inspection: how the launcher learns its OWN network when isolation is off. `selfNetworks`
      // null makes the lookup fail, which is the fallback path.
      if (req.method === 'GET' && /^\/containers\/[^/]+\/json$/.test(url)) {
        if (selfNetworks === null) return send(404, { message: 'no such container' });
        const Networks = {};
        for (const n of selfNetworks) Networks[n] = { NetworkID: `id-${n}` };
        return send(200, { Id: 'self', NetworkSettings: { Networks } });
      }

      if (req.method === 'POST' && url === '/containers/create') {
        createTimestamps.push(Date.now());
        createBodies.push(JSON.parse(bodyText));
        return send(201, { Id: `c${nextId++}` });
      }
      if (req.method === 'POST' && /\/containers\/[^/]+\/start$/.test(url)) return send(204);
      if (req.method === 'GET' && /\/containers\/[^/]+\/logs/.test(url)) {
        // Multiplexed frame: stream type 1 (stdout), then a 4-byte big-endian length, then the
        // payload — see server.js's createDockerLogDemuxer for the format this mirrors.
        const payload = Buffer.from(JSON.stringify({ raw: 'fake-ok' }));
        const header = Buffer.alloc(8);
        header.writeUInt8(1, 0);
        header.writeUInt32BE(payload.length, 4);
        res.writeHead(200, { 'Content-Type': 'application/vnd.docker.raw-stream' });
        res.write(Buffer.concat([header, payload]));
        // Left open briefly then ended, like a real follow=1 stream once the container exits.
        setTimeout(() => res.end(), Math.min(waitDelayMs, 50));
        return;
      }
      if (req.method === 'POST' && /\/containers\/[^/]+\/wait$/.test(url)) {
        setTimeout(() => send(200, { StatusCode: waitStatusCode }), waitDelayMs);
        return;
      }
      if (req.method === 'POST' && /\/containers\/[^/]+\/kill$/.test(url)) return send(204);
      if (req.method === 'DELETE' && /^\/containers\//.test(url)) return send(204);
      return send(404, { message: `unhandled ${req.method} ${url}` });
    });
  });

  return new Promise((resolve) => {
    server.listen(socketPath, () => resolve({ server, createBodies, createTimestamps, imagePullUrls, networkCreates }));
  });
}

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

test('docker backend: container-create carries the required hardening flags', async () => {
  const scratch = tempDir('docker-backend-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });

  const daemon = await startFakeDaemon(socketPath, { waitDelayMs: 20 });
  const port = 18500 + Math.floor(Math.random() * 500);
  const child = await startLauncher({
    SANDBOX_BACKEND: 'docker',
    DOCKER_SOCKET_PATH: socketPath,
    AGENT_IMAGE: 'test-agent:latest',
    LAUNCHER_WORK_DIR: workDir,
    SANDBOX_WORK_VOLUME: 'test-work-volume', // never created — the fake daemon never mounts it
    SANDBOX_API_KEY: 'testkey',
    PORT: String(port),
    SANDBOX_DOCKER_CONCURRENCY: '1',
    AWS_REGION: 'us-east-1',
  });

  try {
    const res = await postJson(port, '/triage', {
      files: [], prompt: 'x', json_schema: {}, mcp: {}, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL,
    }, 'testkey');
    assert.equal(res.status, 200);
    assert.deepEqual(JSON.parse(res.body), { raw: 'fake-ok' });

    assert.equal(daemon.createBodies.length, 1);
    const create = daemon.createBodies[0];
    const hostConfig = create.HostConfig;
    assert.deepEqual(hostConfig.CapDrop, ['ALL']);
    assert.deepEqual(hostConfig.SecurityOpt, ['no-new-privileges']);
    assert.ok(hostConfig.Memory > 0, 'Memory limit must be set');
    assert.ok(hostConfig.NanoCpus > 0, 'CPU limit must be set');
    assert.ok(hostConfig.PidsLimit > 0, 'PidsLimit must be set');
    assert.notEqual(hostConfig.NetworkMode, 'none', 'network must stay on (egress required)');
    assert.notEqual(hostConfig.NetworkMode, 'host', 'must never use host networking');
    assert.equal(hostConfig.Mounts.length, 1, 'exactly one work-dir mount, nothing else from the host');
    assert.equal(hostConfig.Mounts[0].Type, 'volume');
    assert.equal(hostConfig.Mounts[0].Source, 'test-work-volume');
    assert.equal(hostConfig.Mounts[0].Target, '/work');
    assert.ok(hostConfig.Mounts[0].VolumeOptions.Subpath, 'the per-request subdirectory is named');
    assert.equal(create.Labels['tessary.sandbox'], '1');
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

test('docker backend: SANDBOX_DOCKER_CONCURRENCY=1 serializes two concurrent runs', async () => {
  const scratch = tempDir('docker-backend-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });

  const WAIT_MS = 200;
  const daemon = await startFakeDaemon(socketPath, { waitDelayMs: WAIT_MS });
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
    const payload = { files: [], prompt: 'x', json_schema: {}, mcp: {}, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL };
    const [a, b] = await Promise.all([
      postJson(port, '/triage', payload, 'testkey'),
      postJson(port, '/triage', payload, 'testkey'),
    ]);
    assert.equal(a.status, 200);
    assert.equal(b.status, 200);
    assert.equal(daemon.createTimestamps.length, 2);
    // Serialized at concurrency 1: the second container-create cannot start until the first
    // request's own /wait has already resolved (WAIT_MS after ITS create). A concurrency bug
    // (no serialization) would let both creates land back-to-back instead.
    const gap = daemon.createTimestamps[1] - daemon.createTimestamps[0];
    assert.ok(gap >= WAIT_MS * 0.8, `expected the second run to wait for the first (gap=${gap}ms, want >= ~${WAIT_MS}ms)`);
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

test('docker backend: pulls AGENT_IMAGE when the daemon does not already have it', async () => {
  const scratch = tempDir('docker-backend-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });

  // On a fresh self-host the daemon has never seen AGENT_IMAGE (nobody manually `docker pull`-ed
  // it, and it's never a compose `image:`, only an env var — see server.js's ensureAgentImage
  // comment). The raw Engine API's /containers/create does not auto-pull, so the launcher must
  // pull it itself before create; without that, this request would 404 at create time.
  const daemon = await startFakeDaemon(socketPath, { waitDelayMs: 20, imageMissing: true });
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
    const res = await postJson(port, '/triage', {
      files: [], prompt: 'x', json_schema: {}, mcp: {}, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL,
    }, 'testkey');
    assert.equal(res.status, 200, `expected the pull-then-create path to succeed, got: ${res.body}`);
    assert.equal(daemon.imagePullUrls.length, 1, 'expected exactly one /images/create pull');
    assert.match(daemon.imagePullUrls[0], /fromImage=test-agent&tag=latest/);
    assert.equal(daemon.createBodies.length, 1);
    assert.equal(daemon.createBodies[0].Image, 'test-agent:latest');
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

test('docker backend: is the ACTUAL default when SANDBOX_BACKEND is unset (finding #855-3)', async () => {
  // Every other test in this file pins SANDBOX_BACKEND: 'docker' explicitly. That proves the
  // docker code path works when selected, but not that a self-hoster who sets nothing gets it —
  // D7's whole point is zero-cloud-credential-by-default. This test omits SANDBOX_BACKEND
  // entirely so server.js's own fallthrough (`process.env.SANDBOX_BACKEND || 'docker'`) is what
  // actually picks the backend, same as a genuinely fresh self-host's .env.
  const scratch = tempDir('docker-backend-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });

  const daemon = await startFakeDaemon(socketPath, { waitDelayMs: 20 });
  const port = 18500 + Math.floor(Math.random() * 500);
  const child = await startLauncher({
    // SANDBOX_BACKEND intentionally absent.
    DOCKER_SOCKET_PATH: socketPath,
    AGENT_IMAGE: 'test-agent:latest',
    LAUNCHER_WORK_DIR: workDir,
    SANDBOX_WORK_VOLUME: 'test-work-volume',
    SANDBOX_API_KEY: 'testkey',
    PORT: String(port),
    SANDBOX_DOCKER_CONCURRENCY: '1',
    AWS_REGION: 'us-east-1',
    // No E2B_API_KEY, no AWS credentials beyond region: the docker default must not need them.
  });

  try {
    const res = await postJson(port, '/triage', {
      files: [], prompt: 'x', json_schema: {}, mcp: {}, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL,
    }, 'testkey');
    assert.equal(res.status, 200, `default backend must be docker with no cloud creds set, got: ${res.body}`);
    assert.equal(daemon.createBodies.length, 1, 'the unconfigured default must route through /containers/create, not e2b/local');
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

// #856's UNTRUSTED_POSTURE assertions lived here — /grade and /lint running with no credentials and
// NetworkMode:'none' — and went with their routes in Track A. The posture constant survives with no
// caller (see server.js) and test/sandbox-posture.test.js covers it at the unit level; there is no
// route left to drive it through this fake daemon.

test('docker backend: two agentic routes share the SAME semaphore under concurrency=1', async () => {
  const scratch = tempDir('docker-backend-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });

  const WAIT_MS = 200;
  const daemon = await startFakeDaemon(socketPath, { waitDelayMs: WAIT_MS });
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
    const [rca, triage] = await Promise.all([
      postJson(port, '/rca', { files: {}, prompt: 'x', json_schema: {}, mcp: { url: 'https://example.test/mcp' }, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL }, 'testkey'),
      postJson(port, '/triage', { files: {}, prompt: 'x', json_schema: {}, mcp: { url: 'https://example.test/mcp' }, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL }, 'testkey'),
    ]);
    assert.equal(rca.status, 200);
    assert.equal(triage.status, 200);
    assert.equal(daemon.createTimestamps.length, 2);
    // If either route held a second, independent limiter (the bug this test guards against), both
    // creates would land back-to-back instead of serializing.
    const gap = daemon.createTimestamps[1] - daemon.createTimestamps[0];
    assert.ok(gap >= WAIT_MS * 0.8, `expected /rca and /triage to serialize through one semaphore (gap=${gap}ms, want >= ~${WAIT_MS}ms)`);
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

// ---- SANDBOX_NETWORK_ISOLATION -------------------------------------------------------------
// #855 put every sibling on a dedicated bridge, "never the evals service network". That is now
// opt-in, because the cut-off was not reachability-neutral: the agent reads its evidence through
// the backend's MCP door, and off the service network its only route there is the public origin,
// which a localhost `docker compose up` does not have. These three pin the inversion — the default
// joins the launcher's own network, the flag restores the bridge, and the bridge is not created
// when nothing will run on it.

async function runOneAgentJob(env, daemonOpts) {
  const scratch = tempDir('docker-network-test-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir, { recursive: true });
  const daemon = await startFakeDaemon(socketPath, { waitDelayMs: 10, ...daemonOpts });
  const port = 19100 + Math.floor(Math.random() * 400);
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
    ...env,
  });
  try {
    const res = await postJson(port, '/triage', {
      files: [], prompt: 'x', json_schema: {}, mcp: {}, timeout_ms: 10000, credential: BEDROCK_CREDENTIAL,
    }, 'testkey');
    assert.equal(res.status, 200);
    return { create: daemon.createBodies[0], networkCreates: daemon.networkCreates };
  } finally {
    child.kill();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
}

test('sandbox network: the DEFAULT joins the network the launcher itself is on', async () => {
  const { create, networkCreates } = await runOneAgentJob({}, { selfNetworks: ['evals-platform_evals'] });
  assert.equal(create.HostConfig.NetworkMode, 'evals-platform_evals',
    'without isolation the sibling must land on the service network, so `backend` resolves for MCP');
  assert.equal(networkCreates.length, 0,
    'the dedicated bridge must not be created when nothing is going to run on it');
});

test('sandbox network: SANDBOX_NETWORK_ISOLATION=1 restores the dedicated bridge', async () => {
  const { create, networkCreates } = await runOneAgentJob(
    { SANDBOX_NETWORK_ISOLATION: '1' }, { selfNetworks: ['evals-platform_evals'] });
  assert.equal(create.HostConfig.NetworkMode, 'tessary-sandbox',
    'with isolation on the sibling must be cut off from the service network');
  assert.deepEqual(networkCreates.map((n) => n.Name), ['tessary-sandbox'],
    'the bridge it runs on has to be ensured at startup');
});

test('sandbox network: an unreadable self-inspection falls back to the isolated bridge, never to a guess', async () => {
  // Failing closed: one run erroring on an unreachable MCP door beats every run failing at
  // container-create against a network name nobody configured.
  const { create } = await runOneAgentJob({}, { selfNetworks: null });
  assert.equal(create.HostConfig.NetworkMode, 'tessary-sandbox');
  assert.notEqual(create.HostConfig.NetworkMode, 'none', 'network must stay on (egress required)');
});
