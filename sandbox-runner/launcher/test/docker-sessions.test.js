// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Launcher sessions on the docker backend: POST /sessions starts one long-lived sibling container
 * running session.js, and the launcher forwards messages, numbers the script's events, replays them
 * and closes the session. Same approach as docker-backend.test.js (the real server.js as a child
 * process, a fake Engine API on a temp unix socket), with a daemon that starts a fake session
 * (fixtures/fake-session) on 127.0.0.1 for each session container it is asked to start.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { spawnLauncher, request, readSse, freePort, tempDir, withoutElapsed, reply, numbered, sleep, until } = require('./fixtures/launcher-harness');
const { startFakeSession } = require('./fixtures/fake-session');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'test-akid', aws_secret_key: 'test-secret' };
const START = {
  lane: 'authoring',
  system_prompt: 'You help.',
  files: { 'notes.md': 'hello' },
  model: 'global.anthropic.claude-sonnet-4-6',
  model_cost: { input: 3, output: 15 },
  provider: 'BEDROCK',
  credential: CREDENTIAL,
  mcp_servers: [
    { name: 'tessary', url: 'http://backend:8080/mcp', token: 'tsy_a_tok1' },
    { name: 'notes', url: 'http://backend:8080/mcp/x', token: 'tsy_a_tok1' },
  ],
  idle_timeout_ms: 60000,
  max_tool_calls_per_message: 40,
};
const NOT_FOUND = { error: 'not found', kind: 'unknown_session' };

/*
 * A fake Engine API that knows session containers. A create whose Cmd[1] is session.js is a
 * session: its start snapshots and deletes session.json from the work dir (as the script does) and
 * starts a fake session on the SESSION_PORT and SESSION_SECRET of its Env; its wait resolves when
 * that fake exits; its stop and kill close the fake first, so the port is free for the next one.
 * Any other create is a one-shot run answered as in docker-backend.test.js.
 */
function startSessionDaemon(socketPath, { workDir, shape = {}, shapes = [], selfNetworks = ['tessary_tessary'], containers = [] }) {
  const calls = [];
  const createBodies = {};
  const sessionFiles = {};
  const fakes = {};
  const connects = [];
  const abortedBeforeStop = {};
  let nextId = 1;
  let sessionCount = 0;

  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', async () => {
      const url = req.url || '';
      calls.push(`${req.method} ${url}`);
      const bodyText = Buffer.concat(chunks).toString('utf8');
      const send = (status, obj) => {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(obj === undefined ? '' : JSON.stringify(obj));
      };
      const idOf = () => /^\/containers\/([^/?]+)/.exec(url)[1];
      const isSession = (id) => Boolean(createBodies[id] && createBodies[id].Cmd[1] === 'session.js');

      if (req.method === 'GET' && /^\/images\//.test(url)) return send(200, { Id: 'sha256:fake' });
      if (req.method === 'GET' && /^\/networks\//.test(url)) return send(404, {});
      if (req.method === 'POST' && url === '/networks/create') return send(201, { Id: 'net1' });
      const connect = /^\/networks\/([^/]+)\/connect$/.exec(url);
      if (req.method === 'POST' && connect) {
        connects.push({ name: decodeURIComponent(connect[1]), body: JSON.parse(bodyText) });
        return send(204);
      }
      if (req.method === 'GET' && url.startsWith('/containers/json')) return send(200, containers);
      if (req.method === 'POST' && url === '/containers/create') {
        const id = `c${nextId++}`;
        createBodies[id] = JSON.parse(bodyText);
        return send(201, { Id: id });
      }
      if (req.method === 'GET' && /^\/containers\/[^/]+\/json$/.test(url)) {
        const id = idOf();
        if (isSession(id)) {
          const mode = createBodies[id].HostConfig.NetworkMode;
          return send(200, { Id: id, NetworkSettings: { Networks: { decoy_net: { IPAddress: '192.0.2.1' }, [mode]: { IPAddress: '127.0.0.1' } } } });
        }
        const Networks = {};
        for (const n of selfNetworks) Networks[n] = { NetworkID: `id-${n}` };
        return send(200, { Id: id, NetworkSettings: { Networks } });
      }
      if (req.method === 'POST' && /^\/containers\/[^/]+\/start$/.test(url)) {
        const id = idOf();
        if (isSession(id)) {
          const env = Object.fromEntries(createBodies[id].Env.map((e) => [e.slice(0, e.indexOf('=')), e.slice(e.indexOf('=') + 1)]));
          const file = path.join(workDir, createBodies[id].HostConfig.Mounts[0].VolumeOptions.Subpath, 'session.json');
          sessionFiles[id] = fs.readFileSync(file, 'utf8');
          fs.rmSync(file);
          const own = shapes[sessionCount] || shape;
          sessionCount++;
          fakes[id] = await startFakeSession({ port: Number(env.SESSION_PORT), secret: env.SESSION_SECRET, shape: own });
        }
        return send(204);
      }
      if (req.method === 'GET' && /^\/containers\/[^/]+\/logs/.test(url)) {
        const payload = Buffer.from(JSON.stringify({ raw: 'fake-ok' }));
        const header = Buffer.alloc(8);
        header.writeUInt8(1, 0);
        header.writeUInt32BE(payload.length, 4);
        res.writeHead(200, { 'Content-Type': 'application/vnd.docker.raw-stream' });
        res.end(Buffer.concat([header, payload]));
        return undefined;
      }
      if (req.method === 'POST' && /^\/containers\/[^/]+\/wait$/.test(url)) {
        const id = idOf();
        if (!isSession(id)) return send(200, { StatusCode: 0 });
        await until(() => fakes[id], 'the session container to start');
        return send(200, { StatusCode: await fakes[id].exited });
      }
      if (req.method === 'POST' && /^\/containers\/[^/]+\/(stop|kill)/.test(url)) {
        const id = idOf();
        if (fakes[id] && !(id in abortedBeforeStop)) abortedBeforeStop[id] = fakes[id].requests.some((r) => r.path === '/abort');
        if (fakes[id]) await fakes[id].close();
        return send(204);
      }
      if (req.method === 'DELETE' && /^\/containers\//.test(url)) return send(204);
      return send(404, { message: `unhandled ${req.method} ${url}` });
    });
  });
  return new Promise((resolve) => {
    server.listen(socketPath, () => resolve({ server, calls, createBodies, sessionFiles, fakes, connects, abortedBeforeStop }));
  });
}

async function withSessionLauncher(opts, fn) {
  const scratch = tempDir('docker-sessions-');
  const socketPath = path.join(scratch, 'docker.sock');
  const workDir = path.join(scratch, 'work');
  fs.mkdirSync(workDir);
  const sessionPort = await freePort();
  const daemon = await startSessionDaemon(socketPath, { workDir, ...opts });
  const child = spawnLauncher({
    SANDBOX_BACKEND: 'docker',
    DOCKER_SOCKET_PATH: socketPath,
    AGENT_IMAGE: 'test-agent:latest',
    LAUNCHER_WORK_DIR: workDir,
    SANDBOX_WORK_VOLUME: 'test-work-volume',
    SANDBOX_API_KEY: 'testkey',
    SANDBOX_SESSION_PORT: String(sessionPort),
    HOSTNAME: 'launcher-self',
    ...(opts.env || {}),
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
    const auth = 'Bearer testkey';
    return await fn({
      port,
      daemon,
      workDir,
      sessionPort,
      stderr: () => log,
      start: (body = START) => request(port, 'POST', '/sessions', { auth, body }),
      send: (id, text) => request(port, 'POST', `/sessions/${id}/messages`, { auth, body: { text } }),
      abort: (id) => request(port, 'POST', `/sessions/${id}/abort`, { auth }),
      stop: (id) => request(port, 'DELETE', `/sessions/${id}`, { auth }),
      events: (id, readOpts) => readSse(port, id, readOpts),
    });
  } finally {
    child.kill();
    for (const fake of Object.values(daemon.fakes)) await fake.close();
    daemon.server.closeAllConnections();
    daemon.server.close();
    fs.rmSync(scratch, { recursive: true, force: true });
  }
}

const T = { timeout: 15000 };

test('a session takes two messages and streams their events with increasing ids', T, async () => {
  await withSessionLauncher({}, async ({ start, send, events }) => {
    const started = await start();
    assert.equal(started.status, 201, JSON.stringify(started.body));
    const id = started.body.session_id;
    assert.match(id, UUID);

    assert.deepEqual(await send(id, 'one'), { status: 202, body: undefined });
    await events(id, { after: 0, until: 4 });
    assert.deepEqual(await send(id, 'two'), { status: 202, body: undefined });

    const read = await events(id, { after: 0, until: 8 });
    assert.deepEqual(read.events, numbered([...reply(1, 'one'), ...reply(2, 'two')], 1));
  });
});

test('a reconnect with after replays only the rest, then follows live', T, async () => {
  await withSessionLauncher({}, async ({ start, send, events }) => {
    const id = (await start()).body.session_id;
    await send(id, 'one');
    await events(id, { after: 0, until: 4 });
    await send(id, 'two');
    await events(id, { after: 0, until: 8 });

    const rest = await events(id, { after: 4, until: 8 });
    assert.deepEqual(rest.events, numbered(reply(2, 'two'), 5));

    const live = events(id, { after: 8, until: 12 });
    await send(id, 'three');
    assert.deepEqual((await live).events, numbered(reply(3, 'three'), 9));
  });
});

test('a message during a reply is 409 busy, and the reply ends once', T, async () => {
  await withSessionLauncher({ shape: { hold: true } }, async ({ start, send, events, daemon }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'one')).status, 202);
    assert.deepEqual(await send(id, 'two'), { status: 409, body: { error: 'session busy', kind: 'busy' } });
    assert.deepEqual(daemon.fakes.c1.messages, ['one']);

    await events(id, { after: 0, until: 1 });
    daemon.fakes.c1.release();
    await events(id, { after: 0, until: 2 });
    assert.equal((await send(id, 'two')).status, 202);
    const read = await events(id, { after: 0, until: 6 });
    assert.deepEqual(
      read.events.map((e) => [e.id, e.type, e.data.reason]),
      [[1, 'text', undefined], [2, 'message_end', 'done'], [3, 'text', undefined], [4, 'tool_start', undefined], [5, 'tool_end', undefined], [6, 'message_end', 'done']],
      'the held reply ends once, and the next reply follows it',
    );
  });
});

test('DELETE stops the session: closed stopped, container gone, later calls 404', T, async () => {
  await withSessionLauncher({}, async ({ start, send, abort, stop, events, daemon }) => {
    const id = (await start()).body.session_id;
    const reader = events(id, { after: 0 });
    await sleep(50);

    const callsBefore = daemon.calls.length;
    assert.deepEqual(await stop(id), { status: 204, body: undefined });
    const teardown = daemon.calls.slice(callsBefore);
    assert.ok(teardown.includes('POST /containers/c1/stop?t=5'), JSON.stringify(teardown));
    assert.ok(teardown.includes('DELETE /containers/c1?force=true'), JSON.stringify(teardown));

    const read = await reader;
    assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'stopped' } }]);
    assert.equal(read.ended, true);
    assert.deepEqual(await send(id, 'late'), { status: 404, body: NOT_FOUND });
    assert.deepEqual(await abort(id), { status: 404, body: NOT_FOUND });
  });
});

test('abort ends the reply and keeps the session', T, async () => {
  await withSessionLauncher({ shape: { hold: true } }, async ({ start, send, abort, events, daemon }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'one')).status, 202);
    await events(id, { after: 0, until: 1 });
    assert.deepEqual(await abort(id), { status: 204, body: undefined });

    const read = await events(id, { after: 0, until: 2 });
    assert.equal(read.events[1].type, 'message_end');
    assert.equal(read.events[1].data.reason, 'aborted');
    assert.equal((await send(id, 'next')).status, 202);
    await events(id, { after: 2, until: 6 });
    assert.deepEqual(daemon.calls.filter((c) => /c1\/stop|DELETE \/containers\/c1/.test(c)), [], 'the sandbox keeps running');
    assert.deepEqual(await abort(id), { status: 204, body: undefined }, 'an abort with no reply running is still 204');
  });
});

test('idle closes the session with closed: idle, and removes its container and work dir', T, async () => {
  await withSessionLauncher({}, async ({ start, send, stop, events, daemon, workDir }) => {
    const id = (await start({ ...START, idle_timeout_ms: 200 })).body.session_id;

    const read = await events(id);
    assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'idle' } }]);
    assert.equal(read.ended, true);
    await until(() => fs.readdirSync(workDir).length === 0, 'the work dir to go');
    const teardown = daemon.calls.filter((c) => /c1\/stop|DELETE \/containers\/c1/.test(c));
    assert.deepEqual(teardown, ['POST /containers/c1/stop?t=5', 'DELETE /containers/c1?force=true']);
    assert.deepEqual(await send(id, 'late'), { status: 404, body: NOT_FOUND });

    const replay = await events(id, { after: 0 });
    assert.deepEqual(replay.events, [{ id: 1, type: 'closed', data: { reason: 'idle' } }]);
    assert.equal(replay.ended, true);
    assert.deepEqual(await stop(id), { status: 204, body: undefined });
  });
});

test('idle never closes a session while a reply runs', T, async () => {
  await withSessionLauncher({ shape: { hold: true } }, async ({ start, send, events, daemon }) => {
    const id = (await start({ ...START, idle_timeout_ms: 200 })).body.session_id;
    assert.equal((await send(id, 'long')).status, 202);
    const reader = events(id, { after: 0, until: 3 });

    await sleep(500);
    assert.ok(!daemon.calls.some((c) => /c1\/stop/.test(c)), 'no stop while the reply runs');
    assert.equal((await send(id, 'again')).status, 409, 'the session is still live and busy');

    daemon.fakes.c1.release();
    const read = await reader;
    assert.deepEqual(read.events.map((e) => [e.id, e.type]), [[1, 'text'], [2, 'message_end'], [3, 'closed']]);
    assert.deepEqual(read.events[2].data, { reason: 'idle' });
    assert.ok(read.times[2] - read.times[1] >= 200, `idle counts from the reply's end (${read.times[2] - read.times[1]}ms)`);
  });
});

test('max lifetime aborts the running reply, lets it end, then closes the session', T, async () => {
  await withSessionLauncher({ shape: { hold: true, startingPolls: 0 }, env: { SANDBOX_SESSION_MAX_MS: '1500' } }, async ({ start, send, events, daemon }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'long')).status, 202);

    const read = await events(id, { after: 0 });
    assert.deepEqual(read.events.map((e) => [e.id, e.type]), [[1, 'text'], [2, 'message_end'], [3, 'closed']]);
    assert.equal(read.events[1].data.reason, 'aborted');
    assert.deepEqual(read.events[2].data, { reason: 'idle' });
    await until(() => 'c1' in daemon.abortedBeforeStop, 'the container stop');
    assert.equal(daemon.abortedBeforeStop.c1, true, 'the reply is aborted before the sandbox stops');
    assert.equal(daemon.createBodies.c1.Labels['tessary.sandbox.timeout-ms'], '1500');
  });
});

test('a session that never becomes ready is 502, leaves nothing behind, and frees its slot', T, async () => {
  await withSessionLauncher({ shapes: [{ startFails: true }], env: { SANDBOX_SESSION_CAP: '1' } }, async ({ start, daemon, workDir, stderr }) => {
    const failed = await start();
    assert.equal(failed.status, 502);
    assert.deepEqual(withoutElapsed(failed.body), {
      error: 'sandbox orchestration failed',
      kind: 'orchestration',
      timeout: false,
      error_class: 'Error',
      detail: 'session.js did not become ready (failed)',
      script: 'session.js',
      timeout_ms: 90000,
    });
    assert.doesNotMatch(JSON.stringify(failed.body), /boom|tsy_/);
    assert.ok(daemon.calls.includes('POST /containers/c1/stop?t=5'));
    assert.ok(daemon.calls.includes('DELETE /containers/c1?force=true'));
    assert.deepEqual(fs.readdirSync(workDir), []);
    assert.match(stderr(), /boom tsy_\*\*\*/, 'the script\'s start error is on the console, scrubbed');
    assert.doesNotMatch(stderr(), /tsy_a_leaked/);

    assert.equal((await start()).status, 201, 'the failed start frees its cap slot');
  });
});

test('a sandbox that reports its own death closes the session as failed, once', T, async () => {
  await withSessionLauncher({}, async ({ start, send, events, daemon }) => {
    const id = (await start()).body.session_id;
    const reader = events(id, { after: 0 });
    await until(() => daemon.fakes.c1.eventFroms.length > 0, 'the launcher to read the script events');
    await daemon.fakes.c1.die({ withClosed: true });

    const read = await reader;
    assert.deepEqual(read.events, [
      { id: 1, type: 'error', data: { kind: 'opencode_exit', detail: 'exit code 1' } },
      { id: 2, type: 'closed', data: { reason: 'failed' } },
    ]);
    assert.equal(read.ended, true);
    await until(() => daemon.calls.includes('DELETE /containers/c1?force=true'), 'the container removal');
    assert.deepEqual(await send(id, 'late'), { status: 404, body: NOT_FOUND });
  });
});

test('a sandbox that exits without a word still closes the session as failed', T, async () => {
  await withSessionLauncher({}, async ({ start, events, daemon }) => {
    const id = (await start()).body.session_id;
    const reader = events(id, { after: 0 });
    await until(() => daemon.fakes.c1.eventFroms.length > 0, 'the launcher to read the script events');
    await daemon.fakes.c1.die({ withClosed: false });

    const read = await reader;
    assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'failed' } }]);
    await until(() => daemon.calls.includes('DELETE /containers/c1?force=true'), 'the container removal');
  });
});

test('a cut event stream from the sandbox is resumed with from and loses nothing', T, async () => {
  await withSessionLauncher({ shape: { cutEventsAfter: 2 } }, async ({ start, send, events, daemon }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'one')).status, 202);

    const read = await events(id, { after: 0, until: 4 });
    assert.deepEqual(read.events, numbered(reply(1, 'one'), 1));
    assert.deepEqual(daemon.fakes.c1.eventFroms, [0, 2]);
  });
});

test('with isolation on, the launcher joins the sandbox network at boot and runs sessions on it', T, async () => {
  await withSessionLauncher({ env: { SANDBOX_NETWORK_ISOLATION: '1' }, selfNetworks: ['tessary_tessary'] }, async ({ start, daemon }) => {
    await until(() => daemon.calls.some((c) => c.startsWith('GET /containers/json')), 'the boot chain to reach the reaper');
    assert.deepEqual(daemon.connects, [{ name: 'tessary-sandbox', body: { Container: 'launcher-self' } }]);
    assert.equal((await start()).status, 201);
    assert.equal(daemon.createBodies.c1.HostConfig.NetworkMode, 'tessary-sandbox');
  });
});

test('a launcher already on the sandbox network does not join it again', T, async () => {
  await withSessionLauncher({ env: { SANDBOX_NETWORK_ISOLATION: '1' }, selfNetworks: ['tessary_tessary', 'tessary-sandbox'] }, async ({ start, daemon }) => {
    await until(() => daemon.calls.some((c) => c.startsWith('GET /containers/json')), 'the boot chain to reach the reaper');
    assert.deepEqual(daemon.connects, []);
    assert.equal((await start()).status, 201);
  });
});

test('an unknown session id is 404 on every route', T, async () => {
  await withSessionLauncher({}, async ({ send, abort, stop, port }) => {
    for (const id of ['00000000-0000-4000-8000-000000000000', 'nope']) {
      assert.deepEqual(await send(id, 'x'), { status: 404, body: NOT_FOUND }, `messages ${id}`);
      assert.deepEqual(await abort(id), { status: 404, body: NOT_FOUND }, `abort ${id}`);
      assert.deepEqual(await request(port, 'GET', `/sessions/${id}/events`, { auth: 'Bearer testkey' }), { status: 404, body: NOT_FOUND }, `events ${id}`);
      assert.deepEqual(await stop(id), { status: 404, body: NOT_FOUND }, `delete ${id}`);
    }
  });
});

test('the credential never reaches session.json; the secret and the keys ride in the env', T, async () => {
  await withSessionLauncher({}, async ({ start, daemon, sessionPort }) => {
    assert.equal((await start()).status, 201);

    const text = daemon.sessionFiles.c1;
    assert.deepEqual(JSON.parse(text), {
      system_prompt: 'You help.',
      files: { 'notes.md': 'hello' },
      model: 'amazon-bedrock/global.anthropic.claude-sonnet-4-6',
      mcp_servers: START.mcp_servers,
      max_tool_calls_per_message: 40,
    });
    for (const leak of ['test-secret', 'test-akid', 'idle_timeout_ms', 'model_cost', 'credential', 'lane']) {
      assert.ok(!text.includes(leak), `session.json holds ${leak}`);
    }

    const create = daemon.createBodies.c1;
    assert.deepEqual(create.Cmd, ['node', 'session.js', '/work/session.json']);
    assert.equal(create.WorkingDir, '/home/user');
    assert.ok(create.Env.includes('WORK_DIR=/work'));
    assert.ok(create.Env.includes(`SESSION_PORT=${sessionPort}`));
    assert.ok(create.Env.includes('AWS_ACCESS_KEY_ID=test-akid'));
    const secrets = create.Env.filter((e) => e.startsWith('SESSION_SECRET='));
    assert.equal(secrets.length, 1);
    assert.match(secrets[0], /^SESSION_SECRET=[0-9a-f]{64}$/);
    const config = JSON.parse(create.Env.find((e) => e.startsWith('OPENCODE_CONFIG_CONTENT=')).slice('OPENCODE_CONFIG_CONTENT='.length));
    assert.deepEqual(config.provider['amazon-bedrock'].models['global.anthropic.claude-sonnet-4-6'].cost, { input: 3, output: 15 });

    const bearer = `Bearer ${secrets[0].slice('SESSION_SECRET='.length)}`;
    assert.ok(daemon.fakes.c1.requests.length > 0);
    for (const r of daemon.fakes.c1.requests) assert.equal(r.auth, bearer, `${r.method} ${r.path}`);
  });
});

test('a session container has the one-shot hardening and its own labels', T, async () => {
  await withSessionLauncher({}, async ({ start, daemon }) => {
    const id = (await start()).body.session_id;
    const { HostConfig, Labels } = daemon.createBodies.c1;
    assert.deepEqual(HostConfig.CapDrop, ['ALL']);
    assert.deepEqual(HostConfig.SecurityOpt, ['no-new-privileges']);
    assert.equal(HostConfig.Memory, 2147483648);
    assert.equal(HostConfig.NanoCpus, 2000000000);
    assert.equal(HostConfig.PidsLimit, 256);
    assert.equal(HostConfig.AutoRemove, false);
    assert.equal(HostConfig.NetworkMode, 'tessary_tessary');
    assert.equal(HostConfig.Mounts.length, 1);
    const [mount] = HostConfig.Mounts;
    assert.equal(mount.Type, 'volume');
    assert.equal(mount.Source, 'test-work-volume');
    assert.equal(mount.Target, '/work');
    assert.match(mount.VolumeOptions.Subpath, /^session-/);
    assert.deepEqual(Labels, {
      'tessary.sandbox': '1',
      'tessary.sandbox.script': 'session.js',
      'tessary.sandbox.timeout-ms': '7200000',
      'tessary.sandbox.session': id,
    });
  });
});

test('the launcher reaches the session on its network by name, not on the first network listed', T, async () => {
  await withSessionLauncher({}, async ({ start }) => {
    const begun = Date.now();
    const started = await start();
    assert.equal(started.status, 201, JSON.stringify(started.body));
    assert.ok(Date.now() - begun < 5000, 'the decoy address would hang until the start deadline');
  });
});

test('the cap refuses the next start with 429, spends no sandbox on it, and frees on DELETE', T, async () => {
  await withSessionLauncher({ env: { SANDBOX_SESSION_CAP: '1' } }, async ({ start, stop, daemon }) => {
    const a = await start();
    assert.equal(a.status, 201);
    assert.deepEqual(await start(), { status: 429, body: { error: 'sandbox session capacity reached', kind: 'capacity' } });
    assert.equal(daemon.calls.filter((c) => c === 'POST /containers/create').length, 1);

    assert.equal((await stop(a.body.session_id)).status, 204);
    assert.equal((await start()).status, 201);
    const secretOf = (id) => daemon.createBodies[id].Env.find((e) => e.startsWith('SESSION_SECRET='));
    assert.notEqual(secretOf('c1'), secretOf('c2'), 'each session has its own secret');
  });
});

test('the buffer keeps the last SANDBOX_SESSION_BUFFER events; an older after is 410', T, async () => {
  await withSessionLauncher({ env: { SANDBOX_SESSION_BUFFER: '3' } }, async ({ start, send, events, port }) => {
    const id = (await start()).body.session_id;
    const live = events(id, { after: 0, until: 4 });
    await send(id, 'one');
    assert.deepEqual((await live).events, numbered(reply(1, 'one'), 1), 'a live reader gets every event, whatever the buffer holds');

    assert.deepEqual(await events(id, { after: 0 }), { status: 410, body: { error: 'events no longer held', kind: 'gone' } });
    assert.deepEqual((await events(id, { after: 1, until: 4 })).events, numbered(reply(1, 'one'), 1).slice(1));
    for (const [after, detail] of [['5', 'session.js: after is past the last event'], ['x', 'session.js: after must be a non-negative integer']]) {
      const res = await request(port, 'GET', `/sessions/${id}/events?after=${after}`, { auth: 'Bearer testkey' });
      assert.equal(res.status, 502, after);
      assert.equal(res.body.kind, 'bad_request', after);
      assert.equal(res.body.detail, detail);
    }
  });
});

test('a live session does not hold the docker run slot', T, async () => {
  await withSessionLauncher({ env: { SANDBOX_DOCKER_CONCURRENCY: '1' } }, async ({ start, port }) => {
    assert.equal((await start()).status, 201);
    const triage = await request(port, 'POST', '/triage', {
      auth: 'Bearer testkey',
      body: { files: [], prompt: 'x', json_schema: {}, mcp: {}, timeout_ms: 10000, credential: CREDENTIAL },
    });
    assert.deepEqual(triage, { status: 200, body: { raw: 'fake-ok' } });
  });
});

test('a message with no text, or text over 1 MiB, is the caller\'s bad_request', T, async () => {
  await withSessionLauncher({}, async ({ start, send, daemon }) => {
    const id = (await start()).body.session_id;
    for (const text of ['', 'a'.repeat((1 << 20) + 1)]) {
      const res = await send(id, text);
      assert.equal(res.status, 502);
      assert.equal(res.body.kind, 'bad_request');
      assert.equal(res.body.detail, 'session.js: text must be a non-empty string of at most 1 MiB');
    }
    assert.deepEqual(daemon.fakes.c1.messages, []);
  });
});


test('a reply text split inside a multi-byte character reaches the reader whole (non-ASCII text turned into U+FFFD for good)', T, async () => {
  await withSessionLauncher({ shape: { splitMultibyte: true } }, async ({ start, send, events }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'café — ✓ 日本 🙂')).status, 202);

    const read = await events(id, { after: 0, until: 4 });
    assert.deepEqual(read.events, numbered(reply(1, 'café — ✓ 日本 🙂'), 1));
  });
});

test('a message accepted while the session closes ends its reply before closed (a reply\'s spend lost to a close that ran during the message)', T, async () => {
  await withSessionLauncher({ shape: { hold: true, acceptAfterMs: 300 } }, async ({ start, send, stop, events }) => {
    const id = (await start()).body.session_id;
    const sent = send(id, 'one');
    await sleep(100);
    const stopped = stop(id);

    assert.deepEqual(await sent, { status: 202, body: undefined });
    assert.deepEqual(await stopped, { status: 204, body: undefined });
    const read = await events(id, { after: 0 });
    assert.deepEqual(read.events.map((e) => [e.id, e.type, e.data.reason]), [
      [1, 'text', undefined], [2, 'message_end', 'aborted'], [3, 'closed', 'stopped'],
    ]);
  });
});

test('a message the script cannot take any more closes the session as failed and is 404 (a dead script kept live, or read as an outage)', T, async () => {
  for (const messageAnswer of ['drop', 503]) {
    await withSessionLauncher({ shape: { messageAnswer } }, async ({ start, send, events, daemon }) => {
      const id = (await start()).body.session_id;

      assert.deepEqual(await send(id, 'one'), { status: 404, body: NOT_FOUND }, String(messageAnswer));
      const read = await events(id, { after: 0 });
      assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'failed' } }], String(messageAnswer));
      await until(() => daemon.calls.includes('DELETE /containers/c1?force=true'), 'the container removal');
    });
  }
});

test('a message the script refuses for another reason is a 502 orchestration and the session stays (a refusal read as accepted or as closed)', T, async () => {
  await withSessionLauncher({ shape: { messageAnswer: 500 } }, async ({ start, send, stop }) => {
    const id = (await start()).body.session_id;

    const res = await send(id, 'one');
    assert.equal(res.status, 502);
    assert.equal(res.body.kind, 'orchestration');
    assert.equal(res.body.detail, 'session.js refused the message (HTTP 500)');
    assert.deepEqual(await stop(id), { status: 204, body: undefined }, 'the session is still there to stop');
  });
});

test('an abort the script cannot take closes the session as failed and is 404, and any other refusal is 502 (an abort error that leaves a dead session live)', T, async () => {
  await withSessionLauncher({ shape: { abortAnswer: 'drop' } }, async ({ start, abort, events }) => {
    const id = (await start()).body.session_id;
    assert.deepEqual(await abort(id), { status: 404, body: NOT_FOUND });
    assert.deepEqual((await events(id, { after: 0 })).events, [{ id: 1, type: 'closed', data: { reason: 'failed' } }]);
  });
  await withSessionLauncher({ shape: { abortAnswer: 500 } }, async ({ start, abort }) => {
    const id = (await start()).body.session_id;
    const res = await abort(id);
    assert.equal(res.status, 502);
    assert.equal(res.body.kind, 'orchestration');
    assert.equal(res.body.detail, 'session.js refused the abort (HTTP 500)');
  });
});

test('a reply whose message_end beats the 202 does not leave the session busy (a session stuck at 409 with no idle clock)', T, async () => {
  await withSessionLauncher({ shape: { replyBefore202: true } }, async ({ start, send, events, stderr }) => {
    const id = (await start({ ...START, idle_timeout_ms: 400 })).body.session_id;
    const reader = events(id, { after: 0 });
    await sleep(50);

    assert.equal((await send(id, 'one')).status, 202);
    const read = await reader;
    assert.deepEqual(read.events, [...numbered(reply(1, 'one'), 1), { id: 5, type: 'closed', data: { reason: 'idle' } }], stderr());
  });
});
