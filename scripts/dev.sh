#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The Docker dev stack in a tmux session: windows shell, backend, frontend, caddy. Open
# http://localhost once the backend logs "Started TessaryApplication". Session-local keys are in
# .tmux.conf. Re-attach: tmux attach -t tessary-dev. Stop: C-e or `task dev:stop`.

set -euo pipefail

SESSION="tessary-dev"
# TESSARY_PROFILING=1 adds the profiling overlay. Same `= "1"` test as the jar fetch below: `:+` would
# mount the overlay for TESSARY_PROFILING=0 while the fetch skipped, and the JVM would die on the missing jar.
PROFILING_COMPOSE=""
if [ "${TESSARY_PROFILING:-0}" = "1" ]; then PROFILING_COMPOSE=" -f docker-compose.profiling.yml"; fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# After the `cd`, since the paths are relative. The profiling overlay stays last so it wins.
COMPOSE="$(bash "$REPO_ROOT/scripts/lib/dev-compose.sh")${PROFILING_COMPOSE}"

if ! command -v tmux >/dev/null 2>&1; then
    echo "error: tmux is not installed. brew install tmux" >&2
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is not installed." >&2
    exit 1
fi

# Run from inside the session it replaces, `kill-session` would kill this shell silently; refuse instead.
if [ -n "${TMUX:-}" ] && [ "$(tmux display-message -p '#S' 2>/dev/null)" = "$SESSION" ]; then
    echo "error: you are inside the $SESSION tmux session, which task dev replaces." >&2
    echo "       Detach first with C-b d, then run task dev again." >&2
    exit 1
fi

# Asked once and saved in .local/dev-choices.env; exported before `$COMPOSE up` interpolates.
# shellcheck source=lib/dev-choices.sh
. "$REPO_ROOT/scripts/lib/dev-choices.sh"
dev_choices_resolve
dev_choices_summary
dev_choices_export_sandbox_env

# Fetched here so profiling composes with every dev mode; idempotent. The version must match backend/Dockerfile
# and io.pyroscope:agent in backend/shared/pom.xml, or the tessary_pool label silently vanishes.
if [ "${TESSARY_PROFILING:-0}" = "1" ]; then
    PYROSCOPE_AGENT_VERSION="2.8.0"
    PROFILER_DIR="$REPO_ROOT/.local/pyroscope"
    if [ ! -f "$PROFILER_DIR/pyroscope.jar" ]; then
        echo "fetching pyroscope agent v${PYROSCOPE_AGENT_VERSION}..."
        mkdir -p "$PROFILER_DIR"
        # Temp path then move, so a failed fetch never leaves a truncated jar.
        if ! curl -fsSL -o "$PROFILER_DIR/pyroscope.jar.tmp" \
            "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_AGENT_VERSION}/pyroscope.jar"; then
            rm -f "$PROFILER_DIR/pyroscope.jar.tmp"
            echo "error: could not download the pyroscope agent (network?). Re-run, or unset TESSARY_PROFILING to start without profiling." >&2
            exit 1
        fi
        mv "$PROFILER_DIR/pyroscope.jar.tmp" "$PROFILER_DIR/pyroscope.jar"
    fi
    # Profiling needs Alloy as the relay to Pyroscope, so force the `observability` profile, keeping any others.
    case ",${COMPOSE_PROFILES:-}," in
        *,observability,*) ;;
        *) export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}observability" ;;
    esac
    # application.yaml defaults both OTLP export flags to false; default them on without overriding a caller.
    export MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED="${MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED:-true}"
    export MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED="${MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED:-true}"
    echo "profiling ON — flame graphs at http://localhost:4040 once the backend has served some traffic."
fi

echo "starting dev stack (this builds images on first run; subsequent runs hit the cache)..."
$COMPOSE up -d --build

# A pre-rendered file rather than a heredoc through `tmux send-keys`, which can leave the shell in
# heredoc-continuation mode.
CHEATSHEET="$REPO_ROOT/.cache/dev-cheatsheet.txt"
mkdir -p "$(dirname "$CHEATSHEET")"
cat > "$CHEATSHEET" <<EOF
Stack is up at ${TESSARY_DEV_ORIGIN}

$(dev_choices_summary_text)

Tmux windows — switch with any of:
  • Click the window name in the bottom status bar (mouse mode is on)
  • 0 / 1 / 2 / 3       jump to window (no prefix)
  • Tab / Shift+Tab     next / previous window (no prefix)
  • C-b then 0/1/2/3    (standard prefix fallback)

  0 shell (this one)   1 backend   2 frontend   3 caddy

Restart shortcuts (no prefix):
  r then b                   # restart backend (fast — no rebuild)
  r then f                   # restart frontend
  r then c                   # restart caddy
  …or run the task directly:
  task rb / rf / rc          # restart backend / frontend / caddy
  task rb:full / rf:full     # full image rebuild (deps changed)

Other moves:
  task dev:reload-backend    # recompile Java in place; devtools restarts
  task logs -- <svc>         # tail logs for a single service
  $COMPOSE exec backend bash # shell into backend container
  $COMPOSE ps                # service status

Stop:
  C-e                        # session-local shortcut
  task dev:stop              # from any shell

Detach: C-b d   Re-attach: tmux attach -t $SESSION
EOF

tmux kill-session -t "$SESSION" 2>/dev/null || true

# base-index 0 so indices are deterministic whatever the user's tmux config; windows are targeted by name.
tmux new-session -d -s "$SESSION" -n "shell"
tmux set-option        -t "$SESSION" base-index 0
tmux set-window-option -t "$SESSION" pane-base-index 0
tmux send-keys -t "$SESSION:shell" "clear && cat \"$CHEATSHEET\"" C-m

tmux new-window -t "$SESSION" -n "backend"
tmux send-keys  -t "$SESSION:backend" "$COMPOSE logs -f --no-log-prefix backend" C-m

tmux new-window -t "$SESSION" -n "frontend"
tmux send-keys  -t "$SESSION:frontend" "$COMPOSE logs -f --no-log-prefix frontend" C-m

tmux new-window -t "$SESSION" -n "caddy"
tmux send-keys  -t "$SESSION:caddy" "$COMPOSE logs -f --no-log-prefix caddy" C-m

# Bindings are gated on session_name so bare keys do not leak into other sessions.
tmux source-file "$REPO_ROOT/.tmux.conf"

tmux select-window -t "$SESSION:shell"

tmux attach -t "$SESSION"
