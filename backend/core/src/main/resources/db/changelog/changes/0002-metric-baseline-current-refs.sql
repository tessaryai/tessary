--liquibase formatted sql

--changeset evals:0002-metric-baseline-current-refs
-- The rows folded into the window still being filled, so a finding on that window can enumerate its
-- member side completely.
--
-- The sweep already collected them. It held them in a per-page local and threw them away at the end
-- of every page, while the sketch and the count they describe were persisted — so a window that took
-- more than one sweep page to fill kept only the refs from the page its close landed in. The member
-- evidence on such a finding was a contiguous TAIL of the population, presented as the population:
-- the last few samples of a window standing in for all of them. `finding_evidence`'s own contract (role `member`,
-- "the writer may not sample it for them") was being broken by the writer, silently.
--
-- Same shape and same codec as `pinned_refs_json` (MetricEvidenceRefs): a JSON array of {"t","s"}.
-- It rotates with `current_sketch_json` for the reason the sidecars do — a refs blob that outlived
-- the sketch it belongs to would put one window's rows under another window's claim. Bounded by
-- `window_target_count`, since shouldClose cuts the window at that count.
--
-- Nullable, and no backfill: which rows went into a window that has already closed cannot be
-- re-derived from a sketch. Existing findings keep the truncated evidence they were written with.
ALTER TABLE metric_baseline ADD COLUMN current_refs_json text;

--rollback ALTER TABLE metric_baseline DROP COLUMN current_refs_json;
