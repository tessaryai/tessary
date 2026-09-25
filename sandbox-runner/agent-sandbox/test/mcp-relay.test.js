// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Coverage for mcp-relay.js: every tools/call result is saved whole to checks/mcp/NNN-<tool>.json.
 * A small result comes back inline with its full data plus the saved path; a large one is replaced
 * with a summary the agent can act on (file, row count, field names, a few rows, and the cursor
 * under either wire spelling). Everything that is not a tools/call result (initialize's
 * instructions, a notification, a rejected method) passes through unchanged.
 *
 * Run with: node --test test/mcp-relay.test.js
 *
 * Drives a REAL loopback HTTP round trip — relay -> stub upstream -> relay -> caller — rather than
 * mocking either side: the module's whole job is HTTP framing (status codes, a 204 with no body, a
 * 405) and JSON-RPC shape, which a mock of `fetch` would just restate rather than exercise.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { startMcpRelay } = require('../mcp-relay');

function startStubUpstream(handler) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        handler(JSON.parse(Buffer.concat(chunks).toString('utf8')), req, res);
      });
    });
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ url: `http://127.0.0.1:${port}/mcp`, close: () => server.close() });
    });
  });
}

function jsonRpcToolResult(id, payload) {
  return {
    jsonrpc: '2.0',
    id,
    result: { content: [{ type: 'text', text: JSON.stringify(payload) }], structuredContent: payload },
  };
}

function tempWorkDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-relay-test-'));
}

async function callTool(relayUrl, id, name, extra = {}) {
  const res = await fetch(relayUrl, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: {} }, ...extra }),
  });
  return { status: res.status, body: await res.json() };
}

test('a small tools/call result comes back inline in full, with its saved path added', async (t) => {
  const upstream = await startStubUpstream((reqBody, req, res) => {
    assert.equal(req.headers.authorization, 'Bearer test-token');
    res.writeHead(200, { 'content-type': 'application/json' }).end(
      JSON.stringify(jsonRpcToolResult(reqBody.id, { rows: [{ id: 1 }], nextCursor: null })),
    );
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const { status, body } = await callTool(relay.url, 1, 'get_finding_evidence');
  assert.equal(status, 200);
  const expected = {
    rows: [{ id: 1 }],
    nextCursor: null,
    file: path.join('checks', 'mcp', '001-get_finding_evidence.json'),
  };
  assert.deepEqual(JSON.parse(body.result.content[0].text), expected);
  assert.deepEqual(body.result.structuredContent, expected);

  const saved = JSON.parse(fs.readFileSync(path.join(workDir, expected.file), 'utf8'));
  assert.deepEqual(saved, { rows: [{ id: 1 }], nextCursor: null }, 'the full result is saved verbatim, with no file field added');
});

test('a large tools/call result is saved to a file and replaced with a summary (nextCursor)', async (t) => {
  const rows = Array.from({ length: 500 }, (_, i) => ({
    id: i,
    span_id: `s${i}`,
    cost_usd: 0.01,
    model: 'sonnet-5',
  }));
  const payload = { rows, nextCursor: 'abc123', counts: { total: 500 } };
  const upstream = await startStubUpstream((reqBody, req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(jsonRpcToolResult(reqBody.id, payload)));
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const { status, body } = await callTool(relay.url, 2, 'get_finding_evidence');
  assert.equal(status, 200);
  const summary = JSON.parse(body.result.content[0].text);
  assert.equal(summary.row_count, 500);
  assert.deepEqual(summary.fields, ['id', 'span_id', 'cost_usd', 'model']);
  assert.equal(summary.first_rows.length, 3);
  assert.deepEqual(summary.first_rows, rows.slice(0, 3));
  assert.equal(summary.cursor, 'abc123');
  assert.equal(summary.file, path.join('checks', 'mcp', '001-get_finding_evidence.json'));
  assert.deepEqual(body.result.structuredContent, summary, 'structuredContent is rewritten the same way');

  const saved = JSON.parse(fs.readFileSync(path.join(workDir, summary.file), 'utf8'));
  assert.deepEqual(saved, payload, 'the full, unsummarized result is saved verbatim');
});

test('a large tools/call result reads next_cursor as well as nextCursor', async (t) => {
  const rows = Array.from({ length: 500 }, (_, i) => ({ id: i, value: 'x'.repeat(30) }));
  const payload = { rows, next_cursor: 'xyz789' };
  const upstream = await startStubUpstream((reqBody, req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(jsonRpcToolResult(reqBody.id, payload)));
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const { body } = await callTool(relay.url, 3, 'get_finding_evidence');
  const summary = JSON.parse(body.result.content[0].text);
  assert.equal(summary.cursor, 'xyz789', 'the snake_case wire spelling is read too');
});

test('sequential large calls number their files 001, 002, ... by tool', async (t) => {
  const bigRows = Array.from({ length: 500 }, (_, i) => ({ id: i, value: 'x'.repeat(30) }));
  const upstream = await startStubUpstream((reqBody, req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' }).end(
      JSON.stringify(jsonRpcToolResult(reqBody.id, { rows: bigRows })),
    );
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const first = await callTool(relay.url, 10, 'get_finding_evidence');
  const second = await callTool(relay.url, 11, 'get_trace');
  assert.equal(JSON.parse(first.body.result.content[0].text).file, path.join('checks', 'mcp', '001-get_finding_evidence.json'));
  assert.equal(JSON.parse(second.body.result.content[0].text).file, path.join('checks', 'mcp', '002-get_trace.json'));
});

test('the file sequence keeps incrementing across a mix of small and large calls', async (t) => {
  const bigRows = Array.from({ length: 500 }, (_, i) => ({ id: i, value: 'x'.repeat(30) }));
  const upstream = await startStubUpstream((reqBody, req, res) => {
    const payload = reqBody.params.name === 'get_finding' ? { id: 'fnd-1' } : { rows: bigRows };
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(jsonRpcToolResult(reqBody.id, payload)));
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const small = await callTool(relay.url, 20, 'get_finding');
  const large = await callTool(relay.url, 21, 'get_finding_evidence');
  assert.equal(JSON.parse(small.body.result.content[0].text).file, path.join('checks', 'mcp', '001-get_finding.json'));
  assert.equal(JSON.parse(large.body.result.content[0].text).file, path.join('checks', 'mcp', '002-get_finding_evidence.json'));
  assert.equal(fs.readdirSync(path.join(workDir, 'checks', 'mcp')).length, 2, 'both calls saved a file, small included');
});

test('initialize (and anything not tools/call) passes through untouched, instructions intact', async (t) => {
  const upstream = await startStubUpstream((reqBody, req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' }).end(
      JSON.stringify({
        jsonrpc: '2.0',
        id: reqBody.id,
        result: { instructions: 'call get_finding first', capabilities: {} },
      }),
    );
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const res = await fetch(relay.url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }),
  });
  const body = await res.json();
  assert.equal(body.result.instructions, 'call get_finding first');
});

test('a notification is forwarded upstream and answered with a bare 204', async (t) => {
  let forwardedMethod = null;
  const upstream = await startStubUpstream((reqBody, req, res) => {
    forwardedMethod = reqBody.method;
    res.writeHead(200, { 'content-type': 'application/json' }).end('{}');
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 'test-token', workDir });
  t.after(() => relay.close());

  const res = await fetch(relay.url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  });
  assert.equal(res.status, 204);
  assert.equal(await res.text(), '');
  assert.equal(forwardedMethod, 'notifications/initialized');
});

test('a GET is rejected with 405 and never reaches upstream', async (t) => {
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: 'http://127.0.0.1:1/mcp', token: 't', workDir });
  t.after(() => relay.close());
  const res = await fetch(relay.url, { method: 'GET' });
  assert.equal(res.status, 405);
});

/** A relay in front of an upstream that answers every call with `respond(reqBody)` as the raw body. */
async function relayOver(t, respond, status = 200) {
  const upstream = await startStubUpstream((reqBody, req, res) => {
    res.writeHead(status, { 'content-type': 'application/json' }).end(respond(reqBody));
  });
  t.after(() => upstream.close());
  const workDir = tempWorkDir();
  const relay = await startMcpRelay({ url: upstream.url, token: 't', workDir });
  t.after(() => relay.close());
  return { relay, workDir };
}

const textResult = (id, text) => JSON.stringify({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text }] } });

test('a small text-only result that is a bare array or a scalar is kept whole, never dropped', async (t) => {
  const answers = [[{ id: 1 }, { id: 2 }], 42];
  const { relay } = await relayOver(t, (req) => textResult(req.id, JSON.stringify(answers[req.id - 1])));

  const array = await callTool(relay.url, 1, 'list_traces');
  const scalar = await callTool(relay.url, 2, 'count_traces');

  assert.deepEqual(JSON.parse(array.body.result.content[0].text), { file: path.join('checks', 'mcp', '001-list_traces.json'), rows: [{ id: 1 }, { id: 2 }] });
  assert.deepEqual(JSON.parse(scalar.body.result.content[0].text), { file: path.join('checks', 'mcp', '002-count_traces.json'), value: 42 });
  assert.equal(array.body.result.structuredContent, undefined, 'no structuredContent is invented for a text-only result');
});

test('a large bare-array result is summarised by its rows like a paged one', async (t) => {
  const rows = Array.from({ length: 500 }, (_, i) => ({ id: i, span_id: `s${i}`, model: 'sonnet-5' }));
  const { relay } = await relayOver(t, (req) => textResult(req.id, JSON.stringify(rows)));

  const summary = JSON.parse((await callTool(relay.url, 1, 'list_spans')).body.result.content[0].text);

  assert.equal(summary.row_count, 500);
  assert.deepEqual(summary.fields, ['id', 'span_id', 'model']);
  assert.equal(summary.cursor, undefined);
});

test('a tool result whose text is not JSON passes through untouched and nothing is saved', async (t) => {
  const { relay, workDir } = await relayOver(t, (req) => textResult(req.id, 'Trace not found.'));

  const { body } = await callTool(relay.url, 1, 'get_trace');

  assert.deepEqual(body.result.content, [{ type: 'text', text: 'Trace not found.' }]);
  assert.deepEqual(fs.readdirSync(path.join(workDir, 'checks', 'mcp')), []);
});

test('an upstream answer that is not JSON-RPC is forwarded verbatim with its status', async (t) => {
  const { relay } = await relayOver(t, () => '<html>bad gateway</html>', 502);

  const res = await fetch(relay.url, { method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }) });

  assert.equal(res.status, 502);
  assert.equal(await res.text(), '<html>bad gateway</html>');
});

test('a request body that is not JSON is refused with 400 and never reaches upstream', async (t) => {
  let reached = false;
  const { relay } = await relayOver(t, () => { reached = true; return '{}'; });

  const res = await fetch(relay.url, { method: 'POST', body: '{not json' });

  assert.equal(res.status, 400);
  assert.deepEqual(await res.json(), { error: 'invalid json' });
  assert.equal(reached, false);
});

test('an unreachable upstream is a 502 the agent can read, not a dropped connection', async (t) => {
  const relay = await startMcpRelay({ url: 'http://127.0.0.1:1/mcp', token: 't', workDir: tempWorkDir() });
  t.after(() => relay.close());

  const { status, body } = await callTool(relay.url, 1, 'get_trace');

  assert.deepEqual({ status, body }, { status: 502, body: { error: 'fetch failed' } });
});

test('a large single-object result is replaced by its field names and where it was saved', async (t) => {
  const finding = { id: 'f1', narrative: 'x'.repeat(20_000), evidence_counts: { exemplar: 3 } };
  const { relay, workDir } = await relayOver(t, (req) => JSON.stringify(jsonRpcToolResult(req.id, finding)));

  const summary = JSON.parse((await callTool(relay.url, 1, 'get_finding')).body.result.content[0].text);

  const file = path.join('checks', 'mcp', '001-get_finding.json');
  assert.deepEqual(summary, { file, note: 'result saved to file; too large to inline', fields: ['id', 'narrative', 'evidence_counts'] });
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(workDir, file), 'utf8')), finding);
});
