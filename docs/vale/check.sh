#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Runs Vale against the docs/ tree using the Tessary style in this folder.
#
# Usage:
#   docs/vale/check.sh              # lint everything under docs/
#   docs/vale/check.sh path/to/file.mdx

set -euo pipefail

cd "$(dirname "$0")"

if ! command -v vale >/dev/null 2>&1; then
  echo "vale is not installed. See https://vale.sh/docs/vale-cli/installation/" >&2
  exit 1
fi

vale --config .vale.ini "${1:-..}"
