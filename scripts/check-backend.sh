#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Backend gate: compile + tests + static analysis (Spotless / forbidden-apis / SpotBugs / PMD /
# Error Prone / NullAway) in ONE Maven reactor — the static-analysis plugins bind to the `verify`
# phase, which runs after the test phase, so a single `mvn verify` covers tests AND linting.
#
# Single source of truth for the backend gate: invoked by both the Taskfile (`task backend:check`,
# and thus `task check`) and CI (.github/workflows/check.yml, via scripts/check.sh). Keep both callers
# thin wrappers around this script so the local gate and CI can never drift.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# JDK guard: the project targets Java 25 and the static-analysis plugins (notably PMD,
# which runs in the Maven launcher JVM) assume it. Fail loudly here if the active JDK
# isn't major 25, instead of letting it surface as an opaque PMD/compiler crash deep in
# the reactor. Vendor-agnostic on purpose (any Java 25 is fine for `task check` and the
# `task check`); the agent-loop additionally pins Temurin 25 in ~/.config/agent-loop.env.
jmajor="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
if [ "${jmajor:-0}" != 25 ]; then
  echo "check-backend: this project requires Java 25, but the active JDK is '${jmajor:-unknown}'." >&2
  echo "  $(java -version 2>&1 | head -1)" >&2
  echo "  Set JAVA_HOME to a Java 25 JDK (e.g. sdk use java 25.0.3-tem) and retry." >&2
  exit 1
fi

cd "$ROOT/backend"
exec mvn -B verify "$@"
