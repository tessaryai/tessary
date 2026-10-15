# SPDX-License-Identifier: Apache-2.0
# The vendored evals-plugin files, one list sourced by scripts/sync-evals-contract.sh and
# scripts/check-vendored-plugin.sh. Each lands under contract/ by basename. LICENSE (MIT upstream; do not
# "correct" it) is listed so the sync carries it and the check proves it current, since these files cannot
# carry a header.
VENDORED_PLUGIN_FILES=(
  contract/grader.schema.json
  contract/AUTHORING_CONTRACT.md
  CHANGELOG.md
  output_format.md
  LICENSE
)
