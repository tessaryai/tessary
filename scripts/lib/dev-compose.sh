#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The single derivation of the dev stack's `-f` set. Prints the compose command; it does not run
# anything.
#
# Usage: COMPOSE="$(bash scripts/lib/dev-compose.sh [extra -f args ...])"
#
# Every caller builds its compose command through this file rather than hardcoding
# `docker compose -f docker-compose.dev.yml`, so the `-f` set is defined once.
#
# The file paths are relative, so every caller must already be at the repo root.
set -euo pipefail

cmd="docker compose -f docker-compose.dev.yml"

printf '%s' "$cmd${*:+ $*}"
