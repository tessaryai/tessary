#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Classifier PORT-PARITY gate. Single source of truth: invoked by both the Taskfile
# (`task classifiers:parity`) and scripts/check.sh.
#
# WHAT THIS EXISTS FOR. Several classifier components are implemented twice, once in Python where
# they were measured, once in Java or JS where they serve, and a duplicate with nothing pinning it
# drifts silently. This repo has already suffered that once: behaviour drift's `detect.py` and
# `BehaviorDriftDetector.java` diverged, and every number published in the interval described a
# detector that did not exist (behavior_drift/PROGRAM.md §12.5). The failure is quiet by
# construction: both sides keep working, they just stop being the same thing.
#
# The pinned pairs:
#   * groundedness/verifiable_claims.py  <->  VerifiableClaims.java   (the deterministic sentence
#     filter in front of the head; if it drifts, an offline sweep scores a different POPULATION than
#     production). This pin stays a PYTHON-ONLY test (no live import of the Java file) and keeps
#     running unconditionally; see the printed summary below.
#   * groundedness/sweep_corpus.py       <->  classify.js windowsFor/premiseChunksFor  (the pair
#     head's premise tiling; if it drifts, the rig scores different UNITS than the service does)
#   * frustration/slice/build.py        <->  BuiltInClassifierCatalog + ClassifierProperties +
#     ConversationThreadAssembler  (the band, the context policy and the narrow->stub->reduce order
#     the frustration head is served under)
#   * sop_compiler/metrics.py            <->  experiments/shared/metrics.py  (a shipping copy of the
#     experiment's scorer; if it drifts, the compiler publishes numbers under different arithmetic
#     than the tables it is pinned to)
#
# Deliberately NOT the whole classifiers suite: tests/test_tool_error_bridge.py and
# tests/test_metric_drift_windows.py drive the real Java through jshell and raise BridgeUnavailable
# unless the backend has been compiled in this worktree. That loudness is right for a developer
# running them deliberately, and wrong for a gate that must pass on a clean checkout. They stay a
# manual/`task check` -- backend concern.
#
# EDITIONS. Six pins total, none present in an open checkout, so `--edition open` runs ZERO of them and says so by
# name. A green line from this gate in an open checkout asserts nothing on its own; the summary it
# prints says which pins it did not run and why.
#
#   --edition all   (default)  six tests when the paid half is importable; zero plus a named skip
#                              when it is not.
#   --edition open             ZERO tests, ALWAYS, plus a named skip. Deliberately not "six if the
#                              overlay happens to be on disk": this must not go red on a paid-side
#                              failure just because of whose laptop ran it.
#
# WHY THE `[ -d classifiers ]` GUARD BELOW IS NOT THE PREDICATE. classifiers/ now ships in every
# checkout, so that test is permanently true and would carry this script straight into a `cd` and
# six missing test files. The predicate is the importability probe below, the real question (does
# the paid half resolve the way pytest resolves it), derived from classifiers/pyproject.toml's
# `pythonpath` entries rather than written out, because rule 5 of check-open-boundary.sh forbids
# this file from naming the overlay directory at all.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

EDITION=all
while [ $# -gt 0 ]; do
  case "$1" in
    --edition)
      EDITION="${2:-}"
      case "$EDITION" in
        open|all) ;;
        *) echo "check-classifier-parity: --edition takes 'open' or 'all', got '${EDITION:-}'" >&2; exit 1 ;;
      esac
      shift 2
      ;;
    *) echo "check-classifier-parity: unknown argument '$1' (only --edition open|all)" >&2; exit 1 ;;
  esac
done

# Kept, but no longer the edition predicate (see the header). classifiers/ ships in both editions,
# so this fires only in a checkout with no classifier tree at all — not a shape the export
# produces, but a shape a partial clone produces, and `set -euo pipefail` plus the `cd` below
# would otherwise make it a bare shell error with no explanation.
if [ ! -d "$ROOT/classifiers" ]; then
  echo "classifier:parity skipped — no classifiers/ tree in this checkout at all, so there is no uv project to run the pins from"
  exit 0
fi

cd "$ROOT/classifiers"

if ! command -v uv >/dev/null 2>&1; then
  echo "check: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

# ONE open pin, and it is the case this slot was left open for: groundedness/sweep_corpus.py is a
# Python copy of classify.js's premise tiling and window reduction, and BOTH sides are open code, so
# the pin belongs here rather than in the overlay list below. It runs in EVERY edition — see the
# unconditional block after the probe. The rest of what stayed public (framework/, tool_error/,
# metric_drift/, data_gen/) still has no second implementation anywhere, so there is nothing more
# here for a port-parity gate to pin.
OPEN_TESTS="tests/test_groundedness_sweep_corpus.py tests/test_groundedness_token_head.py"
# All six pins. Basenames only: the directory they live in is resolved at run time from
# classifiers/pyproject.toml's pythonpath entries (see PAID_TESTS_DIR below) because rule 5 forbids
# this file from spelling the overlay path.
PAID_TEST_NAMES="test_verifiable_claims_parity.py test_premise_windowing_parity.py test_frustration_serving_parity.py test_sop_compiler_metrics_parity.py test_e19_exemplar_lint.py test_e19_extract_json.py"

# Importability, not directory existence. Exit 0 = present, 3 = absent, and ANYTHING ELSE is a hard
# failure: a uv environment that cannot resolve, a syntax error in the package, or a missing
# interpreter must never read as "the paid half is simply not installed" and quietly halve the gate.
run_paid=0
PAID_TESTS_DIR=""
if [ "$EDITION" = all ]; then
  set +e
  # The probe resolves modules the way PYTEST will, not a bare interpreter: `sop_compiler` is a
  # TOP-LEVEL module reachable only through the `pythonpath` entries in classifiers/pyproject.toml's
  # [tool.pytest.ini_options], which pytest inserts and `python -c` does not. A bare find_spec
  # reports absent even with the paid half fully present, which would silently halve this gate.
  # Reading the entries out of the ini also keeps this file from naming a directory rule 5 forbids
  # it to name.
  #
  # It also PRINTS the directory it found sop_compiler in: all six pinned tests live beside it in
  # the overlay's own tests/ directory, and rule 5 forbids naming that path here, so the run below
  # builds the file list from what the probe returned. One resolution for both questions, so they
  # can never disagree.
  PAID_TESTS_DIR="$(uv run --quiet python -c '
import importlib.util, pathlib, sys, tomllib
cfg = tomllib.loads(pathlib.Path("pyproject.toml").read_text())
for entry in cfg["tool"]["pytest"]["ini_options"].get("pythonpath", []):
    sys.path.insert(0, str(pathlib.Path(entry).resolve()))
spec = importlib.util.find_spec("sop_compiler")
if spec is None or not spec.origin:
    sys.exit(3)
tests = pathlib.Path(spec.origin).resolve().parent.parent / "tests"
if not tests.is_dir():
    sys.exit(3)
print(tests)
')"
  probe=$?
  set -e
  case "$probe" in
    0) run_paid=1 ;;
    3) run_paid=0 ;;
    *)
      echo "check-classifier-parity: the sop_compiler probe failed with exit $probe, which is neither" >&2
      echo "  present (0) nor absent (3). That is a broken uv environment, not an absent paid half," >&2
      echo "  and treating it as absence would silently drop three of this gate's six pins." >&2
      exit 1
      ;;
  esac
fi

# The open pins run in BOTH editions, and unconditionally. They pin open Python against open JS, so
# a checkout that ALSO has the paid half must not skip them — putting this inside the else below
# would mean the pin never runs in the edition that ships to customers.
if [ -n "$OPEN_TESTS" ]; then
  # A pin that SKIPS is not a pin that ran: -rs prints every skip with its reason, and one skip
  # fails this gate, so "RAN N OPEN PINS" below is never printed over a test that asserted nothing.
  # shellcheck disable=SC2086
  open_out="$(uv run --quiet pytest -q -rs -c pyproject.toml $OPEN_TESTS 2>&1)" || { printf '%s\n' "$open_out"; exit 1; }
  printf '%s\n' "$open_out"
  if printf '%s' "$open_out" | grep -qE '^SKIPPED '; then
    echo "check-classifier-parity: an OPEN pin skipped (reasons above). The pin must run: install" >&2
    echo "  node + classify-service/node_modules, and let the pinned tokenizer download once." >&2
    exit 1
  fi
fi

if [ "$run_paid" = 1 ]; then
  PAID_TESTS=""
  for _t in $PAID_TEST_NAMES; do
    [ -f "$PAID_TESTS_DIR/$_t" ] || {
      echo "check-classifier-parity: $_t is not in the resolved paid tests directory. The paid half" >&2
      echo "  IS importable, so this is a renamed or deleted pin, not an absent overlay — which is" >&2
      echo "  the one case that must never read as a skip." >&2
      exit 1
    }
    PAID_TESTS="$PAID_TESTS $PAID_TESTS_DIR/$_t"
  done
  # `-c pyproject.toml` is LOAD-BEARING and its absence is silent. Every path above is outside this
  # directory, and with an argument outside the rootdir pytest re-roots at the repo root, finds no
  # config there, and applies NEITHER pythonpath entry — the relocated tests then fail to collect
  # with ModuleNotFoundError while the gate looks merely red rather than misconfigured. Naming the
  # config file explicitly pins rootdir back to classifiers/.
  # shellcheck disable=SC2086
  uv run --quiet pytest -q -c pyproject.toml $PAID_TESTS
else
  if [ "$EDITION" = open ]; then
    why="--edition open never carries them"
  else
    why="sop_compiler is not importable in this checkout"
  fi
  echo "classifier:parity — RAN 2 OPEN PINS; 6 OVERLAY PINS SKIPPED ($why)."
  echo "  ran:     $OPEN_TESTS  (groundedness/sweep_corpus.py <-> classify.js; token_eval encoding <-> groundedness.js)"
  echo "  skipped: $PAID_TEST_NAMES"
  echo "  Those overlay pins' Python originals are not in this checkout: verifiable_claims.py"
  echo "  (groundedness/), slice/build.py (frustration/), sop_compiler.{metrics,artifacts,schema}"
  echo "  and experiments.shared.metrics."
  echo "  test_premise_windowing_parity.py OVERLAPS the open pin above — both pin the same premise"
  echo "  tiling — because sweep_corpus.py is now open while the overlay may still carry its own"
  echo "  copy. Groundedness moved into the open tree on 2026-09-21 (detector, table, weights); the"
  echo "  overlay name stays listed until the overlay's copy is deleted, rather than dropping silently."
  echo "  READ THAT AS ONE PIN OF COVERAGE, NOT SEVEN. The premise tiling is pinned to the JS that"
  echo "  serves it; claim extraction, the frustration serving contract and the SOP compiler's"
  echo "  metrics are not pinned to anything in this checkout. This gate prints the count rather"
  echo "  than a bare OK so that is impossible to miss."
fi

echo "classifier parity check OK"
