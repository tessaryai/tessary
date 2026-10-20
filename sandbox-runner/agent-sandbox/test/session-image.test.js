// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * session.js in a real agent image, under the launcher's container posture (user node, every
 * capability dropped, no-new-privileges), with the real `opencode serve`. Skipped unless
 * SESSION_IMAGE_UNDER_TEST names an image built from agent-sandbox/Dockerfile and Docker is
 * reachable; the working tree's session.js, agent-stream.js and mcp-relay.js are mounted over the
 * image's, so the test checks this tree. Run:
 *
 *   SESSION_IMAGE_UNDER_TEST=<tag> node --test test/session-image.test.js
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const IMAGE = process.env.SESSION_IMAGE_UNDER_TEST;
const HERE = path.join(__dirname, '..');
const SEALED_NODE = '/usr/local/bin/node-sealed';

const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

/** A ready session in a container run with `bin`, holding `token` for one MCP server. */
async function sessionContainer(t, bin, token) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'session-image-'));
  fs.chmodSync(work, 0o777);
  const secret = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(work, 'session.json'), JSON.stringify({
    lane: 'agent_run',
    system_prompt: 'You help.',
    files: { 'a.md': 'hello' },
    model: 'anthropic/claude-sonnet-5',
    mcp_servers: [{ name: 'tessary', url: 'http://127.0.0.1:9/mcp', token }],
    max_tool_calls_per_message: 3,
  }), { mode: 0o666 });
  const name = `session-image-${crypto.randomBytes(4).toString('hex')}`;
  t.after(() => {
    try { docker('rm', '-f', name); } catch { /* already gone */ }
    fs.rmSync(work, { recursive: true, force: true });
  });
  const mount = (file) => ['-v', `${path.join(HERE, file)}:/home/user/${file}:ro`];
  docker(
    'run', '-d', '--name', name, '--user', 'node', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '-e', `SESSION_SECRET=${secret}`, '-e', 'SESSION_PORT=9400', '-e', 'WORK_DIR=/work',
    '-v', `${work}:/work`, ...mount('session.js'), ...mount('agent-stream.js'), ...mount('mcp-relay.js'),
    '-v', `${path.join(__dirname, 'fixtures', 'proc-scan')}:/tmp/proc-scan:ro`,
    IMAGE, bin, 'session.js', '/work/session.json',
  );
  const deadline = Date.now() + 60_000;
  for (;;) {
    let health = '';
    try {
      health = docker('exec', name, 'wget', '-qO-', '--header', `Authorization: Bearer ${secret}`, 'http://127.0.0.1:9400/healthz');
    } catch { /* not listening yet */ }
    if (health.includes('"ready"')) return name;
    const running = docker('inspect', '-f', '{{.State.Running}}', name).trim() === 'true';
    assert.ok(running && Date.now() < deadline, `session not ready (running=${running}): ${docker('logs', name)}`);
    await new Promise((r) => setTimeout(r, 500));
  }
}

function scan(name, needle) {
  return JSON.parse(docker('exec', '--user', 'node', name, 'node', '/tmp/proc-scan', '1', needle));
}

test('the agent cannot read the MCP token or the secret out of the session script (a token read through /proc/1/mem, or the secret through /proc/1/environ)', { skip: !IMAGE && 'SESSION_IMAGE_UNDER_TEST is not set', timeout: 180_000 }, async (t) => {
  // The token is joined at run time, so it is in no source text the scan could find instead.
  const token = ['tsy', 'a', crypto.randomBytes(12).toString('hex')].join('_');

  const control = await sessionContainer(t, 'node', token);
  assert.equal(scan(control, token).found, 'mem', 'the scan finds the token in a script run from the plain node');
  assert.equal(scan(control, 'SESSION_SECRET=').found, 'environ', 'and the secret in its environment');

  const sealed = await sessionContainer(t, SEALED_NODE, token);
  assert.deepEqual(scan(sealed, token), { found: null, errors: { environ: 'EACCES', maps: 'EACCES', mem: 'EACCES' } });
});
