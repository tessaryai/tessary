// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Launcher sessions on the e2b backend: POST /sessions creates one E2B sandbox, writes session.json
 * through the files API, runs session.js in the background and dials its port through the sandbox
 * host. The real server.js runs as a child process with fixtures/fake-e2b in relay mode, which posts
 * every SDK call to a control server in this process; the control server starts a fake session
 * (fixtures/fake-session) on 127.0.0.1 for each session command, so the same session tests as on
 * Docker run here, plus the create, the file, the command and the kill on every way out.
 */
process.env.SANDBOX_BACKEND = 'e2b'; // keep require('../server.js') side-effect free (no docker fail-fast, no listen)
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { spawnLauncher, request, readSse, freePort, withoutElapsed, reply, numbered, sleep, until } = require('./fixtures/launcher-harness');
const { startFakeSession } = require('./fixtures/fake-session');
const { e2bSessionAddress } = require('../server.js');

const FAKE_E2B = path.join(__dirname, 'fixtures', 'fake-e2b');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'test-akid', aws_secret_key: 'test-secret' };
const START = {
  lane: 'agent_run',
  system_prompt: 'You help.',
  files: { 'notes.md': 'hello' },
  model: 'global.anthropic.claude-sonnet-4-6',
  model_cost: { input: 3, output: 15 },
  provider: 'BEDROCK',
  credential: CREDENTIAL,
  mcp_servers: [
    { name: 'tessary', url: 'https://tessary.example.com/mcp', token: 'tsy_a_tok1' },
    { name: 'notes', url: 'https://tessary.example.com/mcp/x', token: 'tsy_a_tok1' },
  ],
  idle_timeout_ms: 60000,
  max_tool_calls_per_message: 40,
};
const NOT_FOUND = { error: 'not found', kind: 'unknown_session' };
const SBX1 = 'sbx-fake-1';

/*
 * The E2B side of the relay. Every call is kept in `calls` as { op, ...body }. A run reads and
 * deletes session.json from that sandbox's files (as the script does) and starts a fake session on
 * the SESSION_PORT and SESSION_SECRET of its envs, unless the shape says the script exits at once;
 * a wait is held until that process ends; a kill closes the fake first, so the port is free for the
 * next session.
 */
function startE2bControl({ shape = {}, shapes = [], createFailures = 0, writeFails = false } = {}) {
  const calls = [];
  const files = {};
  const sessionFiles = {};
  const envs = {};
  const fakes = {};
  const exits = {};
  const ended = {};
  const waitDrops = {};
  const abortedBeforeStop = {};
  let created = 0;
  let sessionCount = 0;
  let failuresLeft = createFailures;

  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', async () => {
      const op = req.url.slice(1);
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      calls.push({ op, ...body });
      const send = (status, obj) => {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(obj));
      };
      const id = body.sandboxId;
      if (op === 'create') {
        if (failuresLeft > 0) {
          failuresLeft--;
          return send(500, { name: 'NotFoundError', message: 'template not found' });
        }
        const sandboxId = `sbx-fake-${++created}`;
        files[sandboxId] = {};
        return send(200, { sandboxId });
      }
      if (op === 'write') {
        if (writeFails) return send(500, { name: 'SandboxError', message: 'write refused' });
        files[id][body.path] = body.content;
        return send(200, {});
      }
      if (op === 'run') {
        const file = body.cmd.split(' ').pop();
        sessionFiles[id] = files[id][file];
        delete files[id][file];
        envs[id] = body.envs;
        const own = shapes[sessionCount] || shape;
        sessionCount++;
        if (own.exitAtStart) {
          exits[id] = Promise.resolve({ exitCode: own.exitAtStart.code, stderr: own.exitAtStart.stderr });
        } else {
          fakes[id] = await startFakeSession({ port: Number(body.envs.SESSION_PORT), secret: body.envs.SESSION_SECRET, shape: own });
          exits[id] = fakes[id].exited.then((code) => ({ exitCode: code, stderr: '' }));
        }
        exits[id].then(() => { ended[id] = true; });
        waitDrops[id] = own.waitDrops || 0;
        return send(200, { pid: 1 });
      }
      if (op === 'wait') {
        if (waitDrops[id] > 0) {
          waitDrops[id]--;
          return send(500, { name: 'SandboxError', message: 'Process exited without a result' });
        }
        return send(200, await exits[id]);
      }
      if (op === 'connect') {
        if (ended[id] || body.pid !== 1) return send(500, { name: 'NotFoundError', message: `process with pid ${body.pid} not found` });
        return send(200, {});
      }
      if (op === 'kill') {
        if (fakes[id] && !(id in abortedBeforeStop)) abortedBeforeStop[id] = fakes[id].requests.some((r) => r.path === '/abort');
        if (fakes[id]) await fakes[id].close();
        return send(200, {});
      }
      return send(404, { name: 'Error', message: `unhandled ${op}` });
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({
      server,
      url: `http://127.0.0.1:${server.address().port}`,
      calls,
      sessionFiles,
      envs,
      fakes,
      abortedBeforeStop,
      ops: () => calls.map((c) => c.op),
      kills: () => calls.filter((c) => c.op === 'kill').map((c) => c.sandboxId),
    }));
  });
}

async function withE2bLauncher(opts, fn) {
  const control = await startE2bControl(opts);
  const sessionPort = await freePort();
  const child = spawnLauncher({
    SANDBOX_BACKEND: 'e2b',
    E2B_API_KEY: 'fake-e2b-key',
    E2B_DEBUG: 'true',
    SANDBOX_API_KEY: 'testkey',
    SANDBOX_SESSION_PORT: String(sessionPort),
    FAKE_E2B_CONTROL_URL: control.url,
    NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --require ${FAKE_E2B}`,
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
      control,
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
    for (const fake of Object.values(control.fakes)) await fake.close();
    control.server.closeAllConnections();
    control.server.close();
  }
}

const T = { timeout: 15000 };

test('a session on E2B takes two messages and streams their events with increasing ids (an E2B session that drops or misnumbers a reply)', T, async () => {
  await withE2bLauncher({}, async ({ start, send, events }) => {
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

test('on E2B a reconnect with after replays only the rest, then follows live (a resume that replays twice or skips)', T, async () => {
  await withE2bLauncher({}, async ({ start, send, events }) => {
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

test('on E2B a message during a reply is 409 busy, and the reply ends once (two prompts racing in one sandbox)', T, async () => {
  await withE2bLauncher({ shape: { hold: true } }, async ({ start, send, events, control }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'one')).status, 202);
    assert.deepEqual(await send(id, 'two'), { status: 409, body: { error: 'session busy', kind: 'busy' } });
    assert.deepEqual(control.fakes[SBX1].messages, ['one']);

    await events(id, { after: 0, until: 1 });
    control.fakes[SBX1].release();
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

test('DELETE kills the E2B sandbox: closed stopped, later calls 404 (a stopped session that keeps its E2B sandbox and bill)', T, async () => {
  await withE2bLauncher({}, async ({ start, send, abort, stop, events, control }) => {
    const id = (await start()).body.session_id;
    const reader = events(id, { after: 0 });
    await sleep(50);

    assert.deepEqual(await stop(id), { status: 204, body: undefined });
    assert.deepEqual(control.kills(), [SBX1]);

    const read = await reader;
    assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'stopped' } }]);
    assert.equal(read.ended, true);
    assert.deepEqual(await send(id, 'late'), { status: 404, body: NOT_FOUND });
    assert.deepEqual(await abort(id), { status: 404, body: NOT_FOUND });
  });
});

test('on E2B abort ends the reply and keeps the session (an abort that kills the sandbox)', T, async () => {
  await withE2bLauncher({ shape: { hold: true } }, async ({ start, send, abort, events, control }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'one')).status, 202);
    await events(id, { after: 0, until: 1 });
    assert.deepEqual(await abort(id), { status: 204, body: undefined });

    const read = await events(id, { after: 0, until: 2 });
    assert.equal(read.events[1].type, 'message_end');
    assert.equal(read.events[1].data.reason, 'aborted');
    assert.equal((await send(id, 'next')).status, 202);
    await events(id, { after: 2, until: 6 });
    assert.deepEqual(control.kills(), [], 'the sandbox keeps running');
    assert.deepEqual(await abort(id), { status: 204, body: undefined }, 'an abort with no reply running is still 204');
  });
});

test('on E2B idle closes the session with closed: idle and kills its sandbox (an idle E2B sandbox that runs to its E2B timeout)', T, async () => {
  await withE2bLauncher({}, async ({ start, send, stop, events, control }) => {
    const id = (await start({ ...START, idle_timeout_ms: 200 })).body.session_id;

    const read = await events(id);
    assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'idle' } }]);
    assert.equal(read.ended, true);
    await until(() => control.kills().length > 0, 'the sandbox kill');
    assert.deepEqual(control.kills(), [SBX1]);
    assert.deepEqual(await send(id, 'late'), { status: 404, body: NOT_FOUND });

    const replay = await events(id, { after: 0 });
    assert.deepEqual(replay.events, [{ id: 1, type: 'closed', data: { reason: 'idle' } }]);
    assert.equal(replay.ended, true);
    assert.deepEqual(await stop(id), { status: 204, body: undefined });
    assert.deepEqual(control.kills(), [SBX1], 'a DELETE on a closed session kills nothing more');
  });
});

test('on E2B idle never closes a session while a reply runs (a long reply cut by the idle clock)', T, async () => {
  await withE2bLauncher({ shape: { hold: true } }, async ({ start, send, events, control }) => {
    const id = (await start({ ...START, idle_timeout_ms: 200 })).body.session_id;
    assert.equal((await send(id, 'long')).status, 202);
    const reader = events(id, { after: 0, until: 3 });

    await sleep(500);
    assert.deepEqual(control.kills(), [], 'no kill while the reply runs');
    assert.equal((await send(id, 'again')).status, 409, 'the session is still live and busy');

    control.fakes[SBX1].release();
    const read = await reader;
    assert.deepEqual(read.events.map((e) => [e.id, e.type]), [[1, 'text'], [2, 'message_end'], [3, 'closed']]);
    assert.deepEqual(read.events[2].data, { reason: 'idle' });
    assert.ok(read.times[2] - read.times[1] >= 200, `idle counts from the reply's end (${read.times[2] - read.times[1]}ms)`);
  });
});

test('on E2B max lifetime aborts the running reply, lets it end, kills the sandbox, and E2B\'s own timeout is later (spend lost when E2B kills first)', T, async () => {
  await withE2bLauncher({ shape: { hold: true, startingPolls: 0 }, env: { SANDBOX_SESSION_MAX_MS: '1500' } }, async ({ start, send, events, control }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'long')).status, 202);

    const read = await events(id, { after: 0 });
    assert.deepEqual(read.events.map((e) => [e.id, e.type]), [[1, 'text'], [2, 'message_end'], [3, 'closed']]);
    assert.equal(read.events[1].data.reason, 'aborted');
    assert.deepEqual(read.events[2].data, { reason: 'idle' });
    await until(() => SBX1 in control.abortedBeforeStop, 'the sandbox kill');
    assert.equal(control.abortedBeforeStop[SBX1], true, 'the reply is aborted before the sandbox is killed');
    assert.equal(control.calls.find((c) => c.op === 'create').timeoutMs, 61500, 'E2B kills the sandbox 60 s after the launcher closes it');
  });
});

test('on E2B a session that never becomes ready is 502, kills its sandbox, logs the start error scrubbed, and frees its slot (a failed start that leaks a sandbox or a token)', T, async () => {
  await withE2bLauncher({ shapes: [{ startFails: true }], env: { SANDBOX_SESSION_CAP: '1' } }, async ({ start, control, stderr }) => {
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
      sandbox_id: SBX1,
    });
    assert.doesNotMatch(JSON.stringify(failed.body), /boom|tsy_/);
    assert.deepEqual(control.kills(), [SBX1]);
    await until(() => /boom tsy_\*\*\*/.test(stderr()), 'the script\'s start error on the console, scrubbed');
    assert.doesNotMatch(stderr(), /tsy_a_leaked/);

    assert.equal((await start()).status, 201, 'the failed start frees its cap slot');
  });
});

test('an E2B sandbox that reports its own death closes the session as failed, once, and is killed (a dead session left open)', T, async () => {
  await withE2bLauncher({}, async ({ start, send, events, control }) => {
    const id = (await start()).body.session_id;
    const reader = events(id, { after: 0 });
    await until(() => control.fakes[SBX1].eventFroms.length > 0, 'the launcher to read the script events');
    await control.fakes[SBX1].die({ withClosed: true });

    const read = await reader;
    assert.deepEqual(read.events, [
      { id: 1, type: 'error', data: { kind: 'opencode_exit', detail: 'exit code 1' } },
      { id: 2, type: 'closed', data: { reason: 'failed' } },
    ]);
    assert.equal(read.ended, true);
    await until(() => control.kills().length > 0, 'the sandbox kill');
    assert.deepEqual(control.kills(), [SBX1]);
    assert.deepEqual(await send(id, 'late'), { status: 404, body: NOT_FOUND });
  });
});

test('an E2B sandbox whose script exits without a word still closes the session as failed (an exit seen only through wait() missed)', T, async () => {
  await withE2bLauncher({}, async ({ start, events, control }) => {
    const id = (await start()).body.session_id;
    const reader = events(id, { after: 0 });
    await until(() => control.fakes[SBX1].eventFroms.length > 0, 'the launcher to read the script events');
    await control.fakes[SBX1].die({ withClosed: false });

    const read = await reader;
    assert.deepEqual(read.events, [{ id: 1, type: 'closed', data: { reason: 'failed' } }]);
    await until(() => control.kills().length > 0, 'the sandbox kill');
    assert.deepEqual(control.kills(), [SBX1]);
  });
});

test('an E2B session whose command stream drops while the script runs is reattached and stays live (a healthy session closed as failed by a transport error)', T, async () => {
  await withE2bLauncher({ shape: { waitDrops: 2 } }, async ({ start, send, events, control }) => {
    const id = (await start()).body.session_id;
    await until(() => control.calls.filter((c) => c.op === 'connect').length === 2, 'the launcher to reattach twice');

    assert.equal((await send(id, 'one')).status, 202);
    assert.deepEqual((await events(id, { after: 0, until: 4 })).events, numbered(reply(1, 'one'), 1));
    assert.deepEqual(control.kills(), []);
    assert.deepEqual(control.calls.filter((c) => c.op === 'connect').map((c) => [c.pid, c.timeoutMs]), [[1, 0], [1, 0]]);
  });
});

test('an E2B session script that exits after its command stream dropped still closes the session as failed (an exit missed after a reattach)', T, async () => {
  await withE2bLauncher({ shape: { waitDrops: 1 } }, async ({ start, events, control }) => {
    const id = (await start()).body.session_id;
    await until(() => control.calls.some((c) => c.op === 'connect'), 'the launcher to reattach');
    await control.fakes[SBX1].die({ withClosed: false });

    assert.deepEqual((await events(id, { after: 0 })).events, [{ id: 1, type: 'closed', data: { reason: 'failed' } }]);
    await until(() => control.kills().length > 0, 'the sandbox kill');
  });
});

test('a cut event stream from an E2B sandbox is resumed with from and loses nothing (an E2B proxy that drops the NDJSON stream)', T, async () => {
  await withE2bLauncher({ shape: { cutEventsAfter: 2 } }, async ({ start, send, events, control }) => {
    const id = (await start()).body.session_id;
    assert.equal((await send(id, 'one')).status, 202);

    const read = await events(id, { after: 0, until: 4 });
    assert.deepEqual(read.events, numbered(reply(1, 'one'), 1));
    assert.deepEqual(control.fakes[SBX1].eventFroms, [0, 2]);
  });
});

test('on E2B an unknown session id is 404 on every route (an E2B launcher that answers for sessions it does not hold)', T, async () => {
  await withE2bLauncher({}, async ({ send, abort, stop, port }) => {
    for (const id of ['00000000-0000-4000-8000-000000000000', 'nope']) {
      assert.deepEqual(await send(id, 'x'), { status: 404, body: NOT_FOUND }, `messages ${id}`);
      assert.deepEqual(await abort(id), { status: 404, body: NOT_FOUND }, `abort ${id}`);
      assert.deepEqual(await request(port, 'GET', `/sessions/${id}/events`, { auth: 'Bearer testkey' }), { status: 404, body: NOT_FOUND }, `events ${id}`);
      assert.deepEqual(await stop(id), { status: 404, body: NOT_FOUND }, `delete ${id}`);
    }
  });
});

test('on E2B the credential never reaches session.json; the secret and the keys ride in the command env (a credential on the microVM\'s disk)', T, async () => {
  await withE2bLauncher({}, async ({ start, control, sessionPort }) => {
    assert.equal((await start()).status, 201);

    const write = control.calls.find((c) => c.op === 'write');
    assert.equal(write.path, '/home/user/session.json');
    assert.deepEqual(JSON.parse(write.content), {
      system_prompt: 'You help.',
      files: { 'notes.md': 'hello' },
      model: 'amazon-bedrock/global.anthropic.claude-sonnet-4-6',
      mcp_servers: START.mcp_servers,
      max_tool_calls_per_message: 40,
    });
    for (const leak of ['test-secret', 'test-akid', 'idle_timeout_ms', 'model_cost', 'credential', 'lane']) {
      assert.ok(!write.content.includes(leak), `session.json holds ${leak}`);
    }

    const run = control.calls.find((c) => c.op === 'run');
    assert.equal(run.cmd, '/usr/local/bin/node-sealed /home/user/session.js /home/user/session.json');
    assert.equal(run.background, true);
    assert.equal(run.timeoutMs, 0, 'a command timeout would kill the session script after 60 s');
    assert.equal(run.envs.AWS_ACCESS_KEY_ID, 'test-akid');
    assert.equal(run.envs.SESSION_PORT, String(sessionPort));
    assert.match(run.envs.SESSION_SECRET, /^[0-9a-f]{64}$/);
    assert.equal(run.envs.WORK_DIR, undefined, '/work does not exist on E2B');
    const config = JSON.parse(run.envs.OPENCODE_CONFIG_CONTENT);
    assert.deepEqual(config.provider['amazon-bedrock'].models['global.anthropic.claude-sonnet-4-6'].cost, { input: 3, output: 15 });

    const bearer = `Bearer ${run.envs.SESSION_SECRET}`;
    assert.ok(control.fakes[SBX1].requests.length > 0);
    for (const r of control.fakes[SBX1].requests) assert.equal(r.auth, bearer, `${r.method} ${r.path}`);
  });
});

test('on E2B the cap refuses the next start with 429, spends no sandbox on it, and frees on DELETE (a cap that still creates a sandbox)', T, async () => {
  await withE2bLauncher({ env: { SANDBOX_SESSION_CAP: '1' } }, async ({ start, stop, control }) => {
    const a = await start();
    assert.equal(a.status, 201);
    assert.deepEqual(await start(), { status: 429, body: { error: 'sandbox session capacity reached', kind: 'capacity' } });
    assert.equal(control.ops().filter((op) => op === 'create').length, 1);

    assert.equal((await stop(a.body.session_id)).status, 204);
    assert.equal((await start()).status, 201);
    assert.notEqual(control.envs[SBX1].SESSION_SECRET, control.envs['sbx-fake-2'].SESSION_SECRET, 'each session has its own secret');
  });
});

test('on E2B the buffer keeps the last SANDBOX_SESSION_BUFFER events; an older after is 410 (a stale reader that gets a silent gap)', T, async () => {
  await withE2bLauncher({ env: { SANDBOX_SESSION_BUFFER: '3' } }, async ({ start, send, events, port }) => {
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

test('on E2B a message with no text, or text over 1 MiB, is the caller\'s bad_request (a bad text sent into the sandbox)', T, async () => {
  await withE2bLauncher({}, async ({ start, send, control }) => {
    const id = (await start()).body.session_id;
    for (const text of ['', 'a'.repeat((1 << 20) + 1)]) {
      const res = await send(id, text);
      assert.equal(res.status, 502);
      assert.equal(res.body.kind, 'bad_request');
      assert.equal(res.body.detail, 'session.js: text must be a non-empty string of at most 1 MiB');
    }
    assert.deepEqual(control.fakes[SBX1].messages, []);
  });
});

test('the E2B sandbox is created from the template with the session lifetime plus the grace and the session label, and no network rule for an own key (a sandbox E2B kills before the launcher, or one that outlives its session)', T, async () => {
  await withE2bLauncher({}, async ({ start, control }) => {
    const started = await start();
    assert.equal(started.status, 201);
    assert.deepEqual(control.calls[0], {
      op: 'create',
      template: 'tessary/tessary-agent-sandbox:latest',
      apiKey: 'fake-e2b-key',
      timeoutMs: 7260000,
      metadata: { 'tessary.sandbox.session': started.body.session_id },
    });
    assert.deepEqual(control.ops().slice(0, 3), ['create', 'write', 'run']);
  });
});

test('an E2B session with an egress credential reaches only the model, every MCP server host and the extra hosts, with the key injected at egress (an egress session that can reach anything, or holds the key)', T, async () => {
  await withE2bLauncher({ env: { EGRESS_EXTRA_ALLOW: 'extra.example.com' } }, async ({ start, control }) => {
    const started = await start({
      ...START,
      credential: { provider: 'ANTHROPIC', egress_secret: 'tessary-ai-anthropic', platform_funded: true },
      model: 'claude-sonnet-5',
      mcp_servers: [
        { name: 'tessary', url: 'https://tessary.example.com/mcp', token: 'tsy_a_tok1' },
        { name: 'notes', url: 'https://tessary.example.com/mcp/x', token: 'tsy_a_tok1' },
        { name: 'other', url: 'https://notes.example.com/mcp', token: 'tsy_a_tok1' },
      ],
    });
    assert.equal(started.status, 201, JSON.stringify(started.body));

    assert.deepEqual(control.calls.find((c) => c.op === 'create').network, {
      allowOut: ['api.anthropic.com', 'tessary.example.com', 'notes.example.com', 'extra.example.com'],
      denyOut: ['0.0.0.0/0'],
      rules: { 'api.anthropic.com': [{ transform: { headers: { 'x-api-key': '${e2b.secrets.tessary-ai-anthropic}' } } }] },
    });
    assert.equal(control.calls.find((c) => c.op === 'run').envs.ANTHROPIC_API_KEY, 'injected-at-egress');
    const file = control.calls.find((c) => c.op === 'write').content;
    for (const leak of ['egress_secret', 'tessary-ai-anthropic']) assert.ok(!file.includes(leak), `session.json holds ${leak}`);
  });
});

test('an E2B session whose session.json cannot be written kills its sandbox (a half-started sandbox left running)', T, async () => {
  await withE2bLauncher({ writeFails: true }, async ({ start, control }) => {
    const failed = await start();
    assert.equal(failed.status, 502);
    assert.deepEqual(withoutElapsed(failed.body), {
      error: 'sandbox orchestration failed',
      kind: 'orchestration',
      timeout: false,
      error_class: 'SandboxError',
      detail: 'write refused',
      script: 'session.js',
      timeout_ms: 90000,
      sandbox_id: SBX1,
    });
    assert.deepEqual(control.ops(), ['create', 'write', 'kill']);
  });
});

test('an E2B sandbox that is never created is a 502 with no sandbox to kill, and frees its slot (a create failure that counts against the cap or kills a stranger)', T, async () => {
  await withE2bLauncher({ createFailures: 1, env: { SANDBOX_SESSION_CAP: '1' } }, async ({ start, control }) => {
    const failed = await start();
    assert.equal(failed.status, 502);
    assert.deepEqual(withoutElapsed(failed.body), {
      error: 'sandbox orchestration failed',
      kind: 'orchestration',
      timeout: false,
      error_class: 'NotFoundError',
      detail: 'template not found',
      script: 'session.js',
      timeout_ms: 90000,
    });
    assert.deepEqual(control.ops(), ['create']);

    assert.equal((await start()).status, 201, 'the failed create frees its cap slot');
  });
});

test('an E2B session script that exits before it is ready is a 502 at once, its sandbox killed and its stderr logged scrubbed (a start that waits 90 s on a dead script, or logs a token)', T, async () => {
  await withE2bLauncher({ shapes: [{ exitAtStart: { code: 2, stderr: 'SESSION_SECRET is required tsy_a_leaked' } }] }, async ({ start, control, stderr }) => {
    const begun = Date.now();
    const failed = await start();
    assert.ok(Date.now() - begun < 5000, `the start waited ${Date.now() - begun}ms on a dead script`);
    assert.equal(failed.status, 502);
    assert.equal(failed.body.kind, 'orchestration');
    assert.equal(failed.body.detail, 'session.js did not become ready (sandbox exited)');
    assert.equal(failed.body.sandbox_id, SBX1);
    assert.deepEqual(control.kills(), [SBX1]);
    await until(() => /session\.js ended code=2/.test(stderr()), 'the script\'s exit on the console');
    assert.match(stderr(), /session\.js ended code=2 stderr: SESSION_SECRET is required tsy_\*\*\*/);
    assert.doesNotMatch(stderr(), /tsy_a_leaked/);
  });
});

test('a session on real E2B is dialed over https at the sandbox host (a session secret sent in clear text to E2B\'s proxy)', () => {
  assert.equal(e2bSessionAddress('9400-sbx-1.e2b.app', false), 'https://9400-sbx-1.e2b.app');
  assert.equal(e2bSessionAddress('9400-sbx-1.e2b.app', true), 'http://9400-sbx-1.e2b.app');
});
