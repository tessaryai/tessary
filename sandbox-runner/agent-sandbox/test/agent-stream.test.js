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
 * `createOpencodeServer`/`createOpencodeClient` are the ONLY two SDK entry points runAgent touches
 * (see startServer/runAgent) — mocking exactly those two keeps the rest of the module (splitModel,
 * toTurns, sumUsage, toEnvelope, the retry loop itself) genuinely under test.
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
 */
function mockSdk({ messagesById }) {
  let sessionCounter = 0;
  const createCalls = [];
  const callCountById = {};
  mock.module('@opencode-ai/sdk', {
    namedExports: {
      createOpencodeServer: async () => ({ url: 'http://127.0.0.1:1', close() {} }),
      createOpencodeClient: () => ({
        session: {
          create: async () => {
            const id = `s${++sessionCounter}`;
            createCalls.push(id);
            return { id };
          },
          prompt: async () => ({}), // text is read back off messages(), not the reply itself
          messages: async ({ path: p }) => {
            callCountById[p.id] = (callCountById[p.id] || 0) + 1;
            return messagesById(p.id, callCountById[p.id]);
          },
        },
      }),
    },
  });
  return { createCalls };
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
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'investigate', timeoutMs: 1000 }),
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
  const { createCalls } = mockSdk({
    messagesById: () => [
      assistantMessage({ toolCalls: ['read_file'], usage: { input_tokens: 400, output_tokens: 100 } }),
      assistantMessage({ usage: { input_tokens: 50, output_tokens: 0 } }),
    ],
  });

  await assert.rejects(
    runAgent({ model: 'anthropic/claude-sonnet-5', prompt: 'investigate', timeoutMs: 1000 }),
  );
  assert.equal(createCalls.length, 1, 'a session with real prior work must not trigger a second, fresh session');
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
