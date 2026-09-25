#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Start the dev stack detached, with no tmux session: `task dev:up`.
#
# The detached counterpart to scripts/dev.sh: same containers and the same questions, no log
# windows and no cheat-sheet, so it works where tmux does not (CI, agents, a plain `ssh`). The
# questions come from scripts/lib/dev-choices.sh and are only asked on a terminal; anywhere else
# the saved answers or the non-interactive defaults apply.
#
# Deliberately NOT the profiling overlay, even under TESSARY_PROFILING=1: the overlay attaches a
# -javaagent to the backend, and a detached `up` with no log window is the last place you want a
# profiler nobody asked for. Use `task dev:profiling` for that.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# shellcheck source=lib/dev-choices.sh
. "$REPO_ROOT/scripts/lib/dev-choices.sh"
dev_choices_resolve
dev_choices_summary
dev_choices_export_sandbox_env

# One derivation of the `-f` set; already at the repo root, which its relative paths need.
COMPOSE="$(bash "$REPO_ROOT/scripts/lib/dev-compose.sh")"

$COMPOSE up -d --build
