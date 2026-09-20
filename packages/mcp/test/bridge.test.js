// SPDX-License-Identifier: Apache-2.0
"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { spawn } = require("node:child_process");
const path = require("node:path");

const BIN_PATH = path.join(__dirname, "..", "bin", "tessary-mcp.js");

function startMockMcpServer(handler) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let raw = "";
      req.on("data", (chunk) => {
        raw += chunk;
      });
      req.on("end", () => handler(req, res, raw));
    });
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

function waitForLine(stream) {
  return new Promise((resolve, reject) => {
    let buf = "";
    const onData = (chunk) => {
      buf += chunk;
      const newlineAt = buf.indexOf("\n");
      if (newlineAt !== -1) {
        stream.off("data", onData);
        resolve(buf.slice(0, newlineAt));
      }
    };
    stream.on("data", onData);
    stream.on("error", reject);
  });
}

test("proxies a tools/list call from stdio to POST <origin>/mcp and relays the response", async () => {
  let receivedAuth = null;
  let receivedBody = null;

  const server = await startMockMcpServer((req, res, raw) => {
    receivedAuth = req.headers.authorization;
    receivedBody = JSON.parse(raw);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        jsonrpc: "2.0",
        id: receivedBody.id,
        result: { tools: [{ name: "get_project" }, { name: "list_cases" }] },
      }),
    );
  });
  const { port } = server.address();

  const child = spawn(process.execPath, [BIN_PATH], {
    env: {
      ...process.env,
      TESSARY_ORIGIN: `http://127.0.0.1:${port}`,
      TESSARY_TOKEN: "tsy_a_test_token",
    },
  });

  try {
    const responseLine = waitForLine(child.stdout);
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }) + "\n");
    const line = await responseLine;

    assert.equal(receivedAuth, "Bearer tsy_a_test_token");
    assert.equal(receivedBody.method, "tools/list");

    const response = JSON.parse(line);
    assert.equal(response.id, 1);
    assert.deepEqual(response.result.tools, [{ name: "get_project" }, { name: "list_cases" }]);
  } finally {
    child.kill();
    server.close();
  }
});

test("relays a 401 as a JSON-RPC error rather than hanging", async () => {
  const server = await startMockMcpServer((req, res) => {
    res.writeHead(401, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        jsonrpc: "2.0",
        id: null,
        error: { code: -32600, message: "unauthorized" },
      }),
    );
  });
  const { port } = server.address();

  const child = spawn(process.execPath, [BIN_PATH], {
    env: {
      ...process.env,
      TESSARY_ORIGIN: `http://127.0.0.1:${port}`,
      TESSARY_TOKEN: "tsy_a_bad_token",
    },
  });

  try {
    const responseLine = waitForLine(child.stdout);
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 7, method: "tools/list" }) + "\n");
    const line = await responseLine;

    // The bridge forwards the HTTP body verbatim on non-204 responses, including error status
    // codes — it's the caller's job to look at the JSON-RPC envelope, same as talking to /mcp directly.
    const response = JSON.parse(line);
    assert.equal(response.error.message, "unauthorized");
  } finally {
    child.kill();
    server.close();
  }
});

test("resolveConfig prefers explicit flags over env vars and errors when neither is set", () => {
  const { resolveConfig } = require("../bin/tessary-mcp.js");

  assert.deepEqual(
    resolveConfig(["--origin", "http://localhost:8000", "--token", "tsy_a_x"], {}),
    { origin: "http://localhost:8000", token: "tsy_a_x" },
  );
  assert.deepEqual(
    resolveConfig([], { TESSARY_ORIGIN: "http://localhost:8000/", TESSARY_TOKEN: "tsy_a_env" }),
    { origin: "http://localhost:8000", token: "tsy_a_env" },
  );
  assert.throws(() => resolveConfig([], {}), /missing origin/);
  assert.throws(() => resolveConfig(["--origin", "http://localhost:8000"], {}), /missing token/);
});

test("drains in-flight responses when stdin closes before the server answers", async () => {
  const server = await startMockMcpServer((req, res, raw) => {
    const { id } = JSON.parse(raw);
    setTimeout(() => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id, result: {} }));
    }, 200);
  });
  const { port } = server.address();

  const child = spawn(process.execPath, [BIN_PATH], {
    env: {
      ...process.env,
      TESSARY_ORIGIN: `http://127.0.0.1:${port}`,
      TESSARY_TOKEN: "tsy_a_test_token",
    },
  });

  try {
    let stdout = "";
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    const exited = new Promise((resolve) => child.on("close", resolve));

    // Two requests, then stdin closes while both are still outstanding.
    child.stdin.end(
      JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }) +
        "\n" +
        JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list" }) +
        "\n",
    );

    const code = await exited;

    assert.equal(code, 0);
    const ids = stdout
      .trim()
      .split("\n")
      .map((l) => JSON.parse(l).id)
      .sort();
    assert.deepEqual(ids, [1, 2]);
  } finally {
    child.kill();
    server.close();
  }
});
