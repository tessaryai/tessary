# The `home.tessary.ai` telemetry contract

> **Status: the §1 heartbeat client is built (#858); the §2 license-check endpoint is not.**
> `backend/core/.../telemetry` (`TelemetryProperties`, `HomeTessaryClient`, `InstallIdRepository`) and
> `backend/surfaces/.../telemetry` (`TelemetryHeartbeat`, `TelemetryBuckets`) implement §1, §3 and
> §5 of this contract. No service exists at `home.tessary.ai` today, so the client's POSTs fail —
> harmlessly; see §1's error handling — until that service (owner: §6) is stood up. The
> Mixpanel-based analytics stack this doc's contract replaced (`ai.tessary.analytics`,
> `frontend/src/lib/mixpanel.ts`) is gone outright, not superseded gradually — deleted in the same
> change that added this client. §2's license-check endpoint remains spec-only, consumed by epic 9,
> not this issue.

Decided by D6 (Telemetry): anonymous ping, opt-out, disclosed in the README.

## 1. Ping payload

Sent from the backend process (see §4, Scope). Every field below is required
unless marked optional; `contract_version` governs which optional fields a given
payload may carry (see §5, Versioning).

| Field | Type | Example | Purpose |
|---|---|---|---|
| `contract_version` | int | `1` | Schema version of this payload; see §5. |
| `install_id` | UUID v4 | `"a1b2c3d4-...-000000000001"` | Generated once at first boot, persisted locally. Identifies an install, never a person or org — never derived from org name, user email, or license key. |
| `edition` | enum: `open` \| `paid` | `"open"` | Which build sent the ping. Both values are live since epic 5 (#1133): the backend derives it from the classpath (`ai.tessary.edition.Edition`; the paid overlay's presence reads `paid`), never from a property. |
| `app_version` | string (semver) | `"2026.9.1"` | The running app's version. |
| `os` | string | `"linux"` | Host OS family. |
| `arch` | string | `"arm64"` | Host CPU architecture. |
| `org_count_bucket` | enum bucket | `"1-5"` | Coarse, bucketed org count — never the exact number. |
| `project_count_bucket` | enum bucket | `"6-25"` | Coarse, bucketed project count — never the exact number. |
| `trace_volume_bucket` | enum bucket | `"1k-10k"` | Coarse, bucketed daily trace-ingest volume (see the closed set below) — never an exact count, and never trace content. |
| `timestamp` | ISO-8601 UTC | `"2026-09-01T00:00:00Z"` | When the ping was generated. |

**Bucket enums — closed sets, not free-form strings.** Both sides of the ping
exchange must agree on the exact boundaries; a "coarse bucket" left as an
illustrative example rather than an enumerated set defeats this doc's purpose
of being mechanically diffable against a real implementation.

`org_count_bucket` / `project_count_bucket`: `"0"`, `"1-5"`, `"6-25"`,
`"26-100"`, `"101-500"`, `"500+"`.

`trace_volume_bucket` (traces/day, rolling 24h average): `"0"`, `"1-100"`,
`"101-1k"`, `"1k-10k"`, `"10k-100k"`, `"100k+"`.

Adding a bucket boundary is an additive change under §5's versioning rule; a
consumer on an older `contract_version` simply never sees the new bucket value.
Narrowing or removing a boundary is not additive and needs the major-version
bump §5 describes.

**Anonymity guarantee — closed negative list.** The ping never carries: an email
address; a hostname; an org or project name; trace, prompt, or dataset content; a license key
(the license key travels only on the separate license-check call in §2, not on
the heartbeat ping); or an IP address retained beyond the lifetime of the
inbound connection.

**Frequency.** Once on backend process start, then every 24 hours, jittered to
avoid a synchronized thundering herd across self-hosted fleets. Backend-only
(§4). Deliberately coarser than the Mixpanel per-action event stream it
replaces — this is a heartbeat with rollup counts, not a re-implementation of
the now-deleted Mixpanel doc's seven-row backend event inventory.

## 2. License-check endpoint

Consumed by epic 9 (License enforcement and graceful lapse); not implemented by
this issue. Shapes only:

**Request**

| Field | Type | Example | Purpose |
|---|---|---|---|
| `contract_version` | int | `1` | Schema version, same as §1. |
| `install_id` | UUID v4 | `"a1b2c3d4-...-000000000001"` | Same install identity as the heartbeat ping. |
| `license_key` | string | `"tsy_live_..."` | The self-hoster's license key, present only on this call. |

**Response**

| Field | Type | Example | Purpose |
|---|---|---|---|
| `status` | enum: `valid` \| `invalid` \| `unreachable` | `"valid"` | License validity as of this check. |
| `expires_at` | ISO-8601 UTC or null | `"2027-03-01T00:00:00Z"` | When the license expires; null for perpetual/open. |
| `entitlements` | string[] | `["paid-conformance", "paid-groundedness"]` | Which paid capabilities this license unlocks. |

`unreachable` degrades to "never blocks" — a self-hosted instance that cannot
reach `home.tessary.ai` keeps running exactly as it was, matching epic 9's gate
language and the #991 divergence-log note on the same contract (price-book sync
degrades the same way: falls back to the bundled data, a manual override always
wins).

## 3. Opt-out env var

`TESSARY_TELEMETRY_ENABLED`, default `true`, following the existing
`TESSARY_ANALYTICS_ENABLED` truthy-parsing convention
([`config-keys.md`](./config-keys.md)).

When set `false`: zero outbound network calls — including DNS resolution to
`home.tessary.ai` — from the backend process (§4), covering both the heartbeat
ping (§1) and the license-check call (§2). This is written precisely enough for
#1197's `check-zero-egress.sh` (epic 7 clause 9, shipped) to assert
mechanically: with the var off, no attempt to resolve or reach
`home.tessary.ai` may occur anywhere in a boot-to-triage run — proven via a
DNS-sink network, with `TESSARY_TELEMETRY_ENABLED=true` run as the positive
control that must log exactly `home.tessary.ai`.

## 4. Scope note: backend-only

The replacement client is backend-only — one phone-home endpoint per the
component ledger ("`core` \| open \| ... the Mixpanel `analytics` package is
replaced by one phone-home endpoint, `home.tessary.ai`, for telemetry, license
checks, and future needs, with no third-party SDK or token in open code",
tessary-paid/OPEN-CORE.md) and #858's own text ("the replacement client points at
`home.tessary.ai`", singular). Frontend Mixpanel calls are removed outright,
not replaced with their own `home.tessary.ai` path.

This is this doc's reading of the existing decisions, not an unquestionable
fact — if #858 turns out to need a frontend leg later, §5's additive-only
versioning rule accommodates it without reopening this doc's core shape.

## 5. Versioning rules

`contract_version` is additive-only for new optional fields — a downstream
consumer on an older version simply doesn't see the new field, no doc change
required to keep working. This is exactly the mechanism epic 13's future
price-sync field (#991) would use to extend the ping payload.

Any field removal or semantic change (a field's meaning or type changing under
the same name) requires a documented major version bump, with a changelog entry
appended to the bottom of this file recording what changed and why.

## 6. Owner

The `home.tessary.ai` service itself lives outside this repo. Owner: **the Tessary
maintainers (security@tessary.ai)** — the service is not yet built; the owners have taken
responsibility for building and hosting it.

## Changelog

- 2026-09-01 — `contract_version` 1. Initial contract: ping payload, license-check
  endpoint, opt-out env var, scope note, versioning rules, owner. (#859)
- 2026-09-01 — The §1 heartbeat client lands: `analytics` (Mixpanel) is deleted outright — backend
  package and frontend `lib/mixpanel.ts` + all 13 call sites — and replaced by `core`/`surfaces`'s
  `telemetry` package, built to this contract. §2 (license-check) stays spec-only. (#858)
