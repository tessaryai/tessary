// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Layer-2 triage runner — runs INSIDE an E2B microVM (template tessary-agent-sandbox)
 * or on the host in local mode, exactly like rca.js. The launcher injects the agent auth and
 * invokes:  node triage.js <input.json>
 *
 *   input.json : { files, prompt, json_schema, model, mcp: {url, token}|null, timeout_ms }
 *   stdout     : { raw: "<result envelope>", turns: [...], startMs }
 *
 * NO CLONE, EVER. Triage audits one finding's CLAIM — is it true, was it measured over enough,
 * does the evidence carry it — and none of those questions is answered by source code. This lane
 * used to take an optional `clone_url`, so half its runs paid a clone plus a `.tessary/` read to
 * answer a question the repository has no bearing on. Locating a cause is RCA's job and RCA is
 * where the repo went; here there is no `repo/` directory and nothing repo-shaped is writable.
 *
 * `files` is the FINDING's dossier (relative path → content), materialized under WORK/dossier/ so
 * the agent reaches it as ./dossier/ from the directory it runs in — the layout the backend's
 * prompt describes:
 *
 *   dossier/finding.md      the claim, its cause, its windows, and the size of each evidence role
 *   dossier/evidence.json   the detector's own numbers, verbatim
 *
 * That is the whole dossier. NO hydrated traces: the agent pages the finding's evidence refs over
 * MCP (get_finding_evidence → get_trace / get_span) and cites the ids it actually fetched, so the
 * sample it ruled on is one it chose and stated rather than one this file chose for it silently.
 *
 * WORKSPACE-ONLY WRITES. `checks/` under WORK is the one writable place: the agent is required to
 * compute anything mechanical rather than eyeball it, which means authoring and running its own
 * scripts (bash is allowed, python3 and node are in the image). The permission map is the same
 * shape codegen.js already uses for its single writable file — deny everything, allow one path.
 *
 * mcp.token is a live platform key, wired through the agent config and never argv (it must not
 * show in `ps`); scrubToken covers the tsy_* shape, so it cannot reach a log or this script's
 * output either.
 */
const fs = require('node:fs');
const path = require('node:path');
const { runAgent, describeError, sumUsage, WORK } = require('./agent-stream');

/** Where the agent may write, relative to its start directory (WORK). */
const CHECKS_DIR = 'checks';

// Materialize the dossier under root, refusing anything that would escape it — the paths come from
// the backend's own store, but a traversal must be impossible by construction rather than by trust.
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

// The backend sends the schema as a JSON string; the agent SDK wants the object.
function parseSchema(raw) {
  if (!raw) return null;
  if (typeof raw === 'object') return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function main() {
  const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));

  writeDossier(path.join(WORK, 'dossier'), input.files);
  // Created here rather than left to the agent: an `edit` allow-rule on a directory that does not
  // exist is a first tool call that fails for a reason the agent cannot see from its prompt.
  fs.mkdirSync(path.join(WORK, CHECKS_DIR), { recursive: true });

  // 'error': the run's VALUE is the schema-constrained ruling JSON — a half-finished run must
  // reject so the backend leaves triage_verdict NULL and retries, rather than parsing a broken
  // body into a ruling that closes a finding nobody looked at.
  let run;
  try {
    run = await runAgent({
      model: input.model,
      prompt: input.prompt,
      jsonSchema: parseSchema(input.json_schema),
      mcp: input.mcp,
      permission: { edit: { '*': 'deny', [`${CHECKS_DIR}/**`]: 'allow' } },
      rejectOn: 'error',
      timeoutMs: input.timeout_ms,
      maxTurns: input.max_turns,
    });
  } catch (e) {
    // F1: a failing run still spent tokens (agent-stream.js's `.turns` on the thrown error carries
    // every session this run opened, accumulated). Write a small, numeric-only envelope to stdout
    // before exiting non-zero, so server.js's failure path (buildErrorBody) can carry `usage` into
    // the 502 body and E2bTriageSandbox can book it — a failed run must not also book $0.
    process.stdout.write(JSON.stringify({ is_error: true, error: describeError(e), usage: sumUsage(e && e.turns) }));
    process.exitCode = 1;
    return;
  }
  process.stdout.write(JSON.stringify({ raw: run.resultRaw, turns: run.turns, startMs: run.startMs }));
}

main().catch((e) => {
  console.error(describeError(e));
  process.exit(1);
});
