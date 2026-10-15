#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# Copies the evals plugin's contract files into contract/ verbatim and records provenance in
# contract/VERSION. Usage: [EVALS_PLUGIN_PATH=/path/to/plugin] scripts/sync-evals-contract.sh
# If the diff is non-empty, ingest/storage likely needs updating (contract/CHANGELOG.md).
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
default_plugin_path="$repo_root/../plugins/plugins/evals"
plugin_path="${EVALS_PLUGIN_PATH:-$default_plugin_path}"

if [[ ! -d "$plugin_path" ]]; then
  echo "error: plugin path not found: $plugin_path" >&2
  echo "set EVALS_PLUGIN_PATH to your local evals plugin checkout" >&2
  exit 1
fi


# The vendored file list is shared with scripts/check-vendored-plugin.sh — see that file for why.
# shellcheck source=lib/vendored-plugin-files.sh
source "$repo_root/scripts/lib/vendored-plugin-files.sh"

dest="$repo_root/contract"
mkdir -p "$dest"

for rel in "${VENDORED_PLUGIN_FILES[@]}"; do
  src="$plugin_path/$rel"
  if [[ ! -f "$src" ]]; then
    echo "warn: $rel missing in plugin; skipping" >&2
    continue
  fi
  cp "$src" "$dest/$(basename "$rel")"
done

# Scoped to this plugin's directory: the plugins repo hosts several, so repo HEAD may be another plugin's.
commit="$(git -C "$plugin_path" log -1 --format='%H' -- . 2>/dev/null || echo unknown)"
# The fallback is bare `unknown`, not `evals-vunknown`: check-contract-consistency.sh guards on
# `^plugin_release: unknown`.
plugin_version="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["version"])' \
  "$plugin_path/.claude-plugin/plugin.json" 2>/dev/null || true)"
if [[ -n "$plugin_version" ]]; then
  release="evals-v$plugin_version"
else
  release="unknown"
fi

schema_version="$(grep -E '^[[:space:]]*version:' "$dest/output_format.md" 2>/dev/null \
  | head -1 | sed -E 's/.*version:[[:space:]]*"?([0-9][^" ]*)"?.*/\1/' || true)"
schema_version="${schema_version:-unknown}"
today="$(date +%Y-%m-%d)"

# Regenerated here because this heredoc rewrites the whole file.
if [[ -f "$dest/LICENSE" ]]; then
  license_name="$(head -5 "$dest/LICENSE" | grep -oE '(MIT|Apache|BSD|ISC|MPL)[A-Za-z0-9. -]*Licen[cs]e' | head -1 || true)"
  license_line="plugin_license: ${license_name:-see contract/LICENSE} — contract/LICENSE is the plugin's own LICENSE copied verbatim from tessaryai/plugins@${commit:-unknown}"
else
  license_line="plugin_license: unknown — contract/LICENSE is not in this checkout"
fi

cat > "$dest/VERSION" <<EOF
plugin_source: tessary/plugins/plugins/evals
plugin_commit: ${commit:-unknown}
plugin_release: ${release:-unknown}
schema_version: $schema_version
vendored_at: $today
$license_line

# Files listed in scripts/lib/vendored-plugin-files.sh are vendored copies from the
# evals plugin. Re-run scripts/sync-evals-contract.sh to refresh; commit the result
# alongside any code changes that adapt the platform to the new contract.
# contract/LICENSE is VENDORED like the rest, not hand-written, and the plugin is MIT — ours, but
# not Apache-2.0. Stamping an SPDX header into grader.schema.json or AUTHORING_CONTRACT.md instead
# would fail twice over: check-vendored-plugin.sh cmp's every vendored file byte-for-byte against
# plugins@main so the header reads as drift, and this script would erase it on the next refresh.
# A LICENSE file beside the copies travels with them and is re-proved on every check.
# validate.py and pipeline_io.py are NOT vendored: they are platform-owned (the plugin
# dropped its validator with synthesis in v0.23.0) and only happen to live here so the
# contract tests and the E2B analyzer bake share one copy.
EOF

echo "synced ${#VENDORED_PLUGIN_FILES[@]} files from $plugin_path → $dest"
echo "review: git diff -- contract/"
