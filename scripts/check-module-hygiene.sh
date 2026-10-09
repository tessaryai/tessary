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
#    incomplete list fails the image build outright, and it fails at `docker build` time, which
#    `task check` never runs. That is exactly how `Dockerfile.dev` stayed broken while this script
#    passed: it was checking only two of the three files.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0

stray=$(grep -n '<[a-z][a-z0-9.-]*\.version>' backend/*/pom.xml 2>/dev/null || true)
if [ -n "$stray" ]; then
    echo "ERROR: version property declared outside backend/pom.xml — it shadows the parent for that" >&2
    echo "       module alone and the build stays green while it uses a different version:" >&2
    echo "$stray" | sed 's/^/  /' >&2
    fail=1
fi

modules=$(sed -n 's|.*<module>\(.*\)</module>.*|\1|p' backend/pom.xml)
for m in $modules; do
    for f in backend/Dockerfile backend/Dockerfile.dev docker-compose.dev.yml; do
        grep -q "$m/pom.xml" "$f" || { echo "ERROR: module '$m' is in the reactor but not in $f" >&2; fail=1; }
    done
done

# 2a. The converse, and the gap that let a REMOVED module linger. Rule 2 walks the reactor and
#     asserts each module is named in the Docker files; nothing walked the Docker files and asserted
#     each name is still a module. So dropping <module>evaluation</module> from the aggregator left
#     `COPY evaluation/pom.xml` in backend/Dockerfile and backend/Dockerfile.dev, which fails the
#     image build outright with `"/evaluation/pom.xml": not found`, and left docker-compose.dev.yml
#     bind-mounting ./backend/evaluation/{src,pom.xml}, where compose SHORT syntax does not error on
#     a missing source but has dockerd manufacture a ghost host path. Neither failure is visible to
#     `task check`, which never runs `docker build`.
# `$modules` is newline-separated; flatten it to a space-delimited string so the membership
# test below can be a plain substring match (bash 3.2, so no associative arrays).
module_list=" ${modules//$'\n'/ } "
for f in backend/Dockerfile backend/Dockerfile.dev docker-compose.dev.yml; do
    # The s/// delimiter is % throughout, because | is the ERE alternation inside the pattern.
    case "$f" in
        # `COPY <module>/pom.xml ...` and `COPY <module>/src ...`. Non-module build inputs
        # (`COPY pom.xml ./`, `COPY config ./config`, `COPY core/tools ./core/tools`) do not match.
        *Dockerfile*) named=$(sed -nE 's%^COPY ([A-Za-z0-9._-]+)/(pom\.xml|src)([ /].*)?$%\1%p' "$f") ;;
        # `- ./backend/<module>/src:/app/<module>/src`, i.e. the SHORT bind syntax. Long syntax
        # would stop matching here; change this pattern in the same commit.
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
# 3. The dev container installs app's dependency chain before running it. `-pl <one-module> -am`
#    was correct when the reactor was three modules and `shared` was the only sibling; with twelve
#    it installs a fraction of the chain and `spring-boot:run` then dies on the first unresolved
#    sibling. The compose command OVERRIDES the Dockerfile CMD, so fixing the image alone is not
#    enough, which is exactly how the dev stack broke.
for f in backend/Dockerfile.dev docker-compose.dev.yml; do
    if grep -q 'install' "$f" && ! grep -q 'pl app -am install' "$f"; then
        echo "ERROR: $f installs the dev dependency chain with something other than" >&2
        echo "       '-pl app -am install'; it will install only part of the reactor" >&2
        fail=1
    fi
done

[ "$fail" = 0 ] && echo "module hygiene: ok"
exit $fail
