// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The agentic lanes, run INSIDE an E2B microVM (template tessary-agent-sandbox) or a docker sibling.
 * The launcher injects the agent auth and invokes:  node lane.js <rca|triage|run> <input.json>
 *
 *   input.json : { clone_url?, head_sha?, onset_at?, files, system_prompt?, prompt, json_schema?, model,
 *                  mcp: {url, token}, timeout_ms, max_turns }
 *   stdout     : { raw: "<result envelope>", turns: [...], startMs }
 *
 * `files` is the dossier (relative path → content), materialized under WORK/dossier/ so the agent
 * reaches it as ./dossier/; writeDossier refuses a path that escapes it. The agent reads traces only
 * by paging the finding's evidence over `mcp`.
 *
 * rca     Read-only. The clone is optional: the repository deepens an RCA, but an evidence-only
 *         project still gets one. With a clone, {onset_commit} in the prompt becomes the newest
 *         commit on HEAD committed before onset_at, or "unknown".
 * triage  Never clones: whether a claim about production traffic is true is not settled by source.
 *         Edit is a blanket allow because opencode matches an edit glob against `/` when WORK is not
 *         a git repo, so a narrower rule silently denies every edit. checks/ is where the prompt asks
 *         the agent to put its scripts.
 * run     The backend's AgentRunService. The clone is optional and read-only: edit is allowed
 *         under WORK but denied under REPO, listed last because opencode takes the LAST matching rule.
 *         Without json_schema the agent answers in prose.
 *
 * clone_url embeds a short-lived token and mcp.token is a live platform key: git runs with its args
 * kept out of errors, the token travels through the agent config and never argv, and scrubToken
 * covers both shapes, so neither secret can reach a log or this script's output.
 */
const fs = require('node:fs');
const path = require('node:path');
const { git, runAgent, describeError, failureEnvelope, quarantineRepo, writeDossier, WORK, REPO } = require('./agent-stream');

const LANES = {
  rca: { clone: true, onsetCommit: true, edit: { '*': 'deny' } },
  triage: { clone: false, checksDir: 'checks', edit: { '*': 'allow' } },
  run: { clone: true, edit: { '*': 'allow', [`${REPO}/**`]: 'deny' } },
};

function onsetCommit(onsetAt) {
  if (!onsetAt) return 'unknown';
  try {
    return git(['-C', REPO, 'rev-list', '-1', `--before=${onsetAt}`, 'HEAD']) || 'unknown';
  } catch (e) {
    console.error(`onset commit unresolved: ${describeError(e)}`);
    return 'unknown';
  }
}

async function main() {
  const lane = LANES[process.argv[2]];
  if (!lane) throw new Error(`unknown lane: ${process.argv[2]}`);
  const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

  let prompt = input.prompt;
  if (lane.clone && input.clone_url) {
    git(['clone', '--quiet', input.clone_url, REPO]);
    git(['-C', REPO, 'checkout', '--quiet', input.head_sha]);
    quarantineRepo();
    if (lane.onsetCommit) prompt = prompt.split('{onset_commit}').join(onsetCommit(input.onset_at));
  }

  writeDossier(path.join(WORK, 'dossier'), input.files);
  if (lane.checksDir) fs.mkdirSync(path.join(WORK, lane.checksDir), { recursive: true });

  let run;
  try {
    run = await runAgent({
      model: input.model,
      prompt,
      jsonSchema: input.json_schema ? JSON.parse(input.json_schema) : null,
      mcp: input.mcp,
      permission: { edit: lane.edit },
      timeoutMs: input.timeout_ms,
      maxTurns: input.max_turns,
      systemPrompt: input.system_prompt,
    });
  } catch (e) {
    // A failing run still spent tokens: the numeric-only envelope lets the launcher carry `usage`
    // into its 502 body so the backend books what the run cost rather than $0.
    process.stdout.write(failureEnvelope(e));
    process.exitCode = 1;
    return;
  }
  process.stdout.write(JSON.stringify({ raw: run.resultRaw, turns: run.turns, startMs: run.startMs }));
}

main()
  .catch((e) => {
    console.error(describeError(e));
    process.exitCode = 1;
  })
  .finally(() => {
    // Unref'd, so it fires only when something else holds the loop open (a leaked socket), and names
    // the handle within the run rather than as a late launcher timeout.
    setTimeout(() => {
      console.error('still alive 5s after main() finished:', process.getActiveResourcesInfo());
      process.exit(process.exitCode || 1);
    }, 5000).unref();
  });
