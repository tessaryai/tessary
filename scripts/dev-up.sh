#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# `task dev:up`: the dev stack detached, without tmux (CI, agents, ssh). Choices come from
# scripts/lib/dev-choices.sh. Never the profiling overlay; use `task dev:profiling`.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/dev-choices.sh
. "$REPO_ROOT/scripts/lib/dev-choices.sh"
dev_choices_resolve
dev_choices_summary
dev_choices_export_sandbox_env

# Already at the repo root, which the relative `-f` paths need.
COMPOSE="$(bash "$REPO_ROOT/scripts/lib/dev-compose.sh")"

$COMPOSE up -d --build
