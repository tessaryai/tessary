// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Shared OpenCode runner for the agent lanes and session.js. It drives `opencode serve` over the SDK
 * because `opencode run --format json` drops text and step_finish events in containers
 * (anomalyco/opencode#31435). Must be copied beside the lanes in the E2B template (template.ts).
 */
const { execFileSync, spawn } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { Agent, fetch: undiciFetch } = require('undici');
const { startMcpRelay } = require('./mcp-relay');

const TRIAGE_AGENT = 'tessary-triage';

// opencode's own scratch dirs, where its bash tool tells the model it may write; denying them would
// contradict that. Only the run lane under a system prompt and the agent_run and triage sessions.
const OPENCODE_TMP_GLOB = path.join(os.tmpdir(), 'opencode', '*');
const OPENCODE_TOOL_OUTPUT_GLOB = path.join(
  process.env.XDG_DATA_HOME || path.join(os.homedir(), '.local', 'share'),
  'opencode',
  'tool-output',
  '*',
);

/**
 * Headless: every needed tool must be 'allow', since an 'ask' has nobody to answer it. webfetch is off
 * as the cheapest exfiltration channel. opencode takes the last matching rule, so the scratch allows
 * come after `'*': 'deny'`. webfetch/websearch take a bare action; the map form 400s every endpoint.
 */
function basePermission(lanePermission, { scratchDirs = false } = {}) {
  return {
    bash: { '*': 'allow' },
    webfetch: 'deny',
    websearch: 'deny',
    edit: { '*': 'deny' },
    ...lanePermission,
    external_directory: scratchDirs
      ? { '*': 'deny', [OPENCODE_TMP_GLOB]: 'allow', [OPENCODE_TOOL_OUTPUT_GLOB]: 'allow' }
      : { '*': 'deny' },
  };
}

/**
 * A custom primary agent whose `prompt` replaces the provider's default system prompt. On the step that
 * reaches `steps`, OpenCode tells the model to answer in text only.
 */
function customAgent({ prompt, steps }) {
  return {
    mode: 'primary',
    prompt,
    ...(steps !== undefined ? { steps } : {}),
    permission: { task: 'deny', skill: 'deny', todowrite: 'allow' },
  };
}

function writeDossier(root, files) {
  for (const [rel, content] of Object.entries(files || {})) {
    const full = path.resolve(root, rel);
    if (full !== root && !full.startsWith(root + path.sep)) {
      throw new Error('dossier path escapes root: ' + rel);
    }
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
  }
}

/**
 * session.prompt() holds one request open for the whole run and Node's fetch kills it at 300s
 * (the SDK's `req.timeout = false` only works on Bun). Bounded by the deadline, not disabled. Uses
 * undici's own fetch: the built-in one ignores a dispatcher from a different undici major.
 */
function makeFetch(runMs) {
  const dispatcher = new Agent({ headersTimeout: runMs, bodyTimeout: runMs });
  // undici's fetch cannot consume a cross-realm `Request`, which is what the SDK hands its override, so
  // unwrap it. The body is buffered to stay off the half-duplex path.
  return async function boundedFetch(req) {
    const method = req.method || 'GET';
    return undiciFetch(req.url, {
      method,
      headers: Object.fromEntries(req.headers.entries()),
      signal: req.signal,
      body: method === 'GET' || method === 'HEAD' ? undefined : Buffer.from(await req.arrayBuffer()),
      dispatcher,
    });
  };
}

const WORK = process.env.WORK_DIR || '/home/user';
const REPO = `${WORK}/repo`;
const MAX_TURNS = 200;

// Started in WORK, not the clone: OpenCode executes .opencode/ plugins and reads opencode.json and
// AGENTS.md walking up from its start directory, so starting in a customer's clone would hand that repo
// our agent's config and prompt. REPO is a child of WORK, so it stays readable.
const AGENT_CWD = WORK;

// Belt and braces to AGENT_CWD: a future upstream discovery change must not reopen the hole.
const QUARANTINED = ['.opencode', 'opencode.json', 'opencode.jsonc', 'AGENTS.md', 'CLAUDE.md', '.cursor'];

// Clone tokens and platform API keys (tsy_<scope>_<random>, the shape of the ephemeral MCP key).
function scrubToken(s) {
  return String(s)
    .replace(/x-access-token:[^@\s]+@/g, 'x-access-token:***@')
    .replace(/tsy_[a-z]_[A-Za-z0-9_-]+/g, 'tsy_***');
}

// fetch failures bury the real cause (ECONNREFUSED, a 403) in `.cause`, so walk the chain. The SDK wraps
// a 4xx body as a plain-object cause, which String() renders as "[object Object]", so serialise it, capped.
const CAUSE_MAX = 1000;

function describeError(e, depth = 0) {
  if (e == null || depth > 4) return '';
  if (typeof e !== 'object' || (!(e instanceof Error) && e.message === undefined)) {
    let s;
    try {
      s = typeof e === 'string' ? e : JSON.stringify(e);
    } catch {
      s = String(e);
    }
    return scrubToken(String(s === undefined ? e : s)).slice(0, CAUSE_MAX);
  }
  const code = e.code || e.errno || e.syscall;
  const head = scrubToken(String(e.message || e)) + (code ? ` [${code}]` : '');
  const causeStr = e.cause ? describeError(e.cause, depth + 1) : '';
  return causeStr ? `${head} <- caused by: ${causeStr}` : head;
}

const GIT_TIMEOUT_MS = Number(process.env.GIT_TIMEOUT_MS || 180_000);

/**
 * Run git and fail rather than wait for a human: GIT_TERMINAL_PROMPT=0 and empty askpass turn a
 * credential prompt into an exit, an empty credential.helper stops inherited helpers, and a timeout
 * covers network stalls. stderr is scrubbed into the error; args carry the clone token and are never echoed.
 */
function git(args) {
  try {
    return execFileSync('git', ['-c', 'credential.helper=', ...args], {
      stdio: ['ignore', 'pipe', 'pipe'],
      encoding: 'utf8',
      timeout: GIT_TIMEOUT_MS,
      killSignal: 'SIGKILL',
      env: {
        ...process.env,
        GIT_TERMINAL_PROMPT: '0',
        GIT_ASKPASS: '',
        SSH_ASKPASS: '',
        GIT_CONFIG_NOSYSTEM: '1',
      },
    }).trim();
  } catch (e) {
    const verb = args[0];
    if (e && (e.code === 'ETIMEDOUT' || e.signal === 'SIGKILL')) {
      throw new Error(`git ${verb} timed out after ${GIT_TIMEOUT_MS}ms`);
    }
    const why = scrubToken(e && e.stderr ? String(e.stderr) : '')
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean)
      .slice(-2)
      .join('; ')
      .slice(0, 300);
    throw new Error(`git ${verb} failed` + (why ? `: ${why}` : ''));
  }
}

/** The files stay readable as evidence; they just stop sitting where an agent harness looks for instructions. */
function quarantineRepo() {
  const dest = path.join(REPO, 'quarantine');
  for (const name of QUARANTINED) {
    const from = path.join(REPO, name);
    if (!fs.existsSync(from)) continue;
    try {
      fs.mkdirSync(dest, { recursive: true });
      fs.renameSync(from, path.join(dest, name));
    } catch {
    }
  }
}

/** The newest commit on the clone's HEAD committed before `onsetAt`, or "unknown". */
function onsetCommit(onsetAt) {
  if (!onsetAt) return 'unknown';
  try {
    return git(['-C', REPO, 'rev-list', '-1', `--before=${onsetAt}`, 'HEAD']) || 'unknown';
  } catch (e) {
    console.error(`onset commit unresolved: ${describeError(e)}`);
    return 'unknown';
  }
}

/**
 * The posture of each session.js lane. Edit is a blanket allow where allowed, because opencode
 * matches an edit glob against `/` when WORK is not a git repo. rca is read-only, may clone, and
 * runs opencode's default agent when no system prompt is given, as runAgent does without one.
 */
const SESSION_LANES = Object.freeze({
  agent_run: Object.freeze({ edit: { '*': 'allow' }, scratchDirs: true, checksDir: null, clones: false, promptOptional: false }),
  triage: Object.freeze({ edit: { '*': 'allow' }, scratchDirs: true, checksDir: 'checks', clones: false, promptOptional: false }),
  rca: Object.freeze({ edit: { '*': 'deny' }, scratchDirs: false, checksDir: null, clones: true, promptOptional: true }),
});

// --- server lifecycle --------------------------------------------------------
//
// Ready means opencode's own `listening on <url>` line, not an accepting port: a request in the
// ~320ms between the two hangs forever. The spawn is ours, not the SDK's, because its close() is a
// lone SIGTERM that neither waits nor escalates and left the lane's process alive.

const STOP_GRACE_MS = 3_000;
const KILL_WAIT_MS = 1_000;

/**
 * SIGTERM, up to STOP_GRACE_MS, then SIGKILL; the stdio pipes are destroyed either way so nothing of
 * the child holds the event loop. Never rejects.
 */
function stopProcess(proc) {
  return new Promise((resolve) => {
    let timer = null;
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      proc.off('exit', finish);
      for (const stream of [proc.stdin, proc.stdout, proc.stderr]) if (stream) stream.destroy();
      resolve();
    };
    if (proc.pid === undefined || proc.exitCode !== null || proc.signalCode !== null) return finish();
    proc.once('exit', finish);
    proc.kill('SIGTERM');
    timer = setTimeout(() => {
      proc.kill('SIGKILL');
      timer = setTimeout(finish, KILL_WAIT_MS);
    }, STOP_GRACE_MS);
  });
}

/**
 * Resolves `{url, close, exited}` once it announces itself; `exited` also settles after that, so a
 * long-lived caller learns its OpenCode died.
 */
function spawnOpencodeServer({ hostname, port, timeout, config }) {
  const args = ['serve', `--hostname=${hostname}`, `--port=${port}`];
  const proc = spawn('opencode', args, {
    env: { ...process.env, OPENCODE_CONFIG_CONTENT: JSON.stringify(config) },
  });
  const close = () => stopProcess(proc);
  const exited = new Promise((resolve) => proc.once('exit', (code, signal) => resolve({ code, signal })));

  return new Promise((resolve, reject) => {
    let output = '';
    let settled = false;
    const withOutput = (msg) => (output.trim() ? `${msg}\nServer output: ${output}` : msg);
    const fail = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      close().then(() => reject(err));
    };
    const timer = setTimeout(
      () => fail(new Error(withOutput(`Timeout waiting for server to start after ${timeout}ms`))),
      timeout,
    );

    // An unread pipe fills and blocks opencode, so both stay drained for the life of the process.
    proc.stdout.on('data', (chunk) => {
      if (settled) return;
      output += chunk.toString();
      for (const line of output.split('\n')) {
        if (!line.startsWith('opencode server listening')) continue;
        const match = line.match(/on\s+(https?:\/\/[^\s]+)/);
        if (!match) return fail(new Error(`Failed to parse server url from output: ${line}`));
        settled = true;
        clearTimeout(timer);
        return resolve({ url: match[1], close, exited });
      }
    });
    proc.stderr.on('data', (chunk) => {
      if (!settled) output += chunk.toString();
    });
    proc.on('exit', (code) => fail(new Error(withOutput(`Server exited with code ${code}`))));
    proc.on('error', (err) => fail(err));
  });
}

/**
 * Merged, not replaced: overwriting the var drops the launcher's provider block and bedrock-mantle-gpt
 * never registers.
 */
function inheritedConfig() {
  const raw = process.env.OPENCODE_CONFIG_CONTENT;
  if (!raw) return {};
  return JSON.parse(raw);
}

/**
 * No basic auth: loopback in a single-tenant sandbox. opencode ignores `port: 0` and binds 4096, so a
 * free port is chosen here for concurrent local runs.
 */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

function portTaken(port) {
  return new Promise((resolve) => {
    const srv = net.createServer();
    srv.once('error', (e) => resolve(e.code === 'EADDRINUSE'));
    srv.listen(port, '127.0.0.1', () => srv.close(() => resolve(false)));
  });
}

/**
 * freePort releases its port before opencode binds it, and opencode then exits printing only
 * `ServeError`, so a collision is recognised by the port being held after the failed start.
 */
const PORT_ATTEMPTS = 3;

async function startServer(configJson) {
  process.chdir(AGENT_CWD);
  process.env.OPENCODE_DISABLE_AUTOUPDATE = '1';
  process.env.OPENCODE_DISABLE_LSP_DOWNLOAD = '1';
  process.env.OPENCODE_DISABLE_CLAUDE_CODE = '1';
  // Run-scoped keys last so the lane's rules win; read before the spawn overwrites the var.
  const config = { ...inheritedConfig(), ...configJson };
  const startMs = Date.now();
  try {
    for (let attempt = 1; ; attempt++) {
      const port = await freePort();
      try {
        return await spawnOpencodeServer({ hostname: '127.0.0.1', port, timeout: 120_000, config });
      } catch (e) {
        if (attempt >= PORT_ATTEMPTS || !(await portTaken(port))) throw e;
      }
    }
  } catch (e) {
    const hint = /Timeout waiting/.test(String((e && e.message) || '')) && !/Server output:/.test(e.message)
      ? ' — the process was up but never announced itself, and printed nothing'
      : '';
    throw new Error(`opencode server did not start after ${Date.now() - startMs}ms: ${describeError(e)}${hint}`, {
      cause: e,
    });
  }
}

/**
 * The prompt API takes `{providerID, modelID}`; the joined string is a 400 that reads as an empty reply.
 * First slash only: model ids can contain one.
 */
function splitModel(model) {
  const i = typeof model === 'string' ? model.indexOf('/') : -1;
  if (i < 0) return { modelID: String(model || '') };
  return { providerID: model.slice(0, i), modelID: model.slice(i + 1) };
}

/**
 * Asked for in the prompt, not the server's `format`: opencode 1.18.13 accepts `format` and returns an
 * empty reply and a session whose messages then 400. Its documented retry knob is also ignored
 * (anomalyco/opencode#25430). schemaInstruction, extractJson and missingKeys go if that is fixed.
 */
function schemaInstruction(jsonSchema) {
  if (!jsonSchema) return '';
  return (
    '\n\nRespond with ONLY a single JSON object that validates against this JSON Schema. ' +
    'No prose, no markdown fence, no commentary before or after it.\n\n' +
    JSON.stringify(jsonSchema)
  );
}

/**
 * Top-level `required` keys only; the backend re-checks everything. A non-JSON reply is signalled by a
 * null `parsed`, not here.
 */
function missingKeys(parsed, jsonSchema) {
  const required = jsonSchema && Array.isArray(jsonSchema.required) ? jsonSchema.required : [];
  if (!parsed || typeof parsed !== 'object') return required;
  return required.filter((k) => parsed[k] === undefined);
}

/**
 * String-aware because markdown inside a JSON string is full of braces. A span that never balances (a
 * reply truncated at the token limit) yields nothing.
 */
function balancedSpans(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== '{') continue;
    let depth = 0;
    let inStr = false;
    let esc = false;
    for (let j = i; j < text.length; j++) {
      const ch = text[j];
      if (inStr) {
        if (esc) esc = false;
        else if (ch === '\\') esc = true;
        else if (ch === '"') inStr = false;
        continue;
      }
      if (ch === '"') inStr = true;
      else if (ch === '{') depth++;
      else if (ch === '}' && --depth === 0) {
        out.push(text.slice(i, j + 1));
        i = j; // a balanced span cannot contain another top-level candidate
        break;
      }
    }
    if (out.length >= 4) break; // a reply with five candidate objects is not a schema reply
  }
  return out;
}

/** Tolerates a markdown fence and prose on either side, since "ONLY JSON" is an instruction, not a guarantee. */
function extractJson(text) {
  if (!text) return null;
  for (const span of balancedSpans(text)) {
    try {
      const parsed = JSON.parse(span);
      if (parsed && typeof parsed === 'object') return parsed;
    } catch {
    }
  }
  return null;
}

/**
 * A schema miss is discarded by the throw, so log a bounded, scrubbed head and tail of the reply.
 * `output_tokens` at the provider cap is the tell for truncation.
 */
function describeSchemaMiss(text, turns, jsonSchema) {
  const t = String(text || '');
  const last = turns && turns.length ? turns[turns.length - 1] : null;
  const spans = balancedSpans(t);
  const lines = [
    'schema miss: ' +
      `chars=${t.length} braces=${(t.match(/{/g) || []).length} balanced=${spans.length} ` +
      `turns=${turns ? turns.length : 0} last_turn_output_tokens=${last ? last.usage.output_tokens : 0}`,
    'required=' + (jsonSchema.required || []).join(','),
    usageLine(turns),
    '--- reply head ---\n' + scrubToken(t.slice(0, 600)),
  ];
  if (t.length > 900) lines.push('--- reply tail ---\n' + scrubToken(t.slice(-600)));
  return lines.join('\n');
}

/**
 * The one place both the success envelope and a failing lane's stdout envelope compute spend. Every
 * field is a number: server.js's buildErrorBody forwards this shape into its 502 body.
 */
function sumUsage(turns) {
  const z = { input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0, cache_creation_input_tokens: 0, cost_usd: 0 };
  return (turns || []).reduce(
    (a, t) => ({
      input_tokens: a.input_tokens + t.usage.input_tokens,
      output_tokens: a.output_tokens + t.usage.output_tokens,
      cache_read_input_tokens: a.cache_read_input_tokens + t.usage.cache_read_input_tokens,
      cache_creation_input_tokens: a.cache_creation_input_tokens + t.usage.cache_creation_input_tokens,
      cost_usd: a.cost_usd + (t.usage.cost_usd || 0),
    }),
    z,
  );
}

function usageLine(turns) {
  const u = sumUsage(turns);
  return (
    `spend (booked on failure by the caller — see lane.js): in=${u.input_tokens} out=${u.output_tokens} ` +
    `cache_read=${u.cache_read_input_tokens} cache_write=${u.cache_creation_input_tokens} cost_usd=${u.cost_usd} ` +
    `turns=${(turns || []).length}`
  );
}

// Part field names move between OpenCode releases; a rename upstream must degrade telemetry, never
// fail the run.
function partsOf(message) {
  const p = message && (message.parts || message.content);
  return Array.isArray(p) ? p : [];
}

function textOf(parts) {
  return parts
    .filter((c) => c && c.type === 'text' && typeof c.text === 'string')
    .map((c) => c.text)
    .join('\n')
    .trim();
}

function toolCallsOf(parts) {
  return parts
    .filter((c) => c && (c.type === 'tool' || c.type === 'tool_use' || c.type === 'tool-call'))
    .map((c) => {
      const state = c.state || {};
      const args = JSON.stringify(c.input || state.input || {});
      return { id: c.callID || c.id || '', name: c.tool || c.name || '', input: args };
    })
    .filter((c) => c.name);
}

function toolResultsOf(parts) {
  const out = [];
  for (const c of parts) {
    if (!c || (c.type !== 'tool' && c.type !== 'tool_use' && c.type !== 'tool-call')) continue;
    const state = c.state || {};
    const r = state.output ?? state.result ?? c.output;
    if (r === undefined || r === null) continue;
    out.push({ id: c.callID || c.id || '', content: r });
  }
  return out;
}

// `cost_usd` is OpenCode's own figure from the rates the launcher declared; the backend books it as reported.
function usageOf(message) {
  const t = (message && message.tokens) || {};
  const cache = t.cache || {};
  const cost = Number(message && message.cost);
  return {
    input_tokens: Number(t.input || 0),
    output_tokens: Number(t.output || 0),
    cache_read_input_tokens: Number(cache.read || 0),
    cache_creation_input_tokens: Number(cache.write || 0),
    cost_usd: Number.isFinite(cost) && cost > 0 ? cost : 0,
  };
}

/**
 * Bedrock returns these on non-Anthropic families (anomalyco/opencode#31430), and an agent reads it as
 * "done", silently truncating the run, so runAgent retries once.
 */
function isEmptyCompletion(turns) {
  const last = turns[turns.length - 1];
  if (!last) return true;
  return !last.text && last.tool_calls.length === 0 && last.usage.output_tokens === 0;
}

/** `input` is what the call responded to: the prompt for turn 0, the previous turn's tool results after. */
function toTurns(messages, prompt) {
  const turns = [];
  let pendingInput = prompt;
  for (const m of messages) {
    const msg = (m && m.info) || m;
    if (!msg || msg.role !== 'assistant') continue;
    if (turns.length >= MAX_TURNS) break;
    const parts = partsOf(m.parts ? m : msg);
    const results = toolResultsOf(parts);
    turns.push({
      model: msg.modelID || msg.model || '',
      usage: usageOf(msg),
      tools: toolCallsOf(parts).map((c) => c.name),
      tool_calls: toolCallsOf(parts),
      text: textOf(parts),
      input: pendingInput,
      tool_results: results,
      ts: Number(msg.time && msg.time.completed) || Date.now(),
    });
    pendingInput = results.map((r) => r.content).join('\n\n');
  }
  return turns;
}

/**
 * A contract with the backend's AgentSpanTelemetry and the lanes' Java sandboxes. A run with tokens
 * but $0 `cost_usd` had no rate; the backend books it unpriced, not free.
 */
function toEnvelope(turns, structured, text) {
  const usage = sumUsage(turns);
  const env = { num_turns: turns.length, usage, result: text };
  if (structured && typeof structured === 'object') env.structured_output = structured;
  return JSON.stringify(env);
}

function remoteMcp(urls) {
  const mcp = {};
  for (const [name, url] of Object.entries(urls)) mcp[name] = { type: 'remote', url, enabled: true, oauth: false };
  return mcp;
}

function providerFailure(kind, message, status, turns) {
  return Object.assign(new Error(`model provider: ${scrubToken(message || kind)}`.slice(0, CAUSE_MAX)), {
    providerKind: kind,
    providerStatus: Number.isInteger(status) ? status : undefined,
    turns,
  });
}

/**
 * opencode records a provider error on the assistant message once it stops retrying. Read here, it fails the run at
 * once instead of reading as an empty completion that a fresh session repeats.
 */
function refusalOf(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = (messages[i] && messages[i].info) || messages[i];
    if (!msg || msg.role !== 'assistant') continue;
    const error = msg.error;
    if (!error || error.name === 'MessageAbortedError') return null;
    const data = error.data || {};
    const status = Number(data.statusCode);
    if (error.name === 'ProviderAuthError' || status === 401 || status === 403) {
      return { kind: 'provider_rejected', message: data.message, status };
    }
    if (error.name !== 'APIError') return null;
    // A retryable error recorded here is one opencode already retried, about a minute's worth, and gave up on.
    const kind = data.isRetryable === false ? 'request_refused' : 'provider_unavailable';
    return { kind, message: data.message, status };
  }
  return null;
}

/** What lane.js prints for a failed run. Every field is numeric or an enum the launcher allow-lists. */
function failureEnvelope(e) {
  const out = { is_error: true, error: describeError(e), usage: sumUsage(e && e.turns) };
  if (e && e.providerKind) {
    out.kind = e.providerKind;
    if (Number.isInteger(e.providerStatus)) out.provider_status = e.providerStatus;
  }
  return JSON.stringify(out);
}


/**
 * spec: model (`provider/model`), prompt, jsonSchema (null asks for prose: no schema retry),
 * permission (required), mcp, timeoutMs (bounds the fetch), maxTurns (opencode steps at maxTurns - 2
 * so the forced text-only turn lands with margin), and systemPrompt (puts mcp-relay in front of the
 * platform token and runs under TRIAGE_AGENT).
 */
async function runAgent(spec) {
  const startMs = Date.now();
  // The root SDK surface: v2's prompt route answers UnknownError against this server.
  const { createOpencodeClient } = await import('@opencode-ai/sdk');

  const config = {
    permission: basePermission(spec.permission, { scratchDirs: Boolean(spec.systemPrompt) }),
    lsp: {},
  };
  const steps = Number.isFinite(spec.maxTurns) && spec.maxTurns > 0 ? Math.max(1, spec.maxTurns - 2) : undefined;

  let relay = null;
  if (spec.systemPrompt) {
    relay = await startMcpRelay({
      servers: [{ name: 'tessary-evals', url: spec.mcp.url, token: spec.mcp.token }],
      workDir: WORK,
    });
    config.mcp = remoteMcp(relay.urls);
    config.agent = { [TRIAGE_AGENT]: customAgent({ prompt: spec.systemPrompt, steps }) };
  } else {
    config.mcp = {
      'tessary-evals': {
        type: 'remote',
        url: spec.mcp.url,
        enabled: true,
        headers: { Authorization: `Bearer ${spec.mcp.token}` },
      },
    };
    if (steps !== undefined) {
      // No prompt selects an agent here, so the session runs as opencode's default `build`.
      config.agent = { build: { maxSteps: steps } };
    }
  }

  let server = null;
  try {
    server = await startServer(config);
    const client = createOpencodeClient({
      baseUrl: server.url,
      fetch: makeFetch(spec.timeoutMs),
      throwOnError: true, // a 4xx must not read as "the agent produced nothing"
    });
    let turns = [];
    let structured = null;
    let text = '';
    // Turns from abandoned sessions, so the reported spend covers every session this run opened.
    let allTurns = [];

    // One retry each for an empty completion and a schema miss; a real error repeats.
    let correction = '';
    let sessionID = '';
    // A schema miss resumes the same session (only the rendering was wrong). An empty completion starts
    // fresh, unless the session already did real work, which a fresh session would pay for again.
    let resume = false;
    for (let attempt = 0; attempt < 2; attempt++) {
      if (!resume) {
        allTurns = allTurns.concat(turns);
        const session = await client.session.create({ body: { title: 'tessary-agent' } });
        sessionID = (session && (session.id || (session.data && session.data.id))) || '';
      }
      // On a resume the session already holds the task; re-sending the prompt orders a second investigation.
      const prompt = resume
        ? correction.trim() + schemaInstruction(spec.jsonSchema)
        : spec.prompt + schemaInstruction(spec.jsonSchema) + correction;
      const body = {
        model: splitModel(spec.model),
        parts: [{ type: 'text', text: prompt }],
        ...(spec.systemPrompt ? { agent: TRIAGE_AGENT } : {}),
      };

      const reply = await client.session.prompt({ path: { id: sessionID }, body });
      const listed = await client.session.messages({ path: { id: sessionID } });
      const messages = Array.isArray(listed) ? listed : (listed && listed.data) || [];

      turns = toTurns(messages, prompt);
      const refusal = refusalOf(messages);
      if (refusal) {
        throw providerFailure(refusal.kind, refusal.message, refusal.status, allTurns.concat(turns));
      }
      const info = (reply && (reply.info || reply.data || reply)) || {};
      text =
        textOf(partsOf(reply && reply.parts ? reply : info)) || (turns.length ? turns[turns.length - 1].text : '');
      structured = extractJson(text);

      if (isEmptyCompletion(turns)) {
        const substantialWork = turns.length > 1 || turns.some((t) => (t.tool_calls || []).length > 0);
        if (substantialWork) {
          // Real work then an empty turn is opencode's own step cap. A new prompt on the same session reopens
          // tools, so the correction tells the agent not to use them; the run's deadline still bounds it.
          if (spec.systemPrompt && attempt === 0) {
            console.error('empty completion at the turn cap after real work: resuming the same session once, no tools');
            resume = true;
            correction =
              'You have no tool budget left. Do NOT call any tool. Using only the evidence you have already ' +
              'read, reply now with the ruling JSON object and nothing else.';
            continue;
          }
          break;
        }
        resume = false;
        continue;
      }
      // A schema with no `required` list makes missingKeys vacuously empty, so parsing an object at all is
      // what catches prose.
      const missing = missingKeys(structured, spec.jsonSchema);
      if (!spec.jsonSchema || (structured && missing.length === 0)) break;
      if (attempt === 0) console.error(describeSchemaMiss(text, turns, spec.jsonSchema));
      resume = true;
      correction = structured
        ? `\n\nYour previous reply was missing ${missing.join(', ')}. Do NOT investigate further — you ` +
          'already have what you need. Reply with the COMPLETE JSON object and nothing else.'
        : '\n\nYour previous reply could not be parsed as a single JSON object. Do NOT investigate ' +
          'further — you already have what you need. Reply with the JSON object and nothing else: no ' +
          'prose, no markdown fence, nothing before or after it. Keep every string field short enough ' +
          'that the whole object fits in one reply.';
    }

    const finalTurns = allTurns.concat(turns);

    // Every throw carries `.turns` so a failing lane can still book what the run spent.
    const empty = finalTurns.length === 0;
    const unusable = empty || (!structured && !text);
    if (empty) {
      console.error(usageLine(finalTurns));
      throw Object.assign(new Error('opencode produced no result'), { turns: finalTurns });
    }
    if (unusable) {
      console.error(usageLine(finalTurns));
      throw Object.assign(new Error('opencode produced no usable reply'), { turns: finalTurns });
    }

    // Prose clears `unusable` above, so a schema miss that survived the retry is rejected here.
    const missing = missingKeys(structured, spec.jsonSchema);
    if (spec.jsonSchema && (!structured || missing.length)) {
      console.error(describeSchemaMiss(text, finalTurns, spec.jsonSchema));
    }
    if (spec.jsonSchema && !structured) {
      throw Object.assign(
        new Error('opencode did not return a JSON object for the requested schema (2 attempts)'),
        { turns: finalTurns },
      );
    }
    if (missing.length) {
      throw Object.assign(
        new Error(`opencode returned JSON missing required ${missing.join(', ')} (2 attempts)`),
        { turns: finalTurns },
      );
    }

    return { startMs, turns: finalTurns, resultRaw: toEnvelope(finalTurns, structured, text) };
  } finally {
    // The relay starts before startServer(), so it is closed here even on a start failure. The server close
    // is awaited and never rejects, so it cannot replace the run's own error.
    try {
      if (server) await server.close();
    } finally {
      if (relay) relay.close();
    }
  }
}

module.exports = {
  git,
  onsetCommit,
  refusalOf,
  SESSION_LANES,
  runAgent,
  startServer,
  makeFetch,
  splitModel,
  usageOf,
  scrubToken,
  basePermission,
  customAgent,
  remoteMcp,
  writeDossier,
  describeError,
  failureEnvelope,
  quarantineRepo,
  sumUsage,
  WORK,
  REPO,
};
