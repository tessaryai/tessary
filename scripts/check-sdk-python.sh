#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# tessary-prompts (packages/sdk-python) gate: its pytest suite at 100 percent line and branch coverage,
# the OpenTelemetry tests again on the oldest OpenTelemetry SDK the otel extra allows, and a smoke
# install of the built wheel.
# The SDK is the one Tessary client that runs inside a customer's process, so a cache or retry bug
# ships to every user at once; the suite runs against a scripted local http.server, no instance needed.
# Needs uv (check.yml installs it). Locally a missing uv skips with a reason, as scripts/check.sh asks;
# on CI it fails, because there it means the runner is broken.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/packages/sdk-python"
P=check-sdk-python
if ! command -v uv >/dev/null 2>&1; then
  if [ -n "${CI:-}" ]; then echo "$P: uv is missing on CI; check.yml installs it with astral-sh/setup-uv" >&2; exit 1; fi
  echo "$P: SKIPPED, uv is not on PATH (https://docs.astral.sh/uv/); CI runs this gate" >&2
  exit 0
fi
# --locked fails when pyproject.toml changed without `uv lock`, which --frozen would hide.
uv sync --locked --quiet
uv run --no-sync pytest -q -p no:cacheprovider --cov=tessary_prompts --cov-branch --cov-report=term-missing --cov-fail-under=100
# PromptLinkSpanProcessor is duck-typed, so a method one SDK version calls can be missing: run its tests
# again on the oldest OpenTelemetry SDK the otel extra allows, in a venv of its own.
UV_PROJECT_ENVIRONMENT=.venv-otel-oldest uv run --locked --quiet --no-default-groups --group otel-oldest \
  pytest -q -p no:cacheprovider tests/test_otel.py
# Package smoke: build as the release does, install the wheel into a fresh venv and import it there (-I,
# so the source tree is not on the path). A file the build leaves out fails here, not on a user's install.
smoke="$(mktemp -d)"; trap 'rm -rf "$smoke"' EXIT
uv build --quiet --out-dir "$smoke/dist"
uv venv --quiet "$smoke/venv"
wheel="$(ls "$smoke"/dist/*.whl)"
uv pip install --quiet --python "$smoke/venv/bin/python" "${wheel}[otel]"
"$smoke/venv/bin/python" -I -c '
from importlib import resources
import tessary_prompts, tessary_prompts.otel
assert resources.files("tessary_prompts").joinpath("py.typed").is_file(), "py.typed is missing"
assert tessary_prompts.otel.PromptLinkSpanProcessor
'
echo "ok $P: tessary-prompts tests pass at 100 percent line and branch coverage, and the built wheel installs and imports"
