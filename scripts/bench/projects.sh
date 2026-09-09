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
B=http://localhost:8000
C="$(mktemp -t tessary-bench-cookies)"
rm -f tokens.txt

EMAIL="multi+$(date +%s)@example.invalid"
PW="multi-$(date +%s)-passphrase"
curl -sS -c "$C" -b "$C" -X POST "$B/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" -o /dev/null
ORG=$(curl -sS -c "$C" -b "$C" "$B/auth/me" | jq -r '.data.orgs[0].slug')

mint() { # slug
  curl -sS -c "$C" -b "$C" -X POST "$B/api/orgs/$ORG/projects/$1/mcp-tokens" \
    -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -d '{"name":"bench"}' | jq -r '.data.plaintext'
}

mint default >> tokens.txt
for i in $(seq 2 "$N"); do
  slug=$(curl -sS -c "$C" -b "$C" -X POST "$B/api/orgs/$ORG/projects" \
    -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -d "{\"name\":\"bench $i\"}" | jq -r '.data.slug')
  mint "$slug" >> tokens.txt
done

echo "org=$ORG  projects=$(wc -l < tokens.txt)"
