-- The declared schema delta: the objects the baseline-regenerating commit under proof is allowed
-- to move or change between A (verify-baseline-equivalence.sh's pinned old chain) and B (the
-- working tree's regenerated baseline). Applied to A after the old chain and before the schema
-- dump, so A ends up in the shape the commit claims it produces, named object by object, rather
-- than left to the schema diff to discover by elimination. `verify-baseline-equivalence.sh` reads
-- this file by default for whatever regeneration is being proven; it is not tied to any one commit.
--
-- Today this delta declares exactly two drops, and nothing else.
--
-- Folding an old migration chain into a single baseline changes nothing about the schema those
-- changesets built: A is the pinned old chain, fully applied, and B is the regenerated single
-- baseline, both photographs of the same end state. On its own that would leave this file empty
-- and the run a pure identity check.
--
-- Two dead indexes are removed in the same commit, which is why it is not empty. `ux_job_embedding`
-- (predicate `kind = 'embedding'`) and `ux_job_grader_run` (predicate `kind = 'grader_run'`) are
-- partial UNIQUE indexes on `job` whose predicates name kinds `job_kind_check` no longer admits.
-- They can never match a row again, and they survived precisely because they were invisible to
-- this proof: an object present in both A and B cancels out of the diff no matter how dead it is,
-- so the empty delta could not have caught them. The baseline fold is the last moment they can
-- leave without a forward migration, since merging re-arms immutability.
--
-- `ux_job_coalesce` is deliberately not among them. Its predicate names two dead kinds but also
-- 'usage_rollup', which is live and admitted by job_kind_check today. It still serves usage_rollup
-- dedup, so dropping it would be a bug and narrowing its predicate would be a behaviour change in a
-- commit whose whole point is not to make one. It stays exactly as the chain left it.
--
-- A non-empty delta is a list of differences the proof agrees to tolerate, so keep it minimal and
-- make every entry assert itself. Anything in the diff not declared below is a defect in the
-- photograph, with nothing in this file to excuse it.
--
-- Data-only changesets (a DELETE, a backfill, a dedup sweep) need no entry: both A and B are built
-- empty, so those statements matched zero rows on A and have no photograph on B.
--
-- The format, when a future regeneration needs one: wrap every statement in a self-checking
-- `DO $$ ... $$` block, assert the object's pre-delta state, apply the DDL (if any), then assert
-- its post-delta state. A delta file that only applies DDL and trusts the diff to catch a mistake
-- defeats the reason this file exists: the assertions are what turn "the diff was empty" into "the
-- diff was empty and it was empty for the reason we expected", and a wrong assertion fails loudly
-- here, naming the object, instead of showing up as an unexplained line in the schema diff far
-- below.

DO $$
DECLARE
  pred text;
BEGIN
  -- PRE: the index exists on A (the old chain builds it) and is provably dead: its predicate names
  -- 'embedding', a kind job_kind_check no longer admits, asserted by reading both the index
  -- predicate and the live CHECK rather than trusting either alone. The match is anchored on
  -- `kind = '...'` and not a bare substring, because the index name also contains 'embedding' and a
  -- loose LIKE would pass on the name alone, proving nothing about what the index actually selects.
  IF to_regclass('public.ux_job_embedding') IS NULL THEN
    RAISE EXCEPTION 'partition-expected-delta: public.ux_job_embedding missing before the delta — the old chain did not build what this delta expects';
  END IF;
  SELECT pg_get_indexdef('public.ux_job_embedding'::regclass) INTO pred;
  IF pred NOT LIKE '%kind = ''embedding''%' THEN
    RAISE EXCEPTION 'partition-expected-delta: public.ux_job_embedding does not name ''embedding'' in its predicate — %', pred;
  END IF;
  IF (SELECT pg_get_constraintdef(oid) FROM pg_constraint
        WHERE conname = 'job_kind_check' AND conrelid = 'public.job'::regclass) LIKE '%''embedding''%' THEN
    RAISE EXCEPTION 'partition-expected-delta: job_kind_check still admits ''embedding'' — this index is NOT dead and must not be dropped';
  END IF;
  IF (SELECT count(*) FROM public.job WHERE kind = 'embedding') <> 0 THEN
    RAISE EXCEPTION 'partition-expected-delta: public.job holds ''embedding'' rows — the index is live after all';
  END IF;

  DROP INDEX public.ux_job_embedding;

  -- POST: gone, matching B's baseline, which never creates it.
  IF to_regclass('public.ux_job_embedding') IS NOT NULL THEN
    RAISE EXCEPTION 'partition-expected-delta: public.ux_job_embedding still present after the delta — expected it dropped';
  END IF;
END $$;

DO $$
DECLARE
  pred text;
BEGIN
  -- PRE: the index exists on A (the old chain builds it) and is provably dead: its predicate names
  -- 'grader_run', a kind job_kind_check no longer admits, asserted by reading both the index
  -- predicate and the live CHECK rather than trusting either alone. The match is anchored on
  -- `kind = '...'` and not a bare substring, because the index name also contains 'grader_run' and a
  -- loose LIKE would pass on the name alone, proving nothing about what the index actually selects.
  IF to_regclass('public.ux_job_grader_run') IS NULL THEN
    RAISE EXCEPTION 'partition-expected-delta: public.ux_job_grader_run missing before the delta — the old chain did not build what this delta expects';
  END IF;
  SELECT pg_get_indexdef('public.ux_job_grader_run'::regclass) INTO pred;
  IF pred NOT LIKE '%kind = ''grader_run''%' THEN
    RAISE EXCEPTION 'partition-expected-delta: public.ux_job_grader_run does not name ''grader_run'' in its predicate — %', pred;
  END IF;
  IF (SELECT pg_get_constraintdef(oid) FROM pg_constraint
        WHERE conname = 'job_kind_check' AND conrelid = 'public.job'::regclass) LIKE '%''grader_run''%' THEN
    RAISE EXCEPTION 'partition-expected-delta: job_kind_check still admits ''grader_run'' — this index is NOT dead and must not be dropped';
  END IF;
  IF (SELECT count(*) FROM public.job WHERE kind = 'grader_run') <> 0 THEN
    RAISE EXCEPTION 'partition-expected-delta: public.job holds ''grader_run'' rows — the index is live after all';
  END IF;

  DROP INDEX public.ux_job_grader_run;

  -- POST: gone, matching B's baseline, which never creates it.
  IF to_regclass('public.ux_job_grader_run') IS NOT NULL THEN
    RAISE EXCEPTION 'partition-expected-delta: public.ux_job_grader_run still present after the delta — expected it dropped';
  END IF;
END $$;
