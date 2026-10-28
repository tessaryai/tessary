// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Loopback JSON-RPC proxy in front of named MCP upstreams. opencode truncates any MCP result over
 * 2000 lines or 50KB, so each `tools/call` result is saved whole under checks/mcp/ and large ones come
 * back as a summary. Everything else is forwarded byte-for-byte. An unknown name is a 404, never
 * forwarded. The bearer tokens live here, not in opencode's config, which the agent could read back.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

// Compact JSON length / 4 as a cheap token proxy.
const INLINE_TOKEN_BUDGET = 2000;
const PREVIEW_ROW_COUNT = 3;
const PREVIEW_STRING_CHARS = 200;
const SERVER_NAME = /^[a-z][a-z0-9-]{0,31}$/;

function tokenEstimate(compactJson) {
  return Math.ceil(compactJson.length / 4);
}

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

// A copy with every string over PREVIEW_STRING_CHARS cut, so a preview row cannot carry a page of text.
function cutStrings(value) {
  if (typeof value === 'string') {
    return value.length > PREVIEW_STRING_CHARS ? `${value.slice(0, PREVIEW_STRING_CHARS)}…` : value;
  }
  if (Array.isArray(value)) return value.map(cutStrings);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, cutStrings(v)]));
  }
  return value;
}

function withFile(parsed, workRelativeFile) {
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return { ...parsed, file: workRelativeFile };
  if (Array.isArray(parsed)) return { file: workRelativeFile, rows: parsed };
  return { file: workRelativeFile, value: parsed };
}

function summarize(parsed, workRelativeFile) {
  const rows = rowsOf(parsed);
  const cursor = cursorOf(parsed);
  if (rows) {
    const summary = {
      file: workRelativeFile,
      row_count: rows.length,
      fields: fieldNamesOf(rows),
      first_rows: rows.slice(0, PREVIEW_ROW_COUNT).map(cutStrings),
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

function startMcpRelay({ servers, workDir }) {
  const upstreams = new Map();
  for (const { name, url, token } of servers) {
    if (typeof name !== 'string' || !SERVER_NAME.test(name)) {
      return Promise.reject(new Error(`mcp server name must match ${SERVER_NAME}: ${name}`));
    }
    if (upstreams.has(name)) return Promise.reject(new Error(`mcp server name repeated: ${name}`));
    upstreams.set(name, { url, token });
  }
  const mcpDir = path.join(workDir, 'checks', 'mcp');
  fs.mkdirSync(mcpDir, { recursive: true });
  let seq = 0;

  function saveResult(toolName, serialized) {
    seq += 1;
    const n = String(seq).padStart(3, '0');
    const safeName = String(toolName || 'call').replace(/[^A-Za-z0-9_-]/g, '_') || 'call';
    const absFile = path.join(mcpDir, `${n}-${safeName}.json`);
    fs.writeFileSync(absFile, serialized);
    return path.relative(workDir, absFile);
  }

  // A result small enough to inline is saved pretty-printed for reading; a large one is saved compact,
  // since the agent parses it rather than reads it, and indentation would roughly double a big page.
  function rewriteToolCallResult(toolName, respObj) {
    if (!respObj || typeof respObj !== 'object' || respObj.error) return respObj;
    const result = respObj.result;
    const payload = payloadOf(result);
    if (payload === undefined) return respObj; // nothing JSON-shaped to save; pass through.
    const compact = JSON.stringify(payload);
    const inline = tokenEstimate(compact) <= INLINE_TOKEN_BUDGET;
    const relFile = saveResult(toolName, inline ? JSON.stringify(payload, null, 2) : compact);
    const body = inline ? withFile(payload, relFile) : summarize(payload, relFile);
    const rewritten = { ...respObj, result: { ...result } };
    if (result.structuredContent !== undefined) rewritten.result.structuredContent = body;
    rewritten.result.content = [{ type: 'text', text: JSON.stringify(body) }];
    return rewritten;
  }

  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405, { 'content-type': 'text/plain' }).end('method not allowed');
      return;
    }
    const route = /^\/mcp\/([^/?]+)$/.exec(req.url);
    const upstreamFor = route && upstreams.get(route[1]);
    if (!upstreamFor) {
      req.resume();
      res.writeHead(404, { 'content-type': 'application/json' }).end(JSON.stringify({ error: 'unknown server' }));
      return;
    }
    const { url, token } = upstreamFor;
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
      // Drop opencode's keep-alive sockets first, or server.close() waits for them and hangs the run.
      const urls = {};
      for (const name of upstreams.keys()) urls[name] = `http://127.0.0.1:${port}/mcp/${name}`;
      resolve({
        urls,
        close: () => {
          server.closeAllConnections();
          server.close();
        },
      });
    });
  });
}

module.exports = { startMcpRelay };
