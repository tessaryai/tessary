#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Detached boot check for a stack with tessary-paid/ removed: with the directory genuinely gone
# (not just moved aside), the slim dev stack must still come up: backend healthy, frontend up,
# caddy serving :8000, and no tessary-paid/ skeleton left on disk afterward.
#
# Also checks a credential deny-list before and after boot, brings the classify service into the
# stack keyless, and drives an authenticated triage flow through tessary's own identity provider.
#
# NEVER RUN AGENT-SIDE. This needs Docker and Compose to build and boot a real stack; run it via
# `task check:open:boot` or the dispatch-only `.github/workflows/open-edition-boot.yml`, never as
# part of `task check` or `scripts/check.sh`.
#
# Exports the working tree into a scratch copy, deletes tessary-paid/ from the copy, then runs a
# fresh `git init && git add -A` there instead of `mv`-ing the directory aside: a bare `mv` leaves
# the original git index believing tessary-paid/ files still exist, which breaks any gate that
# enumerates tracked files via `git ls-files`. See scripts/lib/export-simulate.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=lib/cloud-credential-denylist.sh
. "$ROOT/scripts/lib/cloud-credential-denylist.sh"
# shellcheck source=lib/open-boot-lib.sh
. "$ROOT/scripts/lib/open-boot-lib.sh"

# Done first, before the export/compose/curl calls below can inherit an ambient operator
# credential from whatever shell invoked this script. This only guards the narrower
# `${VAR}`-interpolation leak class; the backend's `env_file: ./.env` passthrough is a separate
# leak class the post-boot `docker compose config` check further down proves absent.
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    unset "$_cred_var" 2>/dev/null || true
done

# Fail fast: step (f) further down needs `uv` to run classifiers/data_gen/emit_local.py.
if ! command -v uv >/dev/null 2>&1; then
    echo "check-open-boot: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
    exit 1
fi

TMP="$(mktemp -d)"
# A clean-room boot must not share state with the developer's own stack: both compose files carry
# `name: tessary`, so without this the export reuses the developer's project and its persistent
# Postgres volume. COMPOSE_PROJECT_NAME outranks the file's `name:`.
export COMPOSE_PROJECT_NAME="open-boot-$$"
COMPOSE=""
COOKIES=""
BODY_FILE=""

_cleanup() {
    local status=$?
    if [ -n "$COMPOSE" ]; then
        echo "check-open-boot: tearing down the stack ($TMP)…"
        # --remove-orphans: cheap insurance against a stale service left running from an earlier
        # compose invocation with a different service set.
        (cd "$TMP" && $COMPOSE down -v --remove-orphans) || true
    fi
    [ -n "$COOKIES" ] && rm -f "$COOKIES"
    [ -n "$BODY_FILE" ] && rm -f "$BODY_FILE"
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

echo "check-open-boot: exporting the working tree (faithful export) -> $TMP"
# export-simulate.sh owns the tessary-paid/-survived assertion; it exits non-zero itself if the
# strip failed, so a bare call is enough here.
bash "$ROOT/scripts/lib/export-simulate.sh" "$TMP"

# Computed before booting so a boot that fails partway still gets torn down by the trap.
COMPOSE="$(cd "$TMP" && bash scripts/lib/dev-compose.sh)"

# --- three knobs this recipe deliberately changes from the plain dev:up:slim recipe above ---
#
# 1. TESSARY_SKIP_CLASSIFY is dropped entirely: this gate exists to prove the classify service
#    itself builds and boots keyless, so skipping it would skip the thing under test. Note that
#    `frustration` and `groundedness` are the only built-in classifiers that read it, and both are
#    in CapabilityService.UNAVAILABLE_IN_OPEN_EDITION, so this proves the container builds and
#    serves healthy with no credential, not that a classifier verdict flows through it today.
# 2. SANDBOX_BACKEND=docker is exported explicitly as documentation of intent: the `launcher`
#    compose profile stays off here (see the SCOPE BOUNDARY note further down), so E2B_API_KEY is
#    never read either way.
# 3. TESSARY_AUTH_DISABLED=false overrides docker-compose.dev.yml's default of "true" (`task
#    dev`'s unauthenticated convenience). Leaving it unset would make AuthFilter bypass itself for
#    every path, so /auth/me would 401 unconditionally and silently defeat the authenticated-
#    triage assertion below with no signal anything was wrong.
#
# Every denied credential name is ALSO passed explicitly empty on this invocation (`VAR=`), not
# just unset above: an empty assignment is what Compose's `${VAR:-default}` interpolation reads,
# so this is the actual environment `docker compose` renders the file against.
_empty_cred_assignments=()
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    _empty_cred_assignments+=("${_cred_var}=")
done

echo "check-open-boot: booting the slim stack WITH classify (keyless), SANDBOX_BACKEND=docker, auth enabled, no denied credential…"
# Two run-scoped, generated values, minted fresh here and never stored or reused. The clean-room
# export has no .env, so without them the BYO-provider credential PUT in step (e) 412s
# (SecretBox has nothing to seal with) and signup can't seal a session cookie.
_run_secret_key="$(openssl rand -base64 32)"
_run_cookie_password="$(openssl rand -base64 32)"
(cd "$TMP" && env "${_empty_cred_assignments[@]}" SANDBOX_BACKEND=docker TESSARY_AUTH_DISABLED=false \
    TESSARY_SECRET_KEY="$_run_secret_key" TESSARY_AUTH_COOKIE_PASSWORD="$_run_cookie_password" bash scripts/dev-up.sh) || {
    # A stack that never comes up is torn down by the trap before anyone can read why, so print
    # the backend's log on the way out: compose only says "dependency failed to start", and a
    # failed Spring Boot with DevTools on the classpath can exit 0 and look like a clean stop.
    echo "check-open-boot: FAIL the stack did not come up; backend log (tail) follows" >&2
    (cd "$TMP" && $COMPOSE logs --no-color --tail=200 backend 2>/dev/null) >&2 || true
    exit 1
}

HOST_PORT="${HOST_PORT:-8000}"
BASE="http://localhost:${HOST_PORT}"

# Auto-detect tessary-paid/ on $ROOT (the pre-export checkout) so the post-boot table-absence
# assertion below runs without a caller having to remember to set this. An explicit value wins.
OPEN_BOOT_OVERLAY_DIR="${OPEN_BOOT_OVERLAY_DIR:-}"
if [ -z "$OPEN_BOOT_OVERLAY_DIR" ] && [ -f "$ROOT/tessary-paid/pom.xml" ]; then
    OPEN_BOOT_OVERLAY_DIR="$ROOT/tessary-paid/db/src/main/resources"
fi

# Poll rather than sleep-and-hope: docker-compose.dev.yml gates the frontend and caddy's own start
# on the backend's `condition: service_healthy`, but caddy has no such gate on its own readiness —
# it is a reverse_proxy that can be listening in front of an upstream that is not yet. 45 tries at
# 2s (90s total) is generous for a cold `--build`.
# Body lives in scripts/lib/open-boot-lib.sh (shared with the self-host leg).
_wait_for() {
    open_boot_wait_for "check-open-boot" "$@"
}

fail=0

# classify's own readiness, checked directly on its host-published port: /healthz answers 503
# until warmAll()/warmAllEmbedders() resolve and only 200 once every baked head/embedder is
# resident. A heavier, colder-starting container than the rest of the stack, hence its own wider
# budget rather than trusting the 45x2s general one below to also cover it.
_wait_for "GET /healthz         (classify, direct :18080)"     "http://localhost:18080/healthz" 200 90 || fail=1

# A public page, the OpenAPI document, and an authenticated-only route correctly answering
# unauthenticated, all through caddy on :8000, since that's what a real deployment reaches.
_wait_for "GET /              (frontend, via caddy)" "$BASE/"             200 || fail=1
_wait_for "GET /v3/api-docs   (backend,  via caddy)" "$BASE/v3/api-docs"  200 || fail=1
_wait_for "GET /api/v1/me     (backend,  via caddy)" "$BASE/api/v1/me"    401 || fail=1

# Checked again post-boot: a bind mount could manufacture a ghost tessary-paid/ directory in an
# export that never had one.
if [ -e "$TMP/tessary-paid" ]; then
    echo "check-open-boot: tessary-paid/ reappeared on disk after boot - the ghost-directory" >&2
    echo "                 regression is back. Check docker-compose.dev.yml for a bind mount" >&2
    echo "                 naming a path that tessary-paid/docker-compose.dev.yml's probe does not cover." >&2
    fail=1
fi

# Table-absence check, post-boot: proves no paid-overlay table leaked into the database this
# open-only boot created. OPEN_BOOT_OVERLAY_DIR only derives the table-name list from disk; it is
# never itself booted or applied here.
if [ -n "$OPEN_BOOT_OVERLAY_DIR" ]; then
    # A non-empty OPEN_BOOT_OVERLAY_DIR commits this run to the assertion, so a directory that
    # does not exist must fail loud here rather than read as a legitimately-empty overlay.
    if [ ! -d "$OPEN_BOOT_OVERLAY_DIR" ]; then
        echo "check-open-boot: OPEN_BOOT_OVERLAY_DIR='$OPEN_BOOT_OVERLAY_DIR' does not exist - cannot verify the paid-overlay-table-absence assertion, failing rather than silently skipping it" >&2
        fail=1
    else
        _overlay_table_check_failed=0
        _overlay_tables="$(open_boot_overlay_table_names "$OPEN_BOOT_OVERLAY_DIR")" || _overlay_table_check_failed=1
        if [ "$_overlay_table_check_failed" = 1 ]; then
            echo "check-open-boot: could not derive the paid overlay's table names from OPEN_BOOT_OVERLAY_DIR - see above" >&2
            fail=1
        else
            echo "check-open-boot: checking that no paid overlay table exists in this open-only boot's database…"
            for _overlay_table in $_overlay_tables; do
            # `|| true` is load-bearing: under `set -euo pipefail`, a `$COMPOSE exec`/`psql` error
            # would otherwise kill the script silently instead of letting the `elif` below fail
            # loud on an unconfirmed result.
            _overlay_hit="$( (cd "$TMP" && $COMPOSE exec -T postgres \
                psql -U tessary -d tessary -tAc \
                "select to_regclass('public.${_overlay_table}') is not null" 2>/dev/null) \
                | tr -d '[:space:]' || true)"
            if [ "$_overlay_hit" = "t" ]; then
                echo "check-open-boot: paid overlay table 'public.${_overlay_table}' exists in this open-only boot's database - the open master changelog created a table it should not know about" >&2
                fail=1
            elif [ "$_overlay_hit" != "f" ]; then
                echo "check-open-boot: could not confirm 'public.${_overlay_table}' is absent (query returned '${_overlay_hit}', not 't' or 'f') - treating as a failure, not a pass" >&2
                fail=1
            fi
        done
    fi
    fi
else
    echo "check-open-boot: OPEN_BOOT_OVERLAY_DIR is not set - skipping the paid-overlay-table-absence assertion."
fi

# Credential deny-list, post-boot. The pre-boot unset + empty-assignment above only stops the
# `${VAR}`/`${VAR:-default}` shell-interpolation leak; this proves the stack itself never resolved
# a denied name from elsewhere, most importantly the backend's `env_file: ./.env`, which
# blanket-loads a repo-root `.env` into the container regardless of this shell's environment.
# export-simulate.sh never copies `.env` (it's gitignored) so this is normally a no-op; this check
# is what would catch the day that stops being true.
#
# Two passes beyond that: (a) `docker compose config`, the rendered compose file after every
# interpolation and env_file resolves, catches a default value baked into the compose YAML
# itself; (b) `docker exec <container> env` on every running service catches a value baked into a
# Dockerfile ENV line, which (a) cannot see. Both passes live in scripts/lib/open-boot-lib.sh. The
# service list is every service this boot's `dev_up_services` starts.
open_boot_check_denied_credentials "check-open-boot" "$TMP" "$COMPOSE" \
    backend frontend caddy classify postgres || fail=1

[ "$fail" = 0 ] || {
    echo "check-open-boot: FAILED before the authenticated-triage assertion — see above." >&2
    exit 1
}
echo "check-open-boot: the open edition boots keyless - backend healthy, frontend up, classify" \
     "warm with no HF_TOKEN, caddy serving :${HOST_PORT}, no denied credential anywhere, no" \
     "tessary-paid/ left behind"

# --------------------------------------------------------------------------------------------------
# AUTHENTICATED TRIAGE FLOW: drives an account created through PasswordAuthProvider, tessary's own
# identity provider, so auth silently bypassed (no provider configured) can't pass this check as
# an unauthenticated-request-happens-to-succeed false green. Every step checks its HTTP status; the
# final step (the classifier-events poll) also checks response body content, not status alone.
# --------------------------------------------------------------------------------------------------
COOKIES="$(mktemp)"
BODY_FILE="$(mktemp)"
_curl_json() {
    # A tiny wrapper so every call below reads the same way: method, path, optional JSON body.
    # X-Requested-With is sent on every call, not just the mutating ones that need it, since
    # AuthFilter's CSRF guard 403s a cookie-authed mutation under /api/** without it.
    local method="$1" path="$2" body="${3:-}"
    if [ -n "$body" ]; then
        curl -sS -b "$COOKIES" -c "$COOKIES" -X "$method" "$BASE$path" \
            -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' -d "$body" \
            -o "$BODY_FILE" -w '%{http_code}'
    else
        curl -sS -b "$COOKIES" -c "$COOKIES" -X "$method" "$BASE$path" \
            -H 'X-Requested-With: XMLHttpRequest' \
            -o "$BODY_FILE" -w '%{http_code}'
    fi
}

_expect() {
    local desc="$1" want="$2" got="$3"
    if [ "$got" != "$want" ]; then
        echo "check-open-boot: $desc -> $got, wanted $want. body:" >&2
        cat "$BODY_FILE" >&2
        echo >&2
        exit 1
    fi
    echo "check-open-boot: $desc -> $got (ok)"
}

# (a) signup: PasswordAuthProvider creates the account and signs it in, and
# AuthController#establishSession's tail calls tenants.ensureDefaultOrg(...) — no separate
# org-creation call needed. Throwaway, run-scoped credentials so a repeat local run never collides.
_signup_email="open-boot-check+$(date +%s)-$$@example.invalid"
_signup_body="$(jq -nc --arg email "$_signup_email" --arg password "open-boot-check-$(date +%s)-passphrase" \
    '{email:$email, password:$password}')"
_code="$(_curl_json POST /auth/signup "$_signup_body")"
_expect "POST /auth/signup" 200 "$_code"

# (b) /auth/me, with the session cookie, proves the session is real, not just that signup 200'd.
# Distinct from the unauthenticated /api/v1/me probed earlier (same literal path, different
# mapping). Read the default org's slug from here.
_code="$(_curl_json GET /auth/me)"
_expect "GET /auth/me (authenticated)" 200 "$_code"
ORG_SLUG="$(jq -r '.data.orgs[0].slug // empty' "$BODY_FILE")"
if [ -z "$ORG_SLUG" ]; then
    echo "check-open-boot: /auth/me returned 200 but no org in .data.orgs — signup's own" >&2
    echo "                 ensureDefaultOrg call did not produce an org this session can see." >&2
    exit 1
fi
echo "check-open-boot: authenticated as $_signup_email, default org slug=$ORG_SLUG"

# (c) the default project — ensureDefaultOrg's own "Default" project, created alongside the org.
_code="$(_curl_json GET "/api/orgs/$ORG_SLUG/projects")"
_expect "GET /api/orgs/$ORG_SLUG/projects" 200 "$_code"
PROJECT_SLUG="$(jq -r '.data[0].slug // empty' "$BODY_FILE")"
if [ -z "$PROJECT_SLUG" ]; then
    echo "check-open-boot: org $ORG_SLUG has no default project to run triage against." >&2
    exit 1
fi
echo "check-open-boot: default project slug=$PROJECT_SLUG"

# (d) mint a project-scoped MCP token, proving the self-hoster-facing token-issue surface works
# end to end, even though nothing below strictly needs the token itself.
_code="$(_curl_json POST "/api/orgs/$ORG_SLUG/projects/$PROJECT_SLUG/mcp-tokens" '{"name":"open-boot-check"}')"
_expect "POST .../mcp-tokens" 201 "$_code"
MCP_TOKEN="$(jq -r '.data.plaintext // empty' "$BODY_FILE")"
if [ -z "$MCP_TOKEN" ]; then
    echo "check-open-boot: mcp-tokens issue returned 201 but no .data.plaintext token." >&2
    exit 1
fi
echo "check-open-boot: minted an mcp-token (tsy_… redacted)"

# (e) Store a BYO provider credential through the REST path the classifier pipeline actually
# reads keys from (ProviderCredentialController), with a placeholder, never a real key.
#
# No test or check may require a real credential, local or CI. A placeholder proves everything
# this step can: the PUT accepts it, seals it via SecretBox, and the GET below reads back
# has_api_key:true. Nothing downstream ever calls a provider with it — every model-graded
# classifier is in CapabilityService.UNAVAILABLE_IN_OPEN_EDITION, so the step (g) verdict is
# proven by the deterministic `secret_leak` classifier alone. Clearly fake on its face so it can
# never be mistaken for a working credential.
OPEN_BOOT_PLACEHOLDER_KEY="sk-open-boot-placeholder-not-a-real-key-$$"
# Which ModelProvider enum value the placeholder is stored under; case-sensitive path variable.
PROVIDER_NAME="${OPEN_BOOT_PROVIDER_NAME:-ANTHROPIC}"
_provider_body="$(jq -nc --arg key "$OPEN_BOOT_PLACEHOLDER_KEY" '{api_key:$key}')"
_code="$(_curl_json PUT "/api/orgs/$ORG_SLUG/providers/$PROVIDER_NAME" "$_provider_body")"
_expect "PUT .../providers/$PROVIDER_NAME" 200 "$_code"
echo "check-open-boot: stored a $PROVIDER_NAME credential via the BYO-provider-credential REST path"

# Read the credential back rather than trusting the PUT's 200 alone: nothing downstream reads
# this key for us, so this is the only assertion that the stored credential is real. The API never
# returns the secret itself, so has_api_key:true is the strongest signal available.
_list_code="$(_curl_json GET "/api/orgs/$ORG_SLUG/providers")"
_expect "GET .../providers" 200 "$_list_code"
_has_key="$(jq -r --arg p "$PROVIDER_NAME" \
    '.data.credentials[] | select(.provider == $p) | .has_api_key' "$BODY_FILE")"
if [ "$_has_key" != "true" ]; then
    echo "check-open-boot: stored $PROVIDER_NAME credential does not read back has_api_key=true" >&2
    exit 1
fi
echo "check-open-boot: verified the stored $PROVIDER_NAME credential reads back has_api_key=true"

# (f) emit ONE real trace via classifiers/data_gen/emit_local.py's `canary` corpus.
#
# Not `zipeats`/`policygpt`: neither is present in a genuine clean-room export (one needs a
# separately-run LLM-keyed generate step, the other reads a path outside the repo). `canary` is
# one hand-built Conversation with no file and no LLM dependency, whose output embeds an
# AWS-access-key-id-shaped string, which is what makes step (g) below provable: `secret_leak` is
# the one deterministic, no-baseline classifier available here, so it's the only one a single
# CI-boot-check trace can trigger.
echo "check-open-boot: emitting one canary trace through the ingest pipeline…"
# `uv run`, not a hardcoded `.venv/bin/python`: uv resolves classifiers/pyproject.toml +
# classifiers/uv.lock and builds/reuses its own venv on demand. `--extra otlp` pulls in just what
# emit_local.py needs, without the heavier `train`/`quality` extras.
(cd "$ROOT/classifiers" && uv run --quiet --extra otlp python -m data_gen.emit_local \
    --endpoint "$BASE/v1/traces" \
    --token "$MCP_TOKEN" \
    --corpus canary \
    --limit 1 \
    --no-resume)

# (g) poll for the classifier verdict this emission is supposed to produce.
# `/classifiers/events` is fed by ClassifierWorker's sweep over SubstrateObservation rows, the
# actual OTLP-trace-triggered triage pipeline. The worker runs on a heartbeat
# (`tessary.classifier.heartbeat-ms`, default 60000ms) this script does not override, so the poll
# budget below (10 tries x 20s) is sized to comfortably clear the default heartbeat plus one sweep.
#
# False-green guard: assert the response body carries at least one event, not just HTTP 200. A
# ClassifierWorker that fail-opens could return 200 with an empty `.data` array — triage silently
# no-oping instead of failing loud.
echo "check-open-boot: polling for the secret_leak verdict…"
_events_tries=10
_events_found=0
while [ "$_events_tries" -gt 0 ]; do
    _code="$(_curl_json GET "/api/orgs/$ORG_SLUG/projects/$PROJECT_SLUG/classifiers/events")"
    if [ "$_code" = "200" ]; then
        _event_count="$(jq -r '.data | length' "$BODY_FILE" 2>/dev/null || echo 0)"
        if [ "$_event_count" -gt 0 ] 2>/dev/null; then
            _events_found=1
            echo "check-open-boot: /classifiers/events -> 200, $_event_count event(s):"
            jq -r '.data[] | "  - " + (.classifier_id // "?") + " severity=" + (.severity // "?") + " confidence=" + (.confidence // "?")' \
                "$BODY_FILE" 2>/dev/null || true
            break
        fi
    fi
    _events_tries=$((_events_tries - 1))
    sleep 20
done

if [ "$_events_found" != 1 ]; then
    echo "check-open-boot: /classifiers/events never returned a non-empty .data array — the" >&2
    echo "                 canary trace's secret_leak-triggering content never produced a" >&2
    echo "                 detection. Either the triage pipeline silently no-op'd (the false-green" >&2
    echo "                 defect this check exists to catch) or the worker's heartbeat has not" >&2
    echo "                 ticked yet within this budget." >&2
    # Print the backend/classify logs and the endpoint's last body on the way out: telling "worker
    # never ticked" from "worker ticked and found nothing" from "classify unreachable" needs them.
    echo "check-open-boot: --- last /classifiers/events body (HTTP $_code) ---" >&2
    head -c 2000 "$BODY_FILE" >&2 || true
    echo >&2
    echo "check-open-boot: --- backend log, triage-relevant lines (tail) ---" >&2
    (cd "$TMP" && $COMPOSE logs --no-color --tail=1500 backend 2>/dev/null) \
        | grep -iE 'classif|observ|substrate|ingest|otlp|/v1/traces|scorer|encoder|WARN|ERROR' \
        | tail -80 >&2 || true
    echo "check-open-boot: --- classify log (tail) ---" >&2
    (cd "$TMP" && $COMPOSE logs --no-color --tail=40 classify 2>/dev/null) >&2 || true
    exit 1
fi

echo "check-open-boot: the open edition boots keyless AND serves an authenticated triage flow -" \
     "signup, session, default project, mcp-token, a self-hoster-style provider key, and a real" \
     "classifier verdict, all with zero Tessary-owned cloud credentials in play."

# --------------------------------------------------------------------------------------------------
# SCOPE BOUNDARY (kept out of this run deliberately):
#   - The `launcher` compose profile / sandbox-runner are never started. The triage path under
#     test makes no E2B/sandbox call.
#   - The provider key wired in step (e) stays scoped to the ProviderCredentialController REST
#     call only, never added to sandbox-runner's own compose env, which would conflate the
#     classifier-verdict path with the agentic-synthesis path this check does not test.
# --------------------------------------------------------------------------------------------------
