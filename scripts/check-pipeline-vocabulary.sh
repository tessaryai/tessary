#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Grep gates for the classifier-pipeline and triage cutovers, catching renamed or dropped DB
# vocabulary that only fails at runtime, inside a SQL string the compiler can't see.
#
# Scans Java and TypeScript only. Not `.sql` (the baseline snapshot names none of these; a migration
# that drops something has to name what it drops), not `.md`, not generated or build output.
#
# Comment lines are exempt: several classes carry javadoc explaining a rename, and that prose stays.
# A line whose first non-space character starts a comment (`//`, `*`, `/*`) is skipped.
#
# To add a gate, append to FORBIDDEN. To exempt real code, add it to ALLOWED with a reason, after
# reading the line: an exemption for a live query is how a dropped table gets read again.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0

# token|what replaced it
FORBIDDEN=(
    "behavior_finding|the shared 'finding' table"
    "conformance_finding|the shared 'finding' table"
    "signal_event_v|the DetectionTableRegistry-stitched union; the classifier_detection_v view it replaced is still physically in the baseline changelog but unread"
    "signal_trend_rollup|dropped with the detection cutover"
    "grader_run_trigger|dropped with grader Layer 1"
    "source_ref|'eval_case.finding_id', a real FK"
    # The Java-side spelling of the same dropped column: a field still called sourceRef hands the
    # retired name to the next reader.
    "sourceRef|'findingId' — the value is a finding id now"
    "trace_v2|'trace' — the teardown renamed it"
    # The signal→classifier vocabulary: `signal` became `classifier`, `signal_classifier` became
    # `classifier_model`, and the ~12 `signal_id` / `signal_key` columns renamed too. The camelCase
    # spellings are gated for the same reason. `retention_policy.signal` is a different sense of the
    # word (a retention class, now `data_class`) and matches none of these.
    "signal_classifier|the 'classifier_model' table"
    "signal_id|'classifier_id'"
    "signal_key|'classifier_key'"
    "signalId|'classifierId'"
    "signalKey|'classifierKey'"
    # The v1 substrate vocabulary: a context was v1's conversation spine, an observation was v1's unit
    # of work. Both tables are gone; the snake_case tokens also catch `subject_context_id`,
    # `source_context_id` and `subject_observation_id` as substrings.
    #
    # Bare `contextId` / `observationId` are deliberately not gated. `observationId` is the Java field
    # name for a producer span id across the whole analysis slice, roughly thirty sites that name no
    # column at all; gating it would mean thirty exemptions. The compound camelCase spellings below are
    # gated because each one was a column accessor, so a survivor is a rename that stopped halfway.
    "context_id|'session_id' — the v1 spine is gone"
    "observation_id|'span_id' — a span is the unit of work now"
    "subjectContextId|'subjectSessionId'"
    "subjectObservationId|'subjectSpanId'"
    "sourceContextId|'sourceSessionId'"
    # The design-fit refactors: a table, two functions and two metered units were renamed, so a
    # raw-SQL string is the only place an old name would not fail at compile time. `trace_sourced` is a
    # persisted value rather than a relation; it moved with no dual-accept window, so the old spelling
    # is never right. Its camelCase spelling is gated too, on the same sourceRef/signalKey precedent.
    "conformance_verdict|the 'conformance_detection' table"
    "substrate_v2_synth|'synth_trace_id' / 'synth_span_id'"
    "trace_sourced|'span_sourced' — the snapshot's grain is a span"
    "traceSourced|'spanSourced'"
    "signal_evals|'l1_evals' — a metered unit names the layer it bills"
    "grader_runs|'l2_evals' — a metered unit names the layer it bills"
    # Grading, datasets, the git observer, sampling policy and the Environment concept were removed
    # outright, and the migration that removed them dropped the tables behind them. Same shape as
    # every gate above: a query naming one of these compiles, boots, and fails the first time it runs.
    # The verdict tokens are keyword-qualified rather than bare because `verdict` survives in four
    # other senses (ConformanceVerdict, AgentVerdict, BehaviorTriageVerdict, and the `triage_verdict`
    # column), none of which is the dropped table.
    "FROM verdict|nothing — grading left the platform"
    "INTO verdict|nothing — grading left the platform"
    "UPDATE verdict|nothing — grading left the platform"
    "JOIN verdict|nothing — grading left the platform"
    "grader_run_job|nothing — the grader lane is gone"
    "grader_golden_dataset|nothing"
    "grader_calibration|nothing"
    # Not gated, and each omission is deliberate: `grader_failure_mode` is the surviving pipeline
    # bundle's failure-mode catalog (grader-prefixed by history, not content), so its table name and
    # its `grader_deferred` / `grader_id` columns are live. `quality_dimensions` and `graders/` are
    # shard paths the vendored plugin still writes, which `BundleAssembler` skips by name, so the
    # strings must stay readable. `grader_code` is an agent-context panel key, not a relation.
    "dataset_item|nothing"
    "regrade_diff|nothing"
    "curation_entry|nothing"
    "grading_spend_|nothing — the grading spend breaker is gone"
    "grading_breaker|nothing — the grading spend breaker is gone"
    "risk_stat|nothing — the learned risk model is gone"
    "sampling_policy|nothing — ingest applies no sampling"
    "diff_classification|nothing — the observer is gone"
    "observer_alert|nothing — the observer is gone"
    "org_observer_settings|nothing — the observer is gone"
    "environment_id|nothing — the Environment concept is gone"
    "SamplingGate|nothing"
    "SamplingPolicy|nothing"
    "EnvironmentFilter|nothing"
)

# The lines of real code that may legitimately name a dropped relation. Each pattern matches the
# file and the line together, so an exemption expires the moment that line becomes a real query.
#
#   SUBJECT_KIND = "behavior_finding": not a table reference. `llm_call.subject_kind` is a persisted
#   string naming the unit of work a sandbox run was for; rewriting it would orphan every triage-spend
#   row already written under it.
#
#   SubstrateV2DependentPrepTest: a @ValueSource of relation names the test asserts are gone. Naming
#   the table is the assertion. (This is also why there is no `*_v1` gate: the teardown dropped the
#   v1 tables outright, so no `*_v1` name was ever written in Java.)
#
#   BehaviorTriageJobRepository: `context_id` here is a key inside the triage job's own JSONB payload,
#   not a column. The repository is both the only writer and reader, so the key is self-consistent.
#   This exemption is keyed on the file name, so it moves with a rename of that file.
#
#   SubstrateWriteIntegrationTest / SubstrateV2DependentPrepTest: assertions that a column of that
#   name is gone, queried out of information_schema. Naming it is the assertion.
#
#   TrajectoryAssemblerParityTest: a key in the cross-language reduction-contract fixture the Python
#   detector and the Java assembler are both checked against. The Python side owns the field names.
ALLOWED='(SUBJECT_KIND = "behavior_finding"'
ALLOWED="$ALLOWED"'|SubstrateV2DependentPrepTest\.java:[0-9]+:.*@ValueSource'
ALLOWED="$ALLOWED"'|BehaviorTriageJobRepository\.java:[0-9]+:.*context_id'
ALLOWED="$ALLOWED"'|SubstrateWriteIntegrationTest\.java:[0-9]+:.*column_name = .observation_id'
ALLOWED="$ALLOWED"'|SubstrateV2DependentPrepTest\.java:[0-9]+:.*observation_id'
ALLOWED="$ALLOWED"'|TrajectoryAssemblerParityTest\.java:[0-9]+:.*"observation_id")'

_scan() {
    # Java + TypeScript sources only, excluding build output and generated code.
    grep -rn --include='*.java' --include='*.ts' --include='*.tsx' -- "$1" backend frontend/src 2>/dev/null \
        | grep -v '/target/' \
        | grep -v '/node_modules/' \
        | grep -v '/generated-sources/' \
        | grep -v '/dist/' \
        || true
}

# Strip comment lines (javadoc, block, line); see the header above.
_code_only() {
    grep -v -E '^[^:]+:[0-9]+: *(\*|//|/\*)' || true
}

for entry in "${FORBIDDEN[@]}"; do
    token="${entry%%|*}"
    replacement="${entry#*|}"
    hits="$(_scan "$token" | _code_only | grep -v -E "$ALLOWED" || true)"

    if [ -n "$hits" ]; then
        echo "ERROR: '$token' names a relation the classifier-pipeline cutover dropped." >&2
        echo "       Use $replacement." >&2
        printf '%s\n' "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# ---- The triage cutover's gates ----
#
# Layer 2 stopped adjudicating and started triaging. A migration dropped the six `adjudication_*`
# columns on `finding` and deleted every `job.kind = 'behavior_adjudication'` row, so both names now
# resolve to nothing, the same shape as the dropped relations above. The other two are name gates:
# `BehaviorAdjudication*` was the renamed class prefix, and `adjudicate.js` was the launcher script
# before `triage.js` replaced it. A surviving spelling of either is a caller that will 404 at the
# launcher or a class that no longer exists.
#
# This scan is wider than the one above: it reaches `sandbox-runner/` (the launcher is JavaScript)
# and reads markdown, since two of these four are vocabulary rather than SQL and a design doc naming
# the old column is wrong in a way no build can see. `docs/reference/` is exempt because that is
# where this cutover's history notes live; `scripts/` is exempt because check-migrations-populated.sh
# asserts the delete ran.
TRIAGE_FORBIDDEN=(
    "adjudication_verdict|'triage_verdict' — 0009 dropped the column and its five siblings"
    "behavior_adjudication|job.kind = 'triage'"
    "BehaviorAdjudication|the BehaviorTriage* classes"
    "adjudicate\\.js|'triage.js' — the launcher script the sandbox posts to"
)

_scan_triage() {
    # No --include='*.md' and no docs/ root (standing rule, see scripts/check.sh's header): a
    # dropped relation named in prose is not a bug. Code and config only.
    grep -rn --include='*.java' --include='*.ts' --include='*.tsx' --include='*.js' \
        -- "$1" backend classifiers contract frontend/src sandbox-runner 2>/dev/null \
        | grep -v '/target/' \
        | grep -v '/node_modules/' \
        | grep -v '/generated-sources/' \
        | grep -v '/dist/' \
        | grep -v '^docs/reference/' \
        || true
}

for entry in "${TRIAGE_FORBIDDEN[@]}"; do
    token="${entry%%|*}"
    replacement="${entry#*|}"
    hits="$(_scan_triage "$token" | _code_only || true)"

    if [ -n "$hits" ]; then
        echo "ERROR: '$token' names something the triage cutover removed." >&2
        echo "       Use $replacement." >&2
        printf '%s\n' "$hits" | sed 's/^/  /' >&2
        fail=1
    fi
done

# The per-value verdict gates that used to live here are gone with their subject: they pinned facts
# about the grading store that no longer exists. What replaces them is the `FROM|INTO|UPDATE|JOIN
# verdict` pair of tokens in FORBIDDEN above, which say the same thing about a table that is not
# there and cannot go stale.

# The MDC key set is a log-field contract declared twice: as constants in LogContext and as strings
# in logback-spring.xml. Nothing above reads that file, so a rename that touched only one side would
# ship green while the field silently stopped appearing on every log line. This asserts the two agree.
# (Record a key rename in devdocs/reference/telemetry-naming.md too; that half a build can't see.)
LOGBACK='backend/app/src/main/resources/logback-spring.xml'
LOGCTX='backend/shared/src/main/java/ai/tessary/open/obs/LogContext.java'
unknown_mdc=""
while IFS= read -r key; do
    [ -n "$key" ] || continue
    grep -q "\"$key\"" "$LOGCTX" || unknown_mdc="${unknown_mdc:+$unknown_mdc }$key"
done <<EOF
$( {
    sed -n 's/.*<includeMdcKeyName>\([^<]*\)<.*/\1/p' "$LOGBACK"
    sed -n 's/.*<captureMdcAttributes>\([^<]*\)<.*/\1/p' "$LOGBACK" | tr ',' '\n'
} | tr -d ' \t\r' | sort -u )
EOF
if [ -n "$unknown_mdc" ]; then
    echo "ERROR: $LOGBACK ships MDC key(s) no LogContext constant defines: $unknown_mdc" >&2
    echo "       The two must agree — a key named on only one side is a field that never reaches a log line." >&2
    fail=1
fi

[ "$fail" = 0 ] && echo "pipeline vocabulary: ok"
exit $fail
