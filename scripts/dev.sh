#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Launch the Docker dev stack inside a tmux session with one tab per service.
#
# Layout (4 tabbed windows in the same session):
#   [0] shell      interactive shell with cheat-sheet (you land here)
#   [1] backend    backend logs
#   [2] frontend   frontend logs
#   [3] caddy      caddy logs
#
# Open http://localhost:8000 once Spring Boot has booted (watch window 1
# for "Started TessaryApplication"). The status bar at the bottom of tmux
# shows all four window names; the active one is highlighted.
#
# Session-local no-prefix shortcuts (defined in .tmux.conf at the repo root;
# they only fire inside the tessary-dev session and fall back to normal key
# behavior everywhere else):
#   0 / 1 / 2 / 3   jump to window 0/1/2/3
#   Tab / S-Tab     next / previous window
#   r then f/b/c    restart frontend / backend / caddy
#   C-e             stop the stack (task dev:stop)
# These bare keys shadow literal typing inside this session only — outside
# tmux they work as normal.
#
# Standard tmux moves (prefix is C-b):
#   C-b , rename window   C-b d detach   C-b [ scrollback (q to exit)
#
# Mouse mode is enabled — click a window name in the bottom status bar to
# switch windows, and drag-select to copy to the system clipboard.
#
# Re-attach later:    tmux attach -t tessary-dev
# Stop the stack:     C-e   (or `task dev:stop` from any shell)

set -euo pipefail

SESSION="tessary-dev"
# TESSARY_PROFILING=1 (set by `task dev:profiling`) layers the continuous-profiling
# overlay on top: a local Pyroscope container plus a -javaagent on the backend.
# Unset, the compose invocation is byte-identical to what it has always been, so
# the normal dev loop cannot regress from this.
# Must use the SAME test as the jar fetch below (= "1"), not ${TESSARY_PROFILING:+...}:
# `:+` expands on any non-empty value, so TESSARY_PROFILING=0 would mount the overlay
# (attaching -javaagent) while the fetch guard skipped the download — the backend JVM
# then dies on a missing agent jar.
PROFILING_COMPOSE=""
if [ "${TESSARY_PROFILING:-0}" = "1" ]; then PROFILING_COMPOSE=" -f docker-compose.profiling.yml"; fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# The `-f` set comes from the ONE derivation in scripts/lib/dev-compose.sh, which merges the paid
# overlay's fragment when it exists. Built AFTER the `cd` on purpose, not before: that probe is
# relative, so assigning COMPOSE at the top of this file would run it in the CALLER's cwd and
# `bash <repo>/scripts/dev.sh` from anywhere else would silently boot the paid edition with none of
# its module mounts. The profiling overlay stays last, so it still wins on conflicts.
COMPOSE="$(bash "$REPO_ROOT/scripts/lib/dev-compose.sh")${PROFILING_COMPOSE}"

if ! command -v tmux >/dev/null 2>&1; then
    echo "error: tmux is not installed. brew install tmux" >&2
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is not installed." >&2
    exit 1
fi

# Local agent backend (task dev:local → TESSARY_LOCAL_AGENT=1): run the launcher on the HOST
# (against a locally installed `opencode`) instead of E2B, and auto-point the backend container
# at it. Default OFF — when unset/0 everything below is skipped and plain `task dev` is unchanged.
LOCAL_AGENT="${TESSARY_LOCAL_AGENT:-0}"
if [ "$LOCAL_AGENT" = "1" ]; then
    # (a) Preflight: the host launcher starts `opencode` and shells out to `git`.
    if ! command -v opencode >/dev/null 2>&1; then
        echo "error: dev:local needs the 'opencode' CLI on PATH." >&2
        echo "       install it with 'npm i -g opencode-ai'; it runs on the same Bedrock creds as prod." >&2
        exit 1
    fi
    if ! command -v git >/dev/null 2>&1; then
        echo "error: dev:local needs 'git' on PATH (the analyzer scripts clone the repo). brew install git" >&2
        exit 1
    fi
    # (b) Host deps: the analyzer scripts (codegen) need native re2/acorn from this node_modules.
    # Probe the actual requires rather than the directory: a node_modules installed before these
    # deps were declared in package.json exists but still can't run codegen.
    if ! ( cd "$REPO_ROOT/sandbox-runner/agent-sandbox" \
            && node -e "require('acorn'); require('acorn-walk'); require('re2'); import('@opencode-ai/sdk')" 2>/dev/null ); then
        echo "installing host analyzer deps (sandbox-runner/agent-sandbox)..."
        ( cd "$REPO_ROOT/sandbox-runner/agent-sandbox" && pnpm install )
    fi
    # (c) Auto-wire: export BEFORE `$COMPOSE up` so the backend container's compose-interpolated
    # environment picks these up (they outrank .env). The launcher window below uses the same key.
    export TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL=http://host.docker.internal:8080
    # Key agreement: a container recreated OUTSIDE this wrapper (task rb, plain `docker compose
    # up -d backend`) interpolates the key from .env alone — so when .env declares one, use it as
    # the default here too, or the host launcher and any recreated backend silently disagree
    # (backend gets 401s). 'devkey' remains the last resort when neither the shell nor .env sets it.
    if [ -z "${TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY:-}" ]; then
        TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY="$(sed -n 's/^TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY=//p' "$REPO_ROOT/.env" 2>/dev/null | tail -1)"
    fi
    export TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY:-devkey}"
    # RCA rides the same host launcher (compose defaults its URL/key onto the observer's).
    # The analyzer runs on the HOST here, so the dev API origin is reachable for live MCP.
    export TESSARY_RCA_AGENTIC_MCP_BASE_URL="${TESSARY_RCA_AGENTIC_MCP_BASE_URL:-http://localhost:8000}"
    echo "dev:local — host launcher mode (local opencode, no E2B); backend → $TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL"
fi

# Continuous profiling (TESSARY_PROFILING=1 → task dev:profiling / dev:local:profiling, or set it
# yourself in front of any dev task). The agent jar is fetched HERE rather than in the Taskfile so
# profiling composes with every mode — dev, dev:local, dev:slim — instead of only the one task that
# happened to carry the download step. Idempotent: re-running is a no-op once the jar exists.
#
# Version must match the agent pinned in backend/Dockerfile and the io.pyroscope:agent dependency
# in backend/shared/pom.xml. The labels API is shared static state between the javaagent and the
# app, so a skew makes the tessary_pool label silently vanish with no error.
if [ "${TESSARY_PROFILING:-0}" = "1" ]; then
    PYROSCOPE_AGENT_VERSION="2.8.0"
    PROFILER_DIR="$REPO_ROOT/.local/pyroscope"
    if [ ! -f "$PROFILER_DIR/pyroscope.jar" ]; then
        echo "fetching pyroscope agent v${PYROSCOPE_AGENT_VERSION}..."
        mkdir -p "$PROFILER_DIR"
        # Download to a temp path and move into place only on success, so an interrupted or failed
        # fetch can't leave a truncated jar that the JVM then refuses to load on every later boot.
        if ! curl -fsSL -o "$PROFILER_DIR/pyroscope.jar.tmp" \
            "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_AGENT_VERSION}/pyroscope.jar"; then
            rm -f "$PROFILER_DIR/pyroscope.jar.tmp"
            echo "error: could not download the pyroscope agent (network?). Re-run, or unset TESSARY_PROFILING to start without profiling." >&2
            exit 1
        fi
        mv "$PROFILER_DIR/pyroscope.jar.tmp" "$PROFILER_DIR/pyroscope.jar"
    fi
    # Alloy is opt-in by default (#864, `profiles: ["observability"]` in docker-compose.dev.yml) —
    # profiling needs it up regardless (it's the relay to the local Pyroscope container, see
    # docker-compose.profiling.yml), so an explicit TESSARY_PROFILING=1 forces the profile on. Append
    # rather than overwrite: a caller who already set COMPOSE_PROFILES (e.g. `launcher`) keeps it.
    case ",${COMPOSE_PROFILES:-}," in
        *,observability,*) ;;
        *) export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}observability" ;;
    esac
    # Bringing Alloy up is necessary but not sufficient: the base application.yaml defaults both
    # OTLP export flags to false (#864), so without these the backend never creates an exporter
    # and no spans/logs reach the container we just started. Export rather than overwrite so a
    # caller who explicitly set either flag off keeps that choice.
    export MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED="${MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED:-true}"
    export MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED="${MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED:-true}"
    echo "profiling ON — flame graphs at http://localhost:4040 once the backend has served some traffic."
fi

# Slim mode (task dev:slim → TESSARY_SKIP_CLASSIFY=1). The derivation lives in the shared
# lib so `task dev:up`, which has no tmux session and so no scripts/dev.sh, skips the same
# services rather than carrying a second copy of the list.
# shellcheck source=lib/dev-services.sh
. "$REPO_ROOT/scripts/lib/dev-services.sh"
UP_SERVICES="$(dev_up_services "$COMPOSE")"

echo "starting dev stack (this builds images on first run; subsequent runs hit the cache)..."
# Intentionally unquoted: empty ⇒ all services; otherwise word-splits into the service list.
$COMPOSE up -d --build $UP_SERVICES

# Pre-render the cheat-sheet to a file. We avoid a multi-line heredoc inside
# `tmux send-keys` because it sometimes causes the receiving shell to enter
# heredoc-continuation mode and echo `heredoc>` on every line. A single-line
# `cat $file` is robust regardless of paste timing.
CHEATSHEET="$REPO_ROOT/.cache/dev-cheatsheet.txt"
mkdir -p "$(dirname "$CHEATSHEET")"
cat > "$CHEATSHEET" <<EOF
Stack is up at http://localhost:${HOST_PORT:-8000}

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

# In local-agent mode there's a 5th window running the HOST launcher; note it on the sheet.
if [ "$LOCAL_AGENT" = "1" ]; then
cat >> "$CHEATSHEET" <<EOF

── dev:local (host agent launcher) ──
  4 launcher   runs sandbox-runner/launcher/server.js on the HOST in local mode,
               driving your local \`opencode\` (no E2B). Look for
               "listening on :8080 (backend=local)".
  Backend is auto-pointed at http://host.docker.internal:8080 (key
  '$TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY'), so /analyze, /rca, /synthesize and
  /codegen run your local opencode. Jump to it with no-prefix '4'.
EOF
fi

# Wipe any prior session so windows are fresh.
tmux kill-session -t "$SESSION" 2>/dev/null || true

# Create windows in the order shell → backend → frontend → caddy so they map
# to indices 0/1/2/3. Force base-index 0 on this session so the numbering is
# deterministic regardless of the user's global tmux config (many users set
# base-index 1 in ~/.tmux.conf). Targeting by name is still used everywhere
# else so renames or future re-orderings don't break send-keys calls.
#
# Window 0 — interactive shell with a cheat-sheet (you land here).
tmux new-session -d -s "$SESSION" -n "shell"
tmux set-option        -t "$SESSION" base-index 0
tmux set-window-option -t "$SESSION" pane-base-index 0
tmux send-keys -t "$SESSION:shell" "clear && cat \"$CHEATSHEET\"" C-m

# Window 1 — backend logs.
tmux new-window -t "$SESSION" -n "backend"
tmux send-keys  -t "$SESSION:backend" "$COMPOSE logs -f --no-log-prefix backend" C-m

# Window 2 — frontend logs.
tmux new-window -t "$SESSION" -n "frontend"
tmux send-keys  -t "$SESSION:frontend" "$COMPOSE logs -f --no-log-prefix frontend" C-m

# Window 3 — caddy logs.
tmux new-window -t "$SESSION" -n "caddy"
tmux send-keys  -t "$SESSION:caddy" "$COMPOSE logs -f --no-log-prefix caddy" C-m

# Window 4 — host launcher (local mode only). Runs sandbox-runner/launcher/server.js on the
# HOST so the agentic paths (/analyze, /rca, /synthesize, /codegen) drive your local `opencode`
# instead of E2B. The backend reaches it via host.docker.internal:8080 (wired above).
if [ "$LOCAL_AGENT" = "1" ]; then
    tmux new-window -t "$SESSION" -n "launcher"
    tmux send-keys  -t "$SESSION:launcher" \
        "cd '$REPO_ROOT/sandbox-runner/launcher' && SANDBOX_BACKEND=local SANDBOX_API_KEY='$TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY' PORT=8080 node server.js" C-m
fi

# Session-local no-prefix bindings live in the project's .tmux.conf (repo root):
# bare 0-3 / Tab window jumps, the r-chord restart, C-e stop, and mouse-drag
# clipboard copy. Each is gated by if-shell on session_name so the bare keys
# don't leak into other tmux sessions on the same server.
tmux source-file "$REPO_ROOT/.tmux.conf"

# Land the user on the shell window.
tmux select-window -t "$SESSION:shell"

tmux attach -t "$SESSION"
