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

# ---- this build's bundle must not contain certain chunks or copy ----
# Asserted over dist/, not source: TypeScript will not fail a build over an absent view (the stub
# at src/paid/index.ts answers for it), and a successful `vite build` proves nothing about what
# came out. The only way to check what actually reached the bundle is to grep the emitted chunks.
#
# This runs in every checkout, unconditionally, so it is never quietly skipped on the machine most
# likely to have got it wrong.
fail=0

# Named chunks: each of these appearing here means a view this build should not ship reached the
# entry graph. vendor-charts is the tell for recharts: nothing in this tree's open entry graph
# renders a recharts component directly, so that chunk showing up means something pulled it in.
for chunk in Usage Pricing vendor-charts; do
    if ls dist/assets/"$chunk"-*.js >/dev/null 2>&1; then
        echo "ERROR: the OPEN bundle emitted a '$chunk' chunk. That surface is paid and must not be" >&2
        echo "       reachable from the open entry graph — check the @paid alias in vite.config.ts." >&2
        fail=1
    fi
done

# Copy: some surfaces are statically imported and land in the entry chunk, which has no name to
# test. A line of each one's own prose stands in for a named-chunk check.
#
# Every such surface needs a line here, or this check cannot see it come back.
while IFS= read -r marker; do
    [ -z "$marker" ] && continue
    if grep -rqF "$marker" dist/assets; then
        echo "ERROR: the OPEN bundle contains paid copy: $marker" >&2
        fail=1
    fi
done <<'MARKERS'
Metered units
book a demo
No SOP has been compiled
Already broken when this rule was fitted
Behavior profiles
Every one of them, not a sample
MARKERS

[ "$fail" = 0 ] || exit 1
echo "open bundle: no paid code"
