#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Frontend gate: generated-file drift, type-check, tests with the coverage floor, production bundle.
# Assumes node_modules is installed (`pnpm install --frozen-lockfile` or `task frontend:install`).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/frontend"

_gen_tmp="$(mktemp)"
_routes_tmp="$(mktemp)"
trap 'rm -f "$_gen_tmp" "$_routes_tmp"' EXIT
node_modules/.bin/openapi-typescript ../backend/contract/src/main/resources/openapi/tessary-api.json -o "$_gen_tmp" >/dev/null
if ! diff -q "$_gen_tmp" src/api/generated/schema.d.ts >/dev/null; then
  echo "ERROR: frontend/src/api/generated/schema.d.ts is out of date with the OpenAPI spec." >&2
  echo "Run 'pnpm run generate:api' in frontend/ (after regenerating the spec with 'task contract:openapi'" >&2
  echo "if the backend contract changed) and commit the result." >&2
  diff "$_gen_tmp" src/api/generated/schema.d.ts | head -40 >&2
  exit 1
fi

node scripts/generate-route-manifest.mjs "$_routes_tmp" >/dev/null
if ! diff -q "$_routes_tmp" src/routeManifest.generated.json >/dev/null; then
  echo "ERROR: frontend/src/routeManifest.generated.json is out of date with the routes in App.tsx." >&2
  echo "Run 'pnpm run generate:routes' in frontend/ and commit the result." >&2
  diff "$_routes_tmp" src/routeManifest.generated.json | head -40 >&2
  exit 1
fi

# The only type-check: `build` does not run tsc.
pnpm run lint
pnpm run coverage
pnpm run build
