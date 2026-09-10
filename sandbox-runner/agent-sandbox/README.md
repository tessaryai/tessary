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
- `build.ts` — the ONLY thing in this repo that talks to E2B: builds, verifies and tags the
  template. Published in our project as **`tessary-agent-sandbox`**, and **public**, so everyone
  else reaches it as **`tessary/tessary-agent-sandbox`** — which is what the launcher's
  `E2B_ANALYZER_TEMPLATE` default says.

## Build / publish

Releases do this on their own. `.github/workflows/release.yml` drives the four verbs below from the
`E2B_API_KEY` repository secret, and only rebuilds when the recipe actually changed — see
[`sandbox-runner/README.md`](../README.md#deploy) for the job order and why it splits the way it
does.

By hand (a dev template, or the first build in a fresh E2B project):

```bash
cd sandbox-runner/agent-sandbox
pnpm install
echo "E2B_API_KEY=<your team's key>" > .env     # or export it
pnpm exec tsx build.ts                            # == pnpm run build
```

| verb | does | when |
|---|---|---|
| *(none)* | build + publish under the `default` tag | by hand |
| `--release=<semver>` | build **only if the recipe hash changed**, tag `<semver>` + `recipe-<hash>` | `build-agent-template` |
| `--verify=<semver>` | assert public + namespaced name, then boot it and run seven checks | `verify-agent-template` |
| `--promote=<semver>` | move `latest` and `default` onto that build | `finalize` |
| `--rollback=<semver>` | remove the `<semver>` tag, keep `recipe-<hash>` | `cleanup` |

- The `E2B_API_KEY` must be for the **same team** that owns the template, or the build lands in a
  different project under a name nothing references.
- The name is stable, so rebuilds update the same template; tags are what distinguish builds.
- Verify: `e2b template list` shows `tessary/tessary-agent-sandbox`.

> Network egress: this template (unlike the air-gapped grader template) reaches
> github.com + the model provider at run time. Credentials are never baked in —
> the launcher injects them per invocation.
