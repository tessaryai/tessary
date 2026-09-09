#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The open/paid direction check, on the surfaces Maven cannot see.
#
# Open-core decision D2: paid code lives in `tessary-paid/` and the public export is a folder
# filter, so deleting that directory must leave a tree that still builds. Maven enforces one half by
# itself — `backend/pom.xml` gives every open module an `enforce-open-to-paid-direction`
# banned-dependency rule on `ai.tessary.paid:*`, and a Java class cannot be imported without the
# dependency that carries it. That rule has a load-bearing assumption this script has to check, plus
# four gaps a reactor cannot close:
#
#   0. The Maven rule keys on a GROUP ID, and nothing in Maven forces a pom under tessary-paid/ to
#      carry it. Every module pom in backend/ inherits its groupId from `<parent>ai.tessary:backend`
#      and declares none of its own, so a module moved with `git mv` keeps that parent verbatim,
#      stays `ai.tessary:*`, and the ban then does not match it — the guard passes while the
#      property it exists to enforce is false. Rule 0 below is what makes the group id true.
#   1. Java source text. A reference to a class that MOVED to the overlay does not compile, but it
#      also does not mention `ai.tessary.paid` — Java packages here are `ai.tessary.*` and the
#      group id never appears in source. So the text check has to be derived from what the overlay
#      actually contains, not from the group id.
#   2. The frontend, which has no Maven at all, and whose build aliases can point outside frontend/.
#   3. The open IMAGES' build inputs. Per D4 the published open image carries only open code. Two
#      images are exposed: backend/Dockerfile (context backend/, so out of reach by construction —
#      checked anyway, cheaply) and frontend/Dockerfile, whose context is the REPO ROOT, which means
#      the overlay is inside it unless .dockerignore excludes it.
#   4. The wiring itself: if the `paid` profile is dropped from backend/pom.xml, the overlay silently
#      stops building and every check downstream goes green on a lie.
#   5. Everything else that ships in the export and reads files by PATH: classify-service/ and the
#      check scripts themselves. Nothing here compiles, so no compiler and no reactor is watching —
#      a relative path into the overlay is just a string until the export deletes what it points at.
#   6. Open MARKDOWN that resolves a path the export deletes — rules 0-5 all read code, and the
#      first violation of this class survived three issues because nothing read prose.
#   7. The two ORCHESTRATION files, by exact path: Taskfile.yml and .github/workflows/ci.yml. Same
#      failure class as rule 5, one file type over — nothing compiles YAML either, so an overlay
#      path in them is a string until the export deletes what it points at. These two files may
#      name the overlay (they are what decides which edition runs), so the rule is not a ban: every
#      reference must be visibly CONDITIONAL — a comment, a human-facing desc:/name: value, or a
#      reference carrying an existence probe, or sitting within 6 lines below one that still
#      GOVERNS it (nested under it, or a sibling key of the same YAML node — a probe does not
#      travel into the next list item or past the end of its shell block). #875 added it because
#      every edition-awareness gap in this epic so far was found by a human running the detach by
#      hand rather than by a gate (#843's unguarded check-classifier-parity.sh, #843's markdown
#      link that survived three issues, #846's shadowing vite config), and it was non-vacuous on
#      arrival: ci.yml's setup-uv cache-dependency-glob named tessary-paid/compile-service/uv.lock
#      unconditionally while the work it fed was guarded three lines below.
#      DELIBERATELY NOT COVERED by rule 7, said in the same breath because #843's lesson is that a
#      gate which silently does not cover something reads to the next person as coverage:
#      RESOLVED, was parked here until #886: docker-compose.dev.yml. It carried 21 textual overlay
#      references — eleven bind mounts (the aggregator pom, plus a pom+src pair for each of the five
#      overlay modules), nine comments and the `compile` service's overlay build context — and the
#      park argued they were not repairable, because check-module-hygiene.sh pinned those mounts in
#      the opposite direction. That argument was wrong in one word: not repairable HERE. Repairing
#      both halves in one commit is exactly what #886 did — the mounts and the compile service moved
#      into tessary-paid/docker-compose.dev.yml behind a file-exists probe, and rule 2b's pin moved
#      with them. The park's premise also expired: epic 1's gate no longer excludes booting, because
#      an open checkout could not boot at all. Compose short-syntax binds CREATE a missing source as
#      a directory, so a detached `task dev:up:slim` with no overlay had dockerd manufacture a ghost
#      `tessary-paid/` tree, and backend/pom.xml's `<file><exists>` probe — true for a directory —
#      turned the `paid` profile back on over modules that did not exist. The file is covered by
#      rule 7 now, not parked. `alloy` (opt-in, profiles: observability) and the six
#      classifiers-profile dev services are epic 7's (epic 5 decision 7, ruled 2026-09-04).
#        * the deploy workflows, which no longer live under .github/workflows/ at all. #941/#1114
#          moved deploy-aws-production.yml, deploy-classify-service.yml and reusable-ecs-deploy.yml
#          into tessary-paid/.github/workflows/ (deploy-grader-lambda.yml was deleted earlier by
#          e5ff5c79), so rule 7's glob no longer sees them and their denylist rows are gone with
#          them — the whole tessary-paid/ tree is one `delete|dir` row now. This is a STRONGER fact
#          than the exemption it replaces, not a weaker one: they are absent from the scanned path,
#          not skipped by name, so the second hardcoded exemption list this note used to say #885
#          would have to own is not needed. deploy-aws-production.yml still builds compile-service
#          with `context: tessary-paid`, unguarded, and stays that way — it is inside the overlay it
#          references.
#   8. `.github/dependabot.yml` (#927). Same failure class as rules 5-7, one more non-compiled
#      file that can reference a path by string: a `directory:` entry under `tessary-paid/` would
#      have GitHub's own bot open dependency-update PRs against the paid overlay's manifests from
#      this open-core repo, the same class of leak rule 5 exists to catch for scripts/*.sh. Unlike
#      rules 5-7, this one is not "visibly conditional or fine" — dependabot.yml has no probe
#      idiom at all, so the only two states are "does not mention tessary-paid/" (correct: the one
#      uv workspace actually inside it, tessary-paid/compile-service, must simply be absent from
#      the file) and "mentions it" (wrong, always). A ban, not a conditional-reference rule.
#
# Runs in well under a second, so it sits at the front of `task check`. Shared with CI via the
# open-boundary job.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0

# ---- 4. the wiring is present and points where it should ----
if [ -f tessary-paid/pom.xml ]; then
    grep -q '<module>\.\./tessary-paid</module>' backend/pom.xml || {
        echo "ERROR: tessary-paid/pom.xml exists but backend/pom.xml no longer adds it to the" >&2
        echo "       reactor. The overlay would stop building and nothing else would notice." >&2
        fail=1
    }
    grep -q 'tessary-paid/pom.xml</exists>' backend/pom.xml || {
        echo "ERROR: backend/pom.xml's 'paid' profile is no longer activated by the overlay's" >&2
        echo "       existence. Open-edition builds depend on that activation being automatic." >&2
        fail=1
    }
fi

# The same assertion for the DEV COMPOSE wiring, which #886 added and which nothing was watching.
# Not scoped to `[ -f tessary-paid/pom.xml ]` like the Maven half above: the derivation is open code
# and must survive the export intact, and the failure this catches (a call site that hardcodes the
# base file) is silent in BOTH editions.
#
# Why a gate and not a comment. check-module-hygiene.sh rule 2b pins the eleven paid mounts inside
# tessary-paid/docker-compose.dev.yml, and that pin means "the mounts reach the container" ONLY if
# the fragment is actually merged. Before #886 the mounts lived in the base file, which every
# invocation named literally, so the implication was free. Now it rests entirely on
# scripts/lib/dev-compose.sh and on every call site going through it — and reverting one Taskfile
# var to the literal `docker compose -f docker-compose.dev.yml` (a plausible simplification, or a
# merge-conflict resolution) left rule 2b, rule 7 and every other gate green while `task rb`
# recreated the dev backend with all eleven mounts gone and the in-container `paid` profile off.
if [ ! -f scripts/lib/dev-compose.sh ]; then
    echo "ERROR: scripts/lib/dev-compose.sh is gone. It is the single derivation of the dev stack's" >&2
    echo "       '-f' set — the one place that decides whether the paid overlay's compose fragment" >&2
    echo "       is merged. Without it every dev entry point falls back to the base file alone." >&2
    fail=1
else
    grep -q 'tessary-paid/docker-compose.dev.yml' scripts/lib/dev-compose.sh || {
        echo "ERROR: scripts/lib/dev-compose.sh no longer merges tessary-paid/docker-compose.dev.yml." >&2
        echo "       The paid dev stack then runs with none of its module mounts: /tessary-paid/pom.xml" >&2
        echo "       is absent in the container, backend/pom.xml's 'paid' profile deactivates, and the" >&2
        echo "       identical mvn command builds a DIFFERENT module set inside the container than on" >&2
        echo "       the host. The stack still comes up; you find out when a paid bean is missing." >&2
        fail=1
    }
    # Every call site must route through it. Comments are exempt (this is a check for INVOCATIONS,
    # and the derivation's own header quotes the string it replaced); *.md is out of the target set
    # on purpose — prose telling a human to run one service is not a stack the loop recreates.
    hardcoded=""
    for f in Taskfile.yml .github/workflows/ci.yml .dev-tools/agent-loop/config.yaml \
             $(ls scripts/*.sh scripts/lib/*.sh 2>/dev/null); do
        [ -f "$f" ] || continue
        [ "$f" = scripts/lib/dev-compose.sh ] && continue
        hits=$(grep -nE 'docker[ -]compose[^#]*-f[[:space:]]+docker-compose\.dev\.yml' "$f" 2>/dev/null \
               | grep -v '^[0-9][0-9]*:[[:space:]]*#' || true)
        if [ -n "$hits" ]; then
            hardcoded="$hardcoded$(echo "$hits" | sed "s|^|  $f:|")
"
        fi
    done
    if [ -n "$hardcoded" ]; then
        echo "ERROR: a dev-stack compose invocation names docker-compose.dev.yml directly instead of" >&2
        echo "       going through scripts/lib/dev-compose.sh. That is the base file ALONE, so in a" >&2
        echo "       paid checkout it drops the eleven paid module mounts and both TESSARY_SOP_COMPILE_*" >&2
        echo "       keys — and docker-compose.dev.yml pins 'name: tessary', so it targets the" >&2
        echo "       SAME project a running 'task dev' owns: compose sees a changed config hash and" >&2
        echo "       RECREATES the backend without the overlay, deactivating the in-container 'paid'" >&2
        echo "       profile while everything still looks up. Use:" >&2
        echo '         COMPOSE="$(bash scripts/lib/dev-compose.sh)"   # then: $COMPOSE up -d' >&2
        printf '%s' "$hardcoded" >&2
        fail=1
    fi
fi

# ---- 0. every pom in the overlay really is ai.tessary.paid ----
# Without this the Maven ban is decorative: `git mv backend/product tessary-paid/product` produces a
# pom whose <parent> still says ai.tessary:backend, so the moved module keeps the OPEN group id and
# `<exclude>ai.tessary.paid:*</exclude>` never matches it. An open module could then depend on paid
# code with a green build. Checked by text rather than by resolving the model, because this must run
# in a second and must fail BEFORE someone waits out a reactor build to learn about it.
# The export candidate is this tree MINUS tessary-paid/, and the public repo IS that candidate, so
# this script has to pass with the overlay absent — that is the D2 property it exists to protect.
# It did not: `find tessary-paid` returns 1 when the directory is gone, `set -o pipefail` propagates
# that through the rule-1 pipeline, and `set -e` then killed the script at the assignment. Exit 1,
# no message, on the one tree we most need a verdict for. The 2>/dev/null hid the diagnostic and not
# the status. Rules 0 and 1 are derived FROM the overlay, so with no overlay they are vacuous rather
# than failing; rules 2, 3 and 5 still run, because a stale `tessary-paid` path in open code is
# exactly the kind of thing the export must not carry.
if [ ! -d tessary-paid ]; then
    echo "note: no tessary-paid/ in this tree — checking it as an export candidate."
    echo "      Rules 0 and 1 are derived from the overlay and have nothing to derive; 2, 3 and 5 still run."
fi

for pom in $(find tessary-paid -mindepth 2 -name pom.xml -not -path '*/target/*' 2>/dev/null | sort || true); do
    if ! grep -q '<groupId>ai\.tessary\.paid</groupId>' "$pom"; then
        echo "ERROR: $pom does not resolve to the paid group id." >&2
        echo "       Its <parent> must be ai.tessary.paid:tessary-paid (relativePath to the" >&2
        echo "       overlay aggregator), or it must declare <groupId>ai.tessary.paid</groupId>" >&2
        echo "       itself. A module moved here with 'git mv' keeps <parent>ai.tessary:backend</parent>" >&2
        echo "       and stays an OPEN artifact, which makes the enforcer's ai.tessary.paid:* ban" >&2
        echo "       silently stop matching it." >&2
        fail=1
    fi
done

# ---- 1. open source must not reference anything the overlay owns ----
# Derived from the overlay's actual contents: every package that holds paid Java source becomes a
# forbidden prefix for open source. With no paid modules yet this is a no-op that grows correct by
# itself, rather than a fixed string that will never match. Covers both `import x.y.Z` and
# fully-qualified use, and scans resources too (a Spring @ComponentScan base package or a class name
# in application.yaml breaks at BOOT, where no compiler is watching).
# `|| true` on the find, not just 2>/dev/null: with the overlay absent find exits 1, pipefail
# propagates it, and set -e used to kill the script here without printing anything.
paid_pkgs=$( (find tessary-paid -path '*/src/main/java/*' -name '*.java' -not -path '*/target/*' 2>/dev/null || true) \
    | sed -E 's|^tessary-paid/.*/src/main/java/||; s|/[^/]+\.java$||' \
    | tr '/' '.' | sort -u)
for pkg in $paid_pkgs; do
    esc=$(printf '%s' "$pkg" | sed 's/\./\\./g')
    hits=$(grep -rn "${esc}\." backend/*/src 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "ERROR: open backend source references '$pkg', a package that lives in the paid" >&2
        echo "       overlay. Invert it behind an SPI in the open module, or move the caller:" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# The group-id string still has one honest use: an open pom declaring a paid dependency. The Maven
# enforcer fails that too, but only after a reactor starts; this says why in a second.
hits=$(grep -n 'ai\.tessary\.paid' backend/*/pom.xml 2>/dev/null || true)
if [ -n "$hits" ]; then
    echo "ERROR: an open module's pom declares a paid dependency:" >&2
    echo "$hits" | sed 's/^/  /' >&2
    fail=1
fi

# ---- 2. the frontend ----
hits=$(grep -rn 'tessary-paid' frontend/src 2>/dev/null || true)
if [ -n "$hits" ]; then
    echo "ERROR: the open frontend names tessary-paid/. Paid surfaces reach the app through the" >&2
    echo "       '@paid' seam (frontend/src/paid/), which resolves INSIDE frontend/src in this" >&2
    echo "       edition and is repointed by the overlay's own build config in a paid one. Open" >&2
    echo "       source must never name the overlay — not as a static import and not inside a" >&2
    echo "       dynamic import(), which would be a runtime 404 rather than a build failure:" >&2
    echo "$hits" | sed 's/^/  /' >&2
    fail=1
fi

# A path alias defeats the grep above: `import { Usage } from '@paid'` names no folder.
# The files that can declare one are the only places to look — and for vite that is SIX filenames,
# not one. Vite's DEFAULT_CONFIG_FILES resolves vite.config.js and vite.config.mjs BEFORE
# vite.config.ts (node_modules/vite/dist/node/chunks/node.js), so a vite.config.js dropped beside
# the checked .ts shadows it: the negative grep never opens the new file, the positive assertion
# below still finds its literal in the now-dead .ts, and the open build resolves `@paid` wherever
# the shadowing file says. Naming every candidate is what closes that.
for f in frontend/vite.config.js frontend/vite.config.mjs frontend/vite.config.ts \
         frontend/vite.config.cjs frontend/vite.config.mts frontend/vite.config.cts \
         frontend/tsconfig.json frontend/tsconfig.app.json; do
    [ -f "$f" ] || continue
    hits=$(grep -n 'tessary-paid' "$f" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $f aliases a path into the paid overlay. That makes every import through the" >&2
        echo "       alias invisible to the check above, and puts paid source in the open build:" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# The POSITIVE half of the same question, added by #846 with the seam it guards.
#
# Both blocks above are grep-for-absence, and absence is exactly what an alias can fake: the whole
# reason `@paid` exists is that open source imports one specifier and the build decides what it
# resolves to. Repoint that one target outward — to `../tessary-paid/...`, to a sibling checkout, to
# anywhere — and every check above still passes, because `frontend/src` still contains no such
# string. Then the open bundle silently carries paid code. So the target itself is asserted, in both
# files that declare it, and the open stub is asserted to exist.
#
# What this does NOT do: resolve the alias. It is a text check like everything else in this script,
# and it runs in under a second. The real proof that no paid code reached the open bundle is
# `scripts/check-frontend.sh`, which asserts over the emitted chunks after a production build.
if [ -d frontend/src ]; then
    # The positive assertions below read frontend/vite.config.ts by name. Any config vite would
    # prefer over it makes them assert a file the build never loads, so those must not exist.
    for shadow in frontend/vite.config.js frontend/vite.config.mjs; do
        [ -f "$shadow" ] && {
            echo "ERROR: $shadow shadows frontend/vite.config.ts — vite resolves it first. The" >&2
            echo "       '@paid' assertions below read the .ts by name, so they would be pinning a" >&2
            echo "       file the build ignores. Keep one open vite config, and keep it .ts." >&2
            fail=1
        }
    done
    [ -f frontend/src/paid/index.ts ] || {
        echo "ERROR: frontend/src/paid/index.ts is missing. It is the OPEN edition's answer to the" >&2
        echo "       '@paid' seam — real code with empty exports, not a placeholder — and both the" >&2
        echo "       vite alias and the tsconfig path point at it. Without it the open build has an" >&2
        echo "       unresolvable import." >&2
        fail=1
    }
    grep -q '"@paid": fileURLToPath(new URL("./src/paid/index.ts", import.meta.url))' frontend/vite.config.ts || {
        echo "ERROR: frontend/vite.config.ts no longer aliases '@paid' to ./src/paid/index.ts." >&2
        echo "       An '@paid' target anywhere outside frontend/src puts paid source in the OPEN" >&2
        echo "       bundle while every absence check in this rule still passes — which is the" >&2
        echo "       escape hatch this assertion exists to close. The paid build overrides the" >&2
        echo "       alias from its OWN config in the overlay; this one never moves." >&2
        fail=1
    }
    grep -q '"@paid": \["\./src/paid/index\.ts"\]' frontend/tsconfig.json || {
        echo "ERROR: frontend/tsconfig.json no longer maps '@paid' to ./src/paid/index.ts." >&2
        echo "       Same hole as the vite alias above, one file over: tsc would type-check the" >&2
        echo "       open tree against whatever it points at instead." >&2
        fail=1
    }
fi

# ---- 3. the open images' build inputs stay open ----
# Derived from the denylist declaration, not from literals: every `delete` row in
# scripts/lib/export-denylist.txt is a path the scrub removes, so an open Dockerfile that names one
# breaks at export time. The first such leak was #926 (frontend/Dockerfile COPYed Caddyfile.prod);
# until #1105 this rule checked that one literal plus the overlay and nothing else.
DENYLIST=scripts/lib/export-denylist.txt
deny_rows=$(grep -vE '^[[:space:]]*(#|$)' "$DENYLIST" 2>/dev/null | awk -F'|' '$2 == "delete" { print $1 "|" $3 }' || true)
if [ -z "$deny_rows" ]; then
    echo "ERROR: $DENYLIST declares no delete rows (or is missing); rules 3 and 6 derive from it." >&2
    fail=1
fi
deny_paths=$(printf '%s\n' "$deny_rows" | cut -d'|' -f1)
# A Dockerfile can only COPY from inside its build context, so a denied path is a hazard to an image
# only when it sits inside that context, spelled relative to it. backend/Dockerfile builds from
# ./backend (docker-compose.yml), where `product/` is the open Java module and no denied path lives;
# frontend/Dockerfile and the agent-sandbox Dockerfile build from the repo root.
for spec in backend/Dockerfile:backend frontend/Dockerfile:. sandbox-runner/agent-sandbox/Dockerfile:.; do
    f="${spec%%:*}"; ctx="${spec##*:}"
    [ -f "$f" ] || continue
    if [ "$ctx" = . ]; then
        in_ctx="$deny_paths"
    else
        in_ctx=$(printf '%s\n' "$deny_paths" | grep "^$ctx/" | sed "s|^$ctx/||" || true)
    fi
    [ -n "$in_ctx" ] || continue
    deny_alt=$(printf '%s\n' "$in_ctx" | sed 's/[.]/\\./g' | paste -sd'|' -)
    hits=$(grep -nE "^(COPY|ADD)[^#]*(^|[[:space:]]|[\"'])(\./)?($deny_alt)([^A-Za-z0-9_-]|$)" "$f" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $f copies a path on the must-not-publish denylist ($DENYLIST) into an open" >&2
        echo "       image. The scrub deletes it, so the build breaks at export time; per D4 the" >&2
        echo "       published open image carries only open code:" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# Two images build with the REPO ROOT as their context (frontend/Dockerfile via docker-compose.yml,
# sandbox-runner/agent-sandbox/Dockerfile via release.yml), so every denied path is
# inside that context and ships to the daemon unless the root .dockerignore drops it. A broad COPY
# would then bake it into an open image with nothing failing. Every `delete` row must be covered by
# a .dockerignore line: the row itself, or a broader existing line whose prefix contains it (`.github/`
# already covers the three deploy workflows; that is recognised, not duplicated). The overlay row is
# guarded on the overlay being present, as before; the rest are present in every checkout.
_dockerignore_covers() {
    _want="$1"
    while IFS= read -r _line; do
        case "$_line" in ''|'#'*) continue ;; esac
        _l="${_line%/}"
        [ "$_l" = "$_want" ] && return 0
        case "$_want" in "$_l"/*) return 0 ;; esac
    done < .dockerignore
    return 1
}
while IFS='|' read -r _p _scope; do
    [ -n "$_p" ] || continue
    if [ "$_p" = tessary-paid ] && [ ! -f tessary-paid/pom.xml ]; then continue; fi
    _dockerignore_covers "$_p" || {
        echo "ERROR: .dockerignore does not exclude $_p, a delete row in $DENYLIST. Two open images" >&2
        echo "       build with the repo root as their context, so a broad COPY would bake it into" >&2
        echo "       an OPEN image (D4). Add the line, or a prefix line that covers it." >&2
        fail=1
    }
done <<< "$deny_rows"

# ---- 5. the open services and the open check pipeline must not READ out of the overlay ----
# Rule 1 covers open Java, rule 2 the frontend, rule 3 the images — and between them they left the
# whole middle of the repo unwatched. #841 moved `conformance_parity.json` into
# `tessary-paid/conformance/` and repointed three OPEN consumers at it: two Node tests in
# classify-service (open per the ledger: "`/embed` stays open and paid conformance consumes it across
# the boundary") and `check-classify-service.sh`, which `scripts/check.sh` runs. Every gate stayed
# green — no Maven dependency, no paid package name in any open source file — while `task check` and
# two CI jobs were red in the public repo, where the directory does not exist. This rule closes that:
# an open service or check script that names the overlay is reading a file the export deletes.
#
# Four scripts MANAGE the overlay and must name it; every other one must not. Shared fixtures go the
# other way round — they live open and paid consumes them across the boundary, with a `<testResource>`
# or an equivalent, which is the only direction D2 allows.
#
# The fourth, added by #849: scripts/check-open-boot.sh. It is not "reading a file the export
# deletes" the way this rule exists to catch — its whole job is the opposite direction, deleting
# `tessary-paid/` from its OWN scratch export (a working-tree copy into a tmp dir via
# `scripts/lib/export-simulate.sh`, #889) to prove the open edition boots without it, then
# asserting the directory does not silently reappear (the #886 ghost
# regression). A script whose entire purpose is managing the overlay's absence has to name it to do
# that job, same as check-module-hygiene.sh and check.sh already do — it is EXCLUDED from
# scripts/check.sh's own pipeline (see that manifest row) for an unrelated reason, needing Docker,
# not this one.
#
# A FIFTH file names the overlay on purpose and is legitimately outside this rule's reach entirely
# (not on the allowlist above — it never appears in `targets` at all):
# scripts/lib/dev-compose.sh, which emits the dev stack's `-f` set and appends the overlay's compose
# fragment when it exists (#886). The target set below is `ls scripts/*.sh`, which does not recurse
# into scripts/lib/, and that is the whole reason the derivation lives there — the alternative was
# growing `boundary_aware` from three names to five so scripts/dev.sh and scripts/dev-up.sh could
# each carry their own probe, and tessary-paid/OPEN-CORE.md:403 records that this rule was settled once before by
# MOVING a script rather than growing that allowlist. The distinction the allowlist encodes still
# holds: dev-compose.sh is edition-switch WIRING and never READS an overlay file — it emits a second
# `-f` only when one is on disk — so it cannot be the "open gate red in the public repo" failure
# this rule exists to catch. If anyone widens the glob to scripts/**/*.sh, ALLOWLIST that file. Do
# not delete its probe: without it every dev entry point boots the open edition with eleven bind
# mounts naming a directory that is not there.
#
# A SIXTH file is in the same position (#889): scripts/lib/export-simulate.sh, the extracted
# git-archive + git-init faithful export recipe check-open-boot.sh proved under #849. Its whole job
# is naming and stripping `tessary-paid/` on purpose to produce a scratch tree that does not have
# it, the same reason dev-compose.sh sits here rather than in `boundary_aware` — it lives under
# scripts/lib/, outside the `ls scripts/*.sh` glob, deliberately.
boundary_aware="scripts/check-open-boundary.sh scripts/check-module-hygiene.sh scripts/check.sh scripts/check-open-boot.sh"
targets=$(find classify-service -type f -not -path '*/node_modules/*' 2>/dev/null | sort)
targets="$targets $(ls scripts/*.sh 2>/dev/null)"
for f in $targets; do
    case " $boundary_aware " in *" $f "*) continue ;; esac
    hits=$(grep -n 'tessary-paid' "$f" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $f reads or names tessary-paid/. It ships in the public export, where D2's" >&2
        echo "       folder filter has deleted that directory — this is a red 'task check' in the" >&2
        echo "       open repo with every other gate green. Move the shared file to the open tree" >&2
        echo "       and let the paid module consume it across the boundary:" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# ---- 6. open markdown must not resolve a path the export deletes ----
# Same failure as rule 5, one file type over. `check-docs-links.sh` resolves every `[text](path)` in
# the repo's markdown, so an open doc that links a path under `tessary-paid/` is a red docs gate in
# the public export and green here. Found the way rules 1-5 were: `docs/modules.md:359` linked
# `../tessary-paid/README.md` from commit 42eb4901 and nothing caught it for three issues, because
# every other gate reads code and this one reads prose.
#
# Naming the overlay in prose is fine and often necessary — this repo carries both editions. Only a
# RESOLVABLE reference is banned, and `check-docs-links.sh` resolves two forms, not one: a
# `[text](path)` link (its LINK regex) and a bare backticked path ending in `.md` (its TICKED_MD
# regex). De-linking is therefore not enough on its own — `\`tessary-paid/README.md\`` still
# resolves. Both forms are checked below.
# Only markdown the export SHIPS is a target, and the target set and the detection set are ONE set,
# both derived from the `delete` rows of scripts/lib/export-denylist.txt (#1105). Until then the
# exemption list was three `grep -v` literals and the detection regex matched two of them: the rule
# printed `open boundary: ok` while 70 dangling references into denied trees sat behind the green.
# Two consequences of deriving:
#   - a document the scrub deletes is not a target (a reference from inside it can never break a gate
#     in the public repo), and every surviving document is checked against EVERY deleted path;
#   - `contract/` is exempt as a target for the reason check-docs-links.sh gives: those files are
#     vendored verbatim from the plugin repo and are not ours to edit.
# Resolution matches check-docs-links.sh's two forms and its semantics: a `[text](path)` link and a
# bare backticked path ending in `.md`, each resolved RELATIVE TO THE CITING FILE and normalised, so
# `../reference/deployment.md` from docs/guides/ is caught exactly as `docs/reference/deployment.md`
# would be. (The old regex only matched root-relative spellings, which is a second reason it went
# quiet.) #889 AC4's point still holds: nothing under a denied path is ever opened, so the rule
# behaves identically on an mv-detached overlay and on a real export.
# `.mdx` JOINED THE TARGET SET IN #1192, for the reason this rule exists at all. The file list was
# `git ls-files … '*.md'`, and every page this project PUBLISHES is `.mdx`, so the six pages a
# stranger actually reads were the one document set no boundary rule could see. A published page
# linking into `classifiers/` or `tessary-paid/` would have shipped a dangling link into the public
# repo and reddened check-docs-links.sh THERE while passing here — the exact split-brain failure
# this rule was built to close, reintroduced by an extension filter. check-docs-links.sh scans the
# same two extensions, so the target sets stay identical, which is what makes "green here means
# green there" true.
if [ -n "$deny_rows" ]; then
    rule6_out=$(DENY_ROWS="$deny_rows" MD_FILES="$(git ls-files --cached --others --exclude-standard '*.md' '*.mdx' 2>/dev/null)" python3 - <<'PYEOF'
import os, posixpath, re
rows = [l.split('|') for l in os.environ['DENY_ROWS'].split('\n') if l.strip()]
dirs = tuple(p + '/' for p, s in rows if s == 'dir')
files = {p for p, s in rows if s == 'file'}
def denied(p):
    return p in files or p.startswith(dirs)
LINK = re.compile(r'(?<!\!)\[[^\]]*\]\(([^)\s#]+)')
TICKED_MD = re.compile(r'`([A-Za-z0-9_./\-]+\.mdx?)`')
FENCE = re.compile(r'^\s*(```|~~~)')
bad = []
for f in os.environ['MD_FILES'].split('\n'):
    if not f or denied(f) or f.startswith('contract/'):
        continue
    base = posixpath.dirname(f)
    in_fence = False
    with open(f, encoding='utf-8', errors='replace') as fh:
        for n, line in enumerate(fh, 1):
            if FENCE.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            targets = [m for m in LINK.findall(line) if not m.startswith(('http://', 'https://', 'mailto:'))]
            targets += TICKED_MD.findall(line)
            for t in targets:
                p = posixpath.normpath(posixpath.join(base, t))
                if denied(p) or (not t.startswith('.') and denied(t)):
                    bad.append(f"{f}:{n}: {t}")
print('\n'.join(bad))
PYEOF
    )
    if [ -n "$rule6_out" ]; then
        echo "ERROR: these open documents resolve a path the export deletes (a delete row in" >&2
        echo "       $DENYLIST). The public export removes it, so check-docs-links.sh goes red" >&2
        echo "       there while passing here. Keep the mention as prose and drop both the link" >&2
        echo "       markup and the backticked .md path:" >&2
        echo "$rule6_out" | sed 's/^/  /' >&2
        fail=1
    fi
fi

# ---- 7. the orchestration files name the overlay only under a visible condition ----
# Rules 1-6 cover code and prose. These files are neither: they are the edition SWITCHES, so they
# must be allowed to name tessary-paid/ — and that is exactly why nothing was watching them.
# See the header for what this deliberately does not cover (the four deploy workflows) and why.
#
# docker-compose.dev.yml joined the loop in #886, and it is a different animal from the other two:
# YAML data has no conditionals, so a probe there can only ever be TEXT. The contract for that file
# is "comments only" (form 1) — because after #886 the open dev compose must name no
# `./tessary-paid/...` bind source at all, and a mount added back to it must red here immediately.
# That contract is ENFORCED rather than assumed (`comments_only` below), and the enforcement is the
# point: the probe forms are text matches, so without it a comment carrying the probe idiom —
# `# merged only when [ -f tessary-paid/pom.xml ]`, or the honest `# scripts/lib/dev-compose.sh
# merges the overlay when [ -f tessary-paid/docker-compose.dev.yml ]` this script's own error text
# invites you to write — would satisfy form 3 or 4 and VOUCH FOR THE MOUNT ON THE NEXT LINE. That
# is the single likeliest way a mount comes back (put it back, explain when it applies), and it
# went green until #886's review. Forms 2/2b go with them: `desc:`/`name:` are Taskfile and
# workflow keys, not compose ones. This coverage is only non-vacuous because the same commit
# emptied the file of references; if you are ever tempted to relax it, read the header entry above.
#
# The check is textual and deliberately generous, because the failure mode of a too-strict rule
# here is that the next person deletes the rule rather than fixing the code. Four accepted forms,
# and the error message names all four so a red is self-explaining. Form 3 is what keeps
# Taskfile.yml:36's DC_PROD legal: it names tessary-paid/docker-compose.yml as compose wiring that
# can never move into a check script, and it carries its own `[ -f ... ]` probe on the same line.
#
# Generous is not the same as blind, and both of the ways it was blind were the same mistake:
# reasoning about lines by DISTANCE and by RAW indent, in a file format where neither says what
# block a line belongs to. YAML indents a sequence item by its dash (`- name:` at 6) while the
# item's sibling keys line up AFTER the dash (`run:`, `with:`, `if:` at 8), so:
#   * the folded-scalar tracker, which closed the block on the first line not indented deeper than
#     the OPENING line's dash, exempted the whole step rather than the folded name's own value
#     lines. `- name: >-` over two lines made an unconditional `run: bash tessary-paid/...`
#     invisible, in the one file this rule exists to police.
#   * form 4 scanned the 6 preceding RAW lines with no idea which block they were in, so a probe
#     that had already closed still vouched for a reference below it. Adding a new `cmds:` entry to
#     a task that already contains a guard — the single most likely future edit here — passed.
# Both are fixed by measuring the KEY column (step over a leading `- `) instead of the first
# non-space column, and by asking whether the probe's block still CONTAINS the reference instead
# of how many lines away it is.
BOUNDARY_PROBE_LOOKBACK=6
# Bracket-class literals, not backslash escapes: the awk on macOS rejects \[ and \( in an ERE.
# The `(![[:space:]]+)?` is not decoration: `[ ! -d tessary-paid ]` is a real guard shape — it is
# how you write the SKIP branch first, and it is what this very script uses at its own rule-5
# preamble. Without it the rule reds correctly-guarded code, and a rule that reds correct code
# gets deleted rather than obeyed.
probe_re='[[][[:space:]]+(![[:space:]]+)?-[dfe][[:space:]]+tessary-paid|hashFiles[(]'"'"'tessary-paid'
for f in Taskfile.yml .github/workflows/ci.yml docker-compose.dev.yml; do
    [ -f "$f" ] || continue
    # Which accepted forms apply, per file. The two orchestration files execute what they say, so a
    # probe in them is a real conditional and all four forms count. Compose is DATA: nothing in it
    # executes, so forms 2-4 would only ever match text — see the paragraph above for why letting
    # them match is a bypass rather than generosity.
    comments_only=0
    [ "$f" = docker-compose.dev.yml ] && comments_only=1
    unguarded=$(awk -v lookback="$BOUNDARY_PROBE_LOOKBACK" -v probe="$probe_re" \
                    -v comments_only="$comments_only" '
        # Column of the first non-space character, 0-based; -1 for a blank line.
        function indent_of(s) {
            if (s ~ /^[[:space:]]*$/) return -1
            return match(s, /[^ ]/) - 1
        }
        # Column at which CONTENT starts, stepping over a YAML sequence dash. For
        # `      - name: x` that is 8, not 6 — the column where every sibling key of that item
        # sits, and therefore the only one that says anything about block membership.
        function key_indent_of(s,   p, rest, q) {
            p = indent_of(s)
            if (p < 0) return -1
            rest = substr(s, p + 1)
            if (rest ~ /^-[[:space:]]/) {
                q = match(substr(rest, 2), /[^ ]/)
                if (q > 0) return p + q
            }
            return p
        }
        # Does the probe on line `pl` still govern the reference on line NR, whose first
        # non-space column is `ri`? Two shapes count, and nothing else does:
        #   nested  — the reference is indented deeper than the probe key column, i.e. it is
        #             inside the probed shell block or under the probed YAML key;
        #   sibling — the two sit at the same column in the same mapping, which is how a step
        #             level `if:` in a workflow guards the `run:` beneath it.
        # Either way that block must still be open at NR: a line dedenting past the probe key
        # column has closed it, and in the sibling case a new sequence item (a dash to the LEFT
        # of the shared column) starts a different node.
        function governs(pl, ri,   pki, j, ind) {
            pki = key_indent_of(lines[pl])
            if (pki < 0 || pki > ri) return 0
            for (j = pl + 1; j < NR; j++) {
                ind = indent_of(lines[j])
                if (ind < 0) continue
                if (ind < pki) return 0
                if (pki == ri && ind < ri && lines[j] ~ /^[[:space:]]*-[[:space:]]/) return 0
            }
            return 1
        }
        {
            lines[NR] = $0
            # Track YAML block scalars opened by a desc:/name: (`desc: >-`, `name: |`), so their
            # CONTINUATION lines count as part of that label rather than as bare references. A
            # folded desc is legal YAML and this file is full of long descriptions; without this,
            # writing one over three lines would false-red, and a rule that false-reds on ordinary
            # authoring gets deleted rather than obeyed. The block is the value lines of that one
            # scalar and NOTHING else: YAML ends it at the first line not indented past the KEY,
            # which is exactly where the next sibling key of the same step begins.
            if (in_block) {
                if ($0 ~ /^[[:space:]]*$/) { block_line = 1 }
                else if (indent_of($0) > block_indent) { block_line = 1 }
                else { in_block = 0; block_line = 0 }
            } else { block_line = 0 }
            if (!in_block && $0 ~ /^[[:space:]]*(-[[:space:]]+)?(desc|name):[[:space:]]*[>|]/) {
                in_block = 1
                block_indent = key_indent_of($0)
            }
        }
        /tessary-paid/ {
            if ($0 ~ /^[[:space:]]*#/) next                       # 1. a comment
            # Everything below is a form that only means something in a file that EXECUTES. In a
            # compose file they would all reduce to "some text nearby said [ -f ... ]", which
            # guards nothing and vouches for the mount underneath it.
            if (!comments_only) {
                if (block_line) next                              # 2b. inside a folded desc:/name:
                # The optional "- " is a YAML list item, i.e. a workflow step with its own name.
                if ($0 ~ /^[[:space:]]*(-[[:space:]]+)?(desc|name):/) next  # 2. a human-facing label
                if ($0 ~ probe) next                              # 3. a probe on the line itself
                ri = indent_of($0)
                for (i = NR - 1; i >= NR - lookback && i >= 1; i--)   # 4. a probe just above it,
                    if (lines[i] ~ probe && governs(i, ri)) next      #    still governing this block
            }
            printf "%d:%s\n", NR, $0
        }
    ' "$f")
    if [ -n "$unguarded" ]; then
        echo "ERROR: $f names tessary-paid/ with nothing making it conditional. These files ARE" >&2
        echo "       the edition switch, so naming the overlay is fine — naming it unconditionally" >&2
        echo "       is not: the public export has no such directory, so the step runs against a" >&2
        echo "       path that is not there. In docker-compose.dev.yml specifically, a bind mount" >&2
        echo "       naming a missing source is WORSE than a broken path: compose creates it, and" >&2
        echo "       the ghost directory reactivates backend/pom.xml's 'paid' profile (#886). Paid" >&2
        echo "       dev wiring belongs in tessary-paid/docker-compose.dev.yml, which is merged by" >&2
        echo "       scripts/lib/dev-compose.sh's file-exists probe. Accepted forms, any one of them" >&2
        echo "       — except in docker-compose.dev.yml, where ONLY form 1 is accepted: nothing in a" >&2
        echo "       compose file executes, so a probe idiom written in a comment would vouch for the" >&2
        echo "       mount below it without guarding anything:" >&2
        echo "         1. a comment line (starts with #)" >&2
        echo "         2. a human-facing label: a Taskfile 'desc:' or a workflow 'name:' value," >&2
        echo "            including the continuation lines of a folded one ('desc: >-') — which are" >&2
        echo "            the lines indented past the KEY, not every line of the step" >&2
        echo "         3. an existence probe on the SAME line — '[ -d tessary-paid ]', its negated
            form '[ ! -d tessary-paid ]', '[ -f ... ]'," >&2
        echo "            '[ -e ... ]', or a workflow \"if: hashFiles('tessary-paid/...') != ''\"" >&2
        echo "         4. one of those probes within $BOUNDARY_PROBE_LOOKBACK lines ABOVE the reference AND still" >&2
        echo "            governing it: the reference is nested under the probe, or is a sibling key" >&2
        echo "            of the same YAML node. A probe in a previous list item, or in a shell" >&2
        echo "            block that has already closed, does not travel down to the next one —" >&2
        echo "            put the reference inside the guard, or give it its own." >&2
        echo "       Unguarded:" >&2
        echo "$unguarded" | sed "s|^|  $f:|" >&2
        fail=1
    fi
done

# ---- 9. the edition is a bean, never a string literal at its two call sites ----
# CapabilityService decides which capabilities are unavailable and TelemetryHeartbeat reports which
# build sent the D6 ping. Both used to hardcode the open edition; since #1133 both read the Edition
# bean (open default in EditionConfig, displaced by tessary-paid/plan's PaidEdition). A literal "open"
# or "paid" reappearing in either file is the regression that makes a paid build lie about itself
# while every other gate stays green, so it reds here by name.
for f in backend/product/src/main/java/ai/tessary/plan/CapabilityService.java \
         backend/surfaces/src/main/java/ai/tessary/telemetry/TelemetryHeartbeat.java; do
    [ -f "$f" ] || continue
    hits=$(grep -nE '"(open|paid)"' "$f" || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $f carries an edition string literal. Read the Edition bean instead (#1133); a" >&2
        echo "       literal here reports the wrong edition in one build while every gate stays green:" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# ---- 8. dependabot.yml never points a directory: entry at tessary-paid/ ----
# See the header. A ban, not a conditional-reference rule like 5-7: there is no probe idiom in
# dependabot.yml's schema, so any `directory:` entry under tessary-paid/ is wrong, unconditionally.
# Matches quoted or unquoted values, the singular `directory:` key, and both the plural
# `directories:` key and its list items (`- "/tessary-paid/..."`) — a bare `directory:`-only
# regex misses all three.
if [ -f .github/dependabot.yml ]; then
    paid_dirs=$(grep -nE '^\s*(directory|directories)\s*:\s*["'"'"']?/?tessary-paid(/|["'"'"']|$)|^\s*-\s*["'"'"']?/?tessary-paid(/|["'"'"']|$)' .github/dependabot.yml || true)
    if [ -n "$paid_dirs" ]; then
        echo "ERROR: .github/dependabot.yml names a directory under tessary-paid/. The public" >&2
        echo "       export deletes that directory, and this open-core repo's own Dependabot bot" >&2
        echo "       must not open dependency-update PRs against paid-tree manifests — the same" >&2
        echo "       leak class rule 5 exists to catch for scripts/*.sh, one file type over." >&2
        echo "       tessary-paid/compile-service is the one uv workspace actually inside" >&2
        echo "       tessary-paid/; it must simply be absent from this file, not guarded." >&2
        echo "$paid_dirs" | sed 's/^/  .github\/dependabot.yml:/' >&2
        fail=1
    fi
fi

[ "$fail" = 0 ] && echo "open boundary: ok"
exit $fail
