#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The single derivation of the dev stack's `-f` set. Prints the compose command; it does not run
# anything.
#
# Usage: COMPOSE="$(bash scripts/lib/dev-compose.sh [extra -f args ...])"
#
# Every caller should build its compose command through this file rather than hardcoding
# `docker compose -f docker-compose.dev.yml`, so an optional second compose fragment is picked up
# consistently everywhere instead of only where someone remembered to add it.
#
# The probe below is relative, so every caller must already be at the repo root before invoking
# this script.
set -euo pipefail

cmd="docker compose -f docker-compose.dev.yml"

# If a second compose fragment exists at this path, merge it in. A file-exists probe rather than
# an env var or a flag: the file's presence or absence is what decides.
if [ -f tessary-paid/docker-compose.dev.yml ]; then
    cmd="$cmd -f tessary-paid/docker-compose.dev.yml"
fi

printf '%s' "$cmd${*:+ $*}"
