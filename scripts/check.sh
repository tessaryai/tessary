#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The project gate, `task check` (or `bash scripts/check.sh [slices]`).
#
#   bash scripts/check.sh                   # every gate CI runs
#   bash scripts/check.sh --skip-backend    # every gate but check-backend.sh (CI runs that as its own jobs)
#   bash scripts/check.sh rca,metering      # those backend areas (packages under ai.tessary), unit and integration
#   bash scripts/check.sh rca,frontend      # slices mix freely
#
# Every scripts/check-*.sh has exactly one MANIFEST row below, and every run goes through `_gate`.
#
# No gate reads a .md or .mdx file: a gate keyed on prose reds when the prose is edited, not when
# anything breaks. Two exceptions read machine text in markdown: check-blob-links.sh (link paths)
# and check-groundedness-setup.sh (checksum lines, download URLs, `## Restart`).
#
# A gate whose toolchain is missing must skip with a printed reason, never fail silently.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SLICES="${1:-}"
SKIP_BACKEND=0
if [ "$SLICES" = --skip-backend ]; then
    SKIP_BACKEND=1
    SLICES=""
fi

# One row per gate: id | script | RUN or EXCLUDED:<reason> | note.
_manifest() {
    cat <<'MANIFEST'
version-consistency|scripts/check-version-consistency.sh|RUN|no file holds a copy of the release version; image defaults float to `-latest`
classifier-quality-doc|scripts/check-classifier-quality-doc.sh|RUN|the quality page pins the revision serve.py serves and the catalog threshold
module-hygiene|scripts/check-module-hygiene.sh|RUN
license-headers|scripts/check-license-headers.sh|RUN|SPDX header presence over the tree
pipeline-vocabulary|scripts/check-pipeline-vocabulary.sh|RUN
contract-consistency|scripts/check-contract-consistency.sh|RUN
vendored-plugin|scripts/check-vendored-plugin.sh|EXCLUDED:fetches tessaryai/plugins and hard-fails on $CI, so per PR it reds on upstream commits; runs via `task contract:plugin`|declared here only so the completeness assertion can see it
caddy|scripts/check-caddy.sh|RUN
compose-artifact|scripts/check-compose-artifact.sh|RUN|docker-compose.yml is also the published OCI install artifact; pure text plus a client-side `docker compose config`
sandbox-runner|scripts/check-sandbox-runner-launcher.sh|RUN
mcp-bridge|scripts/check-mcp-bridge.sh|RUN
groundedness-serve|scripts/check-groundedness-serve.sh|RUN|setup runs serve.py from its URL, so an import, header or encoding break shows up nowhere else first
groundedness-setup|scripts/check-groundedness-setup.sh|RUN|a setup MD checksum line that lags serve.py or the template stops every install
blob-links|scripts/check-blob-links.sh|RUN|prompts, skills and docs name files by GitHub path, so a move 404s them
price-book-contract|scripts/check-price-book-contract.sh|RUN|tessary-home fetches the vendored price book by raw URL; nothing in this repo reads that URL
frontend|scripts/check-frontend.sh|RUN
backend|scripts/check-backend.sh|RUN
migrations-populated|scripts/check-migrations-populated.sh|EXCLUDED:wants Docker, a JDBC driver and minutes; run per migration that renames or narrows a persisted value|declared here only so the completeness assertion can see it
dependency-audit|scripts/check-dependency-audit.sh|EXCLUDED:manual only; the Maven leg needs NVD_API_KEY
open-artifacts|scripts/check-open-artifacts.sh|EXCLUDED:the built-artifact diff: builds and exports the four published open images, minutes and Docker; run via `task check:open:artifacts` (add --negative for the planted-violation proof), and by release.yml's verify-open-artifacts job against the images it just built|declared here only so the completeness assertion can see it
notice-coverage|scripts/check-notice-coverage.sh|EXCLUDED:manual only; builds the open images with Docker
MANIFEST
}

_manifest_row() {
    _manifest | awk -F'|' -v id="$1" '$1 == id { print; found = 1 } END { exit !found }'
}

_gate() {
    _gate_id="$1"
    shift
    _gate_row="$(_manifest_row "$_gate_id")" || {
        echo "check: no manifest row for gate '$_gate_id'. Add one." >&2
        exit 1
    }
    _gate_script="$(printf '%s\n' "$_gate_row" | cut -d'|' -f2)"
    _gate_disp="$(printf '%s\n' "$_gate_row" | cut -d'|' -f3)"

    case "$_gate_disp" in
        RUN)
            bash "$_gate_script" "$@"
            ;;
        EXCLUDED:*)
            echo "check: gate '$_gate_id' is EXCLUDED from this pipeline but something called it." >&2
            echo "       ${_gate_disp#EXCLUDED:}" >&2
            exit 1
            ;;
        *)
            echo "check: gate '$_gate_id' has an unreadable disposition '$_gate_disp'." >&2
            exit 1
            ;;
    esac
}

# Fails before any gate runs if a check script has no row, a row names a missing script, or a RUN
# row is never called.
_assert_manifest_complete() {
    _rows="$(_manifest | cut -d'|' -f2 | sort -u)"
    _disk="$(ls scripts/check-*.sh scripts/lib/check-*.sh 2>/dev/null || true)"
    _disk="$(printf '%s\n' "$_disk" | grep -v '^$' | sort -u)"

    _unlisted="$(comm -23 <(printf '%s\n' "$_disk") <(printf '%s\n' "$_rows"))"
    if [ -n "$_unlisted" ]; then
        {
            echo "check: these gate scripts exist on disk but no manifest row mentions them, so"
            echo "       nothing here decides whether they run at all."
            echo "       Add a row to _manifest in this file:"
            printf '%s\n' "$_unlisted" | sed 's/^/  /'
        } >&2
        exit 1
    fi

    _phantom=""
    for _r in $_rows; do
        [ -f "$_r" ] || _phantom="${_phantom}  $_r
"
    done
    if [ -n "$_phantom" ]; then
        {
            echo "check: these manifest rows point at scripts that are not there. Either the script"
            echo "       moved (update the row) or the gate is gone (delete the row) — a pipeline"
            echo "       that names a missing gate reports coverage it does not have:"
            printf '%s' "$_phantom"
        } >&2
        exit 1
    fi

    _unwired=""
    while IFS='|' read -r _id _script _disp _note; do
        [ "$_disp" = RUN ] || continue
        grep -qE "^[[:space:]]*_gate[[:space:]]+$_id([[:space:]]|$)" "$ROOT/scripts/check.sh" || _unwired="${_unwired}  $_id
"
    done < <(_manifest)
    if [ -n "$_unwired" ]; then
        {
            echo "check: these manifest rows are RUN but no \`_gate <id>\` call in this file ever"
            echo "       runs them, so the row claims coverage it does not have."
            echo "       Add the call to the ordered list below the manifest:"
            printf '%s' "$_unwired"
        } >&2
        exit 1
    fi
}
_assert_manifest_complete

# A slice's unit tests and its @SpringBootTest tests live in different modules, so areas are
# collected across all of them.
_slice_dirs() {
    find backend/*/src/test/java/ai/tessary -mindepth 1 -maxdepth 1 -type d 2>/dev/null
}

_areas() {
    _slice_dirs | while read -r d; do
        n=$(find "$d" -name '*Test.java' | wc -l | tr -d ' ')
        [ "$n" -gt 0 ] && printf '%s %s\n' "$(basename "$d")" "$n"
    done | awk '{c[$1]+=$2} END {for (a in c) printf "  %-16s %s test classes\n", a, c[a]}' | sort
}

_slice_exists() {
    _slice_dirs | grep -qx ".*/$1"
}

# Cheapest first, so a cheap break fails in seconds instead of behind the two slow gates.
if [ -z "$SLICES" ]; then
    _gate classifier-quality-doc
    _gate module-hygiene
    _gate license-headers
    _gate pipeline-vocabulary
    _gate contract-consistency
    _gate blob-links
    _gate caddy
    _gate version-consistency
    _gate compose-artifact
    _gate groundedness-serve
    _gate groundedness-setup
    _gate sandbox-runner
    _gate mcp-bridge
    _gate price-book-contract
    _gate frontend
    if [ "$SKIP_BACKEND" = 0 ]; then
        _gate backend
    fi
    exit 0
fi

# Validate every slice first: a typo fails with the valid names instead of running nothing.
want_frontend=0
patterns=""
bad=""
for slice in $(echo "$SLICES" | tr ',' ' '); do
    if [ "$slice" = frontend ]; then
        want_frontend=1
    elif _slice_exists "$slice"; then
        patterns="${patterns:+$patterns,}ai.tessary.$slice.**"
    else
        bad="${bad:+$bad }$slice"
    fi
done

if [ -n "$bad" ]; then
    {
        echo "check: unknown slice(s): $bad"
        echo ""
        echo "A slice is 'frontend' or one of these backend areas:"
        _areas
        echo ""
        echo "Combine them with commas: task check -- rca,metering,frontend"
    } >&2
    exit 1
fi

if [ -n "$patterns" ]; then
    (
        cd backend
        mvn -B -q spotless:apply
        mvn -B -q test-compile
        # Reactor-wide: -pl would drop the slice's tests in other modules. The typo guard above
        # is why failIfNoSpecifiedTests=false cannot mask a bad slice.
        mvn -B -q -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$patterns" test
    )
fi

# Repo-wide and cheap, so they run whatever the slice.
_gate license-headers
_gate price-book-contract
_gate compose-artifact

if [ "$want_frontend" = 1 ]; then
    _gate frontend
fi

echo ""
echo "check: ran a NARROWED gate ($SLICES) — this is NOT the full gate."
echo "       CI runs everything on the PR; run 'task check' with no argument if you"
echo "       want that answer before pushing."
