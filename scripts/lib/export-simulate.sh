#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# The export simulation: copies exactly what `git ls-files --cached --others --exclude-standard` names
# from the working tree (so .env and build output never come along), then checks index against disk.
# d=$(bash scripts/lib/export-simulate.sh)   or   bash scripts/lib/export-simulate.sh /existing/dir
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

# Tracked files as on disk plus untracked-but-not-ignored, so uncommitted edits are exported.
git ls-files -z --cached --others --exclude-standard | while IFS= read -r -d '' f; do
    # A tracked file deleted but not committed is skipped rather than aborting the copy.
    if [ -e "$f" ] || [ -L "$f" ]; then
        mkdir -p "$DEST/$(dirname "$f")"
        # -P so a tracked symlink stays a symlink, as git stores it.
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
