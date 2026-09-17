// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The sandbox runners take a REQUIRED credential/network posture. Proves the three
 * properties the launcher's security model rests on, at the one seam that decides them —
 * containerEnvFor, the `Env` list the docker backend sends POST /containers/create (see its
 * own doc comment in server.js):
 *   1. UNTRUSTED_POSTURE yields an Env list with NO credentials -- exactly WORK_DIR, even when
 *      the launcher's own process env is full of provider keys (a leftover-env canary —
 *      agentEnvs() no longer reads any of these from process.env at all, but the posture
 *      boundary itself must hold regardless of where a credential comes from).
 *   2. AGENT_POSTURE forwards the credential passed to it (our own agent needs it) and leaks
 *      NOTHING from the ambient process env — containerEnvFor never spreads process.env, so a
 *      sibling container sees ONLY what agentEnvs(credential) produced plus WORK_DIR.
 *   3. Omitting the posture, or passing anything but the two sentinels, is a hard TypeError --
 *      there is no silent default to full credentials.
 */
process.env.SANDBOX_BACKEND = 'e2b'; // keep require() side-effect free (no docker fail-fast, no listen)
// Leftover-env canaries: agentEnvs() no longer reads any credential env var directly from
// process.env, so these prove the posture/credential boundary holds on its OWN terms, not because nothing is set.
process.env.AWS_ACCESS_KEY_ID = 'LEAKED-env-akid';
process.env.AWS_SECRET_ACCESS_KEY = 'LEAKED-env-secret';
process.env.ANTHROPIC_API_KEY = 'LEAKED-env-anthropic';
process.env.AWS_BEARER_TOKEN_BEDROCK = 'LEAKED-env-bearer';
process.env.GEMINI_API_KEY = 'LEAKED-env-gemini';
const test = require('node:test');
const assert = require('node:assert/strict');
const { containerEnvFor, agentEnvs, AGENT_POSTURE, UNTRUSTED_POSTURE } = require('../server.js');

const LEAKED_ENV_KEYS = ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'ANTHROPIC_API_KEY', 'AWS_BEARER_TOKEN_BEDROCK', 'GEMINI_API_KEY'];
const CREDENTIAL = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'canary-akid', aws_secret_key: 'canary-secret' };

// `Env=["KEY=VALUE", ...]` entry -> {KEY: "VALUE"}, splitting on the FIRST '=' only (a value may
// legitimately contain one, e.g. OPENCODE_CONFIG_CONTENT's JSON).
function envListToObject(envList) {
  const out = {};
  for (const entry of envList) {
    const i = entry.indexOf('=');
    out[entry.slice(0, i)] = entry.slice(i + 1);
  }
  return out;
}

test('UNTRUSTED_POSTURE: container Env is exactly WORK_DIR, no credentials leak', () => {
  const envList = containerEnvFor(UNTRUSTED_POSTURE, CREDENTIAL);
  assert.deepEqual(envList, ['WORK_DIR=/work']);
  const env = envListToObject(envList);
  for (const k of LEAKED_ENV_KEYS) assert.equal(env[k], undefined, `${k} leaked into an untrusted container env`);
});

test('AGENT_POSTURE: container Env forwards the CREDENTIAL argument\'s own keys, and nothing from the ambient process env', () => {
  const envList = containerEnvFor(AGENT_POSTURE, CREDENTIAL);
  const env = envListToObject(envList);
  // containerEnvFor never spreads process.env (unlike a host-process spawn would) — docker and E2B
  // both send ONLY agentEnvs()'s output, so the ambient canaries above must never appear here.
  for (const k of LEAKED_ENV_KEYS) {
    if (k === 'AWS_ACCESS_KEY_ID' || k === 'AWS_SECRET_ACCESS_KEY') continue; // asserted by value below
    assert.equal(env[k], undefined, `${k} leaked into an agent-posture container env`);
  }
  assert.equal(env.AWS_ACCESS_KEY_ID, 'canary-akid', 'the credential argument\'s value, never the ambient env one');
  assert.equal(env.AWS_SECRET_ACCESS_KEY, 'canary-secret');
  assert.notEqual(env.AWS_ACCESS_KEY_ID, 'LEAKED-env-akid');
  assert.ok(env.OPENCODE_CONFIG_CONTENT, 'agentEnvs() output must be present under AGENT_POSTURE');
  assert.equal(env.WORK_DIR, '/work');
});

test('AGENT_POSTURE: docker/E2B backends send ONLY agentEnvs() output, never process.env — the ambient canaries never reach those envs at all', () => {
  // containerEnvFor (docker's envList) and E2B (the sbx.commands.run() envs option) both call
  // agentEnvs(credential) directly, with no {...process.env} spread anywhere near it — see
  // server.js's own call sites. This test pins that agentEnvs() alone — the shape both backends
  // actually send — carries none of the non-Bedrock env canaries when the credential is a
  // Bedrock one.
  const envs = agentEnvs(CREDENTIAL);
  for (const k of ['ANTHROPIC_API_KEY', 'AWS_BEARER_TOKEN_BEDROCK', 'GEMINI_API_KEY']) {
    assert.equal(envs[k], undefined, `${k} must not leak into a BEDROCK-credentialed agentEnvs() output`);
  }
});

test('omitting the posture is a hard error, never a fall-through to full credentials', () => {
  assert.throws(() => containerEnvFor(undefined, CREDENTIAL), TypeError);
  assert.throws(() => containerEnvFor(null, CREDENTIAL), TypeError);
  assert.throws(() => containerEnvFor({}, CREDENTIAL), TypeError);
  assert.throws(() => containerEnvFor({ kind: 'trusted-agent' }, CREDENTIAL), TypeError, 'a look-alike object is not the sentinel');
});
