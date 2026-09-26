// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Tests for agent-stream.js runAgent(): a failing lane's spend survives a fresh-session retry, and a session that
 * already did real work is not re-run at double cost. Run with `node --experimental-test-module-mocks --test
 * test/agent-stream.test.js` (the package "test" script passes the flag).
 *
 * Only `createOpencodeClient` from @opencode-ai/sdk is mocked, so turn accumulation, the retry gate, and usage sums
 * run for real. The server process is test/fixtures/fake-opencode on PATH, which records the config it was started
 * with. The failure envelope is tested through the exported `describeError` and `sumUsage` that triage.js and rca.js
 * call, since their `main()` ends in `process.exit`.
 */
const { test, mock } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Read at module load, and runAgent chdir()s into it, so it must exist before the first require.
const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-stream-test-'));
process.env.WORK_DIR = workDir;

const { makeFakeOpencodeBin } = require('./fixtures/fake-opencode');
const fakeBin = makeFakeOpencodeBin(fs.mkdtempSync(path.join(os.tmpdir(), 'agent-stream-test-bin-')));
const recordDir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-stream-test-record-'));
process.env.PATH = `${fakeBin}${path.delimiter}${process.env.PATH}`;
process.env.FAKE_OPENCODE_RECORD_DIR = recordDir;

/** What every fake opencode started since the last clearRecords() was launched with, in start order. */
function serverRecords() {
  return fs
    .readdirSync(recordDir)
    .map((f) => JSON.parse(fs.readFileSync(path.join(recordDir, f), 'utf8')))
    .sort((a, b) => fs.statSync(path.join(recordDir, `${a.pid}.json`)).mtimeMs - fs.statSync(path.join(recordDir, `${b.pid}.json`)).mtimeMs);
}

function clearRecords() {
  for (const f of fs.readdirSync(recordDir)) fs.rmSync(path.join(recordDir, f));
}

const SCHEMA = { type: 'object', required: ['verdict'], properties: { verdict: { type: 'string' } } };
const OK_REPLY = JSON.stringify({ verdict: 'ok' });
const MCP = { url: 'https://tessary.example/mcp', token: 'tsy_a_live-token' };

/** Whether a process with this pid still exists. */
function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

/**
 * One assistant turn in the `{info, parts}` shape `toTurns` reads; no `info.parts` makes it read the outer `parts`.
 */
function assistantMessage({ text = '', usage = {}, toolCalls = [] }) {
  const parts = [];
  if (text) parts.push({ type: 'text', text });
  for (const tc of toolCalls) parts.push({ type: 'tool', tool: tc, callID: tc, state: { input: {} } });
  return {
    info: {
      role: 'assistant',
      modelID: 'test/model',
      time: { completed: Date.now() },
      tokens: {
        input: usage.input_tokens || 0,
        output: usage.output_tokens || 0,
        cache: { read: usage.cache_read_input_tokens || 0, write: usage.cache_creation_input_tokens || 0 },
      },
    },
    parts,
  };
}

/**
 * A fake @opencode-ai/sdk for one test. `messagesById(id, callCount)` answers `session.messages`, which returns a
 * session's full history so far. Records server configs and every `session.prompt` body.
 */
function mockSdk({ messagesById }) {
  let sessionCounter = 0;
  const createCalls = [];
  const promptBodies = [];
  const callCountById = {};
  clearRecords();
  const serverConfigs = () => serverRecords().map((r) => r.config);
  mock.module('@opencode-ai/sdk', {
    namedExports: {
      createOpencodeClient: () => ({
        session: {
          create: async () => {
            const id = `s${++sessionCounter}`;
            createCalls.push(id);
            return { id };
          },
          prompt: async ({ body }) => {
            promptBodies.push(body);
            return {}; // text is read back off messages(), not the reply itself
          },
          messages: async ({ path: p }) => {
            callCountById[p.id] = (callCountById[p.id] || 0) + 1;
            return messagesById(p.id, callCountById[p.id]);
          },
        },
      }),
    },
  });
  return { createCalls, serverConfigs, promptBodies };
}

test('F1: a fresh-session retry does not lose attempt 1\'s usage', async (t) => {
  t.after(() => mock.reset());
  // Session 1: one empty turn with real input spend, cheap enough that the gate allows a retry. Session 2 fails the
  // same way; session 1's spend must still be counted.
  const { runAgent } = require('../agent-stream');
  mockSdk({
    messagesById: (id) =>
      id === 's1'
        ? [assistantMessage({ usage: { input_tokens: 500, output_tokens: 0 } })]
        : [assistantMessage({ usage: { input_tokens: 300, output_tokens: 0 } })],
  });

  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'investigate', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 }),
    (err) => {
      assert.ok(Array.isArray(err.turns), 'thrown error carries .turns');
      assert.equal(err.turns.length, 2, 'both sessions\' turns survive into the failure');
      const { sumUsage } = require('../agent-stream');
      const usage = sumUsage(err.turns);
      assert.equal(usage.input_tokens, 800, 'attempt 1\'s 500 input tokens were not discarded by the retry');
      return true;
    },
  );
});

test('E: a session that already did real work is not silently retried at double cost', async (t) => {
  t.after(() => mock.reset());
  // Two turns, real work then an empty one. isEmptyCompletion reads only the last turn, so the old code re-ran turn
  // 1's work; the gate must fail on this session instead.
  const { runAgent } = require('../agent-stream');
  const { createCalls, promptBodies } = mockSdk({
    messagesById: () => [
      assistantMessage({ toolCalls: ['read_file'], usage: { input_tokens: 400, output_tokens: 100 } }),
      assistantMessage({ usage: { input_tokens: 50, output_tokens: 0 } }),
    ],
  });

  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'investigate', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 }),
  );
  assert.equal(createCalls.length, 1, 'a session with real prior work must not trigger a second, fresh session');
  assert.equal(promptBodies.length, 1, 'no systemPrompt: the 4B turn-cap resume never applies, so this fails on the first prompt');
});

test('4B: triage resumes the same session once, no tools, when the turn cap empties the final reply', async (t) => {
  t.after(() => mock.reset());
  // Tool-calling work, then an empty final turn: opencode's step cap, not a provider giving up. The resume must reuse
  // this session and ask for an answer with no tools.
  const toolTurn = assistantMessage({
    toolCalls: ['get_finding_evidence'],
    usage: { input_tokens: 400, output_tokens: 100 },
  });
  const emptyTurn = assistantMessage({ usage: { input_tokens: 50, output_tokens: 0 } });
  const rulingTurn = assistantMessage({
    text: JSON.stringify({ verdict: 'positive' }),
    usage: { input_tokens: 20, output_tokens: 15 },
  });
  const { runAgent } = require('../agent-stream');
  const { createCalls, promptBodies } = mockSdk({
    messagesById: (id, callCount) => (callCount === 1 ? [toolTurn, emptyTurn] : [toolTurn, emptyTurn, rulingTurn]),
  });

  const run = await runAgent({
    model: 'anthropic/claude-sonnet-5',
    prompt: 'rule on this finding',
    jsonSchema: SCHEMA,
    mcp: MCP,
    systemPrompt: 'You are the triage agent.',
    timeoutMs: 1000,
  });

  assert.equal(createCalls.length, 1, 'the turn-cap resume reuses the same session, never a fresh one');
  assert.equal(promptBodies.length, 2, 'two prompts: the investigation, then the no-tools correction');
  assert.match(
    promptBodies[1].parts[0].text,
    /Do NOT call any tool/,
    'the correction tells the agent its tool budget is gone',
  );
  assert.equal(run.turns.length, 3, 'every turn of the one session counts, the tool-calling one included');
  const { sumUsage } = require('../agent-stream');
  const usage = sumUsage(run.turns);
  assert.equal(usage.input_tokens, 400 + 50 + 20, 'usage sums all three turns, not just the resumed reply');
  assert.equal(usage.output_tokens, 100 + 0 + 15);
});

test('4B: still empty after the resume, the run rejects after exactly two prompts', async (t) => {
  t.after(() => mock.reset());
  const toolTurn = assistantMessage({
    toolCalls: ['get_finding_evidence'],
    usage: { input_tokens: 400, output_tokens: 100 },
  });
  const emptyTurn = assistantMessage({ usage: { input_tokens: 50, output_tokens: 0 } });
  const stillEmptyTurn = assistantMessage({ usage: { input_tokens: 10, output_tokens: 0 } });
  const { runAgent } = require('../agent-stream');
  const { createCalls, promptBodies } = mockSdk({
    messagesById: (id, callCount) => (callCount === 1 ? [toolTurn, emptyTurn] : [toolTurn, emptyTurn, stillEmptyTurn]),
  });

  await assert.rejects(
    runAgent({
      model: 'anthropic/claude-sonnet-5',
      prompt: 'rule on this finding',
      jsonSchema: SCHEMA,
      mcp: MCP,
      systemPrompt: 'You are the triage agent.',
      timeoutMs: 1000,
    }),
  );

  assert.equal(createCalls.length, 1, 'still just the one session');
  assert.equal(promptBodies.length, 2, 'the resume is spent once — a second empty reply fails rather than retrying again');
});

test('C/F3: a schema-miss same-session retry does not double-count usage', async (t) => {
  t.after(() => mock.reset());
  const schema = { type: 'object', required: ['foo'], properties: { foo: { type: 'string' } } };
  const proseUsage = { input_tokens: 100, output_tokens: 50 };
  const jsonUsage = { input_tokens: 20, output_tokens: 30 };
  const { runAgent } = require('../agent-stream');
  const { createCalls } = mockSdk({
    // Same session id both times; messages() returns the full cumulative history, as the real server does.
    messagesById: (id, callCount) => {
      const prose = assistantMessage({ text: 'here is my analysis, sorry no JSON', usage: proseUsage });
      if (callCount === 1) return [prose];
      const compliant = assistantMessage({ text: JSON.stringify({ foo: 'bar' }), usage: jsonUsage });
      return [prose, compliant];
    },
  });

  const run = await runAgent({
    model: 'anthropic/claude-sonnet-5',
    prompt: 'investigate',
    mcp: MCP,
    jsonSchema: schema,
    timeoutMs: 1000,
  });

  assert.equal(createCalls.length, 1, 'a schema-miss retry reuses the SAME session, never a fresh one');
  assert.equal(run.turns.length, 2, 'both turns of the one session are counted once each, not duplicated');
  const { sumUsage } = require('../agent-stream');
  const usage = sumUsage(run.turns);
  assert.equal(usage.input_tokens, proseUsage.input_tokens + jsonUsage.input_tokens);
  assert.equal(usage.output_tokens, proseUsage.output_tokens + jsonUsage.output_tokens);
  const envelope = JSON.parse(run.resultRaw);
  assert.deepEqual(envelope.usage, usage, 'the success envelope reports the same, non-doubled sum');
});

test('sumUsage: pure reducer over a turns[] array', () => {
  const { sumUsage } = require('../agent-stream');
  const turns = [
    { usage: { input_tokens: 10, output_tokens: 1, cache_read_input_tokens: 2, cache_creation_input_tokens: 3 } },
    { usage: { input_tokens: 5, output_tokens: 2, cache_read_input_tokens: 0, cache_creation_input_tokens: 1 } },
  ];
  assert.deepEqual(sumUsage(turns), {
    input_tokens: 15,
    output_tokens: 3,
    cache_read_input_tokens: 2,
    cache_creation_input_tokens: 4,
  });
  assert.deepEqual(sumUsage([]), {
    input_tokens: 0,
    output_tokens: 0,
    cache_read_input_tokens: 0,
    cache_creation_input_tokens: 0,
  });
  assert.deepEqual(sumUsage(undefined), sumUsage([]), 'a missing turns array (e.g. a run with no .turns) is safe');
});

test('systemPrompt: selects the custom triage agent and routes MCP through a loopback relay', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const { serverConfigs, promptBodies } = mockSdk({
    messagesById: () => [assistantMessage({ text: OK_REPLY, usage: { input_tokens: 10, output_tokens: 5 } })],
  });

  // mcp-relay.js runs for real: it binds a loopback port and never sees a tools/call here.
  await runAgent({
    model: 'anthropic/claude-sonnet-5',
    prompt: 'rule on this finding',
    jsonSchema: SCHEMA,
    systemPrompt: 'You are the triage agent. Goals: ...',
    mcp: { url: 'https://tessary.example/mcp', token: 'tsy_a_live-token' },
    maxTurns: 10,
    timeoutMs: 1000,
  });

  assert.equal(serverConfigs().length, 1);
  const config = serverConfigs()[0];
  const agentCfg = config.agent && config.agent['tessary-triage'];
  assert.ok(agentCfg, 'the custom triage agent is defined');
  assert.equal(agentCfg.mode, 'primary');
  assert.equal(agentCfg.prompt, 'You are the triage agent. Goals: ...', 'prompt carries the system prompt verbatim');
  assert.equal(agentCfg.steps, 8, 'steps is maxTurns - 2, the same margin build.maxSteps uses');
  assert.deepEqual(agentCfg.permission, { task: 'deny', skill: 'deny', todowrite: 'allow' });
  assert.equal(config.agent.build, undefined, 'no default-agent config leaks in alongside the custom one');

  const mcpCfg = config.mcp && config.mcp['tessary-evals'];
  assert.ok(mcpCfg, 'MCP is configured');
  assert.match(mcpCfg.url, /^http:\/\/127\.0\.0\.1:\d+\/mcp$/, 'points at the loopback relay, not the real endpoint');
  assert.equal(mcpCfg.headers, undefined, 'no bearer token in opencode config — the relay holds it, not us');
  assert.equal(mcpCfg.oauth, false);

  assert.equal(promptBodies.length, 1);
  assert.equal(promptBodies[0].agent, 'tessary-triage', 'the custom agent is selected on every session.prompt call');
});

test('5: triage opens external_directory to opencode\'s own tmp and tool-output globs, nothing else', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const { serverConfigs } = mockSdk({
    messagesById: () => [assistantMessage({ text: OK_REPLY, usage: { input_tokens: 10, output_tokens: 5 } })],
  });

  await runAgent({
    model: 'anthropic/claude-sonnet-5',
    prompt: 'rule on this finding',
    jsonSchema: SCHEMA,
    mcp: MCP,
    systemPrompt: 'You are the triage agent.',
    timeoutMs: 1000,
  });

  const extDir = serverConfigs()[0].permission.external_directory;
  const keys = Object.keys(extDir);
  assert.equal(keys.length, 3, 'the base deny plus exactly the two allows, nothing more');
  assert.equal(extDir['*'], 'deny');
  const os = require('node:os');
  const path = require('node:path');
  assert.equal(extDir[path.join(os.tmpdir(), 'opencode', '*')], 'allow');
  const dataHome = process.env.XDG_DATA_HOME || path.join(os.homedir(), '.local', 'share');
  assert.equal(extDir[path.join(dataHome, 'opencode', 'tool-output', '*')], 'allow');
});

test('5: RCA (no systemPrompt) keeps external_directory as a blanket deny, unchanged', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const { serverConfigs } = mockSdk({
    messagesById: () => [assistantMessage({ text: OK_REPLY, usage: { input_tokens: 10, output_tokens: 5 } })],
  });

  await runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'investigate this finding', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 });

  assert.deepEqual(serverConfigs()[0].permission.external_directory, { '*': 'deny' });
});

test('no systemPrompt (RCA): unchanged — default build agent, direct MCP with a bearer header, no agent on the prompt', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const { serverConfigs, promptBodies } = mockSdk({
    messagesById: () => [assistantMessage({ text: OK_REPLY, usage: { input_tokens: 10, output_tokens: 5 } })],
  });

  await runAgent({
    model: 'anthropic/claude-sonnet-5',
    prompt: 'investigate this finding',
    jsonSchema: SCHEMA,
    mcp: { url: 'https://tessary.example/mcp', token: 'tsy_a_live-token' },
    maxTurns: 10,
    timeoutMs: 1000,
  });

  const config = serverConfigs()[0];
  assert.deepEqual(config.agent, { build: { maxSteps: 8 } }, 'the RCA path still runs under opencode\'s default agent');
  assert.deepEqual(config.mcp['tessary-evals'], {
    type: 'remote',
    url: 'https://tessary.example/mcp',
    enabled: true,
    headers: { Authorization: 'Bearer tsy_a_live-token' },
  });
  assert.equal(promptBodies[0].agent, undefined, 'no agent field — the session runs under whatever config.agent picks');
});

/** Whether `relayUrl` is still accepting connections, by trying one. */
async function relayRefusesConnections(relayUrl) {
  try {
    await fetch(relayUrl, { method: 'POST', body: '{}' });
    return false;
  } catch (e) {
    return /fetch failed/.test(String(e && e.message)) && (e.cause && e.cause.code) === 'ECONNREFUSED';
  }
}

test('decision 2: a failed server start still closes the relay, so it cannot hang the process', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  // A real relay: the test is that a real listening socket gets closed.
  mock.module('@opencode-ai/sdk', {
    namedExports: {
      createOpencodeClient: () => {
        throw new Error('must not be reached: the server never started');
      },
    },
  });
  // The fake exits before announcing, after the relay is already listening.
  process.env.FAKE_OPENCODE_EXIT_BEFORE_READY = '1';
  t.after(() => {
    delete process.env.FAKE_OPENCODE_EXIT_BEFORE_READY;
  });
  clearRecords();

  await assert.rejects(
    runAgent({
      model: 'anthropic/claude-sonnet-5',
      prompt: 'rule on this finding',
      jsonSchema: SCHEMA,
      systemPrompt: 'You are the triage agent.',
      mcp: { url: 'https://tessary.example/mcp', token: 'tsy_a_live-token' },
      timeoutMs: 1000,
    }),
    /opencode server did not start/,
  );

  const relayUrl = serverRecords()[0].config.mcp['tessary-evals'].url;
  assert.match(relayUrl, /^http:\/\/127\.0\.0\.1:\d+\/mcp$/, 'the relay really did start, on a real loopback port');
  assert.ok(
    await relayRefusesConnections(relayUrl),
    'the relay must be closed once runAgent rejects, not left listening — that listening socket is the hang decision 2 fixes',
  );
});

test('decision 2: a successful run also closes the relay once it is done', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const { serverConfigs } = mockSdk({
    messagesById: () => [assistantMessage({ text: OK_REPLY, usage: { input_tokens: 10, output_tokens: 5 } })],
  });

  await runAgent({
    model: 'anthropic/claude-sonnet-5',
    prompt: 'rule on this finding',
    jsonSchema: SCHEMA,
    systemPrompt: 'You are the triage agent.',
    mcp: { url: 'https://tessary.example/mcp', token: 'tsy_a_live-token' },
    timeoutMs: 1000,
  });

  const relayUrl = serverConfigs()[0].mcp['tessary-evals'].url;
  assert.ok(await relayRefusesConnections(relayUrl), 'the relay closes on the success path too, same as it always did');
});

test('shutdown: runAgent does not return until an opencode that ignores SIGTERM is gone', async (t) => {
  t.after(() => mock.reset());
  // Live bug: close() sent one SIGTERM and returned, so an opencode ignoring it outlived the run. The close sits in
  // `finally`, so success and failure both reach it.
  process.env.FAKE_OPENCODE_IGNORE_SIGTERM = '1';
  t.after(() => {
    delete process.env.FAKE_OPENCODE_IGNORE_SIGTERM;
  });
  const { runAgent } = require('../agent-stream');

  mockSdk({ messagesById: () => [assistantMessage({ text: OK_REPLY, usage: { input_tokens: 10, output_tokens: 5 } })] });
  await runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'rule on this finding', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 });
  const [ok] = serverRecords();
  assert.equal(isAlive(ok.pid), false, 'a successful run returns only once the server process is gone');

  mock.reset();
  mockSdk({ messagesById: () => [assistantMessage({ usage: { input_tokens: 10, output_tokens: 0 } })] });
  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'rule on this finding', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 }),
    /opencode produced no usable reply/,
  );
  const [failed] = serverRecords();
  assert.equal(isAlive(failed.pid), false, 'a failed run rejects only once the server process is gone');
});

test('b: the failure envelope triage.js/rca.js write is valid JSON with a numeric-only usage object', () => {
  // What triage.js and rca.js do with a thrown runAgent error, through the same two exported functions.
  const { describeError, sumUsage } = require('../agent-stream');
  const err = Object.assign(new Error('opencode produced no usable reply'), {
    turns: [{ usage: { input_tokens: 12, output_tokens: 3, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 } }],
  });
  const stdout = JSON.stringify({ is_error: true, error: describeError(err), usage: sumUsage(err.turns) });
  const parsed = JSON.parse(stdout); // must round-trip: this is what server.js's launcher parses
  assert.equal(parsed.is_error, true);
  assert.equal(typeof parsed.error, 'string');
  for (const [k, v] of Object.entries(parsed.usage)) {
    assert.equal(typeof v, 'number', `usage.${k} must be numeric — server.js's HARD RULE forwards this verbatim`);
  }
});

test('describeError: a non-Error cause is serialised, scrubbed, and never "[object Object]"', () => {
  const { describeError } = require('../agent-stream');
  const body = { name: 'ConfigInvalidError', data: { path: 'https://x-access-token:ghs_1@github.com/a/b' } };
  assert.equal(
    describeError(new Error('Bad Request', { cause: { body, status: 400 } })),
    'Bad Request <- caused by: {"body":{"name":"ConfigInvalidError","data":{"path":"https://x-access-token:***@github.com/a/b"}},"status":400}',
  );
  assert.equal(describeError(new Error('failed', { cause: 'tsy_a_secret-key rejected' })), 'failed <- caused by: tsy_*** rejected');
  const circular = { status: 500 };
  circular.self = circular;
  assert.equal(describeError(new Error('failed', { cause: circular })), 'failed <- caused by: [object Object]', 'an unserialisable cause still describes, never throws');
});

test('the provider config the launcher passes down is merged into opencode\'s, not replaced', async (t) => {
  const inherited = { provider: { 'bedrock-mantle-gpt': { npm: '@ai-sdk/amazon-bedrock/mantle', options: { baseURL: 'https://m.example' } } } };
  process.env.OPENCODE_CONFIG_CONTENT = JSON.stringify(inherited);
  t.after(() => {
    delete process.env.OPENCODE_CONFIG_CONTENT;
    mock.reset();
  });
  const { runAgent } = require('../agent-stream');
  const { serverConfigs } = mockSdk({ messagesById: () => [assistantMessage({ text: OK_REPLY })] });

  await runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'p', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 });

  const config = serverConfigs()[0];
  assert.deepEqual(config.provider, inherited.provider, 'dropping it leaves the mantle lanes with no provider to resolve');
  assert.ok(config.mcp['tessary-evals'], 'and runAgent\'s own keys still land beside it');
});

test('a brace-balanced aside before the JSON object does not hide the object', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const { promptBodies } = mockSdk({
    messagesById: () => [assistantMessage({ text: 'Checked {the evidence} first. {"verdict":"ok"}' })],
  });

  const run = await runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'p', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 });

  assert.equal(promptBodies.length, 1, 'no correction round for a reply that complied');
  assert.deepEqual(JSON.parse(run.resultRaw).structured_output, { verdict: 'ok' });
});

test('JSON still missing a required key after the correction fails the run, naming the key both times', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const partial = assistantMessage({ text: '{"reason":"because"}', usage: { input_tokens: 5 } });
  const { promptBodies } = mockSdk({ messagesById: (id, n) => Array.from({ length: n }, () => partial) });

  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'p', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 }),
    (err) => {
      assert.equal(err.message, 'opencode returned JSON missing required verdict (2 attempts)');
      assert.equal(err.turns.length, 2, 'the failure still carries both turns\' spend');
      return true;
    },
  );
  assert.match(promptBodies[1].parts[0].text, /^Your previous reply was missing verdict\./);
});

test('prose twice fails the run as no JSON object, rather than persisting the prose', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  const prose = assistantMessage({ text: 'The verdict is positive.' });
  mockSdk({ messagesById: (id, n) => Array.from({ length: n }, () => prose) });

  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'p', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 }),
    { message: 'opencode did not return a JSON object for the requested schema (2 attempts)' },
  );
});

test('a session that never produced a turn fails as no result', async (t) => {
  t.after(() => mock.reset());
  const { runAgent } = require('../agent-stream');
  mockSdk({ messagesById: () => [] });

  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'p', jsonSchema: SCHEMA, mcp: MCP, timeoutMs: 1000 }),
    (err) => {
      assert.equal(err.message, 'opencode produced no result');
      assert.deepEqual(err.turns, []);
      return true;
    },
  );
});
