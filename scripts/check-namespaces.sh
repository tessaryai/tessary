#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Namespace ownership recheck (epic 6 clause 7, #1152). Two halves:
#
#   1. Every row of scripts/lib/namespace-inventory.txt resolves, through the platform's own
#      ownership signal, to the account the row names, and where the platform exposes a stable
#      account id the row pins it, so a namespace deleted and re-registered under the same name by
#      someone else is red rather than green. A coordinate that resolves to anyone else, or to
#      nobody, is red: a look-alike or a lapsed namespace is exactly what a self-hoster would
#      `docker pull` before pasting a provider key into it. A private coordinate can only be read
#      with a credential that can see it; otherwise (no `gh auth` or HF_TOKEN, or a token that cannot read
#      the repository) the row is reported UNVERIFIED, never ok.
#   2. Every coordinate the tree itself references, over the whole tree the export would publish
#      (everything scripts/lib/export-denylist.txt deletes is skipped), is in the inventory. A new
#      publish target, or a dangling old path after a rename, reds here before it ships unwatched.
#
#   bash scripts/check-namespaces.sh              both halves
#   bash scripts/check-namespaces.sh --negative   also plant a foreign owner and an un-inventoried
#                                                 coordinate in the tree and require red on each
#
# Needs the network. Run by `task check:namespaces`, quarterly plus on dispatch by
# .github/workflows/namespace-recheck.yml, and before every release by release.yml.
set -euo pipefail
P=check-namespaces
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
INVENTORY="scripts/lib/namespace-inventory.txt"
DENYLIST="scripts/lib/export-denylist.txt"
NEGATIVE=0
for arg in "$@"; do
    case "$arg" in
        --negative) NEGATIVE=1 ;;
        *) echo "$P: unknown argument '$arg' (accepts --negative)" >&2; exit 2 ;;
    esac
done
for tool in curl jq; do command -v "$tool" >/dev/null || { echo "$P: $tool is required" >&2; exit 2; }; done

# _get <body file> <url> [bearer]; prints the http code, 000 when the request never completed.
_get() {
    local code
    if [ -n "${3:-}" ]; then
        code="$(curl -sS --max-time 20 -o "$1" -w '%{http_code}' -H "Authorization: Bearer $3" "$2" 2>/dev/null)" || code=000
    else
        code="$(curl -sS --max-time 20 -o "$1" -w '%{http_code}' "$2" 2>/dev/null)" || code=000
    fi
    printf '%s' "${code:-000}"
}
_gh_ok() { command -v gh >/dev/null && gh auth status >/dev/null 2>&1; }
# _verify <ecosystem> <coordinate> <expected owner> <method>; prints "<code>|<owner seen>|<owner id seen>".
# An empty owner with code UNVERIFIED means the coordinate is private and no credential was available.
_verify() {
    local eco="$1" coord="$2" want="$3" method="$4" body code got id=""
    body="$(mktemp)"
    case "$method" in
        dockerhub-repo)
            code="$(_get "$body" "https://hub.docker.com/v2/repositories/$coord/")"
            got="$(jq -r '.namespace // empty' "$body" 2>/dev/null)" ;;
        dockerhub-org)
            code="$(_get "$body" "https://hub.docker.com/v2/orgs/$coord/")"
            got="$(jq -r 'select(.type=="Organization") | .orgname // empty' "$body" 2>/dev/null)"
            id="$(jq -r '.uuid // empty' "$body" 2>/dev/null)" ;;
        github-org)
            code="$(_get "$body" "https://api.github.com/orgs/$coord")"
            got="$(jq -r 'select(.type=="Organization") | .login // empty' "$body" 2>/dev/null)"
            id="$(jq -r '.id // empty' "$body" 2>/dev/null)" ;;
        github-repo|github-repo-private)
            if _gh_ok; then
                # A workflow token is scoped to the repository running it, so a private repository elsewhere
                # in the organization answers 404 to CI's gh; that is "no credential", not "nobody owns it".
                if gh api "repos/$coord" > "$body" 2>/dev/null; then
                    code="gh api 200"
                    got="$(jq -r '.owner.login // empty' "$body" 2>/dev/null)"
                    id="$(jq -r '.owner.id // empty' "$body" 2>/dev/null)"
                elif [ "$method" = github-repo-private ]; then
                    code=UNVERIFIED; got=""
                else
                    code="gh api $(jq -r '.status // "failed"' "$body" 2>/dev/null)"; got=""
                fi
            elif [ "$method" = github-repo-private ]; then
                code=UNVERIFIED; got=""
            else
                code="$(_get "$body" "https://api.github.com/repos/$coord")"
                got="$(jq -r '.owner.login // empty' "$body" 2>/dev/null)"
                id="$(jq -r '.owner.id // empty' "$body" 2>/dev/null)"
            fi ;;
        ghcr)
            # The namespace on GHCR is the GitHub organization; its ownership and id are the github-org
            # row's. Resolvability of a published tag is check-selfhost-images.sh's assertion.
            code="github-org row"; got="${coord#ghcr.io/}"; got="${got%%/*}" ;;
        hf-org)
            code="$(_get "$body" "https://huggingface.co/api/organizations/$coord/overview")"
            got="$(jq -r '.name // empty' "$body" 2>/dev/null)"
            id="$(jq -r '._id // empty' "$body" 2>/dev/null)" ;;
        hf-model|hf-dataset)
            # Gated and private repositories answer 401 anonymously, and so does a repository that does
            # not exist, so without a token the row is unverified rather than assumed.
            local kind="${method#hf-}s"
            code="$(_get "$body" "https://huggingface.co/api/$kind/$coord" "${HF_TOKEN:-}")"
            case "$code" in
                200) got="$(jq -r '.author // empty' "$body" 2>/dev/null)" ;;
                401|403) if [ -n "${HF_TOKEN:-}" ]; then got=""; else code=UNVERIFIED; got=""; fi ;;
                *) got="" ;;
            esac ;;
        *) rm -f "$body"; echo "$P: unknown verification method '$method' for $eco $coord" >&2; return 2 ;;
    esac
    rm -f "$body"
    printf '%s|%s|%s' "$code" "$got" "$id"
}

fail=0; rows=0; unverified=0
echo "$P: --- 1. every inventory row resolves to its owning account"
while IFS='|' read -r eco coord _refs want method want_id; do
    [ -n "$eco" ] && [ "${eco#\#}" = "$eco" ] || continue
    case "$eco" in ecosystem|recheck) continue ;; esac
    rows=$((rows + 1))
    res="$(_verify "$eco" "$coord" "$want" "$method")"
    code="${res%%|*}"; rest="${res#*|}"; got="${rest%%|*}"; got_id="${rest#*|}"
    if [ "$code" = UNVERIFIED ]; then
        echo "$P: UNVERIFIED $eco $coord (private; no credential in this run, verify with gh auth / HF_TOKEN)"; unverified=$((unverified + 1))
    elif [ "$got" != "$want" ]; then
        echo "$P: RED  $eco $coord -> '${got:-nobody}' (wanted $want; $method, $code)" >&2; fail=1
    elif [ -n "$want_id" ] && [ "$got_id" != "$want_id" ]; then
        echo "$P: RED  $eco $coord -> $want but account id '${got_id:-none}' is not the pinned $want_id: the name was re-registered ($method, $code)" >&2; fail=1
    else
        echo "$P: ok   $eco $coord -> $want${want_id:+ (id $want_id)} ($method, $code)"
    fi
done < "$INVENTORY"
echo "$P: $rows rows: $((rows - unverified)) verified, $unverified unverified"

# Every tessaryai/<name> coordinate in the tree the export publishes, one per line. The walk is the
# tracked and untracked-unignored files (git, or jj in a secondary workspace that has no .git), so
# ignored scratch trees never contribute. Paths the export denylist deletes are skipped: a `dir` row
# as a directory prefix, a `file` row exactly, never a bare string prefix (`.env` must not swallow
# `.env.example`). The instrument and the inventory skip themselves. A trailing dot is sentence
# punctuation, not part of a name, and neither is the `.git` a clone URL ends in — without that
# strip, `git clone https://github.com/tessaryai/<repo>.git` reads as a coordinate no inventory
# row can ever match, which is what it did (unnoticed, on the old name) before #1293.
_tree_files() {
    if git ls-files >/dev/null 2>&1; then git ls-files -z --cached --others --exclude-standard
    elif command -v jj >/dev/null && jj root >/dev/null 2>&1; then jj file list | tr '\n' '\0'
    else find . -type f ! -path './.git/*' ! -path './.jj/*' ! -path '*/node_modules/*' ! -path '*/target/*' -print0
    fi
}
_referenced() {
    local skip_dirs skip_files
    skip_dirs="$(grep -v '^#' "$DENYLIST" | awk -F'|' '$2 == "delete" && $3 == "dir" {print $1 "/"}' | paste -sd ';' -)"
    skip_files="$(grep -v '^#' "$DENYLIST" | awk -F'|' '$2 == "delete" && $3 == "file" {print $1}' | paste -sd ';' -)"
    _tree_files | xargs -0 grep -HInoE '(docker\.io/|ghcr\.io/|huggingface\.co/(datasets/)?|github\.com/)?tessaryai/[A-Za-z0-9._-]+' -- 2>/dev/null \
        | sed -E 's#^\./##' \
        | awk -F: -v dirs="$skip_dirs" -v files="$skip_files" 'BEGIN { nd = split(dirs, d, ";"); nf = split(files, f, ";") }
            { keep = 1
              for (i = 1; i <= nd; i++) if (d[i] != "/" && index($1, d[i]) == 1) keep = 0
              for (i = 1; i <= nf; i++) if (f[i] != "" && $1 == f[i]) keep = 0
              if (keep && $1 != "scripts/check-namespaces.sh" && $1 != "scripts/lib/namespace-inventory.txt") print $3 }' \
        | sed -E 's#^(docker\.io/|huggingface\.co/(datasets/)?|github\.com/)##; s#\.git$##; s#\.$##' | grep . | sort -u
}
_known() { grep -v '^#' "$INVENTORY" | awk -F'|' '$1 != "ecosystem" && $1 != "recheck" {print $2}' | sort -u; }
_missing() { comm -23 <(_referenced | sed -E 's#^ghcr\.io/##' | sort -u) <(_known | sed -E 's#^ghcr\.io/##' | sort -u) || true; }

echo "$P: --- 2. every coordinate the tree references is in the inventory"
missing="$(_missing)"
if [ -n "$missing" ]; then
    echo "$P: RED  coordinates the tree references that the inventory does not list:" >&2
    printf '%s\n' "$missing" | sed "s/^/$P:   /" >&2; fail=1
else
    echo "$P: ok   $(_referenced | grep -c .) referenced coordinates, all inventoried"
fi

if [ "$NEGATIVE" = 1 ]; then
    echo "$P: --- negative: a coordinate we do not own must be red"
    res="$(_verify dockerhub library/nginx tessaryai dockerhub-repo)"; rest="${res#*|}"; got="${rest%%|*}"
    if [ "$got" = tessaryai ]; then echo "$P: FAIL, library/nginx verified as ours; the ownership check is vacuous" >&2; exit 1; fi
    echo "$P: negative ok: library/nginx resolves to '${got:-nobody}', not tessaryai"
    res="$(_verify github tessaryai tessaryai github-org)"; got_id="${res##*|}"
    if [ -z "$got_id" ]; then echo "$P: FAIL, the GitHub organization endpoint exposed no account id; the re-registration check is vacuous" >&2; exit 1; fi
    echo "$P: negative ok: the organization endpoint exposes an account id ($got_id) for the pin to bite on"
    plant="namespace-negative-plant.tmp.md"
    trap 'rm -f "$ROOT/$plant"' EXIT
    printf 'planted by check-namespaces.sh --negative: tessaryai/not-in-inventory.v2 and ghcr.io/tessaryai/not-in-inventory-either.\n' > "$plant"
    planted_missing="$(_missing)"; rm -f "$plant"
    for want in tessaryai/not-in-inventory.v2 tessaryai/not-in-inventory-either; do
        if ! printf '%s\n' "$planted_missing" | grep -qx "$want"; then
            echo "$P: FAIL, a planted un-inventoried coordinate ($want) was not reported missing; the reference check is vacuous" >&2; exit 1
        fi
    done
    echo "$P: negative ok: two planted un-inventoried coordinates (one with a dot, one under ghcr.io, planted untracked at the repository root, so the walk is proven to see untracked files too) are reported missing"
fi
[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: ok, every verified namespace resolves to tessaryai, $unverified unverified, every referenced coordinate is inventoried"
