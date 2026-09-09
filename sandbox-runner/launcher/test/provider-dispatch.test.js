// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Provider-dispatch coverage for the launcher's FULL REMOVAL of the deployment-env-var
 * credential path. This file used to test `requestProvider` (an optional per-request field
 * layered on top of a deployment-wide AGENT_PROVIDER default) — that default is gone entirely, so
 * every function here now takes a required `credential` object (the org's own ProviderCredential
 * row, decrypted by the backend) as its only source of provider identity and secrets. The sibling
 * file provider-dispatch-deployment-mode.test.js, which existed only to cover the interaction
 * between an explicit BEDROCK selection and the AGENT_PROVIDER knob, is deleted outright: there is
 * no more knob for a selection to be hijacked by.
 */
process.env.SANDBOX_BACKEND = 'local'; // keep require() side-effect free (no docker fail-fast, no listen)
// None of these should be read by the launcher any more — set to loud canaries so a regression
// that reaches back into process.env for a provider secret fails LOUDLY, not silently.
process.env.OPENAI_API_KEY = 'LEAKED-env-openai';
process.env.GEMINI_API_KEY = 'LEAKED-env-gemini';
process.env.GLM_API_KEY = 'LEAKED-env-glm';
process.env.GROK_API_KEY = 'LEAKED-env-grok';
process.env.CUSTOM_OPENAI_BASE_URL = 'https://LEAKED-env.example/v1';
process.env.CUSTOM_OPENAI_API_KEY = 'LEAKED-env-custom';
process.env.AWS_ACCESS_KEY_ID = 'LEAKED-env-akid';
process.env.AWS_SECRET_ACCESS_KEY = 'LEAKED-env-secret';
delete process.env.AGENT_PROVIDER; // the knob this whole file used to gate on no longer exists at all

const test = require('node:test');
const assert = require('node:assert/strict');
const { toProviderModel, providerConfig, agentEnvs } = require('../server.js');

const BEDROCK_CRED = { provider: 'BEDROCK', aws_region: 'us-east-1', aws_access_key: 'canary-akid', aws_secret_key: 'canary-secret' };
const MANTLE_CRED = { provider: 'BEDROCK_MANTLE', aws_region: 'us-east-1', aws_access_key: 'canary-akid', aws_secret_key: 'canary-secret' };
const GEMINI_CRED = { provider: 'GEMINI', api_key: 'canary-gemini' };
const GLM_CRED = { provider: 'GLM', api_key: 'canary-glm' };
const GROK_CRED = { provider: 'GROK', api_key: 'canary-grok' };
const CUSTOM_CRED = { provider: 'CUSTOM', api_key: 'canary-custom', base_url: 'https://my-endpoint.example/v1' };
const OPENAI_CRED = { provider: 'OPENAI', api_key: 'canary-openai' };

// ---- toProviderModel: qualifying a bare model id with the right OpenCode provider ----

test('toProviderModel: an id that already names a provider passes through unchanged, regardless of credential', () => {
  assert.equal(toProviderModel('anthropic/claude-sonnet-4-6', GEMINI_CRED), 'anthropic/claude-sonnet-4-6');
});

test('toProviderModel: BEDROCK/BEDROCK_MANTLE fall through to the id-shape dispatch (no OpenCode provider mapping)', () => {
  assert.equal(toProviderModel('anthropic.claude-sonnet-4-6', BEDROCK_CRED), 'amazon-bedrock/anthropic.claude-sonnet-4-6');
  assert.equal(toProviderModel('openai.gpt-5.6-luna', MANTLE_CRED), 'bedrock-mantle-gpt/openai.gpt-5.6-luna');
});

test('toProviderModel: GEMINI/GLM/GROK/CUSTOM/OPENAI each qualify with their own OpenCode provider name', () => {
  assert.equal(toProviderModel('gemini-2.5-pro', GEMINI_CRED), 'gemini/gemini-2.5-pro');
  assert.equal(toProviderModel('glm-4.6', GLM_CRED), 'glm/glm-4.6');
  assert.equal(toProviderModel('grok-4', GROK_CRED), 'grok/grok-4');
  assert.equal(toProviderModel('my-self-hosted-model', CUSTOM_CRED), 'custom-openai/my-self-hosted-model');
  assert.equal(toProviderModel('gpt-5.5', OPENAI_CRED), 'openai-direct/gpt-5.5');
});

test('toProviderModel: a credential naming a non-Bedrock provider wins even for a Bedrock-shaped model id', () => {
  // Never happens in practice (ProjectModelSettings#resolveAgenticModel only sends a GEMINI/GLM/
  // GROK/CUSTOM credential alongside a bare, non-Bedrock-shaped model name) — this pins that the
  // credential is authoritative, not a fallback, so a future caller cannot be silently overridden
  // by id shape.
  assert.equal(toProviderModel('openai.gpt-5.6-luna', GROK_CRED), 'grok/openai.gpt-5.6-luna');
});

// ---- providerConfig: EXACTLY one provider block per credential (no more "always both
// Bedrock endpoints plus an optional merge") ----

test('providerConfig: a BEDROCK credential declares only the bedrock-runtime block, from the credential\'s own region', () => {
  const config = providerConfig(BEDROCK_CRED);
  assert.deepEqual(Object.keys(config.provider), ['amazon-bedrock']);
  assert.deepEqual(config.provider['amazon-bedrock'], { options: { region: 'us-east-1' } });
});

test('providerConfig: a BEDROCK_MANTLE credential declares only the mantle block, from the credential\'s own region', () => {
  const config = providerConfig(MANTLE_CRED);
  assert.deepEqual(Object.keys(config.provider), ['bedrock-mantle-gpt']);
  assert.equal(config.provider['bedrock-mantle-gpt'].options.baseURL, 'https://bedrock-mantle.us-east-1.api.aws/openai/v1');
});

test('providerConfig: a GEMINI credential declares only the gemini block, with NO Bedrock block alongside it', () => {
  const config = providerConfig(GEMINI_CRED);
  assert.deepEqual(Object.keys(config.provider), ['gemini']);
  assert.deepEqual(config.provider.gemini, {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL: 'https://generativelanguage.googleapis.com/v1beta/openai/', apiKey: 'canary-gemini' },
  });
});

test('providerConfig: GLM/GROK/CUSTOM/OPENAI each declare their own block with the right base URL and key, off the credential', () => {
  assert.deepEqual(providerConfig(GLM_CRED).provider.glm, {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL: 'https://open.bigmodel.cn/api/paas/v4', apiKey: 'canary-glm' },
  });
  assert.deepEqual(providerConfig(GROK_CRED).provider.grok, {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL: 'https://api.x.ai/v1', apiKey: 'canary-grok' },
  });
  assert.deepEqual(providerConfig(CUSTOM_CRED).provider['custom-openai'], {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL: 'https://my-endpoint.example/v1', apiKey: 'canary-custom' },
  });
  assert.deepEqual(providerConfig(OPENAI_CRED).provider['openai-direct'], {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL: 'https://api.openai.com/v1', apiKey: 'canary-openai' },
  });
});

// ---- agentEnvs: credential env vars sourced ONLY from the credential object, never from
// process.env — OPENCODE_CONFIG_CONTENT agrees with providerConfig ----

test('agentEnvs: a GEMINI credential forwards GEMINI_API_KEY from the credential, not the LEAKED env canary', () => {
  const envs = agentEnvs(GEMINI_CRED);
  assert.equal(envs.GEMINI_API_KEY, 'canary-gemini', 'must come from the credential object');
  assert.notEqual(envs.GEMINI_API_KEY, 'LEAKED-env-gemini');
  const config = JSON.parse(envs.OPENCODE_CONFIG_CONTENT);
  assert.ok(config.provider.gemini, 'OPENCODE_CONFIG_CONTENT must carry the same provider block agentEnvs was asked to build');
});

test('agentEnvs: a GEMINI credential omits every OTHER OpenAI-compat credential env var', () => {
  const envs = agentEnvs(GEMINI_CRED);
  for (const k of ['OPENAI_API_KEY', 'GLM_API_KEY', 'GROK_API_KEY', 'CUSTOM_OPENAI_API_KEY']) {
    assert.equal(envs[k], undefined, `${k} must not leak into a GEMINI-credentialed run`);
  }
  // And no AWS vars either — a non-Bedrock credential has none to forward.
  assert.equal(envs.AWS_ACCESS_KEY_ID, undefined);
  assert.equal(envs.AWS_SECRET_ACCESS_KEY, undefined);
});

test('agentEnvs: a BEDROCK credential forwards the credential\'s own AWS keys, not the LEAKED env canaries', () => {
  const envs = agentEnvs(BEDROCK_CRED);
  assert.equal(envs.AWS_REGION, 'us-east-1');
  assert.equal(envs.AWS_ACCESS_KEY_ID, 'canary-akid');
  assert.equal(envs.AWS_SECRET_ACCESS_KEY, 'canary-secret');
  assert.notEqual(envs.AWS_ACCESS_KEY_ID, 'LEAKED-env-akid');
  for (const k of ['OPENAI_API_KEY', 'GEMINI_API_KEY', 'GLM_API_KEY', 'GROK_API_KEY', 'CUSTOM_OPENAI_API_KEY']) {
    assert.equal(envs[k], undefined, `${k} must not leak into a BEDROCK-credentialed run`);
  }
});

test('agentEnvs: a BEDROCK credential with unset AWS keys OMITS them entirely, not as empty strings (the IAM-role sandbox-side fix)', () => {
  // The concrete regression carried over from an earlier landing: an explicitly-set-but-empty
  // AWS_ACCESS_KEY_ID would short-circuit the AWS SDK's DefaultCredentialsProvider chain before it
  // ever reaches an instance/task role. An iam_role-mode credential never reaches this launcher in
  // production (see AGENTIC_IAM_ROLE_UNSUPPORTED on the backend), but the omission behavior itself
  // must hold regardless of why the keys are absent.
  const envs = agentEnvs({ provider: 'BEDROCK', aws_region: 'us-east-1' });
  assert.equal('AWS_ACCESS_KEY_ID' in envs, false, 'an unset AWS_ACCESS_KEY_ID must be omitted, not sent blank');
  assert.equal('AWS_SECRET_ACCESS_KEY' in envs, false, 'an unset AWS_SECRET_ACCESS_KEY must be omitted, not sent blank');
  assert.equal(envs.AWS_REGION, 'us-east-1');
});

test('providerConfig: the ANTHROPIC default baseURL carries the /v1 segment', () => {
  // The regression this pins: `@ai-sdk/anthropic` treats baseURL as the full API root and appends
  // only `/messages`, so a bare `https://api.anthropic.com` POSTs to a 404. OpenCode's SERVER mode
  // folds that 404 into an EMPTY assistant turn instead of an error, so the whole run fails as
  // "opencode produced no usable reply" with zero tokens and a perfectly valid key — a failure that
  // reads like a bad credential and is not one. langchain4j on the backend appends the bare path
  // the same way (its own default already carries /v1/), so PlatformCatalog holds the /v1 form too
  // and the two agree literally.
  const cfg = providerConfig({ provider: 'ANTHROPIC', api_key: 'canary-anthropic' }, 'anthropic/claude-sonnet-5');
  assert.equal(cfg.provider.anthropic.options.baseURL, 'https://api.anthropic.com/v1');
  assert.equal(cfg.provider.anthropic.options.apiKey, 'canary-anthropic');
});

test('providerConfig: an explicit ANTHROPIC base_url override still wins over the default', () => {
  const cfg = providerConfig(
    { provider: 'ANTHROPIC', api_key: 'k', base_url: 'https://proxy.internal/v1' },
    'anthropic/claude-sonnet-5',
  );
  assert.equal(cfg.provider.anthropic.options.baseURL, 'https://proxy.internal/v1');
});

// ---- The provider block must DECLARE the run's model (the ProviderModelNotFoundError class) ----
// Six of the eight names in OPENCODE_PROVIDER_NAME are not models.dev provider ids, so OpenCode has
// no catalog to borrow a model list from and `provider/model` fails at SessionPrompt.getModel
// BEFORE any HTTP request — zero turns, zero tokens, surfaced as a bare "Unexpected server error".
// Declaring the one model the run asked for is what makes every OpenAI-compat provider resolvable.

test('providerConfig: an OpenAI-compat block declares the run\'s own model', () => {
  const cfg = providerConfig({ provider: 'OPENAI', api_key: 'k' }, 'openai-direct/gpt-5.5');
  assert.deepEqual(cfg.provider['openai-direct'].models, { 'gpt-5.5': {} });
  assert.equal(cfg.provider['openai-direct'].npm, '@ai-sdk/openai-compatible');
});

test('providerConfig: an OPENROUTER model keeps the slash in its own id', () => {
  // Split on the FIRST slash only, exactly as agent-stream.js's splitModel does: the provider
  // prefix comes off and 'openai/gpt-5.6-terra' stays whole. Splitting on the last slash, or on
  // every slash, would declare a model OpenRouter has never heard of.
  const cfg = providerConfig({ provider: 'OPENROUTER', api_key: 'k' }, 'openrouter/openai/gpt-5.6-terra');
  assert.deepEqual(cfg.provider.openrouter.models, { 'openai/gpt-5.6-terra': {} });
});

test('providerConfig: a CUSTOM block declares its model too — no catalog id can ever exist for it', () => {
  const cfg = providerConfig(
    { provider: 'CUSTOM', api_key: 'k', base_url: 'https://llm.internal/v1' },
    'custom-openai/my-finetune',
  );
  assert.deepEqual(cfg.provider['custom-openai'].models, { 'my-finetune': {} });
  assert.equal(cfg.provider['custom-openai'].options.baseURL, 'https://llm.internal/v1');
});

test('providerConfig: GEMINI keeps its own name rather than the models.dev id `google`', () => {
  // Deliberate, and the one place the rename alternative is actively WRONG: under the id `google`
  // OpenCode's built-in provider plugin wins over the `npm` override and issues the native Gemini
  // wire shape with NO Authorization header. The declared-models route works for every provider,
  // so there is no reason to rename any of them.
  const cfg = providerConfig({ provider: 'GEMINI', api_key: 'k' }, 'gemini/gemini-3.1-pro-preview');
  assert.equal('google' in cfg.provider, false);
  assert.deepEqual(cfg.provider.gemini.models, { 'gemini-3.1-pro-preview': {} });
  assert.equal(cfg.provider.gemini.options.baseURL, 'https://generativelanguage.googleapis.com/v1beta/openai/');
});

test('providerConfig: BEDROCK declares no models block — it is not an OpenAI-compat provider', () => {
  const cfg = providerConfig({ provider: 'BEDROCK', aws_region: 'us-east-1' }, 'amazon-bedrock/anthropic.claude-sonnet-4-6');
  assert.equal('models' in cfg.provider['amazon-bedrock'], false);
});

test('providerConfig: BEDROCK_MANTLE declares its model — `bedrock-mantle-gpt` is not a models.dev id', () => {
  const cfg = providerConfig(
    { provider: 'BEDROCK_MANTLE', aws_region: 'us-east-1', aws_access_key: 'a', aws_secret_key: 's' },
    'bedrock-mantle-gpt/openai.gpt-5.6-terra',
  );
  assert.deepEqual(cfg.provider['bedrock-mantle-gpt'].models, { 'openai.gpt-5.6-terra': {} });
  assert.equal(cfg.provider['bedrock-mantle-gpt'].npm, '@ai-sdk/amazon-bedrock/mantle');
});
