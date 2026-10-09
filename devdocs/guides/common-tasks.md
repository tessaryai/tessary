# Common cross-stack tasks

Recipes that span backend + frontend + docs. Single-sided recipes live in the
scoped guides: *New REST endpoint* and *New error domain* in
[`backend/AGENTS.md`](../../backend/AGENTS.md), *New view* in
[`frontend/AGENTS.md`](../../frontend/AGENTS.md).

## Schema change (a new field on Pipeline, a new sub-record, …)

The field originates in the evals plugin, not here — this stack only
*consumes* it, so confirm it's already in the vendored `contract/` (re-vendor
with `scripts/sync-evals-contract.sh` if `contract/` predates it). Then absorb it:

1. Add or update the record in `backend/core/src/main/java/ai/tessary/model/`.
   Snake_case `@JsonProperty` if the wire form differs from the Java field name.
   (No reflection registration needed — the JVM reflects over Jackson records at
   runtime.)
2. Persist it. Relationally-stored entities (`call_site` / `chain` /
   `grader_failure_mode` / `pipeline_meta`) need a
   Liquibase changeset under
   `backend/core/src/main/resources/db/changelog/changes/` (+ the master-changelog
   include) and matching load/upsert wiring in `PipelineRepository`; compound or
   optional fields can ride in an existing `*_json` column instead of a new
   column. Note what is NOT here: the bundle's grader and quality-dimension shards
   are routed to `Shard.IGNORE` by `BundleAssembler` and persist nowhere, because
   everything that could run one was removed. A field on those shards needs no
   work at all.
   **If the changeset renames a persisted value, narrows a CHECK, or drops a
   table, also run `./scripts/check-migrations-populated.sh`** — `task check`
   applies Liquibase to empty databases, so it cannot fail a constraint swap that
   existing rows violate or an `UPDATE` that matches nothing; that script applies
   the chain over a fixture that holds one row per renamed value. Its header
   explains the mechanics.
   **If the change instead regenerates `0000-baseline.sql` in place** (a squash,
   when that is allowed at all), run `docker compose -f docker-compose.dev.yml down -v`
   (and the paid overlay's compose file, if running one) before the next
   `task dev`: a Postgres volume born under the OLD baseline carries its OWN
   `databasechangelog` ledger, which the new baseline changeset was never
   recorded against, Liquibase then either tries to re-run a changeset ID it
   already sees as applied (a no-op that leaves the volume's schema stuck on the
   old shape) or, worse, halts on the baseline's own `HALT`-on-existing-tables
   precondition. A volume born before this commit cannot migrate forward into
   it; it has to be recreated.
3. Expose it on the wire and regenerate the frontend types: if a controller DTO
   changed, run `task contract:openapi` (regenerates the checked-in canonical
   spec; `OpenApiSpecDriftTest` fails `backend:check` until you do), then in
   `frontend/` run `pnpm run generate:api` and consume the field via the `S[...]`
   aliases re-exported from `frontend/src/api/types.ts`.
4. Update the relevant view in `frontend/src/views/` to render or accept the
   field.
5. Add a round-trip assertion to
   `PipelineRepositoryTest.replaceAndLoad_preservesEveryField` so a missing
   column/mapper can't silently drop the field.
6. **If you added, dropped, or meaningfully altered a table or relationship**,
   update the data-model diagram
   ([`devdocs/reference/data-model.md`](../reference/data-model.md) — the Mermaid
   `erDiagram` and the matching inventory row) in the same change so it never
   drifts from the schema.
7. If it's a significant, hard-to-reverse constraint, record the rule in
   [`devdocs/reference/principles.md`](../reference/principles.md). If it changes the *product
   thesis*, that lives in Notion, not here — update it there and don't start a strategy doc
   in the repo.

## Building a large index before an upgrade

Liquibase runs at startup, so an index on a large table built by a changeset holds the backend's boot
for as long as the build takes. The self-host compose file marks the backend unhealthy after 315 s
without an answer, and `docker compose up -d` then stops with the frontend not started, even though
the backend finishes and turns healthy later. `0035-span-payload-project-fts.sql` is the first such
migration: it builds `ix_span_payload_project_fts` over `span_payload`, the largest table, then drops
`ix_span_payload_fts`, which the new index replaces. The build uses one core: about 2 minutes for
300,000 spans (1.2 GB of payloads) on a laptop. Above about 500,000 spans, or on a slow disk, do both
by hand before you upgrade; the changesets then find nothing to do.

1. Check free disk: the new index is about the size of `ix_span_payload_fts`, and both exist until the
   drop. `SELECT pg_size_pretty(pg_relation_size('ix_span_payload_fts'));`
2. Run the migration's statements with `psql`, as the database owner, in its order. `CONCURRENTLY` lets
   ingest keep writing while the index builds and drops:
   `CREATE EXTENSION IF NOT EXISTS btree_gin WITH SCHEMA public;`, then the `CREATE INDEX CONCURRENTLY`
   statement exactly as `0035-span-payload-project-fts.sql` spells it.
3. A build that fails leaves an invalid index, and the migration halts on one rather than skip it.
   Find it with `SELECT indisvalid FROM pg_index WHERE indexrelid = 'ix_span_payload_project_fts'::regclass;`,
   then `DROP INDEX CONCURRENTLY ix_span_payload_project_fts;` and build again.
4. Once the new index is valid: `DROP INDEX CONCURRENTLY IF EXISTS ix_span_payload_fts;`

If the backend was killed during the build (out of memory, a manual restart), two things can be left
behind. The build itself usually goes on inside Postgres and ends valid; while it runs, the backend
halts on the invalid index and restarts until it finishes. The Liquibase lock stays held, and every
later boot waits 5 minutes for it and exits. Once no backend is running, release it:
`UPDATE databasechangeloglock SET locked = false, lockgranted = NULL, lockedby = NULL WHERE id = 1;`

## New classifier

Not a cross-stack recipe with a fixed shape — a classifier attaches through the `ClassifierSweep`
port and its read-side siblings, and that seam has its own document:
[`../reference/classifier-extension-interface.md`](../reference/classifier-extension-interface.md).

A classifier that spends the org's own provider key per item has a second shape, and
`classifier/frustration/` is the example: a `PagedDetector` supplied through a `DetectorSupplier`,
seeded with `defaultEnabled = false`, pausing through `ClassifierPause` (surfaced as the row's
`readiness`), and lifted by `ProviderCredentialListener` when a key is saved. It must also meet the
five conditions [`principles.md`](../reference/principles.md) records for a per-event model call.

A classifier scored by a model the self-hoster runs outside Tessary's containers has a third shape,
and `classifier/detector/groundedness/` is the example: a `BuiltInDetector` supplied through a
`DetectorSupplier` that calls the model through `EncoderScorer`, listed in
`BuiltInDetector.Kind.ENCODER_BACKED`, and seeded with `defaultEnabled = false` because a person sets
up the model first. `EncoderAvailability` probes the model's `/healthz`; while it is down,
`ClassifierService` enqueues no sweep and `ClassifierWorker` hands a claimed one back without
spending an attempt, so scoring pauses and resumes from the cursor. The model server and its setup
files live in `classifiers/groundedness/`, and the rate test runs as a `ClassifierCatchUp`
(`GroundednessRateService`) once the sweep reaches the head of the stream.

(This heading replaced *New curation kind*. Curation — the accept/edit/reject overlay over an
imported pipeline — was removed along with graders, `CurationController` and the `curation_entry`
table; do not resurrect it as a model for new work.)

## Upgrading to a new contract version

The ingest path mirrors the on-disk layout the evals plugin emits. When
the plugin ships a new release that changes that layout — new pipeline shard,
renamed directory, tightened validation rule — follow
[`upgrade-contract.md`](./upgrade-contract.md). Use it when any of the
following is true:

- The plugin's `CHANGELOG.md` bumped past the `schema_version` in
  `contract/VERSION`.
- An import fails with a parse error after the user re-ran the plugin.
- `contract/output_format.md` or `contract/pipeline_io.py` differs from
  upstream.
- You are about to consume a pipeline shard, directory, or per-file entity the
  platform currently drops.

A one-field addition on an existing record is **not** that runbook — use the
*Schema change* recipe above.
