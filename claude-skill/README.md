# claude-skill — Claude Code integration points

Helpers that run inside a Claude Code session against this platform.

> The `.tessary/` bundle comes from the external **evals plugin**
> (`tessaryai/plugins`), whose output contract is vendored into
> [`contract/`](../contract/). See the repo-root `README.md` and
> [`devdocs/guides/upgrade-contract.md`](../devdocs/guides/upgrade-contract.md).

- **`evals-mcp/`** — how Claude Code (or any MCP client) connects to the
  backend's remote MCP server at `POST /mcp`. The server itself lives in
  `backend/surfaces/src/main/java/ai/tessary/mcp/`.
