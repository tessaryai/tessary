// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * The one-shot agent lane, run INSIDE an E2B microVM (template tessary-agent-sandbox) or a docker
 * sibling. Triage and RCA run as launcher sessions (session.js). The launcher injects the agent auth
 * and invokes:  node lane.js run <input.json>
 *
 *   input.json : { clone_url?, head_sha?, files, system_prompt?, prompt, json_schema?, model,
 *                  mcp: {url, token}, timeout_ms, max_turns }
 *   stdout     : { raw: "<result envelope>", turns: [...], startMs }
 *
 * `files` is the dossier (relative path → content), materialized under WORK/dossier/ so the agent
 * reaches it as ./dossier/; writeDossier refuses a path that escapes it. The agent reads traces only
 * over `mcp`.
 *
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
  run: { edit: { '*': 'allow', [`${REPO}/**`]: 'deny' } },
};

async function main() {
  const lane = LANES[process.argv[2]];
  if (!lane) throw new Error(`unknown lane: ${process.argv[2]}`);
  const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

  if (input.clone_url) {
    git(['clone', '--quiet', input.clone_url, REPO]);
    git(['-C', REPO, 'checkout', '--quiet', input.head_sha]);
    quarantineRepo();
  }

  writeDossier(path.join(WORK, 'dossier'), input.files);

  let run;
  try {
    run = await runAgent({
      model: input.model,
      prompt: input.prompt,
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
