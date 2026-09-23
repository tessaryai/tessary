#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Every `https://github.com/tessaryai/tessary/blob/main/<path>` link that the app, a skill or the
# docs hand to a person or a coding agent names a file that exists in this tree.
#
# WHY. These links are a public contract. The in-app setup prompts, skills/*/SKILL.md, setup.md,
# instrument.md, README.md and the docs point people and agents at a file on GitHub by path, and
# moving or renaming that file breaks every one of them with a 404 that nothing here would see. The
# groundedness prompts swap `main` for the running version's tag at runtime (atRef in
# frontend/src/views/classifiers/groundedness.ts), but the literal constants in that file carry
# `blob/main`, so the paths checked here are the paths users get.
#
# WHAT IT READS. Link paths only, never the prose around them: this is the one named exception to
# the no-markdown rule in scripts/check.sh's header. A link with a `#anchor` to a Markdown file
# also needs a heading that GitHub would give that anchor, since an agent told to follow
# `...#restart` finds nothing when the heading is renamed. A path that a `delete` row of
# scripts/lib/export-denylist.txt removes fails too: the file is here, but it never reaches GitHub.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SOURCES="frontend/src skills docs classifiers/groundedness/setup setup.md instrument.md README.md"
PREFIX='https://github.com/tessaryai/tessary/blob/main/'

existing=""
for s in $SOURCES; do
  [ -e "$s" ] && existing="$existing $s"
done
if [ -z "$existing" ]; then
  echo "blob-links skipped: none of $SOURCES is in this checkout"
  exit 0
fi

# One `<file>:<path>[#anchor]` per link. A trailing `.` or `,` is sentence punctuation, not path.
# shellcheck disable=SC2086
links="$(grep -rIHoE --exclude-dir=node_modules \
  'https://github\.com/tessaryai/tessary/blob/main/[A-Za-z0-9._/-]+(#[A-Za-z0-9_-]+)?' $existing \
  | sed -E "s|:${PREFIX}|:|; s|[.,]+\$||" | sort -u || true)"

# GitHub's heading anchor: lower case, punctuation other than `-` and `_` dropped, spaces to `-`.
anchors_of() {
  sed -nE 's/^#{1,6}[[:space:]]+(.*[^[:space:]])[[:space:]]*$/\1/p' "$1" \
    | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9 _-]//g; s/ /-/g'
}

# The export's `delete` rows, as `dir <prefix>` or `file <path>`.
deleted="$(awk -F'|' '!/^#/ && $2 == "delete" && ($3 == "dir" || $3 == "file") { print $3, $1 }' \
  scripts/lib/export-denylist.txt 2>/dev/null || true)"
export_deletes() {
  local kind pattern
  while read -r kind pattern; do
    [ -n "$pattern" ] || continue
    case "$kind" in
      dir) case "$1" in "$pattern"/*) return 0 ;; esac ;;
      file) [ "$1" = "$pattern" ] && return 0 ;;
    esac
  done <<EOF
$deleted
EOF
  return 1
}

checked=0
bad=""
while IFS= read -r line; do
  [ -n "$line" ] || continue
  src="${line%%:*}"
  target="${line#*:}"
  path="${target%%#*}"
  anchor=""
  case "$target" in *'#'*) anchor="${target#*#}" ;; esac
  checked=$((checked + 1))

  if export_deletes "$path"; then
    bad="${bad}  $src links $path, which the export deletes
"
    continue
  fi
  if [ ! -f "$path" ]; then
    bad="${bad}  $src links $path, which is not a file in this tree
"
    continue
  fi
  if [ -n "$anchor" ]; then
    case "$path" in
      *.md | *.mdx)
        anchors_of "$path" | grep -qxF "$anchor" \
          || bad="${bad}  $src links $path#$anchor, and $path has no heading with that anchor
"
        ;;
    esac
  fi
done <<EOF
$links
EOF

if [ -n "$bad" ]; then
  {
    echo "check-blob-links: these ${PREFIX}<path> links point at nothing on GitHub."
    echo "  Move the link to the file's new path, or put the file back:"
    printf '%s' "$bad"
  } >&2
  exit 1
fi
echo "ok every blob/main link resolves ($checked links)"
