#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validates scripts/lib/export-denylist.txt, the one must-not-publish declaration (epic 4, clause 1).
#
# Always `bash scripts/lib/check-export-denylist.sh`, never sourced. It lives under scripts/lib/
# because boundary rule 5 fails any scripts/*.sh that names the overlay outside a four-name
# allowlist, and this file has to name every denied path by its whole job. Same settlement as
# scripts/lib/export-simulate.sh and scripts/lib/dev-compose.sh: place the file, do not grow the list.
#
# Four jobs, two scopes:
#   everywhere        every row is well-formed (four fields, known kind and scope), and no row
#                     names this file or its declaration: a self-denying denylist deletes the input
#                     the public repo's own rule 6 reads.
#   source tree only  every `delete` and `exempt` row matches at least one path. A row matching
#                     nothing is a dead row, and a scrub that no-ops on a dead row is the
#                     absence-looks-like-compliance failure the epic exists to kill. The probe is
#                     `tessary-paid/pom.xml`, the repo's standing overlay-present test: in the export
#                     candidate and in the public repo every `delete` row matches nothing BY
#                     CONSTRUCTION, so the liveness half prints a named skip there instead.
#   source tree only  SET EQUALITY (#1293): the tree the denylist selects is EXACTLY the tree you get
#                     by deleting tessary-paid/. Before #1293 those were two different sets and only
#                     a reader comparing twenty rows against the checkout could tell; now the overlay
#                     is the boundary and this asserts it in one comparison. It fails naming the
#                     difference, and it fails in BOTH directions: a `delete` row for a public path
#                     (a path outside tessary-paid/ that the export would strip) and a private path
#                     that no row covers are equally wrong. Skipped on the same overlay-absent probe,
#                     where the two sets are trivially equal.
#   `never` rows are declared and validated here and enforced by tessary-paid/scripts/check-scrub.sh
#   over the export candidate (clause 6). #1293 moved that scanner into the overlay: the public repo
#   ships neither it nor the forbidden-strings file it reads, so the `never` rows are declared
#   publicly and enforced privately, which is the only arrangement that does not publish the list of
#   strings we are scanning for.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
DECL="scripts/lib/export-denylist.txt"
P="export-denylist"

[ -f "$DECL" ] || {
    echo "$P: $DECL is missing. The export strip and boundary rules 3 and 6 all derive from it." >&2
    exit 1
}

fail=0
rows=0
while IFS='|' read -r pattern kind scope reason; do
    rows=$((rows + 1))
    if [ -z "$pattern" ] || [ -z "$kind" ] || [ -z "$scope" ] || [ -z "$reason" ]; then
        echo "$P: malformed row (need pattern|kind|scope|reason): $pattern|$kind|$scope|$reason" >&2
        fail=1
        continue
    fi
    case "$kind" in delete|never|exempt) ;; *)
        echo "$P: unknown kind '$kind' on row '$pattern' (delete, never or exempt)" >&2; fail=1 ;;
    esac
    case "$scope" in dir|file|glob) ;; *)
        echo "$P: unknown scope '$scope' on row '$pattern' (dir, file or glob)" >&2; fail=1 ;;
    esac
    case "$pattern" in
        /*|*/) echo "$P: row '$pattern' must be repo-root relative with no leading or trailing slash" >&2; fail=1 ;;
    esac
    case "$pattern" in
        *export-denylist*|*check-export-denylist*)
            echo "$P: row '$pattern' denies the denylist or its loader. Both publish so rule 6 keeps" >&2
            echo "    deriving its exemptions in the public repo. Remove the row." >&2
            fail=1 ;;
    esac
    if [ "$kind" = delete ] && [ "$scope" = glob ]; then
        echo "$P: row '$pattern' is a delete with glob scope; delete rows are dir or file so the export" >&2
        echo "    removes exactly one named thing." >&2
        fail=1
    fi
done < <(grep -vE '^[[:space:]]*(#|$)' "$DECL")

if [ "$rows" -eq 0 ]; then
    echo "$P: $DECL declares no rows." >&2
    fail=1
fi
[ "$fail" = 0 ] || exit 1

if [ ! -f tessary-paid/pom.xml ]; then
    echo "$P: ok ($rows rows well-formed); liveness skipped: no tessary-paid/ overlay in this checkout, so"
    echo "    this is the export candidate or the public repo, where every delete row matches nothing by design"
    exit 0
fi

# Liveness, source tree only. Working-tree-faithful membership, the same set export-simulate.sh copies.
tracked="$(git ls-files --cached --others --exclude-standard)"
while IFS='|' read -r pattern kind scope reason; do
    [ "$kind" = delete ] || [ "$kind" = exempt ] || continue
    case "$scope" in
        dir)  hit="$(printf '%s\n' "$tracked" | grep -c "^$pattern/" || true)" ;;
        file) hit="$(printf '%s\n' "$tracked" | grep -cxF "$pattern" || true)" ;;
        glob) hit=0
              while IFS= read -r f; do
                  # shellcheck disable=SC2254
                  case "$(basename "$f")" in $pattern) hit=1; break ;; esac
              done <<< "$tracked" ;;
    esac
    if [ "${hit:-0}" -eq 0 ]; then
        echo "$P: DEAD ROW: '$pattern' ($kind, $scope) matches nothing in the source tree. Either the path" >&2
        echo "    moved (fix the row) or it is gone (remove the row). A dead row that no-ops is the" >&2
        echo "    absence-looks-like-compliance failure this gate exists to catch." >&2
        fail=1
    fi
done < <(grep -vE '^[[:space:]]*(#|$)' "$DECL")

# Set equality, source tree only, from the SAME listing the liveness half used.
#   A  root-minus-overlay   what the public repo is: every tracked path not under tessary-paid/
#   B  denylist-filtered    what the export produces: every tracked path no `delete` row removes
# export-simulate.sh applies the `delete` rows exactly as the liveness loop above matches them
# (dir => "$pattern/" prefix, file => whole-line), so B is computed the same way rather than by
# running the simulation: this gate has to be cheap enough to sit in front of the expensive one.
_a="$(mktemp)"; _b="$(mktemp)"; _deny="$(mktemp)"
trap 'rm -f "$_a" "$_b" "$_b.all" "$_b.next" "$_deny"' EXIT

printf '%s\n' "$tracked" | grep -v '^tessary-paid/' | LC_ALL=C sort > "$_a"

printf '%s\n' "$tracked" | LC_ALL=C sort > "$_b.all"
grep -vE '^[[:space:]]*(#|$)' "$DECL" | awk -F'|' '$2 == "delete" { print $1 "|" $3 }' > "$_deny"
cp "$_b.all" "$_b"
while IFS='|' read -r _p _scope; do
    case "$_scope" in
        dir)  grep -v "^$_p/" "$_b" > "$_b.next" || true ;;
        file) grep -vxF "$_p" "$_b" > "$_b.next" || true ;;
        *)    cp "$_b" "$_b.next" ;;
    esac
    mv "$_b.next" "$_b"
done < "$_deny"
rm -f "$_b.all"

_only_a="$(comm -23 "$_a" "$_b")"   # public by path, stripped by a delete row
_only_b="$(comm -13 "$_a" "$_b")"   # under the overlay, yet survives the export
if [ -n "$_only_a" ] || [ -n "$_only_b" ]; then
    echo "$P: SET MISMATCH: the denylist-filtered tree and root-minus-tessary-paid select different files." >&2
    if [ -n "$_only_a" ]; then
        echo "    A \`delete\` row strips these, but they are NOT under tessary-paid/ — the export would" >&2
        echo "    remove open code, and no other gate would notice:" >&2
        printf '%s\n' "$_only_a" | sed 's/^/      /' | head -40 >&2
    fi
    if [ -n "$_only_b" ]; then
        echo "    These live under tessary-paid/ yet survive the export — impossible unless the overlay" >&2
        echo "    row was edited. Restore \`tessary-paid|delete|dir\`:" >&2
        printf '%s\n' "$_only_b" | sed 's/^/      /' | head -40 >&2
    fi
    fail=1
fi

if [ "$fail" = 0 ]; then
    echo "$P: ok ($rows rows well-formed, every delete and exempt row resolves,"
    echo "    and the denylist selects exactly root-minus-tessary-paid: $(wc -l < "$_a" | tr -d ' ') files)"
fi
exit $fail
