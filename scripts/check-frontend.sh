#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Frontend gate: type-check (tsc) + production bundle (vite build). The bundle step catches failures
# the type-checker alone misses (bad import resolution, asset/plugin errors), so a change that passes
# `tsc` but breaks `vite build` still fails here.
#
# Single source of truth for the frontend gate: invoked by both the Taskfile (`task frontend:check`)
# and CI (.github/workflows/ci.yml). Dependency install is environment-specific and is NOT part of
# the gate — CI runs `pnpm install --frozen-lockfile`, local dev runs `task frontend:install`; this
# script assumes node_modules is already present.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/frontend"

# Contract drift guard: the generated API types (src/api/generated/schema.d.ts) are
# derived from the checked-in OpenAPI spec (backend/contract/.../evals-api.json). Regenerate into a temp
# file and diff against the working-tree copy — fail if it is stale — the frontend-side twin of the backend
# OpenApiSpecDriftTest, so a spec change that isn't regenerated on the FE can't silently drift. Git-
# independent (no git state needed). Regenerate with `pnpm run generate:api` and commit the result.
_gen_tmp="$(mktemp)"
_routes_tmp="$(mktemp)"
trap 'rm -f "$_gen_tmp" "$_routes_tmp"' EXIT
node_modules/.bin/openapi-typescript ../backend/contract/src/main/resources/openapi/evals-api.json -o "$_gen_tmp" >/dev/null
if ! diff -q "$_gen_tmp" src/api/generated/schema.d.ts >/dev/null; then
  echo "ERROR: frontend/src/api/generated/schema.d.ts is out of date with the OpenAPI spec." >&2
  echo "Run 'pnpm run generate:api' in frontend/ (after regenerating the spec with 'task contract:openapi'" >&2
  echo "if the backend contract changed) and commit the result." >&2
  diff "$_gen_tmp" src/api/generated/schema.d.ts | head -40 >&2
  exit 1
fi

# Route manifest drift guard (#849 AC2): src/routeManifest.generated.json is derived from the
# <Route> elements in src/App.tsx by an AST walk (scripts/generate-route-manifest.mjs) — the
# frontend-side sibling of the schema.d.ts guard above. Regenerate into a temp file and diff
# against the working-tree copy; fail if stale, so a route added to App.tsx without a matching
# manifest entry fails CI rather than silently going untracked. Regenerate with
# 'pnpm run generate:routes' and commit the result.
node scripts/generate-route-manifest.mjs "$_routes_tmp" >/dev/null
if ! diff -q "$_routes_tmp" src/routeManifest.generated.json >/dev/null; then
  echo "ERROR: frontend/src/routeManifest.generated.json is out of date with the routes in App.tsx." >&2
  echo "Run 'pnpm run generate:routes' in frontend/ and commit the result." >&2
  diff "$_routes_tmp" src/routeManifest.generated.json | head -40 >&2
  exit 1
fi

pnpm run lint

# Route render smoke test (#890): mounts the real <App/> at every 'view' entry in the route
# manifest above, under the open edition's default capabilities, and fails on a render error, a
# blank container, or an un-allowlisted console.error. Between lint and build deliberately — it
# needs the same tsc pass lint just ran (vite.config.ts's `test` block is typed by vitest/config),
# and it is cheaper to fail here than to wait for a production bundle first.
pnpm run test

pnpm run build

# ---- the open bundle carries no paid code (open-core D2/D4, #846) ----
# Asserted over dist/, not over source, and that is the whole point of the block. This half of the
# boundary has no enforcer: `check-open-boundary.sh` rule 2 is a text grep of frontend/src plus two
# config files, TypeScript will not fail a build because a paid view is absent (the open stub at
# src/paid/index.ts answers for it), and `vite build` succeeding proves nothing about what came out.
# The one question worth asking — did any paid code reach the open bundle — can only be asked of
# the emitted chunks.
#
# This runs in EVERY checkout, with or without the overlay, and that is deliberate. `frontend/`
# always builds the OPEN edition under this mechanism (the paid build has its own config, in the
# overlay), so these assertions are never conditional and never quietly skipped on the machine most
# likely to have got it wrong. The paid overlay carries the exact inverse of this block in a gate
# of its own: it asserts the same chunks and the same copy ARE present in the paid bundle.
fail=0

# Named chunks: Usage and Pricing are React.lazy'd by the paid registry, so each would appear here
# under its own name if it were ever built into the open app. vendor-charts is the third because
# Usage.tsx is the only file in either tree that renders a recharts component — if that chunk is
# emitted, recharts is reachable from the open entry graph, which means Usage came back.
for chunk in Usage Pricing vendor-charts; do
    if ls dist/assets/"$chunk"-*.js >/dev/null 2>&1; then
        echo "ERROR: the OPEN bundle emitted a '$chunk' chunk. That surface is paid and must not be" >&2
        echo "       reachable from the open entry graph — check the @paid alias in vite.config.ts." >&2
        fail=1
    fi
done

# Copy: the drift debug panel, the SOP rulebook rail and the baseline-evidence block are statically
# imported by the paid registry and land in its entry chunk, which has no name to test. A line of
# each one's own prose is what is unambiguously theirs.
#
# EVERY entry-chunk surface needs a line of its own here. One with none is covered by neither gate:
# the open side cannot see it come back and the paid side cannot see it go, so both stay green while
# the surface moves. Keep this list byte-identical to the one in the paid gate — they are inverses.
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
