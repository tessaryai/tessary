# The `home.tessary.ai` telemetry contract

> **Status: the §1 heartbeat client is built; the §2 license-check endpoint is not.**
> `backend/core/.../telemetry` (`TelemetryProperties`, `HomeTessaryClient`, `InstanceIdRepository`) and
> `backend/surfaces/.../telemetry` (`TelemetryHeartbeat`) implement §1, §3 and §5 of this contract.
> The server is `tessaryai/tessary-home`; §1 matches its `POST /v1/ping` route, and
> `TelemetryHeartbeatTest` validates the real payload against a copy of that repository's
> `contracts/ping.v1.schema.json`. Until `home.tessary.ai` is deployed the client's POSTs fail,
> harmlessly (see §1's error handling). The
> Mixpanel-based analytics stack this doc's contract replaced (`ai.tessary.analytics`,
> `frontend/src/lib/mixpanel.ts`) is gone outright, not superseded gradually — deleted in the same
> change that added this client. §2's license-check endpoint remains spec-only.

Anonymous ping, opt-out, disclosed in the README.

## 1. Ping payload

`POST https://home.tessary.ai/v1/ping`, JSON, sent from the backend process (see §4, Scope). The
first five fields are required by home; home answers 400 without them. The rest are optional there
and always sent here. Home strips any field it does not know.

| Field | Type | Example | Purpose |
|---|---|---|---|
| `contract_version` | int | `1` | Schema version of this payload; must match the `/v1` route. See §5. |
| `instance_id` | UUID v4 | `"a1b2c3d4-...-000000000001"` | Generated once at first boot, persisted locally. Identifies an instance, never a person or org — never derived from org name, user email, or license key. |
| `ping_seq` | int ≥ 0 | `41` | 0 on an instance's first ping, then one more on every ping, persisted in `telemetry_instance.ping_seq` so it keeps rising across restarts and replicas. Lets home tell a missed ping from a restart. |
| `sent_at` | ISO-8601 UTC, `Z` | `"2026-09-13T07:20:00.123Z"` | When the ping was generated. |
| `app_version` | string, ≤ 64 chars | `"2026.9.1"` | The running app's version; `"dev"` outside a packaged jar. |
| `edition` | enum: `open` \| `paid` | `"open"` | Which build sent the ping. Both values are live: the backend derives it from the classpath (`ai.tessary.edition.Edition`; the paid overlay's presence reads `paid`), never from a property. |
| `os` | string, ≤ 32 chars | `"linux"` | Host OS family. |
| `arch` | string, ≤ 32 chars | `"arm64"` | Host CPU architecture. |
| `counts.projects` | int ≥ 0 | `3` | Projects on the install, archived ones included. |
| `counts.spans` | int ≥ 0 | `1204551` | Spans ingested over the install's life: the sum of every `day` rollup of `ingested_spans` in `metric_rollup`. |
| `counts.findings` | int ≥ 0 | `88` | Findings on the install, in any status. |
| `counts.cases` | int ≥ 0 | `12` | Cases on the install, open or resolved. |
| `counts.l1` | int ≥ 0 | `40210` | Classifier detections over the install's life: the sum of every `day` rollup of `l1_evals`. |
| `price_book.digest` | sha256 hex | `"a5ad23f7…"` | The sha256 of the price book this install prices from. Omitted when it holds none (pricing disabled, or a book imported before digests were recorded and not yet backfilled). |
| `price_book.schema_max` | int | `1` | The newest price-book manifest schema this build parses (§1a). |

**Counts.** Totals across the whole install, never per org or project, and never names or content.
`counts` is sent whole or not at all: if any count fails to read, the ping goes out without it.
`spans` and `l1` come from the metering rollups rather than a `COUNT(*)` so the ping never scans the
span table. Those rollups survive retention, so the two totals do not drop when old spans are
deleted. They lag by up to a day, because only closed days are summed. They drop when a project or
org is deleted, because `metric_rollup` cascades from both. `projects`, `findings` and `cases` are
current row counts.

**Not sent.** `deploy_mode`, which home's contract also accepts.

**Response.** Ignored. `HomeTessaryClient` discards the body; a non-2xx status is logged at debug
and the next ping is the retry.

**Anonymity guarantee — closed negative list.** The ping never carries: an email
address; a hostname; an org or project name; trace, prompt, or dataset content; a license key
(the license key travels only on the separate license-check call in §2, not on
the heartbeat ping); or an IP address retained beyond the lifetime of the
inbound connection.

**Frequency.** Once on backend process start (after a 5-30 second jitter, to
avoid a synchronized thundering herd across self-hosted fleets), then every 6
hours, measured from the end of the previous tick. Backend-only (§4).
Deliberately coarser than the Mixpanel per-action event stream it replaces —
this is a heartbeat, not a re-implementation of the now-deleted Mixpanel doc's
seven-row backend event inventory.

## 1a. Price book check

The same tick, right after the ping and regardless of whether the ping succeeded
(`pricing/PriceBookFetcher`, called from `TelemetryHeartbeat`):

1. `GET https://home.tessary.ai/v1/pricing/manifest.json`, which home serves as
   `{"schema": 1, "digest": "<sha256>", "url": "https://home.tessary.ai/v1/pricing/<sha256>.json", "published_at": "<ISO-8601>"}`.
   No request body, no identifiers.
2. If a book with that `digest` is already in `price_book`, stop. This is almost
   every tick, and it costs one indexed lookup.
3. Otherwise `GET /v1/pricing/<digest>.json`: tessary's vendored LiteLLM file,
   published byte for byte from `main` (tessaryai/tessary-home's
   `publish-pricing.yml`).
4. Import it as a new book dated by the manifest's `published_at`. New spans and
   platform calls price from it; stored costs never change.

The instance refuses, logs, and keeps the book it has when: the manifest is not
JSON; `schema` is not one it parses; `digest` is not 64 lowercase hex; `url` is
anything but home's own path for that digest; `published_at` is not an instant;
the book is over 32 MB, does not hash to `digest`, or prices no models. home
being unreachable is the same: the next tick retries.

**Which book is in force.** The newest `published_at` per source. The book
bundled in the jar is dated by the jar's build time (Spring Boot `build-info`),
so a restart cannot put an older bundled book back in force over a newer fetched
one, and a newer release's bundle still wins over an older fetched book. home
publishes the same bytes the jar carries, so the two share a digest and a
version and are never stored twice.

**Opted out.** Both calls sit behind the one `TESSARY_TELEMETRY_ENABLED` gate
(§3). An opted-out install never fetches and prices from its bundled book, which
updates only with a release. `tessary.pricing.enabled=false` turns off both the
bundled import and the fetch.

## 2. License-check endpoint

Not yet implemented. Shapes only:

**Request**

| Field | Type | Example | Purpose |
|---|---|---|---|
| `contract_version` | int | `1` | Schema version, same as §1. |
| `instance_id` | UUID v4 | `"a1b2c3d4-...-000000000001"` | Same instance identity as the heartbeat ping. |
| `license_key` | string | `"tsy_live_..."` | The self-hoster's license key, present only on this call. |

**Response**

| Field | Type | Example | Purpose |
|---|---|---|---|
| `status` | enum: `valid` \| `invalid` \| `unreachable` | `"valid"` | License validity as of this check. |
| `expires_at` | ISO-8601 UTC or null | `"2027-03-01T00:00:00Z"` | When the license expires; null for perpetual/open. |
| `entitlements` | string[] | `["paid-conformance", "paid-groundedness"]` | Which paid capabilities this license unlocks. |

`unreachable` degrades to "never blocks" — a self-hosted instance that cannot
reach `home.tessary.ai` keeps running exactly as it was (price-book sync
degrades the same way: falls back to the bundled data, a manual override always
wins).

## 3. Opt-out env var

`TESSARY_TELEMETRY_ENABLED`, default `true`, following the existing
`TESSARY_ANALYTICS_ENABLED` truthy-parsing convention
([`config-keys.md`](./config-keys.md)).

When set `false`: zero outbound network calls — including DNS resolution to
`home.tessary.ai` — from the backend process (§4), covering the heartbeat
ping (§1), the price book check (§1a), and the license-check call (§2). This is written precisely enough for
`check-zero-egress.sh` to assert
mechanically: with the var off, no attempt to resolve or reach
`home.tessary.ai` may occur anywhere in a boot-to-triage run — proven via a
DNS-sink network, with `TESSARY_TELEMETRY_ENABLED=true` run as the positive
control that must log exactly `home.tessary.ai`.

## 4. Scope note: backend-only

The replacement client is backend-only — one phone-home endpoint ("the Mixpanel
`analytics` package is replaced by one phone-home endpoint, `home.tessary.ai`,
for telemetry, license checks, and future needs, with no third-party SDK or
token in open code"), and the replacement client points at `home.tessary.ai`
(singular). Frontend Mixpanel calls are removed outright,
not replaced with their own `home.tessary.ai` path.

This is this doc's reading of the existing decisions, not an unquestionable
fact — if a frontend leg turns out to be needed later, §5's additive-only
versioning rule accommodates it without reopening this doc's core shape.

## 5. Versioning rules

`contract_version` is additive-only for new optional fields — a downstream
consumer on an older version simply doesn't see the new field, no doc change
required to keep working. This is exactly the mechanism a future
price-sync field would use to extend the ping payload.

Any field removal or semantic change (a field's meaning or type changing under
the same name) requires a documented major version bump, with a changelog entry
appended to the bottom of this file recording what changed and why.

## 6. Owner

The `home.tessary.ai` service itself lives outside this repo, in `tessaryai/tessary-home`. Owner:
**the Tessary maintainers (security@tessary.ai)**.

## Changelog

- 2026-09-01 — `contract_version` 1. Initial contract: ping payload, license-check
  endpoint, opt-out env var, scope note, versioning rules, owner.
- 2026-09-01 — The §1 heartbeat client lands: `analytics` (Mixpanel) is deleted outright — backend
  package and frontend `lib/mixpanel.ts` + all 13 call sites — and replaced by `core`/`surfaces`'s
  `telemetry` package, built to this contract. §2 (license-check) stays spec-only.
- 2026-09-13 — §1 aligned with the server actually built, `tessary-home`'s `POST /v1/ping`, still
  `contract_version` 1. The path moved from `/ping` to `/v1/ping`; `ping_seq` was added and
  `timestamp` became `sent_at`, both required by that route; `org_count_bucket`,
  `project_count_bucket` and `trace_volume_bucket` were replaced by the route's `counts` object
  (`projects`, `spans`, `findings`, `cases`), plus `counts.l1`. Not a version bump under §5: no server ever accepted the earlier shape, so no
  consumer saw it. Frequency went from 24 hours to 6.
- 2026-09-13 — §1a: the instance checks home's price book manifest on the same 6-hour tick and imports
  a new book when the digest changes. The ping gains `price_book.digest` and `price_book.schema_max`.
