#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Classifier PORT-PARITY gate. Single source of truth: invoked by both the Taskfile
# (`task classifiers:parity`) and scripts/check.sh.
#
# WHAT THIS EXISTS FOR. Several classifier components are deliberately implemented twice — once in
# Python where they were measured, once in Java or JS where they serve — and a duplicate with
# nothing pinning it drifts silently. This repo has already paid for that once: behaviour drift's
# `detect.py` and `BehaviorDriftDetector.java` diverged, and every number published in the interval
# described a detector that did not exist (behavior_drift/PROGRAM.md §12.5). The failure is quiet by
# construction — both sides keep working, they just stop being the same thing.
#
# So the pinned pairs get a gate that runs on every PR:
#   * groundedness/verifiable_claims.py  <->  VerifiableClaims.java   (the deterministic sentence
#     filter in front of the head; if it drifts, an offline sweep scores a different POPULATION than
#     production and every rate it reports is about the harness rather than the model). VerifiableClaims.java
#     moved into the paid overlay with the groundedness detector (#887/#888) — this pin's Java half is
#     now paid-owned, same as the sop_compiler/experiments pins below, but it stays a PYTHON-ONLY test
#     (no live import of the Java file) and keeps running unconditionally in both editions; see the
#     printed summary below.
#   * groundedness/sweep_corpus.py       <->  classify.js windowsFor/premiseChunksFor  (the pair
#     head's premise tiling; if it drifts, the rig scores different UNITS than the service does)
#   * frustration/slice/build.py        <->  BuiltInClassifierCatalog + ClassifierProperties +
#     ConversationThreadAssembler  (the band, the context policy and the narrow->stub->reduce order
#     the frustration head is served under; if the rig keeps a stale band or a reordered call site,
#     it reports a precision for an operating point nobody runs)
#   * sop_compiler/metrics.py            <->  experiments/shared/metrics.py  (a shipping copy of the
#     experiment's scorer; if it drifts, the compiler publishes numbers under different arithmetic
#     than the tables it is pinned to)
#
# Deliberately NOT the whole classifiers suite: tests/test_tool_error_bridge.py and
# tests/test_metric_drift_windows.py drive the real Java through jshell and raise BridgeUnavailable
# unless the backend has been compiled in this worktree. That loudness is their design ("raised with
# what to run, never swallowed") and is right for a developer running them deliberately; it is wrong
# for a gate that must pass on a clean checkout. They stay a manual/`task check` -- backend concern.
#
# EDITIONS (#875, rewritten by #1293). ALL SIX PINS ARE OVERLAY-OWNED NOW, and `--edition open`
# consequently runs ZERO of them and says so by name. Read that sentence again before trusting a
# green line from this gate in the open edition: it is asserting nothing there, on purpose, and the
# summary it prints says which pins it did not run and why.
#
# How it got here. #875 split the six into three open and three paid, because groundedness/ and
# frustration/ were still public trees. #1293 moved the whole research half of classifiers/ into
# the overlay — groundedness/, frustration/, behavior_drift/, experiments/, out/ — and every one of
# the six pinned tests went with the module it pins. What stayed public is framework/, tool_error/,
# metric_drift/ and data_gen/, none of which has a Java or JS twin to pin. So the split is not
# 3+3 any more; it is 0+6, and pretending otherwise by keeping a token open pin would be worse than
# admitting the count.
#
#   --edition all   (default)  six tests when the paid pins are importable; zero plus a named skip
#                              when they are not.
#   --edition open             ZERO tests, ALWAYS, plus a named skip. Deliberately not "six if the
#                              overlay happens to be on disk": the open edition must not go red on
#                              a paid-side failure just because of whose laptop ran it.
#
# WHY THE `[ -d classifiers ]` GUARD BELOW IS NO LONGER THE PREDICATE. It used to be right —
# classifiers/ was bucket `private` in its entirety, so its absence meant "no Python originals to
# pin against". After #1293 classifiers/ exists in BOTH editions (the open half stayed), so that
# test is now permanently true and would have carried this script straight into a `cd` and six
# missing test files. The predicate is the importability probe below, which is the real question
# (does the paid half resolve the way pytest resolves it) and which — like the paid test paths
# themselves — is derived from classifiers/pyproject.toml's `pythonpath` entries rather than
# written out, because rule 5 of check-open-boundary.sh forbids this file from naming the overlay
# directory at all.
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

# Kept, but no longer the edition predicate (see the header). classifiers/ ships in both editions
# since #1293, so this fires only in a checkout that has no classifier tree at all — which is not a
# shape the export produces, but is a shape a partial clone produces, and `set -euo pipefail` plus
# the `cd` below would otherwise make it a bare shell error with no explanation.
if [ ! -d "$ROOT/classifiers" ]; then
  echo "classifier:parity skipped — no classifiers/ tree in this checkout at all, so there is no uv project to run the pins from"
  exit 0
fi

cd "$ROOT/classifiers"

if ! command -v uv >/dev/null 2>&1; then
  echo "check: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi

# EMPTY, and that is the finding, not an oversight. Until #1293 this held the three pins whose
# Python originals lived in the open tree (groundedness/verifiable_claims.py,
# groundedness/sweep_corpus.py, frustration/slice/build.py). All three modules are overlay-owned
# now, so all three tests moved with them and there is no open-side pin left to run. The open half
# that stayed — framework/, tool_error/, metric_drift/, data_gen/ — has no second implementation
# anywhere, so there is nothing for a port-parity gate to pin. If a public module ever grows a Java
# or JS twin, its pin goes here and this comment shrinks.
OPEN_TESTS=""
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
  # The probe has to resolve modules the way PYTEST will, not the way a bare interpreter does.
  # `sop_compiler` is a TOP-LEVEL module that sits under no package able to shim it, so it is
  # reachable only through the `pythonpath` entries in classifiers/pyproject.toml's
  # [tool.pytest.ini_options] — which pytest inserts and `python -c` does not. Measured: a bare
  # find_spec reports absent with the paid half fully present, i.e. it would have silently halved
  # this gate in exactly the edition that wants all six pins. Reading the entries out of the ini
  # also keeps this file from naming a directory rule 5 forbids it to name, and keeps it true if
  # the path ever moves.
  #
  # It also PRINTS the directory it found sop_compiler in, and that answers a second question this
  # script cannot answer any other way. Since #1293 all six pinned tests live beside sop_compiler in
  # the overlay's own tests/ directory, and rule 5 forbids naming that path here — so the probe
  # returns it and the run below builds the file list from what it returned. One resolution, used
  # for both "is the paid half here" and "where are its tests", so the two can never disagree.
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
  # config there, and applies NEITHER pythonpath entry — measured at #1293: 15 of the relocated
  # tests then fail to collect with ModuleNotFoundError while the gate looks merely red rather than
  # misconfigured. Naming the config file explicitly pins rootdir back to classifiers/.
  # shellcheck disable=SC2086
  uv run --quiet pytest -q -c pyproject.toml $PAID_TESTS
else
  if [ -n "$OPEN_TESTS" ]; then
    # shellcheck disable=SC2086
    uv run --quiet pytest -q $OPEN_TESTS
  fi
  if [ "$EDITION" = open ]; then
    why="the open edition does not carry them"
  else
    why="sop_compiler is not importable in this checkout"
  fi
  echo "classifier:parity — RAN 0 OF 6 PINS ($why)."
  echo "  skipped: $PAID_TEST_NAMES"
  echo "  Since #1293 every pinned Python original is overlay-owned: verifiable_claims.py and"
  echo "  sweep_corpus.py (groundedness/), slice/build.py (frustration/), sop_compiler.{metrics,"
  echo "  artifacts,schema} and experiments.shared.metrics. The open half of classifiers/ that"
  echo "  remains — framework/, tool_error/, metric_drift/, data_gen/ — has no second implementation"
  echo "  in Java or JS, so there is nothing here for a PORT-parity gate to pin."
  echo "  READ THAT AS ZERO COVERAGE, NOT AS A PASS. This gate is green in the open edition because"
  echo "  it asserted nothing, and it prints this rather than a bare OK so that is impossible to"
  echo "  miss. The Java/JS ports it guards are still open code; what moved is the Python originals"
  echo "  they are pinned to, which is why the pins moved with them."
fi

echo "classifier parity check OK"
