// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Decision 2, end to end: with `opencode` unreachable, triage.js's own process must exit fast and
 * clean, not hang until the launcher's own deadline. agent-stream.test.js covers the same failure
 * in-process (a fake opencode that exits before announcing, and an assertion the relay refuses
 * connections afterwards) — this spawns the REAL script instead, because the bug this guards is
 * entirely about whether the PROCESS exits, which importing runAgent() into the test runner's own
 * event loop can never observe (the runner's other handles would mask a leak).
 *
 * `mcp` + `system_prompt` are both set on purpose: the relay only starts on that path (see
 * agent-stream.js's runAgent), and the pre-fix bug was specifically a server-start failure
 * leaving THAT relay's listening socket open — a run with no relay never exercised it. Before the
 * fix, this input hung for the full 900s default deadline (measured against the pre-fix code);
 * now it exits in well under a second.
 *
 * Run with: node --test test/lane-exit.test.js
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { runLane } = require('./fixtures/run-lane');

const TRIAGE_INPUT = {
  files: { 'finding.md': 'a finding to rule on' },
  prompt: 'rule on this finding',
  system_prompt: 'You are the triage agent.',
};

test('triage.js exits fast and clean when opencode is unreachable, instead of hanging', async (t) => {
  // An empty PATH is what makes the `opencode` lookup fail with ENOENT — agent-stream.js's
  // spawnOpencodeServer spawns the bare name `opencode`, resolved against the child's PATH. node
  // itself is found via process.execPath, an absolute path, so the empty PATH cannot break
  // the spawn of this test's own child process.
  const { result, elapsedMs, cleanup } = await runLane('triage.js', TRIAGE_INPUT, { pathFor: () => '' });
  t.after(cleanup);

  assert.notEqual(result.signal, 'SIGTERM', 'must exit on its own well inside the 15s spawn timeout, not be killed by it');
  assert.ok(elapsedMs < 15_000, `expected a fast exit, took ${elapsedMs}ms`);
  assert.equal(result.status, 1, 'a run that never produced a ruling exits 1');

  const envelope = JSON.parse(result.stdout);
  assert.equal(envelope.is_error, true);
  assert.equal(typeof envelope.error, 'string');
  for (const [k, v] of Object.entries(envelope.usage)) {
    assert.equal(typeof v, 'number', `usage.${k} must be numeric`);
  }

  assert.doesNotMatch(
    result.stderr,
    /still alive/,
    'the unref\'d exit guard is a backstop for a leak — it must never fire on a run that cleaned up after itself',
  );
});

/**
 * Run triage.js against test/fixtures/fake-opencode, which IGNORES SIGTERM — the shape of a live
 * opencode that has just served a session. Before the fix, runAgent's close sent that one SIGTERM
 * and returned; the child and its stdio pipes then held triage.js open until its 5s exit guard
 * fired (`still alive ... PipeWrap, ProcessWrap`). Now the close escalates to SIGKILL and releases
 * the pipes before runAgent returns, so the guard never fires and the child is gone.
 */
function runTriageAgainstStubbornOpencode(reply) {
  return runLane('triage.js', TRIAGE_INPUT, {
    reply,
    env: { FAKE_OPENCODE_IGNORE_SIGTERM: '1' },
    pathFor: (bin) => bin, // only the fake: a real opencode on the host must not be the one started
  });
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

function assertCleanExit({ result, elapsedMs, pids }) {
  assert.notEqual(result.signal, 'SIGTERM', 'must exit on its own well inside the 15s spawn timeout');
  assert.doesNotMatch(result.stderr, /still alive/, 'the exit guard must not fire: the opencode child was stopped');
  // The SIGTERM grace (3s) plus node and fake startup; the guard would add its own 5s on top.
  assert.ok(elapsedMs < 5_000, `expected an exit well before the 5s guard would fire, took ${elapsedMs}ms`);
  assert.equal(pids.length, 1, 'exactly one opencode was started');
  assert.equal(isAlive(pids[0]), false, 'the opencode child is gone, not orphaned');
}

test('triage.js exits clean after a failed run even when opencode ignores SIGTERM', async (t) => {
  const run = await runTriageAgainstStubbornOpencode('');
  t.after(run.cleanup);
  assertCleanExit(run);
  assert.equal(run.result.status, 1, 'a run that never produced a ruling exits 1');
  assert.match(JSON.parse(run.result.stdout).error, /opencode produced no usable reply/);
});

test('triage.js exits clean after a successful run even when opencode ignores SIGTERM', async (t) => {
  const run = await runTriageAgainstStubbornOpencode('{"verdict":"positive"}');
  t.after(run.cleanup);
  assertCleanExit(run);
  assert.equal(run.result.status, 0, run.result.stderr);
  assert.equal(typeof JSON.parse(run.result.stdout).raw, 'string');
});
