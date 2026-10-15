#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Converts gitleaks' default config (MIT, pinned GITLEAKS_VERSION) to JSON for the redaction corpus,
# merging in scripts/lib/gitleaks-self-minted.toml; re-run after editing that file. Regexes stay RE2
# (GitleaksCorpus translates); path-only rules are dropped. Needs python3 >= 3.11.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
version="${GITLEAKS_VERSION:-v8.30.1}"
dest="$repo_root/backend/substrate/src/main/resources/redaction"
base="https://raw.githubusercontent.com/gitleaks/gitleaks/$version"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -sfL -o "$tmp/gitleaks.toml" "$base/config/gitleaks.toml"
curl -sfL -o "$tmp/LICENSE" "$base/LICENSE"

mkdir -p "$dest"
cp "$tmp/LICENSE" "$dest/gitleaks-LICENSE"

python3 - "$tmp/gitleaks.toml" "$version" "$dest/gitleaks-rules.json" "$repo_root/scripts/lib/gitleaks-self-minted.toml" <<'PY'
import hashlib
import json
import sys
import tomllib

source, version, out, self_minted = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
raw = open(source, "rb").read()
config = tomllib.loads(raw.decode("utf-8"))
minted_raw = open(self_minted, "rb").read()
minted = tomllib.loads(minted_raw.decode("utf-8"))


def allowlist(entry):
    kept = {}
    for key, target in (("regexes", "regexes"), ("stopwords", "stopwords"), ("regexTarget", "regex_target"),
                        ("condition", "condition")):
        if key in entry:
            kept[target] = entry[key]
    return kept


rules = []
seen = set()
for rule in config["rules"] + minted.get("rules", []):
    if "regex" not in rule:
        continue
    if rule["id"] in seen:
        raise SystemExit(f"duplicate rule id {rule['id']}: a self-minted rule may not shadow a stock one")
    seen.add(rule["id"])
    converted = {
        "id": rule["id"],
        "description": rule.get("description", ""),
        "regex": rule["regex"],
        "keywords": rule.get("keywords", []),
    }
    if "entropy" in rule:
        converted["entropy"] = rule["entropy"]
    if "secretGroup" in rule:
        converted["secret_group"] = rule["secretGroup"]
    # gitleaks accepts the older single-table form too, [rules.allowlist], which the self-minted file uses.
    raw_lists = rule.get("allowlists", []) + ([rule["allowlist"]] if "allowlist" in rule else [])
    lists = [allowlist(a) for a in raw_lists]
    lists = [a for a in lists if a.get("regexes") or a.get("stopwords")]
    if lists:
        converted["allowlists"] = lists
    rules.append(converted)

document = {
    "source": f"https://github.com/gitleaks/gitleaks/blob/{version}/config/gitleaks.toml",
    "version": version,
    "sha256": hashlib.sha256(raw).hexdigest(),
    "self_minted_sha256": hashlib.sha256(minted_raw).hexdigest(),
    "allowlist": allowlist(config.get("allowlist", {})),
    "rules": rules,
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(document, f, separators=(",", ":"), ensure_ascii=False)
    f.write("\n")
print(f"wrote {len(rules)} rules (gitleaks {version} plus {len(minted.get('rules', []))} self-minted) to {out}")
PY
