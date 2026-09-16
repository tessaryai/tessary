#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Start the dev stack detached, with no tmux session: `task dev:up`.
#
# The detached counterpart to scripts/dev.sh: same containers and the same questions, no log
# windows and no cheat-sheet, so it works where tmux does not (CI, agents, a plain `ssh`). The
# questions come from scripts/lib/dev-choices.sh and are only asked on a terminal; anywhere else
# the saved answers or the non-interactive defaults apply. It honours TESSARY_SKIP_CLASSIFY=1
# exactly as `task dev:slim` does, sharing the derivation in scripts/lib/dev-services.sh.
#
# With agents=local there is no tmux window to run the host launcher in, so it is started in the
# background instead (.local/launcher.pid, log in .local/launcher.log) and `task dev:stop` stops it.
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
dev_local_agent_preflight
dev_choices_export_sandbox_env

# shellcheck source=lib/dev-services.sh
. "$REPO_ROOT/scripts/lib/dev-services.sh"

# One derivation of the `-f` set (it merges the overlay module's compose fragment when present);
# already at the repo root, which that relative probe needs. The same string must feed
# dev_up_services and the `up` below, see the note in scripts/lib/dev-services.sh about what
# diverging `-f` sets do.
COMPOSE="$(bash "$REPO_ROOT/scripts/lib/dev-compose.sh")"
UP_SERVICES="$(dev_up_services "$COMPOSE")"

# Intentionally unquoted: empty ⇒ all services; otherwise word-splits into the service list.
# shellcheck disable=SC2086
$COMPOSE up -d --build $UP_SERVICES

dev_start_host_launcher
