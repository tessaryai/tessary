#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The project gate. Invoked by the Taskfile (`task check`, `task check -- <slices>`); usable
# directly as `bash scripts/check.sh [slices]`.
#
# With no argument it runs every check CI runs, cheapest first. With a comma-separated list of
# SLICES it narrows to those: a slice is a backend product area (a package under
# `ai.tessary` in the test tree) or the literal `frontend`.
#
#   bash scripts/check.sh                         # everything
#   bash scripts/check.sh rca,metering            # those two backend areas, unit AND integration
#   bash scripts/check.sh frontend                # type-check + bundle, no Maven, no Docker
#   bash scripts/check.sh rca,frontend            # mix freely
#
# Every gate is declared once in the MANIFEST below with its disposition, and every invocation in
# both code paths goes through `_gate`, which reads that manifest. There is no second list of
# gates to keep in step with this one.
#
# ---------------------------------------------------------------------------------------------
# STANDING RULE: NO GATE IN THIS PIPELINE READS A .md OR .mdx FILE.
# ---------------------------------------------------------------------------------------------
# Not to check a link, not a heading, not that a documented command matches a published one, not to
# read a table for a list of things to then assert structurally. Two named exceptions, both reading
# machine text a coding agent follows rather than prose a person rewrites: check-blob-links.sh reads
# only the paths of `blob/main` links (a moved file 404s every prompt and skill that names it), and
# check-groundedness-setup.sh reads the setup MDs' checksum lines, download URLs and `## Restart`.
#
# Documentation is prose that people rewrite, and a gate keyed on prose reds when the prose is
# edited, not when anything breaks. That happened: check.yml was armed per PR and went red
# immediately because a README rewrite landed while check-readme-front-door.sh still asserted the
# old one's headings, phrases and command ordering. The gate printed `ok the published install is
# present` in the same run it failed. A gate that reds on wording teaches people to skip gate
# failures, and then the real ones get skipped too.
#
# Gone, so nobody re-adds them thinking it was an oversight: docs-links (resolved relative links
# across the markdown files), selfhost-health (read setup.mdx to decide which services must carry a
# health probe), required-inputs (passed an input with no default if setup.mdx's required-variable
# table listed it) and connect-route (string-matched prose fragments from setup.mdx against JSX),
# all dropped 2026-09-09 and since deleted. readme-front-door was deleted outright too: excluded
# from the pipeline it asserted nothing, and kept on disk it invited someone to run it by hand and
# believe the answer, which two of its own checks could not give (an untagged fence let the
# install-ordering check pass, and its nav lookup matched any label, not the settings group it
# named).
# check-pipeline-vocabulary.sh's `--include='*.md'`, check-compose-artifact.sh's published-command
# clause and check-contract-consistency.sh's two AUTHORING_CONTRACT.md / SKILL.md legs were cut out
# of otherwise-mechanical gates.
#
# Nothing replaces it, deliberately. Docs drift is caught by people reading docs. If a fact matters
# enough to gate on, it belongs somewhere a machine owns: a config file, a schema, a constant. Not a
# sentence.

# THE CI-CALLABLE INTERFACE IS THIS SCRIPT, NOT THE TASK TARGET. CI never runs the Taskfile (see
# the note at the bottom of this header); the per-PR job calls `bash scripts/check.sh` directly,
# and `task check` is a thin wrapper over the same line. That job needs five toolchains in front of
# it, not one, so a single job running this line has to assemble all of them:
#   actions/setup-java java-version 25      -- check-backend.sh hard-fails on any other JDK
#   pnpm/action-setup + actions/setup-node  -- check-frontend.sh runs tsc and a real vite build
#   astral-sh/setup-uv                      -- check-groundedness-serve.sh (pytest over serve.py) and
#                                               cfn-lint in check-groundedness-setup.sh
#   actions/setup-python                    -- check-vendored-plugin-rules.sh (plus pip pyyaml +
#                                               pytest)
#   caddy on PATH                           -- check-caddy.sh
# If a toolchain is genuinely unavailable, the gate must SKIP with a printed reason: never fail
# silently, and never be quietly dropped from the manifest.
#
# `task check` WITH NO ARGUMENT RUNS EVERYTHING, deliberately: other automation depends on it as
# the default test command.
#
# ---------------------------------------------------------------------------------------------
# THREE GATE SETS
# ---------------------------------------------------------------------------------------------
# "Everything" is not one set. It is three, and they are not nested:
#   1. THIS SCRIPT: the manifest below, minus the rows marked EXCLUDED.
#   2. .github/workflows/drift-checks.yml: ONE job, workflow_dispatch only, a gate this script does
#      not run: check-vendored-plugin.sh (EXCLUDED when check.yml went to `pull_request:`; its
#      offline rules half runs here as `vendored-plugin-rules`). It was fourteen jobs until the
#      trim that deleted every job duplicating a row below.
#   3. Standalone Taskfile targets neither pipeline runs the same way: `migrations:populated`
#      (deliberately in neither).
# The manifest still carries a row for every `scripts/check-*.sh` on disk, EXCLUDED ones included,
# because the completeness assertion below is what stops a gate from being deleted from the
# pipeline by accident and saying nothing.
#
# ---------------------------------------------------------------------------------------------
# WHAT EACH GATE IS FOR (dispositions live in the manifest, not here)
# ---------------------------------------------------------------------------------------------
#   scripts/check-version-consistency.sh   (no file holds a copy of the version; image defaults float)
#   scripts/check-classifier-quality-doc.sh (the quality page vs the heads/thresholds deployed)
#   scripts/check-module-hygiene.sh        (reactor/Docker module drift)
#   scripts/check-pipeline-vocabulary.sh   (SQL naming a relation the classifier cutover dropped)
#   scripts/check-contract-consistency.sh  (vendored contract drift)
#   scripts/check-vendored-plugin.sh       (the vendored plugin's own rules + freshness vs plugins@main)
#   scripts/check-vendored-plugin-rules.sh (the rules half alone: offline pytest over contract/tests)
#   scripts/check-backend.sh               (mvn -B verify: tests + static analysis)
#   scripts/check-frontend.sh              (contract-drift guard + pnpm lint + pnpm test + pnpm build)
#   scripts/check-caddy.sh                 (caddy validate)
#   scripts/check-sandbox-runner-launcher.sh (node:test docker-backend coverage against a fake
#                                          Docker daemon over a temp unix socket; no real Docker
#                                          needed.)
#   scripts/check-mcp-bridge.sh            (node:test over packages/mcp, the @tessaryai/mcp stdio bridge)
#   scripts/check-groundedness-serve.sh    (classifiers/groundedness/serve.py runs as one file from
#                                          its URL: pytest over its standalone import, PEP 723 header,
#                                          encoding answer key and contract fixtures. Needs uv, and
#                                          installs pytest only)
#   scripts/check-blob-links.sh            (every github.com/tessaryai/tessary/blob/main/<path> link
#                                          in frontend/src, skills/, docs/, the groundedness setup
#                                          MDs, setup.md, instrument.md and README.md names a file
#                                          in the tree, and a #anchor names a heading)
#   scripts/check-groundedness-setup.sh    (the groundedness setup MDs' checksum lines equal serve.py
#                                          and the template, downloads are at <ref>, the template
#                                          lints and checks serve.py before running it. Needs uv
#                                          for cfn-lint)
#
# One gate is deliberately NOT in this pipeline for its cost alone, and carries an EXCLUDED row so
# that fact is declared rather than implied by absence:
#   scripts/check-migrations-populated.sh (`task migrations:populated`). Everything here migrates
#     EMPTY databases, where a failing `ADD CONSTRAINT` and a zero-row `UPDATE` both pass; that
#     script builds a populated one and asserts. It wants Docker, a Maven-resolved JDBC driver and
#     minutes, so it is run per migration that renames or narrows a persisted value.
#
# ---------------------------------------------------------------------------------------------
# CI IS A PER-PR GATE. .github/workflows/check.yml runs this script, this manifest, on
# `pull_request:`, so "local green => CI green" holds by construction on the same script rather than
# on a weekly cron nobody watched. secret-scan.yml and boot-checks.yml are armed per PR alongside
# it, and codeql.yml runs weekly. price-book-refresh.yml runs daily; its bot-authored PRs do not
# trigger check.yml, so it runs the price-book-contract gate itself before pushing. Everything else
# is workflow_dispatch only, namespace-recheck.yml notably included, so that check runs nowhere
# automatically; its header says what that costs.
#
# Nothing is merge-BLOCKING: this repo's plan tier offers neither branch protection nor rulesets, so
# a red run can be merged past and only convention stops it.
#
# What CI runs per PR that this script does NOT is boot-checks.yml's Docker-backed jobs, each an
# EXCLUDED row below; drift-checks.yml's one job is dispatch-only.
#
# CI never runs the Taskfile, only these scripts, which is why the gate's own logic lives here
# rather than inline in Taskfile.yml.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SLICES="${1:-}"

# ---- the gate manifest ----
# One row per gate: id | script | disposition | note.
# This is the single declaration of what the pipeline is. Dispositions:
#
#   RUN                      run it.
#   EXCLUDED:<reason>        not part of this pipeline. Calling `_gate` on one is a bug and fails
#                            loudly; the row exists so the completeness assertion below can see
#                            the script and so "not here" is declared, not implied.
_manifest() {
    cat <<'MANIFEST'
version-consistency|scripts/check-version-consistency.sh|RUN|the git tag release.yml pushes is the only source of truth for a published version; asserts no file holds a copy and every machine-resolved image default floats to the release `-latest` tag. The artifact's pin is stamped by scripts/lib/pin-compose-version.py and checked by check-compose-artifact.sh. Pure text; unlike check-selfhost-images.sh, no Docker or registry call.
classifier-quality-doc|scripts/check-classifier-quality-doc.sh|RUN|devdocs/reference/classifier-quality.md pins the public groundedness model: the revision classifiers/groundedness/serve.py serves and the catalog's threshold, both in this tree
module-hygiene|scripts/check-module-hygiene.sh|RUN
license-headers|scripts/check-license-headers.sh|RUN|SPDX header presence over the tree; the script's own header names the interim manual-audit + weekly-CI posture it runs under until branch protection is available
pipeline-vocabulary|scripts/check-pipeline-vocabulary.sh|RUN
contract-consistency|scripts/check-contract-consistency.sh|RUN
vendored-plugin|scripts/check-vendored-plugin.sh|EXCLUDED:dropped 2026-09-09. Its freshness half fetches tessaryai/plugins over the network and hard-fails on $CI, so per PR it reds pull requests over upstream commits and transient network failures unrelated to the diff. Right check, wrong trigger; it runs in the dispatch-only drift-checks.yml and via `task contract:plugin`. Its offline rules half runs per PR as the `vendored-plugin-rules` row|declared here only so the completeness assertion can see it
vendored-plugin-rules|scripts/check-vendored-plugin-rules.sh|RUN|phase 1 of check-vendored-plugin.sh on its own: pytest over contract/tests, the vendored validator rules the platform's import depends on. Offline and sub-second; needs python3 with pyyaml and pytest. check-vendored-plugin.sh calls this script, so the rules have one definition
caddy|scripts/check-caddy.sh|RUN
compose-artifact|scripts/check-compose-artifact.sh|RUN|the one-command install: docker-compose.yml is also the OCI artifact behind `docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y`, so it must stay publishable (long-syntax ports, literal memory limits) and mount no host path in a default-profile service; the build strip that makes the artifact must remove build sections and nothing else; pure text plus a client-side `docker compose config`, no daemon and no network
sandbox-runner|scripts/check-sandbox-runner-launcher.sh|RUN|needs only node, already on PATH for the frontend gate
mcp-bridge|scripts/check-mcp-bridge.sh|RUN|the @tessaryai/mcp stdio bridge's node:test suite, which otherwise runs only in release.yml right before npm publish; zero dependencies, needs only node
groundedness-serve|scripts/check-groundedness-serve.sh|RUN|setup runs serve.py from its URL, so this is the only place an import, header or encoding break shows up before a user runs it
groundedness-setup|scripts/check-groundedness-setup.sh|RUN|a setup MD whose checksum line lags serve.py or the template stops every install at the checksum, and nothing else reads that line before a user does. A named exception to the no-markdown rule: it reads checksum lines, URLs and one heading, not prose
blob-links|scripts/check-blob-links.sh|RUN|the in-app prompts, skills and docs send people and agents to files by their GitHub path, so a move 404s them all. A named exception to the no-markdown rule: it reads link paths and, for an anchor, headings, never prose
price-book-contract|scripts/check-price-book-contract.sh|RUN|the vendored price book's path and shape are a contract tessary-home fetches by raw URL; nothing in this repo reads that URL, so this gate is the only place a move, rename or reshape shows up. Repo-wide and cheap (one JSON parse, a few greps), so it runs on every slice too
frontend|scripts/check-frontend.sh|RUN
backend|scripts/check-backend.sh|RUN|the JDK-25 guard runs before Maven does
migrations-populated|scripts/check-migrations-populated.sh|EXCLUDED:wants Docker, a JDBC driver and minutes; run per migration that renames or narrows a persisted value|declared here only so the completeness assertion can see it
open-boot|scripts/check-open-boot.sh|EXCLUDED:wants Docker and minutes to boot a real stack; run via `task check:open:boot` or boot-checks.yml, never part of `task check`|declared here only so the completeness assertion can see it
open-boot-selfhost|scripts/check-open-boot-selfhost.sh|EXCLUDED:the gate's second leg: boots the self-host docker-compose.yml under the production profile; same Docker-and-minutes cost, run via `task check:open:boot:selfhost` or boot-checks.yml's second job, never part of `task check`|declared here only so the completeness assertion can see it
selfhost-quickstart|scripts/check-selfhost-quickstart.sh|EXCLUDED:the quickstart rehearsal executes docs/self-hosting/setup.mdx's own command blocks verbatim in a clean-room export on the compose defaults (ports 80/443), asserts every Check as the page words it, walks the three ladder checkpoints and the control arm, and prints the clock; Docker, network, minutes; run via `task check:selfhost:quickstart` or boot-checks.yml|declared here only so the completeness assertion can see it
selfhost-images|scripts/check-selfhost-images.sh|EXCLUDED:needs Docker and the network to hit two public registries with no login, and a released `v<semver>` git tag (or --version=) to know what to ask for; run via `task check:selfhost:images`, by the quickstart rehearsal before its first timed command, and by boot-checks.yml, never part of `task check`|declared here only so the completeness assertion can see it
zero-egress|scripts/check-zero-egress.sh|EXCLUDED:layers on the quickstart rehearsal with an internal network, a DNS sink and the opt-out set, asserts nothing outside the published permitted set at three vantage points, then proves the instrument with the heartbeat as positive control and a planted call; Docker, ports 80/443, minutes; run via `task check:zero:egress` or boot-checks.yml|declared here only so the completeness assertion can see it
selfhost-compose-artifact|scripts/check-selfhost-compose-artifact.sh|EXCLUDED:the one-command install's end-to-end rehearsal — publishes the artifact to a throwaway TLS registry, boots `docker compose -f oci://...:compose up -d -y` from an empty directory and asserts the working directory stays empty; builds four images and boots a stack, so Docker and minutes; run via `task check:selfhost:compose` or boot-checks.yml's fourth job, never part of `task check`|declared here only so the completeness assertion can see it
dependency-audit|scripts/check-dependency-audit.sh|EXCLUDED:manual/on-demand only — not in CI since 2026-09-02 (owner: no real keys in checks; the Maven leg hard-fails without NVD_API_KEY); runs OWASP dependency-check-maven + pnpm audit + pip-audit
namespaces|scripts/check-namespaces.sh|EXCLUDED:the namespace ownership recheck: hits Docker Hub, GitHub and Hugging Face over the network; run via `task check:namespaces`, quarterly by namespace-recheck.yml and before every release by release.yml, never part of `task check`|declared here only so the completeness assertion can see it
exposure-sweep|scripts/check-exposure-sweep.sh|EXCLUDED:the fresh-deployment exposure sweep: boots the self-host artifact and probes it, Docker and minutes; run via `task check:exposure:sweep` (--record to refresh scripts/lib/exposure-sweep-baseline.txt) and by boot-checks.yml's third job, never part of `task check`|declared here only so the completeness assertion can see it
open-artifacts|scripts/check-open-artifacts.sh|EXCLUDED:the built-artifact diff: builds and exports the four published open images, minutes and Docker; run via `task check:open:artifacts` (add --negative for the planted-violation proof), and by release.yml's verify-open-artifacts job against the images it just built|declared here only so the completeness assertion can see it
notice-coverage|scripts/check-notice-coverage.sh|EXCLUDED:manual/on-demand only — needs Docker to build the open backend and frontend images, same cost class as check-open-boot.sh; run directly with `bash scripts/check-notice-coverage.sh`
MANIFEST
}

_manifest_row() {
    _manifest | awk -F'|' -v id="$1" '$1 == id { print; found = 1 } END { exit !found }'
}

# Every gate invocation in BOTH code paths goes through here, so the full run and a sliced run
# can never disagree about what a gate does.
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

# ---- the manifest is complete ----
# The failure this catches has happened before: a gate stops running and nothing says so. A new
# scripts/check-*.sh that nobody wired in is invisible; a manifest row whose script was moved or
# deleted is a pipeline that lies about its coverage. Both are a hard failure here, before any
# gate runs, and both name the file and the fix.
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

    # A RUN row is a promise that the gate runs; the ordered list below is what keeps it. A row
    # whose id never reaches `_gate` is coverage this file claims and does not have, so every
    # RUN row must have a literal `_gate <id>` call here.
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

# A slice is a package under ai.tessary, and since the module split those packages are spread
# across the reactor's modules: `rca` lives in analysis/, `storage` in substrate/, and every
# @SpringBootTest for both lives in app/. So the areas are collected across ALL modules rather than
# from one directory; a slice that resolved against only one module would silently miss most of its
# tests. Adding a module needs no change here.
_slice_dirs() {
    find backend/*/src/test/java/ai/tessary -mindepth 1 -maxdepth 1 -type d 2>/dev/null
}

# Every backend area that actually holds tests, one per line, summed across modules.
_areas() {
    _slice_dirs | while read -r d; do
        n=$(find "$d" -name '*Test.java' | wc -l | tr -d ' ')
        [ "$n" -gt 0 ] && printf '%s %s\n' "$(basename "$d")" "$n"
    done | awk '{c[$1]+=$2} END {for (a in c) printf "  %-16s %s test classes\n", a, c[a]}' | sort
}

# True when any module has a test package with this name.
_slice_exists() {
    _slice_dirs | grep -qx ".*/$1"
}

# ---- no argument: the full gate ----
# Cheapest first, so a contract or Caddyfile break fails in seconds instead of behind the two slow
# gates. Measured on an M-series laptop: contract/caddy a few seconds each,
# frontend ~40s (tsc --noEmit ~20s, then tsc -b + vite build ~20s), backend ~200s. Running the two
# slow ones concurrently would save ~40s of ~250 but requires buffering both to keep a failure
# readable, which costs all streaming output.
# The ORDER below is load-bearing and is not derived from the manifest: the manifest declares what
# each gate does, this declares how long you wait to find out.
if [ -z "$SLICES" ]; then
    _gate classifier-quality-doc
    _gate module-hygiene
    _gate license-headers
    _gate pipeline-vocabulary
    _gate contract-consistency
    _gate blob-links
    _gate vendored-plugin-rules
    _gate caddy
    _gate version-consistency
    _gate compose-artifact
    _gate groundedness-serve
    _gate groundedness-setup
    _gate sandbox-runner
    _gate mcp-bridge
    _gate price-book-contract
    _gate frontend
    _gate backend
    exit 0
fi

# ---- with an argument: validate EVERY slice before running anything ----
# A typo must fail instantly with the valid names, never run a silently-empty selection.
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

# ---- run the selected slices ----
if [ -n "$patterns" ]; then
    (
        cd backend
        # Reactor-wide, matching `task backend:format`: Spotless is declared on the aggregator, so
        # `shared` and `contract` are formatted and gated too.
        mvn -B -q spotless:apply
        mvn -B -q test-compile
        # Reactor-wide: a slice's unit tests and its @SpringBootTest integration tests live in
        # different modules, so narrowing with -pl would drop half of them. Most modules match
        # nothing, hence failIfNoSpecifiedTests=false; the typo guard above already catches a bad
        # slice name, so this cannot mask one.
        mvn -B -q -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$patterns" test
    )
fi

# ALWAYS, whatever slice was asked for. The license-header gate and the price-book contract are
# both repo-wide invariants and both cheap (a text scan / one JSON parse), so a narrow
# `task check -- rca` must not be a hole a new header-less file or a moved price book slips through.
_gate license-headers
_gate price-book-contract
# Also unconditional, for the same reason: docker-compose.yml is the PUBLISHED one-command
# install, so any edit to it from any slice can break a remote install while `docker compose up`
# stays green locally. Pure text plus a client-side render, so it costs a second.
_gate compose-artifact

if [ "$want_frontend" = 1 ]; then
    _gate frontend
fi

echo ""
echo "check: ran a NARROWED gate ($SLICES) — this is NOT the full gate."
echo "       CI runs everything on the PR; run 'task check' with no argument if you"
echo "       want that answer before pushing."
