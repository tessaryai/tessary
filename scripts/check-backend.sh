#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Backend gate: `bash scripts/check-backend.sh [all|tests|lint] [mvn args]`.
#   all    tests, coverage floor and static analysis in one reactor (`task backend:check`)
#   tests  tests and the coverage floor, no Spotless / forbidden-apis / SpotBugs
#   lint   Spotless / forbidden-apis / SpotBugs, no tests
# CI runs `tests` and `lint` as parallel jobs. Error Prone and NullAway run at compile in every mode.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Aggregate line coverage floor (JaCoCo, all modules).
MIN_LINE_COVERAGE=0.958

mode="${1:-all}"
[ $# -gt 0 ] && shift
case "$mode" in
  all) flags="" ;;
  tests) flags="-Dspotless.check.skip=true -Dforbiddenapis.skip=true -Dspotbugs.skip=true" ;;
  lint) flags="-DskipTests -Djacoco.skip=true" ;;
  *)
    echo "check-backend: unknown mode '$mode' (all, tests or lint)" >&2
    exit 2
    ;;
esac

jmajor="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
if [ "${jmajor:-0}" != 25 ]; then
  echo "check-backend: this project requires Java 25, but the active JDK is '${jmajor:-unknown}'." >&2
  echo "  $(java -version 2>&1 | head -1)" >&2
  echo "  Set JAVA_HOME to a Java 25 JDK (e.g. sdk use java 25.0.3-tem) and retry." >&2
  exit 1
fi

cd "$ROOT/backend"
# shellcheck disable=SC2086 # flags is a list of -D options without spaces
mvn -B -T 2 verify $flags "$@"

[ "$mode" = lint ] && exit 0
awk -F, -v min="$MIN_LINE_COVERAGE" '
  NR > 1 { missed += $8; covered += $9 }
  END {
    ratio = covered / (missed + covered)
    printf "check-backend: aggregate line coverage %.4f (%d of %d), floor %s\n", ratio, covered, missed + covered, min
    exit ratio < min
  }' app/target/site/jacoco-aggregate/jacoco.csv
