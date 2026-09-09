#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Detached open-edition BOOT check (#849 AC4, amending the epic-1 gate #886 widened it with —
# tessary-paid/OPEN-CORE.md's "a detached `task dev:up:slim` must BOOT" clause). With `tessary-paid/` GENUINELY
# gone — not just moved aside — the slim dev stack must come up: backend healthy, frontend up,
# caddy serving :8000, and no `tessary-paid/` skeleton left on disk afterward.
#
# #878 EXTENDS THIS SCRIPT (epic 2's gate: "boots and serves triage with zero Tessary-owned cloud
# credentials" has to be a script someone runs, not an argument) — same file, same
# trap/cleanup/_wait_for lifecycle, not a new one (tessary-paid/OPEN-CORE.md's issue-16 text is explicit that
# this is an extension). Three things are new below: a credential deny-list checked both BEFORE
# boot (this shell + the compose invocation) and AFTER boot (rendered compose config + every
# running container's own env), the classify service brought INTO the stack instead of skipped
# (keyless — #877's UNAVAILABLE_IN_OPEN_EDITION fallback is what this check is proving actually
# works), and an authenticated triage flow driven end to end through the open edition's own
# identity provider (#924 made "authenticated" part of the gate text, not just "serves a response").
#
# NEVER RUN AGENT-SIDE, EVER. This needs Docker and Compose to build and start a real stack, and
# the open-core-issue workflow runs on a host that stack thrashes rather than fails cleanly on —
# see CLAUDE.md's toolchain notes. This script is AUTHORED here and RUN by a human operator
# (`task check:open:boot`) or the dispatch-only `.github/workflows/open-edition-boot.yml` — never
# by an agent, and never part of `task check` or `scripts/check.sh` (see its EXCLUDED manifest row
# and that script's own header for why).
#
# WHY `git init` OVER A COPY, NOT `mv tessary-paid /tmp/`. The `mv` ritual several prior epic-1
# commits used as their detach proof (e.g. 76f7d6f3's own verification note: "overlay
# physically detached and the open pipeline passes") is NOT a faithful export simulation — #889
# found that `check-docs-links.sh` enumerates this repo's markdown with `git ls-files --cached`,
# which reads the INDEX, not the working tree. After a bare `mv`, the index still believes
# `tessary-paid/README.md` exists on disk, so that gate dies with a `FileNotFoundError` before any
# other gate runs. Hitting that error means you reproduced #889, not that you broke something.
# Copying the working tree into a scratch directory, deleting `tessary-paid/` from the COPY, then
# a fresh `git init && git add -A` inside it makes the index describe the tree that is ACTUALLY
# there — zero overlay entries, because the overlay was never copied in to begin with. That
# recipe now lives in `scripts/lib/export-simulate.sh` (#889, working-tree-faithful — not
# `git archive HEAD`, which would silently drop uncommitted changes) — this script just calls it
# and keeps owning its own `$TMP` + trap-based cleanup/compose-teardown lifecycle.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=lib/cloud-credential-denylist.sh
. "$ROOT/scripts/lib/cloud-credential-denylist.sh"
# shellcheck source=lib/open-boot-lib.sh
. "$ROOT/scripts/lib/open-boot-lib.sh"

# Defense-in-depth, done FIRST, before anything downstream (the export, the compose invocation,
# the curl calls) can inherit an ambient operator credential from whatever shell invoked this
# script. `docker-compose.dev.yml`'s backend `environment:` block only forwards a NAMED host env
# var (each name interpolated as `${VAR}`/`${VAR:-default}` at the individual key it needs), so
# none of THESE denied names is individually referenced there — but that is not the whole story:
# `backend:` also carries an UNCONDITIONAL `env_file: ./.env` (required: false), which blanket-
# loads every key in a repo-root `.env` file into the container regardless of what the shell
# holds. `.env` is `.gitignore`d and this script's export (scripts/lib/export-simulate.sh) copies
# only tracked-or-not-ignored files, so a genuine clean-room export never carries one across —
# VERIFIED empirically at #878's implementation time (built a real export, confirmed `.env`
# absent, confirmed `docker compose config` run from that export shows none of these names
# resolving). Unsetting here does nothing to neutralize `env_file` either way (it reads the FILE,
# not the shell) — it is cheap insurance against the narrower `${VAR}`-interpolation leak class
# only, and against the next accidental bare `- WORKOS_API_KEY` passthrough line in a compose
# file. The `env_file`/`.env` class is what the POST-boot `docker compose config` check further
# down actually proves absent — see that section's comment.
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    unset "$_cred_var" 2>/dev/null || true
done

# Fail fast, before spending several minutes on a stack build+boot this can never finish anyway:
# step (f) further down needs `uv` to run classifiers/data_gen/emit_local.py (same convention as
# scripts/check-classifier-parity.sh — see that script's identical check and message).
if ! command -v uv >/dev/null 2>&1; then
    echo "check-open-boot: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
    exit 1
fi

TMP="$(mktemp -d)"
# A clean-room boot must not share state with the developer's own stack: both compose files carry
# `name: tessary`, so without this the export reuses the developer's project, its persistent
# Postgres volume (a pre-partition database fails the regenerated baseline's checksum, D-A) and
# tears that stack down on exit. COMPOSE_PROJECT_NAME outranks the file's `name:`.
export COMPOSE_PROJECT_NAME="open-boot-$$"
COMPOSE=""
COOKIES=""
BODY_FILE=""

_cleanup() {
    local status=$?
    if [ -n "$COMPOSE" ]; then
        echo "check-open-boot: tearing down the stack ($TMP)…"
        # --remove-orphans: cheap insurance, not a proven-needed fix here — #886's review pass
        # found `task dev:stop` orphaning the compile service across an EDITION switch (paid ->
        # open) because DC_DEV_ALL is derived at invocation time; this export is open-only from
        # the start, so there is nothing to orphan today, but the flag costs nothing and keeps
        # this script correct if that ever changes.
        (cd "$TMP" && $COMPOSE down -v --remove-orphans) || true
    fi
    [ -n "$COOKIES" ] && rm -f "$COOKIES"
    [ -n "$BODY_FILE" ] && rm -f "$BODY_FILE"
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

echo "check-open-boot: exporting the working tree (faithful export, #889) -> $TMP"
# export-simulate.sh owns the tessary-paid/-survived assertion now (one source of truth) — it
# exits non-zero itself if the strip failed, so a bare call is enough here.
bash "$ROOT/scripts/lib/export-simulate.sh" "$TMP"

# Computed before booting, not after, so a boot that fails partway (container created, backend
# never healthy) still gets torn down by the trap rather than leaking a stack in $TMP's project.
COMPOSE="$(cd "$TMP" && bash scripts/lib/dev-compose.sh)"

# --- the two boot-recipe knobs #878 deliberately changes from the plain dev:up:slim recipe above ---
#
# 1. TESSARY_SKIP_CLASSIFY is DROPPED entirely (not exported, not even =0) — the epic-1 version of
#    this script set it to 1, which filters `classify` (and `compile`) out of the `up` list before
#    compose ever sees them (scripts/lib/dev-services.sh). This gate exists to prove the classify
#    service ITSELF builds and boots KEYLESS — #877's UNAVAILABLE_IN_OPEN_EDITION fallback (no
#    HF_TOKEN -> download.js skips the two gated heads -> the container still comes up and reports
#    healthy) — so skipping the service would skip exactly the thing under test.
#    NOTE for whoever next reads "triage's signal-head scoring reaches classify, so this proves
#    triage runs keyless": narrower than that in practice. `frustration` and `groundedness` are
#    the ONLY two built-in classifiers that read EncoderDetector/the classify service, and both
#    are in `CapabilityService.UNAVAILABLE_IN_OPEN_EDITION` — they never provision in this
#    edition's projects regardless of whether classify is in the compose `up` list. What THIS
#    proves is that the container the production classify path depends on builds and serves
#    healthy with no credential, not that an open-edition classifier verdict flows through it
#    today. The classifier verdict this check proves further down (secret_leak) is deterministic
#    and never touches classify — see the emit-canary comment below for why.
# 2. SANDBOX_BACKEND=docker is exported explicitly — as DOCUMENTATION of intent, not as a fix:
#    since #1052 docker-compose.dev.yml no longer defaults the launcher to e2b (the passthrough is
#    empty, so server.js's own `docker` default wins, matching docker-compose.yml), and the
#    `launcher` compose profile stays OFF here anyway (see the SCOPE BOUNDARY note further down),
#    so E2B_API_KEY is never read either way. The self-host leg (check-open-boot-selfhost.sh)
#    deliberately does NOT set this, proving the code default is what a fresh `.env` gets.
# 3. TESSARY_AUTH_DISABLED=false is exported explicitly, overriding docker-compose.dev.yml's own
#    default (which is "true" — `task dev`'s deliberate unauthenticated convenience, #924's own
#    comment on that line). AuthFilter.shouldNotFilter bypasses itself for EVERY path when this is
#    set, meaning NO request would ever get a resolved TenantContext — GET /auth/me would 401
#    unconditionally no matter how the earlier signup call went, silently defeating the entire
#    authenticated-triage assertion below without any signal that anything was wrong. That default
#    was a hardcoded YAML literal, not a `${VAR:-default}` — docker-compose.dev.yml gained the one
#    line of interpolation needed to make it overridable here, alongside this script, in the same
#    commit (Rule 4); `task dev`/`task dev:slim` are unaffected (still default to "true").
#
# Every denied credential name is ALSO passed explicitly empty on this one invocation (`VAR=`),
# not just unset in this shell above: an empty assignment is what Compose's own `${VAR:-default}`
# interpolation reads, and it is the belt to the unset loop's suspenders — this is the actual
# environment `docker compose` sees when it renders the file, not an inherited one.
_empty_cred_assignments=()
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    _empty_cred_assignments+=("${_cred_var}=")
done

echo "check-open-boot: booting the slim stack WITH classify (keyless), SANDBOX_BACKEND=docker, auth enabled, no denied credential…"
# Two RUN-SCOPED, GENERATED values, minted fresh here and never stored or reused — not credentials
# (crew review, PR #1033). The clean-room export has no .env, so without them: (1) the BYO-provider
# credential PUT in step (e) 412s (SECRET_KEY_NOT_CONFIGURED — SecretBox has nothing to seal with),
# which means that step could never have passed since #878, real key or fake; (2) TESSARY_AUTH_DISABLED
# =false makes signup seal a session cookie, which needs a cookie password. Both are AES-grade random
# bytes that exist only for this process's lifetime.
_run_secret_key="$(openssl rand -base64 32)"
_run_cookie_password="$(openssl rand -base64 32)"
(cd "$TMP" && env "${_empty_cred_assignments[@]}" SANDBOX_BACKEND=docker TESSARY_AUTH_DISABLED=false \
    TESSARY_SECRET_KEY="$_run_secret_key" TESSARY_AUTH_COOKIE_PASSWORD="$_run_cookie_password" bash scripts/dev-up.sh) || {
    # A stack that never comes up is torn down by the trap before anyone can read why. Print the
    # backend's own log on the way out, the same courtesy the triage step below extends, because
    # compose only says "dependency failed to start" and, with DevTools on the classpath, a failed
    # Spring boot exits 0 and looks like a clean stop.
    echo "check-open-boot: FAIL the stack did not come up; backend log (tail) follows" >&2
    (cd "$TMP" && $COMPOSE logs --no-color --tail=200 backend 2>/dev/null) >&2 || true
    exit 1
}

HOST_PORT="${HOST_PORT:-8000}"
BASE="http://localhost:${HOST_PORT}"

# #1076: the post-boot paid-overlay-table-absence assertion further down is gated on this being
# set. A caller may still pass OPEN_BOOT_OVERLAY_DIR in explicitly (an explicit value always wins
# below), but this script no longer TRUSTS a caller to remember to: Taskfile's check:open:boot
# target was the only place that ever set it, so the dispatch-only open-edition-boot.yml workflow
#, which runs `bash scripts/check-open-boot.sh` directly, after a checkout that DOES carry
# the overlay checkout, silently skipped the assertion in the one automated place it runs. $ROOT is the
# real, pre-export repo checkout (this runs before scripts/lib/export-simulate.sh ever makes
# `$TMP`, which is the tree that genuinely never has an overlay checkout to search for), so probing
# it here for the overlay's pom, the same test Taskfile's own target already used, is not
# the EQCHK_OVERLAY_DIR/MIGPOP_OVERLAY_DIR-style "caller decides" case that comment used to describe;
# it is just doing, unconditionally, what every caller of this script wants and one of two callers
# forgot to do.
OPEN_BOOT_OVERLAY_DIR="${OPEN_BOOT_OVERLAY_DIR:-}"
if [ -z "$OPEN_BOOT_OVERLAY_DIR" ] && [ -f "$ROOT/tessary-paid/pom.xml" ]; then
    OPEN_BOOT_OVERLAY_DIR="$ROOT/tessary-paid/db/src/main/resources"
fi

# Poll rather than sleep-and-hope: docker-compose.dev.yml gates the frontend and caddy's own start
# on the backend's `condition: service_healthy`, but caddy has no such gate on ITS OWN readiness —
# it is a reverse_proxy that can be listening in front of an upstream that is not yet. 45 tries at
# 2s (90s total) is generous for a cold `--build`, matching the order of magnitude #886's own boot
# measurement reported.
# Body lives in scripts/lib/open-boot-lib.sh since #1052 (shared with the self-host leg).
_wait_for() {
    open_boot_wait_for "check-open-boot" "$@"
}

fail=0

# classify's own readiness, checked DIRECTLY (its host-published port, docker-compose.dev.yml's
# `127.0.0.1:18080:8080`), before anything that depends on it: /healthz answers 503 until
# `warmAll()`/`warmAllEmbedders()` resolve (classify-service/server.js) and only 200 once every
# baked head/embedder is resident. Since #878 drops TESSARY_SKIP_CLASSIFY=1 this is now a real cold
# start on every run of this check, and it is a heavier container than the rest of the stack
# (BAKE_EMBEDDERS bakes ~1.7 GB into the image at BUILD time, which `--build` above already paid
# for — this loop is purely the RUNTIME load-into-memory cost) — hence its own, wider budget
# rather than trusting the 45x2s general one below to also cover it.
_wait_for "GET /healthz         (classify, direct :18080)"     "http://localhost:18080/healthz" 200 90 || fail=1

# The exact three checks the operator already made by hand landing #886 (tessary-paid/OPEN-CORE.md's
# 2026-08-31 boot divergence row): a public page, the OpenAPI document, and an authenticated-only
# route correctly answering unauthenticated — ALL THROUGH CADDY on :8000, because that is what an
# operator (and a real deployment) actually reaches; hitting the backend port directly would prove
# a different, weaker thing.
_wait_for "GET /              (frontend, via caddy)" "$BASE/"             200 || fail=1
_wait_for "GET /v3/api-docs   (backend,  via caddy)" "$BASE/v3/api-docs"  200 || fail=1
_wait_for "GET /api/v1/me     (backend,  via caddy)" "$BASE/api/v1/me"    401 || fail=1

# The #886 regression class itself, checked again post-boot: a bind mount naming a path outside
# tessary-paid/docker-compose.dev.yml's file-exists probe would let dockerd manufacture a ghost
# tessary-paid/ directory in an export that never had one.
if [ -e "$TMP/tessary-paid" ]; then
    echo "check-open-boot: tessary-paid/ reappeared on disk after boot - the #886 ghost-directory" >&2
    echo "                 regression is back. Check docker-compose.dev.yml for a bind mount" >&2
    echo "                 naming a path that tessary-paid/docker-compose.dev.yml's probe does not cover." >&2
    fail=1
fi

# PAID-OVERLAY-TABLE ABSENCE, POST-BOOT (#1076). The #886 check above proves no overlay directory
# leaked onto disk; this proves no overlay TABLE leaked into the database this open-only boot
# created, the stack in $TMP never saw a paid changelog, so nothing it applied should have
# created any of the paid overlay's own tables. OPEN_BOOT_OVERLAY_DIR names a `db` module
# resources root purely to DERIVE that table-name list (same convention MIGPOP_OVERLAY_DIR already
# uses for check-migrations-populated.sh, and the same directory shape check-overlay-schema.sh
# calls $PAID_RESOURCES), it is never itself booted or applied here, only read from disk to know
# what to grep for `to_regclass` on.
if [ -n "$OPEN_BOOT_OVERLAY_DIR" ]; then
    # A non-empty OPEN_BOOT_OVERLAY_DIR means this run committed to running the assertion, so a
    # directory that does not exist (typo'd path, or a future rename of the overlay db module's layout)
    # must fail loud here, not fall through to open_boot_overlay_table_names, whose own contract
    # (see its header) folds "no such directory" into the same rc=0/empty-stdout SKIP as a
    # legitimately-empty overlay. That ambiguity is fine for the function in isolation (its unset-
    # dir case is exactly what the `-n` test above already screens out before calling it) but not
    # here, once the caller has asked for the check to actually run.
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
            # `to_regclass` returns NULL (renders as an empty string via `-tAc`, never errors) for
            # a relation that does not exist, so a passing run naturally prints nothing here, the
            # ONLY thing that should ever print is a hit, matching the #1043 guard on the deny-list
            # check just below: a bare `|| true` on the WHOLE assertion would let a query failure
            # (a typo'd table name, a dropped connection) silently read as "not found, therefore
            # passing," which is the exact bug class this file's own comments already warn about.
            # `|| true` is load-bearing, the same way it is on the deny-list check just below: under
            # `set -euo pipefail`, a `$COMPOSE exec` that fails to reach the container (or a `psql`
            # that errors) would otherwise make this whole assignment fail and errexit kill the
            # script right here, silently, with no "FAILED" line and no chance for the `elif` below
            # to ever run, exactly the #1043 bug class. `|| true` turns that into an EMPTY result,
            # which the `elif` below correctly reads as "could not confirm, not 'checked and
            # clean'" and fails loud on, rather than letting it read as "not found, therefore
            # passing."
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

# --------------------------------------------------------------------------------------------------
# CREDENTIAL DENY-LIST, POST-BOOT (#878). The pre-boot unset + empty-assignment above stops the
# NARROW leak class (`${VAR}`/`${VAR:-default}` interpolation reading this shell); this is the
# check that the STACK ITSELF never resolved a denied name from somewhere else — a value baked
# into application.yaml, a Dockerfile ENV default, a compose fragment that reads a DIFFERENT env
# var than the ones this script clears, OR (the one that actually matters most in practice)
# `backend:`'s own `env_file: ./.env` in docker-compose.dev.yml, which blanket-loads a repo-root
# `.env` file into the container regardless of this shell's environment. A LOCAL DEV MACHINE'S
# real `.env` genuinely does carry live WORKOS/E2B/HF_TOKEN/AWS credentials for `task dev`'s
# convenience — verified directly at #878's implementation time (`docker compose config` run from
# the actual repo root, `.env` present, showed every one of them in the backend service's rendered
# environment). What makes that harmless HERE is exports-simulate.sh's own contract: it copies only
# tracked-or-not-`.gitignore`d files, `.env` is `.gitignore`d, so it is never present at `$TMP`, and
# `env_file`'s `required: false` means a missing file is silently a no-op rather than a boot
# failure — verified empirically too (a real export directory, confirmed `.env` absent, confirmed
# `docker compose config` run FROM that export shows none of these names resolving). This check is
# what would catch the day that stops being true (a future change teaches export-simulate.sh to
# copy `.env`, or removes it from `.gitignore`, or the compose file gains `required: true`).
# Two passes beyond that, because they catch two further, different bugs:
#   (a) `docker compose config` — the RENDERED compose file, after every ${VAR:-default}
#       interpolation AND every `env_file` resolves. Catches a default value baked into the
#       compose YAML itself, e.g. a future `E2B_API_KEY: ${E2B_API_KEY:-some-default}` that would
#       pass the empty-assignment above and still ship a real key.
#   (b) `docker exec <container> env` on every running service — the CONTAINER's actual runtime
#       env, which is what a value baked into a Dockerfile `ENV` line (never touching compose at
#       all) would only show up in. `docker compose config` cannot see that class of leak.
# --------------------------------------------------------------------------------------------------
# Both passes live in scripts/lib/open-boot-lib.sh since #1052 (shared with the self-host leg;
# the `|| true` that #1043 added to the rendered-config grep went with them). The service list is
# ALL services this boot's `dev_up_services` (scripts/lib/dev-services.sh) starts — dropping
# TESSARY_SKIP_CLASSIFY=1 (see the boot-recipe comment above) means every declared service comes
# up, not just the original four. postgres currently carries no denylisted var and no env_file:,
# so its inclusion is a defense-in-depth completeness fix, not a live-bug fix (crew review, PR
# #1014) — but the check's own stated intent is "every running container", and a denylisted value
# silently added to it later would otherwise go unchecked here. The slack adapter left this list
# with its compose block (#1106); since #1293 the service itself is overlay-owned, so the open dev
# stack has nothing to declare a service from in the first place.
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
# AUTHENTICATED TRIAGE FLOW (#878, #924). "Boots and serves triage" now specifically means through
# an account created with the open edition's OWN identity provider (PasswordAuthProvider, #852) —
# #924 is exactly the defect this replaces an unauthenticated-request-happens-to-succeed check
# would miss (auth silently bypassed when no provider is configured). Every step below checks its
# HTTP status; the FINAL step (the classifier-events poll) additionally checks response BODY
# content, not status alone — see the false-green-guard note down there for why that distinction
# is load-bearing.
# --------------------------------------------------------------------------------------------------
COOKIES="$(mktemp)"
BODY_FILE="$(mktemp)"
_curl_json() {
    # A tiny wrapper so every call below reads the same way: method, path, optional JSON body.
    # Always sends/receives the session cookie jar; -sS surfaces curl's own errors instead of an
    # empty body that a later `jq` call would fail on with a confusing message.
    #
    # X-Requested-With is on EVERY call, not just the mutating ones that strictly need it:
    # AuthFilter's CSRF guard 403s a cookie-authed POST/PUT/PATCH/DELETE under /api/** without it
    # (browsers cannot attach a custom header cross-origin without a CORS preflight this backend
    # never grants, which is the whole guard) — harmless to also send on GET/non-/api calls, and
    # one fewer thing to remember to add per call site below.
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

# (a) signup: PasswordAuthProvider (#852) creates the account AND signs it in, and
# AuthController#establishSession's tail already calls tenants.ensureDefaultOrg(...) — no separate
# org-creation call needed, a "Default" org + "Default" project exist the instant this returns.
# A throwaway, run-scoped email/password so a repeat local run against a fresh $TMP never collides.
_signup_email="open-boot-check+$(date +%s)-$$@example.invalid"
_signup_body="$(jq -nc --arg email "$_signup_email" --arg password "open-boot-check-$(date +%s)-passphrase" \
    '{email:$email, password:$password}')"
_code="$(_curl_json POST /auth/signup "$_signup_body")"
_expect "POST /auth/signup" 200 "$_code"

# (b) /auth/me, WITH the session cookie — proves the session is real, not just that signup 200'd.
# This is the "authenticated" proof #924 added to the gate; the EXISTING unauthenticated
# /api/v1/me -> 401 check above is untouched and stays a separate assertion, not repurposed into
# this one. Read the default org's slug from here (/auth/me is mounted under /auth, distinct from
# the unauthenticated /api/v1/me probed earlier — same literal path, different mapping).
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

# (d) mint a project-scoped MCP token — proves the self-hoster-facing token-issue surface works
# end to end under this session, even though nothing below strictly needs the token itself (the
# emit step below authenticates with a project-scoped API key of its own kind — see the comment
# there). Any member may issue one (McpTokenController#issue).
_code="$(_curl_json POST "/api/orgs/$ORG_SLUG/projects/$PROJECT_SLUG/mcp-tokens" '{"name":"open-boot-check"}')"
_expect "POST .../mcp-tokens" 201 "$_code"
MCP_TOKEN="$(jq -r '.data.plaintext // empty' "$BODY_FILE")"
if [ -z "$MCP_TOKEN" ]; then
    echo "check-open-boot: mcp-tokens issue returned 201 but no .data.plaintext token." >&2
    exit 1
fi
echo "check-open-boot: minted an mcp-token (tsy_… redacted)"

# (e) Store a BYO provider credential through the REST path the classifier pipeline actually reads
# keys from (ProviderCredentialController; gated on Capability.BYO_PROVIDER_KEYS, ON by default in
# the open edition) — with a PLACEHOLDER, never a real key.
#
# NO TEST OR CHECK MAY REQUIRE A REAL CREDENTIAL, LOCAL OR CI (rule set at #852/#996, reaffirmed
# 2026-09-02 when this step briefly asked for a CI-secret-backed real key and was reversed). A
# placeholder proves everything this step can prove: the PUT accepts it, seals it via SecretBox,
# and the GET below reads back has_api_key:true. Nothing downstream ever calls a provider with it —
# every model-graded classifier (FRUSTRATION, GROUNDEDNESS, BEHAVIOR_DRIFT, SOP_CONFORMANCE) is in
# CapabilityService.UNAVAILABLE_IN_OPEN_EDITION, so the open edition has no model-graded path at
# all, and the step (g) verdict is proven by the deterministic `secret_leak` classifier alone. A
# real-model proof, if ever wanted, is a human-run experiment against a human's own key — not a
# gate, and not something this script will ever ask for. Clearly fake on its face so it can never
# be mistaken for, or accidentally promoted into, a working credential.
OPEN_BOOT_PLACEHOLDER_KEY="sk-open-boot-placeholder-not-a-real-key-$$"
# Which ModelProvider enum value the placeholder is stored under; case-sensitive path variable.
PROVIDER_NAME="${OPEN_BOOT_PROVIDER_NAME:-ANTHROPIC}"
_provider_body="$(jq -nc --arg key "$OPEN_BOOT_PLACEHOLDER_KEY" '{api_key:$key}')"
_code="$(_curl_json PUT "/api/orgs/$ORG_SLUG/providers/$PROVIDER_NAME" "$_provider_body")"
_expect "PUT .../providers/$PROVIDER_NAME" 200 "$_code"
echo "check-open-boot: stored a $PROVIDER_NAME credential via the BYO-provider-credential REST path"

# Read the credential back and assert it actually sealed/stored, rather than trusting the PUT's
# 200 alone — nothing downstream (no open-edition classifier) ever reads this key back for us, so
# this is the only assertion in the script that the stored credential is real. The API never
# returns the secret itself (View exposes only has_api_key), so has_api_key:true is the strongest
# signal available without a model call.
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
# NOT `zipeats`/`policygpt`, which the plan this script implements originally named: neither is
# actually present in a genuine clean-room export. `zipeats`'s backing file
# (classifiers/data/food_delivery/conversations.jsonl) is covered by classifiers/.gitignore's
# blanket `data/` rule — it only exists on a machine that separately ran the LLM-keyed
# `data_gen.food_delivery.generate` step, which this KEYLESS check cannot do and must not depend
# on. `policygpt` reads from `POLICYGPT_STATE_DIR`, which defaults to a path under the operator's
# HOME directory, entirely outside the repo. `scripts/lib/export-simulate.sh` copies
# tracked-or-not-git-ignored files only, so a fresh CI checkout has neither corpus. `canary`
# (added to emit_local.py alongside this script — see its own docstring) is one hand-built
# Conversation with no file and no LLM dependency: a single tool call whose output embeds an
# AWS-access-key-id-shaped string, which is what makes step (g) below provable at all — see that
# step's comment for why the two real corpora ALSO would not have worked even if they were
# present (every open-edition-available STATISTICAL classifier needs a 100-500-call baseline that
# one CI-boot-check trace cannot supply; `secret_leak` is the one deterministic, no-baseline
# classifier available in the open edition, hence the canary).
echo "check-open-boot: emitting one canary trace through the ingest pipeline…"
# `uv run`, not a hardcoded `.venv/bin/python`: matches scripts/check-classifier-parity.sh's own
# convention for driving classifiers/ Python (uv resolves classifiers/pyproject.toml +
# classifiers/uv.lock and builds/reuses its own venv on demand — no committed .venv to go stale or
# be absent on a fresh CI runner). `--extra otlp` pulls in exactly what emit_local.py needs
# (opentelemetry-sdk + the proto-http exporter — classifiers/pyproject.toml's `otlp` extra) without
# the heavier `train`/`quality` extras this check has no use for.
(cd "$ROOT/classifiers" && uv run --quiet --extra otlp python -m data_gen.emit_local \
    --endpoint "$BASE/v1/traces" \
    --token "$MCP_TOKEN" \
    --corpus canary \
    --limit 1 \
    --no-resume)

# (g) poll for the classifier verdict this emission is supposed to produce.
# `/classifiers/events` (ClassifierController) is fed by ClassifierWorker's sweep over
# SubstrateObservation rows — the actual OTLP-trace-triggered triage pipeline — NOT
# ObserverController's /observer/classifications (git-diff-driven, a different mechanism) and NOT
# FindingController's /findings (the behavior-drift correction loop, also diff-based). The worker
# runs on a heartbeat (`tessary.classifier.heartbeat-ms`, default 60000ms via
# `@Scheduled(fixedDelayString=...)`) that this script does NOT override — doing so needs a new
# compose env passthrough line (docker-compose.dev.yml only forwards vars it names explicitly),
# which is a bigger footprint than this check's job; instead the poll budget below (10 tries x 20s
# = up to 200s) is sized to comfortably clear the default heartbeat plus one full sweep, not to
# assume a faster one.
#
# FALSE-GREEN GUARD (the plan's own name for this, and its highest-value review point): assert the
# response BODY carries at least one event, not just HTTP 200. An EncoderScorer failing silently
# against an unreachable/misconfigured classify service, or a ClassifierWorker that fail-opens,
# could return 200 with an empty `.data` array — the same class of defect #924 fixed one layer up
# (auth silently bypassed) reproduced one layer down (triage silently no-oping).
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
    # EVIDENCE, not just a verdict: the first time this step was ever reached in CI (run
    # 33614514928, after #1043 unblocked the deny-list step in front of it) it failed exactly like
    # this, and the only thing the log could say was the message above. Everything needed to tell
    # "worker never ticked" from "worker ticked and found nothing" from "classify unreachable" is in
    # the backend/classify container logs and the endpoint's last body — print those on the way out.
    # (Diagnostics go to stderr; the compose logs are trimmed to the triage-relevant lines so this
    # does not bury the message in Spring boot noise.)
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
# SCOPE BOUNDARY (kept out of this run deliberately, per the plan this script implements):
#   - The `launcher` compose profile / sandbox-runner are never started. The triage path under test
#     (EncoderScorer/EncoderDetector/ClassifierWorker, plus the deterministic secret_leak path
#     above) makes no E2B/sandbox call, and this issue's own code pointers never named the
#     launcher. (Until #1052 docker-compose.dev.yml also defaulted that service to
#     SANDBOX_BACKEND=e2b, which would have manufactured a credential dependency; it no longer
#     does, and check-open-boot-selfhost.sh boots sandbox-runner on the docker default.)
#   - The provider key wired in step (e) stays scoped to the ProviderCredentialController REST
#     call only — never added to sandbox-runner's AGENT_PROVIDER/ANTHROPIC_API_KEY compose env,
#     which would conflate the classifier-verdict path with the agentic-synthesis path this check
#     does not test.
# --------------------------------------------------------------------------------------------------
