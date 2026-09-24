#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# classifiers/ gate: pytest over classifiers/tests, the open eval harness's own tests.
#
# Two of those files drive the SHIPPING Java detectors through jshell bridges
# (classifiers/tool_error/bridge.jsh, classifiers/metric_drift/bridge.jsh). Nothing compiles those
# snippets, so they drift from the backend silently; this gate is what makes that drift red. The
# bridges read backend/*/target/classes and the Maven-resolved Jackson jars, so in the full run this
# gate comes AFTER `backend`, whose `mvn verify` produces both.
#
# The bridge tests skip themselves when jshell or the compiled classes are missing. A gate that
# passed on skips would assert nothing about the bridges, so the preflight below fails first instead,
# with the bridge's own message saying what to run.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/classifiers"

command -v uv >/dev/null 2>&1 || {
    echo "classifiers: uv is required (https://docs.astral.sh/uv/)" >&2
    exit 1
}

# `quality` carries scikit-learn, cleanlab and statsmodels, which test_agreement.py and test_audit.py
# import; `dev` carries pytest. --frozen: the lock is the dependency set, never re-resolved here.
uv sync --frozen --group dev --extra quality

uv run --frozen python - <<'PY'
import sys

from metric_drift import bridge as metric_drift_bridge
from tool_error import bridge as tool_error_bridge

for name, bridge in (("tool_error", tool_error_bridge), ("metric_drift", metric_drift_bridge)):
    try:
        bridge.classpath()
    except bridge.BridgeUnavailable as e:
        sys.exit(f"classifiers: the {name} bridge cannot start, so its tests would skip:\n{e}")
    if bridge.java_home() is None:
        sys.exit(f"classifiers: no jshell for the {name} bridge; set JAVA_HOME to a JDK 25")
PY

uv run --frozen pytest tests -q -p no:cacheprovider
