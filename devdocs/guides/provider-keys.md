# Bring your own model provider keys

The open build's gate is explicit about this one: a self-hoster's own model provider keys are
allowed, because they're theirs. This is the setup path issue #861 (epic 2) verified end to end,
and the seed doc epic 7's self-host docs build on.

**#939 rewrote this doc almost entirely.** Credentials moved from per-project to per-organization
(D1), the settings page now drops any picker option whose provider has no org credential (D3),
the platform's old no-key ambient-Bedrock fallback and Ollama (the one credential-free provider)
are both gone (D4/D6), and every provider now requires an org key with no exceptions.

## Adding a key

Settings → Providers is now **org-scoped, not project-scoped**: add a key once and every project
in your organization can use it — there is no per-project override and no per-environment variant.
The page itself still lives under a project route in the UI, but the credential it writes belongs
to the org.

Pick a platform, paste its credential, Save:

- **API-key platforms** (OpenAI, Anthropic, OpenRouter, Moonshot, Gemini, GLM, Grok, and Custom —
  any other OpenAI-compatible endpoint) — an API key, optionally a base URL override (required for
  Custom, which has no default to assume).
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
placeholder: `BYO_PROVIDER_KEYS` is **on** by default in both editions — the open edition through
`CapabilityService`'s own default, and the hosted product through `Capability.BYO_PROVIDER_KEYS`. It
was off for hosted orgs until #1282; #939 (D4) removed the last platform-funded lane, but the flag's
default was not revisited until #1282, which flipped it once the old off-by-default reasoning no
longer held (that constant's javadoc carries the reasoning). Nobody has to flip a flag to reach this page; it's there
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

The platform's old ambient-Bedrock fallback (`ChatModelFactory#resolvePlatformBedrock`, gated by
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

## The `auth_mode=iam_role` opt-in — what it does and does not cover

A Bedrock or Bedrock-mantle credential can opt into `auth_mode=iam_role`: the backend's own direct
calls (the judge lane, when reached in-process) then use your host's ambient AWS identity — an
instance role or the SDK's default credential chain — instead of the sealed access/secret key pair.
**This opt-in is explicit and never inferred** from blank key fields, so a credential row written
before this field existed keeps failing closed with `MISSING_CREDENTIALS` rather than silently
reaching for an ambient identity nobody asked it to use.

**It does NOT cover RCA or TRIAGE.** Those lanes run the agent in an isolated sandbox launched by
`sandbox-runner` — a fresh sibling Docker container by default (D7), or an E2B microVM or local
process if the operator opts into `SANDBOX_BACKEND=e2b`/`local` — and none of the three backends has
a way to assume your backend process's own AWS identity: every request carries its credential
explicitly, with no ambient-identity relay for any of them, by design (adding one would be new scope
no decision here authorizes). A Bedrock/mantle credential
selected for an agentic (RCA/TRIAGE) lane **must** be `auth_mode=api_key`; an `iam_role`-mode
credential resolved for one of those lanes fails closed with a typed error naming the reason
(`ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED`) rather than silently falling back to something
else.

## Verifying it end to end

1. Add a key for one paid platform (e.g. OpenAI) on your org.
2. Pin a model from that platform for the RCA or TRIAGE lane, if you want to be certain it's the
   one running.
3. Run an RCA investigation or a triage escalation against a project in that org.
   `ChatModelFactory#resolveApiKey` and `AgenticCredentialResolver#resolve` both fail closed with
   `MISSING_CREDENTIALS` for any platform with no stored org key — there is no platform-funded
   exception left at all.

## See also

- [reference/principles.md](../reference/principles.md#product--positioning) for the single-tenant /
  no-shared-training guarantee that also governs how a project's own data is (and isn't) used.
- `backend/llm-runtime/src/main/java/ai/tessary/llm/ProviderCredentialController.java`,
  `ChatModelFactory.java`, and `AgenticCredentialResolver.java` for the code this doc describes.
