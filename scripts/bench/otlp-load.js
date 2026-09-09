// SPDX-License-Identifier: Apache-2.0
// Open-loop OTLP/HTTP load generator for the ingest front door.
//
// Open-loop on purpose: a closed-loop generator (N workers, each waiting for its response) slows
// itself down exactly when the server slows down, so it can never observe a rejection rate at a
// stated offered load. This one fires at a fixed arrival rate regardless of what comes back, which
// is the only way "what does it take without rejecting" has a denominator.
//
// Protobuf is hand-encoded (same approach as scripts/emit-span.js) so this depends on nothing.
//
//   node otlp-load.js --url http://localhost:8000/v1/traces --token tsy_... \
//     --rate 400 --spans-per-request 40 --payload-bytes 2048 --duration 120 --out run.jsonl
"use strict";
const http = require("node:http");
const https = require("node:https");
const crypto = require("node:crypto");
const fs = require("node:fs");

function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? def : process.argv[i + 1];
}

const URL_ = arg("url", "http://localhost:8000/v1/traces");
const TOKEN = arg("token");
// A list of tokens, one per line, round-robined per request. Several projects is the only way to see
// what a per-project-partitioned drain does: one project is pinned to one drainer by construction.
const TOKENS_FILE = arg("tokens");
const TOKENS = TOKENS_FILE
  ? require("node:fs").readFileSync(TOKENS_FILE, "utf8").split("\n").map((t) => t.trim()).filter(Boolean)
  : null;
let tokenCursor = 0;
const nextToken = () => (TOKENS ? TOKENS[tokenCursor++ % TOKENS.length] : TOKEN);
const RATE = Number(arg("rate", 200)); // spans per second offered
const SPANS_PER_REQ = Number(arg("spans-per-request", 40));
const SPANS_PER_TRACE = Number(arg("spans-per-trace", 4));
const PAYLOAD_BYTES = Number(arg("payload-bytes", 2048)); // per span, for EACH of input and output
const DURATION = Number(arg("duration", 60));
const WARMUP = Number(arg("warmup", 0));
const OUT = arg("out");
const LABEL = arg("label", "");
// Spike shape: after --spike-at seconds, multiply the rate by --spike-x for --spike-for seconds.
const SPIKE_AT = Number(arg("spike-at", 0));
const SPIKE_X = Number(arg("spike-x", 1));
const SPIKE_FOR = Number(arg("spike-for", 0));
const MAX_INFLIGHT = Number(arg("max-inflight", 512));

if (!TOKEN && !TOKENS) {
  console.error("usage: node otlp-load.js --token <tsy_...> [--url ...] [--rate N] ...");
  process.exit(2);
}

// ---------------------------------------------------------------- protobuf
function varint(n) {
  const out = [];
  let v = BigInt(n);
  while (v >= 0x80n) { out.push(Number((v & 0x7fn) | 0x80n)); v >>= 7n; }
  out.push(Number(v));
  return Buffer.from(out);
}
const tag = (field, wire) => varint((field << 3) | wire);
const bytesField = (field, buf) => Buffer.concat([tag(field, 2), varint(buf.length), buf]);
const stringField = (field, s) => bytesField(field, Buffer.from(s, "utf8"));
const varintField = (field, n) => Buffer.concat([tag(field, 0), varint(n)]);
function fixed64Field(field, n) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(BigInt(n));
  return Buffer.concat([tag(field, 1), b]);
}
const kv = (key, value) => Buffer.concat([
  stringField(1, key),
  bytesField(2, typeof value === "number" ? varintField(3, value) : stringField(1, String(value))),
]);

// ---------------------------------------------------------------- payload
// A gen_ai.input.messages / gen_ai.output.messages pair of the requested size. The mapper reads
// these as BOTH the messages JSON and the flat input/output text, so the spool's byte accounting
// sees each of them twice — which is what a real chat span does too.
const FILLER = "the quick brown fox jumps over the lazy dog. ";
function messagesJson(role, bytes) {
  let body = "";
  while (body.length < bytes) body += FILLER;
  return JSON.stringify([{ role, parts: [{ type: "text", content: body.slice(0, bytes) }] }]);
}
const INPUT_MSG = messagesJson("user", PAYLOAD_BYTES);
const OUTPUT_MSG = messagesJson("assistant", PAYLOAD_BYTES);

// Ids must be unique or the idempotent write path skips the insert with ON CONFLICT DO NOTHING and
// the measurement reports a no-op as throughput. Counter-based, with a per-process random prefix.
const ID_PREFIX = crypto.randomBytes(8);
let idCounter = 0n;
function id(n) {
  const b = Buffer.alloc(n);
  ID_PREFIX.copy(b, 0, 0, Math.min(8, n));
  b.writeBigUInt64BE(++idCounter, Math.max(0, n - 8));
  return b;
}

function buildBody(spans) {
  const parts = [];
  let traceId = id(16);
  for (let i = 0; i < spans; i++) {
    if (i % SPANS_PER_TRACE === 0) traceId = id(16);
    const nowNs = BigInt(Date.now()) * 1000000n;
    parts.push(bytesField(2, Buffer.concat([
      bytesField(1, traceId),
      bytesField(2, id(8)),
      stringField(5, "chat gpt-4o-mini"),
      varintField(6, 3),
      fixed64Field(7, nowNs - 1200000000n),
      fixed64Field(8, nowNs),
      bytesField(9, kv("tessary.call_site.id", "bench")),
      bytesField(9, kv("gen_ai.operation.name", "chat")),
      bytesField(9, kv("gen_ai.system", "openai")),
      bytesField(9, kv("gen_ai.request.model", "gpt-4o-mini")),
      bytesField(9, kv("gen_ai.usage.input_tokens", 512)),
      bytesField(9, kv("gen_ai.usage.output_tokens", 256)),
      bytesField(9, kv("gen_ai.input.messages", INPUT_MSG)),
      bytesField(9, kv("gen_ai.output.messages", OUTPUT_MSG)),
    ])));
  }
  return bytesField(1, Buffer.concat([
    bytesField(1, bytesField(1, kv("service.name", "tessary-ingest-bench"))),
    bytesField(2, Buffer.concat([bytesField(1, stringField(1, "tessary-bench")), ...parts])),
  ]));
}

// ---------------------------------------------------------------- transport
const url = new URL(URL_);
const client = url.protocol === "https:" ? https : http;
const agent = new client.Agent({ keepAlive: true, maxSockets: MAX_INFLIGHT, maxFreeSockets: 256 });

const buckets = new Map(); // second -> counters
const OTHER_CODES = {};
let FIRST_OTHER = null;
let inflight = 0;
function bucket(sec) {
  let b = buckets.get(sec);
  if (!b) {
    b = { sec, sent: 0, spans_sent: 0, ok: 0, refused: 0, too_large: 0, other: 0, error: 0, skipped: 0, lat: [] };
    buckets.set(sec, b);
  }
  return b;
}

const t0 = Date.now();
const elapsed = () => (Date.now() - t0) / 1000;

function send(spans) {
  const sec = Math.floor(elapsed());
  const b = bucket(sec);
  if (inflight >= MAX_INFLIGHT) { b.skipped++; return; }
  const body = buildBody(spans);
  b.sent++;
  b.spans_sent += spans;
  inflight++;
  const started = process.hrtime.bigint();
  const req = client.request(url, {
    method: "POST",
    agent,
    headers: {
      "Content-Type": "application/x-protobuf",
      Authorization: `Bearer ${nextToken()}`,
      "Content-Length": body.length,
    },
  }, (res) => {
    let bodyText = "";
    res.on("data", (c) => { if (bodyText.length < 400) bodyText += c.toString("utf8"); });
    res.on("end", () => {
      inflight--;
      const ms = Number(process.hrtime.bigint() - started) / 1e6;
      const rb = bucket(Math.floor(elapsed()));
      rb.lat.push(ms);
      if (res.statusCode === 200) b.ok++;
      else if (res.statusCode === 503) b.refused++;
      else if (res.statusCode === 413) b.too_large++;
      else { b.other++; OTHER_CODES[res.statusCode] = (OTHER_CODES[res.statusCode] || 0) + 1; if (!FIRST_OTHER) { FIRST_OTHER = { code: res.statusCode, body: bodyText.slice(0, 400) }; } }
    });
  });
  req.on("error", () => { inflight--; b.error++; });
  req.end(body);
}

// Fixed arrival rate, driven off a 20 ms tick with fractional carry so a rate that is not a whole
// number of requests per tick still averages out rather than rounding to zero.
const TICK_MS = 20;
let carry = 0;
function currentRate() {
  const e = elapsed();
  const inSpike = SPIKE_FOR > 0 && e >= SPIKE_AT && e < SPIKE_AT + SPIKE_FOR;
  return inSpike ? RATE * SPIKE_X : RATE;
}

const timer = setInterval(() => {
  const e = elapsed();
  if (e >= DURATION) { finish(); return; }
  carry += (currentRate() / SPANS_PER_REQ) * (TICK_MS / 1000);
  while (carry >= 1) { send(SPANS_PER_REQ); carry -= 1; }
}, TICK_MS);

function pct(sorted, p) {
  if (!sorted.length) return null;
  return Math.round(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] * 10) / 10;
}

function finish() {
  clearInterval(timer);
  // Let the last responses land before summarizing.
  setTimeout(() => {
    const rows = [...buckets.values()].sort((a, b) => a.sec - b.sec).filter((r) => r.sec >= WARMUP);
    const agg = { label: LABEL, offered_rate: RATE, spans_per_request: SPANS_PER_REQ, payload_bytes: PAYLOAD_BYTES,
      duration_s: DURATION, warmup_s: WARMUP, spike: SPIKE_FOR > 0 ? { at: SPIKE_AT, x: SPIKE_X, for: SPIKE_FOR } : null,
      sent: 0, spans_sent: 0, ok: 0, refused: 0, too_large: 0, other: 0, error: 0, skipped: 0 };
    let lat = [];
    for (const r of rows) {
      for (const k of ["sent", "spans_sent", "ok", "refused", "too_large", "other", "error", "skipped"]) agg[k] += r[k];
      lat = lat.concat(r.lat);
    }
    lat.sort((a, b) => a - b);
    agg.other_codes = OTHER_CODES;
    agg.first_other = FIRST_OTHER;
    agg.latency_ms = { p50: pct(lat, 0.5), p95: pct(lat, 0.95), p99: pct(lat, 0.99), max: pct(lat, 0.999999) };
    agg.measured_span_rate = Math.round((agg.spans_sent / Math.max(1, rows.length)) * 10) / 10;
    agg.accepted_span_rate = Math.round((agg.spans_sent * (agg.ok / Math.max(1, agg.sent)) / Math.max(1, rows.length)) * 10) / 10;
    agg.rejection_rate = agg.sent ? Math.round(((agg.refused + agg.other + agg.error) / agg.sent) * 10000) / 10000 : 0;
    if (OUT) {
      fs.writeFileSync(OUT, rows.map((r) => JSON.stringify({ ...r, lat: undefined, lat_p95: pct([...r.lat].sort((a, b) => a - b), 0.95) })).join("\n") + "\n");
      fs.writeFileSync(OUT.replace(/\.jsonl$/, "") + ".summary.json", JSON.stringify(agg, null, 2) + "\n");
    }
    console.log(JSON.stringify(agg));
    process.exit(0);
  }, 3000);
}
