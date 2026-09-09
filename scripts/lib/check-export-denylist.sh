#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validates scripts/lib/export-denylist.txt: every row is well-formed, every delete/exempt row
# matches something in the source tree, and the denylist selects exactly root-minus-tessary-paid/.
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

# Set equality, source tree only: A = root-minus-tessary-paid/, B = denylist-filtered tree.
# B is computed the same way export-simulate.sh applies `delete` rows, not by running it.
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
