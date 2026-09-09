#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The faithful export simulation, extracted so "what would the export actually look like on disk"
# has one home; do not re-derive it inline at a new call site.
#
# Usage:
#   d=$(bash scripts/lib/export-simulate.sh)          # mktemp's its own dir, prints it on success
#   bash scripts/lib/export-simulate.sh /existing/dir  # exports into a dir you already own
#   bash scripts/lib/export-simulate.sh --manifest scripts/lib/export-manifest.txt [dir]
#
# In the first two forms: on success, exit 0 and (destination-arg form only) print nothing extra;
# the no-arg form is the one that prints the path, because that's what makes `d=$(...)` work
# standalone. On failure, exit 1 with a message on stderr and print nothing to stdout.
#
# `--manifest <path>` additionally writes the candidate's own `git ls-files` output there, one
# path per line, sorted. A relative path resolves from the repo root, since that is where this
# script cds before it parses anything. `task export:manifest` is the wrapper.
#
# It copies the working tree, not HEAD, and not with `cp -r` or `rsync`: only what
# `git ls-files --cached --others --exclude-standard` names, so gitignored and unreferenced
# material (.env, node_modules/, build output, .git) is never copied. That also makes the export
# faithful to uncommitted changes rather than silently reverting to the last commit.
#
# The strip is derived, not hardcoded: every `delete` row in scripts/lib/export-denylist.txt is
# removed from the copy, validated before the first deletion, and re-checked after the strip
# against both the fresh index and the disk, so the two never disagree about what survived.
#
# This script is on the allowlist that may name the paths it strips, since doing so is its whole
# job; see check-open-boundary.sh for the rule this satisfies.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

DECL="$ROOT/scripts/lib/export-denylist.txt"
bash "$ROOT/scripts/lib/check-export-denylist.sh" >/dev/null || {
    echo "export-simulate: the denylist declaration failed validation; nothing was exported." >&2
    exit 1
}
# Loaded into memory here, before any destination exists, from the SOURCE root.
_delete_rows="$(grep -vE '^[[:space:]]*(#|$)' "$DECL" | awk -F'|' '$2 == "delete" { print $1 "|" $3 }')"
if [ -z "$_delete_rows" ]; then
    echo "export-simulate: the denylist declares no delete rows; refusing to export an unstripped tree." >&2
    exit 1
fi

MANIFEST=""
DEST=""
while [ $# -gt 0 ]; do
    case "$1" in
        --manifest)
            MANIFEST="${2:-}"
            [ -n "$MANIFEST" ] || { echo "export-simulate: --manifest needs a path." >&2; exit 2; }
            shift 2
            ;;
        --manifest=*)
            MANIFEST="${1#--manifest=}"
            [ -n "$MANIFEST" ] || { echo "export-simulate: --manifest needs a path." >&2; exit 2; }
            shift
            ;;
        -*)
            echo "export-simulate: unknown option '$1' (only --manifest <path>)." >&2
            exit 2
            ;;
        *)
            [ -z "$DEST" ] || {
                echo "export-simulate: at most one destination directory, got '$DEST' and '$1'." >&2
                exit 2
            }
            DEST="$1"
            shift
            ;;
    esac
done

_own_tmp=0
if [ -z "$DEST" ]; then
    DEST="$(mktemp -d)"
    _own_tmp=1
fi

# Copy the working tree, not HEAD: tracked files as they sit on disk right now (staged or
# unstaged edits included) plus untracked-but-not-ignored files, exactly the set
# `git ls-files --cached --others --exclude-standard` names. This is what makes the export
# faithful to an uncommitted diff instead of silently reverting to the last commit.
git ls-files -z --cached --others --exclude-standard | while IFS= read -r -d '' f; do
    # A tracked file can be listed by --cached (it's in the index) yet be gone from disk: an
    # uncommitted `rm` or `git rm --cached`-then-delete. That's real dirty state too: the file
    # genuinely isn't there to export, so skip it rather than let a missing-source `cp` abort
    # the whole run.
    if [ -e "$f" ] || [ -L "$f" ]; then
        mkdir -p "$DEST/$(dirname "$f")"
        # -P, not just -p: a tracked symlink must copy AS a symlink (matching what git itself
        # stores, mode 120000), not get dereferenced into a regular file holding the target's
        # content -- exactly the silent-divergence class this script exists to eliminate.
        cp -pP "$f" "$DEST/$f"
    fi
done
while IFS='|' read -r _p _scope; do
    case "$_scope" in
        dir)  rm -rf "$DEST/$_p" ;;
        file) rm -f "$DEST/$_p" ;;
    esac
done <<< "$_delete_rows"
(cd "$DEST" && git init -q && git add -A)

_survivors=""
while IFS='|' read -r _p _scope; do
    if [ -e "$DEST/$_p" ] || [ -L "$DEST/$_p" ]; then
        _survivors="${_survivors}  $_p (on disk)
"
    fi
    if (cd "$DEST" && git ls-files -- "$_p" | grep -q .); then
        _survivors="${_survivors}  $_p (in the index)
"
    fi
done <<< "$_delete_rows"
if [ -n "$_survivors" ]; then
    echo "export-simulate: denied paths survived the strip, a bug in this script:" >&2
    printf '%s' "$_survivors" >&2
    exit 1
fi

_disk="$(cd "$DEST" && find . -type f -not -path './.git/*' | sed 's|^\./||' | sort)"
_index="$(cd "$DEST" && git ls-files | sort)"
if [ "$_disk" != "$_index" ]; then
    echo "export-simulate: the export's disk and its index disagree; these paths are in one and not the other:" >&2
    comm -3 <(printf '%s\n' "$_disk") <(printf '%s\n' "$_index") | sed 's/^/  /' >&2
    echo "export-simulate: a tracked-but-ignored file, or a copied .gitignore, is the usual cause." >&2
    exit 1
fi

# Emitted after both assertions above, never before: a manifest written from a tree that failed the
# survivor check or the disk-vs-index check would be a file that looks like evidence and is not.
if [ -n "$MANIFEST" ]; then
    mkdir -p "$(dirname "$MANIFEST")"
    printf '%s\n' "$_index" > "$MANIFEST"
    echo "export-simulate: manifest written to $MANIFEST ($(printf '%s\n' "$_index" | grep -c . ) files)" >&2
fi

if [ "$_own_tmp" -eq 1 ]; then
    echo "$DEST"
fi
