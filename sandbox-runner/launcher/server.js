// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Node launcher sidecar: the backend calls it over HTTP and it runs each agent request in a FRESH
 * sandbox (a hardened sibling Docker container or an E2B microVM), never reused across requests or
 * tenants. A launcher session (POST /sessions) keeps one sandbox for its life, never shared.
 * Every orchestration failure answers 502 whose `detail` is launcher-authored or scrubbed SDK
 * metadata only: never the clone URL, sandbox output, model output or repo content.
 * The request's `credential` (the org's decrypted ProviderCredential) reaches the agent through env
 * vars only; it is never logged or written to input.json. Routes and env vars: sandbox-runner/README.md.
 */
const crypto = require('node:crypto');
const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const { StringDecoder } = require('node:string_decoder');

// Required lazily so the docker backend needs no `e2b` install.
let _Sandbox = null;
function Sandbox() {
  if (!_Sandbox) ({ Sandbox: _Sandbox } = require('e2b'));
  return _Sandbox;
}
let _Secret = null;
function Secret() {
  if (!_Secret) ({ Secret: _Secret } = require('e2b'));
  return _Secret;
}

// An egress credential names a secret in E2B's store that E2B's egress proxy injects outside the
// microVM, so the key never reaches the agent. OpenCode still needs a non-empty key to start.
const EGRESS_PLACEHOLDER_KEY = 'injected-at-egress';
const EGRESS_EXTRA_ALLOW = (process.env.EGRESS_EXTRA_ALLOW || '').split(',').map((h) => h.trim()).filter(Boolean);

const PORT = Number(process.env.PORT || 8080);
const BACKEND = (process.env.SANDBOX_BACKEND || 'docker').toLowerCase();
const SANDBOX_API_KEY = process.env.SANDBOX_API_KEY || '';
const E2B_API_KEY = process.env.E2B_API_KEY;
// A bare template name resolves only for a key of the project that built it, so the default is
// namespaced. Held equal to docker-compose.yml's default by scripts/check-version-consistency.sh.
const ANALYZER_TEMPLATE = process.env.E2B_ANALYZER_TEMPLATE || 'tessary/tessary-agent-sandbox:latest';
const SANDBOX_TIMEOUT_MS = Number(process.env.SANDBOX_TIMEOUT_MS || 60000);
const MAX_BODY_BYTES = 8 * 1024 * 1024;
// The Engine API's log stream has no maxBuffer, so this cap keeps a runaway agent from OOMing us.
const MAX_STDOUT_BYTES = 16 * 1024 * 1024;

const DOCKER_SOCKET_PATH = process.env.DOCKER_SOCKET_PATH || '/var/run/docker.sock';
const AGENT_IMAGE = process.env.AGENT_IMAGE || 'tessaryai/tessary:agent-sandbox-latest';
const SANDBOX_DOCKER_CONCURRENCY = Math.max(1, Number(process.env.SANDBOX_DOCKER_CONCURRENCY || 1));
const SANDBOX_DOCKER_MEMORY_MB = Number(process.env.SANDBOX_DOCKER_MEMORY_MB || 2048);
const SANDBOX_DOCKER_CPUS = Number(process.env.SANDBOX_DOCKER_CPUS || 2);
const SANDBOX_DOCKER_PIDS_LIMIT = Number(process.env.SANDBOX_DOCKER_PIDS_LIMIT || 256);
const DOCKER_SANDBOX_NETWORK = process.env.DOCKER_SANDBOX_NETWORK || 'tessary-sandbox';
// Off by default: a sibling joins this launcher's network and reaches the backend's MCP door by
// service name, which a localhost install with no public origin needs. On, it runs on
// DOCKER_SANDBOX_NETWORK and reaches the backend only through SITE_DOMAIN.
const SANDBOX_NETWORK_ISOLATION = /^(1|true|yes|on)$/i.test(process.env.SANDBOX_NETWORK_ISOLATION || '');
// The daemon resolves a sibling's bind paths on the HOST, where this container's paths mean nothing
// (and an unknown path silently mounts an empty dir). So every sibling mounts the same NAMED volume
// by name with a per-request Subpath.
const LAUNCHER_WORK_DIR = process.env.LAUNCHER_WORK_DIR || '/launcher-work';
const SANDBOX_WORK_VOLUME = process.env.SANDBOX_WORK_VOLUME || '';
const SANDBOX_LABEL = 'tessary.sandbox';
const SANDBOX_LABEL_VALUE = '1';
// Callers send their own timeout_ms (up to 20 minutes), so each container is labeled with it and
// reaped against that times this multiplier; SANDBOX_TIMEOUT_MS only covers an unlabeled container.
const SANDBOX_ORPHAN_GRACE_MULTIPLIER = 2;
const SANDBOX_ORPHAN_MAX_AGE_MS = SANDBOX_TIMEOUT_MS * 4;
const SANDBOX_LABEL_TIMEOUT_MS = 'tessary.sandbox.timeout-ms';
const SANDBOX_LABEL_WORK = 'tessary.sandbox.work';
const WORK_DIR_NAME = /^(run|session)-[A-Za-z0-9]{6}$/;
// A work dir changed this close to boot may belong to a request in flight: mtime can be a second coarse.
const WORK_DIR_BOOT_SLACK_MS = 2_000;
const BOOT_MS = Date.now();

// Number('abc') is NaN and a NaN timer fires at once, so a non-positive-integer value stops the boot.
const badIntEnv = [];
function positiveIntEnv(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isSafeInteger(n) || n <= 0) {
    badIntEnv.push(name);
    return fallback;
  }
  return n;
}
const SANDBOX_SESSION_CAP = positiveIntEnv('SANDBOX_SESSION_CAP', 4);
const SANDBOX_SESSION_BUFFER = positiveIntEnv('SANDBOX_SESSION_BUFFER', 5000);
const SANDBOX_SESSION_MAX_MS = positiveIntEnv('SANDBOX_SESSION_MAX_MS', 7200000);
const SANDBOX_SESSION_PORT = positiveIntEnv('SANDBOX_SESSION_PORT', 9400);
const SESSION_START_MS = 90_000;
const SESSION_POLL_MS = 250;
const SESSION_REQUEST_MS = 10_000;
const SESSION_CLOSE_GRACE_MS = 5_000;
const SESSION_TOMBSTONE_MS = 300_000;
const SESSION_STOP_SECONDS = 5;
const SESSION_RECONNECT_MS = 250;
const SESSION_SCRIPT = 'session.js';
// Execute-only in the Docker image, so session.js is not dumpable and the agent cannot read its MCP
// tokens through /proc. The E2B template cannot give that.
const SESSION_NODE = '/usr/local/bin/node-sealed';
const SANDBOX_LABEL_SESSION = 'tessary.sandbox.session';
const SESSION_TEXT_MAX_BYTES = 1 << 20;
// The E2B sandbox outlives SANDBOX_SESSION_MAX_MS by this much so the launcher always closes first.
const SESSION_E2B_GRACE_MS = 60_000;
const SESSION_E2B_HOME = '/home/user';
const E2B_DEBUG = (process.env.E2B_DEBUG || 'false').toLowerCase() === 'true';
const SESSION_EVENT_TYPES = new Set(['text', 'tool_start', 'tool_end', 'message_end', 'error', 'closed']);

// bedrock-mantle is a different host and SigV4 service from bedrock-runtime, speaks the OpenAI
// Responses API, and is the only route to the GPT-5.6 line; a GPT request on mantle's plain /v1 404s.
const MANTLE_GPT = 'bedrock-mantle-gpt';
const BEDROCK_RUNTIME = 'amazon-bedrock';
const MANTLE_PROJECT_ID = (process.env.MANTLE_PROJECT_ID || '').trim();

// Mirrors backend/llm-runtime/.../PlatformCatalog.java's provider names and defaults.
const OPENAI_COMPAT_MODE = 'openai';
const GEMINI_MODE = 'gemini';
const GLM_MODE = 'glm';
const GROK_MODE = 'grok';
const CUSTOM_OPENAI_MODE = 'custom-openai';
const OPENROUTER_MODE = 'openrouter';
const MOONSHOT_MODE = 'moonshot';
// Anthropic speaks its own wire, so providerConfig gives it a dedicated `@ai-sdk/anthropic` block.
const ANTHROPIC_MODE = 'anthropic';
const OPENCODE_PROVIDER_NAME = {
  [OPENAI_COMPAT_MODE]: 'openai-direct',
  [GEMINI_MODE]: 'gemini',
  [GLM_MODE]: 'glm',
  [GROK_MODE]: 'grok',
  [CUSTOM_OPENAI_MODE]: 'custom-openai',
  [OPENROUTER_MODE]: 'openrouter',
  [MOONSHOT_MODE]: 'moonshot',
  [ANTHROPIC_MODE]: 'anthropic',
};
const REQUEST_PROVIDER_TO_MODE = {
  OPENAI: OPENAI_COMPAT_MODE,
  GEMINI: GEMINI_MODE,
  GLM: GLM_MODE,
  GROK: GROK_MODE,
  CUSTOM: CUSTOM_OPENAI_MODE,
  OPENROUTER: OPENROUTER_MODE,
  MOONSHOT: MOONSHOT_MODE,
  ANTHROPIC: ANTHROPIC_MODE,
};

/** Mirrors PlatformCatalog's defaults. CUSTOM has none: a blank `base_url` on it is a config error. */
function defaultBaseUrlFor(mode) {
  switch (mode) {
    case OPENAI_COMPAT_MODE:
      return 'https://api.openai.com/v1';
    case GEMINI_MODE:
      return 'https://generativelanguage.googleapis.com/v1beta/openai/';
    case GLM_MODE:
      return 'https://open.bigmodel.cn/api/paas/v4';
    case GROK_MODE:
      return 'https://api.x.ai/v1';
    case OPENROUTER_MODE:
      return 'https://openrouter.ai/api/v1';
    case MOONSHOT_MODE:
      return 'https://api.moonshot.ai/v1';
    case ANTHROPIC_MODE:
      // WITH /v1: `@ai-sdk/anthropic` appends only `/messages`, and a 404 there surfaces as an empty
      // reply rather than an error. scripts/check-sandbox-runner-launcher.sh holds this equal to the backend.
      return 'https://api.anthropic.com/v1';
    default:
      return null;
  }
}

function openAiCompatCredentials(mode, credential) {
  if (!OPENCODE_PROVIDER_NAME[mode]) return null;
  const baseURL = (credential.base_url || defaultBaseUrlFor(mode) || '').trim();
  return { baseURL, apiKey: (credential.api_key || '').trim() };
}

// Not cost metadata: the production IAM policy grants mantle inference on a project ARN, so a request
// without it lands in the `default` project and gets a 403.
const MANTLE_PROJECT_HEADER = 'OpenAI-Project';

function openAiCompatProviderBlock(mode, credential, model) {
  const creds = openAiCompatCredentials(mode, credential);
  if (!creds) return null;
  // `models` makes the provider resolvable: a block whose key is not a models.dev provider id has no
  // models of its own, so `provider/model` fails before any request. Renaming to the models.dev ids is
  // wrong for GEMINI: under `google`, OpenCode's built-in plugin wins and sends no Authorization header.
  // OPENAI gets `@ai-sdk/openai` because the compat adapter always sends `max_tokens`, which the GPT-5.x
  // reasoning line rejects (opencode #45223). Every other mode is a compat endpoint.
  const npm = mode === OPENAI_COMPAT_MODE ? '@ai-sdk/openai' : '@ai-sdk/openai-compatible';
  const block = { npm, options: creds };
  if (model) block.models = { [model]: {} };
  return { [OPENCODE_PROVIDER_NAME[mode]]: block };
}

// OpenCode finds models.dev rates only under a models.dev provider id, which most of our blocks are
// not, so without the backend's `model_cost` those runs bill at $0.
const MODEL_COST_FIELDS = ['input', 'output', 'cache_read', 'cache_write'];

function sanitizeModelCost(modelCost) {
  if (!modelCost || typeof modelCost !== 'object') return undefined;
  const out = {};
  for (const k of MODEL_COST_FIELDS) {
    const v = modelCost[k];
    if (typeof v === 'number' && Number.isFinite(v) && v >= 0) out[k] = v;
  }
  return out.input === undefined && out.output === undefined ? undefined : out;
}

function declareModelCost(config, bareModel, modelCost) {
  const cost = sanitizeModelCost(modelCost);
  if (!bareModel || !cost) return;
  for (const block of Object.values(config.provider)) {
    const models = block.models || {};
    block.models = { ...models, [bareModel]: { ...(models[bareModel] || {}), cost } };
  }
}

/** Exactly one provider block per run: the one the request's own credential names. */
function providerConfig(credential, qualifiedModel, modelCost) {
  const config = { provider: {} };
  // First slash only, as agent-stream.js's splitModel does, so an OpenRouter id keeps its own slash.
  const bareModel = typeof qualifiedModel === 'string' ? qualifiedModel.slice(qualifiedModel.indexOf('/') + 1) : '';
  if (credential.provider === 'BEDROCK') {
    config.provider[BEDROCK_RUNTIME] = { options: { region: credential.aws_region || '' } };
  } else if (credential.provider === 'BEDROCK_MANTLE') {
    const mantleOptions = { baseURL: `https://bedrock-mantle.${credential.aws_region || ''}.api.aws/openai/v1` };
    // Blank means the account default project, a working configuration.
    if (MANTLE_PROJECT_ID) mantleOptions.headers = { [MANTLE_PROJECT_HEADER]: MANTLE_PROJECT_ID };
    // 'bedrock-mantle-gpt' is not a models.dev provider id, so it declares its model like the compat
    // blocks; 'amazon-bedrock' is one and needs none.
    const mantleBlock = { npm: '@ai-sdk/amazon-bedrock/mantle', options: mantleOptions };
    if (bareModel) mantleBlock.models = { [bareModel]: {} };
    config.provider[MANTLE_GPT] = mantleBlock;
  } else if (credential.provider === 'ANTHROPIC') {
    config.provider[OPENCODE_PROVIDER_NAME[ANTHROPIC_MODE]] = {
      npm: '@ai-sdk/anthropic',
      options: {
        baseURL: (credential.base_url || defaultBaseUrlFor(ANTHROPIC_MODE) || '').trim(),
        apiKey: credentialKey(credential),
      },
    };
  } else {
    const mode = REQUEST_PROVIDER_TO_MODE[credential.provider];
    const extra = mode && openAiCompatProviderBlock(mode, credential, bareModel);
    if (extra) Object.assign(config.provider, extra);
  }
  declareModelCost(config, bareModel, modelCost);
  if (process.env.OPENCODE_SMALL_MODEL) config.small_model = process.env.OPENCODE_SMALL_MODEL;
  if (credential.egress_secret && qualifiedModel) config.small_model = qualifiedModel;
  return config;
}

// There is no default posture: omitting it is a TypeError, so a future untrusted-content route cannot
// inherit the provider credentials and egress. UNTRUSTED_POSTURE (empty env, no network) has no
// caller today and stays so requirePosture keeps two legal values.
const AGENT_POSTURE = Object.freeze({ kind: 'trusted-agent' });
const UNTRUSTED_POSTURE = Object.freeze({ kind: 'untrusted-content' });
function requirePosture(posture, where) {
  if (posture !== AGENT_POSTURE && posture !== UNTRUSTED_POSTURE) {
    throw new TypeError(`${where}: a sandbox posture is required (AGENT_POSTURE or UNTRUSTED_POSTURE); got ${posture === undefined ? 'undefined' : JSON.stringify(posture)}`);
  }
}
// WORK_DIR is '/work' because that is where the sibling's only mount always lands.
function containerEnvFor(posture, credential, qualifiedModel, modelCost) {
  requirePosture(posture, 'containerEnvFor');
  const envs = posture === AGENT_POSTURE ? agentEnvs(credential, qualifiedModel, modelCost) : {};
  return [...Object.entries(envs).map(([k, v]) => `${k}=${v}`), 'WORK_DIR=/work'];
}

// SigV4 only: an IAM policy granting `bedrock-mantle:CreateInference` does not grant
// `CallWithBearerToken`, so a bearer token would 403 on every mantle model.
// The key rides in both the env var and options.apiKey because @ai-sdk packages differ in which they honour.
function openAiCompatEnvVars(mode, credential) {
  const apiKey = credentialKey(credential);
  switch (mode) {
    case OPENAI_COMPAT_MODE:
      return { OPENAI_API_KEY: apiKey };
    case GEMINI_MODE:
      return { GEMINI_API_KEY: apiKey };
    case GLM_MODE:
      return { GLM_API_KEY: apiKey };
    case GROK_MODE:
      return { GROK_API_KEY: apiKey };
    case CUSTOM_OPENAI_MODE:
      return { CUSTOM_OPENAI_API_KEY: apiKey };
    case OPENROUTER_MODE:
      return { OPENROUTER_API_KEY: apiKey };
    case MOONSHOT_MODE:
      return { MOONSHOT_API_KEY: apiKey };
    case ANTHROPIC_MODE:
      return { ANTHROPIC_API_KEY: apiKey };
  }
}

function credentialKey(credential) {
  return credential.egress_secret ? EGRESS_PLACEHOLDER_KEY : (credential.api_key || '').trim();
}

function hostOf(url) {
  try {
    return url ? new URL(url).hostname : null;
  } catch {
    return null;
  }
}

/**
 * Set only for an egress credential. A rule's host must also be allowed or the provider is
 * unreachable, which is why it leads `allowOut`.
 */
function egressNetwork(credential, urls) {
  if (!credential.egress_secret) return {};
  const modelHost = hostOf((credential.base_url || '').trim() || defaultBaseUrlFor(ANTHROPIC_MODE));
  const hosts = [modelHost, ...urls.map(hostOf), ...EGRESS_EXTRA_ALLOW];
  return {
    network: {
      allowOut: [...new Set(hosts.filter(Boolean))],
      denyOut: ['0.0.0.0/0'],
      rules: { [modelHost]: [{ transform: { headers: { 'x-api-key': Secret().fill(credential.egress_secret) } } }] },
    },
  };
}

/** Env vars and OPENCODE_CONFIG_CONTENT come from one credential so the two always agree. */
function agentEnvs(credential, qualifiedModel, modelCost) {
  const envs = {};
  if (credential.provider === 'BEDROCK' || credential.provider === 'BEDROCK_MANTLE') {
    envs.AWS_REGION = credential.aws_region || '';
    // An explicitly empty AWS_ACCESS_KEY_ID short-circuits the SDK's credential chain, so a blank key is omitted.
    if ((credential.aws_access_key || '').trim()) envs.AWS_ACCESS_KEY_ID = credential.aws_access_key.trim();
    if ((credential.aws_secret_key || '').trim()) envs.AWS_SECRET_ACCESS_KEY = credential.aws_secret_key.trim();
  } else {
    const mode = REQUEST_PROVIDER_TO_MODE[credential.provider];
    if (mode) Object.assign(envs, openAiCompatEnvVars(mode, credential));
  }
  envs.OPENCODE_CONFIG_CONTENT = JSON.stringify(providerConfig(credential, qualifiedModel, modelCost));
  return envs;
}

// The backend sends bare Bedrock ids; OpenCode addresses `provider/model`. An id that already names a
// provider passes through.
function toProviderModel(model, credential) {
  if (!model) return model;
  const mode = credential && REQUEST_PROVIDER_TO_MODE[credential.provider];
  // Before the passthrough: an OpenRouter id carries its own slash, and passing it through would send
  // an OpenRouter key to OpenCode's `openai` provider.
  if (mode === OPENROUTER_MODE) {
    const prefix = OPENCODE_PROVIDER_NAME[OPENROUTER_MODE] + '/';
    return model.startsWith(prefix) ? model : prefix + model;
  }
  if (model.includes('/')) return model;
  if (mode) return OPENCODE_PROVIDER_NAME[mode] + '/' + model;
  return (model.startsWith('openai.') ? MANTLE_GPT : BEDROCK_RUNTIME) + '/' + model;
}

// ---------------------------------------------------------------------------
// Failure diagnostics
//
// HARD RULE: the response body carries launcher-authored strings and SDK/Node error METADATA
// only. Never the clone URL (it embeds a git token), sandbox output, model output or repo content.
// When a field's safety cannot be guaranteed it is omitted, not truncated.

// Duplicate of agent-sandbox/agent-stream.js's scrubber: the launcher image copies only server.js.
// Keep the two in sync.
function scrubToken(s) {
  return String(s)
    .replace(/x-access-token:[^@\s]+@/g, 'x-access-token:***@')
    .replace(/tsy_[a-z]_[A-Za-z0-9_-]+/g, 'tsy_***');
}

const DETAIL_MAX = 200;

// Checked only on the e2b backend: from inside a microVM a loopback callback URL calls the microVM
// itself. 0.0.0.0 and IPv4-mapped IPv6 are loopback too but do not match textually; `u.hostname`
// already normalizes octal and decimal IPv4 forms. An unparseable URL counts as loopback.
const IPV4_MAPPED_LOCALHOST = '[::ffff:7f00:1]';
function pointsAtLocalhost(rawUrl) {
  let u;
  try { u = new URL(rawUrl); } catch { return true; }
  return u.hostname === 'localhost'
    || u.hostname === '127.0.0.1'
    || u.hostname === '0.0.0.0'
    || u.hostname === '[::1]'
    || u.hostname === IPV4_MAPPED_LOCALHOST;
}

function stampFailure(err, fields) {
  if (!err || typeof err !== 'object') return err;
  try { Object.assign(err, fields); } catch { /* non-extensible error object */ }
  return err;
}

function exitCodeOf(err) {
  const c = err && (err.exitCode ?? (err.result && err.result.exitCode));
  return typeof c === 'number' ? c : undefined;
}

// Detected structurally: E2B's CommandExitError's `message` IS the raw sandbox stderr, which can hold
// the clone URL, model output and repo content, so it is never published or pattern-matched.
function carriesProcessOutput(err) {
  return !!err && (err.name === 'CommandExitError'
    || err.stdout !== undefined || err.stderr !== undefined || err.result !== undefined
    || exitCodeOf(err) !== undefined);
}

function namedTimeout(err) {
  if (!err) return false;
  if (err.name === 'TimeoutError' || err.code === 'ETIMEDOUT') return true;
  if (carriesProcessOutput(err)) return false;
  return /timed?\s?out|timeout|deadline exceeded/i.test(String(err.message || ''));
}

// E2B can report a hit deadline as a bare SandboxError with no exit code, so elapsed time is often the
// only signal. `timeout` stays true for a non-zero exit that landed on the deadline.
function classifyFailure(err, elapsedMs, timeoutMs) {
  const atDeadline = Number.isFinite(elapsedMs) && Number.isFinite(timeoutMs) && timeoutMs > 0
    && elapsedMs >= timeoutMs * 0.98;
  const exitCode = exitCodeOf(err);
  let kind;
  if (err && err.launcherKind) kind = err.launcherKind;          // explicitly classified at the throw site
  else if (typeof exitCode === 'number' && exitCode !== 0) kind = 'script_exit';
  else if (namedTimeout(err) || atDeadline) kind = 'timeout';
  else kind = 'orchestration';
  return { kind, timeout: kind === 'timeout' || atDeadline, atDeadline, exitCode };
}

function safeDetail(err, cls, meta) {
  const { elapsed_ms: elapsedMs, timeout_ms: timeoutMs, script } = meta;
  if (cls.kind === 'timeout') {
    if (!Number.isFinite(elapsedMs) || !Number.isFinite(timeoutMs)) return 'timed out before any result';
    return cls.atDeadline
      ? `deadline hit: no result after ${elapsedMs}ms of ${timeoutMs}ms`
      : `timed out after ${elapsedMs}ms (deadline ${timeoutMs}ms)`;
  }
  if (cls.kind === 'script_exit') {
    return `${script || 'command'} exited ${cls.exitCode} (output withheld; see launcher logs)`;
  }
  if (carriesProcessOutput(err)) return 'command failed (output withheld; see launcher logs)';
  return scrubToken(String((err && err.message) || err || 'unknown')).slice(0, DETAIL_MAX);
}

const USAGE_FIELDS = ['input_tokens', 'output_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens', 'cost_usd'];

// A failing lane writes `{is_error:true, error, usage}` to stdout. Only numeric usage fields, allow-listed
// by key and type, may leave: a malformed frame must never let a string reach the response.
function usageFromFailureStdout(stdout) {
  if (!stdout) return undefined;
  let parsed;
  try {
    parsed = JSON.parse(String(stdout));
  } catch {
    return undefined;
  }
  if (!parsed || typeof parsed !== 'object' || parsed.is_error !== true) return undefined;
  return numericUsageOnly(parsed.usage);
}

function numericUsageOnly(u) {
  if (!u || typeof u !== 'object') return undefined;
  const out = {};
  for (const k of USAGE_FIELDS) {
    if (typeof u[k] === 'number' && Number.isFinite(u[k])) out[k] = u[k];
  }
  return Object.keys(out).length ? out : undefined;
}

function buildErrorBody(err) {
  const meta = (err && err.launcherMeta) || {};
  const cls = classifyFailure(err, meta.elapsed_ms, meta.timeout_ms);
  const body = {
    error: 'sandbox orchestration failed', // unchanged, so existing backend matching still works
    kind: cls.kind,
    timeout: cls.timeout,
    error_class: String((err && err.name) || 'Error').slice(0, 64),
    detail: safeDetail(err, cls, meta),
  };
  if (typeof cls.exitCode === 'number') body.exit_code = cls.exitCode;
  if (meta.script) body.script = meta.script;             // a filename this file chose, never input
  if (Number.isFinite(meta.elapsed_ms)) body.elapsed_ms = meta.elapsed_ms;
  if (Number.isFinite(meta.timeout_ms)) body.timeout_ms = meta.timeout_ms;
  if (meta.sandbox_id) body.sandbox_id = String(meta.sandbox_id).slice(0, 64);
  const usage = numericUsageOnly(err && err.usage);
  if (usage) body.usage = usage;
  return body;
}

// JSON.parse's SyntaxError quotes its input, which is raw agent stdout, so it never reaches the response.
function parseScriptOutput(scriptName, stdout) {
  const text = typeof stdout === 'string' ? stdout : String(stdout ?? '');
  try {
    return JSON.parse(text);
  } catch {
    const bytes = Buffer.byteLength(text);
    console.error(`${scriptName}: unparseable stdout (${bytes} bytes)`);
    if (text) console.error('--- unparseable stdout ---\n' + scrubToken(text).slice(0, 2000));
    const e = new Error(`${scriptName} returned unparseable output (${bytes} bytes)`);
    e.launcherKind = 'bad_output';
    throw e;
  }
}

// ---------------------------------------------------------------------------
// Docker backend: raw Engine API over the mounted socket, no docker CLI and no SDK dependency.

function dockerRequest(method, urlPath, { body, stream } = {}) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
    const headers = {};
    if (data) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = data.length;
    }
    const req = http.request({ socketPath: DOCKER_SOCKET_PATH, path: urlPath, method, headers }, (res) => {
      if (stream) { resolve(res); return; }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ statusCode: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
      res.on('error', reject);
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// Never the daemon's own message, which can echo request content such as the bind path.
async function dockerJson(method, urlPath, body) {
  const res = await dockerRequest(method, urlPath, { body });
  if (res.statusCode < 200 || res.statusCode >= 300) {
    throw stampFailure(new Error(`docker ${method} ${urlPath} -> ${res.statusCode}`), { launcherKind: 'orchestration' });
  }
  return res.text ? JSON.parse(res.text) : {};
}

// The colon check is anchored after the last '/' so a registry host:port is not a tag separator.
function splitImageRef(ref) {
  const lastSlash = ref.lastIndexOf('/');
  const lastColon = ref.lastIndexOf(':');
  if (lastColon > lastSlash) return [ref.slice(0, lastColon), ref.slice(lastColon + 1)];
  return [ref, 'latest'];
}

let agentImageReady = false;

// POST /containers/create does not auto-pull, and compose never pulls AGENT_IMAGE (it is only an env
// var), so a fresh install would 502 with "No such image" without this.
async function ensureAgentImage() {
  if (agentImageReady) return;
  const check = await dockerRequest('GET', `/images/${encodeURIComponent(AGENT_IMAGE)}/json`);
  if (check.statusCode >= 200 && check.statusCode < 300) {
    agentImageReady = true;
    return;
  }
  console.error(`docker backend: AGENT_IMAGE ${AGENT_IMAGE} not found locally, pulling...`);
  const [repo, tag] = splitImageRef(AGENT_IMAGE);
  const qs = `fromImage=${encodeURIComponent(repo)}&tag=${encodeURIComponent(tag)}`;
  const pullRes = await dockerRequest('POST', `/images/create?${qs}`, { stream: true });
  // A pull can fail mid-stream as an `{"error"}` frame while the status stays 200; checking the status
  // alone would cache agentImageReady forever.
  let pullError = null;
  let buffered = '';
  await new Promise((resolve, reject) => {
    pullRes.on('data', (chunk) => {
      buffered += chunk.toString();
      let newlineIdx;
      while ((newlineIdx = buffered.indexOf('\n')) !== -1) {
        const line = buffered.slice(0, newlineIdx).trim();
        buffered = buffered.slice(newlineIdx + 1);
        if (!line) continue;
        try {
          const frame = JSON.parse(line);
          if (frame && frame.error && !pullError) pullError = frame.error;
        } catch { /* a non-JSON or partial line; the pull itself isn't invalidated by this */ }
      }
    });
    pullRes.on('end', resolve);
    pullRes.on('error', reject);
  });
  if (pullRes.statusCode < 200 || pullRes.statusCode >= 300 || pullError) {
    throw stampFailure(
      new Error(`docker image pull ${AGENT_IMAGE} -> ${pullRes.statusCode}${pullError ? `: ${pullError}` : ''}`),
      { launcherKind: 'orchestration' },
    );
  }
  agentImageReady = true;
}

// Multiplexed log stream: an 8-byte header (byte 0 the stream, bytes 4-7 the big-endian length), then
// the payload.
function createDockerLogDemuxer(onStdout, onStderr) {
  let buf = Buffer.alloc(0);
  return function feed(chunk) {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    while (buf.length >= 8) {
      const streamType = buf.readUInt8(0);
      const len = buf.readUInt32BE(4);
      if (buf.length < 8 + len) break;
      const payload = buf.subarray(8, 8 + len);
      if (streamType === 2) onStderr(payload); else onStdout(payload);
      buf = buf.subarray(8 + len);
    }
  };
}

class Semaphore {
  constructor(max) {
    this.max = max;
    this.active = 0;
    this.queue = [];
  }
  run(fn) {
    return new Promise((resolve, reject) => {
      const task = () => {
        this.active++;
        fn().then(
          (v) => { this.active--; this._drain(); resolve(v); },
          (e) => { this.active--; this._drain(); reject(e); },
        );
      };
      if (this.active < this.max) task();
      else this.queue.push(task);
    });
  }
  _drain() {
    if (this.queue.length && this.active < this.max) this.queue.shift()();
  }
}
const dockerSemaphore = new Semaphore(SANDBOX_DOCKER_CONCURRENCY);

// With isolation off, this is the launcher's own network, read from the daemon because compose
// prefixes the name with the project. A failed lookup falls back to the isolated bridge: an agent that
// cannot reach the MCP door fails one run clearly, a wrong NetworkMode fails every run.
let sandboxNetworkPromise = null;
async function sandboxNetwork() {
  if (SANDBOX_NETWORK_ISOLATION) return DOCKER_SANDBOX_NETWORK;
  if (sandboxNetworkPromise === null) {
    sandboxNetworkPromise = (async () => {
      try {
        const self = await dockerJson('GET', `/containers/${process.env.HOSTNAME}/json`);
        const names = Object.keys((self && self.NetworkSettings && self.NetworkSettings.Networks) || {});
        if (names.length > 0) return names[0];
        console.error('warning: launcher is on no network; falling back to the isolated sandbox bridge');
      } catch (e) {
        console.error(`warning: could not read the launcher's own network (${e && e.message}); falling back to the isolated sandbox bridge`);
      }
      return DOCKER_SANDBOX_NETWORK;
    })();
  }
  return sandboxNetworkPromise;
}

// A no-op unless isolation is on, so installs that never opt in get no empty network.
async function ensureSandboxNetwork() {
  if (!SANDBOX_NETWORK_ISOLATION) return;
  try {
    await dockerJson('GET', `/networks/${DOCKER_SANDBOX_NETWORK}`);
    return;
  } catch { /* not found (or a transient error) — try to create it below */ }
  try {
    await dockerJson('POST', '/networks/create', { Name: DOCKER_SANDBOX_NETWORK, Driver: 'bridge' });
    console.log(`docker backend: created sandbox network '${DOCKER_SANDBOX_NETWORK}'`);
  } catch (e) {
    console.error(`warning: could not ensure sandbox network '${DOCKER_SANDBOX_NETWORK}': ${e && e.message}`);
  }
}

// With isolation on, the launcher joins the sandbox bridge so it can dial sessions there. Best effort:
// a failure costs session starts, never the boot.
async function joinSandboxNetwork() {
  if (!SANDBOX_NETWORK_ISOLATION) return;
  try {
    const self = await dockerJson('GET', `/containers/${process.env.HOSTNAME}/json`);
    const networks = (self && self.NetworkSettings && self.NetworkSettings.Networks) || {};
    if (Object.prototype.hasOwnProperty.call(networks, DOCKER_SANDBOX_NETWORK)) return;
    await dockerJson('POST', `/networks/${DOCKER_SANDBOX_NETWORK}/connect`, { Container: process.env.HOSTNAME });
    console.log(`docker backend: joined sandbox network '${DOCKER_SANDBOX_NETWORK}'`);
  } catch (e) {
    console.error(`warning: could not join sandbox network '${DOCKER_SANDBOX_NETWORK}': ${e && e.message}`);
  }
}

// For the crash/redeploy case only: every normal run removes its own container in `finally`.
async function reapOrphanSandboxContainers() {
  let list;
  try {
    const filters = encodeURIComponent(JSON.stringify({ label: [`${SANDBOX_LABEL}=${SANDBOX_LABEL_VALUE}`] }));
    list = await dockerJson('GET', `/containers/json?all=true&filters=${filters}`);
  } catch (e) {
    console.error(`warning: could not list sandbox containers for startup reconciliation: ${e && e.message}`);
    return;
  }
  const now = Date.now();
  const reapedWork = new Set();
  const keptWork = new Set();
  for (const c of list) {
    const createdMs = (c.Created || 0) * 1000; // Engine API reports `Created` as unix SECONDS
    const labels = c.Labels || {};
    const labeledTimeoutMs = Number(labels[SANDBOX_LABEL_TIMEOUT_MS]);
    const maxAgeMs = Number.isFinite(labeledTimeoutMs) && labeledTimeoutMs > 0
      ? labeledTimeoutMs * SANDBOX_ORPHAN_GRACE_MULTIPLIER
      : SANDBOX_ORPHAN_MAX_AGE_MS;
    // The session registry lives in memory, so after a boot no caller can reach a session container.
    const isSession = Boolean(labels[SANDBOX_LABEL_SESSION]);
    const work = labels[SANDBOX_LABEL_WORK];
    if (!isSession && now - createdMs < maxAgeMs) { // still plausibly a live run
      if (work) keptWork.add(work);
      continue;
    }
    if (work) reapedWork.add(work);
    console.error(`docker backend: reaping orphaned sandbox container ${c.Id} (created ${new Date(createdMs).toISOString()}, maxAgeMs=${maxAgeMs})`);
    try { await dockerJson('POST', `/containers/${c.Id}/stop?t=1`); } catch { /* best effort */ }
    try { await dockerJson('DELETE', `/containers/${c.Id}?force=true`); } catch { /* best effort */ }
  }
  removeOrphanWorkDirs(reapedWork, keptWork);
}

// A work dir can hold MCP tokens and results, and only its own run removes it, so after a restart the
// reaper removes reaped containers' dirs and every pre-boot dir no kept container uses.
function removeOrphanWorkDirs(reapedWork, keptWork) {
  let entries;
  try {
    entries = fs.readdirSync(LAUNCHER_WORK_DIR, { withFileTypes: true });
  } catch {
    return;
  }
  for (const d of entries) {
    if (!d.isDirectory() || !WORK_DIR_NAME.test(d.name) || keptWork.has(d.name)) continue;
    const dir = path.join(LAUNCHER_WORK_DIR, d.name);
    if (!reapedWork.has(d.name)) {
      let changedMs;
      try { changedMs = fs.statSync(dir).mtimeMs; } catch { continue; }
      if (changedMs >= BOOT_MS - WORK_DIR_BOOT_SLACK_MS) continue;
    }
    try {
      fs.rmSync(dir, { recursive: true, force: true });
      console.error(`docker backend: removed orphaned work dir ${d.name}`);
    } catch (e) {
      console.error(`warning: could not remove orphaned work dir ${d.name}: ${(e && e.code) || 'error'}`);
    }
  }
}

function runScriptInDocker(scriptName, payload, timeoutMs, posture, credential) {
  requirePosture(posture, 'runScriptInDocker');
  return dockerSemaphore.run(() => runScriptInDockerInner(scriptName, payload, timeoutMs, posture, credential));
}

// The create body of every sibling agent container, one-shot or session, so the hardening is one
// copy. The caller names the command, env, extra labels, work subdirectory and network.
function agentContainerBody({ cmd, env, labels, workSubpath, networkMode }) {
  return {
    Image: AGENT_IMAGE,
    Cmd: cmd,
    WorkingDir: '/home/user',
    Env: env,
    Labels: { [SANDBOX_LABEL]: SANDBOX_LABEL_VALUE, [SANDBOX_LABEL_WORK]: workSubpath, ...labels },
    HostConfig: {
      // The only mount: the shared volume by name plus this request's subdirectory, never a host path.
      Mounts: [
        {
          Type: 'volume',
          Source: SANDBOX_WORK_VOLUME,
          Target: '/work',
          ReadOnly: false,
          VolumeOptions: { Subpath: workSubpath },
        },
      ],
      // AutoRemove never fires on a launcher crash; the owner's explicit remove plus the boot reaper is the guarantee.
      AutoRemove: false,
      CapDrop: ['ALL'],
      SecurityOpt: ['no-new-privileges'],
      Memory: SANDBOX_DOCKER_MEMORY_MB * 1024 * 1024,
      NanoCpus: Math.round(SANDBOX_DOCKER_CPUS * 1e9),
      PidsLimit: SANDBOX_DOCKER_PIDS_LIMIT,
      // Never host networking, and never `none` for the agent: it needs egress for the clone and the provider.
      NetworkMode: networkMode,
    },
  };
}

async function runScriptInDockerInner(scriptName, payload, timeoutMs, posture, credential) {
  const startedAt = Date.now();
  const withMeta = (e) => stampFailure(e, { launcherMeta: { script: scriptName, elapsed_ms: Date.now() - startedAt, timeout_ms: timeoutMs } });

  let workDir;
  const cleanupDir = () => { try { if (workDir) fs.rmSync(workDir, { recursive: true, force: true }); } catch { /* best effort */ } };
  try {
    fs.mkdirSync(LAUNCHER_WORK_DIR, { recursive: true });
    workDir = fs.mkdtempSync(path.join(LAUNCHER_WORK_DIR, 'run-'));
    fs.writeFileSync(path.join(workDir, 'input.json'), JSON.stringify(payload));
  } catch (e) {
    cleanupDir();
    throw withMeta(e);
  }
  const workSubpath = path.basename(workDir);

  const envList = containerEnvFor(posture, credential, payload.model, payload.model_cost);

  let containerId;
  try {
    await ensureAgentImage();
    const created = await dockerJson('POST', '/containers/create', agentContainerBody({
      cmd: ['node', scriptName, '/work/input.json'],
      env: envList,
      labels: { 'tessary.sandbox.script': scriptName, [SANDBOX_LABEL_TIMEOUT_MS]: String(timeoutMs) },
      workSubpath,
      networkMode: posture === AGENT_POSTURE ? await sandboxNetwork() : 'none',
    }));
    containerId = created.Id;
  } catch (e) {
    cleanupDir();
    throw withMeta(e);
  }

  let stdout = '';
  let stdoutBytes = 0;
  let stderr = '';
  let overflowed = false;
  let killedForTimeout = false;
  let timer;
  const killContainer = () => dockerJson('POST', `/containers/${containerId}/kill`).catch(() => { /* best effort */ });

  try {
    await dockerJson('POST', `/containers/${containerId}/start`);

    const logsRes = await dockerRequest('GET', `/containers/${containerId}/logs?follow=1&stdout=1&stderr=1`, { stream: true });
    const feed = createDockerLogDemuxer(
      (chunk) => {
        stdoutBytes += chunk.length;
        if (stdoutBytes > MAX_STDOUT_BYTES) {
          if (!overflowed) { overflowed = true; killContainer(); }
          return;
        }
        stdout += chunk.toString();
      },
      (chunk) => { stderr += chunk.toString(); },
    );
    logsRes.on('data', feed);
    const logsEnded = new Promise((resolve) => {
      logsRes.once('end', resolve);
      logsRes.once('error', resolve);
    });

    const waitPromise = dockerJson('POST', `/containers/${containerId}/wait`);
    timer = setTimeout(() => { killedForTimeout = true; killContainer(); }, timeoutMs);
    const result = await waitPromise;
    clearTimeout(timer);
    // `wait` resolving does not mean the demuxer has seen every byte yet.
    await Promise.race([logsEnded, new Promise((r) => setTimeout(r, 500))]);

    const elapsedMs = Date.now() - startedAt;
    if (overflowed) {
      throw new Error(`${scriptName} stdout exceeded ${MAX_STDOUT_BYTES} bytes`);
    }
    if (killedForTimeout) {
      const e = new Error(`${scriptName} timed out after ${elapsedMs}ms`);
      e.launcherKind = 'timeout';
      throw e;
    }
    const exitCode = result && result.StatusCode;
    if (exitCode !== 0) {
      console.error(`${scriptName} exit ${exitCode} after ${elapsedMs}ms of ${timeoutMs}ms (container ${containerId})`);
      if (stderr) console.error('--- container stderr ---\n' + scrubToken(stderr).slice(-4000));
      if (stdout) console.error('--- container stdout ---\n' + scrubToken(stdout).slice(0, 2000));
      const e = new Error(`${scriptName} exit ${exitCode}`);
      e.exitCode = exitCode;
      const usage = usageFromFailureStdout(stdout);
      if (usage) e.usage = usage;
      throw e;
    }
    return parseScriptOutput(scriptName, stdout);
  } catch (e) {
    if (timer) clearTimeout(timer);
    throw withMeta(e);
  } finally {
    cleanupDir();
    try { await dockerJson('DELETE', `/containers/${containerId}?force=true`); } catch { /* best effort */ }
  }
}

// A missing or malformed credential is the caller's bad_request here, rather than a blank key failing
// deep inside the agent run as an opaque auth error.
const KNOWN_CREDENTIAL_PROVIDERS = new Set(['BEDROCK', 'BEDROCK_MANTLE', ...Object.keys(REQUEST_PROVIDER_TO_MODE)]);
function requireCredential(credential, scriptName) {
  const bad = (why) => {
    const e = new Error(`${scriptName}: missing or invalid credential (${why})`);
    e.launcherKind = 'bad_request';
    throw e;
  };
  if (!credential || typeof credential !== 'object') bad('no credential object on the request');
  if (!KNOWN_CREDENTIAL_PROVIDERS.has(credential.provider)) bad(`unknown provider '${credential.provider}'`);
  if (credential.egress_secret !== undefined) {
    if (credential.provider !== 'ANTHROPIC') bad('egress_secret is only supported on an ANTHROPIC credential');
    if (!(credential.egress_secret || '').trim()) bad('egress_secret is blank');
    return;
  }
  if (credential.provider === 'BEDROCK' || credential.provider === 'BEDROCK_MANTLE') {
    if (!(credential.aws_region || '').trim()) bad('BEDROCK/BEDROCK_MANTLE credential is missing aws_region');
    if (!(credential.aws_access_key || '').trim() || !(credential.aws_secret_key || '').trim()) {
      bad('BEDROCK/BEDROCK_MANTLE credential is missing aws_access_key/aws_secret_key — an iam_role-mode'
        + ' credential must never reach this launcher (see AGENTIC_IAM_ROLE_UNSUPPORTED on the backend)');
    }
  } else if (!(credential.api_key || '').trim()) {
    bad(`${credential.provider} credential is missing api_key`);
  }
  // A blank base_url would reach the SDK as '' and throw ERR_INVALID_URL inside the agent run.
  if (credential.provider === 'CUSTOM' && !(credential.base_url || '').trim()) {
    bad('CUSTOM credential is missing base_url — there is no default endpoint to assume');
  }
}

// A sandbox already gone, or at its endAt, hit the deadline rather than failing in the script.
async function logSandboxDiagnostics(sandboxId) {
  try {
    const info = await Sandbox().getInfo(sandboxId, { apiKey: E2B_API_KEY });
    const at = (d) => (d instanceof Date ? d.toISOString() : String(d));
    console.error(`sandbox ${sandboxId} post-mortem: state=${info.state} started=${at(info.startedAt)} end=${at(info.endAt)}`);
  } catch (e) {
    console.error(`sandbox ${sandboxId} post-mortem unavailable: ${(e && e.name) || 'Error'}`);
  }
}

async function runAgenticScript(scriptName, rawPayload) {
  const timeoutMs = Number(rawPayload.timeout_ms || SANDBOX_TIMEOUT_MS);
  // `credential` never rejoins `payload`, which is written to input.json on the sandbox's disk; it
  // reaches the agent through env vars only.
  const { credential, ...rest } = rawPayload;
  requireCredential(credential, scriptName);
  const payload = { ...rest, model: toProviderModel(rest.model, credential) };
  console.log(`${scriptName}: model ${rest.model} -> ${payload.model} (provider ${credential.provider})`);
  if (credential.egress_secret && BACKEND !== 'e2b') {
    const e = new Error(`${scriptName}: an egress_secret credential needs SANDBOX_BACKEND=e2b (got ${BACKEND})`);
    e.launcherKind = 'bad_request';
    throw e;
  }
  if (BACKEND === 'docker') return runScriptInDocker(scriptName, payload, timeoutMs, AGENT_POSTURE, credential);
  // A microVM cannot reach the host's localhost, so reject that callback before spending a sandbox.
  const mcpUrl = payload.mcp && payload.mcp.url;
  if (!mcpUrl || pointsAtLocalhost(mcpUrl)) {
    const e = new Error(`${scriptName}: mcp.url is missing or unreachable from an E2B microVM `
      + `(got ${mcpUrl ? JSON.stringify(mcpUrl) : 'unset'}) — set a publicly reachable `
      + 'tessary.rca.agentic.mcp-base-url / tessary.classifier.triage-mcp-base-url, or switch '
      + 'SANDBOX_BACKEND to docker for development');
    e.launcherKind = 'bad_request';
    throw e;
  }
  const startedAt = Date.now();
  const meta = { script: scriptName, timeout_ms: timeoutMs };
  const fail = (e) => stampFailure(e, { launcherMeta: { ...meta, elapsed_ms: Date.now() - startedAt } });
  let sbx;
  try {
    sbx = await Sandbox().create(ANALYZER_TEMPLATE, { apiKey: E2B_API_KEY, timeoutMs, ...egressNetwork(credential, [payload.mcp && payload.mcp.url, payload.clone_url]) });
  } catch (e) {
    console.error(`${scriptName}: sandbox create failed after ${Date.now() - startedAt}ms: ${(e && e.name) || 'Error'}: ${scrubToken(String((e && e.message) || e)).slice(0, 500)}`);
    throw fail(e);
  }
  const sandboxId = sbx.sandboxId;
  meta.sandbox_id = sandboxId;
  console.log(`${scriptName}: sandbox ${sandboxId} created (timeoutMs=${timeoutMs})`);
  // The severed-stream SandboxError ("[unknown] terminated") drops the buffered output, so capture it here.
  let outBuf = '';
  let errBuf = '';
  try {
    await sbx.files.write('/home/user/input.json', JSON.stringify(payload));
    let res;
    try {
      res = await sbx.commands.run(`node /home/user/${scriptName} /home/user/input.json`, {
        timeoutMs,
        envs: agentEnvs(credential, payload.model, payload.model_cost),
        onStdout: (d) => { outBuf += d; },
        onStderr: (d) => { errBuf += d; },
      });
    } catch (e) {
      const err = e || {};
      const elapsedMs = Date.now() - startedAt;
      const cls = classifyFailure(err, elapsedMs, timeoutMs);
      const stderr = (err.stderr ?? err.result?.stderr ?? errBuf ?? '').toString();
      const stdout = (err.stdout ?? err.result?.stdout ?? outBuf ?? '').toString();
      console.error(`${scriptName} ${cls.kind} after ${elapsedMs}ms of ${timeoutMs}ms (sandbox ${sandboxId}): ${err.name || 'Error'}: ${scrubToken(String(err.message || '')).slice(0, 500)}`);
      if (stderr) console.error('--- sandbox stderr ---\n' + scrubToken(stderr).slice(-4000));
      if (stdout) console.error('--- sandbox stdout ---\n' + scrubToken(stdout).slice(0, 2000));
      await logSandboxDiagnostics(sandboxId);
      const usage = usageFromFailureStdout(stdout);
      if (usage && e && typeof e === 'object') e.usage = usage;
      throw e;
    }
    return parseScriptOutput(scriptName, res.stdout);
  } catch (e) {
    throw fail(e);
  } finally {
    try { await sbx.kill(); } catch { /* best effort */ }
  }
}

// payload.clone_url is optional: an evidence-only project still gets its investigation.
function runRca(payload) {
  return runAgenticScript('rca.js', payload);
}

// The only agentic route with no clone: whether a claim about production traffic is true is not
// something a repository can settle, so this route must never grow a clone_url.
function runTriage(payload) {
  return runAgenticScript('triage.js', payload);
}

function runAgentRun(payload) {
  return runAgenticScript('run.js', payload);
}

// ---------------------------------------------------------------------------
// Launcher sessions: one long-lived sandbox per session running session.js. A backend is one driver
// (start/stop, see sessionDriver). The registry lives in memory only, so after a restart every
// session is unknown (404).

const SESSION_UNKNOWN = { error: 'not found', kind: 'unknown_session' };
const sessions = new Map();

function sessionFailure(message, launcherKind) {
  return stampFailure(new Error(message), { launcherKind });
}

function liveSessionCount() {
  let n = 0;
  for (const s of sessions.values()) if (s.state !== 'closed') n++;
  return n;
}

function newSessionRecord({ lane, idleTimeoutMs, createdAt }) {
  return {
    id: crypto.randomUUID(),
    lane,
    secret: crypto.randomBytes(32).toString('hex'),
    state: 'starting',
    handle: null,
    address: null,
    seq: 0,
    buffer: [],
    scriptLines: 0,
    subscribers: new Set(),
    replyOpen: false,
    sending: false,
    messageEnds: 0,
    closedSent: false,
    ended: false,
    idleTimeoutMs,
    idleTimer: null,
    lifeTimer: null,
    createdAt,
    closing: null,
    exited: false,
    eventsReq: null,
    readerDone: Promise.resolve(),
  };
}

// session.js's own checks, repeated so a bad session.json spends no sandbox; the two images share no code.
const SESSION_SERVER_NAME = /^[a-z][a-z0-9-]{0,31}$/;
const SESSION_MAX_TOOL_CALLS = 1000;

// Only these five fields reach session.json; the credential reaches the sandbox through its env alone.
function parseSessionStart(rawBody) {
  const body = rawBody && typeof rawBody === 'object' ? rawBody : {};
  const bad = (why) => { throw sessionFailure(`${SESSION_SCRIPT}: ${why}`, 'bad_request'); };
  if (typeof body.lane !== 'string' || !body.lane) bad('lane must be a non-empty string');
  requireCredential(body.credential, SESSION_SCRIPT);
  if (typeof body.model !== 'string' || !body.model) bad('model must be a non-empty string');
  if (!Number.isSafeInteger(body.idle_timeout_ms) || body.idle_timeout_ms <= 0) bad('idle_timeout_ms must be a positive integer');
  if (body.credential.egress_secret && BACKEND !== 'e2b') bad(`an egress_secret credential needs SANDBOX_BACKEND=e2b (got ${BACKEND})`);
  if (typeof body.system_prompt !== 'string' || !body.system_prompt) bad('system_prompt must be a non-empty string');
  const files = body.files === undefined ? {} : body.files;
  if (!files || typeof files !== 'object' || Array.isArray(files) || Object.values(files).some((c) => typeof c !== 'string')) {
    bad('files must map relative paths to strings');
  }
  if (!Array.isArray(body.mcp_servers)) bad('mcp_servers must be an array');
  const names = new Set();
  body.mcp_servers.forEach((server, i) => {
    if (!server || typeof server.name !== 'string' || !SESSION_SERVER_NAME.test(server.name)) {
      bad(`each mcp_servers name must match ${SESSION_SERVER_NAME.source}`);
    }
    if (names.has(server.name)) bad(`mcp_servers name repeated: ${server.name}`);
    if (BACKEND !== 'docker' && (typeof server.url !== 'string' || pointsAtLocalhost(server.url))) {
      bad(`mcp_servers[${i}].url is missing or unreachable from an E2B microVM`);
    }
    if (typeof server.url !== 'string' || typeof server.token !== 'string') bad(`mcp_servers ${server.name} needs a url and a token`);
    names.add(server.name);
  });
  const maxToolCalls = body.max_tool_calls_per_message;
  if (!Number.isInteger(maxToolCalls) || maxToolCalls < 1 || maxToolCalls > SESSION_MAX_TOOL_CALLS) {
    bad(`max_tool_calls_per_message must be an integer from 1 to ${SESSION_MAX_TOOL_CALLS}`);
  }
  const model = toProviderModel(body.model, body.credential);
  return {
    lane: body.lane,
    credential: body.credential,
    rawModel: body.model,
    model,
    modelCost: body.model_cost,
    idleTimeoutMs: body.idle_timeout_ms,
    sessionFile: {
      system_prompt: body.system_prompt,
      files,
      model,
      mcp_servers: body.mcp_servers,
      max_tool_calls_per_message: body.max_tool_calls_per_message,
    },
  };
}

function sseFrame(event) {
  return `id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`;
}

// Exactly one `closed` per session and always the last event.
function emitSessionEvent(s, type, data, fromScript) {
  if (s.closedSent) return;
  const event = { seq: ++s.seq, type, data };
  s.buffer.push(event);
  if (s.buffer.length > SANDBOX_SESSION_BUFFER) s.buffer.shift();
  const frame = sseFrame(event);
  for (const res of s.subscribers) res.write(frame);
  if (type === 'message_end') {
    s.replyOpen = false;
    s.messageEnds++;
    armIdle(s);
  }
  if (type === 'closed') {
    s.closedSent = true;
    if (fromScript) closeSession(s, data && data.reason);
  }
}

function e2bSessionAddress(host, debug) {
  return `${debug ? 'http' : 'https'}://${host}`;
}

function sessionTransport(address) {
  return new URL(address).protocol === 'https:' ? https : http;
}

function sessionCall(s, method, urlPath, body) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
    const headers = { Authorization: `Bearer ${s.secret}` };
    if (data) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = data.length;
    }
    const req = sessionTransport(s.address).request(`${s.address}${urlPath}`, { method, headers, timeout: SESSION_REQUEST_MS }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        let parsed = null;
        try { parsed = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { /* no JSON body */ }
        resolve({ status: res.statusCode, body: parsed });
      });
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(sessionFailure(`${SESSION_SCRIPT} did not answer ${method} ${urlPath} in ${SESSION_REQUEST_MS}ms`, 'orchestration')));
    req.on('error', reject);
    req.end(data);
  });
}

function readSessionEventsOnce(s) {
  return new Promise((resolve) => {
    const req = sessionTransport(s.address).request(`${s.address}/events?from=${s.scriptLines}`, { method: 'GET', headers: { Authorization: `Bearer ${s.secret}` } }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        resolve();
        return;
      }
      // One decoder for the whole response, so a multi-byte character split across reads is not mangled.
      const decoder = new StringDecoder('utf8');
      let buffered = '';
      res.on('data', (chunk) => {
        buffered += decoder.write(chunk);
        let cut;
        while ((cut = buffered.indexOf('\n')) !== -1) {
          const line = buffered.slice(0, cut);
          buffered = buffered.slice(cut + 1);
          s.scriptLines++;
          let event = null;
          try { event = JSON.parse(line); } catch { /* counted, dropped below */ }
          const type = event && typeof event.type === 'string' ? event.type : 'unparseable';
          if (!SESSION_EVENT_TYPES.has(type)) {
            console.error(`session ${s.id}: dropped event of type ${type.slice(0, 40)}`);
            continue;
          }
          emitSessionEvent(s, type, event.data, true);
        }
      });
      res.on('end', resolve);
      res.on('error', resolve);
      res.on('aborted', resolve);
    });
    req.on('error', resolve);
    s.eventsReq = req;
    req.end();
  });
}

async function readSessionEvents(s) {
  for (;;) {
    await readSessionEventsOnce(s);
    if (s.ended || s.exited) return;
    await new Promise((r) => setTimeout(r, SESSION_RECONNECT_MS));
    if (s.ended || s.exited) return;
  }
}

function armIdle(s) {
  clearIdle(s);
  if (s.state !== 'live' || s.replyOpen || s.sending) return;
  s.idleTimer = setTimeout(() => closeSession(s, 'idle'), s.idleTimeoutMs);
}

function clearIdle(s) {
  if (s.idleTimer) clearTimeout(s.idleTimer);
  s.idleTimer = null;
}

function waitUntil(condition, ms) {
  return new Promise((resolve) => {
    const deadline = Date.now() + ms;
    const tick = () => {
      if (condition() || Date.now() >= deadline) resolve();
      else setTimeout(tick, 20);
    };
    tick();
  });
}

async function waitSessionReady(s, startedAt) {
  for (;;) {
    if (s.exited) throw sessionFailure(`${SESSION_SCRIPT} did not become ready (sandbox exited)`, 'orchestration');
    if (Date.now() - startedAt >= SESSION_START_MS) {
      throw sessionFailure(`${SESSION_SCRIPT} did not become ready in ${SESSION_START_MS}ms`, 'timeout');
    }
    let answer = null;
    try {
      answer = await sessionCall(s, 'GET', '/healthz');
    } catch { /* refused or reset: the server is not up yet */ }
    if (answer && answer.status === 200 && answer.body && answer.body.state === 'ready') return;
    if (answer && answer.status === 503 && answer.body && answer.body.state === 'failed') {
      throw sessionFailure(`${SESSION_SCRIPT} did not become ready (failed)`, 'orchestration');
    }
    await new Promise((r) => setTimeout(r, SESSION_POLL_MS));
  }
}

function closeSession(s, reason) {
  if (!s.closing) {
    s.closing = (async () => {
      s.state = 'closing';
      clearIdle(s);
      if (s.lifeTimer) clearTimeout(s.lifeTimer);
      await waitUntil(() => !s.sending || s.exited, SESSION_REQUEST_MS + SESSION_CLOSE_GRACE_MS);
      // Abort a running reply first so its message_end, and the spend it carries, arrives before closed.
      if (s.replyOpen && !s.exited) {
        try { await sessionCall(s, 'POST', '/abort'); } catch { /* the script is gone; nothing to wait for */ }
        await waitUntil(() => !s.replyOpen || s.exited || s.closedSent, SESSION_CLOSE_GRACE_MS);
      }
      emitSessionEvent(s, 'closed', { reason });
      s.ended = true;
      for (const res of s.subscribers) res.end();
      s.subscribers.clear();
      if (s.eventsReq) s.eventsReq.destroy();
      await sessionDriver.stop(s);
      s.state = 'closed';
      console.log(`session ${s.id}: closed reason=${reason} events=${s.seq} age_ms=${Date.now() - s.createdAt}`);
      setTimeout(() => sessions.delete(s.id), SESSION_TOMBSTONE_MS).unref();
    })().catch((e) => console.error(`session ${s.id}: close failed: ${(e && e.name) || 'Error'}`));
  }
  return s.closing;
}

function logStartFailure(s) {
  if (!s.address) return Promise.resolve();
  return new Promise((resolve) => {
    const req = sessionTransport(s.address).request(`${s.address}/events?from=0`, { method: 'GET', headers: { Authorization: `Bearer ${s.secret}` } }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { text += c; });
      res.on('end', done);
      res.on('error', done);
      res.on('aborted', done);
      function done() {
        for (const line of text.split('\n').filter(Boolean)) {
          console.error(`session ${s.id}: start event ${scrubToken(line).slice(0, 500)}`);
        }
        resolve();
      }
    });
    req.setTimeout(2000, () => req.destroy());
    req.on('error', () => resolve());
    req.end();
  });
}

async function abandonStart(s) {
  s.state = 'closed';
  sessions.delete(s.id);
  if (s.eventsReq) s.eventsReq.destroy();
  await sessionDriver.stop(s);
}

// At SANDBOX_DOCKER_CONCURRENCY=1 one open session would freeze every one-shot run, so sessions take
// no dockerSemaphore slot; SANDBOX_SESSION_CAP bounds them instead.
const dockerSessionDriver = {
  async start(s, { sessionFile, credential, model, modelCost }) {
    fs.mkdirSync(LAUNCHER_WORK_DIR, { recursive: true });
    const workDir = fs.mkdtempSync(path.join(LAUNCHER_WORK_DIR, 'session-'));
    s.handle = { containerId: null, workDir };
    await ensureAgentImage();
    const networkMode = await sandboxNetwork();
    fs.writeFileSync(path.join(workDir, 'session.json'), JSON.stringify(sessionFile), { mode: 0o600 });
    const created = await dockerJson('POST', '/containers/create', agentContainerBody({
      cmd: [SESSION_NODE, SESSION_SCRIPT, '/work/session.json'],
      env: [...containerEnvFor(AGENT_POSTURE, credential, model, modelCost), `SESSION_SECRET=${s.secret}`, `SESSION_PORT=${SANDBOX_SESSION_PORT}`],
      labels: {
        'tessary.sandbox.script': SESSION_SCRIPT,
        [SANDBOX_LABEL_TIMEOUT_MS]: String(SANDBOX_SESSION_MAX_MS),
        [SANDBOX_LABEL_SESSION]: s.id,
      },
      workSubpath: path.basename(workDir),
      networkMode,
    }));
    s.handle.containerId = created.Id;
    await dockerJson('POST', `/containers/${created.Id}/start`);
    const exited = dockerJson('POST', `/containers/${created.Id}/wait`).then(
      (r) => ({ code: r && r.StatusCode }),
      () => ({ code: null }),
    );
    // By network name: only the address on the network the launcher shares is reachable from here.
    const info = await dockerJson('GET', `/containers/${created.Id}/json`);
    const networks = (info && info.NetworkSettings && info.NetworkSettings.Networks) || {};
    const ip = networks[networkMode] && networks[networkMode].IPAddress;
    if (!ip) throw sessionFailure(`${SESSION_SCRIPT} has no address on ${networkMode}`, 'orchestration');
    return { handle: s.handle, address: `http://${ip}:${SANDBOX_SESSION_PORT}`, exited };
  },
  async stop(s) {
    const h = s.handle;
    if (!h) return;
    if (h.containerId) {
      try { await dockerJson('POST', `/containers/${h.containerId}/stop?t=${SESSION_STOP_SECONDS}`); } catch { /* best effort */ }
      try { await dockerJson('DELETE', `/containers/${h.containerId}?force=true`); } catch { /* best effort */ }
    }
    try { fs.rmSync(h.workDir, { recursive: true, force: true }); } catch { /* best effort */ }
  },
};

// command.wait() also rejects when only its event stream to E2B broke while session.js runs on. Only
// an exit code is an exit; any other rejection reattaches to the same pid.
async function followSessionCommand(s, sbx, command) {
  let handle = command;
  for (;;) {
    try {
      const r = await handle.wait();
      return { code: r.exitCode, stderr: r.stderr };
    } catch (e) {
      const code = exitCodeOf(e);
      if (code !== undefined) return { code, stderr: (e && e.stderr) || '' };
      if (s.state === 'closing' || s.state === 'closed') return { code: null, stderr: '' };
      console.error(`session ${s.id}: lost the ${SESSION_SCRIPT} command stream (${(e && e.name) || 'Error'}); reattaching to pid ${command.pid}`);
    }
    await new Promise((r) => setTimeout(r, SESSION_RECONNECT_MS));
    try {
      handle = await sbx.commands.connect(command.pid, { timeoutMs: 0 });
    } catch (e) {
      console.error(`session ${s.id}: ${SESSION_SCRIPT} pid ${command.pid} not found on reattach (${(e && e.name) || 'Error'})`);
      return { code: null, stderr: '' };
    }
  }
}

const e2bSessionDriver = {
  async start(s, { sessionFile, credential, model, modelCost }) {
    const mcpUrls = Array.isArray(sessionFile.mcp_servers) ? sessionFile.mcp_servers.map((m) => m && m.url) : [];
    const sbx = await Sandbox().create(ANALYZER_TEMPLATE, {
      apiKey: E2B_API_KEY,
      timeoutMs: SANDBOX_SESSION_MAX_MS + SESSION_E2B_GRACE_MS,
      metadata: { [SANDBOX_LABEL_SESSION]: s.id },
      ...egressNetwork(credential, mcpUrls),
    });
    s.handle = { sbx, sandboxId: sbx.sandboxId };
    const file = `${SESSION_E2B_HOME}/session.json`;
    await sbx.files.write(file, JSON.stringify(sessionFile));
    const command = await sbx.commands.run(`${SESSION_NODE} ${SESSION_E2B_HOME}/${SESSION_SCRIPT} ${file}`, {
      background: true,
      timeoutMs: 0,
      envs: { ...agentEnvs(credential, model, modelCost), SESSION_SECRET: s.secret, SESSION_PORT: String(SANDBOX_SESSION_PORT) },
    });
    const exited = followSessionCommand(s, sbx, command)
      .then(({ code, stderr }) => {
        const tail = stderr ? ` stderr: ${scrubToken(stderr).slice(-500)}` : '';
        console.error(`session ${s.id}: ${SESSION_SCRIPT} ended code=${code}${tail}`);
        return { code };
      });
    return { handle: s.handle, address: e2bSessionAddress(sbx.getHost(SANDBOX_SESSION_PORT), E2B_DEBUG), exited };
  },
  async stop(s) {
    if (s.handle && s.handle.sbx) {
      try { await s.handle.sbx.kill(); } catch { /* best effort */ }
    }
  },
};

const sessionDriver = BACKEND === 'docker' ? dockerSessionDriver : e2bSessionDriver;

async function onSandboxExit(s) {
  s.exited = true;
  if (s.state !== 'live') return;
  await Promise.race([s.readerDone, new Promise((r) => setTimeout(r, 1000))]);
  closeSession(s, 'failed');
}

async function startSession(body) {
  const arrivedAt = Date.now();
  const p = parseSessionStart(body);
  // Count and insert stay synchronous so two starts cannot both take the last slot.
  if (liveSessionCount() >= SANDBOX_SESSION_CAP) {
    return { status: 429, body: { error: 'sandbox session capacity reached', kind: 'capacity' } };
  }
  const s = newSessionRecord({ lane: p.lane, idleTimeoutMs: p.idleTimeoutMs, createdAt: arrivedAt });
  sessions.set(s.id, s);
  console.log(`session ${s.id}: model ${p.rawModel} -> ${p.model} (provider ${p.credential.provider}) lane ${p.lane}`);
  try {
    const started = await sessionDriver.start(s, {
      sessionFile: p.sessionFile,
      credential: p.credential,
      model: p.model,
      modelCost: p.modelCost,
    });
    s.handle = started.handle;
    s.address = started.address;
    started.exited.then(() => onSandboxExit(s));
    await waitSessionReady(s, arrivedAt);
  } catch (e) {
    await logStartFailure(s);
    await abandonStart(s);
    const sandbox = s.handle && s.handle.sandboxId ? { sandbox_id: s.handle.sandboxId } : {};
    throw stampFailure(e, { launcherMeta: { script: SESSION_SCRIPT, elapsed_ms: Date.now() - arrivedAt, timeout_ms: SESSION_START_MS, ...sandbox } });
  }
  s.state = 'live';
  s.readerDone = readSessionEvents(s);
  armIdle(s);
  const lifeLeft = SANDBOX_SESSION_MAX_MS - (Date.now() - s.createdAt);
  if (lifeLeft > 0) s.lifeTimer = setTimeout(() => closeSession(s, 'idle'), lifeLeft);
  else setImmediate(() => closeSession(s, 'idle'));
  const h = s.handle || {};
  const where = h.containerId ? ` container=${h.containerId.slice(0, 12)}` : h.sandboxId ? ` sandbox=${h.sandboxId}` : '';
  console.log(`session ${s.id}: live${where} ready_ms=${Date.now() - arrivedAt}`);
  return { status: 201, body: { session_id: s.id } };
}

async function sendSessionMessage(s, req) {
  const body = await readBody(req);
  const text = body && body.text;
  if (typeof text !== 'string' || !text || Buffer.byteLength(JSON.stringify({ text })) > SESSION_TEXT_MAX_BYTES) {
    throw sessionFailure(`${SESSION_SCRIPT}: text must be a non-empty string of at most 1 MiB`, 'bad_request');
  }
  if (s.state !== 'live') return { status: 404, body: SESSION_UNKNOWN };
  if (s.replyOpen || s.sending) return { status: 409, body: { error: 'session busy', kind: 'busy' } };
  s.sending = true;
  clearIdle(s);
  const endsBefore = s.messageEnds;
  const sentAt = Date.now();
  let answer;
  try {
    try {
      answer = await sessionCall(s, 'POST', '/message', { text });
    } catch {
      closeSession(s, 'failed');
      return { status: 404, body: SESSION_UNKNOWN };
    }
    if (answer.status === 202) {
      if (s.closedSent) return { status: 404, body: SESSION_UNKNOWN };
      // The reply's events travel on another connection and can beat this answer here.
      s.replyOpen = s.messageEnds === endsBefore;
      console.log(`session ${s.id}: message accepted bytes=${Buffer.byteLength(text)} ms=${Date.now() - sentAt} reply_open=${s.replyOpen}`);
      return { status: 202 };
    }
    if (answer.status === 409) return { status: 409, body: { error: 'session busy', kind: 'busy' } };
    if (answer.status === 503) {
      closeSession(s, 'failed');
      return { status: 404, body: SESSION_UNKNOWN };
    }
    throw sessionFailure(`${SESSION_SCRIPT} refused the message (HTTP ${answer.status})`, 'orchestration');
  } finally {
    s.sending = false;
    if (!answer || answer.status !== 503) armIdle(s);
  }
}

async function abortSessionReply(s) {
  let answer;
  try {
    answer = await sessionCall(s, 'POST', '/abort');
  } catch {
    closeSession(s, 'failed');
    return { status: 404, body: SESSION_UNKNOWN };
  }
  if (answer.status === 204) return { status: 204 };
  throw sessionFailure(`${SESSION_SCRIPT} refused the abort (HTTP ${answer.status})`, 'orchestration');
}

function streamSessionEvents(s, rawAfter, res) {
  const after = rawAfter === null ? 0 : /^\d+$/.test(rawAfter) ? Number(rawAfter) : NaN;
  if (!Number.isSafeInteger(after)) throw sessionFailure(`${SESSION_SCRIPT}: after must be a non-negative integer`, 'bad_request');
  const oldest = s.buffer.length > 0 ? s.buffer[0].seq : s.seq + 1;
  if (after + 1 < oldest) return send(res, 410, { error: 'events no longer held', kind: 'gone' });
  if (after > s.seq) throw sessionFailure(`${SESSION_SCRIPT}: after is past the last event`, 'bad_request');
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
  res.flushHeaders();
  for (const event of s.buffer) if (event.seq > after) res.write(sseFrame(event));
  if (s.ended) {
    res.end();
    return;
  }
  s.subscribers.add(res);
  res.on('close', () => s.subscribers.delete(res));
}

function matchSessionRoute(method, rawUrl) {
  let url;
  try {
    url = new URL(rawUrl, 'http://launcher');
  } catch {
    return null;
  }
  const parts = url.pathname.split('/');
  if (parts[1] !== 'sessions') return null;
  const id = parts[2];
  if (parts.length === 2 && method === 'POST') return { op: 'start' };
  if (parts.length === 3 && id && method === 'DELETE') return { op: 'stop', id };
  if (parts.length === 4 && id) {
    if (method === 'POST' && parts[3] === 'messages') return { op: 'message', id };
    if (method === 'POST' && parts[3] === 'abort') return { op: 'abort', id };
    if (method === 'GET' && parts[3] === 'events') return { op: 'events', id, after: url.searchParams.get('after') };
  }
  return 'other';
}

function answer(res, { status, body }) {
  if (body === undefined) {
    res.writeHead(status);
    res.end();
    return;
  }
  send(res, status, body);
}

async function handleSessionRoute(req, res, route) {
  if (route.op === 'start') return answer(res, await startSession(await readBody(req)));
  const s = sessions.get(route.id);
  if (!s) return answer(res, { status: 404, body: SESSION_UNKNOWN });
  if (route.op === 'events') return streamSessionEvents(s, route.after, res);
  if (route.op === 'stop') {
    await closeSession(s, 'stopped');
    return answer(res, { status: 204 });
  }
  if (s.state !== 'live') return answer(res, { status: 404, body: SESSION_UNKNOWN });
  if (route.op === 'message') return answer(res, await sendSessionMessage(s, req));
  return answer(res, await abortSessionReply(s));
}

const AGENTIC_ROUTES = new Map([
  ['/rca', runRca],
  ['/triage', runTriage],
  ['/runs', runAgentRun],
]);

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) { reject(stampFailure(new Error('body too large'), { launcherKind: 'bad_request' })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      // JSON.parse quotes the offending input, so its error is discarded.
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch { reject(stampFailure(new Error('invalid json body'), { launcherKind: 'bad_request' })); }
    });
    req.on('error', reject);
  });
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body);
}

function authorized(req) {
  const auth = req.headers['authorization'] || '';
  return Boolean(SANDBOX_API_KEY) && auth === `Bearer ${SANDBOX_API_KEY}`;
}

function logFailure(urlPath, body) {
  console.error(`${urlPath} failed: kind=${body.kind} timeout=${body.timeout} class=${body.error_class}`
    + (body.script ? ` script=${body.script}` : '')
    + (body.sandbox_id ? ` sandbox=${body.sandbox_id}` : '')
    + (body.elapsed_ms !== undefined ? ` elapsed=${body.elapsed_ms}ms` : '')
    + (body.timeout_ms !== undefined ? ` deadline=${body.timeout_ms}ms` : '')
    + ` detail=${body.detail}`);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/healthz') return send(res, 200, { ok: true });

  const sessionRoute = matchSessionRoute(req.method, req.url);
  if (sessionRoute === 'other') return send(res, 404, { error: 'not found' });
  if (sessionRoute) {
    if (!authorized(req)) return send(res, 401, { error: 'unauthorized' });
    try {
      return await handleSessionRoute(req, res, sessionRoute);
    } catch (e) {
      const body = buildErrorBody(e);
      logFailure(req.url.split('?')[0], body);
      if (res.headersSent) return res.end();
      return send(res, 502, body);
    }
  }

  if (req.method !== 'POST' || !AGENTIC_ROUTES.has(req.url)) {
    return send(res, 404, { error: 'not found' });
  }
  if (!authorized(req)) {
    return send(res, 401, { error: 'unauthorized' });
  }
  try {
    const payload = await readBody(req);
    const out = await AGENTIC_ROUTES.get(req.url)(payload);
    return send(res, 200, out);
  } catch (e) {
    const body = buildErrorBody(e);
    logFailure(req.url, body);
    return send(res, 502, body);
  }
});

if (badIntEnv.length > 0) {
  for (const name of badIntEnv) console.error(`error: ${name} must be a positive integer`);
  process.exit(1);
}

if (BACKEND === 'docker' && !SANDBOX_WORK_VOLUME) {
  console.error('error: SANDBOX_BACKEND=docker requires SANDBOX_WORK_VOLUME (the name of the Docker volume mounted at LAUNCHER_WORK_DIR).');
  console.error('       See docker-compose.yml\'s sandbox-runner service for how the two are wired together.');
  process.exit(1);
}

if (require.main === module && BACKEND === 'docker') {
  ensureSandboxNetwork()
    .then(joinSandboxNetwork)
    .then(reapOrphanSandboxContainers)
    .catch((e) => console.error(`docker backend startup reconciliation failed: ${e && e.message}`));
}

if (require.main === module) server.listen(PORT, () => {
  const detail = BACKEND === 'docker' ? `backend=docker, image=${AGENT_IMAGE}, concurrency=${SANDBOX_DOCKER_CONCURRENCY}`
    : `backend=e2b, template=${ANALYZER_TEMPLATE}`;
  // The bound port: PORT=0 asks the OS for one, and the tests read it from this line.
  console.log(`sandbox-runner launcher listening on :${server.address().port} (${detail})`);
});

module.exports = {
  containerEnvFor,
  AGENT_POSTURE,
  UNTRUSTED_POSTURE,
  toProviderModel,
  providerConfig,
  agentEnvs,
  e2bSessionAddress,
};
