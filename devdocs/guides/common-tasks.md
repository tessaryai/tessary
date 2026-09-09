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
   Track A removed everything that could run one. A field on those shards needs no
   work at all.
   **If the changeset renames a persisted value, narrows a CHECK, or drops a
   table, also run `./scripts/check-migrations-populated.sh`** — `task check`
   applies Liquibase to empty databases, so it cannot fail a constraint swap that
   existing rows violate or an `UPDATE` that matches nothing; that script applies
   the chain over a fixture that holds one row per renamed value. Its header
   explains the mechanics. Pass `MIGPOP_OVERLAY_DIR=tessary-paid/db/src/main/resources`
   (an absolute path, since it becomes a Docker bind mount) to also run the paid
   overlay's own lane.
   **If the change instead regenerates `0000-baseline.sql` or the paid overlay's
   own `P0000-*-baseline.sql` in place** (a squash, or the epic-3
   partition, see decision D-A in the open-core program doc's divergence log for
   when this is allowed at all), run `docker compose -f docker-compose.dev.yml down -v`
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

## New classifier

Not a cross-stack recipe with a fixed shape — a classifier attaches through the `ClassifierSweep`
port and its read-side siblings, and that seam has its own document:
[`../reference/classifier-extension-interface.md`](../reference/classifier-extension-interface.md).

(This heading replaced *New curation kind*. Curation — the accept/edit/reject overlay over an
imported pipeline — was removed on the backend with graders in Track A, along with
`CurationController` and the `curation_entry` table. Frontend still carries dead `Curation`/
`CurationEntry` types (`frontend/src/api/types.ts`) and an orphaned `client.ts` stub hitting a
`/curation/*` route that no longer exists — do not resurrect it as a model for new work.)

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
