#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# Prints the dev stack's compose command, the one derivation of its `-f` set. Callers must be at the
# repo root. Usage: COMPOSE="$(bash scripts/lib/dev-compose.sh [extra -f args ...])"
set -euo pipefail

cmd="docker compose -f docker-compose.dev.yml"

printf '%s' "$cmd${*:+ $*}"
