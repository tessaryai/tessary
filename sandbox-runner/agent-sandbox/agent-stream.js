// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Shared OpenCode runner for the analyzer scripts (analyze.js, rca.js, synthesize.js,
 * codegen.js). ONE source of truth for:
 *   - starting `opencode serve` and driving it over the SDK,
 *   - turning its session messages into the `turns[]` shape the backend's
 *     AgentSpanTelemetry.recordTurns expects,
 *   - synthesising the result envelope the backend parses (`resultRaw`),
 *   - the clone-token scrubber.
 *
 * WHY THE SERVER, NOT `opencode run`: `run --format json` drops text and step_finish events in
 * containers (anomalyco/opencode#31435) — which is exactly this microVM.
 *
 * THE SDK OWNS THE LIFECYCLE. createOpencodeServer spawns the process and waits for its own
 * `opencode server listening on <url>` announcement; we do not spawn or poll ourselves. The
 * hand-rolled version treated "the TCP port accepts" as ready, and every RCA hung in the ~320ms
 * between accept and readiness (measured), burning Node's 300s fetch cap before failing.
 *
 * WHY WE LIST MESSAGES INSTEAD OF STREAMING: nothing here renders live progress. The
 * backend reads one payload at the end, so reading the session's messages once the
 * prompt resolves is the same data with none of the event-ordering exposure.
 *
 * Runs identically in-VM (E2B template, /home/user) and in local/host mode
 * (agent-sandbox/), because `require('./agent-stream')` resolves beside the caller in
 * both. THEREFORE this file MUST be copied into the E2B template next to the scripts (see
 * template.ts `.copy('agent-stream.js', ...)`) — a missing copy turns every analyzer run
 * into a require-not-found crash.
 */
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const { Agent, fetch: undiciFetch } = require('undici');

// How long the sandbox may run when the caller does not say. The launcher always passes the
// run's real deadline; this only covers a direct/local invocation.
const DEFAULT_RUN_MS = 900_000;

/**
 * Raise Node's 300s cap on a SINGLE fetch to the run's own deadline.
 *
 * session.prompt() holds ONE HTTP request open for the WHOLE agent run — every turn, every
 * model call — and Node's fetch defaults headersTimeout to 300s. A lane that thinks for longer
 * is killed by the CLIENT while the server is still working, surfacing as a bare
 * `TypeError: fetch failed`. The SDK tries to lift this (`req.timeout = false`) but that is a
 * Bun-ism: Bun honours it, Node ignores an unknown property on a WHATWG Request, and these
 * scripts run on node:22-slim.
 *
 * Bounded by the deadline rather than disabled outright, so the launcher's timeout_ms stays the
 * one authority on how long a run may take. Disabling it (headersTimeout: 0) would let a
 * genuinely wedged request sit until the sandbox is reaped, turning a 5-minute failure into a
 * 15-minute one.
 *
 * Two dead ends worth not re-walking, both measured against a slow stub server:
 *   - `setGlobalDispatcher` is SILENTLY IGNORED by the built-in fetch when the runtime's
 *     bundled undici is a different major than this package's — no error, just no effect.
 *   - a `dispatcher` in the built-in fetch's init throws UND_ERR_INVALID_ARG, same reason.
 * Calling undici's OWN fetch with an explicit dispatcher sidesteps the split entirely.
 */
function makeFetch(runMs) {
  const dispatcher = new Agent({ headersTimeout: runMs, bodyTimeout: runMs });
  // undici's fetch cannot consume a cross-realm global `Request` (it stringifies to
  // "[object Request]" and throws Invalid URL), and a Request is exactly what the SDK hands its
  // override — so unwrap it into (url, init). The body is buffered rather than streamed to stay
  // off the half-duplex path; these payloads are prompts, not uploads.
  return async function boundedFetch(input, init) {
    // Unwrapped whether or not an init tags along. The generated client calls this as
    // `fetch(request)` with one argument today, but a Request reaching the (url, init) fallback
    // below is exactly the Invalid URL failure above — so the Request-like branch must be the
    // one that can never be bypassed, not the one that happens to match today's call shape.
    if (input && typeof input === 'object' && typeof input.url === 'string' && input.headers) {
      const req = init ? new Request(input, init) : input;
      const method = req.method || 'GET';
      return undiciFetch(req.url, {
        method,
        headers: Object.fromEntries(req.headers.entries()),
        // Carried across the unwrap: dropping it would strand the socket of a request whose
        // caller has already given up.
        signal: req.signal,
        body: method === 'GET' || method === 'HEAD' ? undefined : Buffer.from(await req.arrayBuffer()),
        dispatcher,
      });
    }
    return undiciFetch(input, { ...init, dispatcher });
  };
}

// WORK is the filesystem root for a run. Default '/home/user' keeps the E2B template behavior
// byte-for-byte; the host launcher (local mode) sets WORK_DIR to a per-request temp dir.
const WORK = process.env.WORK_DIR || '/home/user';
const REPO = `${WORK}/repo`;
const MAX_TURNS = 200;

// The agent is started in WORK, NOT in the clone. OpenCode discovers opencode.json and
// .opencode/ (whose plugins it EXECUTES at startup) from its start directory up to the
// nearest git root, and reads AGENTS.md/CLAUDE.md by walking up from it. Starting inside a
// customer's clone would hand that repo control of our agent's config, plugins and system
// prompt. Starting in WORK — which we own and which is not a git repo — puts every one of
// those lookups on ground we control, while leaving REPO fully readable: it is a child of
// the start directory, so the `external_directory` permission never applies to it.
const AGENT_CWD = WORK;

// Repo-sourced agent configuration, moved aside immediately after checkout by quarantineRepo().
// Belt to AGENT_CWD's braces: nothing here is discovered from WORK anyway, but a future
// upstream change to discovery must not silently re-open the hole.
const QUARANTINED = ['.opencode', 'opencode.json', 'opencode.jsonc', 'AGENTS.md', 'CLAUDE.md', '.cursor'];

// Strip embedded secrets before they can reach a log: clone tokens
// (https://x-access-token:TOKEN@host) and platform API keys (tsy_<scope>_<random> —
// the ephemeral MCP key rca.js wires into the sandbox has this shape).
function scrubToken(s) {
  return String(s)
    .replace(/x-access-token:[^@\s]+@/g, 'x-access-token:***@')
    .replace(/tsy_[a-z]_[A-Za-z0-9_-]+/g, 'tsy_***');
}

// Node's fetch (undici) throws `TypeError: fetch failed` for every transport failure —
// DNS, connection refused, TLS, a reset mid-response — and buries the actual cause one
// level down in `.cause`. rca.js's top-level catch used to log only `.message`, so a run
// that died talking to the local opencode server or a Bedrock endpoint left nothing in the
// launcher logs but the word "failed": undebuggable. Walk the cause chain and any
// code/errno/syscall Node attaches, so the real failure (e.g. ECONNREFUSED, ENOTFOUND,
// a 403 from Bedrock) survives into the log line a human actually reads.
//
// A cause is not always an Error. The SDK's throwOnError path wraps a 4xx body into
// `new Error(name, { cause: { body, status } })` — a PLAIN OBJECT whose `body` holds the only
// copy of the server's actual complaint (ConfigInvalidError's failing path, BadRequest's zod
// issue). `String(pojo)` is "[object Object]", so walking the chain naively throws that detail
// away and leaves a bare error name — which is the failure mode this whole harness exists to
// avoid. Serialise a non-Error cause instead, capped so a large body cannot flood the log.
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

/** Wall-clock ceiling for any one git invocation. Far below every caller's own deadline. */
const GIT_TIMEOUT_MS = Number(process.env.GIT_TIMEOUT_MS || 180_000);

/**
 * Run git, and FAIL rather than wait for a human who is not there.
 *
 * <p>This helper existed in five copies (analyze, adjudicate, codegen, rca, synthesize), each
 * `execFileSync('git', args, { stdio: 'ignore' })` with no timeout and no prompt suppression. That
 * is a hang, not an error: when a clone's credentials are rejected — an expired installation token,
 * a revoked app — git asks for a username, the question goes to an ignored stdin, and it waits
 * forever. Nothing is logged, because stdio is discarded. The only symptom is the caller's own
 * deadline expiring: measured as a 20-minute silent burn per repo-grounded adjudication, reported
 * as `kind=timeout`, with a fail-safe `unclear` verdict recorded against a finding nobody ruled on.
 * Every agentic path shared the defect, so an auth problem looked identical to a slow agent.
 *
 * <p>Three guards, in the order they matter:
 * <ul>
 *   <li>{@code GIT_TERMINAL_PROMPT=0} plus empty askpass vars — a credential request becomes an
 *       immediate non-zero exit instead of a blocked read.
 *   <li>{@code -c credential.helper=} resets the helper list, so a configured helper (macOS
 *       keychain on a dev host, anything inherited in an image) can neither hang nor silently
 *       substitute different credentials than the caller passed.
 *   <li>a hard {@code timeout} — the backstop for a network stall, which no prompt setting covers.
 * </ul>
 *
 * <p>stderr is captured and scrubbed rather than discarded, so the thrown error can say
 * <em>why</em>. That is the whole point: "Authentication failed" and "connection timed out" are
 * different operational problems and used to be the same opaque sentence. Args are never echoed —
 * they carry the tokenized clone URL — and {@link scrubToken} covers the case where git quotes the
 * remote back at us in its own message.
 */
function git(args) {
  try {
    execFileSync('git', ['-c', 'credential.helper=', ...args], {
      // stdin/stdout ignored, stderr captured: the reason is worth having, the output never is.
      stdio: ['ignore', 'ignore', 'pipe'],
      timeout: GIT_TIMEOUT_MS,
      killSignal: 'SIGKILL',
      env: {
        ...process.env,
        GIT_TERMINAL_PROMPT: '0',
        GIT_ASKPASS: '',
        SSH_ASKPASS: '',
        GIT_CONFIG_NOSYSTEM: '1',
      },
    });
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

/**
 * Move repo-sourced agent configuration out of the clone, into REPO/quarantine/. The files stay
 * readable as evidence — an AGENTS.md is a legitimate thing for the observer to describe — they
 * just stop sitting where any agent harness looks for instructions to obey.
 */
function quarantineRepo() {
  const dest = path.join(REPO, 'quarantine');
  for (const name of QUARANTINED) {
    const from = path.join(REPO, name);
    if (!fs.existsSync(from)) continue;
    try {
      fs.mkdirSync(dest, { recursive: true });
      fs.renameSync(from, path.join(dest, name));
    } catch {
      // Best-effort: a file we cannot move is one AGENT_CWD already protects us from.
    }
  }
}

// --- server lifecycle --------------------------------------------------------
//
// The SDK's createOpencodeServer owns spawning and readiness; we do NOT hand-roll either.
//
// This used to spawn `opencode serve` directly and treat "the TCP port accepts a connection"
// as ready. It is not. Measured in the E2B template: the port accepts at 3.22s and the server
// announces itself at 3.54s, and a request that lands in that ~320ms window HANGS FOREVER
// rather than being queued. Every RCA died there — the hang burned Node's 300s fetch cap and
// surfaced as `fetch failed` at ~309s, with no model ever invoked (Bedrock logged zero
// invocations for those runs).
//
// createOpencodeServer waits for the process to print `opencode server listening on <url>` on
// stdout, which is the server's own readiness signal, and hands back that url. It also takes a
// TYPED config object and serialises OPENCODE_CONFIG_CONTENT itself — the hand-built JSON is
// how an invalid `permission.webfetch` shape shipped unnoticed.

/**
 * The launcher's provider config (agentEnvs), passed down in the env var OpenCode reads. Must be
 * MERGED, not replaced: the var holds one value, so overwriting it drops the provider block and
 * bedrock-mantle-gpt never registers — the GPT-5.6 lanes then fail to resolve a model while every
 * bedrock-runtime lane still works off the ambient AWS_REGION. Unparseable throws rather than
 * degrading to that same silent state.
 */
function inheritedConfig() {
  const raw = process.env.OPENCODE_CONFIG_CONTENT;
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (e) {
    throw new Error(`OPENCODE_CONFIG_CONTENT is not valid JSON: ${e.message}`);
  }
}

/**
 * Start the server through the SDK, on loopback, rooted at AGENT_CWD.
 *
 * No basic auth: the server is bound to 127.0.0.1 inside a single-tenant microVM that is torn
 * down after one run, so a password would only protect the sandbox from the agent we are
 * deliberately running in it.
 *
 * Two things the SDK does NOT do for us, so we do them here, before it spawns:
 *   - cwd. createOpencodeServer inherits the parent's working directory, and the agent's start
 *     directory is a SECURITY boundary (see AGENT_CWD) — so chdir first rather than hope the
 *     launcher invoked us from the right place.
 *   - the OPENCODE_DISABLE_* pins. It forwards process.env, so setting them on ourselves is
 *     what reaches the child: auto-update (a network fetch mid-run), LSP downloads (nothing
 *     here needs a language server) and .claude compatibility reads (a customer repo must not
 *     reach our system prompt).
 *
 * The port is chosen HERE rather than left to the SDK. Its default is a fixed 4096, which two
 * concurrent runs on one host (local mode) would collide on, and `port: 0` does NOT mean
 * "any free port" — measured, opencode ignores it and binds 4096 anyway. An explicitly chosen
 * free port is honoured, and the SDK still reads the real url back off the announcement.
 *
 * Timeout is generous because a cold microVM is slow to boot: the SDK's own default is 5s and
 * we measured 3.5s warm, which leaves no headroom at all.
 *
 * The SDK's two start failures are NOT equally informative, which is worth knowing before
 * reading a log written by one of them (read its dist/server.js):
 *   - the process EXITS (the usual shape of a bad config or a missing binary) → it rejects with
 *     `Server exited with code N` AND the stdout+stderr it collected. That is the good case.
 *   - the timeout fires (the process is up but never announced) → it rejects with a bare
 *     `Timeout waiting for server to start after Nms` and DISCARDS everything it collected.
 * The SDK hands back no process handle, so there is no way to tap that output ourselves. All we
 * can do is say so at the point of failure, rather than let a contentless timeout read like a
 * slow boot — hence the wrap below.
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

async function startServer(configJson) {
  const { createOpencodeServer } = await import('@opencode-ai/sdk');
  process.chdir(AGENT_CWD);
  process.env.OPENCODE_DISABLE_AUTOUPDATE = '1';
  process.env.OPENCODE_DISABLE_LSP_DOWNLOAD = '1';
  process.env.OPENCODE_DISABLE_CLAUDE_CODE = '1';
  // Run-scoped keys last, so the lane's permission rules win over anything inherited. Read
  // BEFORE handing the merged object over, because the SDK overwrites the var with its own.
  const config = { ...inheritedConfig(), ...configJson };
  const startMs = Date.now();
  try {
    return await createOpencodeServer({ hostname: '127.0.0.1', port: await freePort(), timeout: 120_000, config });
  } catch (e) {
    const hint = /Timeout waiting/.test(String((e && e.message) || ''))
      ? ' — the process was up but never announced itself, and the SDK discards its output on this' +
        ' path, so there is none to show'
      : '';
    throw new Error(`opencode server did not start after ${Date.now() - startMs}ms: ${describeError(e)}${hint}`, {
      cause: e,
    });
  }
}

/**
 * `provider/model` → the `{providerID, modelID}` the prompt API takes. Sending the joined string
 * is a 400 (`Expected object | null, got "amazon-bedrock/..." at ["model"]`), which the SDK
 * surfaces as an empty reply rather than a throw — so it reads as "the agent said nothing".
 *
 * Split on the FIRST slash only: provider ids never contain one, model ids can (and routinely
 * carry a `:0` version suffix, e.g. `anthropic.claude-sonnet-4-5-20250929-v1:0`). An id with no
 * slash is passed through as a bare modelID and left for the server to resolve.
 */
function splitModel(model) {
  const i = typeof model === 'string' ? model.indexOf('/') : -1;
  if (i < 0) return { modelID: String(model || '') };
  return { providerID: model.slice(0, i), modelID: model.slice(i + 1) };
}

/**
 * Schema-constrained output, asked for in the PROMPT rather than through the server's `format`.
 *
 * `format: {type:'json_schema', schema}` is accepted by opencode 1.18.13 and then does not work:
 * the reply comes back with no text and no structured output, and the session is left in a state
 * where GET messages 400s with `Expected OutputFormatJsonSchema` — the server cannot re-read the
 * format it just stored. Measured both ways against a live model: identical prompt, `format` set
 * → empty reply + poisoned session; `format` omitted → a real answer and a readable session.
 *
 * So we instruct instead of constrain, and rebuild `structured_output` ourselves in extractJson.
 * The envelope the backend reads is unchanged, which is the contract that actually matters.
 * Revisit if a later opencode fixes it — schemaInstruction/extractJson/missingKeys are the three
 * places to delete. NOTE upstream's own mechanism is a forced `StructuredOutput` TOOL CALL, and
 * its documented retry knob (`format.json_schema.retryCount`) is accepted but ignored
 * (anomalyco/opencode#25430) — so even when it works there is no second attempt. Ours retries.
 */
function schemaInstruction(jsonSchema) {
  return (
    '\n\nRespond with ONLY a single JSON object that validates against this JSON Schema. ' +
    'No prose, no markdown fence, no commentary before or after it.\n\n' +
    JSON.stringify(jsonSchema)
  );
}

/**
 * The top-level `required` keys a parsed reply is missing, as a list. NOT a JSON Schema validator
 * — a real one is a dependency this microVM does not need, and the backend re-parses and re-checks
 * everything anyway (RcaSynthesisOutput). This exists to catch the one failure that is both common
 * and cheap to fix by asking again: a model that answered in prose, or dropped a required field.
 *
 * "The reply was not JSON at all" is NOT signalled here — a caller already knows that from a null
 * `parsed`, and folding it in as a pseudo-key put the string "<no JSON object>" into the list the
 * correction prompt names back to the model.
 */
function missingKeys(parsed, jsonSchema) {
  const required = jsonSchema && Array.isArray(jsonSchema.required) ? jsonSchema.required : [];
  if (!parsed || typeof parsed !== 'object') return required;
  return required.filter((k) => parsed[k] === undefined);
}

/**
 * The first BALANCED `{...}` span in `text`, as a slice, or null.
 *
 * String-aware on purpose: the RCA schema's `detailed_report` is a markdown document carried
 * inside a JSON string, and markdown is full of braces and backticks. A depth counter that did
 * not skip string contents would close the object on the first `}` inside the report.
 *
 * Scanning from each `{` in turn (rather than trusting the first) is what tolerates leading
 * prose that happens to contain a brace. A span that never balances — the shape a reply
 * truncated by an output-token limit has — yields nothing, which is the honest answer.
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

/**
 * Pull the reply's JSON object back out of the text. Tolerates a markdown fence and prose on
 * either side because "ONLY JSON" is an instruction, not a guarantee; returns null when there is
 * nothing parseable, leaving the caller's normal empty-result handling to fire.
 *
 * This USED TO be `indexOf('{')` to `lastIndexOf('}')`, which is wrong in both directions and
 * silently so: a sentence before the object containing a brace moved the start, a sentence after
 * it containing one moved the end, and either produced an unparseable slice — reported to the
 * caller as "the model ignored the schema" when the model had in fact complied. balancedSpans
 * finds the object itself.
 */
function extractJson(text) {
  if (!text) return null;
  for (const span of balancedSpans(text)) {
    try {
      const parsed = JSON.parse(span);
      if (parsed && typeof parsed === 'object') return parsed;
    } catch {
      // Fall through to the next candidate.
    }
  }
  return null;
}

/**
 * Why a schema lane is about to reject, in a form a log can carry.
 *
 * A schema miss is the most expensive failure this file has: it lands at the END of a run that
 * has already spent its whole wall-clock and its whole bill, and the reply that caused it is
 * discarded by the throw. That left the operator with one sentence — "did not return a JSON
 * object" — and no way to tell a truncated reply from a prose one from a parser bug, which is
 * exactly the ambiguity that made the first such failure undiagnosable.
 *
 * Deliberately BOUNDED and SCRUBBED: this goes to the launcher console, which keeps the last 4KB
 * of a run's stderr, and the reply can quote evidence. Head and tail rather than the middle —
 * the two ends are what distinguish the failure modes. `output_tokens` is the tell for
 * truncation: a reply that stopped at the provider's cap reports the cap.
 */
function describeSchemaMiss(text, turns, jsonSchema) {
  const t = String(text || '');
  const last = turns && turns.length ? turns[turns.length - 1] : null;
  const spans = balancedSpans(t);
  const lines = [
    'schema miss: ' +
      `chars=${t.length} braces=${(t.match(/{/g) || []).length} balanced=${spans.length} ` +
      `turns=${turns ? turns.length : 0} last_turn_output_tokens=${last ? last.usage.output_tokens : 0}`,
    'required=' + ((jsonSchema && jsonSchema.required) || []).join(','),
    usageLine(turns),
    '--- reply head ---\n' + scrubToken(t.slice(0, 600)),
  ];
  if (t.length > 900) lines.push('--- reply tail ---\n' + scrubToken(t.slice(-600)));
  return lines.join('\n');
}

/**
 * What the run has spent so far, as one line, for the launcher console.
 *
 * F1: a failing lane USED TO reach only this line — its tokens never reached the launcher, and the
 * backend booked NOTHING for it (a run could burn its whole wall-clock and whole bill and leave a
 * ledger that said it cost zero). triage.js/rca.js now also write `sumUsage(e.turns)` into a small
 * JSON envelope on stdout before exiting non-zero, which server.js's buildErrorBody carries into the
 * 502 body as `usage`, and E2bTriageSandbox/E2bRcaSandbox book it from there. This line stays as the
 * human-readable copy on the launcher console — the two must never disagree, hence both read sumUsage.
 *
 * Cache reads are called out separately because on a repo-grounded or evidence-heavy run they are
 * most of the input and are priced at a tenth of it — an input_tokens figure that folded them in
 * would read an order of magnitude too expensive.
 */
/**
 * Sum a turns[] array into one numeric-only usage object — the ONE place both the success envelope
 * (toEnvelope) and a failing run's stdout envelope (triage.js/rca.js's catch block, via this same
 * exported name) compute spend, so the two can never drift apart.
 *
 * Every field here is a plain number, on purpose: this crosses into grader-runner/launcher/server.js's
 * HARD RULE territory (buildErrorBody forwards exactly this shape into the 502 body it sends the
 * backend), so nothing added to this object may ever become a string.
 */
function sumUsage(turns) {
  const z = { input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 };
  return (turns || []).reduce(
    (a, t) => ({
      input_tokens: a.input_tokens + t.usage.input_tokens,
      output_tokens: a.output_tokens + t.usage.output_tokens,
      cache_read_input_tokens: a.cache_read_input_tokens + t.usage.cache_read_input_tokens,
      cache_creation_input_tokens: a.cache_creation_input_tokens + t.usage.cache_creation_input_tokens,
    }),
    z,
  );
}

function usageLine(turns) {
  const u = sumUsage(turns);
  return (
    `spend (booked on failure by the caller — see triage.js/rca.js): in=${u.input_tokens} out=${u.output_tokens} ` +
    `cache_read=${u.cache_read_input_tokens} cache_write=${u.cache_creation_input_tokens} ` +
    `turns=${(turns || []).length}`
  );
}

// --- message → turn mapping --------------------------------------------------

// OpenCode message parts are a tagged union whose exact field names have moved between
// releases. Every reader below is written to accept the shapes we have seen rather than one
// of them, because a rename upstream must degrade telemetry, never fail the run.
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
      let args = '';
      try {
        args = JSON.stringify(c.input || state.input || {});
      } catch {
        args = '';
      }
      return { id: c.callID || c.id || '', name: c.tool || c.name || '', input: args };
    })
    .filter((c) => c.name);
}

function toolResultsOf(parts) {
  const out = [];
  for (const c of parts) {
    if (!c || (c.type !== 'tool' && c.type !== 'tool_use' && c.type !== 'tool-call')) continue;
    const state = c.state || {};
    let r = state.output ?? state.result ?? c.output;
    if (r === undefined || r === null) continue;
    if (typeof r !== 'string') {
      try {
        r = JSON.stringify(r);
      } catch {
        continue;
      }
    }
    out.push({ id: c.callID || c.id || '', content: r });
  }
  return out;
}

function usageOf(message) {
  const t = (message && message.tokens) || {};
  const cache = t.cache || {};
  return {
    input_tokens: Number(t.input || 0),
    output_tokens: Number(t.output || 0),
    cache_read_input_tokens: Number(cache.read || 0),
    cache_creation_input_tokens: Number(cache.write || 0),
  };
}

/**
 * An assistant message carrying neither text, nor a tool call, nor any output tokens.
 *
 * Bedrock returns these on the non-Anthropic model families: a completed response, stop reason
 * and all, with nothing in it (anomalyco/opencode#31430, closed as a provider bug). An agent
 * reads it as "the task is done", so a run that hit one has silently truncated — which is worse
 * than a crash, because the caller cannot tell. runAgent retries once when the LAST assistant
 * message looks like this.
 */
function isEmptyCompletion(turns) {
  const last = turns[turns.length - 1];
  if (!last) return true;
  return !last.text && last.tool_calls.length === 0 && last.usage.output_tokens === 0;
}

/**
 * Build the `turns[]` the backend renders as child llm_request spans. `input` is what that
 * model call was responding to: the prompt for turn 0, the previous turn's tool results after.
 */
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
      index: turns.length,
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
 * The result envelope. This shape is OURS — the backend's AgentSpanTelemetry and
 * E2bAnalysisSandbox read `usage`, `num_turns` and `structured_output` by name, and the analyzer
 * scripts read `structured_output`/`result` — so it is a contract between this file and Java, not
 * an artifact of the harness that used to produce it.
 *
 * It carries NO cost field, on purpose. The harness's own figure does not price cache reads
 * (anomalyco/opencode#28494), and cache reads are most of a repo-grounded run's bill, so the
 * platform prices these raw token counts itself (LlmUsageAccountant.recordSandboxRun).
 */
function toEnvelope(turns, structured, text) {
  const usage = sumUsage(turns);
  const env = { type: 'result', is_error: false, num_turns: turns.length, usage, result: text };
  if (structured && typeof structured === 'object') env.structured_output = structured;
  return JSON.stringify(env);
}

// --- the run -----------------------------------------------------------------

/**
 * Run one agent invocation against `spec.prompt`; collect per-turn telemetry + a result envelope.
 *
 * @param {{model: string, prompt: string, jsonSchema?: object, mcp?: {url: string, token: string},
 *          permission?: object, rejectOn?: 'error'|'no-result'|'never', timeoutMs?: number,
 *          maxTurns?: number}} spec
 *   - model: a `provider/model` id (see toProviderModel in the launcher); split for the wire.
 *   - jsonSchema: when set, the reply is schema-constrained and lands in `structured_output`.
 *   - permission: the lane's OpenCode permission rules. Every lane passes one — an agent that
 *     may edit anything is a choice, not a default.
 *   - rejectOn: 'error' (default) rejects when the run produced no usable reply, for runs whose
 *     VALUE is that reply (synthesize, rca). 'no-result' rejects only on a wholly empty run
 *     (analyze, which fails open downstream). 'never' always resolves, for runs whose artifact
 *     is a file the agent wrote (codegen).
 *   - timeoutMs: the launcher's deadline for this run; bounds the client-side fetch.
 *   - maxTurns: the operator-configured turn BUDGET (triage/RCA only; every other
 *     caller omits this). Passed to the SDK as `config.agent.build.maxSteps`, whose own doc
 *     comment ("Maximum number of agentic iterations before forcing text-only response") is the
 *     mechanism this relies on for a soft landing rather than a hard kill — set 2 LOWER than the
 *     budget so the forced text-only turn lands with margin, per the issue's "two turns before the
 *     cap" ask. NOT independently confirmed against a live run in the change that added this field
 *     — see that change's PR description.
 * @returns {Promise<{startMs: number, turns: object[], resultRaw: string}>}
 */
async function runAgent(spec) {
  const startMs = Date.now();
  // The ROOT surface, deliberately. The package also ships `@opencode-ai/sdk/v2`, which is the
  // only one whose types know about `format` — but its prompt route answers UnknownError
  // ("Unexpected server error") against this server, while the root client drives it correctly.
  // Since schemaInstruction replaces `format` entirely (see there), v2 buys nothing.
  const { createOpencodeClient } = await import('@opencode-ai/sdk');

  // Headless: every tool the lane needs must be 'allow' outright, because an 'ask' has nobody to
  // answer it and would burn the run's wall-clock waiting. bash is on for all five lanes (git, the
  // baked validator, the codegen harness, triage's own check scripts); webfetch is off for all five —
  // nothing here has a reason to reach the network, and it would be the cheapest exfiltration channel
  // out of a sandbox holding cloud credentials. external_directory pins the agent inside AGENT_CWD.
  //
  // webfetch/websearch take a BARE action, not a `{'*': ...}` map — those two are not
  // pattern-scoped the way bash/edit/external_directory are. Sending the map form makes the
  // server reject the whole config with ConfigInvalidError, which 400s every endpoint including
  // session creation, so the lane fails before it can invoke anything.
  const config = {
    permission: {
      bash: { '*': 'allow' },
      webfetch: 'deny',
      websearch: 'deny',
      edit: { '*': 'deny' },
      ...(spec.permission || {}),
      external_directory: { '*': 'deny' },
    },
    lsp: {},
  };
  if (spec.mcp && spec.mcp.url && spec.mcp.token) {
    // Config, never argv: the platform key must not be visible in the process table.
    config.mcp = {
      'tessary-evals': {
        type: 'remote',
        url: spec.mcp.url,
        enabled: true,
        headers: { Authorization: `Bearer ${spec.mcp.token}` },
      },
    };
  }
  if (Number.isFinite(spec.maxTurns) && spec.maxTurns > 0) {
    // See the JSDoc above for the mechanism and its margin. No prompt selects a
    // non-default agent (the `body` below carries no `agent` field), so the SESSION runs under
    // opencode's default agent identity, which is `build` — the one this config key names.
    config.agent = { build: { maxSteps: Math.max(1, spec.maxTurns - 2) } };
  }

  const server = await startServer(config);
  try {
    const client = createOpencodeClient({
      baseUrl: server.url,
      fetch: makeFetch(spec.timeoutMs || DEFAULT_RUN_MS),
      throwOnError: true, // a 4xx must not read as "the agent produced nothing"
    });
    let turns = [];
    let structured = null;
    let text = '';
    // F1/F3: turns from a session this run ABANDONED (the fresh-session-retry branch below starts
    // a brand-new session, which orphans whatever the previous session already spent). Folded back
    // in just before a fresh session is created — see the `if (!resume)` branch — so the final
    // usage this function reports (on success via toEnvelope, on failure via the throw sites'
    // `.turns`) is the sum of EVERY session this run opened, not just the last one. A schema-miss
    // resume never needs this: it stays on the same session, so `client.session.messages` below
    // already returns that session's full history on its own.
    let allTurns = [];

    // Two attempts, for two transient failures worth one more try each:
    //   - an empty completion (a provider that answered "done" with nothing in it, see
    //     isEmptyCompletion), and
    //   - a schema miss: prose instead of JSON, or a dropped required key. Upstream's own
    //     structured-output path has no retry at all (#25430), which is half of why a schema
    //     miss used to sink a run.
    // A real error repeats, so neither is retried more than once. The second attempt names what
    // was wrong rather than just asking again.
    let correction = '';
    let sessionID = '';
    // Whether attempt 2 CONTINUES attempt 1's session or starts a new one, and the two are not
    // interchangeable. A schema miss means the investigation happened and only its final
    // rendering was wrong, so the fix is to ask the same session to restate it — a fresh session
    // would throw away the whole run and re-do it inside whatever wall-clock is left, which is
    // both the expensive answer and the one that makes "your previous reply" refer to nothing.
    // An EMPTY completion is the opposite case: the session itself produced nothing, so the
    // retry has to start clean — UNLESS (E) that session already did real work and only its LAST
    // turn came back empty, in which case a fresh session would re-pay for that work rather than
    // recover it; see the isEmptyCompletion branch below.
    let resume = false;
    for (let attempt = 0; attempt < 2; attempt++) {
      if (!resume) {
        // Starting a brand-new session abandons whatever `turns` currently holds from the PRIOR
        // session (attempt 0 here is a no-op: `turns` is still `[]`) — fold it into the accumulator
        // before it is overwritten below, or that spend silently vanishes (the exact F1 bug).
        allTurns = allTurns.concat(turns);
        const session = await client.session.create({ body: { title: 'tessary-agent' } });
        sessionID = (session && (session.id || (session.data && session.data.id))) || '';
      }
      // On a resume the session already holds the task and the evidence; re-sending the
      // investigation prompt would order a second investigation. Send the correction and the
      // schema, nothing else.
      const prompt = resume
        ? correction.trim() + (spec.jsonSchema ? schemaInstruction(spec.jsonSchema) : '')
        : (spec.jsonSchema ? spec.prompt + schemaInstruction(spec.jsonSchema) : spec.prompt) + correction;
      const body = { model: splitModel(spec.model), parts: [{ type: 'text', text: prompt }] };

      const reply = await client.session.prompt({ path: { id: sessionID }, body });
      const listed = await client.session.messages({ path: { id: sessionID } });
      const messages = Array.isArray(listed) ? listed : (listed && listed.data) || [];

      turns = toTurns(messages, prompt);
      const info = (reply && (reply.info || reply.data || reply)) || {};
      text =
        textOf(partsOf(reply && reply.parts ? reply : info)) || (turns.length ? turns[turns.length - 1].text : '');
      structured = spec.jsonSchema ? extractJson(text) : null;

      if (isEmptyCompletion(turns)) {
        // (E) Gate the fresh-session retry on how little this session actually did. A LONE empty
        // turn (no prior turns, no tool calls) is cheap to redo from scratch. A session that
        // already ran a multi-turn investigation and only stumbled on its FINAL reply is not: a
        // fresh-session retry would throw away everything it learned and re-run the whole
        // investigation inside whatever wall-clock is left, silently doubling the run's cost for a
        // failure that a retry is not even likely to fix (the same model, the same task). Let it
        // fail instead — `unusable` below will reject it, and Step 1's accumulation means the
        // failure's usage line/envelope still carries every token this session actually spent.
        const substantialWork = turns.length > 1 || turns.some((t) => (t.tool_calls || []).length > 0);
        if (substantialWork) break;
        resume = false;
        continue;
      }
      if (!spec.jsonSchema) break;
      // Both halves matter. A schema with no `required` list makes missingKeys vacuously empty,
      // so "we parsed an object at all" is the check that carries the prose case.
      const missing = missingKeys(structured, spec.jsonSchema);
      if (structured && missing.length === 0) break;
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

    // The final tally: every session this run opened, not just the last one (see `allTurns` above).
    const finalTurns = allTurns.concat(turns);

    // No stderr tail to append any more, and none is needed. `throwOnError` turns a rejected
    // request into a throw carrying the server's own body (ConfigInvalidError, BadRequest, …) —
    // as an Error whose `cause` is the parsed body, which is why describeError has to serialise a
    // non-Error cause. Server-start failures are wrapped in startServer. Both land in the lanes'
    // top-level catch, all four of which now go through describeError.
    //
    // F1: every throw below carries `.turns = finalTurns` — a plain data property on the Error,
    // never a string field derived from model output — so triage.js/rca.js's catch block can book
    // what this run actually spent instead of the backend recording a $0 failure.
    const rejectOn = spec.rejectOn || 'error';
    const empty = finalTurns.length === 0;
    const unusable = empty || (!structured && !text);
    if (rejectOn !== 'never' && empty) {
      console.error(usageLine(finalTurns));
      throw Object.assign(new Error('opencode produced no result'), { turns: finalTurns });
    }
    if (rejectOn === 'error' && unusable) {
      console.error(usageLine(finalTurns));
      throw Object.assign(new Error('opencode produced no usable reply'), { turns: finalTurns });
    }

    // A schema miss that survived the retry is a FAILED run, not a thin one. Prose is truthy
    // `text`, so it clears `unusable` above and would otherwise resolve as success — and the
    // callers of a schema lane do not degrade gracefully on that: synthesize's extractBody falls
    // back to `{judge_prompt: <the prose>, rubric: ''}` and would ship that as a grader. Reject
    // here so the backend records a failure instead of persisting a plausible-looking artifact.
    // Only for rejectOn:'error'; 'no-result' (analyze) and 'never' (codegen) both have a
    // downstream that reads a partial run correctly.
    if (rejectOn === 'error' && spec.jsonSchema) {
      const missing = missingKeys(structured, spec.jsonSchema);
      if (!structured || missing.length) console.error(describeSchemaMiss(text, finalTurns, spec.jsonSchema));
      if (!structured) {
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
    }

    return { startMs, turns: finalTurns, resultRaw: toEnvelope(finalTurns, structured, text) };
  } finally {
    server.close();
  }
}

module.exports = {
  git,
  missingKeys,
  runAgent,
  scrubToken,
  describeError,
  splitModel,
  extractJson,
  quarantineRepo,
  sumUsage,
  WORK,
  REPO,
  AGENT_CWD,
};
