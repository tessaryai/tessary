#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Reactor hygiene the Maven build cannot express.
#
# 1. Version properties belong to backend/pom.xml alone. A module that redeclares one shadows the
#    parent for ITSELF and nothing else, so the reactor builds green while that one module quietly
#    compiles against a different version. That is not hypothetical: `app` carried all 14 properties
#    after they were lifted to the parent, and upgrades landed in 11 of 12 modules for a while
#    before a protobuf gencode/runtime skew finally made it fail out loud.
#
# 2. Every module in the reactor must also be enumerated in the three Docker files, which cannot
#    glob. Maven refuses to read an aggregator whose declared <module> directory is missing, so an
#    incomplete list fails the image build outright — and it fails at `docker build` time, which
#    `task check` never runs. That is exactly how `Dockerfile.dev` stayed broken while this script
#    passed: it was checking only two of the three files.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0

# The paid overlay inherits from backend/pom.xml too (open-core decision D2), so a version
# property redeclared there shadows the parent exactly the same way. Glob both trees.
stray=$(grep -n '<[a-z][a-z0-9.-]*\.version>' backend/*/pom.xml tessary-paid/pom.xml tessary-paid/*/pom.xml 2>/dev/null || true)
if [ -n "$stray" ]; then
    echo "ERROR: version property declared outside backend/pom.xml — it shadows the parent for that" >&2
    echo "       module alone and the build stays green while it uses a different version:" >&2
    echo "$stray" | sed 's/^/  /' >&2
    fail=1
fi

modules=$(sed -n 's|.*<module>\(.*\)</module>.*|\1|p' backend/pom.xml)
for m in $modules; do
    # A module path that escapes backend/ is the paid overlay (`../tessary-paid`, added by the
    # file-activated `paid` profile). It is deliberately NOT in the Docker files: per open-core
    # decision D4 the published open image carries only open code, and the paid image layers on
    # top of it. The dev stack still needs it, which is the separate rule below.
    case "$m" in ../*) continue ;; esac
    for f in backend/Dockerfile backend/Dockerfile.dev docker-compose.dev.yml; do
        grep -q "$m/pom.xml" "$f" || { echo "ERROR: module '$m' is in the reactor but not in $f" >&2; fail=1; }
    done
done

# 2a. The converse, and the gap that let a REMOVED module linger. Rule 2 walks the reactor and
#     asserts each module is named in the Docker files; nothing walked the Docker files and asserted
#     each name is still a module. So dropping <module>evaluation</module> from the aggregator left
#     `COPY evaluation/pom.xml` in backend/Dockerfile and backend/Dockerfile.dev, which fails the
#     image build outright with `"/evaluation/pom.xml": not found`, and left docker-compose.dev.yml
#     bind-mounting ./backend/evaluation/{src,pom.xml} — where compose SHORT syntax does not error on
#     a missing source but has dockerd manufacture a ghost host path, the same hazard rule 2b guards
#     for the overlay. Neither failure is visible to `task check`, which never runs `docker build`.
# `$modules` is newline-separated; flatten it to a space-delimited string so the membership
# test below can be a plain substring match (bash 3.2, so no associative arrays).
module_list=" ${modules//$'\n'/ } "
for f in backend/Dockerfile backend/Dockerfile.dev docker-compose.dev.yml; do
    # The s/// delimiter is % throughout, because | is the ERE alternation inside the pattern.
    case "$f" in
        # `COPY <module>/pom.xml ...` and `COPY <module>/src ...`. Non-module build inputs
        # (`COPY pom.xml ./`, `COPY config ./config`, `COPY core/tools ./core/tools`) do not match.
        *Dockerfile*) named=$(sed -nE 's%^COPY ([A-Za-z0-9._-]+)/(pom\.xml|src)([ /].*)?$%\1%p' "$f") ;;
        # `- ./backend/<module>/src:/app/<module>/src`, i.e. the SHORT bind syntax rule 2b relies
        # on as well. Long syntax would stop matching here too; change both in the same commit.
        *) named=$(sed -nE 's%^ *- \./backend/([A-Za-z0-9._-]+)/(pom\.xml|src)[:/].*%\1%p' "$f") ;;
    esac
    for m in $(echo "$named" | sort -u); do
        case "$module_list" in
            *" $m "*) ;;
            *) echo "ERROR: $f references module '$m', which is not in backend/pom.xml's reactor." >&2
               echo "       A module dropped from the aggregator must be dropped from the three" >&2
               echo "       Docker files in the same change, or the image build fails on a path" >&2
               echo "       that no longer exists." >&2
               fail=1 ;;
        esac
    done
done
# 2b. The overlay is absent from the IMAGES by design (D4), but the dev container must see the
#     same reactor the host does. `/app` is the reactor root inside the container, so the `paid`
#     profile looks for ../tessary-paid/pom.xml = /tessary-paid/pom.xml; without that mount the
#     profile deactivates and an in-container `mvn` silently builds a DIFFERENT module set from the
#     identical command on the host. That divergence is the bug this rule exists to prevent.
#
#     What it deliberately does NOT claim: that the dev backend loads paid beans. It cannot today —
#     the boot is `-pl app -am`, whose closure can never include a paid module, because the enforcer
#     forbids `app` from declaring a dependency on one. Wiring paid code into a running app is the
#     classifier extension registry's job (#847 designs it, #876 implements it), and whatever that
#     lands will need this mount to already be right.
#
#     Since #886 the mounts live in tessary-paid/docker-compose.dev.yml, not the base compose file:
#     an OPEN checkout must name no `./tessary-paid/...` bind source at all, or compose's
#     short-syntax create-host-path behaviour has dockerd manufacture a ghost overlay tree and the
#     `paid` profile — whose `<file><exists>` probe is true for a DIRECTORY — turns back on in a
#     tree with no paid code. So this rule reads the FRAGMENT. Rules 2 and 3 around it still read
#     docker-compose.dev.yml, and must: they assert things about the OPEN modules and the open boot
#     command. Do not substitute the filename wholesale.
PAID_DEV_COMPOSE=tessary-paid/docker-compose.dev.yml
if [ -f tessary-paid/pom.xml ]; then
    # The overlay is present, so its compose fragment must be too. This is the seam the #886 fix
    # creates and it fails closed: Maven probes tessary-paid/pom.xml while compose probes the
    # fragment, so an overlay with no fragment means no mounts, a deactivated in-container profile,
    # and exactly the host/container module-set divergence rule 2b exists to prevent — while every
    # grep below would fail against a missing file with text blaming a missing mount instead.
    if [ ! -f "$PAID_DEV_COMPOSE" ]; then
        echo "ERROR: the paid overlay is present but $PAID_DEV_COMPOSE is missing." >&2
        echo "       That file is what merges the paid module mounts into the dev stack; without" >&2
        echo "       it the 'paid' profile deactivates inside the container and the same mvn" >&2
        echo "       command builds a different module set there than it does on the host." >&2
        fail=1
    else
        grep -q './tessary-paid/pom.xml:/tessary-paid/pom.xml' "$PAID_DEV_COMPOSE" || {
            echo "ERROR: tessary-paid/pom.xml is not mounted into the dev backend container" >&2
            echo "       ($PAID_DEV_COMPOSE)." >&2
            echo "       The 'paid' profile deactivates inside the container, so the same mvn command" >&2
            echo "       builds a different module set there than it does on the host." >&2
            fail=1
        }
        # Same trap as rule 2 above, one directory over: a paid module in the overlay's reactor whose
        # sources are not mounted installs a class-less jar in the container.
        #
        # The trailing colon in "$path:" is load-bearing, and so is the grep above it: both match the
        # SHORT bind syntax `- ./tessary-paid/plan/src:/tessary-paid/plan/src`. Converting those mounts
        # to long syntax (`source: ./tessary-paid/plan/src`) drops the colon, both greps silently stop
        # matching, and this pin passes VACUOUSLY — guarding nothing, while looking green. That is why
        # the fragment's header says the binds stay short syntax on purpose. If you ever need long
        # syntax, change these patterns in the same commit.
        paid_modules=$(sed -n 's|.*<module>\(.*\)</module>.*|\1|p' tessary-paid/pom.xml)
        for m in $paid_modules; do
            for path in "./tessary-paid/$m/pom.xml" "./tessary-paid/$m/src"; do
                grep -q "$path:" "$PAID_DEV_COMPOSE" || {
                    echo "ERROR: paid module '$m' is in the overlay reactor but $path is not mounted" >&2
                    echo "       into the dev backend container ($PAID_DEV_COMPOSE)." >&2
                    fail=1
                }
            done
        done
    fi
fi

# 3. The dev container installs app's dependency chain before running it. `-pl <one-module> -am`
#    was correct when the reactor was three modules and `shared` was the only sibling; with twelve
#    it installs a fraction of the chain and `spring-boot:run` then dies on the first unresolved
#    sibling. The compose command OVERRIDES the Dockerfile CMD, so fixing the image alone is not
#    enough — that is exactly how the dev stack broke.
for f in backend/Dockerfile.dev docker-compose.dev.yml; do
    if grep -q 'install' "$f" && ! grep -q 'pl app -am install' "$f"; then
        echo "ERROR: $f installs the dev dependency chain with something other than" >&2
        echo "       '-pl app -am install'; it will install only part of the reactor" >&2
        fail=1
    fi
done

[ "$fail" = 0 ] && echo "module hygiene: ok"
exit $fail
