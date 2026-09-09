# SPDX-License-Identifier: Apache-2.0
# The evals-plugin files this repo vendors — the SINGLE source of truth, sourced by both
# scripts/sync-evals-contract.sh (which copies them in) and scripts/check-vendored-plugin.sh
# (which proves the copies are current).
#
# One list, because two lists silently diverge in the one direction that matters: a file added to the
# sync script but not the freshness check gets vendored and then never checked again, which reads
# exactly like a file that never drifts.
#
# Format: "<path within the plugin dir>". The vendored copy is always its basename under contract/,
# matching what sync-evals-contract.sh writes.
#
# NOT here, deliberately (plugin v0.23.0 — the synthesis strip):
#   validate.py + pipeline_io.py — PLATFORM-OWNED now, at contract/. The plugin stopped carrying
#     a validator when it lost synthesis; the writer (the observer) lives here, so its validator
#     does too. They are still importable side by side by contract/tests and baked into the E2B
#     analyzer template by sandbox-runner/agent-sandbox/build.ts.
#   contract/PLATFORM_HANDOFF.md + contract/pack.schema.json — retired with the features they
#     described (the handoff checklist shipped; packs died with plugin synthesis).
#
# LICENSE is on the list for a different reason than the other four. Those are vendored
# because the platform must agree with the plugin about the contract; LICENSE is vendored because
# the four of them are somebody else's files sitting in our Apache-2.0 tree with no license text of
# their own. Nothing in this repo can check that by inspection: check-license-headers.sh covers
# seven CODE extensions (.java .ts .tsx .js .mjs .py .sh) and these are .json and .md, one of which
# cannot syntactically hold a comment at all. So a header stamped by hand would be silently reverted
# the next time sync-evals-contract.sh ran — it copies over each destination unconditionally.
# Putting the upstream LICENSE on THIS list makes the sync carry it and the freshness check prove it
# is still the current one, which is the only version of the fix that survives the next sync.
# (Upstream is MIT, not Apache-2.0. Do not "correct" that: it is the license the vendored files
# actually travel under, and asserting ours over it would misstate it.)
VENDORED_PLUGIN_FILES=(
  contract/grader.schema.json
  contract/AUTHORING_CONTRACT.md
  CHANGELOG.md
  output_format.md
  LICENSE
)
