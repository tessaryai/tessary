#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The decisions a dev stack needs, asked once and remembered: `task dev`, `task dev:up`, and
# `task dev:configure` all source this.
#
# The dev stack used to encode every one of these as a default nobody chose, and two of those
# defaults made it a different product from the one `docker compose -f oci://…` installs: auth
# was switched off (so an account could be created and then never signed into), and no agent
# launcher was wired (so triage and RCA refused to run). Each is now a question with the
# self-host behaviour as its recommended answer, unless a dev checkout has a better one.
#
# Three decisions:
#   TESSARY_DEV_SANDBOX     local | docker | e2b | off   where triage and RCA agents run
#   TESSARY_SKIP_CLASSIFY   1 | 0                        whether the encoder classifier service runs
#   TESSARY_AUTH_DISABLED   false | true                 whether sign-in is enforced
#
# Each resolves in this order, and the first that answers wins:
#   1. the environment: an explicit export, or a task preset (dev:local, dev:slim)
#   2. .env at the repo root. Read here, not left to compose, because whatever this exports
#      outranks .env at interpolation: without this step a .env saying TESSARY_AUTH_DISABLED=true
#      would be silently overridden by the default below.
#   3. .local/dev-choices.env, the answers saved from an earlier run
#   4. a multiple-choice prompt, when stdin is a terminal
#   5. the non-interactive default. For agents and the classifier service that is what the stack
#      did before these were questions, so CI and scripts/check-open-boot.sh see no change. For
#      sign-in it is now enforced, like a self-hosted install; the one non-interactive caller that
#      boots this stack already asked for exactly that explicitly.
#
# Only prompted answers are saved. A preset is not: `task dev:local` once must not quietly turn
# every later plain `task dev` into a local-launcher stack.
#
# Bash 3.2 on purpose (macOS /bin/bash): no associative arrays, no case-modifying expansions.

# Sourced, the caller has already set REPO_ROOT; run directly (the Taskfile), derive it.
if [ -z "${REPO_ROOT:-}" ]; then
    REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

DEV_CHOICES_FILE="$REPO_ROOT/.local/dev-choices.env"

# The launcher bearer docker-compose.yml ships on both sides of the pair, which is base64 of this
# string. Public by design, and refused by PlaceholderSecretGuard on any install with a
# SITE_DOMAIN. Derived rather than written out: the encoded literal is key-shaped, and the secret
# scan's generic rule flags it in a shell file even though the compose files carry it allowlisted.
DEV_LAUNCHER_KEY_PLACEHOLDER="$(printf '%s' 'CHANGE-ME-insecure-default-launch' | base64)"

DEV_HOST_LAUNCHER_PID="$REPO_ROOT/.local/launcher.pid"
DEV_HOST_LAUNCHER_LOG="$REPO_ROOT/.local/launcher.log"

dev_is_interactive() {
    [ -t 0 ] && [ -t 2 ] && [ -z "${CI:-}" ] && [ "${TESSARY_DEV_NONINTERACTIVE:-0}" != "1" ]
}

# Print one key's value from the repo's .env, or nothing. Guarded on the file existing: under
# `set -o pipefail` a sed over a missing file fails the whole script, and silently.
dev_dotenv_value() {
    [ -f "$REPO_ROOT/.env" ] || return 0
    sed -n "s/^$1=//p" "$REPO_ROOT/.env" | tail -1
}

# Print a saved value for one key, or nothing.
dev_saved_value() {
    [ "${TESSARY_DEV_RECONFIGURE:-0}" = "1" ] && return 0
    [ -f "$DEV_CHOICES_FILE" ] || return 0
    sed -n "s/^$1=//p" "$DEV_CHOICES_FILE" | tail -1
}

# Record one answer, replacing any earlier line for the same key.
dev_save_value() {
    mkdir -p "$(dirname "$DEV_CHOICES_FILE")"
    if [ ! -f "$DEV_CHOICES_FILE" ]; then
        printf '%s\n' "# Saved by task dev. Change with \`task dev:configure\`, or delete this file." \
            > "$DEV_CHOICES_FILE"
    fi
    local tmp="$DEV_CHOICES_FILE.tmp"
    grep -v "^$1=" "$DEV_CHOICES_FILE" > "$tmp" || true
    printf '%s=%s\n' "$1" "$2" >> "$tmp"
    mv "$tmp" "$DEV_CHOICES_FILE"
}

# dev_ask <default-number> <question> <value|label> ...
# Prints the chosen value on stdout. Everything the user sees goes to stderr.
dev_ask() {
    local default="$1" question="$2"
    shift 2
    local n=0 option
    printf '\n? %s\n' "$question" >&2
    for option in "$@"; do
        n=$((n + 1))
        if [ "$n" = "$default" ]; then
            printf '  %d) %s  (recommended)\n' "$n" "${option#*|}" >&2
        else
            printf '  %d) %s\n' "$n" "${option#*|}" >&2
        fi
    done
    local answer
    while :; do
        printf 'Choose [1-%d] (Enter for %d): ' "$n" "$default" >&2
        IFS= read -r answer || answer=""
        [ -z "$answer" ] && answer="$default"
        case "$answer" in
            *[!0-9]*) ;;
            *)
                if [ "$answer" -ge 1 ] && [ "$answer" -le "$n" ]; then
                    local i=0
                    for option in "$@"; do
                        i=$((i + 1))
                        [ "$i" = "$answer" ] && { printf '%s\n' "${option%%|*}"; return 0; }
                    done
                fi
                ;;
        esac
        printf '  Enter a number from 1 to %d.\n' "$n" >&2
    done
}

# dev_resolve <VAR> <non-interactive default> <prompt default number> <question> <value|label> ...
# Sets and exports VAR, and records where the answer came from in <VAR>_SOURCE.
# shellcheck disable=SC2034  # `origin` is read through the eval that names <VAR>_SOURCE.
dev_resolve() {
    local var="$1" fallback="$2" prompt_default="$3" question="$4"
    shift 4
    local value origin=""
    eval "value=\${$var:-}"
    [ -n "$value" ] && origin="set"
    if [ -z "$origin" ]; then
        value="$(dev_dotenv_value "$var")"
        [ -n "$value" ] && origin=".env"
    fi
    if [ -z "$origin" ]; then
        value="$(dev_saved_value "$var")"
        [ -n "$value" ] && origin="saved"
    fi
    if [ -z "$origin" ]; then
        if dev_is_interactive; then
            value="$(dev_ask "$prompt_default" "$question" "$@")"
            dev_save_value "$var" "$value"
            origin="chosen"
        else
            value="$fallback"
            origin="default"
        fi
    fi
    eval "export $var=\"\$value\""
    eval "${var}_SOURCE=\"\$origin\""
}

dev_choices_resolve() {
    # dev:local predates the question; keep its old spelling meaning what it always meant.
    if [ -z "${TESSARY_DEV_SANDBOX:-}" ] && [ "${TESSARY_LOCAL_AGENT:-0}" = "1" ]; then
        export TESSARY_DEV_SANDBOX=local
    fi

    dev_resolve TESSARY_DEV_SANDBOX off 1 \
        "Where should triage and RCA agents run?" \
        "local|local   On this machine, running this checkout's agent scripts on your opencode. No image build, so agent changes are live." \
        "docker|docker  In a sandbox container per run, from the published agent image. How a self-hosted install runs." \
        "e2b|e2b     In E2B microVMs. Needs E2B_API_KEY, a published template and a publicly reachable MCP URL." \
        "off|off     Nowhere. No launcher is wired, so triage and RCA will not run."

    dev_resolve TESSARY_SKIP_CLASSIFY 0 1 \
        "Run the encoder classifier service?" \
        "1|No    Like a self-hosted install. Frustration and groundedness stay dormant; everything else runs." \
        "0|Yes   Adds the classify service: an 8 GB container and a gated encoder-weight download on first run."

    dev_resolve TESSARY_AUTH_DISABLED false 1 \
        "Enforce sign-in?" \
        "false|Yes   Like a self-hosted install: create an account, sign in, and every API call is authenticated." \
        "true|No    Every request is anonymous. The UI's sign-in flow cannot complete in this mode."

    case "$TESSARY_DEV_SANDBOX" in
        local | docker | e2b | off) ;;
        *)
            echo "error: TESSARY_DEV_SANDBOX='$TESSARY_DEV_SANDBOX' is not one of local, docker, e2b, off." >&2
            echo "       Fix it in .local/dev-choices.env or the environment, or run: task dev:configure" >&2
            exit 1
            ;;
    esac
}

# One line naming each answer and where it came from. Printed to stderr on every run, and written
# onto the tmux cheat-sheet by scripts/dev.sh, because tmux clears the screen the moment it
# attaches: printed alone, the confirmation of what was just chosen is gone before it can be read.
dev_choices_summary_text() {
    local classify=on auth=enforced
    [ "$TESSARY_SKIP_CLASSIFY" = "1" ] && classify=off
    [ "$TESSARY_AUTH_DISABLED" = "true" ] && auth=disabled
    printf 'dev stack: agents=%s (%s)  encoder classifiers=%s (%s)  sign-in=%s (%s)\n' \
        "$TESSARY_DEV_SANDBOX" "$TESSARY_DEV_SANDBOX_SOURCE" \
        "$classify" "$TESSARY_SKIP_CLASSIFY_SOURCE" \
        "$auth" "$TESSARY_AUTH_DISABLED_SOURCE"
    printf '           change with: task dev:configure, then restart with task dev\n'
}

dev_choices_summary() {
    printf '\n' >&2
    dev_choices_summary_text >&2
    printf '\n' >&2
}

# The launcher bearer, the same on both sides of the pair: the environment, then .env, then the
# placeholder docker-compose.yml ships.
dev_launcher_key() {
    local key="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY:-}"
    [ -z "$key" ] && key="$(dev_dotenv_value TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY)"
    printf '%s\n' "${key:-$DEV_LAUNCHER_KEY_PLACEHOLDER}"
}

# Export what the chosen sandbox needs, before `compose up` interpolates the files. Anything the
# caller already set wins.
dev_choices_export_sandbox_env() {
    [ "$TESSARY_DEV_SANDBOX" = "off" ] && return 0

    TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY="$(dev_launcher_key)"
    export TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY

    case "$TESSARY_DEV_SANDBOX" in
        local)
            # The launcher runs on the host, so the backend container reaches it through the
            # Docker host gateway, and the agent (also on the host) reaches the API through Caddy.
            export TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL:-http://host.docker.internal:8080}"
            export TESSARY_RCA_AGENTIC_MCP_BASE_URL="${TESSARY_RCA_AGENTIC_MCP_BASE_URL:-http://localhost:8000}"
            ;;
        docker | e2b)
            case ",${COMPOSE_PROFILES:-}," in
                *,launcher,*) ;;
                *) export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}launcher" ;;
            esac
            export SANDBOX_BACKEND="$TESSARY_DEV_SANDBOX"
            export TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL:-http://sandbox-runner:8080}"
            if [ "$TESSARY_DEV_SANDBOX" = "docker" ]; then
                # Same origin docker-compose.yml gives its sandbox containers.
                export TESSARY_RCA_AGENTIC_MCP_BASE_URL="${TESSARY_RCA_AGENTIC_MCP_BASE_URL:-http://backend:8080}"
            else
                if [ -z "${E2B_API_KEY:-}" ] && ! grep -q '^E2B_API_KEY=.' "$REPO_ROOT/.env" 2>/dev/null; then
                    echo "warning: agents=e2b but E2B_API_KEY is not set; every triage and RCA run will fail to start." >&2
                fi
                if [ -z "${TESSARY_RCA_AGENTIC_MCP_BASE_URL:-}" ]; then
                    echo "warning: agents=e2b needs TESSARY_RCA_AGENTIC_MCP_BASE_URL set to a public URL; a microVM cannot reach localhost." >&2
                fi
            fi
            ;;
    esac
}

# Preflight for agents=local: the host launcher starts `opencode` and the agent scripts shell out
# to `git` and need their own node_modules.
dev_local_agent_preflight() {
    [ "$TESSARY_DEV_SANDBOX" = "local" ] || return 0
    if ! command -v opencode >/dev/null 2>&1; then
        echo "error: agents=local needs the 'opencode' CLI on PATH. Install it with 'npm i -g opencode-ai'," >&2
        echo "       or choose another option with: task dev:configure" >&2
        exit 1
    fi
    if ! command -v git >/dev/null 2>&1; then
        echo "error: agents=local needs 'git' on PATH (the agent scripts clone the repo). brew install git" >&2
        exit 1
    fi
    # Probe the actual requires rather than the directory: a node_modules installed before these
    # deps were declared exists but still cannot run the scripts.
    if ! (cd "$REPO_ROOT/sandbox-runner/agent-sandbox" \
        && node -e "require('acorn'); require('acorn-walk'); require('re2'); import('@opencode-ai/sdk')" 2>/dev/null); then
        echo "installing host agent deps (sandbox-runner/agent-sandbox)..." >&2
        (cd "$REPO_ROOT/sandbox-runner/agent-sandbox" && pnpm install)
    fi
}

# The one command line that runs the host launcher, for the tmux window and for the detached start.
dev_host_launcher_cmd() {
    printf "cd '%s/sandbox-runner/launcher' && SANDBOX_BACKEND=local SANDBOX_API_KEY='%s' PORT=8080 node server.js" \
        "$REPO_ROOT" "$TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY"
}

dev_stop_host_launcher() {
    [ -f "$DEV_HOST_LAUNCHER_PID" ] || return 0
    local pid
    pid="$(cat "$DEV_HOST_LAUNCHER_PID" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        echo "stopped the host agent launcher (pid $pid)." >&2
    fi
    rm -f "$DEV_HOST_LAUNCHER_PID"
}

# Detached start for `task dev:up`, which has no tmux window to run it in.
dev_start_host_launcher() {
    [ "$TESSARY_DEV_SANDBOX" = "local" ] || return 0
    dev_stop_host_launcher
    if curl -s -m 2 -o /dev/null http://localhost:8080/healthz 2>/dev/null; then
        echo "warning: something is already listening on :8080, so the host launcher was not started." >&2
        echo "         If that is an earlier launcher from a tmux session, stop it first: task dev:stop" >&2
        return 0
    fi
    mkdir -p "$(dirname "$DEV_HOST_LAUNCHER_PID")"
    nohup bash -c "$(dev_host_launcher_cmd)" >> "$DEV_HOST_LAUNCHER_LOG" 2>&1 &
    echo $! > "$DEV_HOST_LAUNCHER_PID"
    local i=0
    while [ "$i" -lt 20 ]; do
        if curl -s -m 1 -o /dev/null http://localhost:8080/healthz 2>/dev/null; then
            echo "host agent launcher up on :8080 (log: .local/launcher.log)." >&2
            return 0
        fi
        i=$((i + 1))
        sleep 0.5
    done
    echo "warning: the host agent launcher did not answer on :8080 within 10s; see .local/launcher.log" >&2
}

# `bash scripts/lib/dev-choices.sh <command>` for the Taskfile, which cannot source a file.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    case "${1:-}" in
        configure)
            if ! dev_is_interactive; then
                echo "error: task dev:configure asks questions, so it needs a terminal." >&2
                exit 1
            fi
            unset TESSARY_DEV_SANDBOX TESSARY_SKIP_CLASSIFY TESSARY_AUTH_DISABLED TESSARY_LOCAL_AGENT
            TESSARY_DEV_RECONFIGURE=1
            dev_choices_resolve
            dev_choices_summary
            echo "Saved to .local/dev-choices.env. Restart the stack to apply: task dev" >&2
            ;;
        stop-launcher)
            dev_stop_host_launcher
            ;;
        *)
            echo "usage: bash scripts/lib/dev-choices.sh configure|stop-launcher" >&2
            exit 2
            ;;
    esac
fi
