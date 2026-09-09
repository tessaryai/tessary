#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# =============================================================================
# Substrate v2 cutover verification battery
# =============================================================================
# Run this inside the maintenance window, after the app is back up and the
# backfill has been given time to converge, and again the next morning. It is
# the executable half of docs/reference/substrate-v2-cutover.md: every check
# below is one the runbook would otherwise ask someone to eyeball at 2am.
#
#   ./scripts/substrate-v2-verify.sh                 # against the deployed stack
#   PSQL="psql -h localhost -p 5433 -U tessary -d tessary" ./scripts/substrate-v2-verify.sh
#   PARITY_SAMPLE=200 ./scripts/substrate-v2-verify.sh
#
# Exit codes: 0 = every check passed. 1 = at least one FAILED. A check can also
# report WARN, which does not fail the run — those are the numbers that need a
# human to say whether they are expected (how much history is unmappable, how
# many spans are unpriced), not invariants.
#
# It reads. It writes nothing, locks nothing, and is safe to run repeatedly
# while ingest is live.
#
# ---------------------------------------------------------------------------
# THIS SCRIPT EXPIRES WITH THE TEARDOWN, AND THAT IS ITS JOB.
# ---------------------------------------------------------------------------
# Sections 1 and 3 read `context`, `observation`, the v1 `trace` and
# `substrate_v2_id_map`. Migration 0083 drops all four. So this runs against a
# database that has NOT yet had the teardown applied — which is exactly when it
# is wanted: a green run here is the evidence that lets 0083 be deployed at all
# (the plan calls it the zero-legacy gate). After 0083 it fails on a missing
# relation, and that failure is the correct answer to "has the teardown already
# happened?".
#
# The parity sections are kept verbatim rather than being rewritten against the
# post-teardown schema. There is nothing left to compare v2 against once the v1
# tables are gone, and a script that quietly stopped comparing while still
# printing PASS would be worse than one that stops running.
# =============================================================================

set -uo pipefail

PSQL="${PSQL:-docker compose -f /opt/evals-platform/docker-compose.yml exec -T postgres psql -U ${POSTGRES_USER:-tessary} -d ${POSTGRES_DB:-tessary}}"
PARITY_SAMPLE="${PARITY_SAMPLE:-50}"
UNPRICED_WARN_PCT="${UNPRICED_WARN_PCT:-5}"

failures=0
warnings=0

q() { $PSQL -qtAX -c "$1"; }

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; warnings=$((warnings + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---------------------------------------------------------------------------
# 1. Backfill convergence — is the copy actually finished?
# ---------------------------------------------------------------------------
# The map is the artifact the copy writes one row per source row into, so
# comparing its per-kind counts against the v1 tables' own counts answers "is it
# done" without trusting the job's own opinion of itself.
section "1. Backfill convergence"

# traces and observations ARE copied one-for-one, so "every source row is mapped" is the
# check for those two.
for pair in "trace:trace_v2:trace" "observation:span:observation"; do
  src="${pair%%:*}"; rest="${pair#*:}"; dst="${rest%%:*}"; kind="${rest#*:}"
  src_n=$(q "SELECT count(*) FROM ${src}")
  map_n=$(q "SELECT count(*) FROM substrate_v2_id_map WHERE old_kind = '${kind}'")
  dst_n=$(q "SELECT count(*) FROM ${dst}")
  if [ "$map_n" -ge "$src_n" ]; then
    pass "${src}: ${src_n} source rows, ${map_n} mapped, ${dst_n} now in ${dst}"
  else
    behind=$((src_n - map_n))
    fail "${src}: ${behind} of ${src_n} source rows not yet mapped — the copy has not converged"
  fi
done

# CONTEXTS ARE NOT ONE-FOR-ONE, so counting them against the map is not the check. Only
# kind='session' contexts become sessions, and the traces phase maps a trace's own
# turn/conversation context; every other context — the turns and conversations that are not a
# trace head — is deliberately never mapped, because v2 has nothing for it to become. On any
# real project those are the majority, so comparing count(context) against the map would fail
# on a copy that has fully converged. The exact invariant is per-kind:
unmapped_sessions=$(q "SELECT count(*) FROM context c
                        WHERE c.kind = 'session' AND coalesce(c.external_id, '') <> ''
                          AND NOT EXISTS (SELECT 1 FROM substrate_v2_id_map m
                                           WHERE m.old_kind = 'context' AND m.old_id = c.id)")
ctx_n=$(q "SELECT count(*) FROM context WHERE kind = 'session' AND coalesce(external_id, '') <> ''")
sess_n=$(q "SELECT count(*) FROM session")
if [ "$unmapped_sessions" -eq 0 ]; then
  pass "context: ${ctx_n} session contexts with a producer id, all mapped, ${sess_n} now in session"
else
  fail "${unmapped_sessions} of ${ctx_n} session contexts not yet mapped — the copy has not converged"
fi
# A session context with no external_id has no producer identity and is skipped by design
# (no synthesized sessions); it is reported, not failed on.
idless=$(q "SELECT count(*) FROM context WHERE kind = 'session' AND coalesce(external_id, '') = ''")
if [ "$idless" -gt 0 ]; then
  warn "${idless} session contexts carry no external_id — skipped by design, their traces are anonymous"
fi

# The one that has to be exact on the other side of the map:
unmapped_spans=$(q "SELECT count(*) FROM observation o
                     WHERE NOT EXISTS (SELECT 1 FROM substrate_v2_id_map m
                                        WHERE m.old_kind = 'observation' AND m.old_id = o.id)")
if [ "$unmapped_spans" -eq 0 ]; then
  pass "every v1 observation has a map entry"
else
  fail "${unmapped_spans} v1 observations have no map entry — spans phase incomplete"
fi

# ---------------------------------------------------------------------------
# 2. Rollup settle probe — the §7.3/§7.4 invariant
# ---------------------------------------------------------------------------
# A settled trace is one the worker recomputed while nothing was due. A row that
# claims BOTH is settled and has work outstanding is the settle protocol having
# been violated: it is reporting totals as final while a span it has not counted
# is waiting to be rolled in.
section "2. Rollup settle invariant"

dishonest=$(q "SELECT count(*) FROM trace_v2 WHERE is_settled AND rollup_due_at IS NOT NULL")
if [ "$dishonest" -eq 0 ]; then
  pass "no trace claims is_settled with a rollup still due"
else
  fail "${dishonest} traces are is_settled with rollup_due_at set — settle protocol violated"
fi

# Queue depth and the oldest overdue trace. Both are steady-state small; a queue
# that only grows is a worker that is not running or cannot keep up.
depth=$(q "SELECT count(*) FROM trace_v2 WHERE rollup_due_at IS NOT NULL")
overdue=$(q "SELECT coalesce(round(extract(epoch FROM now() - min(rollup_due_at))), 0)
               FROM trace_v2 WHERE rollup_due_at IS NOT NULL AND rollup_due_at < now()")
if [ "$overdue" -lt 300 ]; then
  pass "rollup queue depth ${depth}, oldest overdue ${overdue}s"
else
  fail "rollup queue depth ${depth}, oldest overdue ${overdue}s — the worker is behind or stopped"
fi

# ---------------------------------------------------------------------------
# 3. Parity spot-check — do the copied numbers match the originals?
# ---------------------------------------------------------------------------
# N random backfilled traces, compared on the two numbers a reader actually sees:
# how many spans the trace has, and how many tokens it burned. v1 had no rollup
# columns, so the reference side is the same read-time arithmetic the old traces
# list did — which is exactly the arithmetic this migration deleted, computed one
# last time to check its replacement.
#
# Only SETTLED traces are compared: an unsettled one is legitimately mid-rollup,
# and comparing it would be measuring the clock.
section "3. Parity spot-check (${PARITY_SAMPLE} settled backfilled traces)"

parity=$(q "
  WITH sample AS (
      SELECT m.old_id AS v1_id, m.new_trace_id AS v2_id, m.project_id
        FROM substrate_v2_id_map m
        JOIN trace_v2 t ON t.project_id = m.project_id AND t.id = m.new_trace_id
       WHERE m.old_kind = 'trace' AND m.new_trace_id IS NOT NULL AND t.is_settled
       ORDER BY random() LIMIT ${PARITY_SAMPLE}
  ), v1 AS (
      SELECT s.v1_id,
             count(o.id)                                     AS spans,
             coalesce(sum((o.usage->>'input_tokens')::bigint), 0)
           + coalesce(sum((o.usage->>'output_tokens')::bigint), 0) AS tokens
        FROM sample s LEFT JOIN observation o ON o.trace_id = s.v1_id
       GROUP BY s.v1_id
  ), v2 AS (
      SELECT s.v1_id, t.span_count AS spans,
             coalesce(t.input_tokens, 0) + coalesce(t.output_tokens, 0) AS tokens
        FROM sample s JOIN trace_v2 t ON t.project_id = s.project_id AND t.id = s.v2_id
  )
  SELECT count(*) FILTER (WHERE v1.spans IS DISTINCT FROM v2.spans),
         count(*) FILTER (WHERE v1.tokens IS DISTINCT FROM v2.tokens),
         count(*)
    FROM v1 JOIN v2 USING (v1_id)")

span_diff=$(echo "$parity" | cut -d'|' -f1)
token_diff=$(echo "$parity" | cut -d'|' -f2)
compared=$(echo "$parity" | cut -d'|' -f3)

if [ "${compared:-0}" -eq 0 ]; then
  warn "no settled backfilled traces to compare yet — re-run once the rollup queue drains"
else
  if [ "$span_diff" -eq 0 ]; then
    pass "span counts match on all ${compared} sampled traces"
  else
    fail "${span_diff}/${compared} sampled traces disagree on span count"
  fi
  if [ "$token_diff" -eq 0 ]; then
    pass "token sums match on all ${compared} sampled traces"
  else
    fail "${token_diff}/${compared} sampled traces disagree on token sum"
  fi
fi

# ---------------------------------------------------------------------------
# 4. Lateness sanity — is the §7.6 histogram saying anything alarming?
# ---------------------------------------------------------------------------
# Lateness is measured from the log heartbeat, not the database, so this is the
# database-side proxy for the same question: are spans arriving long after their
# trace was last rolled up, and is any producer's clock running backwards?
section "4. Lateness sanity"

backward=$(q "SELECT count(*) FROM span WHERE ended_at IS NOT NULL AND ended_at < started_at")
if [ "$backward" -eq 0 ]; then
  pass "no span ends before it starts"
else
  warn "${backward} spans end before they start — a producer clock stepped backwards (§6.2, bounded risk)"
fi

late=$(q "SELECT count(*) FROM span s JOIN trace_v2 t ON t.project_id = s.project_id AND t.id = s.trace_id
           WHERE t.rolled_up_at IS NOT NULL AND s.created_at > t.rolled_up_at + interval '1 hour'")
if [ "$late" -eq 0 ]; then
  pass "no span arrived more than an hour after its trace's last rollup"
else
  warn "${late} spans arrived >1h after their trace rolled up — check the §7.4 windows against the histogram"
fi

# ---------------------------------------------------------------------------
# 5. Unpriced rate — eyeball, not an invariant
# ---------------------------------------------------------------------------
# A span with usage the price book could not price is recorded honestly (null
# cost, never zero). A few percent is normal — a model landed before the book
# knew it. A large fraction means the resolver is missing a whole family, and
# every dollar figure on the platform is understated until it is fixed.
section "5. Pricing coverage"

read -r with_usage unpriced <<<"$(q "SELECT count(*) FILTER (WHERE total_tokens IS NOT NULL),
                                            count(*) FILTER (WHERE total_tokens IS NOT NULL
                                                              AND cost_source = 'unpriced')
                                       FROM span" | tr '|' ' ')"
if [ "${with_usage:-0}" -eq 0 ]; then
  warn "no spans carry usage yet — nothing to price"
else
  pct=$((unpriced * 100 / with_usage))
  if [ "$pct" -le "$UNPRICED_WARN_PCT" ]; then
    pass "${unpriced}/${with_usage} spans with usage are unpriced (${pct}%)"
  else
    warn "${unpriced}/${with_usage} spans with usage are unpriced (${pct}%) — check ModelResolver coverage"
  fi
fi

# ---------------------------------------------------------------------------
# 6. Dependent re-key residue — the gate the teardown is held against
# ---------------------------------------------------------------------------
# Rows whose old ids had no map entry keep their legacy vocabulary; readers
# exclude them by filter. The count is not required to be zero. It IS required
# to stop moving before the teardown drops the tables those ids point into.
section "6. Legacy vocabulary residue"

# Four of the five tables this used to sum over -- verdict, annotation, annotation_queue_item and
# label -- were DROPPED by 0016 (Track A), which takes their legacy-vocabulary rows with them. Whole
# tables of residue went away as data loss that was already sanctioned: the rows described gradings of
# a substrate id that no longer resolves. failure_mode_instance is the one carrier left, so this is now
# one count rather than five, and the number it prints is not comparable with one taken before 0016.
residue=$(q "SELECT count(*) FROM failure_mode_instance WHERE subject_kind IN ('context','observation')")
echo "  ---- ${residue} rows still on the legacy subject vocabulary"
echo "       Record this number. It is the teardown's baseline: PR C is gated on it having"
echo "       stopped changing, not on it being zero."

# ---------------------------------------------------------------------------
section "Result"
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%s check(s) FAILED\033[0m, %s warning(s)\n' "$failures" "$warnings"
  exit 1
fi
printf '\033[32mAll checks passed\033[0m, %s warning(s)\n' "$warnings"
