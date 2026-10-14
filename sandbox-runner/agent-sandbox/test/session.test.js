// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * session.js as a launcher runs it: a real child process over a real session.json, the real SDK,
 * the real relay, and test/fixtures/fake-opencode on PATH playing a scripted session in the event
 * shapes of the opencode 1.18.30 binary. Two stub MCP upstreams, alpha and beta, each with its own
 * token. Run with `node --test test/session.test.js`.
 *
 * Expected values are by hand: secret 'a' x 64, max_tool_calls_per_message 2 (so the agent's steps
 * are 3), and costs that are binary fractions so the sums are exact.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startSession } = require('./fixtures/run-session');
const { startStubUpstream, jsonRpcToolResult } = require('./fixtures/mcp-upstream');

const SECRET = 'a'.repeat(64);
const ALPHA_TOKEN = 'tsy_a_alphatoken';
const BETA_TOKEN = 'tsy_a_betatoken';
const SYSTEM_PROMPT = 'You are the session agent. Answer from the traces.';
const usage = (input, output, cacheRead, cacheWrite, cost) => ({ input, output, cache_read: cacheRead, cache_write: cacheWrite, cost });
const ev = (type, data) => ({ type, data });

const SCRIPT_A = {
  replies: [
    {
      steps: [
        { text: ['Look', 'ing.'], tools: [{ name: 'alpha_list_traces', input: { q: 'angry' }, duration_ms: 7 }], usage: usage(100, 20, 50, 10, 0.25) },
        { text: ['Found 3.'], usage: usage(130, 40, 60, 0, 0.5) },
      ],
    },
    {
      steps: [
        { tools: [{ name: 'beta_get_thing', input: { id: 'w1' }, duration_ms: 4 }], usage: usage(10, 2, 0, 0, 0.125) },
        { text: ['Done.'], usage: usage(12, 3, 0, 0, 0.125) },
      ],
    },
  ],
};

const REPLY_1_EVENTS = [
  ev('text', { delta: 'Look' }),
  ev('text', { delta: 'ing.' }),
  ev('tool_start', { call_id: 'call_1_1_1', server: 'alpha', tool: 'list_traces', input_summary: '{"q":"angry"}' }),
  ev('tool_end', { call_id: 'call_1_1_1', result_summary: '{"count":3,"file":"checks/mcp/001-list_traces.json"}', bytes: 52, duration_ms: 7, ok: true }),
  ev('text', { delta: 'Found 3.' }),
  ev('message_end', {
    usage: { input_tokens: 230, output_tokens: 60, cache_read_input_tokens: 110, cache_creation_input_tokens: 10, cost_usd: 0.75 },
    reason: 'done',
  }),
];
const REPLY_2_EVENTS = [
  ev('tool_start', { call_id: 'call_2_1_1', server: 'beta', tool: 'get_thing', input_summary: '{"id":"w1"}' }),
  ev('tool_end', { call_id: 'call_2_1_1', result_summary: '{"name":"widget","file":"checks/mcp/002-get_thing.json"}', bytes: 56, duration_ms: 4, ok: true }),
  ev('text', { delta: 'Done.' }),
  ev('message_end', {
    usage: { input_tokens: 22, output_tokens: 5, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0.25 },
    reason: 'done',
  }),
];

/** alpha and beta stub upstreams, closed after the test. */
async function upstreams(t) {
  const answer = (payload) => (reqBody, req, res) =>
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(jsonRpcToolResult(reqBody.id, payload)));
  const alpha = await startStubUpstream(answer({ count: 3 }));
  const beta = await startStubUpstream(answer({ name: 'widget' }));
  t.after(() => { alpha.close(); beta.close(); });
  return {
    alpha,
    beta,
    servers: [
      { name: 'alpha', url: alpha.url, token: ALPHA_TOKEN },
      { name: 'beta', url: beta.url, token: BETA_TOKEN },
    ],
  };
}

/** A ready session over alpha and beta playing `script`, cleaned up after the test. */
async function readySession(t, { script = SCRIPT_A, input = {}, env = {} } = {}) {
  const ups = await upstreams(t);
  const session = await startSession({ script, env, input: { system_prompt: SYSTEM_PROMPT, mcp_servers: ups.servers, ...input } });
  t.after(() => session.cleanup());
  await session.waitReady();
  return { session, ...ups };
}

async function send(session, text) {
  const res = await session.request('POST', '/message', { text });
  assert.equal(res.status, 202, `POST /message answered ${res.status} ${JSON.stringify(res.body)}`);
}

/** Script A's two messages, one after the other; the whole event log once both ended. */
async function playScriptA(session) {
  await send(session, 'find the angry traces');
  await session.readEvents(0, 'message_end', 1);
  await send(session, 'now the widget');
  return session.readEvents(0, 'message_end', 2);
}

function filesUnder(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) =>
    d.isDirectory() ? filesUnder(path.join(dir, d.name)) : [path.join(dir, d.name)],
  );
}

test('two messages in a row each stream text, tool_start, tool_end and message_end in order', async (t) => {
  // Bug caught: the second message is refused, or its events interleave with the first.
  const { session } = await readySession(t);

  const events = await playScriptA(session);

  assert.deepEqual(events, [...REPLY_1_EVENTS, ...REPLY_2_EVENTS]);
  assert.ok(!JSON.stringify(events).includes('find the angry traces'), 'no event carries the user\'s own text');
});

test('a tool call to each of two named servers reaches its upstream with its own token', async (t) => {
  // Bug caught: the session wires one upstream, or one token for both.
  const { session, alpha, beta } = await readySession(t);

  await playScriptA(session);

  assert.deepEqual(alpha.requests.map((r) => [r.authorization, r.body.params.name]), [[`Bearer ${ALPHA_TOKEN}`, 'list_traces']]);
  assert.deepEqual(beta.requests.map((r) => [r.authorization, r.body.params.name]), [[`Bearer ${BETA_TOKEN}`, 'get_thing']]);
});

test('OpenCode is configured for the session and the prompt selects its agent', async (t) => {
  // Bug caught: the cap, the system prompt or the permission base is missing, or a token reaches
  // OpenCode's config.
  const { session } = await readySession(t);

  await send(session, 'find the angry traces');
  await session.readEvents(0, 'message_end', 1);

  const [record] = session.records();
  const { config } = record;
  assert.deepEqual(config.agent['tessary-session'], {
    mode: 'primary',
    prompt: SYSTEM_PROMPT,
    steps: 3,
    permission: { task: 'deny', skill: 'deny', todowrite: 'allow' },
  });
  assert.deepEqual(config.permission.bash, { '*': 'allow' });
  assert.equal(config.permission.webfetch, 'deny');
  assert.equal(config.permission.websearch, 'deny');
  assert.deepEqual(config.permission.edit, { '*': 'allow' });
  assert.equal(config.permission.external_directory['*'], 'deny');
  assert.match(config.mcp.alpha.url, /\/mcp\/alpha$/);
  assert.match(config.mcp.beta.url, /\/mcp\/beta$/);
  assert.equal(config.mcp.alpha.headers, undefined);
  assert.equal(config.mcp.beta.headers, undefined);
  assert.equal(record.prompts.length, 1);
  for (const body of record.prompts) {
    assert.equal(body.agent, 'tessary-session');
    assert.deepEqual(body.model, { providerID: 'anthropic', modelID: 'claude-sonnet-5' });
  }
});

test('an abort ends the reply as aborted and the session takes the next message', async (t) => {
  // Bug caught: the abort kills the session, or the aborted reply reports done.
  const script = {
    replies: [
      { steps: [{ tools: [{ name: 'alpha_slow', input: {}, duration_ms: 0, hang: true }], usage: usage(5, 1, 0, 0, 0.5) }] },
      { steps: [{ text: ['Still here.'], usage: usage(7, 2, 0, 0, 0.25) }] },
    ],
  };
  const { session } = await readySession(t, { script });

  await send(session, 'take your time');
  const [toolStart] = await session.readEvents(0, 'tool_start', 1);
  assert.deepEqual(toolStart, ev('tool_start', { call_id: 'call_1_1_1', server: 'alpha', tool: 'slow', input_summary: '{}' }));
  assert.deepEqual(await session.request('POST', '/message', { text: 'again' }), { status: 409, body: { kind: 'busy' }, contentType: 'application/json' });
  assert.equal((await session.request('POST', '/abort')).status, 204);

  const reply1 = await session.readEvents(0, 'message_end', 1);
  assert.equal(reply1.length, 3);
  assert.equal(reply1[1].type, 'tool_end');
  assert.equal(reply1[1].data.call_id, 'call_1_1_1');
  assert.equal(reply1[1].data.ok, false);
  assert.equal(reply1[1].data.result_summary, 'Tool execution aborted');
  assert.equal(reply1[1].data.bytes, 22);
  assert.deepEqual(reply1[2], ev('message_end', {
    usage: { input_tokens: 5, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0.5 },
    reason: 'aborted',
  }));

  await send(session, 'are you there?');
  const all = await session.readEvents(0, 'message_end', 2);
  assert.deepEqual(all.slice(3), [
    ev('text', { delta: 'Still here.' }),
    ev('message_end', {
      usage: { input_tokens: 7, output_tokens: 2, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0.25 },
      reason: 'done',
    }),
  ]);
});

const toolStep = (j) => ({ text: [`s${j}`], tools: [{ name: 'alpha_list_traces', input: { q: `q${j}` }, duration_ms: 1 }], usage: usage(1, 1, 0, 0, 0.125) });

test('the tool cap ends a reply with reason limit', async (t) => {
  // Bug caught: a capped reply reads as done, or the cap is not set. The model follows OpenCode's
  // last-step instruction: two steps with a tool, then text only on step 3.
  const script = { replies: [{ steps: [toolStep(1), toolStep(2), { text: ['s3'], usage: usage(1, 1, 0, 0, 0.125) }] }] };
  const { session } = await readySession(t, { script });

  await send(session, 'keep going');
  const events = await session.readEvents(0, 'message_end', 1);

  assert.deepEqual(events.map((e) => (e.type === 'text' ? `text:${e.data.delta}` : e.type)), [
    'text:s1', 'tool_start', 'tool_end', 'text:s2', 'tool_start', 'tool_end', 'text:s3', 'message_end',
  ]);
  assert.equal(events.at(-1).data.reason, 'limit');
  assert.equal(events.at(-1).data.usage.cost_usd, 0.375);
});

test('a model that keeps calling tools past the cap is cut at the cap with reason limit', async (t) => {
  // Bug caught: OpenCode only tells the model to stop at `steps` and keeps offering tools, so a
  // reply that calls one tool more runs until the session's max lifetime, holding its credential
  // and spending. The third call hangs, so only the script's abort at the cap ends the reply.
  const hanging = { text: ['s3'], tools: [{ name: 'alpha_list_traces', input: { q: 'q3' }, duration_ms: 1, hang: true }], usage: usage(1, 1, 0, 0, 0.125) };
  const script = { replies: [{ steps: [toolStep(1), toolStep(2), hanging, toolStep(4)] }] };
  const { session } = await readySession(t, { script });

  await send(session, 'keep going');
  const events = await session.readEvents(0, 'message_end', 1);

  assert.deepEqual(events.map((e) => (e.type === 'text' ? `text:${e.data.delta}` : e.type)), [
    'text:s1', 'tool_start', 'tool_end', 'text:s2', 'tool_start', 'tool_end', 'text:s3', 'tool_start', 'tool_end', 'message_end',
  ]);
  assert.deepEqual(events.at(-2).data, { call_id: 'call_1_3_1', result_summary: 'Tool execution aborted', bytes: 22, duration_ms: events.at(-2).data.duration_ms, ok: false });
  assert.deepEqual(events.at(-1), ev('message_end', {
    usage: { input_tokens: 3, output_tokens: 3, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0.375 },
    reason: 'limit',
  }));
});

test('a request without the secret is 401 on every route', async (t) => {
  // Bug caught: the launcher's port is open to anyone who reaches the sandbox host.
  const { session } = await readySession(t);
  const routes = [['GET', '/healthz'], ['POST', '/message'], ['POST', '/abort'], ['GET', '/events'], ['GET', '/nowhere']];

  for (const auth of [null, 'b'.repeat(64), 'a']) {
    for (const [method, route] of routes) {
      const body = method === 'POST' && route === '/message' ? { text: 'hi' } : undefined;
      const res = await session.request(method, route, body, { auth });
      assert.deepEqual([res.status, res.body], [401, { kind: 'unauthorized' }], `${method} ${route} with ${auth}`);
    }
  }
  assert.deepEqual(await session.request('GET', '/healthz'), { status: 200, body: { state: 'ready' }, contentType: 'application/json' });
});

test('a message with no text is refused and an unknown route is 404', async (t) => {
  // Bug caught: an empty or malformed body reaches OpenCode as a prompt.
  const { session } = await readySession(t);

  for (const body of [{}, { text: '' }, { text: 7 }, 'not json']) {
    assert.deepEqual((await session.request('POST', '/message', body)).body, { kind: 'bad_request' });
  }
  assert.equal((await session.request('POST', '/message', { text: 'x'.repeat(1 << 20) })).status, 400);
  assert.deepEqual(await session.request('GET', '/nowhere'), { status: 404, body: { kind: 'not_found' }, contentType: 'application/json' });
  assert.deepEqual(session.records()[0].prompts, []);
});

test('no token or secret survives on disk, in OpenCode\'s config or env, or in output', async (t) => {
  // Bug caught: a token in session.json, a saved file, the config, the env or a log.
  const { session } = await readySession(t);

  await playScriptA(session);

  const secrets = [ALPHA_TOKEN, BETA_TOKEN, SECRET];
  assert.equal(fs.existsSync(path.join(session.workDir, 'session.json')), false);
  for (const file of filesUnder(session.workDir)) {
    const content = fs.readFileSync(file, 'utf8');
    for (const s of secrets) assert.ok(!content.includes(s), `${file} holds a secret`);
  }
  const [record] = session.records();
  const config = JSON.stringify(record.config);
  for (const s of secrets) assert.ok(!config.includes(s), 'OpenCode\'s config holds a secret');
  assert.equal('SESSION_SECRET' in record.env, false);
  for (const value of Object.values(record.env)) {
    for (const s of secrets) assert.ok(!String(value).includes(s), 'OpenCode\'s env holds a secret');
  }
  for (const s of secrets) {
    assert.ok(!session.stdout().includes(s), 'stdout holds a secret');
    assert.ok(!session.stderr().includes(s), 'stderr holds a secret');
  }
});

test('the dossier lands under dossier/', async (t) => {
  // Bug caught: the agent cannot find the files the prompt names.
  const { session } = await readySession(t, { input: { files: { 'notes/a.md': 'hello' } } });

  assert.equal(fs.readFileSync(path.join(session.workDir, 'dossier', 'notes', 'a.md'), 'utf8'), 'hello');
});

test('a dossier path that escapes fails the start, and the session closes as failed', async (t) => {
  // Bug caught: a session that cannot start stays up and looks alive to the launcher.
  const session = await startSession({ input: { files: { '../x': 'y' } } });
  t.after(() => session.cleanup());
  await session.waitListening();

  const events = await session.readEvents(0, 'closed', 1);

  assert.deepEqual(events, [
    ev('error', { kind: 'start', detail: 'dossier path escapes root: ../x' }),
    ev('closed', { reason: 'failed' }),
  ]);
  assert.deepEqual((await session.request('GET', '/healthz')).body, { state: 'failed' });
  assert.deepEqual((await session.request('POST', '/message', { text: 'hi' })).body, { kind: 'failed' });
  assert.deepEqual(await withDeadline(session.exited, 7_000), { code: 1, signal: null });
});

test('a lost SSE frame still yields tool_end and text before message_end', async (t) => {
  // Bug caught: an event lost in a reconnect gap leaves a step the backend never stores.
  const script = {
    replies: [{ steps: [{ silent: true, text: ['Quiet.'], tools: [{ name: 'alpha_list_traces', input: { q: 'x' }, duration_ms: 3 }], usage: usage(1, 1, 0, 0, 0.125) }] }],
  };
  const { session } = await readySession(t, { script });

  await send(session, 'quietly');
  const events = await session.readEvents(0, 'message_end', 1);

  assert.deepEqual(events, [
    ev('text', { delta: 'Quiet.' }),
    ev('tool_start', { call_id: 'call_1_1_1', server: 'alpha', tool: 'list_traces', input_summary: '{"q":"x"}' }),
    ev('tool_end', { call_id: 'call_1_1_1', result_summary: '{"count":3,"file":"checks/mcp/001-list_traces.json"}', bytes: 52, duration_ms: 3, ok: true }),
    ev('message_end', {
      usage: { input_tokens: 1, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0.125 },
      reason: 'done',
    }),
  ]);
});

test('reasoning text is not the reply', async (t) => {
  // Bug caught: the model's private reasoning shows as the agent's words.
  const script = { replies: [{ steps: [{ reasoning: ['think'], text: ['Answer.'], usage: usage(1, 1, 0, 0, 0.125) }] }] };
  const { session } = await readySession(t, { script });

  await send(session, 'why?');
  const events = await session.readEvents(0, 'message_end', 1);

  assert.deepEqual(events.filter((e) => e.type === 'text'), [ev('text', { delta: 'Answer.' })]);
});

test('a provider error is reported and the reply still ends once', async (t) => {
  // Bug caught: an error leaves the reply open forever, or its spend is not booked.
  const script = { replies: [{ steps: [{ usage: usage(5, 0, 0, 0, 0.5) }], error: { name: 'APIError', data: { message: 'overloaded' } } }] };
  const { session } = await readySession(t, { script });

  await send(session, 'hello');
  const events = await session.readEvents(0, 'message_end', 1);
  const later = await session.eventsWithin(events.length, 300);

  assert.deepEqual(events, [
    ev('error', { kind: 'agent', detail: 'APIError: overloaded' }),
    ev('message_end', {
      usage: { input_tokens: 5, output_tokens: 0, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0.5 },
      reason: 'done',
    }),
  ]);
  assert.deepEqual([...later], [], 'the second idle pair ends nothing');
});

test('a reply whose message never completes after an error still ends', async (t) => {
  // Bug caught: OpenCode reports idle and never completes the message, and the reply stays open,
  // so every later message is 409.
  const script = {
    replies: [
      { steps: [{ usage: usage(5, 0, 0, 0, 0.5) }], error: { name: 'APIError', data: { message: 'overloaded' } } },
      { steps: [{ text: ['Back.'], usage: usage(1, 1, 0, 0, 0.125) }] },
    ],
  };
  const { session } = await readySession(t, { script, env: { FAKE_OPENCODE_NEVER_SETTLE: '1' } });

  await send(session, 'hello');
  const events = await session.readEvents(0, 'message_end', 1);

  assert.deepEqual(events.map((e) => e.type), ['error', 'message_end']);
  assert.equal(events[1].data.reason, 'done');
  await send(session, 'again');
  assert.deepEqual((await session.readEvents(2, 'message_end', 1))[0], ev('text', { delta: 'Back.' }));
});

test('an OpenCode that dies mid-session closes the session as failed', async (t) => {
  // Bug caught: the launcher keeps a dead session as live.
  const { session } = await readySession(t, { env: { FAKE_OPENCODE_EXIT_ON_PROMPT: '1' } });

  const res = await session.request('POST', '/message', { text: 'hello' });
  assert.equal(res.status, 502);
  assert.equal(res.body.kind, 'prompt');
  assert.equal(typeof res.body.detail, 'string');

  const events = await session.eventsWithin(0, 6_000);
  assert.deepEqual([...events], [ev('error', { kind: 'opencode_exit', detail: 'exit code 3' }), ev('closed', { reason: 'failed' })]);
  assert.equal(events.ended, true, 'the /events response ends');
  assert.deepEqual(await withDeadline(session.exited, 7_000), { code: 1, signal: null });
});

test('a reader that reconnects with from gets only what it has not read', async (t) => {
  // Bug caught: the launcher numbers a replayed event twice or skips one.
  const { session } = await readySession(t);

  await send(session, 'find the angry traces');
  assert.deepEqual(await session.readEvents(0, 'message_end', 1), REPLY_1_EVENTS);
  await send(session, 'now the widget');
  assert.deepEqual(await session.readEvents(6, 'message_end', 1), REPLY_2_EVENTS);

  assert.deepEqual([...(await session.eventsWithin(10, 300))], []);
  assert.deepEqual((await session.request('GET', '/events?from=99')), { status: 400, body: { kind: 'bad_request' }, contentType: 'application/json' });
  assert.equal((await session.request('GET', '/events?from=-1')).status, 400);
});

test('no secret, no server', async (t) => {
  // Bug caught: a session that starts open to anyone.
  for (const secret of [null, 'short']) {
    const session = await startSession({ secret });
    t.after(() => session.cleanup());

    assert.deepEqual(await withDeadline(session.exited, 5_000), { code: 2, signal: null });
    assert.match(session.stderr(), /SESSION_SECRET is required/);
    await assert.rejects(fetch(`${session.base}/healthz`), /fetch failed/);
  }
});

test('a late idle from the previous reply does not end the new one', async (t) => {
  // Bug caught: a phantom message_end with zero usage while the agent still works, then a stuck reply.
  const { session } = await readySession(t, { env: { FAKE_OPENCODE_STRAY_IDLE: '1' } });

  assert.deepEqual(await playScriptA(session), [...REPLY_1_EVENTS, ...REPLY_2_EVENTS]);
});

test('a reply whose idle was lost in a stream reconnect still ends', async (t) => {
  // Bug caught: the reply stays open and every later message is 409.
  const { session } = await readySession(t, { env: { FAKE_OPENCODE_DROP_BEFORE_IDLE: '1' } });

  assert.deepEqual(await playScriptA(session), [...REPLY_1_EVENTS, ...REPLY_2_EVENTS]);
});

test('a reply whose user message was lost in a stream reconnect still ends', async (t) => {
  // Bug caught: the reply is never armed, ignores every later event and idle, and stays open, so
  // every later message is 409 and its spend is never booked.
  const { session } = await readySession(t, { env: { FAKE_OPENCODE_DROP_BEFORE_USER: '1' } });

  await send(session, 'find the angry traces');
  assert.deepEqual(await session.readEvents(0, 'message_end', 1), [
    ev('text', { delta: 'Looking.' }),
    REPLY_1_EVENTS[2],
    REPLY_1_EVENTS[3],
    ev('text', { delta: 'Found 3.' }),
    REPLY_1_EVENTS[5],
  ]);
  await send(session, 'now the widget');
  assert.deepEqual(await session.readEvents(5, 'message_end', 1), REPLY_2_EVENTS);
});

test('a reply OpenCode compacts midway streams the steps after it and books every step', async (t) => {
  // Bug caught: the steps after an automatic compaction answer a new user message, so their text
  // and tools are dropped and the compaction's and the continuation's spend is never booked. The
  // summary is OpenCode's own note, not the agent's words, and is not a step: at a cap of 3 the
  // agent has 4 steps, so three steps and the summary still end as done.
  const script = {
    replies: [{
      steps: [
        { text: ['Look.'], tools: [{ name: 'alpha_list_traces', input: { q: 'a' }, duration_ms: 2 }], usage: usage(100, 10, 0, 0, 0.25) },
        {
          compact: { summary: ['Earlier: looked.'], usage: usage(50, 20, 0, 0, 0.5) },
          tools: [{ name: 'beta_get_thing', input: { id: 'w1' }, duration_ms: 4 }],
          usage: usage(10, 2, 0, 0, 0.125),
        },
        { text: ['Done.'], usage: usage(12, 3, 0, 0, 0.125) },
      ],
    }],
  };
  const { session } = await readySession(t, { script, input: { max_tool_calls_per_message: 3 } });

  await send(session, 'go');
  const events = await session.readEvents(0, 'message_end', 1);

  assert.deepEqual(events, [
    ev('text', { delta: 'Look.' }),
    ev('tool_start', { call_id: 'call_1_1_1', server: 'alpha', tool: 'list_traces', input_summary: '{"q":"a"}' }),
    ev('tool_end', { call_id: 'call_1_1_1', result_summary: '{"count":3,"file":"checks/mcp/001-list_traces.json"}', bytes: 52, duration_ms: 2, ok: true }),
    ev('tool_start', { call_id: 'call_1_2_1', server: 'beta', tool: 'get_thing', input_summary: '{"id":"w1"}' }),
    ev('tool_end', { call_id: 'call_1_2_1', result_summary: '{"name":"widget","file":"checks/mcp/002-get_thing.json"}', bytes: 56, duration_ms: 4, ok: true }),
    ev('text', { delta: 'Done.' }),
    ev('message_end', {
      usage: { input_tokens: 172, output_tokens: 35, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 1 },
      reason: 'done',
    }),
  ]);
});

function withDeadline(promise, ms) {
  return Promise.race([promise, new Promise((_, reject) => setTimeout(() => reject(new Error(`not settled in ${ms}ms`)), ms))]);
}
