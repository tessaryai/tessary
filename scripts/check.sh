#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The project gate. Invoked by the Taskfile (`task check`, `task check:open`, `task check -- <slices>`);
# usable directly as `bash scripts/check.sh [--edition open|all] [slices]`.
#
# With no argument it runs every check CI runs, cheapest first. With a comma-separated list of
# SLICES it narrows to those: a slice is a backend product area (a package under
# `ai.tessary` in the test tree) or the literal `frontend`.
#
#   bash scripts/check.sh                         # everything
#   bash scripts/check.sh rca,metering            # those two backend areas, unit AND integration
#   bash scripts/check.sh frontend                # type-check + bundle, no Maven, no Docker
#   bash scripts/check.sh rca,frontend            # mix freely
#   bash scripts/check.sh --edition open          # the OPEN edition of the whole gate
#   bash scripts/check.sh --edition open frontend # ...and narrowed, the flags compose
#
# ---------------------------------------------------------------------------------------------
# EDITIONS
# ---------------------------------------------------------------------------------------------
# Paid code lives in a separate overlay directory, and the public export is a folder filter, so
# the tree minus that directory has to stand on its own. `--edition open` is the command that
# runs the gate exactly that way.
#
# WHAT IT IS: an edition is a question this script asks each gate, not a second pipeline. Every
# gate is declared once in the MANIFEST below with its disposition under each edition, and every
# invocation in both code paths goes through `_gate`, which reads that manifest. There is no second
# list of gates to keep in step with this one, and a gate that runs in one edition and not the
# other has to say so out loud in the manifest.
#
# MODE IS A PROXY; A PHYSICAL DETACH IS THE PROOF. `--edition open` runs with the overlay
# directory still on disk and only changes what this script asks for; it cannot see a break that
# exists solely because the directory is gone. The seams that matter still resolve under any
# flag: `backend/pom.xml`'s `paid` profile activates on the overlay pom existing, and the Python
# gates reach a relocated test through `classifiers/pyproject.toml`'s second pythonpath entry,
# which pytest silently ignores when it isn't a directory. The gate you can run every day is
# `d=$(bash scripts/lib/export-simulate.sh) && (cd "$d" && bash scripts/check.sh --edition open)`;
# that's what actually proves the property, not moving the overlay directory aside, which leaves
# the git index believing files under it still exist, so `check-docs-links.sh` dies first with a
# stale-path error before any other gate runs.
#
# ---------------------------------------------------------------------------------------------
# STANDING RULE: NO GATE IN THIS PIPELINE READS A .md OR .mdx FILE.
# ---------------------------------------------------------------------------------------------
# Not to check a link, not a heading, not that a documented command matches a published one, not to
# read a table for a list of things to then assert structurally. Flat rule, no exceptions.
#
# Documentation is prose that people rewrite, and a gate keyed on prose reds when the prose is
# edited, not when anything breaks. That happened: check.yml was armed per PR and went red
# immediately because a README rewrite landed while check-readme-front-door.sh still asserted the
# old one's headings, phrases and command ordering. The gate printed `ok the published install is
# present` in the same run it failed. A gate that reds on wording teaches people to skip gate
# failures, and then the real ones get skipped too.
#
# Gone, so nobody re-adds them thinking it was an oversight: docs-links, selfhost-health,
# required-inputs, readme-front-door and connect-route (rows below carry the reason).
# check-open-boundary.sh's rule 6, check-pipeline-vocabulary.sh's `--include='*.md'`,
# check-compose-artifact.sh's published-command clause and check-contract-consistency.sh's two
# AUTHORING_CONTRACT.md / SKILL.md legs were cut out of otherwise-mechanical gates.
#
# Nothing replaces it, deliberately. Docs drift is caught by people reading docs and by the export
# manifest review, which is a human reading a file list. If a fact matters enough to gate on, it
# belongs somewhere a machine owns: a config file, a schema, a constant. Not a sentence.

# THE CI-CALLABLE INTERFACE IS THIS SCRIPT, NOT THE TASK TARGET. CI never runs the Taskfile (see
# the note at the bottom of this header); the per-PR open-edition job calls
# `bash scripts/check.sh --edition open` directly, and `task check:open` is a thin wrapper over
# the same line. That job needs five toolchains in front of it, not one, so a single job running
# this line has to assemble all of them:
#   actions/setup-java java-version 25      -- check-backend.sh hard-fails on any other JDK
#   pnpm/action-setup + actions/setup-node  -- check-frontend.sh runs tsc and a real vite build
#   astral-sh/setup-uv                      -- check-classifier-parity.sh, and the Python gates
#   actions/setup-python                    -- check-classify-service.sh, and the overlay's own
#                                               Python gate when it's present
#   caddy on PATH                           -- check-caddy.sh
# If a toolchain is genuinely unavailable, the gate must SKIP with a printed reason like any
# other: never fail silently, and never be quietly dropped from the manifest.
#
# `task check` WITH NO ARGUMENT IS UNCHANGED, deliberately: other automation depends on it as the
# default test command. The default edition is `all`, the slice contract is untouched, and
# `--edition` is parsed strictly ahead of the positional so a slice can never be mistaken for a
# flag or the reverse.
#
# ---------------------------------------------------------------------------------------------
# THREE GATE SETS, AND WHICH ONE `--edition` FILTERS
# ---------------------------------------------------------------------------------------------
# "Everything" is not one set. It is three, and they are not nested:
#   1. THIS SCRIPT: the manifest below, minus the rows marked EXCLUDED.
#   2. .github/workflows/drift-checks.yml: TWO jobs, workflow_dispatch only, and both are gates
#      this script does not run: check-conformance-parity.sh (EXCLUDED since #875) and
#      check-vendored-plugin.sh (EXCLUDED when check.yml went to `pull_request:`). It was fourteen
#      jobs until the trim that deleted every job duplicating a row below.
#   3. Standalone Taskfile targets neither pipeline runs the same way: `conformance:parity`
#      (in CI, not here), `classifiers:parity` (here, not in CI) and `migrations:populated`
#      (deliberately in neither).
# `--edition` filters set 1 and nothing else. The manifest still carries a row for every
# `scripts/check-*.sh` on disk, EXCLUDED ones included, because the completeness assertion below
# is what stops a gate from being deleted from the pipeline by accident and saying nothing.
#
# ---------------------------------------------------------------------------------------------
# WHAT EACH GATE IS FOR (dispositions live in the manifest, not here)
# ---------------------------------------------------------------------------------------------
#   scripts/check-docs-links.sh            (relative markdown links that no longer resolve)
#   scripts/check-version-consistency.sh   (no file holds a copy of the version; image defaults float)
#   scripts/check-classifier-quality-doc.sh (the quality page vs the heads/thresholds deployed)
#   scripts/check-module-hygiene.sh        (reactor/Docker module drift)
#   scripts/check-open-boundary.sh         (open code reaching into the overlay)
#   scripts/check-pipeline-vocabulary.sh   (SQL naming a relation the classifier cutover dropped)
#   scripts/check-contract-consistency.sh  (vendored contract drift)
#   scripts/check-vendored-plugin.sh       (the vendored plugin's own rules + freshness vs plugins@main)
#   scripts/check-backend.sh               (mvn -B verify: tests + static analysis; under
#                                          `--edition open` the same script runs with -P '!paid',
#                                          so there is one definition of the backend gate)
#   scripts/check-frontend.sh              (contract-drift guard + pnpm lint + pnpm build, then an
#                                          assertion over dist/ that no paid chunk or copy is in
#                                          the bundle. The build resolves '@paid' to the in-tree
#                                          stub frontend/src/paid/index.ts and never reads the
#                                          overlay, so it's already the open gate by construction.)
#   the overlay's check-paid-frontend.sh   (the same app with the '@paid' seam pointed at the
#                                          overlay's registry: tsc over both source roots, a real
#                                          vite build, then the inverse assertions. Lives in the
#                                          overlay for the same rule-5 reason check-compile-service.sh
#                                          does.)
#   scripts/check-caddy.sh                 (caddy validate)
#   scripts/check-classify-service.sh      (syntax + manifest consistency. models.json moved to
#                                          the overlay, so `--edition open` asserts an empty
#                                          manifest and the UNAVAILABLE_IN_OPEN_EDITION token
#                                          instead of the three built-in heads. The service itself
#                                          stays open either way.)
#   the overlay's check-slack-service.sh   (ruff + pytest with faked network. Overlay-owned: with
#                                          the overlay gone there's no Slack adapter and no gate
#                                          for one, the same shape as check-compile-service.sh below.)
#   scripts/check-sandbox-runner-launcher.sh (node:test docker-backend coverage against a fake
#                                          Docker daemon over a temp unix socket; no real Docker
#                                          needed. Open on both sides.)
#   the overlay's check-compile-service.sh (ruff + pytest: engine fit/export vs a direct export.
#                                          Lives in the overlay along with the service and the
#                                          engine it runs, so with the overlay gone there's no SOP
#                                          compile service and a skip is the true answer rather than a hole.)
#   scripts/check-classifier-parity.sh     (Python<->Java/JS classifier port pins. Three of its six
#                                          pins are on overlay-owned modules and three are not, so
#                                          it takes --edition and runs 3 or 6; see its own header.)
#   scripts/check-no-bedrock.sh            (hard ban on AWS Bedrock in Python tooling; runs on every
#                                          slice and in every edition, deliberately; see below)
#
# Two gates are deliberately NOT in this pipeline, in either edition, and carry EXCLUDED rows so
# that fact is declared rather than implied by absence:
#   scripts/check-migrations-populated.sh (`task migrations:populated`). Everything here migrates
#     EMPTY databases, where a failing `ADD CONSTRAINT` and a zero-row `UPDATE` both pass; that
#     script builds a populated one and asserts. It wants Docker, a Maven-resolved JDBC driver and
#     minutes, so it is run per migration that renames or narrows a persisted value.
#   scripts/check-conformance-parity.sh (`task conformance:parity`, and the CI conformance-parity
#     job). Adding it here would change what `task check` runs and cost a uv sync, for no gate
#     movement. It is NOT "CI-only": the Taskfile runs it too.
#
# ---------------------------------------------------------------------------------------------
# CI IS A PER-PR GATE. .github/workflows/check.yml runs this script, this manifest, on
# `pull_request:`, so "local green => CI green" holds by construction on the same script rather than
# on a weekly cron nobody watched. secret-scan.yml is armed alongside it, and those two are the whole
# of it: there is no cron anywhere in this repository, and everything else is workflow_dispatch only
# ahead of the public cutover. namespace-recheck.yml notably included, so that check runs nowhere
# automatically; its header says what that costs. The overlay's reusable-ecs-deploy.yml is
# `workflow_call`.
#
# Nothing is merge-BLOCKING: this repo's plan tier offers neither branch protection nor rulesets, so
# a red run can be merged past and only convention stops it.
#
# What CI runs that this script does NOT is exactly drift-checks.yml's two dispatch-only jobs. The
# overlay-schema `--with-docker` half that was once wired to pull_request went with the trim; the
# overlay-schema row above is RUN_IF_PRESENT with no --with-docker argument,
# so `task check` stays static-only and fast, and "local green => CI green" holds for the static
# half only.
#
# CI never runs the Taskfile, only these scripts, which is why the gate's own logic lives here
# rather than inline in Taskfile.yml.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---- the edition axis ----
# Parsed strictly BEFORE the positional slice list, because that list is validated against a
# whitelist that hard-errors on unknown names: without this loop `--edition` lands in $1, fails
# `_slice_exists` and dies with "unknown slice(s): --edition" before a single gate runs.
EDITION=all
while [ $# -gt 0 ]; do
    case "$1" in
        --edition)
            EDITION="${2:-}"
            case "$EDITION" in
                open|all) ;;
                *)
                    echo "check: --edition takes 'open' or 'all', got '${EDITION:-}'" >&2
                    exit 1
                    ;;
            esac
            shift 2
            ;;
        --edition=*)
            EDITION="${1#--edition=}"
            case "$EDITION" in
                open|all) ;;
                *)
                    echo "check: --edition takes 'open' or 'all', got '$EDITION'" >&2
                    exit 1
                    ;;
            esac
            shift
            ;;
        --)
            shift
            break
            ;;
        -*)
            echo "check: unknown flag '$1' (only --edition open|all)" >&2
            exit 1
            ;;
        *)
            # The slice list. `task check` passes "{{.CLI_ARGS}}", which with no arguments is a
            # single EMPTY string; it must land here and mean "everything", exactly as before.
            break
            ;;
    esac
done

# Once the slice list starts, flags are over, and a flag AFTER it must be an error, never a
# silent drop. `check.sh frontend --edition open` would otherwise take `frontend` as the slice,
# leave EDITION at its `all` default, and run the paid gates while the operator believes they
# asked to exclude them. A gate running when you asked for it not to, saying nothing, is the
# exact defect the manifest and the `_gate` helper exist to prevent; it must not sneak back in
# through argument parsing.
for _arg in "$@"; do
    case "$_arg" in
        -*)
            echo "check: '$_arg' comes after the slice list, where flags are no longer read." >&2
            echo "       Put it first: bash scripts/check.sh --edition open ${1:-<slices>}" >&2
            exit 1
            ;;
    esac
done

SLICES="${1:-}"

# Extra Maven arguments the edition implies, for the SLICED path's three raw `mvn` invocations
# below. The full-run path does not read this: its backend leg goes through the `backend` manifest
# row, which carries the same `-P !paid` and hands it to scripts/check-backend.sh, whose last line
# is `exec mvn -B verify "$@"`, so the profile threads through and the JDK-25 guard stays in front
# of the open build too. Two consumers, one meaning; if the flag ever changes, change both.
if [ "$EDITION" = open ]; then
    EDITION_MVN_ARGS="-P !paid"
else
    EDITION_MVN_ARGS=""
fi

# ---- the gate manifest ----
# One row per gate: id | script | disposition under `all` | disposition under `open` | note.
# This is the single declaration of what the pipeline is. Dispositions:
#
#   RUN                      run it.
#   RUN:<args>               run it with those arguments.
#   RUN_ENV:<VAR=value ...>  run it with those environment variables set. For a gate whose INPUTS
#                            moved into the overlay while the script itself may not name the
#                            overlay (check-open-boundary.sh rule 5 allows only four scripts to,
#                            and this file is one of them). Without it such a gate takes its own
#                            default, finds nothing, and self-skips green in the paid checkout
#                            too: coverage silently lost, the exact failure this manifest exists
#                            to prevent.
#   RUN_IF_PRESENT:<reason>  run it when the script FILE exists, else skip with that reason. Used
#                            only for overlay-owned gates, where the script living inside
#                            tessary-paid/ makes its own presence the honest edition signal.
#   SKIP:<reason>            this edition does not run it, and here is why.
#   EXCLUDED:<reason>        not part of this pipeline in any edition. Calling `_gate` on one is a
#                            bug and fails loudly; the row exists so the completeness assertion
#                            below can see the script and so "not here" is declared, not implied.
#
# A skip with no reason is the defect this whole mechanism exists to prevent (a gate has stopped
# running and said nothing before), so `_skip` refuses an empty one.
_manifest() {
    cat <<'MANIFEST'
docs-links|scripts/check-docs-links.sh|EXCLUDED:dropped 2026-09-09 under the standing rule in this file's header that no gate reads a .md or .mdx file. It WAS markdown: it resolved relative links across 81 markdown files. Nothing survives the no-markdown rule|EXCLUDED:same|declared here only so the completeness assertion can see it
version-consistency|scripts/check-version-consistency.sh|RUN_ENV:VERSION_LITERAL_EXEMPT=tessary-paid/OPEN-CORE.md|RUN_ENV:VERSION_LITERAL_EXEMPT=tessary-paid/OPEN-CORE.md|the git tag release.yml pushes is the only source of truth for a published version; asserts no file holds a copy and every machine-resolved image default floats to the release `-latest` tag. The artifact's pin is stamped by scripts/lib/pin-compose-version.py and checked by check-compose-artifact.sh. VERSION_LITERAL_EXEMPT covers dated literals this script can't name directly (boundary rule 5). Pure text; unlike check-selfhost-images.sh, no Docker or registry call.
classifier-quality-doc|scripts/check-classifier-quality-doc.sh|RUN_ENV:CQ_DOC=tessary-paid/devdocs/reference/classifier-quality.md CQ_MODELS=tessary-paid/classify-service/models.json|SKIP:this edition has neither of the gate's two inputs: the measured-quality page and the populated model manifest live in the overlay, and the manifest shipped here is the empty `{}`|Both inputs are overlay paths the script may not name itself (boundary rule 5), so the paid column passes them in. A bare RUN would take the script's own defaults, find neither, and self-skip green in the paid checkout too: silent coverage loss, which is what RUN_ENV exists to prevent. If the overlay is missing from an all-edition checkout, the script's own guard still prints a reasoned skip.
module-hygiene|scripts/check-module-hygiene.sh|RUN|RUN|self-scoping on the overlay pom; see the note below the manifest
open-boundary|scripts/check-open-boundary.sh|RUN|RUN|the open/paid direction check itself
license-headers|scripts/check-license-headers.sh|RUN|RUN|SPDX header presence over the publishable tree (derived from export-denylist.txt's own `delete` rows, so private trees are out of scope for both editions the same way); the script's own header names the interim manual-audit + weekly-CI posture it runs under until branch protection is available
export-denylist|scripts/lib/check-export-denylist.sh|RUN|RUN|the must-not-publish declaration is well-formed and, with the overlay present, every delete/exempt row resolves; self-scopes the liveness half
pipeline-vocabulary|scripts/check-pipeline-vocabulary.sh|RUN|RUN|open on both sides
contract-consistency|scripts/check-contract-consistency.sh|RUN|RUN|open on both sides
vendored-plugin|scripts/check-vendored-plugin.sh|EXCLUDED:dropped 2026-09-09. Its freshness half fetches tessaryai/plugins over the network and hard-fails on $CI, so per PR it reds pull requests over upstream commits and transient network failures unrelated to the diff. Right check, wrong trigger; it runs in the dispatch-only drift-checks.yml and via `task contract:plugin`. See the standing rule in this file's header|EXCLUDED:same|declared here only so the completeness assertion can see it
caddy|scripts/check-caddy.sh|RUN|RUN|open on both sides
paid-caddy|tessary-paid/scripts/check-paid-caddy.sh|RUN_IF_PRESENT:no tessary-paid/ overlay in this checkout|SKIP:this edition has no paid spec and no Caddyfile.prod for the gate to compare; both live in the overlay|this gate lives in the overlay, the overlay twin of the `caddy` row above; it lives there because check-open-boundary.sh rule 5 fails any scripts/*.sh naming that directory. The checker itself is open and variadic (scripts/lib/caddy-proxies-spec.py); only the caller and the config are the overlay's.
readme-front-door|scripts/check-readme-front-door.sh|EXCLUDED:dropped 2026-09-09 under the standing rule in this file's header that no gate reads a .md or .mdx file. It asserted README prose: a required section heading, eight exact phrases inside it, and which fenced block came first. A README rewrite reds it while the README is fine|EXCLUDED:same|declared here only so the completeness assertion can see it
connect-route|scripts/check-connect-route.sh|EXCLUDED:dropped 2026-09-09 under the standing rule in this file's header that no gate reads a .md or .mdx file. It string-matched eleven prose fragments from docs/self-hosting/setup.mdx against JSX. Renaming a button reds it|EXCLUDED:same|declared here only so the completeness assertion can see it
required-inputs|scripts/check-required-inputs.sh|EXCLUDED:dropped 2026-09-09 under the standing rule in this file's header that no gate reads a .md or .mdx file. An input with no default passed if setup.mdx's required-variable table had a row for it, so documenting a variable elsewhere reds the build|EXCLUDED:same|declared here only so the completeness assertion can see it
compose-artifact|scripts/check-compose-artifact.sh|RUN|RUN|the one-command install: docker-compose.yml is also the OCI artifact behind `docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y`, so it must stay publishable (long-syntax ports, literal memory limits) and mount no host path in a default-profile service; the build strip that makes the artifact must remove build sections and nothing else; pure text plus a client-side `docker compose config`, no daemon and no network
selfhost-health|scripts/check-selfhost-health.sh|EXCLUDED:dropped 2026-09-09 under the standing rule in this file's header that no gate reads a .md or .mdx file. It read docs/self-hosting/setup.mdx to decide WHICH services must carry a health probe. The probe assertions were structural; the service list came out of a doc|EXCLUDED:same|declared here only so the completeness assertion can see it
classify-service|scripts/check-classify-service.sh|RUN|RUN:--edition open|classify-service stays open per the ledger, but its models.json manifest lives in the overlay; without it, this asserts an empty manifest and the UNAVAILABLE_IN_OPEN_EDITION token, not the three built-in heads
slack-service|tessary-paid/scripts/check-slack-service.sh|RUN_IF_PRESENT:no tessary-paid/ overlay in this checkout|SKIP:this edition has no Slack adapter; the service and its gate live in the overlay together|a gate that lives in the overlay
sandbox-runner|scripts/check-sandbox-runner-launcher.sh|RUN|RUN|open on both sides; needs only node, already on PATH for the frontend gate
compile-service|tessary-paid/scripts/check-compile-service.sh|RUN_IF_PRESENT:no tessary-paid/ overlay in this checkout|SKIP:this edition has no SOP compile service; the service, its engine and its gate live in the overlay together|a gate that lives in the overlay
overlay-schema|tessary-paid/scripts/check-overlay-schema.sh|RUN_IF_PRESENT:no tessary-paid/ overlay in this checkout|SKIP:the open edition has no overlay changelog to lint|a gate that lives in the overlay
classifier-parity|scripts/check-classifier-parity.sh|EXCLUDED:dropped 2026-09-09. In the OPEN edition it asserts NOTHING: #1293 moved all six of its pins into the overlay, so it prints a named skip and returns OK. It was the only reason this pipeline needed uv. See the standing rule in this file's header|EXCLUDED:same|declared here only so the completeness assertion can see it
no-bedrock|scripts/check-no-bedrock.sh|RUN|RUN|repo-wide invariant, every slice and every edition
frontend|scripts/check-frontend.sh|RUN|RUN|already the open gate by construction ('@paid' resolves to the in-tree stub)
paid-image|tessary-paid/scripts/check-paid-image.sh|RUN_IF_PRESENT:no tessary-paid/ overlay in this checkout|SKIP:the open edition has no paid image to layer|a gate that lives in the overlay; the static half only here, `task paid:image:check` runs the Docker half
paid-frontend|tessary-paid/scripts/check-paid-frontend.sh|RUN_IF_PRESENT:no tessary-paid/ overlay in this checkout|SKIP:this edition has no paid frontend surfaces; they live in the overlay|a gate that lives in the overlay
backend|scripts/check-backend.sh|RUN|RUN:-P !paid|one script, both editions; the JDK-25 guard is in front of both
conformance-parity|scripts/check-conformance-parity.sh|EXCLUDED:run by `task conformance:parity` and the CI conformance-parity job, never by this pipeline|EXCLUDED:same, and its generator is paid so the open edition would skip it anyway|declared here only so the completeness assertion can see it
migrations-populated|scripts/check-migrations-populated.sh|EXCLUDED:wants Docker, a JDBC driver and minutes; run per migration that renames or narrows a persisted value|EXCLUDED:same|declared here only so the completeness assertion can see it
open-boot|scripts/check-open-boot.sh|EXCLUDED:wants Docker and minutes to boot a real stack; run via `task check:open:boot` or the dispatch-only boot-checks.yml CI workflow (workflow_dispatch only, see its header), never part of `task check`|EXCLUDED:same|declared here only so the completeness assertion can see it
scrub|tessary-paid/scripts/check-scrub.sh|EXCLUDED:its subject is the export candidate, not this checkout, and it needs gitleaks and trufflehog, which no contributor toolchain installs; run via `task scrub:check` or the dispatch-only tessary-paid/.github/workflows/scrub-gate.yml workflow|EXCLUDED:same, and the script itself lives in the overlay, not just the forbidden-strings file it reads; this tree runs .github/workflows/secret-scan.yml instead, which scans its own checkout with no private configuration|declared here only so the completeness assertion can see it
paid-boot|tessary-paid/scripts/check-paid-boot.sh|EXCLUDED:Docker and minutes: builds both images and boots the open then the paid stack on one Postgres volume; run via `task paid:boot:check` or the dispatch-only tessary-paid/.github/workflows/paid-boot.yml workflow|EXCLUDED:same, and the open edition has no overlay to boot|declared here only so the completeness assertion can see it
open-boot-selfhost|scripts/check-open-boot-selfhost.sh|EXCLUDED:the gate's second leg: boots the self-host docker-compose.yml under the production profile; same Docker-and-minutes cost, run via `task check:open:boot:selfhost` or boot-checks.yml's second job, never part of `task check`|EXCLUDED:same|declared here only so the completeness assertion can see it
selfhost-quickstart|scripts/check-selfhost-quickstart.sh|EXCLUDED:the quickstart rehearsal executes docs/self-hosting/setup.mdx's own command blocks verbatim in a clean-room export on the compose defaults (ports 80/443), asserts every Check as the page words it, walks the three ladder checkpoints and the control arm, and prints the clock; Docker, network, minutes; run via `task check:selfhost:quickstart` or boot-checks.yml|EXCLUDED:same|declared here only so the completeness assertion can see it
selfhost-images|scripts/check-selfhost-images.sh|EXCLUDED:needs Docker and the network to hit two public registries with no login, and a released `v<semver>` git tag (or --version=) to know what to ask for; run via `task check:selfhost:images`, by the quickstart rehearsal before its first timed command, and by boot-checks.yml, never part of `task check`|EXCLUDED:same|declared here only so the completeness assertion can see it
zero-egress|scripts/check-zero-egress.sh|EXCLUDED:layers on the quickstart rehearsal with an internal network, a DNS sink and the opt-out set, asserts nothing outside the published permitted set at three vantage points, then proves the instrument with the heartbeat as positive control and a planted call; Docker, ports 80/443, minutes; run via `task check:zero:egress` or boot-checks.yml|EXCLUDED:same|declared here only so the completeness assertion can see it
selfhost-compose-artifact|scripts/check-selfhost-compose-artifact.sh|EXCLUDED:the one-command install's end-to-end rehearsal — publishes the artifact to a throwaway TLS registry, boots `docker compose -f oci://...:compose up -d -y` from an empty directory and asserts the working directory stays empty; builds four images and boots a stack, so Docker and minutes; run via `task check:selfhost:compose` or boot-checks.yml's fourth job, never part of `task check`|EXCLUDED:same|declared here only so the completeness assertion can see it
dependency-audit|scripts/check-dependency-audit.sh|EXCLUDED:manual/on-demand only — not in CI since 2026-09-02 (owner: no real keys in checks; the Maven leg hard-fails without NVD_API_KEY); runs OWASP dependency-check-maven + pnpm audit + pip-audit
namespaces|scripts/check-namespaces.sh|EXCLUDED:the namespace ownership recheck: hits Docker Hub, GitHub and Hugging Face over the network; run via `task check:namespaces`, quarterly by namespace-recheck.yml and before every release by release.yml, never part of `task check`|EXCLUDED:same|declared here only so the completeness assertion can see it
exposure-sweep|scripts/check-exposure-sweep.sh|EXCLUDED:the fresh-deployment exposure sweep: boots the self-host artifact and probes it, Docker and minutes; run via `task check:exposure:sweep` (--record to refresh scripts/lib/exposure-sweep-baseline.txt) and by boot-checks.yml's third job, never part of `task check`|EXCLUDED:same|declared here only so the completeness assertion can see it
open-artifacts|scripts/check-open-artifacts.sh|EXCLUDED:the built-artifact diff: builds and exports the four published open images, minutes and Docker; run via `task check:open:artifacts` (add --negative for the planted-violation proof), and by release.yml's verify-open-artifacts job against the images it just built|EXCLUDED:same|declared here only so the completeness assertion can see it
notice-coverage|scripts/check-notice-coverage.sh|EXCLUDED:manual/on-demand only — needs Docker to build the open backend and frontend images, same cost class as check-open-boot.sh; run directly with `bash scripts/check-notice-coverage.sh`
MANIFEST
}
# module-hygiene is the one gate `--edition open` does NOT put into open mode, deliberately. Rule
# 2b carries its own overlay-pom scoping and self-skips the paid half when the overlay is gone;
# with the overlay present it asserts the paid mounts in the OPPOSITE direction, inside the
# overlay's own dev-compose file, which is where they now live. Threading an edition into it
# would catch no failure and would need a second file in rule 5's allowlist.
# What keeps docker-compose.dev.yml itself honest is check-open-boundary.sh rule 7, which reds
# any reference to the overlay directory in the open compose file that is not a comment.

_manifest_row() {
    _manifest | awk -F'|' -v id="$1" '$1 == id { print; found = 1 } END { exit !found }'
}

SKIPPED=""
_skip() {
    if [ -z "${2:-}" ]; then
        echo "check: gate '$1' tried to skip with no reason. That is the bug this pipeline exists" >&2
        echo "       to prevent — fix the manifest row." >&2
        exit 1
    fi
    echo "$1 skipped [$EDITION edition] — $2"
    SKIPPED="${SKIPPED}  $1 — $2
"
}

# Every gate invocation in BOTH code paths goes through here. That is not tidiness: the three paid
# guards this replaced were duplicated across the full-run branch and the slice branch with
# byte-identical bodies, so any edition logic applied to one and not the other would have made
# `check.sh --edition open frontend` disagree with `check.sh --edition open` silently.
_gate() {
    _gate_id="$1"
    shift
    _gate_row="$(_manifest_row "$_gate_id")" || {
        echo "check: no manifest row for gate '$_gate_id'. Add one." >&2
        exit 1
    }
    _gate_script="$(printf '%s\n' "$_gate_row" | cut -d'|' -f2)"
    if [ "$EDITION" = open ]; then
        _gate_disp="$(printf '%s\n' "$_gate_row" | cut -d'|' -f4)"
    else
        _gate_disp="$(printf '%s\n' "$_gate_row" | cut -d'|' -f3)"
    fi

    case "$_gate_disp" in
        RUN)
            bash "$_gate_script" "$@"
            ;;
        RUN:*)
            # Unquoted on purpose: the manifest's argument field is a word list, e.g. `-P !paid`.
            # shellcheck disable=SC2086
            bash "$_gate_script" ${_gate_disp#RUN:} "$@"
            ;;
        RUN_ENV:*)
            # Same word-list splitting as RUN:, but the words are VAR=value assignments handed to
            # `env` rather than arguments. `env` is used rather than an inline prefix so the
            # assignments cannot leak into the rest of this shell.
            # shellcheck disable=SC2086
            env ${_gate_disp#RUN_ENV:} bash "$_gate_script" "$@"
            ;;
        RUN_IF_PRESENT:*)
            if [ -f "$_gate_script" ]; then
                bash "$_gate_script" "$@"
            else
                _skip "$_gate_id" "${_gate_disp#RUN_IF_PRESENT:}"
            fi
            ;;
        SKIP:*)
            _skip "$_gate_id" "${_gate_disp#SKIP:}"
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
    if [ -d tessary-paid/scripts ]; then
        _disk="$_disk
$(ls tessary-paid/scripts/check-*.sh 2>/dev/null || true)"
    fi
    _disk="$(printf '%s\n' "$_disk" | grep -v '^$' | sort -u)"

    _unlisted="$(comm -23 <(printf '%s\n' "$_disk") <(printf '%s\n' "$_rows"))"
    if [ -n "$_unlisted" ]; then
        {
            echo "check: these gate scripts exist on disk but no manifest row mentions them, so"
            echo "       nothing here decides whether they run, in which edition, or at all."
            echo "       Add a row to _manifest in this file:"
            printf '%s\n' "$_unlisted" | sed 's/^/  /'
        } >&2
        exit 1
    fi

    # A row pointing at a missing script is only legitimate for the overlay-owned gates, whose
    # whole point is to be absent when `--edition open` runs.
    _phantom=""
    for _r in $_rows; do
        case "$_r" in tessary-paid/*) continue ;; esac
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
    while IFS='|' read -r _id _script _open _paid _note; do
        case "$_open$_paid" in *RUN*) ;; *) continue ;; esac
        grep -qE "^[[:space:]]*_gate[[:space:]]+$_id([[:space:]]|$)" "$ROOT/scripts/check.sh" || _unwired="${_unwired}  $_id
"
    done < <(_manifest)
    if [ -n "$_unwired" ]; then
        {
            echo "check: these manifest rows are RUN in at least one edition but no \`_gate <id>\`"
            echo "       call in this file ever runs them, so the row claims coverage it does not have."
            echo "       Add the call to the ordered list below the manifest:"
            printf '%s' "$_unwired"
        } >&2
        exit 1
    fi
}
_assert_manifest_complete

_summary() {
    if [ -n "$SKIPPED" ]; then
        echo ""
        echo "check: $EDITION edition — the following gates were SKIPPED, by declaration:"
        printf '%s' "$SKIPPED"
    fi
    # Only printed when the overlay is actually present; a FILE probe (tessary-paid/pom.xml), not
    # a directory test, since an empty directory skeleton can satisfy the latter without meaning it.
    if [ "$EDITION" = open ] && [ -f tessary-paid/pom.xml ]; then
        echo ""
        echo "check: this was the OPEN edition with tessary-paid/ still on disk, which is a PROXY."
        echo "       It cannot see a break that appears only when the directory is GONE. Maven is"
        echo "       NOT such a gap: -P with the paid profile negated deactivates it exactly as its"
        echo "       absence would. The real one is the Python import seam: every relocated test is"
        echo "       reached through classifiers/pyproject.toml's second pythonpath entry,"
        echo "       ../tessary-paid/classifiers, which pytest ignores when it is not a directory —"
        echo "       so with the overlay present the packages resolve no matter which edition was"
        echo "       asked for. The proof is:"
        echo "         d=\$(bash scripts/lib/export-simulate.sh) && (cd \"\$d\" && bash scripts/check.sh --edition open)"
        echo "       NOT 'mv tessary-paid /tmp/' — that leaves the git index believing"
        echo "       tessary-paid/README.md still exists, so check-docs-links.sh dies first with a"
        echo "       stale-path error before any other gate runs."
        echo "       The frontend gate needs its own deps: node_modules/ is gitignored and the"
        echo "       export only carries tracked/untracked-not-ignored files, so run"
        echo "       '(cd \"\$d\"/frontend && pnpm install)' before the check above, or expect a red"
        echo "       'openapi-typescript: No such file or directory' unrelated to the boundary."
    fi
}


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
# Cheapest first, so a contract, Caddyfile, classify, slack- or compile-service break fails in seconds instead of behind
# the two slow gates. Measured on an M-series laptop: contract/caddy/classify a few seconds each,
# frontend ~40s (tsc --noEmit ~20s, then tsc -b + vite build ~20s), backend ~200s. Running the two
# slow ones concurrently would save ~40s of ~250 but requires buffering both to keep a failure
# readable, which costs all streaming output.
# The ORDER below is load-bearing and is not derived from the manifest: the manifest declares what
# each gate does per edition, this declares how long you wait to find out.
if [ -z "$SLICES" ]; then
    _gate classifier-quality-doc
    _gate module-hygiene
    _gate open-boundary
    _gate license-headers
    _gate export-denylist
    _gate pipeline-vocabulary
    _gate contract-consistency
    _gate caddy
    _gate paid-caddy
    _gate version-consistency
    _gate compose-artifact
    _gate classify-service
    _gate slack-service
    _gate sandbox-runner
    _gate compile-service
    _gate overlay-schema
    _gate no-bedrock
    _gate frontend
    _gate paid-image
    _gate paid-frontend
    _gate backend
    _summary
    exit 0
fi

# ---- with an argument: validate EVERY slice before running anything ----
# A typo must fail instantly with the valid names, never run a silently-empty selection.
want_frontend=0
want_slack_service=0
want_compile_service=0
patterns=""
bad=""
for slice in $(echo "$SLICES" | tr ',' ' '); do
    if [ "$slice" = frontend ]; then
        want_frontend=1
    elif [ "$slice" = slack-service ]; then
        want_slack_service=1
    elif [ "$slice" = compile-service ]; then
        want_compile_service=1
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
        echo "A slice is 'frontend', 'slack-service' (overlay-only), 'compile-service',"
        echo "or one of these backend areas:"
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
        #
        # $EDITION_MVN_ARGS is unquoted on purpose (it is `-P !paid` or empty) and goes on all three
        # invocations, not just the test one: Spotless and test-compile must see the same reactor
        # the tests do, or a module gets formatted and compiled by a run that claims not to build
        # it at all.
        # shellcheck disable=SC2086
        mvn -B -q $EDITION_MVN_ARGS spotless:apply
        # shellcheck disable=SC2086
        mvn -B -q $EDITION_MVN_ARGS test-compile
        # Reactor-wide: a slice's unit tests and its @SpringBootTest integration tests live in
        # different modules, so narrowing with -pl would drop half of them. Most modules match
        # nothing, hence failIfNoSpecifiedTests=false; the typo guard above already catches a bad
        # slice name, so this cannot mask one.
        # shellcheck disable=SC2086
        mvn -B -q $EDITION_MVN_ARGS -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$patterns" test
    )
fi

if [ "$want_slack_service" = 1 ]; then
    _gate slack-service
fi

if [ "$want_compile_service" = 1 ]; then
    _gate compile-service
fi

# ALWAYS, whatever slice was asked for. The Bedrock ban and the license-header gate are both
# repo-wide invariants and both cheap (a few greps / a text scan), so a narrow `task check -- rca`
# must not be a hole a new call site or a new header-less file slips through.
_gate no-bedrock
_gate license-headers
# Also unconditional, for the same reason: docker-compose.yml is the PUBLISHED one-command
# install, so any edit to it from any slice can break a remote install while `docker compose up`
# stays green locally. Pure text plus a client-side render, so it costs a second.
_gate compose-artifact

if [ "$want_frontend" = 1 ]; then
    _gate frontend
    _gate paid-image
    _gate paid-frontend
fi

_summary

echo ""
echo "check: ran a NARROWED gate ($SLICES) [$EDITION edition] — this is NOT the full gate."
echo "       CI runs everything on the PR; run 'task check' with no argument if you"
echo "       want that answer before pushing."
