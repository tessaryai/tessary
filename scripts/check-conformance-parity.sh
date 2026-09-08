#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Conformance parity-fixture freshness gate. Single source of truth: invoked by both the Taskfile
# (`task conformance:parity`) and the CI `conformance-parity` job.
#
# WHY THIS EXISTS. `backend/analysis/src/test/resources/conformance_parity.json` is what pins the
# Java conformance port (#841 took the port into the paid overlay; the fixture stayed OPEN, because
# classify-service's `/embed` tests and `check-classify-service.sh` read it too and the public export
# deletes the overlay) to the Python engine
# (classifiers/experiments/engine). ConformanceParityTest reads it on every backend run — but
# NOTHING regenerated it. So the fixture only ever caught a Java change; a PYTHON change that
# moved the engine's semantics left the fixture pinning the port to an engine that no longer
# exists, and the suite stayed green while the two implementations diverged. This project has
# already been burned once by exactly that failure mode (classifiers/behavior_drift/PROGRAM.md
# §12.5), which is why the fixture exists at all.
#
# WHAT IT DOES. Reruns the generator in memory and diffs it against the checked-in file, failing
# on any difference. It writes nothing, so a red run leaves the tree untouched and the fix is the
# developer's own regeneration plus whatever the Java tests then report.
#
# The one block that legitimately cannot be regenerated everywhere is `encoder_smoke` — reference
# embeddings from the real GTE checkpoint, which needs torch and a multi-GB download. The
# generator fails soft on it and carries the checked-in block forward unchanged, so this gate is
# honest on a runner with no model: it compares everything that IS reproducible, and a machine
# that CAN produce the block still diffs it.
#
# EDITIONS (#875). Unlike check-classifier-parity.sh there is no narrowed form here: the generator
# IS the paid half (`experiments.engine`, in the overlay since #843), so an open-edition run has
# nothing to regenerate and skips whole. Two things it now decides for itself rather than having a
# caller decide for it:
#
#   --edition open   skip, with the reason printed.
#   --edition all    run when the generator is importable; skip, named, when it is not.
#
# And the guard moved onto the RIGHT predicate. Every caller used to ask whether the paid overlay
# directory was on disk, which was correct only by accident: this script cds into `classifiers/`, a
# tree the open-core ledger buckets `private` and epic 4's export deletes outright. Overlay-present
# and classifiers-present are different questions, and only the second is this gate's dependency.
# (This file may not name that directory at all — check-open-boundary.sh rule 5 — which is itself
# the reason the predicate had to move out of the callers and into the two probes above.)
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

# Kept, but not the edition predicate. Since #1293 classifiers/ ships in BOTH editions (the open
# half — framework/, tool_error/, metric_drift/, data_gen/ — stayed behind when the research half
# moved into the overlay), so this fires only in a checkout with no classifier tree at all. The
# `--edition open` skip above is what answers for the export; the importability probe below is what
# answers for a paid checkout missing its overlay.
if [ ! -d "$ROOT/classifiers" ]; then
  echo "conformance:parity skipped — no classifiers/ tree in this checkout at all, so there is no uv project to run the generator from"
  exit 0
fi

cd "$ROOT/classifiers"

if ! command -v uv >/dev/null 2>&1; then
  echo "check: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

# Importability, not directory existence — the same probe shape and the same three-way answer as
# check-classifier-parity.sh, for the same reason: a broken uv environment must never read as an
# absent paid half and quietly turn a gate into a skip. 0 = present, 3 = absent, anything else is
# a hard failure. Path entries come out of pyproject.toml so this file names no overlay directory
# (check-open-boundary.sh rule 5) and stays true if the layout moves.
#
# THE SAME ENTRIES ARE EXPORTED AS PYTHONPATH FOR THE RUN, and #1293 is why. This used to be a
# probe-only concern: `experiments` resolved from this directory, and the open
# classifiers/experiments/__init__.py appended the overlay to its own `__path__` on an is_dir()
# test, so `python -m experiments.engine...` found the generator with no path help at all. #1293
# moved e01-e30 into the overlay beside engine/ and shared/, which made that shim pointless and
# deleted it — and with it the only thing making the RUN below resolve. The probe still passed
# (it inserts the entries itself), so the failure mode was the worst kind: a gate that says the
# generator is present and then dies on ModuleNotFoundError. One derivation now feeds both.
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
