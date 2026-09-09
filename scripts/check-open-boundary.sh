#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The open/paid direction check, on the surfaces Maven cannot see.
#
# Paid code lives in tessary-paid/, and the public export is a folder filter: deleting that
# directory must leave a tree that still builds. Maven enforces half of this itself
# (backend/pom.xml bans ai.tessary.paid:* as a dependency of any open module), but that rule has
# a load-bearing assumption this script checks, plus gaps a reactor cannot close:
#
#   0. The Maven ban keys on a group id, and nothing forces a pom moved into tessary-paid/ to
#      carry it — a `git mv` module inherits its parent's ai.tessary groupId and the ban silently
#      stops matching it.
#   1. Java source text can reference a moved class without ever mentioning the paid group id
#      (packages here are all ai.tessary.*), so the check is derived from the overlay's actual
#      contents, not the group id.
#   2. The frontend has no Maven at all, and its build aliases can point outside frontend/.
#   3. The open images' build inputs: the published open image must carry only open code.
#      frontend/Dockerfile builds with the repo root as context, so the overlay is reachable
#      unless .dockerignore excludes it.
#   4. The wiring itself: if the `paid` profile is dropped from backend/pom.xml, the overlay
#      silently stops building and every check downstream goes green on a lie.
#   5. Everything else that ships in the export and reads files by path: classify-service/ and the
#      check scripts themselves. Nothing here compiles, so a relative path into the overlay is
#      just a string until the export deletes what it points at.
#   6. Open markdown that resolves a path the export deletes.
#   7. The orchestration files, by exact path: Taskfile.yml, .github/workflows/drift-checks.yml and
#      .github/workflows/check.yml (check.yml runs the gate per PR, so it is where a stray compose
#      invocation would land) (plus
#      docker-compose.dev.yml). They ARE the edition switch, so they may name the overlay, but
#      only under a visibly conditional form: a comment, a human-facing desc:/name: value, or a
#      reference carrying (or governed by) an existence probe.
#   8. .github/dependabot.yml must never point a `directory:` entry at tessary-paid/ — no probe
#      idiom exists for it, so this is a ban, not a conditional-reference rule.
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

# The same assertion for the dev compose wiring. Not scoped to `[ -f tessary-paid/pom.xml ]` like
# the Maven half above: the derivation is open code and must survive the export intact, and the
# failure this catches (a call site that hardcodes the base file) is silent in both editions.
#
# check-module-hygiene.sh rule 2b pins the paid mounts inside tessary-paid/docker-compose.dev.yml,
# and that pin only reaches the container if the fragment is actually merged — which now rests
# entirely on scripts/lib/dev-compose.sh and every call site going through it.
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
    # Every call site must route through it. Comments are exempt (a check for invocations); *.md
    # is out of the target set on purpose since prose telling a human to run a service isn't one.
    hardcoded=""
    for f in Taskfile.yml .github/workflows/drift-checks.yml .github/workflows/check.yml .dev-tools/agent-loop/config.yaml \
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
# Without this the Maven ban is decorative: `git mv backend/product tessary-paid/product` keeps a
# pom whose <parent> still says ai.tessary:backend, so the moved module keeps the open group id and
# the enforcer's ban never matches it. Checked by text, not by resolving the model, so this runs in
# a second and fails before anyone waits out a reactor build to learn about it.
#
# This script must also pass with the overlay absent, since the public repo is that export
# candidate. The `|| true` on the `find` below is load-bearing: with the overlay gone, `find`
# exits 1, pipefail propagates it, and `set -e` would otherwise kill the script silently, with no
# message, on the one tree that most needs a verdict. Rules 0 and 1 are derived from the overlay,
# so with no overlay they are vacuous rather than failing; rules 2, 3 and 5 still run, since a
# stale tessary-paid path in open code is exactly what the export must not carry.
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
# forbidden prefix for open source, so this grows correct by itself as the overlay grows. Covers
# both `import x.y.Z` and fully-qualified use, and scans resources too (a Spring @ComponentScan
# base package or a class name in application.yaml breaks at boot, where no compiler is watching).
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

# An open pom declaring a paid dependency: the Maven enforcer fails this too, but only after a
# reactor starts, so this says why in a second.
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

# A path alias defeats the grep above: `import { Usage } from '@paid'` names no folder. Vite
# resolves vite.config.js/.mjs before .ts, so a vite.config.js dropped beside the checked .ts would
# shadow it silently; naming every candidate config file is what closes that.
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

# The positive half of the same question: both blocks above are grep-for-absence, and absence is
# exactly what an alias can fake by repointing `@paid`'s one target outward while `frontend/src`
# still contains no such string. So the target itself is asserted, in both files that declare it,
# and the open stub is asserted to exist.
#
# This does not resolve the alias; it's a text check like everything else here. The real proof
# that no paid code reached the open bundle is scripts/check-frontend.sh, over the emitted chunks
# after a production build.
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
# scripts/lib/export-denylist.txt is a path the scrub removes, so an open Dockerfile that names
# one breaks at export time.
DENYLIST=scripts/lib/export-denylist.txt
deny_rows=$(grep -vE '^[[:space:]]*(#|$)' "$DENYLIST" 2>/dev/null | awk -F'|' '$2 == "delete" { print $1 "|" $3 }' || true)
if [ -z "$deny_rows" ]; then
    echo "ERROR: $DENYLIST declares no delete rows (or is missing); rules 3 and 6 derive from it." >&2
    fail=1
fi
deny_paths=$(printf '%s\n' "$deny_rows" | cut -d'|' -f1)
# A Dockerfile can only COPY from inside its build context, so a denied path is a hazard only when
# it sits inside that context, spelled relative to it. backend/Dockerfile builds from ./backend;
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

# Two images build with the repo root as their context, so every denied path ships to the daemon
# unless the root .dockerignore drops it, and a broad COPY would then bake it into an open image
# with nothing failing. Every `delete` row must be covered by a .dockerignore line: the row itself,
# or a broader existing line whose prefix contains it.
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
# Rule 1 covers open Java, rule 2 the frontend, rule 3 the images, leaving the whole middle of the
# repo unwatched: an open service or check script that names the overlay is reading a file the
# export deletes, invisible to every source-and-dependency check above.
#
# Four scripts manage the overlay and must name it; every other one must not. Shared fixtures go
# the other way round: they live open, and paid consumes them across the boundary.
#
# scripts/check-open-boot.sh names the overlay to delete it from its own scratch export (via
# scripts/lib/export-simulate.sh) and then assert it doesn't silently reappear — the opposite
# direction from the leak this rule catches, so it's excluded the same way check-module-hygiene.sh
# and check.sh already are.
#
# scripts/lib/dev-compose.sh and scripts/lib/export-simulate.sh both name and probe the overlay on
# purpose (emitting the dev stack's `-f` set, and stripping the overlay for a faithful export) and
# sit outside this rule's reach entirely: the target set below is `ls scripts/*.sh`, which does not
# recurse into scripts/lib/. Widen that glob and these two need adding to the allowlist.
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

# ---- 6. removed ----
# Removed 2026-09-09 under the standing rule in scripts/check.sh's header: no gate reads a .md
# or .mdx file. Rule 6 asserted that no open .md/.mdx backticked a path
# the export deletes, so it scanned every markdown file in the tree and a doc edit could red the
# boundary gate. What it protected is real and is now caught at export review, where a human reads
# the manifest anyway. Rules 0-5 and 7 read code and config and are unaffected.

# ---- 7. the orchestration files name the overlay only under a visible condition ----
# Rules 1-6 cover code and prose. These files are the edition switches, so they must be allowed to
# name tessary-paid/. See the header for what this deliberately does not cover and why.
#
# docker-compose.dev.yml is a different animal from the other two: YAML data has no conditionals,
# so a probe there can only ever be text. Its contract is "comments only" (form 1), enforced rather
# than assumed (`comments_only` below): without it, a comment carrying the probe idiom — e.g.
# `# merged only when [ -f tessary-paid/pom.xml ]` — would satisfy form 3 or 4 and vouch for a
# mount on the next line without actually guarding it.
#
# The check is textual and deliberately generous, because the failure mode of a too-strict rule
# here is that the next person deletes the rule rather than fixing the code. Four accepted forms,
# and the error message names all four so a red is self-explaining.
#
# Reasoning about lines by distance and by raw indent does not work in a format where neither says
# what block a line belongs to: YAML indents a sequence item by its dash (`- name:` at 6) while the
# item's sibling keys line up after the dash (`run:`, `with:`, `if:` at 8). A naive tracker closes a
# folded scalar's block on the opening line's dash indent rather than the folded value's own indent,
# and a naive lookback vouches for a reference below a probe that has already closed. Both are fixed
# by measuring the key column (stepping over a leading `- `) instead of the first non-space column,
# and by asking whether the probe's block still contains the reference instead of how many lines
# away it is.
BOUNDARY_PROBE_LOOKBACK=6
# Bracket-class literals, not backslash escapes: awk on macOS rejects \[ and \( in an ERE. The
# `(![[:space:]]+)?` matters: `[ ! -d tessary-paid ]` is a real guard shape (this script uses it at
# its own rule-5 preamble), and without it the rule would red correctly-guarded code.
probe_re='[[][[:space:]]+(![[:space:]]+)?-[dfe][[:space:]]+tessary-paid|hashFiles[(]'"'"'tessary-paid'
for f in Taskfile.yml .github/workflows/drift-checks.yml .github/workflows/check.yml docker-compose.dev.yml; do
    [ -f "$f" ] || continue
    # Which accepted forms apply, per file: the two orchestration files execute what they say, so
    # a probe is a real conditional and all four forms count; compose is data, so forms 2-4 would
    # only ever match text.
    comments_only=0
    [ "$f" = docker-compose.dev.yml ] && comments_only=1
    unguarded=$(awk -v lookback="$BOUNDARY_PROBE_LOOKBACK" -v probe="$probe_re" \
                    -v comments_only="$comments_only" '
        # Column of the first non-space character, 0-based; -1 for a blank line.
        function indent_of(s) {
            if (s ~ /^[[:space:]]*$/) return -1
            return match(s, /[^ ]/) - 1
        }
        # Column at which content starts, stepping over a YAML sequence dash: for `      - name: x`
        # that is 8, not 6, the column every sibling key of that item sits at.
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
        # Does the probe on line `pl` still govern the reference on line NR (indent `ri`)? Two
        # shapes count: nested (deeper than the probe key column) or sibling (same column, as a
        # step-level `if:` guards the `run:` beneath it) — and that block must still be open at NR.
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
            # continuation lines count as part of that label rather than as bare references. YAML
            # ends the block at the first line not indented past the key.
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
            # Everything below only means something in a file that executes; in a compose file it
            # would reduce to "text nearby said [ -f ... ]", guarding nothing.
            if (!comments_only) {
                if (block_line) next                              # 2b. inside a folded desc:/name:
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
        echo "       the ghost directory reactivates backend/pom.xml's 'paid' profile. Paid" >&2
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
# CapabilityService decides which capabilities are unavailable, and TelemetryHeartbeat reports
# which build sent the heartbeat. Both read the Edition bean; a literal "open" or "paid"
# reappearing in either file is the regression that makes a paid build lie about itself while
# every other gate stays green.
for f in backend/product/src/main/java/ai/tessary/plan/CapabilityService.java \
         backend/surfaces/src/main/java/ai/tessary/telemetry/TelemetryHeartbeat.java; do
    [ -f "$f" ] || continue
    hits=$(grep -nE '"(open|paid)"' "$f" || true)
    if [ -n "$hits" ]; then
        echo "ERROR: $f carries an edition string literal. Read the Edition bean instead; a" >&2
        echo "       literal here reports the wrong edition in one build while every gate stays green:" >&2
        echo "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# ---- 8. dependabot.yml never points a directory: entry at tessary-paid/ ----
# A ban, not a conditional-reference rule like 5-7: dependabot.yml's schema has no probe idiom, so
# any `directory:` entry under tessary-paid/ is wrong, unconditionally. Matches the singular
# `directory:` key and both the plural `directories:` key and its list items.
if [ -f .github/dependabot.yml ]; then
    paid_dirs=$(grep -nE '^\s*(directory|directories)\s*:\s*["'"'"']?/?tessary-paid(/|["'"'"']|$)|^\s*-\s*["'"'"']?/?tessary-paid(/|["'"'"']|$)' .github/dependabot.yml || true)
    if [ -n "$paid_dirs" ]; then
        echo "ERROR: .github/dependabot.yml names a directory under tessary-paid/. The public" >&2
        echo "       export deletes that directory, and this repo's own Dependabot bot" >&2
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
