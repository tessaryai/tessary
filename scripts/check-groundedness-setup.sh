#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The groundedness setup files agree with the serve.py and the CloudFormation template they install.
#
# WHY. classifiers/groundedness/setup/groundedness-setup-mac.md and groundedness-setup-aws.md tell a
# coding agent to download serve.py (and, on AWS, groundedness-aws.yaml) at the running Tessary's
# tag and to refuse the file unless its SHA-256 equals the line at the top of the MD. Edit serve.py
# or the template without that line and every setup from the next release stops at the checksum.
# A hardcoded `main`, tag or commit in a download URL would install a serve.py that does not match
# the Tessary it serves.
#
# WHAT IS CHECKED, and all of it is machine text in those files rather than prose, which is why
# this is the second named exception to the no-markdown rule in scripts/check.sh's header:
#   a. each MD has exactly one `serve.py SHA-256: <hex>` line, equal to serve.py's checksum
#   b. the AWS MD's `groundedness-aws.yaml SHA-256: <hex>` line equals the template's checksum
#   c. every raw.githubusercontent.com/tessaryai/tessary URL in the MDs is at `<ref>`, and the file
#      it names exists in this tree
#   d. `cfn-lint` passes on the template (through uvx)
#   e. the template's ServePyUrl and ServePySha256 parameters have no default, and its user data
#      checks the download against ServePySha256, with a check that can fail, before anything runs
#      serve.py
#   f. each MD has a `## Restart` heading, which the in-app restart prompt links as `#restart`
# The request and response fixtures in classifiers/groundedness/contract/ have one copy, which the
# backend's test and serve.py's test both read, so there is nothing to compare there.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SERVE=classifiers/groundedness/serve.py
SETUP=classifiers/groundedness/setup
MAC_MD="$SETUP/groundedness-setup-mac.md"
AWS_MD="$SETUP/groundedness-setup-aws.md"
TEMPLATE="$SETUP/groundedness-aws.yaml"

for f in "$SERVE" "$MAC_MD" "$AWS_MD" "$TEMPLATE"; do
  if [ ! -f "$f" ]; then
    echo "check-groundedness-setup: $f is missing; the setup files and serve.py move together" >&2
    exit 1
  fi
done

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

bad=""
fail() {
  bad="${bad}  $1
"
}

# ---- a, b: the checksum lines ----
# $1 the MD, $2 the file name on the line, $3 the file in the tree
check_sum() {
  local md="$1" name="$2" file="$3" want lines count got
  want="$(sha256_of "$file")"
  lines="$(grep -E "$name SHA-256: [0-9a-f]+" "$md" || true)"
  count="$(printf '%s' "$lines" | grep -c . || true)"
  if [ "$count" != 1 ]; then
    fail "$md: needs exactly one '$name SHA-256: <hex>' line, found $count. Add: \`$name SHA-256: $want\`"
    return
  fi
  got="$(printf '%s\n' "$lines" | sed -E "s/.*$name SHA-256: ([0-9a-f]+).*/\\1/")"
  if [ "$got" != "$want" ]; then
    fail "$md: '$name SHA-256' is $got but $file is $want. Update that line to $want in the same commit as the $name change"
  fi
}
check_sum "$MAC_MD" serve.py "$SERVE"
check_sum "$AWS_MD" serve.py "$SERVE"
check_sum "$AWS_MD" groundedness-aws.yaml "$TEMPLATE"

# ---- c: every download is at <ref>, and names a file that exists ----
for md in "$MAC_MD" "$AWS_MD"; do
  urls="$(grep -oE 'https://raw\.githubusercontent\.com/tessaryai/tessary/[^[:space:]`)"]+' "$md" || true)"
  if [ -z "$urls" ]; then
    fail "$md: downloads nothing from raw.githubusercontent.com/tessaryai/tessary/<ref>/; the setup must fetch serve.py at <ref>"
    continue
  fi
  while IFS= read -r url; do
    rest="${url#https://raw.githubusercontent.com/tessaryai/tessary/}"
    case "$rest" in
      '<ref>/'*)
        path="${rest#<ref>/}"
        [ -f "$path" ] || fail "$md: downloads $path, which is not a file in this tree"
        ;;
      *)
        fail "$md: $url is pinned to '${rest%%/*}'; build it from <ref> so it matches the running Tessary"
        ;;
    esac
  done <<EOF
$urls
EOF
done

# ---- e: the template's two parameters and its checksum gate ----
# The lines of one top-level parameter's block, from `  <name>:` to the next key at that indent.
param_block() {
  awk -v name="$2" '
    /^[^[:space:]#]/ { in_params = ($0 ~ /^Parameters:/) ; inside = 0; next }
    in_params && /^  [A-Za-z0-9]+:/ { inside = ($0 ~ "^  " name ":") ; if (inside) found = 1; next }
    in_params && inside { print }
    END { exit !found }
  ' "$1"
}
for p in ServePyUrl ServePySha256; do
  if ! block="$(param_block "$TEMPLATE" "$p")"; then
    fail "$TEMPLATE: declares no $p parameter; groundedness-setup-aws.md passes it"
  elif printf '%s\n' "$block" | grep -qE '^[[:space:]]+Default:'; then
    fail "$TEMPLATE: $p has a Default; it must come from groundedness-setup-aws.md so the stack runs the serve.py that MD verified"
  fi
done
# The first line that checks a file against ${ServePySha256} and ends at `sha256sum --check`, so a
# mismatch fails the script: not `--status` (the "is it already here" probe) and not `|| true`.
# Then the first line that runs serve.py. The check must come first.
verify_line="$(grep -nE '\$\{ServePySha256\}.*sha256sum --check[[:space:]]*$' "$TEMPLATE" | head -1 | cut -d: -f1 || true)"
download_line="$(grep -nE '\$\{ServePyUrl\}' "$TEMPLATE" | grep -v '^[0-9]*:[[:space:]]*#' | head -1 | cut -d: -f1 || true)"
run_line="$(grep -nE 'uv run [^[:space:]]*serve\.py' "$TEMPLATE" | head -1 | cut -d: -f1 || true)"
if [ -z "$download_line" ]; then
  fail "$TEMPLATE: the user data never downloads \${ServePyUrl}"
fi
if [ -z "$verify_line" ]; then
  fail "$TEMPLATE: the user data never checks serve.py with 'sha256sum --check' against \${ServePySha256}"
elif [ -z "$run_line" ]; then
  fail "$TEMPLATE: nothing runs serve.py with 'uv run'"
elif [ -n "$download_line" ] && { [ "$verify_line" -lt "$download_line" ] || [ "$verify_line" -gt "$run_line" ]; }; then
  fail "$TEMPLATE: the checksum check (line $verify_line) must sit between the download (line $download_line) and the 'uv run' of serve.py (line $run_line)"
fi

# ---- f: the restart anchor ----
for md in "$MAC_MD" "$AWS_MD"; do
  grep -qxE '## Restart[[:space:]]*' "$md" \
    || fail "$md: has no '## Restart' heading; the in-app restart prompt links it as #restart"
done

if [ -n "$bad" ]; then
  {
    echo "check-groundedness-setup: the setup files no longer match what they install:"
    printf '%s' "$bad"
  } >&2
  exit 1
fi

# ---- d: cfn-lint, last because it is the only step that needs a tool download ----
if ! command -v uvx >/dev/null 2>&1; then
  echo "check-groundedness-setup: needs uv (https://docs.astral.sh/uv/) to run cfn-lint on $TEMPLATE" >&2
  exit 1
fi
uvx --quiet --from 'cfn-lint>=1,<2' cfn-lint "$TEMPLATE"

echo "ok groundedness setup files match serve.py and the template, and the template lints"
