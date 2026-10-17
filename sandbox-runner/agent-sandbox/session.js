// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * One agent session: one `opencode serve`, one OpenCode session, many messages; the protocol is in
 * sandbox-runner/README.md, "Agent sessions". session.json is deleted once read so MCP tokens live only
 * in the relay's memory; the Docker image's execute-only node keeps that memory unreadable through
 * /proc (E2B cannot); SESSION_SECRET leaves process.env before any child is spawned.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const {
  startServer,
  makeFetch,
  splitModel,
  usageOf,
  sumUsage,
  scrubToken,
  basePermission,
  customAgent,
  remoteMcp,
  writeDossier,
  describeError,
  WORK,
} = require('./agent-stream');
const { startMcpRelay } = require('./mcp-relay');

const SESSION_AGENT = 'tessary-session';
const DEFAULT_PORT = 9400;
const SUMMARY_CHARS = 2000;
const DETAIL_CHARS = 500;
const REQUEST_MS = 60_000;
const CLOSE_GRACE_MS = 5_000;
const SETTLE_MS = 2_000;
const RESUBSCRIBE_MS = 250;
const MAX_BODY_BYTES = 1 << 20;
const MIN_SECRET_CHARS = 32;
const MAX_TOOL_CALLS = 1000;
const SERVER_NAME = /^[a-z][a-z0-9-]{0,31}$/;

function readSessionFile(file) {
  if (!file) throw new Error('session.json: no path given');
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } finally {
    fs.rmSync(file, { force: true });
  }
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    throw new Error('session.json: not valid JSON');
  }
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('session.json: not an object');
  const { system_prompt: systemPrompt, files = {}, model, mcp_servers: servers, max_tool_calls_per_message: maxToolCalls } = input;
  if (typeof systemPrompt !== 'string' || !systemPrompt) throw new Error('session.json: system_prompt must be a non-empty string');
  if (!files || typeof files !== 'object' || Array.isArray(files) || Object.values(files).some((c) => typeof c !== 'string')) {
    throw new Error('session.json: files must map relative paths to strings');
  }
  if (typeof model !== 'string' || !model) throw new Error('session.json: model must be a non-empty string');
  if (!Array.isArray(servers)) throw new Error('session.json: mcp_servers must be an array');
  const names = new Set();
  for (const server of servers) {
    if (!server || typeof server.name !== 'string' || !SERVER_NAME.test(server.name)) {
      throw new Error(`session.json: each mcp_servers name must match ${SERVER_NAME}`);
    }
    if (names.has(server.name)) throw new Error(`session.json: mcp_servers name repeated: ${server.name}`);
    if (typeof server.url !== 'string' || typeof server.token !== 'string') {
      throw new Error(`session.json: mcp_servers ${server.name} needs a url and a token`);
    }
    names.add(server.name);
  }
  if (!Number.isInteger(maxToolCalls) || maxToolCalls < 1 || maxToolCalls > MAX_TOOL_CALLS) {
    throw new Error(`session.json: max_tool_calls_per_message must be an integer from 1 to ${MAX_TOOL_CALLS}`);
  }
  return { systemPrompt, files, model, servers, maxToolCalls };
}

/** OpenCode names an MCP tool `<server>_<tool>`; server names hold no `_`, so the first one splits. */
function splitToolName(name, serverNames) {
  const i = name.indexOf('_');
  if (i > 0 && serverNames.has(name.slice(0, i))) return { server: name.slice(0, i), tool: name.slice(i + 1) };
  return { server: null, tool: name };
}

function toolStartOf(part, serverNames) {
  return {
    call_id: part.callID,
    ...splitToolName(String(part.tool || ''), serverNames),
    input_summary: scrubToken(JSON.stringify(part.state.input || {})).slice(0, SUMMARY_CHARS),
  };
}

function toolEndOf(part) {
  const { state } = part;
  const ok = state.status === 'completed';
  const settled = ok || state.status === 'error';
  const out = !settled ? 'Tool did not finish' : String((ok ? state.output : state.error) ?? '');
  const time = state.time || {};
  return {
    call_id: part.callID,
    result_summary: scrubToken(out).slice(0, SUMMARY_CHARS),
    bytes: Buffer.byteLength(out),
    duration_ms: settled && Number.isFinite(time.end) && Number.isFinite(time.start) ? time.end - time.start : 0,
    ok,
  };
}

/** Every event of the session, in emit order, as NDJSON lines; a reader starts at any index. */
function createEventLog() {
  const lines = [];
  const readers = new Set();
  let ended = false;
  return {
    emit(type, data) {
      const line = JSON.stringify({ type, data }) + '\n';
      lines.push(line);
      for (const res of readers) res.write(line);
    },
    size: () => lines.length,
    pipe(res, from) {
      res.writeHead(200, { 'content-type': 'application/x-ndjson', 'cache-control': 'no-store' });
      res.flushHeaders();
      for (let i = from; i < lines.length; i++) res.write(lines[i]);
      if (ended) {
        res.end();
        return;
      }
      readers.add(res);
      res.on('close', () => readers.delete(res));
    },
    endAll() {
      ended = true;
      for (const res of readers) res.end();
      readers.clear();
    },
  };
}

/**
 * Maps OpenCode's events for one reply onto the session's events. A reply is armed by the first
 * user message it has not seen before: OpenCode re-sends the previous user message and a second
 * idle pair after a reply ends, and either one must not end the next reply. A user message that is
 * new while the reply is armed is OpenCode's own (an automatic compaction and the message that
 * continues after it), because the script takes no second message while a reply is open, so the
 * assistant messages answering it are the reply's too. An idle ends the reply only once every
 * assistant message of it is complete, because after an abort or a provider error OpenCode reports
 * idle before it settles the running tool and the message.
 */
function createReplyTracker({ log, serverNames, steps, maxToolCalls }) {
  const userIds = new Set();
  let reply = null;

  function emitText(partId, text) {
    const done = reply.emitted.get(partId) || 0;
    if (text.length <= done) return;
    log.emit('text', { delta: text.slice(done) });
    reply.emitted.set(partId, text.length);
  }

  /** True when this part's start takes the reply past its tool cap. */
  function emitTool(part) {
    const status = part.state && part.state.status;
    let overCap = false;
    if (!reply.started.has(part.callID) && (status === 'running' || status === 'completed' || status === 'error')) {
      reply.started.add(part.callID);
      log.emit('tool_start', toolStartOf(part, serverNames));
      if (reply.started.size > maxToolCalls && !reply.limitHit) {
        reply.limitHit = true;
        overCap = true;
      }
    }
    if (!reply.ended.has(part.callID) && (status === 'completed' || status === 'error')) {
      reply.ended.add(part.callID);
      log.emit('tool_end', toolEndOf(part));
    }
    return overCap;
  }

  function onUser(info) {
    if (reply && !userIds.has(info.id)) {
      if (!reply.armed) {
        reply.armed = true;
        reply.userId = info.id;
      }
      reply.parents.add(info.id);
    }
    userIds.add(info.id);
  }

  function onAssistant(info) {
    if (!reply || !reply.armed || !reply.parents.has(info.parentID)) return;
    reply.assistants.set(info.id, Boolean(info.time && info.time.completed));
    if (info.summary) reply.summaries.add(info.id);
  }

  const ofReply = (messageId) => reply !== null && reply.armed && reply.assistants.has(messageId);
  const spokenBy = (messageId) => ofReply(messageId) && !reply.summaries.has(messageId);

  return {
    get open() {
      return reply !== null;
    },
    get armed() {
      return reply !== null && reply.armed;
    },
    begin() {
      reply = {
        armed: false,
        userId: null,
        parents: new Set(),
        assistants: new Map(),
        summaries: new Set(),
        partTypes: new Map(),
        emitted: new Map(),
        finalParts: new Set(),
        started: new Set(),
        ended: new Set(),
        abortRequested: false,
        limitHit: false,
      };
    },
    cancel() {
      reply = null;
    },
    requestAbort() {
      if (reply) reply.abortRequested = true;
    },
    /** Takes in stored messages, in order, whose events may have been lost in a reconnect. */
    adopt(messages) {
      for (const m of messages) {
        const info = (m && m.info) || {};
        if (info.role === 'user') onUser(info);
        else if (info.role === 'assistant') onAssistant(info);
      }
    },
    /**
     * 'end' when the reply is over, 'settle' when OpenCode is idle but a message is still open,
     * 'limit' when a tool call takes the reply past its cap.
     */
    onEvent(event) {
      const p = event.properties || {};
      switch (event.type) {
        case 'message.updated': {
          const info = p.info || {};
          if (info.role === 'user') onUser(info);
          else if (info.role === 'assistant') onAssistant(info);
          return null;
        }
        case 'message.part.updated': {
          const part = p.part || {};
          if (!ofReply(part.messageID)) return null;
          reply.partTypes.set(part.id, part.type);
          if (
            part.type === 'text' && !part.synthetic && part.time && part.time.end &&
            spokenBy(part.messageID) && !reply.finalParts.has(part.id)
          ) {
            emitText(part.id, String(part.text || ''));
            reply.finalParts.add(part.id);
          }
          if (part.type === 'tool' && emitTool(part)) return 'limit';
          return null;
        }
        case 'message.part.delta': {
          if (p.field !== 'text' || !spokenBy(p.messageID)) return null;
          if (reply.partTypes.get(p.partID) !== 'text' || reply.finalParts.has(p.partID)) return null;
          const delta = String(p.delta || '');
          if (!delta) return null;
          log.emit('text', { delta });
          reply.emitted.set(p.partID, (reply.emitted.get(p.partID) || 0) + delta.length);
          return null;
        }
        case 'session.error': {
          const error = p.error || {};
          if (!reply || error.name === 'MessageAbortedError') return null;
          const message = error.data && error.data.message;
          log.emit('error', { kind: 'agent', detail: scrubToken(`${error.name}: ${message}`).slice(0, DETAIL_CHARS) });
          return null;
        }
        case 'session.status':
        case 'session.idle': {
          if (event.type === 'session.status' && !(p.status && p.status.type === 'idle')) return null;
          if (!reply || !reply.armed) return null;
          return [...reply.assistants.values()].every(Boolean) ? 'end' : 'settle';
        }
      }
    },
    /** Reconcile against the stored messages, then end the reply with its summed usage. */
    finish(messages) {
      this.adopt(messages);
      const own = messages.filter((m) => m && m.info && m.info.role === 'assistant' && reply.parents.has(m.info.parentID));
      for (const m of own) {
        const parts = m.parts || [];
        if (!m.info.summary) {
          for (const part of parts) if (part.type === 'text' && !part.synthetic) emitText(part.id, String(part.text || ''));
        }
        for (const part of parts) {
          if (part.type !== 'tool') continue;
          emitTool(part);
          if (reply.started.has(part.callID) && !reply.ended.has(part.callID)) {
            reply.ended.add(part.callID);
            log.emit('tool_end', toolEndOf(part));
          }
        }
      }
      const usage = sumUsage(own.map((m) => ({ usage: usageOf(m.info) })));
      const stepsTaken = own.filter((m) => !m.info.summary).length;
      const reason = reply.limitHit ? 'limit' : reply.abortRequested ? 'aborted' : stepsTaken >= steps ? 'limit' : 'done';
      log.emit('message_end', { usage, reason });
      reply = null;
    },
  };
}

function readBody(req, limit) {
  return new Promise((resolve) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size <= limit) chunks.push(c);
    });
    req.on('end', () => resolve(size <= limit ? Buffer.concat(chunks).toString('utf8') : null));
    req.on('error', () => resolve(null));
  });
}

function createHttpServer({ secret, state, log, onMessage, onAbort }) {
  const expected = Buffer.from(`Bearer ${secret}`);
  const authorized = (req) => {
    const got = Buffer.from(String(req.headers.authorization || ''));
    return got.length === expected.length && crypto.timingSafeEqual(got, expected);
  };
  return http.createServer((req, res) => {
    const send = (status, body) => {
      if (body === undefined) {
        res.writeHead(status).end();
        return;
      }
      res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
    };
    if (!authorized(req)) {
      req.resume();
      send(401, { kind: 'unauthorized' });
      return;
    }
    const url = new URL(req.url, 'http://session');
    const route = `${req.method} ${url.pathname}`;
    if (route === 'POST /message') {
      readBody(req, MAX_BODY_BYTES)
        .then(async (raw) => {
          if (state.phase !== 'ready') return send(503, { kind: state.phase });
          let text = null;
          try {
            text = raw === null ? null : JSON.parse(raw).text;
          } catch {
            text = null;
          }
          if (typeof text !== 'string' || !text) return send(400, { kind: 'bad_request' });
          const answer = await onMessage(text);
          return send(answer.status, answer.body);
        });
      return;
    }
    req.resume();
    if (route === 'GET /healthz') {
      send(state.phase === 'ready' ? 200 : 503, { state: state.phase });
      return;
    }
    if (route === 'GET /events') {
      const raw = url.searchParams.get('from');
      const from = raw === null ? 0 : /^\d+$/.test(raw) ? Number(raw) : NaN;
      if (!Number.isSafeInteger(from) || from > log.size()) {
        send(400, { kind: 'bad_request' });
        return;
      }
      log.pipe(res, from);
      return;
    }
    if (route === 'POST /abort') {
      onAbort().then((answer) => send(answer.status, answer.body));
      return;
    }
    send(404, { kind: 'not_found' });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const sessionOf = (event) => {
  const p = event.properties || {};
  return p.sessionID || (p.info && p.info.sessionID) || (p.part && p.part.sessionID) || null;
};

async function main() {
  const secret = process.env.SESSION_SECRET || '';
  delete process.env.SESSION_SECRET;
  if (secret.length < MIN_SECRET_CHARS) {
    process.stderr.write('SESSION_SECRET is required\n');
    process.exit(2);
  }

  let loaded;
  try {
    loaded = { input: readSessionFile(process.argv[2]) };
  } catch (e) {
    loaded = { error: e };
  }

  const log = createEventLog();
  const state = { phase: 'starting' };
  const subscription = new AbortController();
  let relay = null;
  let server = null;
  let client = null;
  let tracker = null;
  let sessionId = null;
  let model = null;
  let stopping = false;
  let lost = false;
  let settleTimer = null;
  let finishing = null;
  let onConnected = () => {};
  const connected = new Promise((resolve) => {
    onConnected = resolve;
  });

  async function closeAll() {
    subscription.abort();
    clearTimeout(settleTimer);
    try {
      if (server) await server.close();
    } finally {
      if (relay) relay.close();
    }
  }

  function failSession(kind, detail) {
    if (stopping || state.phase === 'failed') return;
    state.phase = 'failed';
    log.emit('error', { kind, detail: scrubToken(detail).slice(0, DETAIL_CHARS) });
    log.emit('closed', { reason: 'failed' });
    log.endAll();
    void closeAll();
    setTimeout(() => process.exit(1), CLOSE_GRACE_MS);
  }

  function finishReply() {
    if (finishing) return finishing;
    clearTimeout(settleTimer);
    settleTimer = null;
    finishing = (async () => {
      let messages = [];
      try {
        const listed = await client.session.messages({ path: { id: sessionId } });
        messages = Array.isArray(listed.data) ? listed.data : [];
      } catch (e) {
        console.error(`session messages unread at reply end: ${describeError(e)}`);
      }
      if (tracker.open) tracker.finish(messages);
    })().finally(() => {
      finishing = null;
    });
    return finishing;
  }

  /**
   * After a reconnect, a reply whose user message was lost is armed from the stored messages; a
   * reply whose idle was lost ends here.
   */
  async function recoverReply() {
    if (!tracker.open) return;
    try {
      if (!tracker.armed) {
        const listed = await client.session.messages({ path: { id: sessionId } });
        tracker.adopt(Array.isArray(listed.data) ? listed.data : []);
        if (!tracker.armed) return;
      }
      const { data } = await client.session.status();
      const status = data && data[sessionId];
      if (!status || status.type === 'idle') await finishReply();
    } catch (e) {
      console.error(`reply not recovered after a reconnect: ${describeError(e)}`);
    }
  }

  async function abortAtCap() {
    try {
      await client.session.abort({ path: { id: sessionId } });
    } catch (e) {
      console.error(`reply not aborted at its tool cap: ${describeError(e)}`);
    }
  }

  async function onOpencodeEvent(event) {
    if (!event || typeof event !== 'object') return;
    if (event.type === 'server.connected') {
      onConnected();
      if (lost) {
        lost = false;
        await recoverReply();
      }
      return;
    }
    if (!sessionId || sessionOf(event) !== sessionId) return;
    const signal = tracker.onEvent(event);
    if (signal === 'end') await finishReply();
    else if (signal === 'settle' && !settleTimer) settleTimer = setTimeout(() => void finishReply(), SETTLE_MS);
    else if (signal === 'limit') void abortAtCap();
  }

  async function runEventLoop() {
    while (!subscription.signal.aborted) {
      // The SDK's stream retries its own errors and ends on an abort, so nothing here throws.
      const { stream } = await client.event.subscribe({
        signal: subscription.signal,
        sseDefaultRetryDelay: RESUBSCRIBE_MS,
        onSseError: () => {
          lost = true;
        },
      });
      for await (const event of stream) await onOpencodeEvent(event);
      lost = true;
      if (!subscription.signal.aborted) await sleep(RESUBSCRIBE_MS);
    }
  }

  async function onMessage(text) {
    if (tracker.open) return { status: 409, body: { kind: 'busy' } };
    tracker.begin();
    try {
      await client.session.promptAsync({
        path: { id: sessionId },
        body: { agent: SESSION_AGENT, model, parts: [{ type: 'text', text }] },
      });
      return { status: 202 };
    } catch (e) {
      tracker.cancel();
      return { status: 502, body: { kind: 'prompt', detail: describeError(e).slice(0, DETAIL_CHARS) } };
    }
  }

  async function onAbort() {
    if (!tracker || !tracker.open) return { status: 204 };
    tracker.requestAbort();
    try {
      await client.session.abort({ path: { id: sessionId } });
      return { status: 204 };
    } catch (e) {
      return { status: 502, body: { kind: 'abort', detail: describeError(e).slice(0, DETAIL_CHARS) } };
    }
  }

  const httpServer = createHttpServer({ secret, state, log, onMessage, onAbort });
  await new Promise((resolve, reject) => {
    httpServer.once('error', reject);
    httpServer.listen(Number(process.env.SESSION_PORT || DEFAULT_PORT), '0.0.0.0', resolve);
  });

  async function shutdown() {
    if (stopping) return;
    stopping = true;
    await closeAll();
    log.endAll();
    httpServer.closeAllConnections();
    httpServer.close();
    process.exit(0);
  }
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);

  try {
    if (loaded.error) throw loaded.error;
    const { input } = loaded;
    writeDossier(path.join(WORK, 'dossier'), input.files);
    relay = await startMcpRelay({ servers: input.servers, workDir: WORK });
    const steps = input.maxToolCalls + 1;
    server = await startServer({
      permission: basePermission({ edit: { '*': 'allow' } }, { scratchDirs: true }),
      lsp: {},
      mcp: remoteMcp(relay.urls),
      agent: { [SESSION_AGENT]: customAgent({ prompt: input.systemPrompt, steps }) },
    });
    const { createOpencodeClient } = await import('@opencode-ai/sdk');
    client = createOpencodeClient({ baseUrl: server.url, fetch: makeFetch(REQUEST_MS), throwOnError: true });
    tracker = createReplyTracker({ log, serverNames: new Set(input.servers.map((s) => s.name)), steps, maxToolCalls: input.maxToolCalls });
    void runEventLoop();
    await Promise.race([
      connected,
      server.exited.then(() => {
        throw new Error('opencode exited before its event stream connected');
      }),
      sleep(REQUEST_MS).then(() => {
        throw new Error(`opencode event stream not connected after ${REQUEST_MS}ms`);
      }),
    ]);
    const created = await client.session.create({ body: { title: SESSION_AGENT } });
    sessionId = created.data.id;
    model = splitModel(input.model);
    state.phase = 'ready';
    server.exited.then(({ code, signal }) => failSession('opencode_exit', signal ? `signal ${signal}` : `exit code ${code}`));
  } catch (e) {
    failSession('start', describeError(e));
  }
}

// A rejection here (the port is taken) exits 1 with the error on stderr, as Node does by default.
main();
