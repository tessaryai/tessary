#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# =============================================================================
# Baseline equivalence proof — a database born from the pinned pre-partition chain
# (plus a declared schema delta) is the same database the open master (plus an
# optional paid overlay) builds today
# =============================================================================
# Built for the 2026-08-15 squash (95 changesets folded into 0000-baseline.sql) and
# generalised for the epic-3 partition commit that follows it, which moves tables
# OUT of 0000-baseline.sql rather than folding files IN — neither is reviewable by
# reading. A 6400-line dump either reproduces the reference chain's end state or it
# quietly doesn't, and the ways it quietly doesn't are exactly the ways nobody
# notices — a DEFAULT that came from an ALTER and not from the CREATE TABLE, an
# index the chain dropped and the photograph kept, a CHECK arm a later changeset
# widened, a seed row whose value a rename moved after it was first inserted. So
# this script builds both databases and diffs them.
#
#   DB A — an empty pgvector:pg16 + the PINNED OLD CHAIN: the changelog at
#          EQCHK_OLD_REV, materialised from git (read out of the object store,
#          never checked out — a rev with uncommitted local changes proves nothing
#          about the tree that will actually ship), plus that same rev's paid
#          overlay when the overlay is in play at all, plus EQCHK_DELTA_FILE's
#          self-checking schema delta on top — the objects the regeneration under
#          proof is allowed to move or change, declared and asserted rather than
#          left to the diff to discover.
#   DB B — an empty pgvector:pg16 + the OPEN master changelog from the working
#          tree, plus — when EQCHK_OVERLAY_DIR is set, or defaulted from an
#          overlay checkout present on disk — the paid overlay's own changelog
#          applied second, against the SAME database, after the open update, so
#          the overlay's floor-pin precondition sees the open ledger already there.
#
# A pass means: no schema difference at all, and the seeded rows are identical.
#
# BOTH SIDES GET THE OVERLAY, OR NEITHER DOES, and which rev each side's overlay
# comes from is what makes the comparison meaningful. A takes OLD_REV's overlay and
# B takes the working tree's, so a run proves the WHOLE database — both lanes,
# composed in apply order, including the anchor FK that spans them — rather than
# proving the open lane and assuming the overlay still fits it. Where a run
# regenerates only one lane, the other lane is identical on both sides and
# contributes nothing to the diff, which is the correct answer, not a gap: it is
# the lane under proof that has to come out byte-identical.
#
# This replaces an asymmetry that was correct exactly once. Before #1074 the paid
# tables lived in the OPEN baseline, so A got them from its open chain and only B
# needed an overlay to match; applying one to A as well would have double-created
# every paid table. #1074 moved them out for good, so from the rev after it
# onwards an A without the overlay is simply a smaller database than B, and every
# paid table lands in the diff as a spurious difference.
#
# The identity mode still exists — EQCHK_DELTA_FILE pointed at an empty file and
# EQCHK_OVERLAY_DIR= forced empty makes A and B the same changelog applied twice,
# which is what proves the harness itself.
#
# WHY EACH RULE EXISTS. Every one of these was a way to get a green diff that proves
# nothing, so none of them is a style preference:
#
#  1. SAME APPLY TOOL BOTH SIDES — liquibase/liquibase:5.0 for A and for B. Apply the old
#     chain with one Liquibase and the baseline with another and any difference in the
#     diff has a second explanation ("that's just tool skew"), which is precisely the
#     explanation a real finding would hide behind. Neither database's changelog ledger is
#     ever read again — both are throwaway — so using the CLI here costs nothing. The
#     app's own SpringLiquibase applies the swapped changelog in Gates 4 and 5, on fresh
#     databases, which is where that question belongs.
#
#  2. pg_dump RUNS INSIDE THE CONTAINERS — `docker exec`, never the host binary. The host
#     is Postgres 18.4 and the servers are 16: a newer pg_dump emits a newer `SET`
#     preamble and formats objects differently, so a host dump is both noisier and,
#     because Phase 1's baseline is a dump pasted into a changeset, actively dangerous —
#     one newer-server `SET` transplanted into a `splitStatements:false` changeset aborts
#     the whole changeset. Same image on both sides means the same pg_dump build on both
#     sides, and the diff can only be about the databases.
#
#  3. THE NORMALIZATION IS DECLARED AND TINY — and it is the whole list:
#       a. the dump preamble (the banner comment and the `SET`/`set_config` block). It is
#          identical on both sides anyway; stripping it just keeps a diff readable.
#       b. `databasechangelog` / `databasechangeloglock`, via pg_dump's own
#          --exclude-table rather than a regex. These are Liquibase's bookkeeping, not the
#          product schema, and their CONTENT necessarily differs (A ran 166 changesets, B
#          ran 1) — but their SHAPE is not something this proof has an opinion about.
#       c. pg_dump's `\restrict` / `\unrestrict` guard pair. Postgres 16.10 wrapped every
#          dump in these psql meta-commands, and their argument is a RANDOM token minted
#          per invocation: dump one database twice and the two files differ on exactly
#          those two lines. They are a property of the dumping session, not of anything in
#          the database, and they are the reason for the control below.
#     NOTHING ELSE. If you find yourself reaching for a fourth rule, stop: what you are
#     about to normalize away is a real difference between the two databases, and the fix
#     belongs in 0000-baseline.sql. A normalizer that grows to fit the diff is a proof
#     that has been edited until it passes.
#
#  3b. THE NORMALIZATION IS ITSELF UNDER TEST. Rule 3 is only safe if it is complete, so
#     before A is compared to B, A is compared to ITSELF — dumped twice, normalized, and
#     required to be byte-identical. That control is what separates "this normalizer
#     removes pg_dump's nondeterminism" from "this normalizer removes whatever was in the
#     way": a rule that is too weak fails the control, and the day a future Postgres adds
#     another per-run token the control fails first and names it, instead of the real diff
#     quietly acquiring noise.
#
#  4. THE DATA DIFF NAMES ITS COLUMNS. The seeds are compared through a projection table
#     (`zz_eqcheck_<table>`) built from a column list spelled out below, so "which columns
#     did this compare?" is answerable by reading rather than by trusting. A column that
#     cannot match by construction is excluded BY NAME with the reason attached and asserted
#     NOT NULL instead (the precedent: old 0018 stamped two singleton rows with `now()` while
#     the baseline transcribed literals; Track A has since dropped both of those tables, so
#     the exclusion list is empty today). `embedding_space` — every column, including 0093's
#     renamed key over its unrenamed id — used to be this rule's one worked example, until
#     0017 (#1116) dropped the table along with the rest of the vector substrate. Nothing
#     platform-seeded survives it, so SEEDED_TABLES is empty and rule 5 below now asserts
#     that emptiness explicitly rather than iterating zero times and reading as a silent PASS.
#
#  5. THE SEEDED-TABLE LIST IS DERIVED, NOT TRUSTED. Before the per-table dumps, both
#     databases are asked which tables have rows at all, and the two answers must match
#     each other AND the declared list. A table the chain seeds and the baseline forgets
#     would otherwise be invisible: nobody diffs a table they didn't think to name. With the
#     declared list empty (see rule 4), the census still runs and both answers are asserted
#     empty by name — a future seed that lands with no matching entry here fails loudly
#     instead of the loop silently having nothing to walk.
#
# Usage:  ./scripts/verify-baseline-equivalence.sh [--keep]
#           --keep   leave both containers running; prints the psql lines
#         EQCHK_OLD_REV=<rev>       commit whose tree holds the pre-partition chain
#                                   (default HEAD — the placeholder before the
#                                   partition commit, which pins its own parent's
#                                   sha the same way the squash pinned one)
#         EQCHK_DELTA_FILE=<path>   self-checking SQL applied to A after the old
#                                   chain, before the schema dump (default
#                                   scripts/lib/partition-expected-delta.sql, empty
#                                   — a no-op — until the partition commit fills it
#                                   in with the objects it moves)
#         EQCHK_OVERLAY_DIR=<dir>   a paid module's resources root, holding
#                                   db/changelog/paid/db.changelog-paid.yaml —
#                                   applied second, against the same database as the
#                                   open master, on BOTH sides: this directory is B's
#                                   overlay, and A gets OLD_REV's own (default: the
#                                   paid overlay/db's resources root, if that tree
#                                   exists in this checkout; pass EQCHK_OVERLAY_DIR=
#                                   empty to force BOTH sides open-only)
#
# Requires: docker and a Postgres JDBC driver in ~/.m2 (any backend build puts one there).
# Runs two small Postgres containers and applies the whole old chain; several minutes on
# one CPU.
# Leaves nothing behind.
#
# Not wired into `task check`: it is the gate for a squash, and a squash happens once.
# Checked in so the next one can rerun it instead of reinventing it.
# =============================================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOURCES="backend/core/src/main/resources"
MASTER="$RESOURCES/db/changelog/db.changelog-master.yaml"
CHANGES="$RESOURCES/db/changelog/changes"

# The pre-partition placeholder. There is no fixed pre-partition commit to pin until the
# epic-3 partition commit exists, so before then this runs as an IDENTITY check — the same
# open changelog materialised twice, once via git archive at HEAD and once from the working
# tree — which is what proves the harness before it has a real delta to prove. The partition
# commit replaces this default with a sha the same way the 2026-08-15 squash pinned
# 3b3f43b783e65dd82626c8b2fc9b51e3743acaab (its own parent, the last tree holding the old
# 96-file chain intact): a sha, never a symbolic ref, so a later rebase produces a clear
# failure below (`git cat-file -e`) instead of a quietly different comparison.
OLD_REV="${EQCHK_OLD_REV:-HEAD}"

# Self-checking schema delta applied to A after the old chain, before the schema dump — the
# objects a partition commit is allowed to move or change, declared by name instead of left
# to the diff to discover. Empty by default: a no-op, which is exactly what keeps this script
# green as the identity check it is before #1074 lands. See partition-expected-delta.sql's
# own header for the DO-block format a future caller (the partition commit) must use.
DELTA_FILE="${EQCHK_DELTA_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/partition-expected-delta.sql}"

# A paid overlay changelog directory, applied second — against the SAME database as the open
# master, after it, so the overlay floor-pin's `evals:0000-baseline` precondition sees the open
# ledger already there. Applied to BOTH databases: this working-tree directory is B's, and A
# gets the same overlay as OLD_REV had it (see "materialise the old chain" below). Defaults to the overlay module's resources root (found by its db.changelog-paid.yaml) when that tree
# is present in the checkout — the same file-exists probe backend/pom.xml uses to activate the
# paid profile (D2) — because since #1074 the paid tables live ONLY in the overlay's own
# baseline: a checkout with the overlay present but no overlay applied to B is not the open
# export B is supposed to model, it is just B missing tables A still has. Pass
# EQCHK_OVERLAY_DIR= (empty) to force BOTH sides open-only regardless — the shape that ships
# once the paid folder is filtered out of the public export.
DEFAULT_OVERLAY_DIR=""
PAID_MASTER="$(find "$ROOT" \( -path '*/node_modules' -o -path '*/target' -o -path '*/.claude' -o -path '*/.crew' \) -prune -o -name 'db.changelog-paid.yaml' -print 2>/dev/null | head -1)"
if [ -n "$PAID_MASTER" ]; then
  DEFAULT_OVERLAY_DIR="${PAID_MASTER%/db/changelog/paid/db.changelog-paid.yaml}"
fi
OVERLAY_DIR="${EQCHK_OVERLAY_DIR-$DEFAULT_OVERLAY_DIR}"

CONTAINER_A="tessary-eqcheck-old"
CONTAINER_B="tessary-eqcheck-new"
NETWORK="tessary-eqcheck-net"
# Deliberately not 5433 (dev stack), 55432 (check-migrations-populated.sh) or 55555
# (restore-drill.sh): this must be runnable while any of those is up.
PORT_A="${EQCHK_PORT_A:-55437}"
PORT_B="${EQCHK_PORT_B:-55438}"
PG_IMAGE="${EQCHK_PG_IMAGE:-pgvector/pgvector:pg16}"
LB_IMAGE="${EQCHK_LB_IMAGE:-liquibase/liquibase:5.0}"
DB="tessary"
PASSWORD="eqcheck"
KEEP=0

# ---------------------------------------------------------------- the declared comparison
# Rule 4. Per seeded table: the columns this proof compares, in order. An excluded column
# carries its reason here and nowhere else.
#
# It used to be three tables, then one. grading_spend_watermark and grading_breaker each
# seeded one singleton row whose updated_at the old chain stamped now() and the baseline
# transcribed as a literal, so that column was EXCLUDED by name and asserted NOT NULL instead;
# Epic 8 Track A (0016, PR #1095) dropped both tables before the partition, leaving
# embedding_space (0000's five platform rows) as the sole seeded table. 0017 (#1116) then
# dropped embedding_space itself along with the rest of the vector substrate, so nothing is
# platform-seeded any more — SEEDED_TABLES is empty, and rule 5's census below asserts that
# emptiness explicitly rather than iterating zero times. The NOT_NULL_ASSERTS mechanism stays,
# empty, for the next seed whose column has to be excluded rather than compared.
SEEDED_TABLES=""
# Columns excluded above, asserted present rather than compared. None today.
NOT_NULL_ASSERTS=""

[ "${1:-}" != "--keep" ] || KEEP=1

die() {
  echo "verify-baseline-equivalence: FAIL — $*" >&2
  exit 1
}

WORK="$(mktemp -d -t tessary-eqcheck.XXXXXX)"
cleanup() {
  if [ "$KEEP" != 1 ]; then
    rm -rf "$WORK"
    docker rm -f "$CONTAINER_A" "$CONTAINER_B" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- preflight

cd "$ROOT"
command -v docker >/dev/null || die "docker is required"
command -v git >/dev/null || die "git is required"
[ -f "$MASTER" ] || die "no master changelog at $MASTER"
[ -f "$CHANGES/0000-baseline.sql" ] || die "no baseline changeset at $CHANGES/0000-baseline.sql"
[ -f "$DELTA_FILE" ] || die "EQCHK_DELTA_FILE ${DELTA_FILE} does not exist"
if [ -n "$OVERLAY_DIR" ]; then
  OVERLAY_MASTER="$OVERLAY_DIR/db/changelog/paid/db.changelog-paid.yaml"
  [ -f "$OVERLAY_MASTER" ] || die "EQCHK_OVERLAY_DIR ${OVERLAY_DIR} has no db/changelog/paid/db.changelog-paid.yaml — pass a paid module's resources root"
fi

DRIVER="$(find "$HOME/.m2/repository/org/postgresql/postgresql" -name 'postgresql-*.jar' 2>/dev/null \
  | grep -v -- '-sources' | sort -V | tail -1 || true)"
[ -n "$DRIVER" ] || die "no Postgres JDBC driver under ~/.m2 — build the backend once (mvn -f backend/pom.xml -q -DskipTests package) so the driver is resolved"

git cat-file -e "${OLD_REV}^{commit}" 2>/dev/null || die "OLD_REV ${OLD_REV} is not a commit in this repo — if the branch was rebased, pass EQCHK_OLD_REV=<the pre-partition commit>"

# Reads a Liquibase master changelog's own include list back out as a sorted set of the
# repo-relative paths it declares — never a count, so an added-and-removed file that happens
# to leave the total unchanged cannot hide from the comparison below.
included_set() {
  local master="$1" prefix="$2"
  # `|| true`: an overlay floor-pin with no includes yet (today's paid db module, before
  # #1074) is a legitimate empty set, not a failure — grep's no-match exit code must not trip
  # `set -e` on the assignment this feeds.
  { grep "file: ${prefix}" "$master" || true; } | sed -E "s#.*file:[[:space:]]*(${prefix}[^\"' ]+).*#\1#" | sort -u
}

# ---------------------------------------------------------------- materialise the old chain
# git archive, not a checkout: only the changelog tree is needed and nothing in the working
# tree — anyone's, including another agent's uncommitted work — may be touched to get it.

mkdir -p "$WORK/old"
git archive "$OLD_REV" -- "$RESOURCES/db" | tar -x -C "$WORK/old"
OLD_RESOURCES="$WORK/old/$RESOURCES"
[ -f "$OLD_RESOURCES/db/changelog/db.changelog-master.yaml" ] || die "git archive did not produce a master changelog under $OLD_RESOURCES"

# A's expected file SET, derived from OLD_REV's own master changelog rather than a hardcoded
# count — "every changeset in that tree ran" is the property under test, and a hardcoded
# number only ever proves "the number I typed matches itself". Compared as a set against what
# git actually has on disk at that rev, so a file the changelog declares but the tree lacks
# (or vice versa) fails here, before either database exists.
OLD_INCLUDED="$(included_set "$OLD_RESOURCES/db/changelog/db.changelog-master.yaml" 'db/changelog/changes/')"
OLD_ONDISK="$(git ls-tree -r --name-only "$OLD_REV" -- "$CHANGES" | grep '\.sql$' | sed "s#^${RESOURCES}/##" | sort -u)"
[ "$OLD_INCLUDED" = "$OLD_ONDISK" ] || die "$(printf 'OLD_REV %s: the master changelog and the changes/ directory disagree on which files exist —\n%s' "$OLD_REV" "$(diff <(printf '%s\n' "$OLD_INCLUDED") <(printf '%s\n' "$OLD_ONDISK"))")"
OLD_INCLUDED_FILES="$(printf '%s\n' "$OLD_INCLUDED" | grep -c . || true)"

# A's overlay, materialised from OLD_REV the same way and for the same reason. Found by its
# changelog FILENAME in that rev's tree, never by a hardcoded path — the open boundary forbids
# a scripts/*.sh naming the overlay's location, and the working-tree default above is found the
# same way. A rev with no overlay in its tree, while the working tree has one, is a comparison
# this script cannot make lane for lane, so it DIES rather than quietly applying A open-only and
# reporting every paid table as a difference — the exact failure this whole block exists to fix.
# Forcing EQCHK_OVERLAY_DIR= empty is how you compare two open lanes on purpose.
OLD_OVERLAY_DIR=""
OLD_OVERLAY_CHANGESETS=0
if [ -n "$OVERLAY_DIR" ]; then
  OLD_PAID_MASTER="$(git ls-tree -r --name-only "$OLD_REV" | grep '/db/changelog/paid/db.changelog-paid.yaml$' | head -1 || true)"
  [ -n "$OLD_PAID_MASTER" ] \
    || die "OLD_REV ${OLD_REV} has no paid overlay changelog in its tree, but the working tree has one — the two sides cannot be compared lane for lane; pass EQCHK_OVERLAY_DIR= to force both open-only"
  OLD_OVERLAY_REL="${OLD_PAID_MASTER%/db/changelog/paid/db.changelog-paid.yaml}"
  git archive "$OLD_REV" -- "$OLD_OVERLAY_REL/db" | tar -x -C "$WORK/old"
  OLD_OVERLAY_DIR="$WORK/old/$OLD_OVERLAY_REL"
  [ -f "$OLD_OVERLAY_DIR/db/changelog/paid/db.changelog-paid.yaml" ] \
    || die "git archive did not produce an overlay master changelog under $OLD_OVERLAY_DIR"
  # Cross-checked against its own directory exactly as B's overlay is below — a file the archived
  # changelog declares but the archived tree lacks (or the reverse) fails here, before either
  # database exists, rather than surfacing later as an unexplained ledger-height mismatch.
  OLD_OVERLAY_INCLUDED="$(included_set "$OLD_OVERLAY_DIR/db/changelog/paid/db.changelog-paid.yaml" 'db/changelog/paid/')"
  OLD_OVERLAY_ONDISK="$(find "$OLD_OVERLAY_DIR/db/changelog/paid" -name '*.sql' | sed "s#^${OLD_OVERLAY_DIR}/##" | sort -u)"
  [ "$OLD_OVERLAY_INCLUDED" = "$OLD_OVERLAY_ONDISK" ] \
    || die "$(printf 'OLD_REV %s: the overlay changelog and its own changes directory disagree on which files exist —\n%s' "$OLD_REV" "$(diff <(printf '%s\n' "$OLD_OVERLAY_INCLUDED") <(printf '%s\n' "$OLD_OVERLAY_ONDISK"))")"
  OLD_OVERLAY_CHANGESETS="$(grep -rh '^--changeset' "$OLD_OVERLAY_DIR/db/changelog/paid" 2>/dev/null | wc -l | tr -d ' ' || true)"
fi

# The ledger's expected height, counted out of the tree that is about to be applied. Sanity
# check: every included file must carry at least one changeset, or a file present but empty
# would inflate OLD_INCLUDED_FILES without inflating the ledger it is supposed to explain.
OLD_CHANGESETS="$(grep -rh '^--changeset' "$OLD_RESOURCES/db/changelog/changes/" | wc -l | tr -d ' ')"
[ "$OLD_CHANGESETS" -ge "$OLD_INCLUDED_FILES" ] \
  || die "counted ${OLD_CHANGESETS} changesets across ${OLD_INCLUDED_FILES} files — fewer changesets than files, so at least one included file declares none"
OLD_TOTAL_CHANGESETS=$((OLD_CHANGESETS + OLD_OVERLAY_CHANGESETS))

# B's expected file set and changeset height, the same way, out of the working tree.
NEW_INCLUDED="$(included_set "$MASTER" 'db/changelog/changes/')"
NEW_ONDISK="$(find "$CHANGES" -name '*.sql' | sed "s#^${RESOURCES}/##" | sort -u)"
[ "$NEW_INCLUDED" = "$NEW_ONDISK" ] || die "the working tree's master changelog and its changes/ directory disagree on which files exist"
NEW_CHANGESETS="$(grep -rh '^--changeset' "$CHANGES" | wc -l | tr -d ' ')"

# The overlay, when set, adds to B's expected ledger height the same derived way — never a
# bare count either, and its includes are checked against its own directory just like the
# open master's are checked against changes/.
OVERLAY_CHANGESETS=0
if [ -n "$OVERLAY_DIR" ]; then
  OVERLAY_INCLUDED="$(included_set "$OVERLAY_MASTER" 'db/changelog/paid/')"
  # Recursive, not -maxdepth 1: the overlay mirrors the open changes/ layout with a nested
  # db/changelog/paid/changes/ subdirectory (see check-migrations-populated.sh), so a shallow
  # find would never see a real changeset file and would falsely disagree with
  # OVERLAY_INCLUDED, which correctly captures nested paths via included_set's grep. Same
  # relative-path-preserving strip as NEW_ONDISK above, just anchored at OVERLAY_DIR.
  OVERLAY_ONDISK="$(find "$OVERLAY_DIR/db/changelog/paid" -name '*.sql' | sed "s#^${OVERLAY_DIR}/##" | sort -u)"
  [ "$OVERLAY_INCLUDED" = "$OVERLAY_ONDISK" ] || die "EQCHK_OVERLAY_DIR ${OVERLAY_DIR}: db.changelog-paid.yaml and db/changelog/paid/ disagree on which files exist"
  OVERLAY_CHANGESETS="$(grep -rh '^--changeset' "$OVERLAY_DIR/db/changelog/paid" 2>/dev/null | wc -l | tr -d ' ' || true)"
fi
NEW_TOTAL_CHANGESETS=$((NEW_CHANGESETS + OVERLAY_CHANGESETS))

echo "verify-baseline-equivalence: A = ${OLD_INCLUDED_FILES} changeset file(s)/${OLD_CHANGESETS} changeset(s) at $(git rev-parse --short "$OLD_REV")$([ -n "$OLD_OVERLAY_DIR" ] && echo " + ${OLD_OVERLAY_CHANGESETS} overlay = ${OLD_TOTAL_CHANGESETS}"), B = ${NEW_CHANGESETS} changeset(s)$([ -n "$OVERLAY_DIR" ] && echo " + ${OVERLAY_CHANGESETS} overlay = ${NEW_TOTAL_CHANGESETS}") in the working tree"

# ---------------------------------------------------------------- two throwaway databases

docker rm -f "$CONTAINER_A" "$CONTAINER_B" >/dev/null 2>&1 || true
docker network create "$NETWORK" >/dev/null 2>&1 || true

start_pg() {
  local name="$1" port="$2"
  docker run --rm -d --name "$name" --network "$NETWORK" \
    -e POSTGRES_PASSWORD="$PASSWORD" -e POSTGRES_DB="$DB" \
    -p "${port}:5432" "$PG_IMAGE" >/dev/null || die "could not start $PG_IMAGE on :${port}"
  local i
  for i in $(seq 1 90); do
    docker exec "$name" pg_isready -U postgres >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "postgres ($name) never became ready on :${port}"
}

start_pg "$CONTAINER_A" "$PORT_A"
start_pg "$CONTAINER_B" "$PORT_B"

# Rule 1: one function, both sides, same image, same flags. `changelog` defaults to the open
# master's own relative path; the overlay call below passes the paid one instead, mirroring
# check-migrations-populated.sh's liquibase_update, which takes the same second argument for
# the same reason — one function driving more than one changelog against the same container.
liquibase_update() {
  local label="$1" container="$2" resources="$3" changelog="${4:-db/changelog/db.changelog-master.yaml}"
  local log="$WORK/liquibase-${label// /-}.log"
  if ! docker run --rm --network "$NETWORK" \
      -v "$resources:/liquibase/changelog:ro" \
      -v "$DRIVER:/liquibase/lib/postgresql.jar:ro" \
      "$LB_IMAGE" --headless=true --logLevel=warning \
      --url="jdbc:postgresql://${container}:5432/${DB}" --username=postgres --password="$PASSWORD" \
      --searchPath=/liquibase/changelog --changeLogFile="$changelog" \
      update >"$log" 2>&1; then
    echo "--- liquibase output (${label}, stack frames elided) ---" >&2
    { grep -v '^[[:space:]]*at ' "$log" || true; } | tail -40 >&2
    die "the ${label} changelog did not apply"
  fi
}

echo "verify-baseline-equivalence: applying the old chain to A (:${PORT_A})"
liquibase_update "old chain" "$CONTAINER_A" "$OLD_RESOURCES"
if [ -n "$OLD_OVERLAY_DIR" ]; then
  echo "verify-baseline-equivalence: applying OLD_REV's paid overlay to A (:${PORT_A}), after its open chain so its floor-pin sees it"
  liquibase_update "old paid overlay" "$CONTAINER_A" "$OLD_OVERLAY_DIR" "db/changelog/paid/db.changelog-paid.yaml"
fi
echo "verify-baseline-equivalence: applying the open master to B (:${PORT_B})"
liquibase_update "open master" "$CONTAINER_B" "$ROOT/$RESOURCES"
if [ -n "$OVERLAY_DIR" ]; then
  echo "verify-baseline-equivalence: applying the paid overlay to B (:${PORT_B}), after the open master so its floor-pin sees it"
  liquibase_update "paid overlay" "$CONTAINER_B" "$OVERLAY_DIR" "db/changelog/paid/db.changelog-paid.yaml"
fi

q() {
  docker exec "$1" psql -U postgres -d "$DB" -tAc "$2" 2>/dev/null | tr -d '[:space:]'
}

# The declared schema delta — the objects the regeneration under proof is allowed to move or
# change — applied to A after the old chain and before the schema dump. An empty file is a
# no-op on `psql -f` and a legitimate, stronger claim than a filled one: it tolerates no
# difference at all. See the delta file's own header for what it declares today.
echo "verify-baseline-equivalence: applying the expected schema delta to A ($(basename "$DELTA_FILE"))"
docker exec -i "$CONTAINER_A" psql -v ON_ERROR_STOP=1 -U postgres -d "$DB" -f - <"$DELTA_FILE" \
  || die "the expected schema delta ($DELTA_FILE) failed against A — see its own self-checks above for which object"

LEDGER_A="$(q "$CONTAINER_A" 'SELECT count(*) FROM databasechangelog')"
LEDGER_B="$(q "$CONTAINER_B" 'SELECT count(*) FROM databasechangelog')"
[ "$LEDGER_A" = "$OLD_TOTAL_CHANGESETS" ] || die "A's ledger has ${LEDGER_A} rows, expected ${OLD_TOTAL_CHANGESETS} — the old chain (plus OLD_REV's overlay, if any) did not fully apply"
[ "$LEDGER_B" = "$NEW_TOTAL_CHANGESETS" ] || die "B's ledger has ${LEDGER_B} rows, expected ${NEW_TOTAL_CHANGESETS} — the open master (plus the overlay, if any) did not fully apply"
echo "verify-baseline-equivalence: ledgers — A ${LEDGER_A} rows, B ${LEDGER_B} rows"

# ---------------------------------------------------------------- 1. the schema diff
# Rule 2 (dump inside the container) and rule 3 (the entire normalization).

dump_schema() {
  local container="$1" out="$2"
  docker exec "$container" pg_dump -U postgres -d "$DB" \
    --schema-only --no-owner --no-privileges \
    --exclude-table=public.databasechangelog --exclude-table=public.databasechangeloglock \
    >"$out.raw" || die "pg_dump --schema-only failed inside ${container}"
  normalize_dump "$out.raw" "$out" "${container} schema"
}

# Rules 3a and 3c, in one place, used by every dump this script takes.
normalize_dump() {
  local raw="$1" out="$2" what="$3"
  # 3a: drop the preamble — everything through the last `SET` line before the first real
  # object. The anchor is asserted rather than assumed, so an unrecognised preamble stops
  # the proof instead of silently landing in the diff. (This also takes `\restrict`, which
  # pg_dump emits above it; `\unrestrict` closes the file and is dropped by 3c.)
  grep -q '^SET row_security = off;$' "$raw" \
    || die "the pg_dump preamble for ${what} does not end in the expected 'SET row_security = off;' — check the normalization before trusting this diff"
  sed -e '1,/^SET row_security = off;$/d' -e '/^\\unrestrict /d' -e '/^\\restrict /d' "$raw" >"$out"
}

echo
echo "verify-baseline-equivalence: dumping schemas (inside the pg16 containers)"
dump_schema "$CONTAINER_A" "$WORK/schema-a.sql"
dump_schema "$CONTAINER_B" "$WORK/schema-b.sql"

SCHEMA_FAIL=0

# Rule 3b: the control. A against itself, through the same normalizer, before A is allowed
# to be compared against B.
dump_schema "$CONTAINER_A" "$WORK/schema-a-control.sql"
if diff -u "$WORK/schema-a.sql" "$WORK/schema-a-control.sql" >"$WORK/control.diff"; then
  echo "  PASS  control — two dumps of the SAME database normalize identically, so the diff below is about databases"
else
  SCHEMA_FAIL=1
  echo "  FAIL  control — two dumps of the SAME database differ after normalization; pg_dump has a per-run token this script does not know about, and no diff below can be trusted until it does:"
  sed 's/^/        /' "$WORK/control.diff"
fi
if diff -u "$WORK/schema-a.sql" "$WORK/schema-b.sql" >"$WORK/schema.diff"; then
  echo "  PASS  schema diff empty ($(wc -l <"$WORK/schema-a.sql" | tr -d ' ') lines compared, old chain vs baseline)"
else
  SCHEMA_FAIL=1
  echo "  FAIL  schema diff is NOT empty — the baseline does not reproduce the chain's schema:"
  sed 's/^/        /' "$WORK/schema.diff"
fi

# ---------------------------------------------------------------- 2. the seeded-table census
# Rule 5: ask both databases which tables have rows, rather than trusting the declared list.

census() {
  docker exec "$1" psql -U postgres -d "$DB" -tAF' ' -c "
    SELECT c.relname, s.n
      FROM pg_class c
      JOIN pg_namespace ns ON ns.oid = c.relnamespace
      CROSS JOIN LATERAL (
        SELECT (xpath('/row/c/text()',
                      query_to_xml(format('SELECT count(*) AS c FROM public.%I', c.relname),
                                   false, true, '')))[1]::text::bigint AS n
      ) s
     WHERE ns.nspname = 'public'
       AND c.relkind = 'r'
       AND c.relname NOT LIKE 'databasechangelog%'
       AND s.n > 0
     ORDER BY c.relname" 2>/dev/null | sed '/^[[:space:]]*$/d'
}

echo
echo "verify-baseline-equivalence: seeded-table census"
census "$CONTAINER_A" >"$WORK/census-a.txt"
census "$CONTAINER_B" >"$WORK/census-b.txt"
sed 's/^/        A  /' "$WORK/census-a.txt"
sed 's/^/        B  /' "$WORK/census-b.txt"

CENSUS_FAIL=0
if ! diff -u "$WORK/census-a.txt" "$WORK/census-b.txt" >"$WORK/census.diff"; then
  CENSUS_FAIL=1
  echo "  FAIL  the two databases do not carry rows in the same tables:"
  sed 's/^/        /' "$WORK/census.diff"
else
  echo "  PASS  both databases carry rows in exactly the same tables, with the same counts"
fi

DECLARED="$(printf '%s\n' $SEEDED_TABLES | sort)"
OBSERVED="$(cut -d' ' -f1 "$WORK/census-a.txt" | sort)"
if [ "$DECLARED" != "$OBSERVED" ]; then
  CENSUS_FAIL=1
  echo "  FAIL  the old chain seeds tables this script does not compare (declared vs observed):"
  diff -u <(printf '%s\n' "$DECLARED") <(printf '%s\n' "$OBSERVED") | sed 's/^/        /' || true
elif [ -z "$SEEDED_TABLES" ]; then
  # Both empty, asserted by name rather than left as a loop that happens to iterate zero
  # times: since 0017 (#1116) dropped embedding_space, the platform-seeded row count on a
  # fresh install is zero, and this is the one place that fact is checked rather than assumed.
  echo "  PASS  neither database seeds any table — SEEDED_TABLES is empty and the census agrees"
else
  echo "  PASS  the declared seeded-table list is exactly what the old chain leaves rows in"
fi

# ---------------------------------------------------------------- 3. the per-table data diff
# Rule 4. The projection table is what makes "compared on a declared column subset" literal
# rather than a claim: pg_dump has no column selector, so the subset becomes a table and
# pg_dump dumps that. Built AFTER the census and AFTER the schema dump, so it can neither
# hide a seeded table nor appear in the schema comparison.

project() {
  local container="$1" table="$2" cols="$3"
  docker exec "$container" psql -v ON_ERROR_STOP=1 -U postgres -d "$DB" -q -c \
    "CREATE TABLE public.zz_eqcheck_${table} AS SELECT ${cols} FROM public.${table} ORDER BY id" \
    >/dev/null || die "could not build the comparison projection for ${table} in ${container}"
}

dump_projection() {
  local container="$1" table="$2" out="$3"
  docker exec "$container" pg_dump -U postgres -d "$DB" \
    --data-only --no-owner --no-privileges --table="public.zz_eqcheck_${table}" \
    >"$out.raw" || die "pg_dump --data-only failed for ${table} inside ${container}"
  # The same normalizer, and it asserts its anchor for a sharper reason here: sed's range
  # delete against a pattern that never matches would empty BOTH files and read as a pass.
  normalize_dump "$out.raw" "$out" "${table} data from ${container}"
}

echo
echo "verify-baseline-equivalence: seed data, per table, on the declared columns"
DATA_FAIL=0
if [ -z "$SEEDED_TABLES" ]; then
  echo "  PASS  no seeded tables declared — nothing to project or diff (see rule 5's census above)"
fi
for t in $SEEDED_TABLES; do
  eval "cols=\$COLUMNS_${t}"
  project "$CONTAINER_A" "$t" "$cols"
  project "$CONTAINER_B" "$t" "$cols"
  dump_projection "$CONTAINER_A" "$t" "$WORK/data-a-$t.sql"
  dump_projection "$CONTAINER_B" "$t" "$WORK/data-b-$t.sql"
  if diff -u "$WORK/data-a-$t.sql" "$WORK/data-b-$t.sql" >"$WORK/data-$t.diff"; then
    printf '  PASS  %-24s (%s)\n' "$t" "$cols"
  else
    DATA_FAIL=1
    printf '  FAIL  %-24s rows differ:\n' "$t"
    sed 's/^/        /' "$WORK/data-$t.diff"
  fi
done

# The excluded columns: asserted present, so "not compared" never quietly means "not there".
for pair in $NOT_NULL_ASSERTS; do
  t="${pair%%:*}"; c="${pair##*:}"
  a="$(q "$CONTAINER_A" "SELECT count(*) FROM public.${t} WHERE ${c} IS NULL")"
  b="$(q "$CONTAINER_B" "SELECT count(*) FROM public.${t} WHERE ${c} IS NULL")"
  if [ "$a" = "0" ] && [ "$b" = "0" ]; then
    printf '  PASS  %-24s %s excluded (A stamps now(), B a literal) but non-null on both sides\n' "$t" "$c"
  else
    DATA_FAIL=1
    printf '  FAIL  %-24s %s is NULL on A in %s row(s), on B in %s row(s)\n' "$t" "$c" "$a" "$b"
  fi
done

# ---------------------------------------------------------------- summary

echo
if [ "$KEEP" = 1 ]; then
  echo "verify-baseline-equivalence: containers kept — PGPASSWORD=${PASSWORD} psql -h localhost -p ${PORT_A} -U postgres -d ${DB}   (A, old chain)"
  echo "verify-baseline-equivalence: containers kept — PGPASSWORD=${PASSWORD} psql -h localhost -p ${PORT_B} -U postgres -d ${DB}   (B, new baseline)"
  echo "verify-baseline-equivalence: dumps kept in ${WORK}"
fi
if [ "$SCHEMA_FAIL" != 0 ] || [ "$CENSUS_FAIL" != 0 ] || [ "$DATA_FAIL" != 0 ]; then
  die "B is not equivalent to A plus the declared delta — fix the schema, do not widen the normalization"
fi
# The three values a divergence-log entry for this run needs: what A was pinned to, what tree
# actually ran the proof, and which version of the proof itself ran it.
echo "verify-baseline-equivalence: PASS — B (open master$([ -n "$OVERLAY_DIR" ] && echo " + paid overlay")) is identical in schema and seed data to A (${OLD_INCLUDED_FILES}-file open chain$([ -n "$OLD_OVERLAY_DIR" ] && echo " + OLD_REV's paid overlay") + declared delta, ${OLD_TOTAL_CHANGESETS} ledger rows)"
echo "verify-baseline-equivalence: EQCHK_OLD_REV=${OLD_REV} (resolved $(git rev-parse --short "$OLD_REV")), HEAD=$(git rev-parse HEAD), script=$(git hash-object "${BASH_SOURCE[0]}")"
