// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * A loopback JSON-RPC proxy in front of Tessary's own MCP endpoint, used ONLY on the triage path
 * (agent-stream.js starts one exactly when spec.systemPrompt is set; RCA never sees it).
 *
 * WHY: triage's prompt tells the agent to read every evidence row rather than a sampled preview,
 * and a finding's evidence page can run to hundreds of rows — well past what belongs inline in a
 * model's context. opencode itself truncates any MCP tool result over 2000 lines or 50KB, so a
 * page that size would arrive at the agent silently cut off rather than as a page it could work
 * with. The relay sits between opencode and the real Tessary MCP server, on the SAME loopback
 * interface agent-stream.js already binds the opencode server to, and rewrites only the reply to a
 * `tools/call`: small results pass through untouched; large ones are saved whole to
 * `checks/mcp/NNN-<tool>.json` under the run's WORK dir and replaced with a summary the agent can
 * read at a glance and then go compute over with its own bash/jq/scripts.
 *
 * Everything else — `initialize` (whose `instructions` field must reach the agent unchanged, see
 * agent-stream.js's caller), `tools/list`, notifications — is forwarded byte-for-byte. This file
 * has no opinion on the MCP protocol beyond "find the tools/call result and maybe shrink it".
 *
 * The bearer token lives HERE, not in opencode's own MCP config: opencode config is written to
 * OPENCODE_CONFIG_CONTENT and is at least in principle a value a curious agent could read back
 * (env, a debug endpoint). The relay is the one place that holds the live platform key, exactly
 * the way a same-origin reverse proxy would.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

// opencode's own MCP truncation is 2000 lines / 50KB (see file header) — stay well under both.
// Estimated the same way the design doc does: compact JSON length / 4 as a cheap token proxy.
const INLINE_TOKEN_BUDGET = 2000;
const PREVIEW_ROW_COUNT = 3;

function tokenEstimate(compactJson) {
  return Math.ceil(compactJson.length / 4);
}

/**
 * The paged-rows shape `get_finding_evidence` (and anything else that pages) answers with: a
 * `rows` array plus a cursor under either spelling. A bare JSON array is treated the same way, so
 * a tool that answers with `[...]` at the top level still gets a row-shaped summary. Anything else
 * (a single object, e.g. `get_finding`) has no row concept and falls back to a plainer summary.
 */
function rowsOf(parsed) {
  if (parsed && Array.isArray(parsed.rows)) return parsed.rows;
  if (Array.isArray(parsed)) return parsed;
  return null;
}

function cursorOf(parsed) {
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined;
  return parsed.nextCursor !== undefined ? parsed.nextCursor : parsed.next_cursor;
}

function fieldNamesOf(rows) {
  const first = rows.find((r) => r && typeof r === 'object' && !Array.isArray(r));
  return first ? Object.keys(first) : [];
}

/**
 * The reply the agent sees in place of a large tool result: enough to decide what to look at next
 * without paying for the whole page in context. `file` is WORK-relative, matching how the agent
 * already addresses its own `checks/` scripts.
 */
function summarize(parsed, workRelativeFile) {
  const rows = rowsOf(parsed);
  const cursor = cursorOf(parsed);
  if (rows) {
    const summary = {
      file: workRelativeFile,
      row_count: rows.length,
      fields: fieldNamesOf(rows),
      first_rows: rows.slice(0, PREVIEW_ROW_COUNT),
    };
    if (cursor !== undefined) summary.cursor = cursor;
    return summary;
  }
  const fields = parsed && typeof parsed === 'object' ? Object.keys(parsed) : [];
  return {
    file: workRelativeFile,
    note: 'result saved to file; too large to inline',
    fields,
  };
}

/** The tool's own JSON payload out of a `tools/call` result: `structuredContent`, else the first text block. */
function payloadOf(result) {
  if (result && result.structuredContent !== undefined) return result.structuredContent;
  const textBlock = Array.isArray(result && result.content)
    ? result.content.find((c) => c && c.type === 'text' && typeof c.text === 'string')
    : null;
  if (!textBlock) return undefined;
  try {
    return JSON.parse(textBlock.text);
  } catch {
    return undefined;
  }
}

/**
 * Start the relay. `mcp.url`/`mcp.token` are the real Tessary MCP endpoint and its bearer token;
 * `workDir` is the run's WORK dir (results save under `<workDir>/checks/mcp/`).
 *
 * @returns {Promise<{url: string, close: () => void}>}
 */
function startMcpRelay({ url, token, workDir }) {
  if (!url || !token) throw new Error('mcp relay requires an upstream mcp url and token');
  if (!workDir) throw new Error('mcp relay requires a workDir');
  const mcpDir = path.join(workDir, 'checks', 'mcp');
  fs.mkdirSync(mcpDir, { recursive: true });
  let seq = 0;

  function saveResult(toolName, payload) {
    seq += 1;
    const n = String(seq).padStart(3, '0');
    const safeName = String(toolName || 'call').replace(/[^A-Za-z0-9_-]/g, '_') || 'call';
    const absFile = path.join(mcpDir, `${n}-${safeName}.json`);
    fs.writeFileSync(absFile, JSON.stringify(payload, null, 2));
    return path.relative(workDir, absFile);
  }

  /** Rewrite a parsed `tools/call` JSON-RPC response in place, or return it untouched. */
  function rewriteToolCallResult(toolName, respObj) {
    if (!respObj || typeof respObj !== 'object' || respObj.error) return respObj;
    const result = respObj.result;
    const payload = payloadOf(result);
    if (payload === undefined) return respObj; // nothing JSON-shaped to summarize; pass through.
    const compact = JSON.stringify(payload);
    if (tokenEstimate(compact) <= INLINE_TOKEN_BUDGET) return respObj; // small enough as-is.
    const relFile = saveResult(toolName, payload);
    const summary = summarize(payload, relFile);
    const rewritten = { ...respObj, result: { ...result } };
    if (result.structuredContent !== undefined) rewritten.result.structuredContent = summary;
    rewritten.result.content = [{ type: 'text', text: JSON.stringify(summary) }];
    return rewritten;
  }

  const server = http.createServer((req, res) => {
    if (req.method !== 'POST' || req.url !== '/mcp') {
      res.writeHead(405, { 'content-type': 'text/plain' }).end('method not allowed');
      return;
    }
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      void (async () => {
        const rawBody = Buffer.concat(chunks).toString('utf8');
        let parsedReq;
        try {
          parsedReq = JSON.parse(rawBody);
        } catch {
          res.writeHead(400, { 'content-type': 'application/json' }).end(JSON.stringify({ error: 'invalid json' }));
          return;
        }
        // A JSON-RPC notification carries no `id` and gets no reply body; opencode expects 204.
        const isNotification =
          parsedReq && typeof parsedReq === 'object' && !Array.isArray(parsedReq) && parsedReq.id === undefined;
        try {
          const upstream = await fetch(url, {
            method: 'POST',
            headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
            body: rawBody,
          });
          if (isNotification) {
            await upstream.arrayBuffer().catch(() => {});
            res.writeHead(204).end();
            return;
          }
          const text = await upstream.text();
          let respObj;
          try {
            respObj = JSON.parse(text);
          } catch {
            // Not JSON-RPC we understand; forward verbatim rather than guess.
            res.writeHead(upstream.status, { 'content-type': 'application/json' }).end(text);
            return;
          }
          const toolName =
            parsedReq && parsedReq.method === 'tools/call' && parsedReq.params ? parsedReq.params.name : null;
          const out = toolName ? rewriteToolCallResult(toolName, respObj) : respObj;
          res.writeHead(upstream.status, { 'content-type': 'application/json' }).end(JSON.stringify(out));
        } catch (e) {
          res
            .writeHead(502, { 'content-type': 'application/json' })
            .end(JSON.stringify({ error: String((e && e.message) || e) }));
        }
      })();
    });
  });

  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ url: `http://127.0.0.1:${port}/mcp`, close: () => server.close() });
    });
  });
}

module.exports = { startMcpRelay, tokenEstimate, INLINE_TOKEN_BUDGET };
