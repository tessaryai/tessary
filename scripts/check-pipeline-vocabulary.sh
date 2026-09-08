#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The cutovers' grep gates: the classifier pipeline's (implementation plan §4 M6) and the triage
# lane's (triage-rca-implementation-plan.md M6), in that order.
#
# The cutover was hard: `behavior_finding`, `conformance_finding`, `signal_trend_rollup`,
# `grader_run_trigger`, `signal_event_v` and `eval_case.source_ref` were DROPPED, the substrate
# teardown dropped the v1 tables and renamed `trace_v2` to `trace`, and the signal→classifier and
# context/observation→session/span vocabularies moved wholesale. A query naming any of them does not
# fail at compile time and does not fail at startup — it fails the first time it runs, in
# production, against a table that is not there. That is the whole reason this gate exists: nothing
# else in the build can see inside a SQL string. It stayed load-bearing through the 2026-08 database
# reset: the baseline changed which migrations exist, not what is dropped.
#
# WHAT IS SCANNED: Java and TypeScript sources. Not `.sql` — 0000-baseline.sql is a snapshot of the
# schema as it stands, so it names none of these, and a future migration that drops something has to
# name what it drops. Not `.md` — docs explain what was removed. Not generated sources or build
# output. (The triage section at the bottom scans a wider set, for reasons it states there.)
#
# COMMENT LINES ARE EXEMPT. The gate is about SQL a program executes, and several classes carry
# javadoc explaining which name they used to hold and why they no longer do. Deleting that prose to
# satisfy a grep would remove the only in-code record of the rename. A line whose first non-space
# character starts a comment (`//`, `*`, `/*`) is skipped; anything else is code.
#
# TO ADD A GATE: append to FORBIDDEN. To exempt a specific line of real code, add it to ALLOWED with
# the reason — and read the line first. An exemption for something that turned into a live query is
# how a dropped table gets read again.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0

# token|what replaced it
FORBIDDEN=(
    "behavior_finding|the shared 'finding' table"
    "conformance_finding|the shared 'finding' table"
    "signal_event_v|the DetectionTableRegistry-stitched union (Epic 3; the classifier_detection_v view it replaced is still physically in the baseline changelog but unread)"
    "signal_trend_rollup|dropped with the detection cutover"
    "grader_run_trigger|dropped with grader Layer 1"
    "source_ref|'eval_case.finding_id', a real FK"
    # The Java-side spelling of the same dropped column. A parameter or field still called sourceRef
    # outlives the column by one rename and is how the old mental model gets handed to the next reader
    # — the value flowing through it is a finding id now, and it should say so.
    "sourceRef|'findingId' — the value is a finding id now"
    "trace_v2|'trace' — the teardown renamed it"
    # The signal→classifier vocabulary (PLAN-1 phase B1). `signal` became `classifier` and
    # `signal_classifier` became `classifier_model`, so a query naming either resolves to nothing —
    # and so does one naming the ~12 renamed `signal_id` / `signal_key` columns. The camelCase
    # spellings are gated too: a field still called signalKey outlives the column by one rename and
    # hands the retired vocabulary to the next reader. `retention_policy.signal` is a DIFFERENT sense
    # of the word (a retention class, renamed to `data_class` in phase B3) and matches none of these.
    "signal_classifier|the 'classifier_model' table"
    "signal_id|'classifier_id'"
    "signal_key|'classifier_key'"
    "signalId|'classifierId'"
    "signalKey|'classifierKey'"
    # The v1 substrate vocabulary (PLAN-1 phase B2). A context was v1's conversation spine and an
    # observation was v1's unit of work; both tables went with the teardown, and the columns that still
    # named them were renamed after it. The two snake_case tokens are substrings, so they also gate
    # `subject_context_id`, `source_context_id` and `subject_observation_id` — every spelling that moved.
    #
    # BARE `contextId` / `observationId` ARE DELIBERATELY NOT GATED, and it is worth saying why so nobody
    # adds them thinking it was an oversight. `observationId` is the Java field name for a producer SPAN
    # id across the whole analysis slice (SubstrateObservation, TrajectoryAssembler.Symbol,
    # MetricSource.ToolSpanFacts, …) — roughly thirty sites that name no column at all. Gating it would
    # mean thirty exemptions, and a gate whose exemption list is longer than its findings stops being
    # read. What this file protects is SQL a program executes, and the snake_case tokens are that. The
    # compound camelCase spellings below ARE gated: each was a column accessor, so each one that survives
    # is a rename that stopped halfway.
    "context_id|'session_id' — the v1 spine is gone"
    "observation_id|'span_id' — a span is the unit of work now"
    "subjectContextId|'subjectSessionId'"
    "subjectObservationId|'subjectSpanId'"
    "sourceContextId|'sourceSessionId'"
    # The design-fit refactors (PLAN-1 phase B3). A table, two functions and two metered units
    # were renamed; each old name now resolves to nothing, and a raw-SQL string is the only place that
    # would not fail at compile time. `trace_sourced` is a persisted VALUE rather than a relation, and
    # it is gated for the reason that release states: it moved with no dual-accept window, so the old
    # spelling is never right. Its camelCase spelling is gated on the sourceRef/signalKey precedent — an
    # identifier still called traceSourced hands the retired grain to the next reader even where no
    # column is named.
    "conformance_verdict|the 'conformance_detection' table"
    "substrate_v2_synth|'synth_trace_id' / 'synth_span_id'"
    "trace_sourced|'span_sourced' — the snapshot's grain is a span"
    "traceSourced|'spanSourced'"
    "signal_evals|'l1_evals' — a metered unit names the layer it bills"
    "grader_runs|'l2_evals' — a metered unit names the layer it bills"
    # Track A (open-core epic 8). Grading, datasets, the git observer, sampling policy and the
    # Environment concept were removed root and stem, and `0016-track-a-removal.sql` DROPPED the
    # tables behind them. Same shape as every gate above: a query naming one of these compiles, boots,
    # and fails the first time it runs. The verdict tokens are keyword-qualified rather than bare
    # because `verdict` survives in four other senses — ConformanceVerdict, AgentVerdict,
    # BehaviorTriageVerdict, and the `triage_verdict` column — none of which is the dropped table.
    "FROM verdict|nothing — grading left the platform"
    "INTO verdict|nothing — grading left the platform"
    "UPDATE verdict|nothing — grading left the platform"
    "JOIN verdict|nothing — grading left the platform"
    "grader_run_job|nothing — the grader lane is gone"
    "grader_golden_dataset|nothing"
    "grader_calibration|nothing"
    # NOT gated, and each omission is deliberate: `grader_failure_mode` is the SURVIVING pipeline
    # bundle's failure-mode catalog (grader-prefixed by history, not by content), so its table name and
    # its `grader_deferred` / `grader_id` columns are live; `quality_dimensions` and `graders/` are
    # shard PATHS the vendored plugin still writes and `BundleAssembler` deliberately skips by name,
    # so the strings have to stay readable; and `grader_code` appears as an agent-context panel key
    # rather than a relation.
    "dataset_item|nothing"
    "regrade_diff|nothing"
    "curation_entry|nothing"
    "grading_spend_|nothing — the grading spend breaker is gone"
    "grading_breaker|nothing — the grading spend breaker is gone"
    "risk_stat|nothing — the learned risk model is gone (rebuild tracked as #1021)"
    "sampling_policy|nothing — ingest applies no sampling"
    "diff_classification|nothing — the observer is gone"
    "observer_alert|nothing — the observer is gone"
    "org_observer_settings|nothing — the observer is gone"
    "environment_id|nothing — the Environment concept is gone"
    "SamplingGate|nothing"
    "SamplingPolicy|nothing"
    "EnvironmentFilter|nothing"
)

# The lines of real code that may name a dropped relation, and what each has to still be doing. Each
# pattern matches the FILE and the LINE together, so an exemption expires the moment that line stops
# being what it was granted for and becomes a query.
#
#   SUBJECT_KIND = "behavior_finding" — NOT a table reference. `llm_call.subject_kind` is a persisted
#   string naming the unit of work a sandbox run was for, and persisted strings are never renamed in
#   place (the signals→classifiers precedent). It outlived the table whose name it borrowed; rewriting
#   it would orphan every triage-spend row already written under it.
#
#   SubstrateV2DependentPrepTest — a @ValueSource of relation names the test asserts are GONE. It is
#   the assertion that the teardown ran, so naming the table is the point. (This is also why there is
#   no `*_v1` gate: the teardown dropped the v1 tables outright rather than renaming them, so no
#   `*_v1` name was ever written in Java at all. `trace_v2` was the working name of the table now
#   called `trace`, and that gate is the one the teardown itself asks for.)
#
#   BehaviorTriageJobRepository — `context_id` here is a key inside the triage job's own payload
#   JSONB, not a column. The repository is both the only writer and the only reader of that payload,
#   so the key is self-consistent; renaming it would need a migration over in-flight jobs to buy
#   nothing a reader can see. (Contrast the `signal_id` payload key, which HAD to move because the
#   word it used was being retired outright.) This exemption is keyed on the FILE NAME, so it had to
#   move with the adjudication→triage rename: a stale name here does not fail loudly, it silently
#   un-exempts a live line and turns the `context_id` gate red.
#
#   SubstrateWriteIntegrationTest / SubstrateV2DependentPrepTest — assertions that a column of that name
#   is GONE, queried out of information_schema. Naming it is the assertion.
#
#   TrajectoryAssemblerParityTest — a key in `classifiers/behavior_drift/fixtures/reduction_contract.json`,
#   the cross-language reduction contract the Python detector and the Java assembler are both checked
#   against. The Python side owns the file's field names; the Java test only has to read what is there.
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

# Strip comment lines (javadoc, block, line) — see the header.
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

# ---- The triage cutover's gates (docs/reference/triage-rca-implementation-plan.md M6) ----
#
# Layer 2 stopped adjudicating and started triaging. Migration `0009` DROPPED the six `adjudication_*`
# columns on `finding` and DELETED every `job.kind = 'behavior_adjudication'` row along with its partial
# unique index, so both names now resolve to nothing — the same shape as the dropped relations above,
# and invisible to the compiler for the same reason. The other two are name gates rather than relation
# gates: `BehaviorAdjudication*` was the class prefix #781 renamed, and `adjudicate.js` was the launcher
# script the sandbox posted to before `triage.js` replaced it. A surviving spelling of either is a caller
# that will 404 at the launcher or a class that no longer exists.
#
# THIS SCAN IS WIDER THAN THE ONE ABOVE, in two ways it is worth stating. It reaches `sandbox-runner/`,
# because the launcher is JavaScript and the script name lives there rather than in Java. And it reads
# MARKDOWN, because two of these four are vocabulary rather than SQL: a design doc that still tells a
# reader the verdict column is `adjudication_verdict` is wrong in a way no build can see. `docs/reference/`
# is exempt — that is where this cutover's history notes live, and a migration record has to name what it
# migrated. `scripts/` is exempt for the same reason: check-migrations-populated.sh asserts the delete ran.
TRIAGE_FORBIDDEN=(
    "adjudication_verdict|'triage_verdict' — 0009 dropped the column and its five siblings"
    "behavior_adjudication|job.kind = 'triage'"
    "BehaviorAdjudication|the BehaviorTriage* classes"
    "adjudicate\\.js|'triage.js' — the launcher script the sandbox posts to"
)

_scan_triage() {
    grep -rn --include='*.java' --include='*.ts' --include='*.tsx' --include='*.js' --include='*.md' \
        -- "$1" backend classifiers contract docs frontend/src sandbox-runner 2>/dev/null \
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

# The verdict gates that used to live here are gone with their subject. Three of them pinned live
# facts about the grading store -- the single permitted `INSERT INTO verdict` writer, and the
# `source='automatic'` / `timing='online'` values the detection cutover retired -- and Track A
# dropped the table itself. A gate that pins a deleted path does not fail loudly; it passes forever
# while exempting a line nobody can find, which is the rot this file's own header warns about. What
# replaces them is the `FROM|INTO|UPDATE|JOIN verdict` pair of tokens in FORBIDDEN above: they say
# the same thing about a table that is not there, and they cannot go stale.

# The MDC key set is a log-field contract declared TWICE: as constants in LogContext (whose javadoc
# calls itself the single source of truth) and as strings in logback-spring.xml. Nothing above reads
# that file — the scan is Java and TypeScript only — so the signal→classifier rename moved `signalKey`
# to `classifierKey` in both by hand, and a rename that had moved only one of them would have shipped
# green: the field simply stops appearing on every log line, and every Loki query and alert filtering
# on it silently matches nothing. This asserts the javadoc's claim, which is the half a build can see.
# (An external dashboard is the half it cannot — record a key rename in docs/reference/telemetry-naming.md.)
LOGBACK='backend/app/src/main/resources/logback-spring.xml'
LOGCTX='backend/shared/src/main/java/ai/tessary/evals/open/obs/LogContext.java'
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
