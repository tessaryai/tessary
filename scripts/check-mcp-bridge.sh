#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# @tessaryai/mcp gate: the stdio bridge's own node:test suite (packages/mcp/test).
#
# release.yml's publish-mcp-package job runs the same `node --test` before `npm publish`, but that is
# the release, not the PR: without this row a broken bridge is found when it is about to ship to npm,
# or after, rather than on the diff that broke it. Zero runtime dependencies, so nothing to install;
# needs only node, already on PATH for the frontend gate.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/packages/mcp"

node --check bin/tessary-mcp.js
node --test
