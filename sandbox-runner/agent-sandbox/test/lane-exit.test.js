// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Decision 2, end to end: with `opencode` unreachable, triage.js's own process must exit fast and
 * clean, not hang until the launcher's own deadline. agent-stream.test.js covers the same failure
 * in-process (a mocked createOpencodeServer that throws, and an assertion the relay refuses
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
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

test('triage.js exits fast and clean when opencode is unreachable, instead of hanging', () => {
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lane-exit-test-work-'));
  const inputPath = path.join(workDir, 'input.json');
  fs.writeFileSync(
    inputPath,
    JSON.stringify({
      files: { 'finding.md': 'a finding to rule on' },
      prompt: 'rule on this finding',
      json_schema: null,
      model: 'anthropic/claude-sonnet-5',
      mcp: { url: 'https://tessary.example/mcp', token: 'tsy_a_fake' },
      timeout_ms: 5000,
      system_prompt: 'You are the triage agent.',
    }),
  );

  const start = Date.now();
  const result = spawnSync(
    process.execPath,
    [path.join(__dirname, '..', 'triage.js'), inputPath],
    {
      // An empty PATH is what makes cross-spawn's `opencode` lookup fail with ENOENT — see
      // @opencode-ai/sdk's server.js, which shells out via `cross-spawn(\`opencode\`, ...)`. node
      // itself is found via process.execPath, an absolute path, so the empty PATH cannot break
      // the spawn of this test's own child process.
      env: { WORK_DIR: workDir, PATH: '' },
      timeout: 15_000,
      encoding: 'utf8',
    },
  );
  const elapsedMs = Date.now() - start;

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

  fs.rmSync(workDir, { recursive: true, force: true });
});
