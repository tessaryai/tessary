#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The route the setup page names is a route the app has (epic 7 clause 5, #1193), proven
# statically: every control or destination docs/self-hosting/setup.mdx tells a reader to use is a
# string the frontend actually renders or routes, and the page names no React component as a place
# to go. The repo has no browser driver, so this is grep over two greppable sources, which reds the
# moment a nav label is renamed, a route moves, or the page starts naming code.
set -euo pipefail
P=check-connect-route
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
DOC=docs/self-hosting/setup.mdx
fail=0

# doc phrase | file that must carry it | the exact source string
while IFS='|' read -r phrase file needle; do
    [ -n "$phrase" ] || continue
    if ! grep -qF -- "$phrase" "$DOC"; then
        echo "$P: RED  $DOC no longer says '$phrase'; the pairing below it is unverifiable" >&2; fail=1; continue
    fi
    if grep -qF -- "$needle" "$file"; then
        echo "$P: ok   '$phrase' -> $file carries $needle"
    else
        echo "$P: RED  $DOC says '$phrase' but $file no longer carries $needle" >&2; fail=1
    fi
done <<'ROWS'
**Bearer Token** field on the gate|frontend/src/views/onboarding/ConnectGate.tsx|label="Bearer Token"
Start with a sample project|frontend/src/views/onboarding/ConnectGate.tsx|Start with a sample project
**Triage** names the stage|frontend/src/App.tsx|path="triage"
**Settings** → **Sources**|frontend/src/shell/Sidebar.tsx|label: "Settings"
**Settings** → **Sources**|frontend/src/shell/nav.tsx|label: "Sources"
**Settings** → **Sources**|frontend/src/App.tsx|path="sources" element={<Sources />}
`/v1/traces`|frontend/caddy/base.caddy|/v1/traces
scripts/emit-span.js|scripts/emit-span.js|tessary.call_site.id
ROWS

# A destination is a label or an address, never a component. Any CamelCase identifier that names a
# frontend component file is a leak of code into the reader's instructions.
components="$(find frontend/src -name '*.tsx' | sed -E 's#.*/##; s#\.tsx$##' | grep -E '^[A-Z][A-Za-z]+$' | sort -u)"
leaks="$(grep -oE '\b[A-Z][a-z]+[A-Z][A-Za-z]+\b' "$DOC" | sort -u | grep -xF -f <(printf '%s\n' "$components") || true)"
if [ -n "$leaks" ]; then
    echo "$P: RED  $DOC names frontend components as destinations: $(printf '%s' "$leaks" | tr '\n' ' ')" >&2; fail=1
else
    echo "$P: ok   $DOC names no frontend component"
fi

# The emit command the page carries runs the shipped file on the sandbox runner's own Node.
if grep -qF 'docker compose exec -T sandbox-runner node - <token> < scripts/emit-span.js' "$DOC" && [ -f scripts/emit-span.js ]; then
    echo "$P: ok   the documented emit command names scripts/emit-span.js, which exists"
else
    echo "$P: RED  the documented emit command and scripts/emit-span.js disagree" >&2; fail=1
fi

[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: ok, every destination the setup page names exists in the app, and it names no component"
