// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Agentic RCA runner — runs INSIDE an E2B microVM (template tessary-agent-sandbox)
 * or on the host in local mode, exactly like analyze.js. The launcher injects the
 * agent auth and invokes:  node rca.js <input.json>
 *
 *   input.json : { clone_url?, head_sha?, files, prompt, json_schema, model,
 *                  mcp: {url, token}, timeout_ms }
 *   stdout     : { raw: "<result envelope>", turns: [...], startMs }
 *
 * The run is READ-ONLY: unlike analyze.js there is no bundle-dir gate and no working-tree
 * change collection. The lane's permission rules state that to the agent as well as to us —
 * nothing here may edit.
 *
 * `files` is the FINDING's dossier (relative path → content), materialized under WORK/dossier/
 * so the agent reaches it as ./dossier/ from the directory it runs in — the layout the
 * backend's prompt describes:
 *
 *   dossier/finding.md      the claim, its cause, its window, and the size of each evidence role
 *   dossier/evidence.json   the detector's own numbers, verbatim
 *   dossier/checklist.md    the structural measurements, unjudged
 *
 * That is the whole dossier. NO hydrated traces and no per-side ledgers: this file used to
 * receive two directories of trace bodies that the backend chose before knowing the question,
 * capped at whatever fitted. The agent now pages the finding's evidence refs over MCP
 * (get_finding_evidence -> get_trace / get_span) and cites the ids it actually fetched, so the
 * sample it reasons over is one it chose and stated. `mcp` is therefore REQUIRED — the backend
 * refuses the run without it — and is wired through the agent config, never argv (the token
 * must not show in `ps`).
 *
 * THE CLONE IS OPTIONAL. A project with no git integration sends no clone_url, and the run
 * proceeds without ./repo/: the repository deepens an RCA (it is where a located change lives)
 * but the production evidence is readable without it, and refusing the run would deny an
 * evidence-only project the investigation it can have.
 *
 * clone_url embeds a short-lived token and mcp.token is a live platform key: git runs
 * with stdio ignored and its errors are re-thrown WITHOUT the args; scrubToken also
 * covers the tsy_* key shape, so neither secret can reach a log or this script's output.
 */
const fs = require('node:fs');
const path = require('node:path');
const { git, runAgent, describeError, sumUsage, quarantineRepo, WORK, REPO } = require('./agent-stream');


// Materialize the dossier under root, refusing anything that would escape it — trace ids
// come from the backend's own store, but a path traversal must be impossible by construction.
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

  if (input.clone_url) {
    git(['clone', '--quiet', input.clone_url, REPO]);
    git(['-C', REPO, 'checkout', '--quiet', input.head_sha]);
    quarantineRepo();
  }

  writeDossier(path.join(WORK, 'dossier'), input.files);

  // 'error': the run's VALUE is the schema-constrained verdict JSON — a half-finished run must
  // reject so the backend stamps the report failed instead of parsing a broken body.
  let run;
  try {
    run = await runAgent({
      model: input.model,
      prompt: input.prompt,
      jsonSchema: parseSchema(input.json_schema),
      mcp: input.mcp,
      permission: { edit: { '*': 'deny' } },
      rejectOn: 'error',
      timeoutMs: input.timeout_ms,
      maxTurns: input.max_turns,
    });
  } catch (e) {
    // F1: see triage.js's identical block — a failing run still spent tokens, so emit them as a
    // small numeric-only envelope before exiting non-zero, letting E2bRcaSandbox book what this
    // run actually cost instead of $0.
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
