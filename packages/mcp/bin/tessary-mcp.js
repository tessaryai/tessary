#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
"use strict";

// stdio<->streamable-HTTP bridge for Tessary's MCP server. Reads one JSON-RPC
// message per line from stdin, POSTs it as-is to <origin>/mcp with the bearer
// token, and writes whatever comes back as one line on stdout. Exists for MCP
// clients that only know how to launch a stdio server (e.g. via `npx`) and
// have no streamable-HTTP transport of their own — the origin can otherwise
// be reached directly, no bridge required.

const readline = require("node:readline");

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--origin") args.origin = argv[++i];
    else if (arg === "--token") args.token = argv[++i];
    else if (arg.startsWith("--origin=")) args.origin = arg.slice("--origin=".length);
    else if (arg.startsWith("--token=")) args.token = arg.slice("--token=".length);
  }
  return args;
}

function resolveConfig(argv, env) {
  const args = parseArgs(argv);
  const origin = (args.origin || env.TESSARY_ORIGIN || "").replace(/\/+$/, "");
  const token = args.token || env.TESSARY_TOKEN;
  if (!origin) {
    throw new Error("missing origin. Pass --origin <url> or set TESSARY_ORIGIN.");
  }
  if (!token) {
    throw new Error("missing token. Pass --token <token> or set TESSARY_TOKEN.");
  }
  return { origin, token };
}

async function forward(origin, token, body) {
  const res = await fetch(`${origin}/mcp`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body,
  });
  // A batch made entirely of notifications answers 204 with no body — nothing to relay.
  if (res.status === 204) return null;
  return res.text();
}

function errorResponseFor(line, origin, err) {
  let id = null;
  try {
    id = JSON.parse(line).id ?? null;
  } catch {
    // line wasn't valid JSON; relay a null-id error rather than dropping it silently.
  }
  return JSON.stringify({
    jsonrpc: "2.0",
    id,
    error: { code: -32000, message: `tessary-mcp: request to ${origin}/mcp failed: ${err.message}` },
  });
}

function main() {
  let config;
  try {
    config = resolveConfig(process.argv.slice(2), process.env);
  } catch (err) {
    process.stderr.write(`tessary-mcp: ${err.message}\n`);
    process.exitCode = 1;
    return;
  }

  const rl = readline.createInterface({ input: process.stdin, terminal: false });

  rl.on("line", (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    forward(config.origin, config.token, trimmed)
      .then((responseText) => {
        if (responseText !== null) process.stdout.write(responseText + "\n");
      })
      .catch((err) => {
        process.stdout.write(errorResponseFor(trimmed, config.origin, err) + "\n");
      });
  });

  rl.on("close", () => process.exit(0));
}

if (require.main === module) {
  main();
}

module.exports = { parseArgs, resolveConfig };
