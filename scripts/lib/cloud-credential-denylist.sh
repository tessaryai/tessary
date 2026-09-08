# SPDX-License-Identifier: Apache-2.0
# The names a clean-room open-edition boot must never see resolve to a non-empty value — sourced
# by scripts/check-open-boot.sh (#878), not executed on its own (no shebang needed, matches
# scripts/lib/dev-compose.sh's non-executable, always-`bash`'d shape).
#
# Scoped to names that can ACTUALLY appear as a TESSARY-OWNED cloud credential in the tree TODAY —
# checked against the code at #878's implementation time, not copied from the issue body's list
# unexamined:
#
#   WORKOS_API_KEY / WORKOS_CLIENT_ID / WORKOS_REDIRECT_URI — WorkOsProperties.java. WorkOS is NOT
#     gone from the tree the way Mixpanel is: WorkOsClient/WorkOsProperties/AuthProviderConfig are
#     live, compiled code in backend/tenancy, selected over the default PasswordAuthProvider purely
#     by WorkOsProperties#isEnabled() (both WORKOS_API_KEY and WORKOS_CLIENT_ID set). A stray value
#     here would silently swap the auth provider away from the local one this check exercises, with
#     no build-time signal — so this has to fail on the env var's PRESENCE, not on some runtime
#     symptom of WorkOS being used.
#   E2B_API_KEY — the sandbox-runner launcher's opt-in SANDBOX_BACKEND=e2b path (both compose
#     files pass it through; neither defaults the backend to e2b since #1052). Latent in the dev
#     leg (the launcher profile stays off — see check-open-boot.sh's SCOPE BOUNDARY comment) and
#     live in the self-host leg (check-open-boot-selfhost.sh starts sandbox-runner), where this
#     entry is what catches an ambient key quietly reaching the launcher container.
#   HF_TOKEN — classify-service/Dockerfile's BuildKit --secret mount for the private
#     tessaryai/MiniCheck-RoBERTa-Large-onnx repo backing the groundedness head. Deliberately UNSET
#     for this check's build (see check-open-boot.sh's boot-recipe comment) — #877's
#     UNAVAILABLE_IN_OPEN_EDITION path is what a keyless build is supposed to fall back to, and this
#     entry is what catches an ambient HF_TOKEN in the CI runner's own env quietly reintroducing it.
#   AWS_BEARER_TOKEN_BEDROCK — Tessary's Bedrock bearer-token scheme; ChatModelFactory forces SigV4
#     over it specifically because AWS SDK v2 prefers a bearer token when this is present (the
#     "Bedrock bearer token hijacks SigV4" trap). A self-hoster's OWN Bedrock key would arrive as
#     the SDK default chain's AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, not this — so this name is
#     denied and the bare AWS_* pair deliberately is NOT (see the carve-out below). Still live:
#     this concerns ChatModelFactory's own SDK client construction (a BYO/iam_role Bedrock judge
#     credential), unrelated to and unaffected by #939 D4's sandbox-launcher credential removal.
#   OBSERVER_LAUNCHER_AWS_ACCESS_KEY_ID / OBSERVER_LAUNCHER_AWS_SECRET_ACCESS_KEY lived here until
#     #939 D4 removed docker-compose.yml's launcher remap that read them (the sandbox launcher's
#     deployment-env-var credential path is gone entirely — see AgenticCredentialResolver). Neither
#     name maps into any container any more; dropped from this list on the same footing
#     MIXPANEL_*/LAUNCHDARKLY_* are excluded below — denying a name that can no longer leak guards
#     against nothing.
#   NGROK_AUTHTOKEN — fully absent from the tree (zero grep hits across *.java/*.yml/*.yaml/*.sh,
#     confirmed at #878's implementation time same as Mixpanel/LaunchDarkly below) but kept on
#     Stripe's should-never-appear footing rather than dropped: unlike Mixpanel/LaunchDarkly, which
#     are structurally impossible to reintroduce without new code, an operator could still export
#     NGROK_* into the boot env by habit, and the guard costs nothing to keep.
#   STRIPE_SECRET_KEY / STRIPE_API_KEY — Stripe's keys are DELETED outright, not varied
#     (tessary-paid/OPEN-CORE.md: "StripeProperties is deleted with billing rather than moved") — a
#     should-never-appear check, not a live wiring concern, same footing as ngrok.
#
# Deliberately EXCLUDED, and why, so a future reader does not "fix" the omission:
#   MIXPANEL_* / LAUNCHDARKLY_* — fully absent from the open backend/frontend: no SDK, no key, no
#     config surface (TelemetryProperties.java's own javadoc and application.yaml's feature-flag
#     comment both say so directly). Denying an env-var name that can no longer appear in the tree
#     guards against nothing.
#   ANTHROPIC_API_KEY / OPENAI_API_KEY — conspicuously NOT here. check-open-boot.sh's authenticated-
#     triage assertion deliberately introduces ONE of these as a legitimate, scoped, self-hoster-
#     style BYO provider credential (via ProviderCredentialController's REST upsert, gated on
#     Capability.BYO_PROVIDER_KEYS, which is ON by default in the open edition) to exercise the
#     model-graded classifier gate end to end. Denying these two names would make the check fail
#     itself on the one credential the gate text explicitly allows ("a self-hoster-style model key,
#     which the gate allows").
CLOUD_CREDENTIAL_DENYLIST=(
    WORKOS_API_KEY
    WORKOS_CLIENT_ID
    WORKOS_REDIRECT_URI
    E2B_API_KEY
    HF_TOKEN
    AWS_BEARER_TOKEN_BEDROCK
    NGROK_AUTHTOKEN
    STRIPE_SECRET_KEY
    STRIPE_API_KEY
)
