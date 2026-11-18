#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# @tessaryai/prompts (packages/sdk-ts) gate: install the dev toolchain from the lockfile, type-check
# source and tests, prove the publishable build emits, then run node:test on the .ts sources with
# Node's type stripping at 100 percent line, branch and function coverage of src/.
# Needs pnpm (the frontend gate needs it too) and Node 24 or newer (type stripping; check.yml pins 24).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/packages/sdk-ts"
P=check-sdk-ts
command -v pnpm >/dev/null 2>&1 || { echo "$P: needs pnpm on PATH" >&2; exit 1; }
major="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$major" -lt 24 ]; then
  if [ -n "${CI:-}" ]; then echo "$P: Node $major on CI; check.yml pins 24" >&2; exit 1; fi
  echo "$P: SKIPPED, Node 24 or newer is needed to run .ts tests (found $major); CI runs this gate" >&2
  exit 0
fi
pnpm install --frozen-lockfile
node_modules/.bin/tsc -p tsconfig.json --noEmit
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
node_modules/.bin/tsc -p tsconfig.build.json --outDir "$out"
[ -f "$out/index.js" ] && [ -f "$out/index.d.ts" ] || { echo "$P: build emitted no dist/index.js or index.d.ts" >&2; exit 1; }
node --experimental-test-coverage --test-coverage-include='src/**/*.ts' \
  --test-coverage-lines=100 --test-coverage-branches=100 --test-coverage-functions=100 \
  --test 'test/**/*.test.ts'
echo "ok $P: @tessaryai/prompts type-checks, builds, and tests at 100 percent coverage"
