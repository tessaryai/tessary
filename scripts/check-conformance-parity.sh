#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Conformance parity-fixture freshness gate. Single source of truth: invoked by both the Taskfile
# (`task conformance:parity`) and the CI `conformance-parity` job.
#
# WHY THIS EXISTS. `backend/analysis/src/test/resources/conformance_parity.json` pins the Java
# conformance port to the Python engine (classifiers/experiments/engine).
# ConformanceParityTest reads it on every backend run, but nothing regenerated it. So the fixture
# only ever caught a Java change; a Python change that moved the engine's semantics left the
# fixture pinning the port to an engine that no longer exists, and the suite stayed green while the
# two implementations diverged. This is why the fixture exists at all.
#
# WHAT IT DOES. Reruns the generator in memory and diffs it against the checked-in file, failing
# on any difference. It writes nothing, so a red run leaves the tree untouched and the fix is the
# developer's own regeneration plus whatever the Java tests then report.
#
# The one block that legitimately cannot be regenerated everywhere is `encoder_smoke`: reference
# embeddings from the real GTE checkpoint, which needs torch and a multi-GB download. The
# generator fails soft on it and carries the checked-in block forward unchanged, so this gate is
# honest on a runner with no model: it compares everything that is reproducible, and a machine
# that can produce the block still diffs it.
#
# EDITIONS. The generator (`experiments.engine`) may not be present in every checkout, so a
# checkout without it has nothing to regenerate against and skips whole. Two things this script
# decides for itself rather than having a caller decide for it:
#
#   --edition open   skip, with the reason printed.
#   --edition all    run when the generator is importable; skip, named, when it is not.
#
# The guard is on the right predicate: whether the generator is importable, not whether a
# particular directory exists on disk, since a checkout can carry the classifiers tree without
# carrying the generator. This file names no fixed directory for that reason.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

EDITION=all
while [ $# -gt 0 ]; do
  case "$1" in
    --edition)
      EDITION="${2:-}"
      case "$EDITION" in
        open|all) ;;
        *) echo "check-conformance-parity: --edition takes 'open' or 'all', got '${EDITION:-}'" >&2; exit 1 ;;
      esac
      shift 2
      ;;
    *) echo "check-conformance-parity: unknown argument '$1' (only --edition open|all)" >&2; exit 1 ;;
  esac
done

if [ "$EDITION" = open ]; then
  echo "conformance:parity skipped — open edition (the fixture stays open and three open consumers read it, but its GENERATOR, experiments.engine, is paid since issue 843, so there is nothing here to regenerate against)"
  exit 0
fi

# Kept, but not the edition predicate: classifiers/ can be present without the generator being
# importable, so this only fires in a checkout with no classifier tree at all. The `--edition open`
# skip above is a separate answer for a checkout that never carries the generator; the
# importability probe below is what answers for one that is missing it despite having the tree.
if [ ! -d "$ROOT/classifiers" ]; then
  echo "conformance:parity skipped — no classifiers/ tree in this checkout at all, so there is no uv project to run the generator from"
  exit 0
fi

cd "$ROOT/classifiers"

if ! command -v uv >/dev/null 2>&1; then
  echo "check: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

# Importability, not directory existence: the same probe shape and the same three-way answer as
# check-classifier-parity.sh, for the same reason. A broken uv environment must never read as an
# absent generator and quietly turn a gate into a skip. 0 = present, 3 = absent, anything else is
# a hard failure. Path entries come out of pyproject.toml so this file names no fixed directory
# and stays true if the layout moves.
#
# The same entries are exported as PYTHONPATH for the run below, so the probe and the run resolve
# `experiments.engine` identically. A probe that passes with its own inserted paths but a run that
# resolves the module differently is the worst failure mode: a gate that says the generator is
# present and then dies on ModuleNotFoundError.
_PYPATH="$(uv run --quiet python -c '
import pathlib, tomllib
cfg = tomllib.loads(pathlib.Path("pyproject.toml").read_text())
print(":".join(
    str(pathlib.Path(e).resolve())
    for e in cfg["tool"]["pytest"]["ini_options"].get("pythonpath", [])
    if pathlib.Path(e).is_dir()
))')"

set +e
uv run --quiet python -c '
import importlib.util, pathlib, sys, tomllib
cfg = tomllib.loads(pathlib.Path("pyproject.toml").read_text())
for entry in cfg["tool"]["pytest"]["ini_options"].get("pythonpath", []):
    sys.path.insert(0, str(pathlib.Path(entry).resolve()))
sys.exit(0 if importlib.util.find_spec("experiments.engine") else 3)
'
probe=$?
set -e
case "$probe" in
  0) ;;
  3)
    echo "conformance:parity skipped — experiments.engine is not importable in this checkout (the generator is paid, in the overlay since issue 843)"
    exit 0
    ;;
  *)
    echo "check-conformance-parity: the experiments.engine probe failed with exit $probe, which is" >&2
    echo "  neither present (0) nor absent (3). That is a broken uv environment, not an absent" >&2
    echo "  generator, and treating it as absence would turn this gate into a silent skip." >&2
    exit 1
    ;;
esac

# No extras: the generator is deliberately pure arithmetic (numpy only), which is what makes it
# runnable on a bare CI runner in seconds.
PYTHONPATH="$_PYPATH${PYTHONPATH:+:$PYTHONPATH}" uv run --frozen python -m experiments.engine.build_parity_fixture --check

echo "conformance parity fixture OK"
