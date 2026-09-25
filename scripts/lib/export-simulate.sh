#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The faithful export simulation, extracted so "what would the export actually look like on disk"
# has one home; do not re-derive it inline at a new call site.
#
# Usage:
#   d=$(bash scripts/lib/export-simulate.sh)          # mktemp's its own dir, prints it on success
#   bash scripts/lib/export-simulate.sh /existing/dir  # exports into a dir you already own
#
# On success, exit 0 and (destination-arg form only) print nothing extra; the no-arg form is the
# one that prints the path, because that's what makes `d=$(...)` work standalone. On failure,
# exit 1 with a message on stderr and print nothing to stdout.
#
# It copies the working tree, not HEAD, and not with `cp -r` or `rsync`: only what
# `git ls-files --cached --others --exclude-standard` names, so gitignored and unreferenced
# material (.env, node_modules/, build output, .git) is never copied. That also makes the export
# faithful to uncommitted changes rather than silently reverting to the last commit.
#
# The fresh index and the disk are checked against each other after the copy, so the two never
# disagree about what was exported.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

DEST=""
while [ $# -gt 0 ]; do
    case "$1" in
        -*)
            echo "export-simulate: unknown option '$1'." >&2
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
(cd "$DEST" && git init -q && git add -A)

_disk="$(cd "$DEST" && find . -type f -not -path './.git/*' | sed 's|^\./||' | sort)"
_index="$(cd "$DEST" && git ls-files | sort)"
if [ "$_disk" != "$_index" ]; then
    echo "export-simulate: the export's disk and its index disagree; these paths are in one and not the other:" >&2
    comm -3 <(printf '%s\n' "$_disk") <(printf '%s\n' "$_index") | sed 's/^/  /' >&2
    echo "export-simulate: a tracked-but-ignored file, or a copied .gitignore, is the usual cause." >&2
    exit 1
fi

if [ "$_own_tmp" -eq 1 ]; then
    echo "$DEST"
fi
