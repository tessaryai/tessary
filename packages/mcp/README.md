# @tessaryai/mcp

A stdio bridge for [Tessary](https://github.com/tessaryai/tessary)'s MCP server, for MCP clients
that only know how to launch a stdio server. Tessary's MCP endpoint speaks **streamable HTTP**
(`POST <origin>/mcp`), which most modern clients (Claude Code, Claude Desktop's HTTP transport,
etc.) can reach directly — this package exists only for the clients that can't.

If your client supports an HTTP/streamable-HTTP MCP transport, point it at `<origin>/mcp` directly
with an `Authorization: Bearer <token>` header and skip this package entirely. See
[`docs/reference/mcp-server.mdx`](https://github.com/tessaryai/tessary/blob/main/docs/reference/mcp-server.mdx)
for that path — it's what the `tessary` Claude Code plugin's `/connect` skill sets up.

## What it does

Reads one JSON-RPC message per line from stdin, `POST`s it as-is to `<origin>/mcp` with the
stored bearer token, and writes whatever comes back as one line on stdout. It does not interpret,
cache, or transform any MCP message — it's a transport adapter, not a client.

## Usage

```bash
npx @tessaryai/mcp --origin https://tessary.example.com --token tsy_a_...
```

Or via environment variables (useful for a client's own MCP server config, which typically sets
`env` rather than `args`):

```bash
TESSARY_ORIGIN=https://tessary.example.com TESSARY_TOKEN=tsy_a_... npx @tessaryai/mcp
```

The token is an admin-scoped project API key, minted under **Settings → MCP tokens** in your
Tessary instance. See
[`docs/reference/api-keys.mdx`](https://github.com/tessaryai/tessary/blob/main/docs/reference/api-keys.mdx)
for the credential model.

## Development

```bash
npm test   # node --test — mocks the /mcp endpoint, no live Tessary instance required
```
