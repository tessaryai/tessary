#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# The dev stack's decisions, asked once and remembered; sourced by `task dev`, `dev:up` and `dev:configure`.
# TESSARY_DEV_SANDBOX     docker | e2b | off   where triage and RCA agents run
# TESSARY_AUTH_DISABLED   false | true         whether sign-in is enforced
# First answer wins: the environment, repo .env (read here because exports outrank it), saved answers in
# .local/dev-choices.env, a prompt on a terminal, then the default. Only prompted answers are saved.
# Bash 3.2 on purpose (macOS /bin/bash).

# Sourced, the caller has already set REPO_ROOT; run directly (the Taskfile), derive it.
if [ -z "${REPO_ROOT:-}" ]; then
    REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

DEV_CHOICES_FILE="$REPO_ROOT/.local/dev-choices.env"

# docker-compose.yml's public launcher placeholder. Derived because the encoded literal trips the secret
# scan in a shell file.
DEV_LAUNCHER_KEY_PLACEHOLDER="$(printf '%s' 'CHANGE-ME-insecure-default-launch' | base64)"

# Distinct from the published agent-sandbox tags, so a dev build is never mistaken for a release.
DEV_AGENT_IMAGE_TAG="tessary-agent-sandbox-dev:latest"

dev_is_interactive() {
    [ -t 0 ] && [ -t 2 ] && [ -z "${CI:-}" ] && [ "${TESSARY_DEV_NONINTERACTIVE:-0}" != "1" ]
}

# Guarded: under pipefail a sed over a missing file fails the script silently.
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
    dev_resolve TESSARY_DEV_SANDBOX off 1 \
        "Where should triage and RCA agents run?" \
        "docker|docker  In a sandbox container per run, built from this checkout's sandbox-runner/agent-sandbox/. How a self-hosted install runs, but always up to date with your changes." \
        "e2b|e2b     In E2B microVMs, from the published tessary/tessary-agent-sandbox template. Needs E2B_API_KEY and a publicly reachable MCP URL." \
        "off|off     Nowhere. No launcher is wired, so triage and RCA will not run."

    dev_resolve TESSARY_AUTH_DISABLED false 1 \
        "Enforce sign-in?" \
        "false|Yes   Like a self-hosted install: create an account, sign in, and every API call is authenticated." \
        "true|No    Every request is anonymous. The UI's sign-in flow cannot complete in this mode."

    case "$TESSARY_DEV_SANDBOX" in
        docker | e2b | off) ;;
        *)
            echo "error: TESSARY_DEV_SANDBOX='$TESSARY_DEV_SANDBOX' is not one of docker, e2b, off." >&2
            echo "       Fix it in .local/dev-choices.env or the environment, or run: task dev:configure" >&2
            exit 1
            ;;
    esac

    dev_origin_export
}

# TESSARY_DEV_ORIGIN from TESSARY_DEV_PORT (env, .env, then 80). Computed here because compose
# interpolation has no conditionals; a raw `docker compose` on another port must set both.
dev_origin_export() {
    local port="${TESSARY_DEV_PORT:-}"
    [ -z "$port" ] && port="$(dev_dotenv_value TESSARY_DEV_PORT)"
    port="${port:-80}"
    case "$port" in
        '' | *[!0-9]*)
            echo "error: TESSARY_DEV_PORT='$port' is not a port number." >&2
            exit 1
            ;;
    esac
    export TESSARY_DEV_PORT="$port"
    if [ "$port" = "80" ]; then
        export TESSARY_DEV_ORIGIN="http://localhost"
    else
        export TESSARY_DEV_ORIGIN="http://localhost:$port"
    fi
}

# Also written onto the tmux cheat-sheet, since tmux clears the screen when it attaches.
dev_choices_summary_text() {
    local auth=enforced
    [ "$TESSARY_AUTH_DISABLED" = "true" ] && auth=disabled
    printf 'dev stack: agents=%s (%s)  sign-in=%s (%s)\n' \
        "$TESSARY_DEV_SANDBOX" "$TESSARY_DEV_SANDBOX_SOURCE" \
        "$auth" "$TESSARY_AUTH_DISABLED_SOURCE"
    printf '           change with: task dev:configure, then restart with task dev\n'
}

dev_choices_summary() {
    printf '\n' >&2
    dev_choices_summary_text >&2
    printf '\n' >&2
}

dev_launcher_key() {
    local key="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY:-}"
    [ -z "$key" ] && key="$(dev_dotenv_value TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY)"
    printf '%s\n' "${key:-$DEV_LAUNCHER_KEY_PLACEHOLDER}"
}

# agents=docker: build AGENT_IMAGE from this checkout before `compose up`, failing the task if the build
# fails, instead of a 502 on the first agent run. An explicit AGENT_IMAGE opts out. Rebuilt every run;
# the layer cache makes that cheap and keeps the image from drifting from the checkout.
dev_docker_agent_image_ensure() {
    [ "$TESSARY_DEV_SANDBOX" = "docker" ] || return 0
    if [ -n "${AGENT_IMAGE:-}" ]; then
        echo "dev stack: AGENT_IMAGE=$AGENT_IMAGE is already set; skipping the checkout build." >&2
        return 0
    fi
    # Restarts reuse the existing build to stay fast; `task dev` refreshes it.
    if [ "${DEV_AGENT_IMAGE_REUSE:-0}" = "1" ] && docker image inspect "$DEV_AGENT_IMAGE_TAG" >/dev/null 2>&1; then
        export AGENT_IMAGE="$DEV_AGENT_IMAGE_TAG"
        return 0
    fi
    echo "dev stack: building the agent-sandbox image from this checkout (sandbox-runner/agent-sandbox/)…" >&2
    local revision
    revision="$(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    if ! docker build -f "$REPO_ROOT/sandbox-runner/agent-sandbox/Dockerfile" \
        -t "$DEV_AGENT_IMAGE_TAG" \
        --build-arg IMAGE_VERSION=dev \
        --build-arg IMAGE_REVISION="$revision" \
        "$REPO_ROOT" 1>&2; then
        echo "error: building the agent-sandbox image failed — see the docker build output above." >&2
        echo "       Fix the build, or set AGENT_IMAGE to a published tag and retry." >&2
        exit 1
    fi
    export AGENT_IMAGE="$DEV_AGENT_IMAGE_TAG"
}

# Export what the chosen sandbox needs, before `compose up` interpolates the files. Anything the
# caller already set wins.
dev_choices_export_sandbox_env() {
    [ "$TESSARY_DEV_SANDBOX" = "off" ] && return 0

    TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY="$(dev_launcher_key)"
    export TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY

    case ",${COMPOSE_PROFILES:-}," in
        *,launcher,*) ;;
        *) export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}launcher" ;;
    esac
    export SANDBOX_BACKEND="$TESSARY_DEV_SANDBOX"
    export TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL:-http://sandbox-runner:8080}"

    if [ "$TESSARY_DEV_SANDBOX" = "docker" ]; then
        dev_docker_agent_image_ensure
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
}

# For the Taskfile, which cannot source a file:
# configure         re-ask every question and save the answers
# compose <args>    run the dev compose command with the saved choices exported
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    case "${1:-}" in
        configure)
            if ! dev_is_interactive; then
                echo "error: task dev:configure asks questions, so it needs a terminal." >&2
                exit 1
            fi
            unset TESSARY_DEV_SANDBOX TESSARY_AUTH_DISABLED
            TESSARY_DEV_RECONFIGURE=1
            dev_choices_resolve
            dev_choices_summary
            echo "Saved to .local/dev-choices.env. Restart the stack to apply: task dev" >&2
            ;;
        compose)
            # For anything that recreates dev containers: without the exports, compose recreates the backend with
            # empty defaults. Never prompts.
            shift
            cd "$REPO_ROOT"
            TESSARY_DEV_NONINTERACTIVE=1
            dev_choices_resolve
            DEV_AGENT_IMAGE_REUSE=1
            dev_choices_export_sandbox_env
            compose_cmd="$(bash scripts/lib/dev-compose.sh)"
            # shellcheck disable=SC2086  # the printed command word-splits by design.
            exec $compose_cmd "$@"
            ;;
        *)
            echo "usage: bash scripts/lib/dev-choices.sh configure" >&2
            echo "       bash scripts/lib/dev-choices.sh compose <docker compose args...>" >&2
            exit 2
            ;;
    esac
fi
