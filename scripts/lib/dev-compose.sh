#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# THE single derivation of the dev stack's `-f` set — the one place that decides whether the paid
# overlay's compose fragment is merged. Prints the compose command; it does not run anything.
#
# Usage: COMPOSE="$(bash scripts/lib/dev-compose.sh [extra -f args ...])"
#
# WHY ONE HOME. Five call sites carried five copies of `docker compose -f docker-compose.dev.yml`
# (Taskfile's DC_DEV and DC_DEV_ALL, scripts/dev.sh, scripts/dev-up.sh, and the agent loop's
# verify.start_cmd) and a sixth would have diverged in silence. check-open-boundary.sh's rule 7 reds
# a site that names the overlay UNCONDITIONALLY, but it is structurally blind to a site that FORGETS
# to name it — and forgetting is the failure that bites: `{{.DC_DEV}} up --force-recreate backend`
# (Taskfile `rb`/`rbb`) with the base file alone drops all eleven paid module mounts, so
# /tessary-paid/pom.xml is absent in the container, backend/pom.xml's `paid` profile deactivates,
# and the identical mvn command builds a DIFFERENT module set inside the container than on the host.
# The stack still comes up. You find out when a paid bean is missing at runtime.
#
# "One derivation, every caller" is an ASSERTION, not a hope: check-open-boundary.sh's rule 4 reds
# any orchestration file (the Taskfile, ci.yml, the agent-loop config, any scripts/*.sh) that names
# docker-compose.dev.yml in a compose invocation instead of calling this, and reds the removal of
# this file or of its probe. Written that way after the first review of #886 found the property
# resting on nothing: reverting one Taskfile var to the literal left every gate green while
# `task rb` recreated the dev backend with all eleven mounts gone.
#
# WHY IT LIVES UNDER scripts/lib/. check-open-boundary.sh rule 5 (its `boundary_aware=` allowlist
# and the `targets=` glob under it) fails any `scripts/*.sh`
# containing the string `tessary-paid`, with an allowlist of exactly three scripts that MANAGE the
# overlay. dev.sh and dev-up.sh are not on it and must not be added — tessary-paid/OPEN-CORE.md:403 records that
# rule 5 was settled once already by MOVING a script rather than growing that allowlist, and this is
# the same settlement. The rule's target set is `ls scripts/*.sh`, which does not recurse, so this
# file sits legitimately outside it: it is edition-switch WIRING, it never READS an overlay file, and
# it emits the second `-f` only when one exists. If anyone ever widens that glob to `scripts/**/*.sh`,
# allowlist this file — do not delete the probe.
#
# THE PROBE IS RELATIVE, so every caller must already be at the repo root. Task always is; dev.sh
# and dev-up.sh build their COMPOSE only after their `cd "$REPO_ROOT"`. That ordering is the fix,
# not a tidy-up: probed from the caller's cwd, `bash <repo>/scripts/dev.sh` run from anywhere else
# silently drops the fragment and boots the paid edition with no overlay at all.
#
# Not a `check-*.sh`, so scripts/check.sh's gate-manifest completeness assertion (its `_disk=`
# assignment, which globs `scripts/check-*.sh`) does not want a row for it.
#
# Every cross-file pointer in this header names an ANCHOR rather than a line number, deliberately:
# the first draft cited `:259-264` and `:54`/`:65`, and the same commit that wrote those citations
# moved all three.
set -euo pipefail

cmd="docker compose -f docker-compose.dev.yml"

# The open/paid switch, and the whole point of this file. A file-exists probe, never an env var or
# a flag: with the overlay deleted (the public export, decision D2) the fragment is absent, so no
# bind mount names a missing source and dockerd cannot manufacture the ghost `tessary-paid/` tree
# that turned backend/pom.xml's `paid` profile back on in an open checkout (#886). Same activation
# shape as the Taskfile's DC_PROD and as the `paid` Maven profile itself.
if [ -f tessary-paid/docker-compose.dev.yml ]; then
    cmd="$cmd -f tessary-paid/docker-compose.dev.yml"
fi

printf '%s' "$cmd${*:+ $*}"
