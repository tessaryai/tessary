#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mint N projects in one org and write one write-scoped token per line to tokens.txt.
#
# The in-process spool partitions by project, so a single-project load test measures a single drainer
# no matter how many are configured. This is what gives the drain something to parallelise.
#
#   bash projects.sh 8
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
N="${1:-8}"
B="http://localhost:${HTTP_PORT:-8000}"
C="$(mktemp "${TMPDIR:-/tmp}/tessary-bench-cookies.XXXXXX")"   # portable: GNU mktemp needs the Xs
rm -f tokens.txt

EMAIL="multi+$(date +%s)@example.invalid"
PW="multi-$(date +%s)-passphrase"
curl -sS -c "$C" -b "$C" -X POST "$B/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" -o /dev/null
ORG=$(curl -sS -c "$C" -b "$C" "$B/auth/me" | jq -r '.data.orgs[0].slug')
if [ -z "$ORG" ] || [ "$ORG" = "null" ]; then
  echo "could not resolve an org from $B/auth/me — is the stack up on HTTP_PORT=${HTTP_PORT:-8000}?" >&2
  exit 1
fi

mint() { # slug -> one plaintext token on stdout, or a hard failure
  local t
  t=$(curl -sS -c "$C" -b "$C" -X POST "$B/api/orgs/$ORG/projects/$1/mcp-tokens" \
    -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -d '{"name":"bench"}' | jq -r '.data.plaintext')
  if [ -z "$t" ] || [ "$t" = "null" ]; then
    echo "minting a token for project '$1' failed" >&2
    exit 1
  fi
  printf '%s\n' "$t"
}

mint default >> tokens.txt
for i in $(seq 2 "$N"); do
  slug=$(curl -sS -c "$C" -b "$C" -X POST "$B/api/orgs/$ORG/projects" \
    -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -d "{\"name\":\"bench $i\"}" | jq -r '.data.slug')
  mint "$slug" >> tokens.txt
done

chmod 600 tokens.txt
echo "org=$ORG  projects=$(wc -l < tokens.txt)"
echo "tokens.txt holds live write-scoped credentials for this instance; it is gitignored, not secret-safe."
