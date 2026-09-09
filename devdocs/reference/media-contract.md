# The media contract — supported modalities, storage, and export

> **Status: images and PDF documents are both live** — extending to at least one non-image modality
> rather than staying images-only. This
> doc is the public, documented decision; `MediaResolver`'s old "images-only v1"
> framing predates it and has been corrected in code alongside this doc.

Flagged in the 2026-09-01 review of the Langfuse migration gap analysis: "declare the supported media
contract … fail explicitly on unsupported modalities rather than silently labeling them." This is that
contract.

## 1. Supported modalities

| Modality | MIME types | Kinds (`ContentBlock.type`) |
|---|---|---|
| Image | `image/png`, `image/jpeg`, `image/gif`, `image/webp` | `image_url`, `image_b64`, `image_ref` |
| Document | `application/pdf` only | `document_url`, `document_b64`, `document_ref` |

**Audio and video are explicitly out of scope for this launch.** The type system is shaped so a third
modality is additive, not a rework: a fourth `TYPE_*` triad on `ContentBlock`, a fourth switch case
everywhere this batch touched (`ContentExtractor`'s three flatten switches, `MediaExternalizer`,
`MediaResolver`'s inline-resolution gate, `TraceSpanMapper`'s export, and `PayloadViewer.tsx`'s frontend
switch — `ContentBlocks`' judge-boundary switch no longer exists, see §6) — never a structural change to
`MediaStore` or the `media_object`
schema. When audio/video is picked up, size and streaming will force real decisions this contract
deliberately defers (see §3).

## 2. Storage: PostgreSQL `bytea`, not an object store

`PostgresMediaStore` stays the only storage-backing `MediaStore` implementation (`CachingMediaStore` is a
decorator — an in-memory read cache in front of it, not an alternate backend; see §3). A 2026-09-02 comment proposed a
storage-backend selector (`tessary.media.store`: `filesystem` default, `postgres`, `s3`) with Range-serving
and per-modality caps — **not adopted**. The `MediaStore` interface already isolates this choice behind a
seam an object store could fill later without touching callers or the schema; PDF sizes don't force that
now the way audio/video would, and building a filesystem/S3 backend for a modality that fits comfortably
in a `bytea` column is scope the gate doesn't require.

## 3. Size limit: one shared 8 MiB cap, not an inherited one

`tessary.ingest.max-media-bytes` (default 8 MiB, tuned for images) now bounds documents too. No new config
key was added — the goal was to state the resulting limit, not build a new one, and unnecessary
config surface is disfavored.

**This is a deliberate, real per-item cap, not an inherited or object-store-scale one.** Postgres `bytea`
has no practical size ceiling anywhere near a gigabyte in principle, but a hot OLTP table serving live
ingest traffic is not the place for multi-hundred-MB blobs, and this repo already gates request/OTLP-batch
size well below that via the existing ingest cap. For comparison: Langfuse's own S3 media backend defaults
`LANGFUSE_S3_MEDIA_MAX_CONTENT_LENGTH` to **1 GB**, with presigned download URLs expiring after an hour —
that number belongs to an object store, is over 100x the cap here, and is **not** being adopted or implied
by this contract. When audio/video needs headroom beyond a `bytea`-friendly size, that is the signal an
object-store `MediaStore` implementation is due, not a reason to raise this cap past what Postgres should
carry.

`CachingMediaStore`'s in-memory read cache (`tessary.media.cache.max-bytes`, 64 MiB default) sits in front
of storage and was previously undocumented — see [`config-keys.md`](./config-keys.md). It was sized for
image-scale objects (a handful of images per grader call); a document up to the 8 MiB ingest cap fits the
same budget without resizing it.

## 4. Export shape: inline, not referenced or bundled

Media is preserved on export as a base64 `data:` URI inlined **inside the existing plain-string `content`
field** `TraceSpanMapper` already emits — no new part-shape, no bundled sidecar file, and no referenced
`/media/{id}` export requiring org/project-slug-to-URL plumbing. An inline base64 image/document's bytes
are already present and need no `MediaStore` round trip; an externalized `_ref` block rehydrates via
`MediaStore.get` when a store is available, falling back to the honest placeholder label
(`[image omitted: <mediaType>]` / `[document omitted: <mediaType>]`) when it is not — lossless-or-labeled,
never a silent collapse, exactly the invariant this class has documented since before real preservation
existed. A real `http(s)` URL is never fetched at export time, matching the no-fetch posture ingest and
the judge boundary already hold.

This mirrors — without adopting — the storage-vs-export split in Langfuse's own product: their trace
payload carries only a reference token in storage (`@@@langfuseMedia:type=...|id=...|source=...@@@`,
resolved against their own S3 backend), and their SDK's `resolve_media_references(...,
resolve_with='base64_data_uri')` rewrites that token into an inline `data:` URI only at export time. Only
the storage/export split and the inline-on-export conclusion are adopted — not their S3 backend, not their
token syntax, and not their SDK-side resolution mechanics. Tessary already has the storage half
(`image_ref`/`document_ref` + `MediaStore`); this contract is only the export half.

The endpoint is `GET /api/orgs/{orgSlug}/projects/{projectSlug}/traces/{traceId}/export`
(`application/x-ndjson`, one OTel GenAI span per line) — the existing, already-authenticated per-trace
route convention, not a new public/token-scoped surface. `TraceSpanMapper.toSpan`/`toSpanLine` had zero
production callers before this issue; this endpoint is the caller.

## 5. `document_url` is display-only — a deliberate decision, not an omission

`document_url` mirrors `image_url`'s field layout exactly (the `url` field holds the real URL) and is
**never fetched server-side** — not at ingest (`MediaResolver`, `MediaExternalizer`), and not at export
(`TraceSpanMapper` labels it, never downloads it). There is no judge boundary left to fetch it either:
grading and `ContentBlocks` were removed along with it (§6). This adds no new outbound-fetch surface anywhere in the
pipeline. A caller that needs a document graded must send its bytes (`document_b64`) or let it externalize
through the normal ingest path to a `document_ref`.

## 6. Two unsupported-modality policies, reconciled (not unified)

Two different policies were live in the codebase before this issue, and the right answer was to keep
both rather than merge them. One of the two has since gone away with the boundary it guarded; the
reasoning for keeping them separate is recorded here anyway, because it is what decides where a future
gradable boundary belongs:

- **Fail-loud (422)** at the judge/grading boundary — `ContentBlocks` threw
  `JudgeError.UNSUPPORTED_CONTENT_TYPE` for any block type it didn't route, because a
  silently-incomplete grade is a **wrong verdict** and a grader that graded half an input without
  saying so is worse than one that refused to run. **This boundary no longer exists**: grading and
  `ContentBlocks` were removed, so `JudgeError.UNSUPPORTED_CONTENT_TYPE` is declared and thrown
  nowhere. The policy is recorded because the reasoning is what any future gradable boundary should
  re-adopt — not because something enforces it today.
- **Silently labeled** at the classifier-context/trajectory-rendering boundary —
  `ContentExtractor.partPlaceholder` (Java) and `classifiers/framework/context.py` (Python) render an
  unrecognized part as `[unsupported]` text, and the frontend's `PayloadViewer.tsx` degrades to an
  "unsupported image"/"unsupported document" chip. These are correct where they are because they are
  **best-effort, non-gradable, informational renderers** — a thread view or a debug pane that drops one
  part is still useful, and failing the whole render over one part would make the tool worse at its actual
  job (showing a human what happened). This predates the current work and is not a bug to merge away.

Reader's rule of thumb: if the surface decides a grade, it fails loud; if the surface only informs a
human or a downstream text feature, it labels and moves on.

## 7. Braintrust attachments — out of scope, and why

A Braintrust `braintrust_attachment`/`external_attachment` reference still degrades to
`[attachment: <filename> (<content_type>)]` rather than being resolved to bytes. This is unrelated to the
document modality: the public download endpoint for a `braintrust_attachment` key is undocumented (the
Braintrust SDK resolves it internally), and `external_attachment`'s `s3://` URL is not `http(s)`, so
`UrlGuard` rejects it. Neither obstacle is closed by adding a document/PDF `ContentBlock` kind, so this
path is untouched and stays detect-and-label only.
