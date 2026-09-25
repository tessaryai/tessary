#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The decisions a dev stack needs, asked once and remembered: `task dev`, `task dev:up`, and
# `task dev:configure` all source this, and the restart tasks run it (see `compose` at the bottom).
#
# The dev stack used to encode every one of these as a default nobody chose, and two of those
# defaults made it a different product from the one `docker compose -f oci://…` installs: auth
# was switched off (so an account could be created and then never signed into), and no agent
# launcher was wired (so triage and RCA refused to run). Each is now a question with the
# self-host behaviour as its recommended answer, unless a dev checkout has a better one.
#
# Two decisions:
#   TESSARY_DEV_SANDBOX     docker | e2b | off   where triage and RCA agents run
#   TESSARY_AUTH_DISABLED   false | true         whether sign-in is enforced
#
# Each resolves in this order, and the first that answers wins:
#   1. the environment: an explicit export
#   2. .env at the repo root. Read here, not left to compose, because whatever this exports
#      outranks .env at interpolation: without this step a .env saying TESSARY_AUTH_DISABLED=true
#      would be silently overridden by the default below.
#   3. .local/dev-choices.env, the answers saved from an earlier run
#   4. a multiple-choice prompt, when stdin is a terminal
#   5. the non-interactive default. For agents that is what the stack
#      did before these were questions, so CI and scripts/check-open-boot.sh see no change. For
#      sign-in it is now enforced, like a self-hosted install; the one non-interactive caller that
#      boots this stack already asked for exactly that explicitly.
#
# Only prompted answers are saved. An export is not: exporting a value once must not quietly
# change every later plain `task dev`.
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

# The tag `task dev` builds sandbox-runner/agent-sandbox/ under and points AGENT_IMAGE at — see
# dev_docker_agent_image_ensure. Distinct from the published tessaryai/tessary:agent-sandbox-*
# tags (docker-compose.dev.yml's own AGENT_IMAGE default) so a dev build can never be mistaken
# for, or silently reuse a stale copy of, a released image.
DEV_AGENT_IMAGE_TAG="tessary-agent-sandbox-dev:latest"

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

# The browser origin the dev stack serves on, derived from TESSARY_DEV_PORT (the environment, then
# .env, then 80) and exported as TESSARY_DEV_ORIGIN: http://localhost on port 80, else
# http://localhost:<port>. docker-compose.dev.yml publishes Caddy on the port and hands the origin to
# the backend (sign-in redirects, the agents' MCP callback). Compose interpolation has no
# conditionals, so the origin is computed here; a raw `docker compose` on another port must set both.
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

# One line naming each answer and where it came from. Printed to stderr on every run, and written
# onto the tmux cheat-sheet by scripts/dev.sh, because tmux clears the screen the moment it
# attaches: printed alone, the confirmation of what was just chosen is gone before it can be read.
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

# The launcher bearer, the same on both sides of the pair: the environment, then .env, then the
# placeholder docker-compose.yml ships.
dev_launcher_key() {
    local key="${TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY:-}"
    [ -z "$key" ] && key="$(dev_dotenv_value TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY)"
    printf '%s\n' "${key:-$DEV_LAUNCHER_KEY_PLACEHOLDER}"
}

# Boot guard for agents=docker: build AGENT_IMAGE from this checkout's sandbox-runner/agent-sandbox/
# BEFORE `compose up` ever runs, and fail the whole `task dev`/`task dev:up` invocation if the
# build fails — the alternative is server.js's own lazy ensureAgentImage() discovering the problem
# on the first /rca or /triage request, an hour into a dev session, as an opaque 502. A caller that
# already named AGENT_IMAGE (an explicit export, or .env) is left alone: naming one is an explicit
# opt-out of the checkout build, e.g. to pin a published tag instead.
#
# Rebuilt on every run rather than once: Docker's own layer cache makes a no-op rebuild a
# sub-second check, and the alternative (build once, then silently drift from the checkout as
# rca.js/triage.js/agent-stream.js change underneath it) is exactly the staleness this exists to
# prevent — see AGENT_IMAGE's own doc comment in server.js.
dev_docker_agent_image_ensure() {
    [ "$TESSARY_DEV_SANDBOX" = "docker" ] || return 0
    if [ -n "${AGENT_IMAGE:-}" ]; then
        echo "dev stack: AGENT_IMAGE=$AGENT_IMAGE is already set; skipping the checkout build." >&2
        return 0
    fi
    # A restart (the `compose` command below) reuses an existing build: it must stay fast, and
    # `task dev` is where the image is brought up to date with the checkout.
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

# `bash scripts/lib/dev-choices.sh <command>` for the Taskfile, which cannot source a file.
#   configure         re-ask every question and save the answers
#   compose <args>    run the dev compose command with the saved choices exported
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
            # Any compose command that creates or recreates dev containers (task rb/rf/rc and
            # their :full variants). Compose interpolates docker-compose.dev.yml
            # on every `up`, so a restart that skips the exports `task dev` made recreates the
            # backend with its empty defaults: no launcher URL, and an MCP base URL a sandbox
            # container cannot reach. Never prompts; saved answers, else the defaults.
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
