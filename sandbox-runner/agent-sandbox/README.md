# observer-analyzer E2B template (v2 SDK build)

The microVM every agentic lane runs in: it clones the target repo at HEAD and runs
**OpenCode** over the diff + the committed `.tessary/` bundle. Defined in code with the
**E2B v2 build system** (no `e2b.toml`, no Dockerfile).

## Files
- `template.ts` — the image definition (base image, `git`, the `opencode-ai` CLI +
  `@opencode-ai/sdk` pinned in lockstep, and the in-VM scripts).
- `agent-stream.js` — the shared OpenCode runner: starts `opencode` as a server and drives it
  through the SDK. Required by `analyze.js` / `rca.js` / `triage.js` / `synthesize.js` /
  `codegen.js`.
- `analyze.js` — runs inside the sandbox: clone → checkout → repo-only bundle check → agent run
  → emit verdict. (Node builtins only.)
- `rca.js` — the finding-anchored root-cause lane: materialize the finding's dossier (`finding.md`,
  `evidence.json`, `checklist.md`) → read-only agent run wired to the platform's MCP surface → emit
  verdict + hypotheses + the markdown investigation. It reads every trace it cites through MCP, so
  the door is required; the clone is OPTIONAL and adds `./repo/` for the projects that have an
  integration. Read-only by permission rule: it may never edit the clone. (Node builtins only.)
- `triage.js` — the Layer-2 ruling: materialize the finding's two-file dossier (`finding.md`,
  `evidence.json`) → agent run wired to the platform's MCP surface → emit verdict + citations. The
  one script that never clones: triage audits a claim, and no repository says whether a claim about
  production traffic is true. It reads the substrate through MCP instead, and `checks/` under the
  work dir is the one path it may write — the agent computes what is mechanical rather than
  eyeballing it. (Node builtins only.)
- `build.ts` — builds + publishes the template to the E2B cloud under alias **`tessary-agent-sandbox`** (what the launcher's `E2B_ANALYZER_TEMPLATE` expects).

## Build / publish
```bash
cd sandbox-runner/agent-sandbox
pnpm install
echo "E2B_API_KEY=<your team's key>" > .env     # or export it
pnpm exec tsx build.ts                            # == pnpm run build
```
- The `E2B_API_KEY` must be for the **same team** the launcher uses at runtime, or
  `Sandbox.create('tessary-agent-sandbox', …)` won't find the template.
- The alias is stable, so rebuilds update the same template.
- Verify: `e2b template list` shows `tessary-agent-sandbox`.

> Network egress: this template (unlike the air-gapped grader template) reaches
> github.com + the model provider at run time. Credentials are never baked in —
> the launcher injects them per invocation.
