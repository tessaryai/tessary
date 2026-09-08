# Config keys

All runtime config binds through `@ConfigurationProperties` classes discovered by
`@ConfigurationPropertiesScan`. Most live in `core`'s `config/` package; the handful that
belong to a feature carry that feature's module in the table below (`telemetry/` in `core`,
`featureflags/` in `tenancy`, …). Documented `@Value`
exceptions are listed at the bottom. Spring relaxed binding maps each dotted key to a
`SCREAMING_SNAKE_CASE` env var (e.g. `evals.rca.batch-size` → `EVALS_RCA_BATCH_SIZE`)
whether or not the key is declared in `application.yaml` — undeclared keys take
their default from the Java field.

Worker cadences (`heartbeat-ms` / `drain-interval-ms` / `schedule.heartbeat-ms`)
are bound directly by `@Scheduled(fixedDelayString=…)` on each worker, never on a
`*Properties` class.

Feature gating is a different axis — capabilities, their platform defaults and
LaunchDarkly targeting belong to the capability layer, not to this page.

## Prefix → class map

| Prefix | Class | Covers (key defaults) |
|---|---|---|
| `evals.*` (core) | `config/EvalsProperties` | `jdbc-url`, `db-username`, `db-password`, `secret-key`, plus `site-domain`/`tls-mode`/`acme-email` (bound from the bare `SITE_DOMAIN`/`TLS_MODE`/`ACME_EMAIL` env vars below, not `EVALS_*`) |
| `evals.telemetry.*` | `telemetry/TelemetryProperties` | the `home.tessary.ai` heartbeat ping: `enabled` (true, opt-out, D6). No token — the contract carries no third-party credential. See [`telemetry-contract.md`](./telemetry-contract.md) |
| `evals.observer.*` | `config/ObserverProperties` | what the sandbox agent lanes lease and run on. Track A removed the observer worker itself, and this prefix is what outlived it: `lease-seconds` (1800) — read by `BehaviorTriageWorker`, whose jobs are agentic runs and so need a lease sized to `agentic.timeout-ms` rather than the classifier's 300s — plus `agentic.*` (sandbox `e2b`, `model` — a full Bedrock inference-profile id, `global.anthropic.claude-sonnet-5`; a bare foundation-model name 400s at the launcher — `remediate` false, timeout, `max-turns` (40) and `max-cost-usd` (3.00)) and `encoder.url`/`encoder.api-key` (classify-service). RCA and triage both read `agentic.model`. `max-turns` is threaded into the microVM agent's `maxSteps`, which forces a text-only reply at the cap rather than killing the run, so a capped run still lands a (confidence-qualified) ruling; `max-cost-usd` is checked POST-HOC against the run's booked cost and only FLAGS — a structured OPS log line plus a span attribute — because by the time the number is known the money is already spent. **The prefix is a historical name, kept because renaming it would break every deployment's env in a release that changes nothing else** — it does not imply a surviving observer |
| `evals.classifier.*` | `config/ClassifierProperties` | async classifier-detection worker (formerly `evals.signal.*`/`SignalProperties` — persisted strings/tables stay `signal`): `batch-size` (200), `lease-seconds` (300), `max-attempts` (5), `dead-letter-cooldown-seconds` (1800), `classifier-sample-limit` (100), `classifier-min-examples` (4), `thread-char-budget` (8000 — the encoder classifier's reduced conversation-thread cap; turns are compact (assistant prose capped, tool payloads collapsed to terse markers) so the reduction keeps the baseline head + earliest failure marker + most-recent turns and marks the elided middle rather than a naive oldest-first cut), `thread-recent-turns` (3 — how many most-recent turns the reduction always keeps intact when it must evict), `thread-max-observations` (40 — session rows loaded per thread assembly), `behavior-fit-idle-interval-ms` (21600000 / 6h — a ceiling, not a period: how stale an idle behaviour profile may get before the fit re-runs it with no trace delta, jittered per profile by a stable hash of its id over `(interval/2, interval]` so idle profiles don't all come due in one burst; the fit's enqueue scope is otherwise "profiles the sweep has folded traces into since their last fit", because without a delta the fit recomputes the identical row, while graduation and quarantine expiry still answer to the clock and are measured in days — **paid only**: the field lives on this open class but its sole reader is `BehaviorProfileWorker` in `tessary-paid/behavior-drift`), `triage-config-retry-seconds` (1800 — how long a triage job waits before re-checking a missing or unusable org credential). Cadence: `evals.classifier.heartbeat-ms` (60000) in `ClassifierWorker`, `evals.classifier.catalog-resync-ms` (60000) in `ClassifierCatalogWorker` (the built-in catalog reconcile — deliberately a SEPARATE cadence from the sweep heartbeat, and a separate project scope: the sweep enqueues for projects with observations, while the reconcile scans every active project, because which classifiers an org has answers to the capability flag layer and must not wait on that project having ingested a trace. Raising it only delays how long a freshly-flipped flag takes to reach an idle project; nothing downstream waits on a reconcile), `evals.classifier.behavior-fit-ms` (900000) in `BehaviorProfileWorker` — **paid only** (`tessary-paid/behavior-drift`); the key is inert in the open edition, which ships no behaviour-drift fit worker — and `evals.classifier.behavior-triage-ms` (300000) in `BehaviorTriageWorker` (which leases from `evals.observer.*`, not `lease-seconds` above — its jobs are agentic runs budgeted against `evals.observer.agentic.timeout-ms`, so a 300s lease would let a long run be re-claimed and spawn a second microVM) — the behaviour-drift fit pass is a whole-profile job (graduation bars, D2 re-quantile, alphabet refit, retention), deliberately far rarer than the per-batch sweep. Autonomous triage adds `triage-interval-ms` (900000 / 15m), `triage-min-trace-count` (2), `triage-reopen-recurrences` (3), `triage-reopen-window-hours` (168 / 7d), `triage-breaker-failures` (3), `triage-breaker-cooldown-seconds` (300) and `triage-mcp-base-url` (blank) — the recurrence bar `TriageAutoEscalator` runs under, the launcher breaker that parks the drain when the sandbox launcher is refusing everyone, plus the door the ruling itself reads through. There is no per-tick or per-project ration: every eligible finding is scheduled and the queue holds the backlog. A tool-error finding closed by a ruling (`artifact` or `unclear`) folds its judged window into that tool's reference and clears the arm it fired on, so a dismissed spell stops re-firing on evidence the ruling already dismissed — `sound` opens a case and touches no detector state. `triage-mcp-base-url` is the publicly reachable API origin the triage microVM calls back on, the same value `evals.rca.agentic.mcp-base-url` carries; blank is a broken deployment rather than a degraded one, because the triage dossier is the detector's own numbers and nothing else — an agent with no MCP cannot open a single row of the population it is auditing, so the engine refuses to run and the job dead-letters. They apply only where `triage_automatic_enabled` is targeted on for the org; with the flag off the scheduler ticks and does nothing. The per-project number is a RUNAWAY bound rather than a budget: triage decides whether a finding is ever seen (`sound` opens a case, `artifact` and `unclear` close it), so rationing runs would ration what the product notices — it is sized above what a healthy project produces, so reaching it is itself the signal. The two `reopen` keys are the recovery that makes closing on `unclear` safe: R firings within W send a closed finding back through triage once, and a finding that has had its two looks and closed again opens a case directly. The rest of behaviour drift's operating point is per-project data on the signal's `config_json`, not a deployment key — see `BehaviorDriftConfig`; `behavior-fit-settle-delay-ms` (60000 ms: once a LEARNING profile's corpus stops growing, its next fit is due this long after the last, until arming's sustained quiet fits are taken; a floor, the `behavior-fit-ms` tick is the period, #1248 — **paid only**, same as `behavior-fit-idle-interval-ms` above). Conformance sub-block, `evals.classifier.conformance.*`: `encoder-mode` (`http`), `encoder-model-dir`, `encoder-checkpoint` (the `in-jvm` mode's local ONNX checkpoint, paid only), `encoder-concurrency` (2), `max-turns-per-sweep` (500), `min-confirmations` (2) |
| `evals.ingest.*` | `config/IngestProperties` | media resolver bounds: `max-media-bytes` (8 MiB — bounds documents too, since #985; see [`media-contract.md`](./media-contract.md) for why one shared cap is a deliberate, real per-item limit rather than an inherited one), `max-media-fetches-per-page` (10) |
| `evals.ingest.spool.*` | `config/IngestSpoolProperties` | which `IngestSpool` buffers accepted batches (#984): `mode` (`memory`, the default: in-process, bounded by `evals.ingest.substrate.queue-max-bytes`, a restart loses what is queued; `kafka`: a Kafka-API broker, opt-in, accepted means persisted) and `max-lag-ms` (300000): the age of the oldest unprocessed batch past which the `/actuator/health/ingest` group (platform staff) and the top-level status are DOWN, `0` to disable, and the `kafka.*` block read only in that mode: `bootstrap-servers` (`redpanda:9092`, the bundled single-node Redpanda under `docker compose --profile kafka`), `topic` (`tessary.ingest`), `dead-letter-topic` (`tessary.ingest.dead-letter`, where a batch that exhausted its write retries is parked and committed so nothing is dropped and nothing blocks the partition), `group-id` (`tessary-ingest`), `partitions` (8, keyed by project so one project's batches stay ordered on one partition), `replication-factor` (1, all the bundled single-node Redpanda can satisfy; applied at topic creation, so it must be set before the first boot, and against a multi-broker cluster it is what makes an acknowledged batch survive losing a broker — above 1 the spool also sets `min.insync.replicas=2` so `acks=all` means two copies), `consumers` (4: drainer threads, one consumer each in the group, so throughput scales with cores while a project's partition is drained by one of them at a time; `memory` mode always runs one drainer), `max-message-bytes` (8 MiB, above which the edge answers OVERSIZE for the producer to split), `publish-timeout-ms` (10000, the wait for the broker's `acks=all` before a publish is a shed) |
| `evals.ingest.substrate.*` | `config/SubstrateProperties` | async substrate write path: `queue-max-bytes` (**64 MiB**, the `memory` spool's ceiling — the hand-off queue's real ceiling, in payload bytes measured at admission and released after the drain. A full queue SHEDS the batch rather than blocking, and the ingest edge answers the producer with a retryable error — HTTP `503` + `Retry-After`, gRPC `UNAVAILABLE` — instead of a `200`, so a stock exporter re-sends into the idempotent write path. Bytes rather than batches because a batch holds its entries' payloads inline: in one bulk upload they ranged from 10 spans to 825 spans and 24.5 MB, so a batch count bounded nothing and a queue at half its slot capacity had already taken the heap. Every reference implementation that started with a count added a byte bound afterwards — Jaeger `queue-size-memory`, Datadog `forwarder_retry_queue_payloads_max_size`, the OTel Collector's `sending_queue.sizer: bytes`), `refuse-above-queue-fraction` (**0.8** — above this share of the byte budget the OTLP receiver refuses a push BEFORE decoding its body, answering `503` + `Retry-After`. The shed above is the last line and fires only after the batch is already in the heap it was meant to protect; this is the OTel Collector's `memorylimiterextension` / Phoenix `is_not_at_capacity` pattern. `0` disables it), `max-attempts` (3), `retry-backoff-ms` (250). v2 span resolvers: `resolver-batch-size` (500) rows per pass and `resolver-interval-ms` (1000) between passes for `ingest/substrate/v2/PathResolver` (ancestry fixpoint) and `CorrelationBackfiller`, plus `resolvers-enabled` (true) — an ops kill switch, not a rollout flag: both beans tick every second, and an operator must be able to stop a background job competing with ingest for connections. v2 trace rollups: `rollup-enabled` (true — the same kind of kill switch; with it off the deadlines stay armed on the rows, so turning it back on drains the backlog), `rollup-interval-ms` (1000) and `rollup-claim-limit` (500) for `ingest/substrate/v2/TraceRollupWorker` (the §7.3 claim + §7.2 replacement recompute), and `rollup-reap-interval-ms` (60000), `rollup-reap-grace-seconds` (300), `rollup-stale-after-ms` (60000) for `TraceRollupReaper` (the §7.4 crash-fingerprint sweep, plus a log-only ERROR when the oldest due deadline falls further behind than `rollup-stale-after-ms` — because a rollup queue that stops draining is otherwise invisible: every trace surface keeps serving the last numbers written, with no gap and no error). The v1 → v2 backfill was a one-time cutover job; it and its config keys were deleted, along with `substrate_v2_id_map` and `substrate_v2_backfill_cursor`, once the sweep converged (the teardown release) — `SubstrateProperties` has no `backfill-*` field any more. Cadence: `evals.ingest.substrate.report-ms` (60000) in `IngestThroughputReporter` — the `ingest.throughput` heartbeat the ingest dashboard is built from, which also carries the v2 span counters, the §7.6 span-lateness histogram and the rollup worker/reaper/queue-depth counters |
| `evals.retention.*` | `config/RetentionProperties` | retention enforcement: `enabled` (true, the kill switch — `false` deletes nothing), `interval-ms` (3600000), `batch-size` (5000), `max-batches-per-sweep` (20), and the platform default TTLs `trace-ttl-days` (90), `detection-ttl-days` (90 — the per-classifier detection tables, now defined in `0000-baseline.sql` after the migration-history squash; `embedding-ttl-days` was retired with the vector substrate it governed, #1116). A `retention_policy` row for a `(project, data_class)` overrides the matching TTL; `0` means keep forever. `media-grace-hours` (24) is not a TTL: unreferenced media is collected at any age, and this is only the window that keeps the collector off bytes a batch still in flight has stored but not yet referenced. The two bounds cap one statement and one project-class per pass, so a first sweep after a long unenforced period drains over passes instead of locking a table |
| `evals.pricing.*` | `config/PricingProperties` | rate import: `enabled` (true, the kill switch — `false` imports no book, so every model prices as unpriced) and `import-interval-ms` (86400000). `pricing/PriceBookImporter` imports the checked-in `litellm-model-prices.json` into `price_book`/`model_price` on `ApplicationReadyEvent` and daily. There is deliberately no key for the rates themselves: a version is the hash of the file's bytes, so a price change is a reviewed diff and a new book, never configuration |
| `evals.ingest.otlp.*` | `config/OtlpReceiverProperties` | OTLP receiver: `transport` (`HTTP` \| `GRPC` \| `BOTH`), `grpc-port` (4317), `max-spans-per-request` (2000), `max-body-bytes` (20 MiB — lowered from 32 MiB after protobuf-decode OOMs; matches the OTel Collector's `confighttp` default) |
| `evals.priors.*` | `config/PriorsProperties` | cross-customer priors: `enabled` (false), `min-cohort` (5, ≥2 enforced), `epsilon` (1.0), `sensitivity` (1.0) |
| `evals.model-catalog.*` | `config/ModelCatalogProperties` | live per-provider model-catalog fetch (`ModelCatalogFetchService`): `refresh-interval` (15m — how long a fetched (provider, region) entry is served before refetch), `fetch-timeout` (5s — per-call ceiling on one provider's models endpoint) |
| `evals.intelligence-mode.*` | `config/IntelligenceProperties` | `single-tenant` (true) — the cross-customer analysis boundary |
| `evals.metering.*` | `config/MeteringProperties` | usage-metering rollups: `storage-enabled` (false), `claim-batch` (50), `lease-seconds` (600). Cadence: `evals.metering.heartbeat-ms` (300000). Daily platform-spend report: `spend-warn-usd-per-org-per-day` (25.0, a log level and **not** a cap — see below), `spend-report-org-limit` (50), `evals.metering.spend-report-heartbeat-ms` (3600000) |
| `evals.rca.*` | `config/RcaProperties` | RCA worker: `batch-size` (5), `lease-seconds` (1200 — must exceed `agentic.timeout-ms`), `max-attempts` (5). Cadence: `evals.rca.heartbeat-ms` (15000) |
| `evals.sop.*` | — (constants in `tessary-paid/sop`'s `SopCompileWorker`) | SOP-compile worker: claims `sop_compile` jobs, compiles through the compile service, installs the bundle. Cadence: `evals.sop.compile-heartbeat-ms` (60000). **Read only by the paid `sop` module since #842** — the open edition ships no consumer, so the key is inert there rather than removed |
| `evals.rca.agentic.*` | `config/RcaProperties.Agentic` | the sandboxed agent session that performs every RCA: `launcher-url` (required — RCA has no other analysis path), `launcher-api-key`, `timeout-ms` (900000), `max-turns` (40 — triage's `maxSteps` mechanism, see `evals.observer.agentic.*`; RCA carries no cost cap), `mcp-base-url` (blank is a broken deployment, not a degraded mode — the dossier is the finding's claim, its numbers and the measured checklist, and every trace behind them is fetched over MCP, so the engine refuses to run without it and the job stamps `failed`; same publicly reachable origin as `evals.classifier.triage-mcp-base-url`). No `model`: RCA and triage share one launcher and microVM image, so both read `evals.observer.agentic.model` (a historical prefix — see that row). The repository is NOT required — a project with no git integration gets an evidence-only run with a stated ceiling, rather than a refused press |
| `evals.alert.*` | `config/AlertProperties` | alerting engine: `default-digest-cron` (08:00 UTC), `cron-zone`, `lease-seconds` (300), `app-base-url` (empty — the SPA origin a case-opened alert links back to; empty means the message carries no link rather than a broken one). Cadence: `evals.alert.heartbeat-ms` (60000) |
| `evals.predeploy.*` | `config/PreDeployProperties` | pre-deploy checks: `enabled` (false) |
| `evals.redaction.*` | `config/RedactionProperties` | PII redaction write-path guard: `enabled` (true) — the kill switch. Note the platform default RULES are code, not config (`BuiltInRedactionRules`), and are reconciled onto every project by name |
| `evals.slack.*` | `config/SlackProperties` | How the backend reaches the Slack ADAPTER: `base-url` (e.g. `http://slack:8090`), `service-key` (shared both ways; must equal the adapter's `SLACK_SERVICE_KEY`), `timeout-seconds` (20). **Slack's own credentials are no longer here** — the signing secret and bot token live in the Slack adapter service (`tessary-paid/slack-service/`, not part of the public export), so this process holds nothing that could post to Slack. Stays OPEN after #842 took `slack/` to the overlay: `alert/channel/SlackDelivery` (the per-project incoming-webhook channel, a different feature) binds it, and `tessary-paid/slack/SlackMentionController` — paid since #920, paid → open — reads it to compare `service-key` |
| `evals.sop.compile.*` | `config/SopCompileProperties` | How the backend reaches the SOP **compile** service: `url` (e.g. `http://compile:8100`), `api-key` (must equal its `COMPILE_SERVICE_KEY`), `encoder` (`gte`), `fit-window-conversations` (2000 — a bound on the WINDOW, never on any turn's content), `timeout-seconds` (900). Blank `url` → the worker DEFERS each claimed job (returns it to pending without burning an attempt) rather than parking it, so configuring the service later still compiles the SOPs imported before it. Stays OPEN in `core` after #842: `tessary-paid/sop`'s worker AND `tessary-paid/conformance`'s two compile classes both read it, paid → open, and the ledger's `core` row is bucket open |
| `evals.git.github.*` | `git/github/GithubAppProperties` | GitHub App: `app-id`, `private-key-pem`, `webhook-secret`, `app-slug`, `client-id`, `client-secret` |
| `evals.auth.*` | `auth/AuthProperties` | `disabled` (`EVALS_AUTH_DISABLED`, default **false**): true AND no configured identity provider ⇒ `AuthFilter` serves every request unauthenticated; either alone ⇒ auth is enforced. The repo's only negative-polarity switch, deliberately: an `enabled` key safe by default is one nothing ever prints, whereas `EVALS_AUTH_DISABLED=true` is greppable and reads as alarming wherever it appears (#924). Also owns the provider-agnostic session/app config every `AuthProvider` adapter shares (#851): `cookie-password`, `cookie-name` (`evals-session`), `cookie-max-age-seconds` (7 d), `cookie-secure` (false; `application-production.yaml` → true), `frontend-url`. Under the `production` Spring profile, a blank `cookie-password` — or no enabled `AuthProvider` at all — makes `auth/AuthRequiredInProdGuard` (`@PostConstruct`) throw `IllegalStateException` and abort startup, rather than the auth filter's normal runtime 401. That guard's original reason for existing (absent WorkOS config silently opened every request) stopped being true at #924 — missing config now fails closed in every profile on its own — but it stays because it fails a misconfigured production box LOUDLY at boot instead of leaving it up and 401-ing everyone until someone notices |
| `workos.*` | `auth/WorkOsProperties` | WorkOS-specific only, since #851 split off the provider-agnostic session config above: `api-key`, `client-id`, `redirect-uri`. Consumed only by `WorkOsClient` (via `AuthProviderConfig`), one of two `AuthProvider` adapters today — the other is `PasswordAuthProvider`, the dependency-free email/password provider that is the open edition's default (#852/#996); `AuthProviderConfig` picks WorkOS when both `WORKOS_API_KEY`/`WORKOS_CLIENT_ID` are set and falls back to the password provider otherwise |
| `evals.platform.*` | `config/PlatformStaffProperties` | `staff-emails` (`EVALS_PLATFORM_STAFF_EMAILS`, default empty = **nobody**): comma-separated, lowercase-matched allowlist of identities checked by `PlatformStaff`. In **every build, open included**, `PlatformStaff#isStaff` gates `AuthFilter`'s bypass for the whole `/actuator/**` surface (`env`, `heapdump`, etc. — everything but the two health probes): any authenticated principal on this list gets read access to JVM heap contents and environment variables, so treat it as a credentials-adjacent allowlist even with `tessary-paid/` deleted. Paid-only on top of that: `PlatformStaff#canAdminister` (owner/admin standing in the target org, additionally) lets a listed identity administer another org's plan tier by hand via `tessary-paid/plan/PaidPlanController` — inert in the open build |

## Documented `@Value` exceptions

These `evals.*` keys are `@Value`-injected directly and are **not** fields on any
`*Properties` class:

| Key | Location | Default |
|---|---|---|
| `evals.grader.cache.ttl` | `llm/ChatModelFactory`, `llm/LlmCaller` | `5m` — a *requested* TTL, see below. The key's name is historical: it is the Bedrock **prompt-cache** TTL for every lane, and outlived the grader it was named for |

`evals.judge.default-bedrock-region`, `evals.judge.platform-bedrock.enabled` and
`evals.judge.default-bedrock-model-id` lived here until **#939 D4** removed the ambient-identity
lane resolver they gated (`ChatModelFactory#resolve(projectId, ModelLane)`,
`resolvePlatformBedrock`, its mantle twin) — a grep at removal time found ZERO production callers
of any of it (RCA/TRIAGE have always resolved through `ProjectModelSettings`, never through
`ChatModelFactory`), so the removal changed no live behavior. Every RCA/TRIAGE run now resolves the
org's own `ProviderCredential` unconditionally, in every edition, and fails closed with
`MISSING_CREDENTIALS` when there is none — see [`provider-keys.md`](../guides/provider-keys.md).
| `evals.plan.default-key` | `tessary-paid/plan/EntitlementService` | `free` — **paid only** since open-core epic 1 issue 1 removed plan tiers from the open `CapabilityService`; the key now configures only the paid entitlement engine's fallback plan tier, and no longer anything on the open edition's capability resolution. The integration suite no longer sets it to `enterprise` — see `CapabilityFixture` |
| `evals.classifier.encoder-dependency-report-ms` | `classifier/EncoderDependencyReporter` | `86400000` — how often the "can classify-service be scaled to zero" line is emitted. Also fires once on boot |
| `evals.media.cache.max-bytes` | `storage/CachingMediaStore` | `0` (falls back to the class's own `64 MiB` default) — the in-memory LRU read cache in front of `MediaStore`, sized for image-scale objects (a handful of images per grader call); documents up to the `evals.ingest.max-media-bytes` (8 MiB) cap fit the same budget without resizing it. Previously undocumented — closed alongside #985 |

`evals.mantle.*` (`config/MantleProperties`) is the **second** Bedrock endpoint —
`region` (`us-east-1`) and `project-id` (blank ⇒ the account's `default` project).
Deliberately not `AWS_REGION`: mantle has no cross-region inference profiles and
the GPT-5.6 line is US-only, so it points somewhere else than every other AWS
call. Blank `project-id` is a working configuration, which is what keeps local
development and non-prod environments running without their own Project. The two
endpoints sit in two IAM namespaces and a grant on one gives nothing on the other: the standard
endpoint needs `bedrock:InvokeModel`, mantle needs `bedrock-mantle:CreateInference` scoped to the
Project's ARN. Because mantle has no cross-region inference profiles, trace content graded on a
mantle model leaves the region every other platform-funded call stays inside.

The agentic sandbox reaches mantle through the launcher, not this backend. `MANTLE_PROJECT_ID`
(set from `EVALS_MANTLE_PROJECT_ID` in both compose files) is still a deployment-wide launcher env
var — `MANTLE_REGION` is NOT: as of **#939 D4** it was removed from the launcher's env along with
every other AWS credential var, and the region for a mantle run now comes per-request from the
org's own `credential.aws_region`. The launcher no longer signs mantle calls with any credential of
its own (dedicated IAM user or instance role): every `/rca` and `/triage` request carries the org's
own decrypted `ProviderCredential` (`aws_access_key`/`aws_secret_key`/`aws_region`), and an
`iam_role`-mode credential is explicitly refused before it ever reaches the launcher.

`evals.grader.cache.ttl` is a **request**, not a guarantee: the supported TTL menu is per
model (Claude Haiku 4.5 takes `5m` or `1h`, Amazon Nova 2 Lite takes `5m` only) and Bedrock
rejects an unsupported TTL outright rather than degrading. `llm/BedrockModelProfile.clampTtl`
downgrades to the model's shortest supported TTL and logs a `WARN` when it does, so one global
value stays usable across models with different menus.

## Env vars declared in `application.yaml`

Only these are declared with `${…}` defaults in `application.yaml`; every other
key relies on relaxed binding straight into its Properties class.

| Env var | Default | Notes |
|---|---|---|
| `EVALS_JDBC_URL` / `EVALS_DB_USERNAME` / `EVALS_DB_PASSWORD` | (empty) | blank JDBC URL fails fast (`DataSourceConfig`) |
| `EVALS_SECRET_KEY` | (empty) | base64 32-byte AES-GCM key (`SecretBox`). `docker-compose.yml` supplies a published placeholder so a self-host boots unconfigured; `PlaceholderSecretGuard` refuses the boot on it once `SITE_DOMAIN` is set (#1230) |
| `SITE_DOMAIN` | (empty) | **The one key bound from a bare, un-prefixed name**, into `evals.site-domain`. Deliberate: `docker-compose.yml`, `.env.example` and `frontend/caddy/render.sh` already share that spelling, and a second one would be a second thing to keep in sync. Empty means "localhost only", which is what keeps `PlaceholderSecretGuard` inert in every non-compose boot (#1230) |
| `TLS_MODE` | `acme` | Bound bare into `evals.tls-mode` for the same reason (#1225): how the frontend serves `SITE_DOMAIN` (`acme`, `owncert`, `upstream`). `PublicOriginGuard` rejects any other value, requires `ACME_EMAIL` in `acme` mode, requires `SITE_DOMAIN` to be a bare hostname, derives `evals.auth.frontend-url` and `workos.redirect-uri` from it when they are still on a localhost default, and refuses the boot when either names a different host |
| `ACME_EMAIL` | (empty) | Bound bare into `evals.acme-email`; the Let's Encrypt account address, read only by the guard above. No default anywhere: it is the operator's |
| `EVALS_TELEMETRY_ENABLED` | `true` | `home.tessary.ai` heartbeat ping, opt-out (D6). No frontend leg — see [`telemetry-contract.md`](./telemetry-contract.md) §4 |
| `EVALS_GRADER_CACHE_TTL` | `5m` | prompt-cache TTL |
| `EVALS_PLATFORM_STAFF_EMAILS` | (empty) | comma-separated staff allowlist. **Empty ⇒ nobody**. In every build, gates `AuthFilter`'s bypass for the whole `/actuator/**` surface (heapdump, env, etc.) — listed identities get read access there with no org check. Also gates `PaidPlanController`'s cross-org plan admin (owner/admin role in the target org additionally required), which is hosted-only now (`tessary-paid/plan`) |
| `APP_LOG_LEVEL` | `INFO` | Boot `logging.level.ai.tessary.evals` (+ logback `ai.tessary`) |

## Observability opt-in (#864)

Grafana Alloy, Grafana Cloud, Langfuse and Pyroscope are all **off by default** in the open build, so a self-hoster with no cloud observability account boots with zero export attempts. Two independent levers, both defaulting off — bringing the container up does not by itself turn export on, and vice versa.

| Knob | Where | Default | Notes |
|---|---|---|---|
| `COMPOSE_PROFILES=observability` | `docker-compose.dev.yml` | unset (Alloy not started) | The `alloy` service sits behind a Compose profile in the DEV file. Nothing else `depends_on` it. `task dev:profiling` adds the profile for you. The production alloy moved to `tessary-paid/docker-compose.yml`, block and profile gate both verbatim, so it still takes this same variable there — what changed is which file defines it, not how it is switched on |
| `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED` | Spring, relaxed binding of `management.tracing.export.otlp.enabled` | `false` (base `application.yaml`, every profile) | OTLP **trace** export to Alloy. `application-production.yaml` supplies endpoint/transport only and leaves this flag to the base file. `docker-compose.dev.yml` forwards it into the container; `task dev:profiling` sets it `true` |
| `MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED` | Spring, `management.logging.export.otlp.enabled` | `false` | OTLP **log** export, same wiring as the trace flag above |
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` | Spring, `management.otlp.metrics.export.enabled` | `false` | OTLP **metrics** export; was already off in the `production` profile before #864 |
| `PYROSCOPE_AGENT_ENABLED` | `tessary-paid/docker-compose.yml` → backend | `false` | Kill switch for the Pyroscope profiling agent — the open `docker-compose.yml` no longer wires this var at all (the Alloy/Pyroscope/Langfuse block moved to the paid overlay). `backend/Dockerfile`'s `-javaagent` attach is unconditional in every edition, but with the flag off (or unset, as in the open compose file) the agent never opens a connection |
| `LANGFUSE_OTLP_ENDPOINT` | `docker-compose.dev.yml` + `tessary-paid/docker-compose.yml` → `alloy` | blank | Langfuse trace target. Blank means Alloy's exporter has nothing to resolve, so opting into `observability` without Langfuse keys does not start dialing Langfuse Cloud |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | `docker-compose.dev.yml` + `tessary-paid/docker-compose.yml` → `alloy` | `pk-lf-dev-unset` / `sk-lf-dev-unset` | Deliberately fake placeholders, not blank: Alloy's `otelcol.auth.basic` treats EMPTY credentials as a fatal config-load error (exit 1), so blank defaults would crash-loop the container for anyone without Langfuse keys. Real keys in `.env` override |

## Deploy-level worker switches vs product gating

Two deliberately distinct layers:

1. **Deploy-level `enabled` switches** stay `EVALS_*` env config — they gate
   context-less worker loops and infrastructure:
   `evals.predeploy.enabled`, `evals.priors.enabled`,
   `evals.redaction.enabled`, `evals.metering.storage-enabled`. (The classifier and
   RCA workers have **no** `enabled` field — they run unconditionally and gate
   per-project/org via capabilities.)
2. **Per-request product gating** is the capability axis — see
   the capability layer. The two are deliberately not merged: a
   deploy switch turns a process off for the whole deployment, a capability decides
   what one org has.

## Platform LLM spend: reported, not capped

Every platform LLM lane — triage, RCA — is platform-funded and deliberately
**uncapped** at launch (decision D6). Track A removed grading, which was the one lane that carried a
spend ceiling of its own, and #1117 removed the assistant (the LLM_CALLS-group lane, not one of the
two named here) entirely, so there is now no monetary cap anywhere in the open tree. What stands in
for a cap is attribution:

- Every platform LLM call books to `llm_call` with its lane, and every *sandbox* run also books the
  unit of work it was for (`subject_kind` / `subject_id`). For a Layer-2 ruling that is the `finding`
  it ruled on, which is what makes "what did one ruling cost" answerable. (`subject_kind` still reads
  `behavior_finding`, the name the table had when the column was written. It is a persisted string
  and is never renamed in place — rewriting it would orphan every spend row already booked under it.)
- `PlatformSpendReporter` emits one structured line per org per closed UTC day
  (`event="llm.spend.daily"`, plus an `llm.spend.daily.total`), carrying cost, tokens, triage
  runs and the count of calls the pricing catalog held no rate for. "What did last week cost, and which
  org drove it" is a range query over those lines — there is no platform-admin UI and this is
  deliberately not one.
- `evals.metering.spend-warn-usd-per-org-per-day` (default 25.0) escalates an org's line from INFO to
  WARN. **It changes a log level and nothing else** — no request is refused, no lane is stopped. Set it
  to 0 to disable the escalation.

> **Renamed twice, and there is no alias either time.** The keys were `adjudication-*`
> (`EVALS_CLASSIFIER_ADJUDICATION_AUTO_*`, `EVALS_CLASSIFIER_BEHAVIOR_ADJUDICATION_MS`) before the
> model-lane cutover and `triage-auto-*` between it and the triage cutover; they are `triage-*` now, and
> the two budget keys changed meaning as well as spelling (a runaway bound, not a ration). A deployment
> that overrode any of them under an older name is silently back on the default, so re-set them before
> deploying. The persisted words moved with them this time: `finding.triage_*` replaced the
> `adjudication_*` columns and `job.kind='triage'` replaced `behavior_adjudication`, which
> discarded every stored ruling rather than reading one vocabulary as the other (now folded into
> `0000-baseline.sql` after the migration-history squash).

The lever that *does* bite is elsewhere and is deliberate: `triage_automatic_enabled` is off by
default, so rulings only happen when someone presses, and `TriageAutoEscalator` schedules every eligible finding
when it is on — the per-tick and per-project caps were removed, because the triage queue and its
two-microVM launcher pool are what bound the work, and a finding refused by a counter is one
nobody ever sees. What still gates it is `triage-min-trace-count`, and what stops a broken
launcher being retried into the ground is `TriageLauncherBreaker`. Per-org detail is readable in-app at
`GET /api/orgs/{orgSlug}/usage/llm/triages` (needs `BILLING_MANAGE`), which reports cost per
ruling alongside the whole-window total.
