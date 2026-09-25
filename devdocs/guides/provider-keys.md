# Bring your own model provider keys

The open build's gate is explicit about this one: a self-hoster's own model provider keys are
allowed, because they're theirs. This is the setup path verified end to end, and the seed doc the
self-host docs build on.

Credentials are per-organization, not per-project. The settings page drops any picker option whose
provider has no org credential. The platform's old no-key ambient-Bedrock fallback and Ollama (the
one credential-free provider) are both gone, and every provider now requires an org key with no
exceptions.

## Adding a key

Settings → Providers is now **org-scoped, not project-scoped**: add a key once and every project
in your organization can use it — there is no per-project override and no per-environment variant.
The page itself still lives under a project route in the UI, but the credential it writes belongs
to the org.

Pick a platform, paste its credential, Save:

- **API-key platforms** (OpenAI, Anthropic, OpenRouter, Moonshot, Gemini, GLM, Grok, and Custom —
  any other OpenAI-compatible endpoint) — an API key, optionally a base URL override (required for
  Custom, which has no default to assume).
- **TypeSafe** — an API key, optionally a base URL override. TypeSafe serves decision models
  (Jev), not chat models, so its key never runs RCA or Triage. See *Decision-model keys* below.
- **AWS Bedrock / Bedrock Mantle** — an AWS region, plus either an access key + secret key
  (`auth_mode=api_key`, the default) or an explicit opt-in to your own host's ambient AWS identity
  (`auth_mode=iam_role`) — see the IAM-role section below for what that opt-in does and does not
  cover. An optional inference-profile ARN is available for marketplace models.

A key is never echoed back once stored — `GET /providers` returns only `has_api_key` /
`has_aws_credentials` booleans and non-secret metadata (region, base URL, timestamps), never the
sealed value or the plaintext. Saving replaces a stored secret; leaving a secret field blank on an
update keeps the existing one untouched (`ProviderCredentialController#upsert`'s keep-vs-replace
semantics).

## The fresh, empty state

An org with no stored credentials shows every catalog platform as **Not configured** — not an
error, and there is no "no key needed" platform any more (Ollama, the one exception, was removed).
That is the state a brand-new self-host install starts in, and it is a real, tested state, not a
placeholder: `BYO_PROVIDER_KEYS` is **on** by default, through `CapabilityService`'s own default
(every capability is on except the ones it names as off by default). It was off for hosted orgs
previously; once the last platform-funded lane was removed, that default no longer held. Nobody has to flip a flag to reach this page; it's there
from the first boot. **Every org now needs at least one commercial provider key configured before
the first RCA or Triage run can succeed** — there is no keyless default any more, in either
edition.

## The picker shows what you can actually run

The Models settings page (per-project, per-lane model selection) offers only the providers the org
has a credential for — an unconfigured provider's options are dropped from the picker entirely, not
shown disabled. If a lane was explicitly pinned to a provider whose credential has since been
removed, the picker falls back to "Automatic" and shows a small note ("`<model>` is no longer
available") rather than keeping the stale option visible. Saving an explicitly-chosen
model whose provider has no configured credential is refused at write time
(`ModelConfigError.PROVIDER_NOT_CONFIGURED`) — seeing that error instead of a disabled picker
option is itself a bug report, not something you should have to work around.

## What happens if you leave a lane on "no selection"

The platform's old ambient-Bedrock fallback (gated by
`tessary.judge.platform-bedrock.enabled`) is **gone entirely** — it had no production caller left
(RCA and TRIAGE have always resolved through `ProjectModelSettings`, never through that path), so
removing it changed no live behavior, only deleted dead code and a stale doc claim. If a project's
RCA or TRIAGE lane has no explicit row — a row is only ever written by an explicit choice on the
settings page, never seeded — it resolves automatically to the highest-priority provider (per
`LanePriority`) the org holds a credential for: Bedrock leads RCA's order, GLM leads TRIAGE's, and
any of the ten supported providers can end up serving the lane depending on what the org has
configured. If the org has configured no provider at all, the run fails closed with the typed
`MISSING_CREDENTIALS` error. There is no silent "whatever AWS credentials the host happens to
carry" path any more, in either edition.

## Decision-model keys (TypeSafe, OpenRouter)

A decision model answers a typed question about a piece of text in one call, rather than chatting.
The Frustration classifier uses TypeSafe's Jev this way. Two keys can carry that call:

- a **TypeSafe** key, which calls `jev-latest` at `https://api.typesafe.ai/v1/systemone`;
- an **OpenRouter** key, which calls `typesafe/jev-latest` at
  `https://openrouter.ai/api/alpha/decisions`. The same key keeps serving chat models on the RCA
  and Triage lanes.

Which one runs is the **Frustration** lane on the Models page, in its own "Decision models"
section. The lane offers a provider select and nothing else: each provider serves one decision model,
and there is no tier or effort to set. Left on Automatic it takes TypeSafe when the org holds both
keys; pinning OpenRouter keeps it there. Only decision models can be saved on the lane, and a decision
model cannot be saved on RCA or Triage (`ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE`). A base URL
override on either credential replaces the host; a trailing `/v1` is dropped, since neither decision
path sits under it.

Each call is booked in the usage ledger on the org's own key, and priced from the price book under
`typesafe/jev-latest` on both routes: the book has no OpenRouter-specific Jev rate, so an OpenRouter
markup, if any, is not in the booked figure. OpenRouter's own reported cost is kept with the call's
raw response for audit. Only `jev-latest` is offered, with no pinned versions; the version that
answered each call is recorded with it.

A rejected key (HTTP 401 or 403) fails with `DECISION.PROVIDER_REJECTED` and is not retried.

The Providers page marks TypeSafe "Used by Frustration" (the catalog's `used_by` on that platform),
since that is the only thing its key does. Enabling Frustration without a key its lane can run on is
refused with `CLASSIFIER.PROVIDER_REQUIRED`; the Catalog's enable dialog picks the provider, takes the
key when the org has none, and sets the lane before it enables. When the provider later refuses the
key, or the key is deleted, the classifier pauses (`readiness` reads `provider_rejected` or
`no_provider`, shown on the Catalog row and rail) and sends nothing until the key works again. Saving
the key the lane runs on lifts the pause at once, as does the rail's Retry or any re-enable; otherwise
the sweep re-checks every `tessary.frustration.credential-retry-seconds`.

## The `auth_mode=iam_role` opt-in — what it does and does not cover

A Bedrock or Bedrock-mantle credential can opt into `auth_mode=iam_role`: the backend's own direct
calls (the judge lane, when reached in-process) then use your host's ambient AWS identity — an
instance role or the SDK's default credential chain — instead of the sealed access/secret key pair.
**This opt-in is explicit and never inferred** from blank key fields, so a credential row written
before this field existed keeps failing closed with `MISSING_CREDENTIALS` rather than silently
reaching for an ambient identity nobody asked it to use.

**It does NOT cover RCA or TRIAGE.** Those lanes run the agent in an isolated sandbox launched by
`sandbox-runner` — a fresh sibling Docker container by default, or an E2B microVM if the operator
opts into `SANDBOX_BACKEND=e2b` — and neither backend has
a way to assume your backend process's own AWS identity: every request carries its credential
explicitly, with no ambient-identity relay for either of them, by design (adding one would be new
scope no decision here authorizes). A Bedrock/mantle credential
selected for an agentic (RCA/TRIAGE) lane **must** be `auth_mode=api_key`; an `iam_role`-mode
credential resolved for one of those lanes fails closed with a typed error naming the reason
(`ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED`) rather than silently falling back to something
else.

## Verifying it end to end

1. Add a key for one paid platform (e.g. OpenAI) on your org.
2. Pin a model from that platform for the RCA or TRIAGE lane, if you want to be certain it's the
   one running.
3. Run an RCA investigation or a triage escalation against a project in that org.
   `AgenticCredentialResolver#resolve` fails closed with
   `MISSING_CREDENTIALS` for any platform with no stored org key — there is no platform-funded
   exception left at all.

## See also

- [reference/principles.md](../reference/principles.md#product--positioning) for the
  no-shared-training guarantee that also governs how a project's own data is (and isn't) used.
- `backend/llm-runtime/src/main/java/ai/tessary/llm/ProviderCredentialController.java`,
  `AgenticCredentialResolver.java` and `decisions/JevDecisionClient.java`
  for the code this doc describes.
