// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Standalone encoder classification service — the /classify head serving extracted from the
 * sandbox-runner launcher into its own deployable (ECS Fargate in production) so CPU inference
 * can never starve the web stack again (see the 2026-07-12 OOM incident).
 *
 * Endpoints (Bearer-authed with CLASSIFY_API_KEY):
 *   POST /classify { head, texts: [".."] }     -> { scores: [0..1] }              (see classify.js)
 *   POST /embed    { checkpoint, texts: [".."] } -> { vectors, dim, checkpoint }  (see embed.js)
 *   GET  /healthz                              -> 200 { ok: true }    (no auth — ECS health checks)
 *
 * Env:
 *   PORT               (default 8080)
 *   CLASSIFY_API_KEY   shared secret the backend must present (required to serve /classify)
 *   MAX_INFLIGHT       concurrent /classify requests running at once (default 2) — the
 *                      memory-safety ceiling; CPU-bound inference past this only grows memory.
 *   MAX_QUEUE          requests allowed to WAIT for a slot before 429 (default 8). Over the
 *                      concurrency ceiling a request queues (smoothing sweep bursts) instead of
 *                      failing outright; past the queue it's 429 and the backend retries.
 *   QUEUE_TIMEOUT_MS   max time a request waits in the queue before 429 (default 20000) — a slow
 *                      burst fails fast rather than holding the socket indefinitely.
 *   HF_CACHE_DIR       model weight cache — baked into the image at build time (warmup.js),
 *                      so startup needs no network fetch and scale-out is fast.
 */
const http = require('node:http');
const { classify, warmAll } = require('./classify');
const { embed, warmAllEmbedders } = require('./embed');
const { ConcurrencyGate } = require('./queue');

// A finite env int, else the default — so a malformed env var (NaN) can't produce an unbounded
// queue or a nonsense limit, while a legitimate 0 (e.g. MAX_QUEUE=0 to restore reject-at-capacity)
// is still honored.
const envInt = (name, def) => {
  const n = Number(process.env[name]);
  return Number.isFinite(n) ? n : def;
};

const PORT = envInt('PORT', 8080);
const CLASSIFY_API_KEY = process.env.CLASSIFY_API_KEY || '';
const MAX_INFLIGHT = envInt('MAX_INFLIGHT', 2);
const MAX_QUEUE = envInt('MAX_QUEUE', 8);
const QUEUE_TIMEOUT_MS = envInt('QUEUE_TIMEOUT_MS', 20000);
const MAX_BODY_BYTES = 8 * 1024 * 1024; // same request-body contract as the launcher

// Bounded concurrency: MAX_INFLIGHT run at once (memory ceiling), extras wait in a FIFO queue
// rather than failing immediately; 429 only when the queue is full or a waiter times out.
const gate = new ConcurrencyGate({ maxInflight: MAX_INFLIGHT, maxQueue: MAX_QUEUE, timeoutMs: QUEUE_TIMEOUT_MS });

// Pre-warm every head (and every embed checkpoint) sequentially at boot, and gate
// /healthz on it: ECS marks the container healthy only once all models are resident, so
// Cloud Map never routes a sweep burst to a cold task. Lazy loading during a burst is
// what OOM-killed the first production task (2026-07-12): concurrent ONNX session builds
// spike transient memory. Requests that do arrive pre-warm still work — loads are
// serialized in classify.js / embed.js.
let warm = false;
warmAll()
  .then(() => warmAllEmbedders())
  .then(() => {
    warm = true;
    console.log('classify-service warm: all heads resident');
  })
  .catch((e) => {
    console.error('pre-warm failed, exiting so the orchestrator replaces the task:', e);
    process.exit(1);
  });

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) });
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let over = false;
    req.on('data', (c) => {
      size += c.length;
      if (over) {
        // Already over cap: drain and discard so the 413 can be delivered on a live
        // socket (destroying mid-upload turns the response into ECONNRESET at the
        // client), but hard-abort a body so oversized it's clearly abuse.
        if (size > 4 * MAX_BODY_BYTES) req.destroy();
        return;
      }
      if (size > MAX_BODY_BYTES) {
        over = true;
        chunks.length = 0;
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (over) reject(Object.assign(new Error('request body too large'), { statusCode: 413 }));
      else resolve(Buffer.concat(chunks));
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/healthz') {
    return warm ? send(res, 200, { ok: true }) : send(res, 503, { ok: false, warming: true });
  }
  const isClassify = req.method === 'POST' && req.url === '/classify';
  const isEmbed = req.method === 'POST' && req.url === '/embed';
  if (!isClassify && !isEmbed) return send(res, 404, { error: 'not found' });

  const auth = req.headers['authorization'] || '';
  if (!CLASSIFY_API_KEY || auth !== `Bearer ${CLASSIFY_API_KEY}`) {
    return send(res, 401, { error: 'unauthorized' });
  }

  // Acquire a concurrency slot — waits in the bounded queue if at the ceiling; 429 when
  // saturated. /embed sits behind the SAME gate as /classify: both are CPU inference in
  // this one memory envelope, so a shared ceiling is the invariant, not a per-route one.
  let release;
  try {
    release = await gate.acquire();
  } catch (e) {
    return send(res, 429, { error: e.reason || 'at capacity, retry later' });
  }
  try {
    let payload;
    try {
      payload = JSON.parse((await readBody(req)).toString('utf8'));
    } catch (e) {
      return send(res, e.statusCode || 400, { error: e.statusCode ? e.message : 'invalid JSON body' });
    }
    try {
      return send(res, 200, isEmbed ? await embed(payload) : await classify(payload));
    } catch (e) {
      // A 400-worthy client error (bad shape, unknown checkpoint, oversized batch)
      // carries a statusCode + a safe message; return it verbatim so the caller can fix
      // the request instead of retrying a permanent failure forever.
      if (e.statusCode === 400) return send(res, 400, { error: e.message });
      console.error(`${isEmbed ? 'embed' : 'classify'} failed:`, e);
      // Same loud-failure contract as the launcher: the backend fails the sweep and
      // retries on subsequent heartbeats rather than silently scoring clean.
      return send(res, 502, { error: isEmbed ? 'embedding failed' : 'classification failed' });
    }
  } finally {
    release();
  }
});

server.listen(PORT, () =>
  console.log(`classify-service listening on :${PORT} (max inflight ${MAX_INFLIGHT}, queue ${MAX_QUEUE})`),
);

