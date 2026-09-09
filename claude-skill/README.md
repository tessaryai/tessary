# claude-skill — Claude Code integration points

Helpers that run inside a Claude Code session against this platform.

> Whole-repo **synthesis is not here** — that's the external **evals
> plugin** (`tessaryai/plugins`), whose output contract is vendored into
> [`contract/`](../contract/). See the repo-root `README.md` and
> [`devdocs/guides/upgrade-contract.md`](../devdocs/guides/upgrade-contract.md).

- **`evals-prompt/`** — prompt-craft reference for authoring an LLM-judge grader
  against the plugin's authoring contract
  ([`contract/AUTHORING_CONTRACT.md`](../contract/AUTHORING_CONTRACT.md)).
  **The platform side of this is gone**: graders were removed, so nothing in
  this repo runs what the skill helps author. It is kept because the contract and
  the plugin are still live, and the craft guidance is the useful half.
- **`evals-mcp/`** — how Claude Code (or any MCP client) connects to the
  backend's remote MCP server at `POST /mcp`. The server itself lives in
  `backend/surfaces/src/main/java/ai/tessary/mcp/`.
