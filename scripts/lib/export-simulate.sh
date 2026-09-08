#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# THE faithful open-edition export simulation (#889), extracted from `check-open-boot.sh`, which
# proved this exact recipe correct under #849 (epic-1 issue 13) before this file existed. If you
# need "what would the open export actually look like on disk", this is the one home for that —
# do not re-derive it inline at a new call site.
#
# Usage:
#   d=$(bash scripts/lib/export-simulate.sh)          # mktemp's its own dir, prints it on success
#   bash scripts/lib/export-simulate.sh /existing/dir  # exports into a dir you already own
#   bash scripts/lib/export-simulate.sh --manifest scripts/lib/export-manifest.txt [dir]
#
# In the first two forms: on success, exit 0 and (destination-arg form only) print nothing extra —
# the no-arg form is the one that prints the path, because that's what makes `d=$(...)` work
# standalone. On failure (see below), exit 1 with a message on stderr and print nothing to stdout.
#
# `--manifest <path>` additionally writes the candidate's own `git ls-files` output there, one path
# per line, sorted. A relative path resolves from the repo root, since that is where this script
# cds before it parses anything. `task export:manifest` is the wrapper.
#
# WHAT THE MANIFEST IS EVIDENCE OF (#1293, C6). Three separate boundary claims, discharged by one
# artifact because of HOW this script builds the tree rather than by anything the manifest itself
# asserts:
#   "built from git ls-files"            — the copy loop below reads exactly
#                                          `git ls-files --cached --others --exclude-standard` and
#                                          nothing else. That is why the manifest cannot contain
#                                          .env, .cache/, .crew/, .publish/, node_modules/, build
#                                          output, a worktree directory or .git: gitignored and
#                                          unreferenced material is not enumerable by that command,
#                                          so it is never copied. A `cp -r` or an `rsync` of the
#                                          checkout would carry all of it, which is the whole reason
#                                          neither is used here.
#   "the staged set equals the scanned set" — asserted above the emit, by path, not by count alone.
#   "contains no ignored directory"        — the same property as the first, read the other way.
# So the manifest is a RECORD of a construction, not a filter applied after the fact. Reading it as
# a list to be checked against gets the direction backwards: if something unwanted appears in it,
# the bug is in git's enumeration or in a missing .gitignore rule, not in this file.
#
# WHY THIS EXISTS AND NOT `mv tessary-paid /tmp/`. Several prior epic-1 commits used `mv` as their
# detach proof (e.g. 76f7d6f3's own verification note). It is not a faithful export: #889 found
# that `check-docs-links.sh` enumerates this repo's markdown via `git ls-files --cached`, which
# reads the git INDEX, not the working tree. After a bare `mv`, the index still believes
# `tessary-paid/README.md` exists on disk, so that gate dies with a `FileNotFoundError` before any
# other gate runs. Copying the WORKING TREE (tracked files as they currently sit on disk, plus any
# untracked-but-not-ignored files — i.e. exactly what `git ls-files --cached --others
# --exclude-standard` names) into a scratch directory, deleting `tessary-paid/` from the COPY, then
# a fresh `git init && git add -A` inside it makes the index describe the tree that is ACTUALLY
# there — zero overlay entries, because the overlay was never copied to begin with.
#
# NOT `git archive HEAD`. That was this script's first cut and it is a real bug, not a style
# choice: `git archive HEAD` only ever materializes the last COMMIT, so an uncommitted boundary
# violation (edit a non-boundary_aware script to add a `tessary-paid` reference, or a doc link that
# resolves into `tessary-paid/`, without committing it) is silently absent from the export. A
# developer running the `check.sh` pre-commit recipe against dirty working state — exactly the
# workflow this script exists to serve — would get a false-clean "open boundary: ok" on a real
# violation. Copying from disk instead of from the git object store is what makes the export
# faithful to what is actually about to be committed, staged or not.
#
# WHY IT LIVES UNDER scripts/lib/, NOT scripts/export-simulate.sh. check-open-boundary.sh's rule 5
# fails any `scripts/*.sh` (that glob does not recurse) naming the literal string `tessary-paid`,
# unless it is on the `boundary_aware` allowlist — currently exactly four names: check.sh,
# check-open-boundary.sh, check-module-hygiene.sh, check-open-boot.sh. This file legitimately needs
# to name and strip `tessary-paid/` as its whole job, the same way `scripts/lib/dev-compose.sh`
# legitimately names it to decide whether to merge the overlay's compose fragment (see that file's
# own header). tessary-paid/OPEN-CORE.md's divergence log already recorded that class of problem being settled
# once by MOVING a file out of `scripts/*.sh` rather than growing the allowlist a fourth time — this
# is the same settlement, not a new one. Precedent followed exactly: `scripts/lib/dev-compose.sh` is
# non-executable (mode 644) and always invoked as `bash scripts/lib/dev-compose.sh <args>`, never
# sourced, never `./`-executed. This file matches that shape.
#
# AC4 ENUMERATION (the other git-ls-files-reads-the-index call sites this recipe's callers should
# know about, checked 2026-09-01 against current HEAD):
#   - check-docs-links.sh: REAL hazard, confirmed above — `tracked_files()` reads the index via
#     `--cached` and then OPENS files by that stale path list. Unsafe under `mv`, safe under this
#     script's real export (see check-docs-links.sh's own `tracked is None` branch for the
#     complementary fail-loud-on-genuinely-untethered-export fix, also #889).
#   - check-open-boundary.sh rule 6 (~line 411, `md_targets=$(git ls-files ...)`): SAFE by scope,
#     confirmed by reading it, not by assumption. It excludes tessary-paid/, classifiers/, and
#     tessary-paid/OPEN-CORE.md from its target list BEFORE opening anything — derived from the `delete` rows,
#     so since #1293 that exclusion is the single overlay row and everything it used to name
#     separately is under it — then only greps the *content* of the remaining (non-excluded) files
#     for markup that references those paths. It never opens a path under tessary-paid/ itself, so
#     it behaves identically whether the overlay was `mv`-detached or genuinely never archived.
#   This enumeration is a point-in-time grep result, recorded here as a comment, not a new list
#   file.
#
# THE STRIP IS DERIVED, NOT HARDCODED (epic 4, #1105). Every `delete` row in
# scripts/lib/export-denylist.txt is removed from the COPY; this script names no denied path of its
# own. Three properties the strip phase below guarantees, each of which was once false here:
#   - the declaration is validated and read from the SOURCE root into memory BEFORE the first
#     deletion, so a dead row aborts the run with nothing removed, and the strip can never read its
#     own input out of the copy it is deleting from;
#   - after the strip, no `delete` row matches anything in the export, checked against the fresh
#     index AND the disk, because `git add -A` honours the copied .gitignore files and can silently
#     drop tracked-but-ignored files from the index that are still on disk;
#   - the export's index and its disk describe the same file set, asserted by count and named by
#     path on a mismatch, so the two kinds of consumer (index-walking gates and disk-walking
#     scanners) are measuring one tree.
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
    # A tracked file can be listed by --cached (it's in the index) yet be gone from disk — an
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

# Emitted AFTER both assertions above, never before: a manifest written from a tree that failed the
# survivor check or the disk-vs-index check would be a file that looks like evidence and is not.
if [ -n "$MANIFEST" ]; then
    mkdir -p "$(dirname "$MANIFEST")"
    printf '%s\n' "$_index" > "$MANIFEST"
    echo "export-simulate: manifest written to $MANIFEST ($(printf '%s\n' "$_index" | grep -c . ) files)" >&2
fi

if [ "$_own_tmp" -eq 1 ]; then
    echo "$DEST"
fi
