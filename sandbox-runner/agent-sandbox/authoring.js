// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Generic agent runner, the lane behind POST /authoring — runs INSIDE an E2B microVM (template
 * tessary-agent-sandbox) or a docker sibling, exactly like rca.js and triage.js. The launcher
 * injects the agent auth and invokes:  node authoring.js <input.json>
 *
 *   input.json : { clone_url?, head_sha?, files, system_prompt, prompt, json_schema?, model,
 *                  mcp: {url, token}, timeout_ms, max_turns }
 *   stdout     : { raw: "<result envelope>", turns: [...], startMs }
 *
 * triage.js's shape with rca.js's optional clone. The caller (the backend's AgentRunService, on
 * behalf of whoever wants a structured answer from an agent that has read the repository and the
 * traces) sends a system prompt ALWAYS, so every run goes through agent-stream.js's custom agent and
 * its mcp-relay in front of `mcp`. `json_schema` is optional: with one, the reply is constrained to
 * it and lands in `structured_output`; without one, the agent answers in prose and `result` carries
 * it.
 *
 * THE CLONE IS OPTIONAL and READ-ONLY. A project with a git integration sends clone_url + head_sha
 * and gets ./repo/ at that commit, quarantined like RCA's so repo-sourced agent config cannot steer
 * the run. The permission rule below denies `edit` under REPO and allows it everywhere else under
 * WORK: the agent may write scratch files and run `node` on a builder it drafts (the image has node
 * 24, python3 and jq), but a customer's checkout is evidence, never a working tree. opencode takes
 * the LAST matching rule on a map, so the REPO deny is listed after the blanket allow.
 *
 * `files` is the caller's dossier (relative path → content), materialized under WORK/dossier/ so the
 * agent reaches it as ./dossier/ from the directory it runs in. A traversal must be impossible by
 * construction rather than by trust, exactly as the other two lanes guard it.
 *
 * clone_url embeds a short-lived token and mcp.token is a live platform key: git runs with stdio
 * ignored and re-throws without its args, the token is wired through the agent config and never
 * argv, and scrubToken covers both shapes, so neither secret can reach a log or this script's output.
 */
const fs = require('node:fs');
const path = require('node:path');
const { git, runAgent, describeError, sumUsage, quarantineRepo, WORK, REPO } = require('./agent-stream');

// Materialize the dossier under root, refusing anything that would escape it.
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

async function main() {
  const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));

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
      // The backend sends the schema as a JSON string, or nothing at all for a prose answer.
      jsonSchema: input.json_schema ? JSON.parse(input.json_schema) : null,
      mcp: input.mcp,
      permission: { edit: { '*': 'allow', [`${REPO}/**`]: 'deny' } },
      timeoutMs: input.timeout_ms,
      maxTurns: input.max_turns,
      systemPrompt: input.system_prompt,
    });
  } catch (e) {
    // A failing run still spent tokens: see triage.js's identical block. The numeric-only envelope
    // lets the launcher carry `usage` into its 502 body and the backend book what this run cost.
    process.stdout.write(JSON.stringify({ is_error: true, error: describeError(e), usage: sumUsage(e && e.turns) }));
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
    // The same unref'd exit guard as the other two lanes: it only fires when something else holds
    // the loop open, and names the leaked handle within the run rather than as a late timeout.
    setTimeout(() => {
      console.error('still alive 5s after main() finished:', process.getActiveResourcesInfo());
      process.exit(process.exitCode || 1);
    }, 5000).unref();
  });
