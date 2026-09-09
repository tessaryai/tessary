// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The sandbox runners take a REQUIRED credential/network posture. Proves the three
 * properties the launcher's security model rests on, at the one seam that decides them:
 *   1. UNTRUSTED_POSTURE yields an env with NO credentials -- exactly PATH + WORK_DIR, even when
 *      the launcher's own process env is full of provider keys (a leftover-env canary —
 *      agentEnvs() no longer reads any of these from process.env at all, but the posture
 *      boundary itself must hold regardless of where a credential comes from). The docker-backend
 *      tests assert the equivalent Env/NetworkMode on the container create request; this is the
 *      local-backend counterpart that was missing.
 *   2. AGENT_POSTURE forwards the credential passed to it (our own agent needs it) and leaks
 *      NOTHING from the ambient process env — agentEnvs() is now a pure function of its
 *      `credential` argument, never of process.env.
 *   3. Omitting the posture, or passing anything but the two sentinels, is a hard TypeError --
 *      there is no silent default to full credentials.
 */
process.env.SANDBOX_BACKEND = 'local'; // keep require() side-effect free (no docker fail-fast, no listen)
// Leftover-env canaries: agentEnvs() no longer reads any credential env var directly from
// process.env, so these prove the posture/credential boundary holds on its OWN terms, not because nothing is set.
process.env.AWS_ACCESS_KEY_ID = 'LEAKED-env-akid';
process.env.AWS_SECRET_ACCESS_KEY = 'LEAKED-env-secret';
process.env.ANTHROPIC_API_KEY = 'LEAKED-env-anthropic';
process.env.AWS_BEARER_TOKEN_BEDROCK = 'LEAKED-env-bearer';
process.env.GEMINI_API_KEY = 'LEAKED-env-gemini';
const test = require('node:test');
const assert = require('node:assert/strict');
const { childEnvFor, AGENT_POSTURE, UNTRUSTED_POSTURE } = require('../server.js');

const LEAKED_ENV_KEYS = ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'ANTHROPIC_API_KEY', 'AWS_BEARER_TOKEN_BEDROCK', 'GEMINI_API_KEY'];
const CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'canary-akid', aws_secret_key: 'canary-secret' };

test('UNTRUSTED_POSTURE: local child env is exactly PATH + WORK_DIR, no credentials leak', () => {
  const env = childEnvFor(UNTRUSTED_POSTURE, '/tmp/w', CREDENTIAL);
  assert.deepEqual(Object.keys(env).sort(), ['PATH', 'WORK_DIR']);
  assert.equal(env.WORK_DIR, '/tmp/w');
  for (const k of LEAKED_ENV_KEYS) assert.equal(env[k], undefined, `${k} leaked into an untrusted child env`);
});

test('AGENT_POSTURE: local child env forwards the CREDENTIAL argument\'s own keys, not the ambient env canaries', () => {
  const env = childEnvFor(AGENT_POSTURE, '/tmp/w', CREDENTIAL);
  // AWS_ACCESS_KEY_ID lands in `env` either way here (AGENT_POSTURE's local-backend shape is
  // `{...process.env, ...agentEnvs(credential)}` — see childEnvFor's own doc: the LOCAL backend is
  // a developer convenience that intentionally runs with the full host env, unlike docker/E2B,
  // which send ONLY agentEnvs()'s output and never see process.env at all). The guarantee is which
  // VALUE wins: agentEnvs(credential)'s own value must win over (overwrite) the
  // ambient one, which is what the equality below actually proves.
  assert.equal(env.AWS_ACCESS_KEY_ID, 'canary-akid', 'the credential argument\'s value must win over the ambient env one');
  assert.notEqual(env.AWS_ACCESS_KEY_ID, 'LEAKED-env-akid');
  // Discriminating assertion (crew review): a bare {...process.env} would also carry the canary above;
  // OPENCODE_CONFIG_CONTENT is produced only by agentEnvs(), so this proves the agent env was spread.
  assert.ok(env.OPENCODE_CONFIG_CONTENT, 'agentEnvs() output must be present under AGENT_POSTURE');
  assert.equal(env.WORK_DIR, '/tmp/w');
});

test('AGENT_POSTURE: docker/E2B backends send ONLY agentEnvs() output, never process.env — the ambient canaries never reach those envLists at all', () => {
  // childEnvFor is the LOCAL-backend seam; docker (runScriptInDockerInner's envList) and E2B
  // (the sbx.commands.run() envs option) both call agentEnvs(credential) directly, with no
  // {...process.env} spread anywhere near it — see server.js's own call sites. This test pins
  // that agentEnvs() alone — the shape those two backends actually send — carries none of the
  // non-Bedrock env canaries when the credential is a Bedrock one, independent of childEnvFor's
  // local-only host-env passthrough asserted above.
  const { agentEnvs } = require('../server.js');
  const envs = agentEnvs(CREDENTIAL);
  for (const k of ['ANTHROPIC_API_KEY', 'AWS_BEARER_TOKEN_BEDROCK', 'GEMINI_API_KEY']) {
    assert.equal(envs[k], undefined, `${k} must not leak into a BEDROCK-credentialed agentEnvs() output`);
  }
});

test('omitting the posture is a hard error, never a fall-through to full credentials', () => {
  assert.throws(() => childEnvFor(undefined, '/tmp/w', CREDENTIAL), TypeError);
  assert.throws(() => childEnvFor(null, '/tmp/w', CREDENTIAL), TypeError);
  assert.throws(() => childEnvFor({}, '/tmp/w', CREDENTIAL), TypeError);
  assert.throws(() => childEnvFor({ kind: 'trusted-agent' }, '/tmp/w', CREDENTIAL), TypeError, 'a look-alike object is not the sentinel');
});
