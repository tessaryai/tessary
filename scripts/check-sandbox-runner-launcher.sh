#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# sandbox-runner gate. Single source of truth: invoked by both the Taskfile
# (`task sandbox-runner:check`) and CI (.github/workflows/ci.yml) — the only job that actually runs
# any sandbox-runner test, so a script covering just launcher/ leaves everything else in
# sandbox-runner/ unwired into any gate. No test harness existed for
# server.js before the docker sandbox driver was added — the first thing that needed real
# coverage: hardening flags a compile-time check cannot see, and a concurrency limiter whose bug
# would only show up as a resource-limit incident in someone's self-hosted deployment.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/sandbox-runner/launcher"

node --check server.js

# node:test, zero new dependency — same constraint the docker backend itself holds to (no
# docker-cli, no Engine API SDK; see server.js's Docker-backend header comment). Spins up a fake
# Docker daemon over a temp unix socket and runs the REAL server.js as a child process against
# it — see test/docker-backend.test.js's own header for why that shape and what it does and does
# NOT prove (a fake daemon proves the launcher asked Docker for the right thing; it cannot prove a
# spawned container actually runs — that is sandbox-runner/README.md's documented manual proof).
node --test

# sandbox-runner/agent-sandbox/test/agent-stream.test.js: F1/E coverage for
# agent-stream.js's runAgent() — a fresh-session retry must not lose the failing attempt's usage,
# and a session that already did real work must not be silently re-run at double cost. node:test
# with --experimental-test-module-mocks: mock.module still needs the mocked specifier
# (@opencode-ai/sdk) resolvable on disk even though it replaces its exports, and agent-stream.js
# itself requires undici at load time — so a real install comes first. --prod is enough (the test
# needs no devDependency); this package also depends on re2, a native addon, but does not import
# it, and the pnpm store already has it built. This package's own "test" script in package.json
# runs the same command but nothing else invoked it — added here, alongside the launcher's tests,
# so a regression fails this gate instead of none.
(cd "$ROOT/sandbox-runner/agent-sandbox" && pnpm install --frozen-lockfile --prod && node --experimental-test-module-mocks --test)

# Base-URL parity between the launcher and the backend's PlatformCatalog. Nothing asserted this
# before, and the drift it would have caught was real and shipped: ANTHROPIC held the bare host
# `https://api.anthropic.com` in both places, but BOTH consumers append a bare path to that value
# (OpenCode's @ai-sdk/anthropic appends `messages`; langchain4j's DefaultAnthropicClient appends
# `messages` too — its own default already carries the /v1/). So every agentic run 404'd, and
# OpenCode folded the 404 into an empty assistant turn, which surfaced as "opencode produced no
# usable reply" with zero tokens and a valid key.
#
# The contract this pins: `default_base_url` is the FULL API root — the string a client appends a
# bare path to — and there is exactly one correct form of it per provider, so the two tables must
# agree literally. GEMINI is compared like the rest (supports_base_url=false only stops the UI from
# offering an override; the launcher still uses its default). CUSTOM/BEDROCK/BEDROCK_MANTLE have no
# default on either side and are skipped.
node - "$ROOT" <<'PARITY'
const fs = require('fs');
const root = process.argv[2];
const java = fs.readFileSync(root + '/backend/llm-runtime/src/main/java/ai/tessary/llm/PlatformCatalog.java', 'utf8');
const js = fs.readFileSync(root + '/sandbox-runner/launcher/server.js', 'utf8');

const MODE_CONST = { OPENAI: 'OPENAI_COMPAT_MODE', ANTHROPIC: 'ANTHROPIC_MODE', OPENROUTER: 'OPENROUTER_MODE',
  MOONSHOT: 'MOONSHOT_MODE', GEMINI: 'GEMINI_MODE', GLM: 'GLM_MODE', GROK: 'GROK_MODE' };

const fromJava = {};
for (const m of java.matchAll(/ModelProvider\.([A-Z_]+),\s*"[^"]*",\s*AUTH_\w+,\s*(?:true|false),\s*(?:"([^"]*)"|null)\)/g)) {
  fromJava[m[1]] = m[2] === undefined ? null : m[2];
}
const fromJs = {};
for (const m of js.matchAll(/case (\w+_MODE):\s*(?:\/\/[^\n]*\n\s*)*return '([^']+)';/g)) fromJs[m[1]] = m[2];

let failed = 0;
for (const [provider, modeConst] of Object.entries(MODE_CONST)) {
  const want = fromJava[provider];
  const got = fromJs[modeConst];
  if (want === undefined) { console.error(`parity: ${provider} not found in PlatformCatalog.java`); failed++; continue; }
  if (got === undefined) { console.error(`parity: ${modeConst} not found in defaultBaseUrlFor`); failed++; continue; }
  if (want !== got) {
    console.error(`parity: ${provider} base URL disagrees\n  PlatformCatalog.java: ${want}\n  launcher server.js:  ${got}`);
    failed++;
  }
}
if (failed) {
  console.error('\nBoth values are the FULL API root a client appends a bare path to. Fix whichever is wrong; do not add an exemption.');
  process.exit(1);
}
console.log(`base-URL parity: ok (${Object.keys(MODE_CONST).length} providers)`);
PARITY
