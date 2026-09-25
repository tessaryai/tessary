#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Frontend gate: type-check (tsc) + production bundle (vite build). The bundle step catches failures
# the type-checker alone misses (bad import resolution, asset/plugin errors), so a change that passes
# `tsc` but breaks `vite build` still fails here.
#
# Single source of truth for the frontend gate: invoked by both the Taskfile (`task frontend:check`)
# and CI (.github/workflows/check.yml, via scripts/check.sh). Dependency install is environment-specific and is NOT part of
# the gate: CI runs `pnpm install --frozen-lockfile`, local dev runs `task frontend:install`; this
# script assumes node_modules is already present.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/frontend"

# Contract drift guard: the generated API types (src/api/generated/schema.d.ts) are
# derived from the checked-in OpenAPI spec (backend/contract/.../tessary-api.json). Regenerate into a temp
# file and diff against the working-tree copy, failing if it is stale: the frontend-side twin of the backend
# OpenApiSpecDriftTest, so a spec change that isn't regenerated on the FE can't silently drift. Git-
# independent (no git state needed). Regenerate with `pnpm run generate:api` and commit the result.
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

# Route manifest drift guard: src/routeManifest.generated.json is derived from the <Route>
# elements in src/App.tsx by an AST walk (scripts/generate-route-manifest.mjs), the frontend-side
# sibling of the schema.d.ts guard above. Regenerate into a temp file and diff against the
# working-tree copy; fail if stale, so a route added to App.tsx without a matching manifest entry
# fails CI rather than silently going untracked. Regenerate with 'pnpm run generate:routes' and
# commit the result.
node scripts/generate-route-manifest.mjs "$_routes_tmp" >/dev/null
if ! diff -q "$_routes_tmp" src/routeManifest.generated.json >/dev/null; then
  echo "ERROR: frontend/src/routeManifest.generated.json is out of date with the routes in App.tsx." >&2
  echo "Run 'pnpm run generate:routes' in frontend/ and commit the result." >&2
  diff "$_routes_tmp" src/routeManifest.generated.json | head -40 >&2
  exit 1
fi

pnpm run lint

# Route render smoke test: mounts the real <App/> at every 'view' entry in the route manifest
# above, under default capabilities, and fails on a render error, a blank container, or an
# un-allowlisted console.error. It runs between lint and build deliberately, needing the same tsc
# pass lint just ran (vite.config.ts's `test` block is typed by vitest/config), and it is cheaper
# to fail here than to wait for a production bundle first.
pnpm run test

pnpm run build
