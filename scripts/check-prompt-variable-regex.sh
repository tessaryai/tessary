#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# prompt-regex gate. Single source of truth: invoked by both the Taskfile (`task check:prompt-regex`) and CI (via
# scripts/check.sh). A prompt variable is found by one regex that lives in four places: the server
# (PromptNames.VARIABLE), the Prompts page (promptCompile.ts), and both SDKs. A copy that drifts makes the playground,
# the SDK compile and the server disagree on which {{names}} a template holds, and no test spans the four languages.
# This reads each literal from its source and fails when they differ. Java's string escapes are undone first, so the
# four compare as the regex text itself.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

node - "$ROOT" <<'PARITY'
const fs = require('fs');
const root = process.argv[2];
const read = (path) => fs.readFileSync(root + '/' + path, 'utf8');

const sources = [
  ['backend/analysis/src/main/java/ai/tessary/promptlib/PromptNames.java',
    /Pattern VARIABLE = Pattern\.compile\("((?:[^"\\]|\\.)*)"\)/, (s) => s.replace(/\\(.)/g, '$1')],
  ['frontend/src/views/prompts/promptCompile.ts', /export const VARIABLE_PATTERN = \/(.+)\/g;/, (s) => s],
  ['packages/sdk-python/src/tessary_prompts/prompts.py', /VARIABLE_PATTERN = re\.compile\(r"([^"]+)"\)/, (s) => s],
  ['packages/sdk-ts/src/prompts.ts', /export const VARIABLE_PATTERN = \/(.+)\/g;/, (s) => s],
];

let failed = 0;
const found = [];
for (const [path, pattern, unescape] of sources) {
  const m = read(path).match(pattern);
  if (!m) {
    console.error(`prompt-regex: no variable pattern found in ${path}`);
    failed++;
    continue;
  }
  found.push([path, unescape(m[1])]);
}
const distinct = new Set(found.map(([, regex]) => regex));
if (distinct.size > 1) {
  console.error('prompt-regex: the variable pattern differs between copies:');
  for (const [path, regex] of found) console.error(`  ${regex}  ${path}`);
  failed++;
}
if (failed) {
  console.error('\nThe four copies must be the same regex. Change all of them together; do not add an exemption.');
  process.exit(1);
}
console.log(`prompt variable regex parity: ok (${found.length} copies: ${found[0][1]})`);
PARITY
