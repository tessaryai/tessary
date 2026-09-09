#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Nothing the docs do not tell you to set is required to boot. Four
# classes of required input are enumerated out of the published tree, and each member must have
# a working default, a row in docs/self-hosting/setup.mdx's required-variable table, or (class iv
# only) an "optional, leave blank" annotation beside it. No fifth option.
#
#   (i)   every `${VAR:?...}` in docker-compose.yml: compose refuses to render without it
#   (ii)  every `${VAR}` with no `:-` fallback in docker-compose.yml: renders empty and boots wrong
#         (`${PWD}` is the declared exclusion: the shell supplies it, no reader sets it)
#   (iii) every `ARG` without a default in a Dockerfile docker-compose.yml builds
#   (iv)  every uncommented, empty-valued key in .env.example: a reader copying the file sees a
#         blank they may believe they must fill
#
# One more assertion, because (ii) and (iii) are closed with a literal that also lives in
# Taskfile.yml: every PNPM_VERSION default in the compose files and Dockerfiles equals the
# Taskfile's pin, so giving the build a default did not give it a second source.
#
#   bash scripts/check-required-inputs.sh              the tree
#   bash scripts/check-required-inputs.sh --negative   also, in scratch copies: strip PNPM_VERSION's
#                                                      compose fallback (red), strip it but add the
#                                                      setup-page row (green, the row is honored),
#                                                      strip one annotation (red), drift one
#                                                      Dockerfile default (red)
#   --root=<dir>                                       check that tree instead (the negative arm's own use)
# Pure text, no Docker; a RUN|RUN row in scripts/check.sh.
set -euo pipefail
P=check-required-inputs
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
NEGATIVE=0
for arg in "$@"; do
    case "$arg" in
        --negative) NEGATIVE=1 ;;
        --root=*) ROOT="${arg#--root=}" ;;
        *) echo "$P: unknown argument '$arg' (accepts --negative, --root=<dir>)" >&2; exit 2 ;;
    esac
done
cd "$ROOT"
COMPOSE=docker-compose.yml
DEV_COMPOSE=docker-compose.dev.yml
ENV_EXAMPLE=.env.example
DOC=docs/self-hosting/setup.mdx
TASKFILE=Taskfile.yml
ANNOTATION_WINDOW=8
fail=0

# The setup page's required-variable table: rows of a markdown table whose header names "Required".
# Today the page says nothing is required, and that sentence is asserted below when the set is empty.
_doc_rows() { awk -F'|' '/^## /{sec=$0} /^\| `[A-Z_0-9]+`/ && sec ~ /[Rr]equired/ {gsub(/[` ]/,"",$2); print $2}' "$DOC" | grep . | sort -u || true; }
doc_rows="$(_doc_rows)"
_documented() { [ -n "$doc_rows" ] && printf '%s\n' "$doc_rows" | grep -qx "$1"; }

echo "$P: --- (i) \${VAR:?} in $COMPOSE"
# Comments are stripped first: a `${VAR}` quoted in prose is not an interpolation.
_compose_code() { sed -E 's/(^|[[:space:]])#.*$//' "$COMPOSE"; }
req_i="$(_compose_code | grep -oE '\$\{[A-Z_0-9]+:\?[^}]*\}' | sed -E 's/^\$\{([A-Z_0-9]+):.*/\1/' | sort -u || true)"
for v in $req_i; do
    if _documented "$v"; then echo "$P: ok   $v is required and the setup page has its row"; else echo "$P: RED  $v is \${$v:?} in $COMPOSE and has neither a default nor a setup-page row" >&2; fail=1; fi
done
[ -n "$req_i" ] || echo "$P: ok   none"

echo "$P: --- (ii) \${VAR} with no fallback in $COMPOSE (\${PWD} excluded: the shell supplies it)"
# `${VAR:-}` is the compose idiom for an optional value with an empty default, so it is a
# fallback here; a build argument that must not be empty is caught by the parity assertion below,
# which reads the fallback's value.
req_ii="$(_compose_code | grep -oE '\$\{[A-Z_0-9]+\}' | tr -d '${}' | grep -vx PWD | sort -u || true)"
for v in $req_ii; do
    if _documented "$v"; then echo "$P: ok   $v renders empty when unset and the setup page has its row"; else echo "$P: RED  $v is \${$v} with no fallback in $COMPOSE and has no setup-page row" >&2; fail=1; fi
done
[ -n "$req_ii" ] || echo "$P: ok   none"

echo "$P: --- (iii) ARG without a default in the Dockerfiles $COMPOSE builds"
# Both compose shapes: `build: <path>` on one line, and a `build:` block with context/dockerfile.
dockerfiles="$(awk '/^ *build: +[^ ]/{print $2 "/Dockerfile"; next} /^ *build:/{b=1; ctx=""; df=""} b && /^ *context:/{ctx=$2} b && /^ *dockerfile:/{df=$2} b && !/^ *(build:|context:|dockerfile:|args:|#|[A-Z_]+:)/ && !/^ *$/ {if (ctx != "") {print (df != "" ? ctx "/" df : ctx "/Dockerfile")}; b=0}' "$COMPOSE" | sed -E 's#^\./##; s#^\.\/##' | sort -u)"
[ -n "$dockerfiles" ] || { echo "$P: RED  no build: blocks found in $COMPOSE; the class (iii) enumeration is empty by accident" >&2; fail=1; }
for df in $dockerfiles; do
    df="${df#./}"
    [ -f "$df" ] || { echo "$P: RED  $COMPOSE builds $df, which does not exist" >&2; fail=1; continue; }
    df_ok=1
    for a in $(grep -oE '^ARG [A-Z_0-9]+$' "$df" | awk '{print $2}' | sort -u); do
        if _documented "$a"; then echo "$P: ok   $df ARG $a has no default and the setup page has its row"; else echo "$P: RED  $df declares ARG $a with no default and the setup page has no row for it" >&2; fail=1; df_ok=0; fi
    done
    [ "$df_ok" = 1 ] && echo "$P: ok   $df: every ARG has a default or a row"
done

# The annotation is the exact phrase, at the start of a comment line, so "NOT optional" cannot pass.
echo "$P: --- (iv) uncommented empty keys in $ENV_EXAMPLE need an 'Optional, leave blank' annotation within $ANNOTATION_WINDOW lines above, or a row"
while IFS=: read -r ln key; do
    key="${key%=}"
    if _documented "$key"; then echo "$P: ok   $key (row on the setup page)"; continue; fi
    start=$((ln - ANNOTATION_WINDOW)); [ "$start" -ge 1 ] || start=1
    if sed -n "${start},$((ln - 1))p" "$ENV_EXAMPLE" | grep -qiE '^# *optional, leave blank'; then
        echo "$P: ok   $key is annotated optional"
    else
        echo "$P: RED  $ENV_EXAMPLE:$ln $key= is empty and uncommented, with no 'optional' annotation within $ANNOTATION_WINDOW lines and no setup-page row" >&2; fail=1
    fi
done < <(grep -nE '^[A-Z_][A-Z_0-9]*=$' "$ENV_EXAMPLE" || true)

echo "$P: --- the setup page states the required set as it is"
if [ -z "$req_i$req_ii" ] && [ -z "$doc_rows" ]; then
    if grep -q 'There is nothing to configure first' "$DOC"; then
        echo "$P: ok   nothing is required, and the setup page says so in those words"
    else
        echo "$P: RED  nothing is required, but the setup page no longer says 'There is nothing to configure first'" >&2; fail=1
    fi
fi

echo "$P: --- PNPM_VERSION: every default equals the Taskfile's pin"
pin="$(sed -n 's/^  PNPM_VERSION: "\(.*\)"$/\1/p' "$TASKFILE" | head -1)"
[ -n "$pin" ] || { echo "$P: RED  $TASKFILE carries no PNPM_VERSION pin" >&2; fail=1; }
copies=0
while IFS=: read -r f ln val; do
    copies=$((copies + 1))
    if [ "$val" = "$pin" ]; then echo "$P: ok   $f:$ln PNPM_VERSION default $val"; else echo "$P: RED  $f:$ln PNPM_VERSION default '$val' is not the Taskfile's $pin" >&2; fail=1; fi
done < <({ grep -nHoE '\$\{PNPM_VERSION:-[^}]*\}' "$COMPOSE" "$DEV_COMPOSE" | sed -E 's/\$\{PNPM_VERSION:-([^}]*)\}/\1/'; for df in $dockerfiles; do grep -nHoE '^ARG PNPM_VERSION=.*' "${df#./}" | sed -E 's/ARG PNPM_VERSION=//'; done; } || true)
[ "$copies" -ge 3 ] || { echo "$P: RED  only $copies PNPM_VERSION defaults found; the parity assertion has nothing to hold" >&2; fail=1; }

if [ "$NEGATIVE" = 1 ]; then
    echo "$P: --- negative: a stripped default and a stripped annotation must each be red"
    T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
    mkdir -p "$T/docs/self-hosting" "$T/frontend" "$T/sandbox-runner/launcher" "$T/backend" "$T/sandbox-runner/agent-sandbox"
    for f in "$COMPOSE" "$DEV_COMPOSE" "$ENV_EXAMPLE" "$DOC" "$TASKFILE" $dockerfiles; do f="${f#./}"; mkdir -p "$T/$(dirname "$f")"; cp "$f" "$T/$f"; done
    sed -i.bak 's/PNPM_VERSION: \${PNPM_VERSION:-[^}]*}/PNPM_VERSION: ${PNPM_VERSION}/' "$T/$COMPOSE"
    if bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, PNPM_VERSION without a fallback passed; class (ii) is vacuous" >&2; exit 1; fi
    echo "$P: negative ok: PNPM_VERSION stripped of its compose fallback is red"
    printf '\n## Required variables\n\n| Variable | Why |\n| --- | --- |\n| `PNPM_VERSION` | planted by the negative arm |\n' >> "$T/$DOC"
    if ! bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, PNPM_VERSION with no fallback but a setup-page row was red; the doc-row escape is dead" >&2; exit 1; fi
    echo "$P: negative ok: the same stripped fallback with a setup-page row is green, so the row is honored"
    cp "$DOC" "$T/$DOC"; cp "$COMPOSE" "$T/$COMPOSE"
    sed -i.bak 's/^# Optional, leave blank: the open edition signs in.*$/# (annotation removed)/' "$T/$ENV_EXAMPLE"
    if bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, an empty key with its annotation removed passed; class (iv) is vacuous" >&2; exit 1; fi
    echo "$P: negative ok: an empty .env.example key without its annotation is red"
    cp "$ENV_EXAMPLE" "$T/$ENV_EXAMPLE"
    sed -i.bak 's/^ARG PNPM_VERSION=.*$/ARG PNPM_VERSION=0.0.0/' "$T/frontend/Dockerfile"
    if bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, a Dockerfile default that disagrees with the Taskfile passed; the parity assertion is vacuous" >&2; exit 1; fi
    echo "$P: negative ok: a drifted PNPM_VERSION default is red"
fi
[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: ok, every required input has a default, a row, or an optional annotation, and every PNPM_VERSION default is the Taskfile's"
