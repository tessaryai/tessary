// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * F1/F3/E coverage for agent-stream.js's runAgent(): the turn-accumulation fix (a failing lane's
 * spend must survive a fresh-session retry) and the retry gate (E — a session that already did
 * real work must not be silently re-run at double cost). Run with:
 *
 *   node --experimental-test-module-mocks --test test/agent-stream.test.js
 *
 * (the "test" script in package.json passes the flag; node:test's `mock.module` needs it.)
 *
 * WHY MOCK @opencode-ai/sdk RATHER THAN SPIN UP A REAL `opencode serve`: runAgent's own contract
 * with the SDK is one HTTP-shaped client (`session.create` / `session.prompt` / `session.messages`)
 * — everything this file is responsible for (accumulating turns across a fresh-session retry,
 * gating that retry, summing usage) sits entirely on top of that client's return values, so faking
 * them exercises the real code under test without needing a live agent, a model, or a network call.
 * `createOpencodeClient` is the ONLY SDK entry point runAgent touches — mocking exactly that keeps
 * the rest of the module (splitModel, toTurns, sumUsage, toEnvelope, the retry loop itself)
 * genuinely under test.
 *
 * THE SERVER PROCESS IS REAL, just not opencode. agent-stream.js spawns `opencode serve` itself
 * (see its header), so the `opencode` on this process's PATH is test/fixtures/fake-opencode: it
 * announces itself like opencode and records the config it was started with, which is how the
 * tests below read what runAgent configured. Its HTTP routes go unused here (the client is mocked).
 *
 * WHY NOT DRIVE triage.js/rca.js DIRECTLY for the failure-envelope shape (b): their `main()` is not
 * exported and ends in `process.exit`, which is awkward to assert against in-process without a
 * child process per case. Both scripts' catch blocks do exactly two things with runAgent's thrown
 * error — `describeError(e)` and `sumUsage(e.turns)` — and JSON.stringify the result; that contract
 * is tested directly against the real exported functions below, which is the part actually worth
 * protecting (the numeric-only shape server.js's HARD RULE depends on).
 */
const { test, mock } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// agent-stream.js reads WORK_DIR (-> WORK -> AGENT_CWD) at module load time, and runAgent's
// startServer() chdir()s into it — so this MUST be a real, existing directory, set BEFORE the
// first require of the module under test. '/home/user' (the module's own default) does not exist
// on a dev/CI host.
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
 * One assistant turn in the shape `toTurns` reads (see agent-stream.js's partsOf/toolCallsOf/
 * usageOf): `{info: {...}, parts: [...]}`, where `info.parts` being absent is what makes `toTurns`
 * read `parts` off the outer object (`m.parts ? m : msg` in the real code) rather than `info`.
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
 * Install a fake @opencode-ai/sdk for one test. `messagesById(id, callCount)` returns the message
 * array `session.messages` answers with for that session id on its Nth call (1-based) — modelling
 * the real API, where messages() always returns a session's FULL history so far.
 *
 * Also records what runAgent handed over: `serverConfigs()` returns every config a (fake) opencode
 * was started with, and `promptBodies` every `body` session.prompt was called with — the two places
 * the systemPrompt/no-systemPrompt split (custom triage agent vs. the RCA path) is visible.
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
  // Session 1: ONE assistant turn, empty completion (no text, no tool calls) but real input spend
  // (Bedrock/non-Anthropic "done with nothing in it" — see isEmptyCompletion's doc comment). A lone
  // empty turn is cheap-looking enough that the E gate (below) allows the fresh-session retry.
  // Session 2: the same shape, so the run ultimately fails — the case that matters here is whether
  // session 1's spend is still counted in the failure.
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
  // Session 1: TWO turns — the first does substantial (tool-calling) work, the second ends empty.
  // isEmptyCompletion looks only at the LAST turn, so the old code retried this from scratch,
  // re-paying for turn 1's work. The gate must instead let the run fail on this session.
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
  // Session 1, first prompt: real (tool-calling) investigation work, then an empty final turn —
  // the shape opencode's own step cap produces (a forced text-only turn with no budget left), not
  // a provider giving up. The resume must reuse this session and ask it to answer with no tools.
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
    // Same session id ('s1') both times — messages() answers the FULL cumulative history, exactly
    // as the real opencode server does. The second call adds the corrected reply on top of the first.
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

  // No mock of mcp-relay.js: it is cheap to run for real (binds a loopback port, does not touch
  // the network until an actual tools/call arrives, which never happens here since the SDK client
  // is mocked) — see agent-stream.js's runAgent for why the relay is only ever real here or in prod.
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
  // No mock of mcp-relay.js here either (see the systemPrompt test above for why that is cheap and
  // real): the whole point of this test is that a REAL listening socket gets closed, which a mocked
  // relay could not demonstrate.
  mock.module('@opencode-ai/sdk', {
    namedExports: {
      createOpencodeClient: () => {
        throw new Error('must not be reached: the server never started');
      },
    },
  });
  // The fake records its config, then exits before announcing — the "Server exited with code 1"
  // start failure (a bad config, say), after the relay is already listening.
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
  // The live bug: the SDK's close() sent one SIGTERM and returned, an opencode that did not exit on
  // it kept its pipes open, and the lane's process outlived its own run. Both outcomes are covered —
  // the close sits in runAgent's `finally`, which a failed run reaches by a different path.
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
  // Reproduces exactly what triage.js/rca.js's catch block does with a thrown runAgent error, using
  // the same two exported functions they call — see the file header for why this is tested at that
  // level rather than by driving triage.js's non-exported, process.exit-ing main().
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
