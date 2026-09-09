# Runbook — upgrade the evals plugin contract

The platform's import path mirrors the on-disk layout emitted by the
`evals` plugin (`.tessary/pipeline/*`; the bundle's `graders/*.yaml` shards are read and ignored,
since this platform has no grading). When the
plugin ships a new release that changes that layout — new shard, renamed field,
tightened schema — this runbook is the playbook for absorbing the change in
one PR.

The upstream source of truth for the contract DOCUMENTS is the plugin repo's
`output_format.md`, `contract/AUTHORING_CONTRACT.md`, and `contract/grader.schema.json`
(vendored here — see `scripts/lib/vendored-plugin-files.sh`); the `schema_version`
recorded in `contract/VERSION` is the version we currently support. The bundle
VALIDATOR (`contract/validate.py` + `pipeline_io.py`) is **platform-owned** since
plugin v0.23.0 — a contract change edits it here directly, no sync involved.

## When to run this

- The plugin shipped a new release (check its `CHANGELOG.md`).
- A user's import fails with a parse error on a bundle at HEAD.
- `git diff` against the plugin shows changes under `contract/` or
  `output_format.md`.
- You're about to consume a new pipeline shard the platform currently drops on the floor. (Grader
  and quality-dimension shards are dropped **on purpose** — `BundleAssembler` routes them to
  `Shard.IGNORE`. Consuming those is not a contract upgrade, it is reintroducing grading.)

If none of the above applies, you don't need this runbook — a regular schema
change (one field, no layout shift) is documented in
[`common-tasks.md`](./common-tasks.md) → "Schema change".

## Steps

### 1. Sync the vendored contract

```bash
# Defaults to ../plugins/plugins/evals; override with EVALS_PLUGIN_PATH if your
# local checkout lives elsewhere.
scripts/sync-evals-contract.sh
git diff -- contract/
```

`contract/VERSION` should now show the new plugin commit, release tag, and
bundle schema version. Read `contract/CHANGELOG.md` from the top until you hit
the previous `schema_version` — those entries are the migration notes that
matter.

### 2. Decide what changed at the layout level

Open `contract/output_format.md` side-by-side with the previous version (and
update the platform-owned `contract/validate.py` / `pipeline_io.py` to enforce
the change). Categorise each change:

- **New shard** (e.g. `pipeline/foo.yaml`) — add a `Shard.FOO` enum case in
  `pipeline/BundleAssembler.classify`, a new branch in `applyShard`, and a slot on
  `ShardCollector`. Persist it on `Pipeline` if it's a typed entity.
- **Renamed directory or filename** — extend the path classifier in
  `pipeline/BundleAssembler.classify` / `normalisePath`. Keep the previous name as a
  fall-through alias only if upstream is shipping both for one release.
- **New per-file entity** (under `pipeline/<foo>/*.yaml`) —
  add a model class under `backend/.../model/`, a classifier branch, and a
  collector list.
- **New field on an existing entity** — see [`common-tasks.md`](./common-tasks.md) →
  "Schema change" (this runbook is overkill for one field).
- **Tightened validation rule** (e.g. min 3 self-tests with at least one pass
  and one fail) — encode it explicitly; Jackson's permissive deserializer
  won't catch it.

### 3. Walk the surfaces

For every layout-level change, touch each of these in order:

1. **Java model** — `backend/core/src/main/java/ai/tessary/model/`. Add or
   update the record. (Running on the standard JVM, Jackson reflects over
   records at runtime — no reflection-hint registration step.)
2. **DB migration** — new Liquibase changeset under
   `backend/core/src/main/resources/db/changelog/changes/`. Compound fields go in a
   TEXT/JSON column on `pipeline_meta`; relational entities get their own
   table. Add the `<NNN>-…sql` line to `db.changelog-master.yaml`.
3. **Repository** — `backend/product/src/main/java/ai/tessary/pipeline/PipelineRepository.java`.
   Extend the `load` / `upsert` / `replace` paths with the new column or table. JSON
   blobs go through `writeJson` / `readJson`.
4. **Bundle assembly** — `backend/product/src/main/java/ai/tessary/pipeline/BundleAssembler.java`
   (the upload path `ImportController` is its only caller now — the observer's server-side import
   path went with the observer). Add the
   `Shard` enum case, the `classify` branch, the `applyShard` switch arm, and
   the `ShardCollector` slot in `assemble`. `ImportController` just calls
   `assembler.assemble(...)` — no per-shard logic lives in the controller.
5. **TypeScript types** — regenerate them. Run `task contract:openapi` (this
   is enforced by `OpenApiSpecDriftTest` in `backend:check`), then in `frontend/`
   run `pnpm run generate:api` to refresh `frontend/src/api/generated/schema.d.ts`.
   Most pipeline entities in `frontend/src/api/types.ts` are `S[...]` re-exports
   of that generated schema — hand-editing `types.ts` won't add a field there.
   Only the few hand-authored types (string-literal unions, generic client
   wrappers, SSE payloads) are edited directly; keep those snake_case to match
   the wire format.
6. **UI** — `frontend/src/views/`. Render the new field on the relevant view. Note that the
   entity-level surfaces this step used to name (Graders, CallSites, Chains) were deleted with the
   Calibrate group; a genuinely new pipeline entity may need a new view rather than an edit to one.
7. **Import UI summary** — `frontend/src/views/Settings/Import.tsx`. If you
   added a new top-level path, extend `summariseDirSelection` so users see
   it counted in the picker preview.
8. **Tests** — `backend/app/src/test/java/ai/tessary/pipeline/ImportControllerTest.java`.
   Update the shard fixtures (`META_YAML`, `CALL_SITE_YAML`, …) to use the new
   schema version, add a fixture for any new shard, and add a happy-path
   assertion that the new field round-trips through the DB.
9. **Prompt-craft skill** — `claude-skill/evals-prompt/`. It is craft guidance, not a second copy of
   the schema, and it points at `contract/` as source of truth. A contract bump touching
   author-owned fields still wants its `contract_version:` frontmatter bumped and any prose that
   names a renamed field updated. It no longer has to stay in lockstep the way it did when the
   platform ran the graders this skill helps author.
10. **E2B `tessary-agent-sandbox` template** — `sandbox-runner/agent-sandbox/template.ts`
   bakes the contract files into the sandbox image from this repo's `contract/` (staged into
   `vendor/` by `build.ts`). The two lanes that run there — RCA and Layer-2 triage — READ the
   bundle to ground a ruling; neither writes one back, so a version skew now fails a comparison
   rather than corrupting a repo. It still wants a rebuild on a contract bump: `cd
   sandbox-runner/agent-sandbox && pnpm install && pnpm exec tsx build.ts` (needs the prod
   team's `E2B_API_KEY`). **The template must exist under the name `tessary-agent-sandbox` before
   any E2B-backed deploy** — nothing in this repo can create the
   cloud template.

### 4. Verify

```bash
(cd backend && mvn -B -q -pl app -DfailIfNoTests=false -Dtest='ImportControllerTest' test)
(cd frontend && pnpm exec tsc --noEmit)
```

Then a manual smoke test: author (or hand-edit) a `.tessary/` bundle carrying the new
field, upload it through Settings → Import, and confirm the new field is visible in the UI.

### 5. Commit

One PR, in this order in the diff:

```
contract/                  # vendored bump + VERSION
backend/.../db/changelog/  # migration
backend/.../model/         # records
backend/.../pipeline/      # repository + shard assembly + import controller (PipelineRepository, BundleAssembler, ImportController)
backend/.../test/          # fixtures
frontend/src/api/          # types + client
frontend/src/views/        # rendering
claude-skill/evals-prompt/ # prompt-craft skill, when author-owned fields changed
sandbox-runner/agent-sandbox/  # rebuild the E2B analyzer image (baked contract files)
devdocs/                   # this runbook if the steps shifted
```

Mention the plugin commit and schema version in the PR description.

## Anti-patterns

- **Don't add backward-compat shims for older shard layouts.** We support
  exactly one schema version at a time. If a user is on an older plugin
  release, they re-sync the plugin before re-uploading.
- **Don't silently drop unknown fields without filing a note.** Jackson's
  forward-compat config drops them at parse time; that's fine for *new*
  optional fields the platform hasn't caught up to yet, but if you see the
  feature in the wild, add it to the model before merging.
- **Don't write a sibling endpoint** (`/import/v2`, `/import/sharded`) to
  preserve the old one. The single endpoint accepts the current schema; the
  client decides what to upload.
