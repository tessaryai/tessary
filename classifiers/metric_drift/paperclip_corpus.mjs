// SPDX-License-Identifier: Apache-2.0
// A corpus built from the local Paperclip run logs, for when the platform's own database is out of
// reach — PLAN.md §9's A1 with an asterisk, and the asterisk is the whole reason this file carries a
// header instead of a one-line comment.
//
// WHAT THIS IS. Paperclip's `evals-tracer` is what produced paperclip's production traces: it reads
// each agent run's `claude` stream-json log and builds the `gen_ai.*` span tree that gets POSTed to
// /v1/traces. Those logs are on this machine. So this script drives THE TRACER'S OWN BUILDER over
// them — `parseRunLog` + `enrichRun` + `buildRunTrace`, imported, not reimplemented — and maps the
// resulting spans into the JSONL `corpus.load_turns_jsonl` reads. The durations, the models, the
// token counts and the call sites are the real ones, from the real runs.
//
// WHAT THIS IS NOT, and this bounds what may be concluded from it. The span tree is the INPUT to
// ingest, not its output. Between here and the rows `MetricSourceRepository` reads sit a set of
// decisions this script stands in for rather than reproduces:
//
//   - `call_site_id` is taken from the tracer's `tessary.call_site.id` attribute (the agent slug).
//     Ingest resolves the platform's own call site, and the bucket key IS the population — so a
//     bucket here is a paperclip agent, which is close to but not identical with what the sweep
//     buckets on.
//   - `tool_call` normalization, `StructuralEnricher`, `context` nesting and late-parent relinking
//     (0035) all happen after this point and none of them happens here.
//   - Every run is its own trace, so the turn population is agent runs rather than turns within a
//     conversation.
//
// Consequently: **this corpus can validate the harness and give an indicative curve. It cannot set
// the operating point of record.** That still wants `export_corpus.py` against the platform's own
// substrate, or these same spans replayed through a local ingest. The runs print a banner saying so.
//
// Usage, from `classifiers/`:
//   node metric_drift/paperclip_corpus.mjs --limit 4000 --out data/metric_drift/paperclip.jsonl

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createRequire } from 'node:module';

const TRACER = process.env.PAPERCLIP_TRACER_DIR
  || path.join(os.homedir(), 'Developer', 'paperclip', 'evals-tracer');

// The tracer's own `.mjs` sources import bare OTel specifiers and resolve them from their own
// directory, so they need nothing here. This script does: it lives in a different package, so its
// two OTel imports go through a require rooted at the tracer's manifest rather than at ours. There
// is deliberately no copy of these packages on this side — the provider that collects the spans has
// to be the one the builder was written against.
const require = createRequire(path.join(TRACER, 'package.json'));
const { BasicTracerProvider, SimpleSpanProcessor } = require('@opentelemetry/sdk-trace-base');
const { resourceFromAttributes } = require('@opentelemetry/resources');
const { loadConfig } = await import(path.join(TRACER, 'src/config.mjs'));
const { discoverRuns } = await import(path.join(TRACER, 'src/discover.mjs'));
const { makeClient, enrichRun, PaperclipUnreachable } = await import(path.join(TRACER, 'src/paperclip.mjs'));
const { parseRunLog, conformanceIssues } = await import(path.join(TRACER, 'src/parse.mjs'));
const { makeIdGenerator } = await import(path.join(TRACER, 'src/ids.mjs'));
const { buildRunTrace } = await import(path.join(TRACER, 'src/build.mjs'));

// `index.mjs`'s set verbatim — the tracer never traces a live run, and a corpus that included one
// would carry a truncated trajectory as if it were a fast one.
const TERMINAL = new Set(['succeeded', 'failed', 'cancelled']);

function parseArgs(argv) {
  const a = { limit: 2000, out: 'data/metric_drift/paperclip.jsonl' };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--limit') a.limit = Number(argv[++i]);
    else if (argv[i] === '--out') a.out = argv[++i];
  }
  return a;
}

const iso = (hrTime) => new Date(hrTime[0] * 1000 + hrTime[1] / 1e6).toISOString();
const ms = (span) =>
  (span.endTime[0] - span.startTime[0]) * 1000 + (span.endTime[1] - span.startTime[1]) / 1e6;

// `StructuralEnricher.usageJson`'s mapping, reproduced: ingest does NOT carry the `gen_ai.usage.*`
// attribute names into the `usage` jsonb column, it renames them to the bare Anthropic-shaped keys
// TokenUsage parses, and computes `total_tokens` when the source omits it.
//
// This is copied rather than invented, and it is worth saying why the first version of this file got
// it wrong in a way that cost nothing to find and would have cost everything to miss: shipping the
// `gen_ai.usage.*` names through meant `TokenUsage` matched none of them, every turn priced at zero,
// and all 3,864 cost samples landed in the sketch's underflow bin. The cost run was silent — not
// because the corpus was quiet, but because it was measuring nothing. The values are passed through
// untouched; only the names change, exactly as ingest changes them.
const USAGE_NAMES = [
  ['gen_ai.usage.input_tokens', 'input_tokens'],
  ['gen_ai.usage.output_tokens', 'output_tokens'],
  ['gen_ai.usage.cache_read.input_tokens', 'cache_read_input_tokens'],
  ['gen_ai.usage.cache_creation.input_tokens', 'cache_creation_input_tokens'],
];

function usageOf(attrs) {
  const usage = {};
  for (const [from, to] of USAGE_NAMES) if (attrs[from] !== undefined) usage[to] = attrs[from];
  if (!Object.keys(usage).length) return null;
  // Key order matches usageJson's insertion order, total_tokens third — cosmetic for the parser,
  // but it makes a corpus row and a production row diffable by eye.
  const total = (usage.input_tokens ?? 0) + (usage.output_tokens ?? 0);
  return {
    ...(usage.input_tokens !== undefined ? { input_tokens: usage.input_tokens } : {}),
    ...(usage.output_tokens !== undefined ? { output_tokens: usage.output_tokens } : {}),
    total_tokens: total,
    ...(usage.cache_read_input_tokens !== undefined ? { cache_read_input_tokens: usage.cache_read_input_tokens } : {}),
    ...(usage.cache_creation_input_tokens !== undefined
      ? { cache_creation_input_tokens: usage.cache_creation_input_tokens }
      : {}),
  };
}

/** One run's spans → one turn row, with its tool spans nested. */
function turnOf(spans) {
  const root = spans.find((s) => !s.parentSpanContext?.spanId && s.attributes['gen_ai.operation.name'] === 'invoke_agent')
    ?? spans.find((s) => s.attributes['gen_ai.operation.name'] === 'invoke_agent');
  if (!root) return null;

  const attrs = root.attributes;
  const startedAt = iso(root.startTime);
  const leaves = [];
  const tools = [];
  for (const span of spans) {
    const op = span.attributes['gen_ai.operation.name'];
    if (op === 'chat') {
      const usage = usageOf(span.attributes);
      // A chat span with no usage is a turn that reported none — carried as a leaf with a null
      // usage would be indistinguishable from a priced zero, so it is simply not a leaf. The turn
      // still costs what its other leaves cost.
      if (usage) leaves.push({ model: span.attributes['gen_ai.request.model'] ?? null, usage });
    } else if (op === 'execute_tool') {
      tools.push({
        // `mcp` and `tool` are two of MEASURED_KINDS and ActionSymbol buckets them differently;
        // the tracer's own discriminator for an MCP call is `gen_ai.tool.type === 'extension'`.
        kind: span.attributes['gen_ai.tool.type'] === 'extension' ? 'mcp' : 'tool',
        name: span.attributes['gen_ai.tool.name'] ?? span.name,
        duration_ms: ms(span),
        event_at: iso(span.startTime),
        call_site_id: attrs['tessary.call_site.id'] ?? null,
        trace_id: root.spanContext().traceId,
      });
    }
  }

  return {
    trace_id: root.spanContext().traceId,
    context_id: attrs['session.id'] ?? null,
    created_at: startedAt,
    event_at: startedAt,
    // No deploy boundaries in this source: paperclip stamps no release on a run, so run 3 has
    // nothing to attribute to off this corpus. Left null rather than invented.
    project_version_id: null,
    environment_id: null,
    call_site_id: attrs['tessary.call_site.id'] ?? '__unattributed__',
    duration_ms: ms(root),
    // Null exactly as `trace.total_cost` is on every production row: the loader prices the leaves
    // through TokenPriceBook over the bridge, so an unpriced model abstains rather than reads free.
    cost_usd: null,
    usage_leaves: leaves,
    tools,
  };
}

const args = parseArgs(process.argv.slice(2));
const cfg = loadConfig();
cfg.backfillLimit = args.limit;

const collected = [];
const exporter = {
  export(batch, cb) {
    collected.push(...batch);
    cb({ code: 0 });
  },
  forceFlush: () => Promise.resolve(),
  shutdown: () => Promise.resolve(),
};
const provider = new BasicTracerProvider({
  resource: resourceFromAttributes({ 'service.name': 'paperclip-corpus' }),
  spanProcessors: [new SimpleSpanProcessor(exporter)],
});
const tracer = provider.getTracer('paperclip-corpus');
const idGen = makeIdGenerator();
const client = makeClient(cfg);

const rows = [];
// Skips are tallied by reason rather than counted, because "skipped 30" is the same output whether
// the runs were all non-terminal or the API was answering every call with an error.
const skips = new Map();
const skip = (why) => skips.set(why, (skips.get(why) ?? 0) + 1);
let built = 0;
for (const cand of discoverRuns(cfg, new Set())) {
  try {
    const agent = await client.getAgent(cand.agentId);
    if (agent.adapterType && agent.adapterType !== 'claude_local') { skip(`adapter:${agent.adapterType}`); continue; }
    const run = await client.getRun(cand.runId);
    if (!TERMINAL.has(run.status)) { skip(`status:${run.status}`); continue; }
    const issues = await client.getRunIssues(cand.runId).catch(() => []);
    const enrichment = await enrichRun(client, run, agent, issues);
    const { records, stderr, stats: parseStats } = parseRunLog(cand.file);
    const drift = conformanceIssues(records);
    if (drift.includes('no assistant records')) { skip('no assistant records'); continue; }

    collected.length = 0;
    buildRunTrace(tracer, idGen, { run, agent, records, enrichment, stderr, conformance: drift, parseStats });
    const turn = turnOf(collected.slice());
    if (turn) { rows.push(turn); built++; } else skip('no invoke_agent span');
  } catch (e) {
    if (e instanceof PaperclipUnreachable) {
      console.error('paperclip API unreachable — stopping. ' + e.message);
      break;
    }
    skip(`error:${e?.message ?? e}`.slice(0, 80));
  }
  if (built % 250 === 0 && built) console.error(`  ${built} runs built...`);
}

fs.mkdirSync(path.dirname(args.out), { recursive: true });
fs.writeFileSync(args.out, rows.map((r) => JSON.stringify(r)).join('\n') + '\n');

const perSite = new Map();
for (const r of rows) perSite.set(r.call_site_id, (perSite.get(r.call_site_id) ?? 0) + 1);
console.error(`\nwrote ${args.out}`);
console.error(`turns ${rows.length}   call sites ${perSite.size}`);
for (const [why, n] of [...skips.entries()].sort((a, b) => b[1] - a[1])) console.error(`  skipped ${String(n).padStart(5)}  ${why}`);
console.error(`comparable (>=300 turns) ${[...perSite.values()].filter((n) => n >= 300).length}`);
for (const [site, n] of [...perSite.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)) {
  console.error(`  ${String(n).padStart(6)}  ${site}`);
}
await provider.shutdown();
