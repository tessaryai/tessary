#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Detached open-edition SELF-HOST boot check (#1052) — the second leg of the epic-2 gate.
#
# scripts/check-open-boot.sh boots docker-compose.dev.yml (through scripts/lib/dev-compose.sh) and
# drives the authenticated triage flow through it. That proves the DEV stack boots keyless. The
# artifact a self-hoster actually runs is docker-compose.yml — a different file with a different
# shape, and until #1052 no check had ever booted it: it defaults SPRING_PROFILES_ACTIVE to
# `production` (activating application-production.yaml and AuthRequiredInProdGuard, which refuses
# to start on a blank session key), it builds the real backend/Dockerfile and
# frontend/Dockerfile (the Caddy-bundled image #926 hardened, which had no automated coverage
# either), it starts sandbox-runner unconditionally (docker.sock mount, group_add, published image),
# and it carried a hardcoded Tessary-region default the dev file never had. The gate's green run
# said nothing about any of that. This leg does — boot + production auth guard + credential
# deny-list. It deliberately does NOT repeat the authenticated triage flow: that is the dev leg's
# job, and the two files share the same backend image contents, so proving the flow twice buys
# nothing the production-profile boot itself does not already prove.
#
# NEVER RUN AGENT-SIDE, EVER — same rule as check-open-boot.sh, same reason (needs Docker to build
# and boot a real stack). Run by a human (`task check:open:boot:selfhost`) or the dispatch-only
# `.github/workflows/open-edition-boot.yml` (its second job). Never part of `task check` or
# scripts/check.sh — see its EXCLUDED manifest row.
#
# WHAT IS PASSED IN, AND HOW — AND WHAT NO LONGER IS. Until #1230 this leg wrote a `.env` into the
# export holding four generated credential values, because docker-compose.yml could not render or
# boot without them: two of its own keys used `:?must be set`, so Compose refused before pulling an
# image, and the production profile's auth guard refused to start on a blank session key. That is
# exactly the prerequisite #1230 removed, so the write is gone, and ITS ABSENCE IS THIS LEG'S
# ASSERTION: the self-host artifact must now boot with no credential supplied to it at all. If a
# future change reintroduces one, this leg fails, which is the point — the .env below carries only
# this run's port/dir knobs and nothing that seals or authenticates anything.
#
# Kept from the original recipe: no cloud var, no SITE_DOMAIN (unset, so Caddy's :8000 plain-HTTP
# site is what a fresh self-host reaches first, and so PlaceholderSecretGuard stays in its warn
# branch rather than refusing the boot; the first run of this leg found that a BLANK SITE_DOMAIN
# crash-looped Caddy, fixed in docker-compose.yml's own default), and no SANDBOX_BACKEND (the code
# default, docker, must win on its own). HTTP_PORT/HTTPS_PORT are moved off :80/:443 only so this
# can run on a host that already has something on them; they are the file's own documented knobs.
#
#   bash scripts/check-open-boot-selfhost.sh                    the boot, as a self-hoster runs it
#   bash scripts/check-open-boot-selfhost.sh --negative-health  prove the health signal can fail:
#       the backend's probe is overridden to a failing command, and the frontend must stay
#       `created` (waiting) rather than `running`, with the wait reporting the failure instead of
#       hanging until a timeout (epic 7 clause 4, #1189)
#   bash scripts/check-open-boot-selfhost.sh --domain  boot with SITE_DOMAIN set (#1225): the
#       sign-in origin and the WorkOS callback derive from it, the frontend serves the hostname on
#       :HTTPS_PORT with an operator-mounted certificate and no ACME attempt, a scheme-prefixed
#       SITE_DOMAIN and a disagreeing EVALS_AUTH_FRONTEND_URL each refuse the boot naming the key,
#       and in upstream mode a forwarded client address is honoured from TRUSTED_PROXIES only.
#       This leg DOES write two generated sealing keys, because PlaceholderSecretGuard refuses a
#       domain on the shipped placeholders; the keyless assertion belongs to the default leg.
#   bash scripts/check-open-boot-selfhost.sh --kafka  boot with the `kafka` profile and
#       EVALS_INGEST_SPOOL_MODE=kafka (#984): the bundled Redpanda is in the healthy set, its
#       binary runs with --unsafe-bypass-fsync=false (the durability the docs promise), the
#       backend creates both ingest topics on its own, and the consumer group holds one member per
#       configured drainer, each with partitions assigned — the parallel drain is real, not a knob.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
NEGATIVE_HEALTH=0
DOMAIN_LEG=0
KAFKA_LEG=0
for arg in "$@"; do
    case "$arg" in
        --negative-health) NEGATIVE_HEALTH=1 ;;
        --domain) DOMAIN_LEG=1 ;;
        --kafka) KAFKA_LEG=1 ;;
        *) echo "check-open-boot-selfhost: unknown argument '$arg' (accepts --negative-health, --domain, --kafka)" >&2; exit 2 ;;
    esac
done

# shellcheck source=lib/cloud-credential-denylist.sh
. "$ROOT/scripts/lib/cloud-credential-denylist.sh"
# shellcheck source=lib/open-boot-lib.sh
. "$ROOT/scripts/lib/open-boot-lib.sh"

P="check-open-boot-selfhost"

# Defense-in-depth, FIRST: no ambient operator credential reaches the export, the compose
# rendering or the containers. See check-open-boot.sh's identical loop for the full reasoning.
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    unset "$_cred_var" 2>/dev/null || true
done

TMP="$(mktemp -d)"
# A clean-room boot must not share state with the developer's own stack: both compose files carry
# `name: tessary`, so without this the export reuses the developer's project, its persistent
# Postgres volume (a pre-partition database fails the regenerated baseline's checksum, D-A) and
# tears that stack down on exit. COMPOSE_PROJECT_NAME outranks the file's `name:`.
export COMPOSE_PROJECT_NAME="open-boot-selfhost-$$"
COMPOSE=""

_cleanup() {
    local status=$?
    if [ -n "$COMPOSE" ]; then
        echo "$P: tearing down the stack ($TMP)…"
        # -v: docker-compose.yml declares named volumes (caddy-data, caddy-config); drop them with
        # the project so a re-run on the same host starts from nothing.
        (cd "$TMP" && $COMPOSE down -v --remove-orphans -v) || true
        # postgres writes its data dir (POSTGRES_DATA_DIR, defaulted below to a path under $TMP)
        # as the container's own uid, which a non-root operator cannot `rm -rf`. Clear it the same
        # way it was written — from a container — before removing the export. busybox:1.36 is
        # already local: docker-compose.yml's sandbox-runner-work-init service pulled it.
        docker run --rm -v "$TMP:/t" busybox:1.36 sh -c 'rm -rf /t/.local /t/.data' >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

echo "$P: exporting the working tree (faithful export, #889) -> $TMP"
bash "$ROOT/scripts/lib/export-simulate.sh" "$TMP"

# The self-host artifact, named directly and on purpose. This is NOT a dev-stack invocation, so
# scripts/lib/dev-compose.sh (the dev file's single derivation, check-open-boundary.sh rule 4) is
# the wrong tool here: docker-compose.yml has no overlay fragment to merge, and in the export
# there is no overlay to find anyway.
COMPOSE="docker compose -f docker-compose.yml"
if [ "$NEGATIVE_HEALTH" = 1 ]; then
    # A failing backend probe with a short budget, layered over the real file so nothing else
    # about the boot changes; `up -d` then fails once Docker marks the backend unhealthy.
    cat > "$TMP/negative-health.override.yml" <<'EOF'
services:
  backend:
    healthcheck:
      test: ["CMD", "false"]
      interval: 2s
      timeout: 1s
      retries: 3
      start_period: 0s
EOF
    COMPOSE="$COMPOSE -f negative-health.override.yml"
fi
# The services docs/self-hosting/setup.mdx names, plus Redpanda when the kafka profile is on: the
# healthy set below must be exactly these, so the profile-gated service is asserted, not tolerated.
SERVICES=(postgres backend frontend sandbox-runner)
KAFKA_CONSUMERS=4
if [ "$KAFKA_LEG" = 1 ]; then
    COMPOSE="$COMPOSE --profile kafka"
    SERVICES+=(redpanda)
fi

HTTP_PORT="${OPEN_BOOT_SELFHOST_HTTP_PORT:-18000}"
HTTPS_PORT="${OPEN_BOOT_SELFHOST_HTTPS_PORT:-18443}"
BASE="http://localhost:${HTTP_PORT}"
DOMAIN="tessary.test"
if [ "$DOMAIN_LEG" = 1 ]; then
    # An operator-mounted certificate (TLS_MODE=owncert), so the domain is served without any
    # ACME traffic and with a subject this leg can check. Mounted through the same override-file
    # shape docs/self-hosting/custom-domain.mdx documents.
    mkdir -p "$TMP/certs"
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 2 \
        -subj "/CN=$DOMAIN" -keyout "$TMP/certs/tls.key" -out "$TMP/certs/tls.crt" >/dev/null 2>&1
    chmod 644 "$TMP/certs/tls.key" "$TMP/certs/tls.crt"
    cat > "$TMP/domain.override.yml" <<YML
services:
  frontend:
    volumes:
      - $TMP/certs:/certs:ro
YML
    COMPOSE="$COMPOSE -f domain.override.yml"
fi

# The file's own documented knob for the launcher's docker.sock access (see docker-compose.yml's
# sandbox-runner comment). Read it off the socket itself, from inside a container, so this is
# right on any host — a GitHub runner's docker group is not GID 999, and Docker Desktop's is 0.
# Read as a CONTAINER sees the socket, which is the only view that matters: Docker Desktop mounts
# it root:root, so the host-side stat (501:20 on a Mac) was never the right number (#1191 found it).
DOCKER_SOCK_GID="$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock busybox:1.36 stat -c %g /var/run/docker.sock 2>/dev/null || echo 999)"
# This run's port/dir knobs, and deliberately NOTHING ELSE (see the header). Compose reads this file
# both for `${VAR}` interpolation and through backend's `env_file:`, and `config`/`ps` below see the
# same file the `up` did. Every value here is a host-shape knob this leg needs to run beside a
# developer's own stack; not one of them seals, signs or authenticates anything, which is what makes
# the boot below a proof that the shipped artifact needs no credential from its operator.
{
    echo "HTTP_PORT=$HTTP_PORT"
    echo "HTTPS_PORT=$HTTPS_PORT"
    echo "POSTGRES_DATA_DIR=$TMP/.local/postgres"
    echo "DOCKER_SOCK_GID=$DOCKER_SOCK_GID"
    # The one volume COMPOSE_PROJECT_NAME does NOT isolate: docker-compose.yml gives `sandbox-work`
    # an explicit `name:`, because the launcher hands that exact string to the Engine API when it
    # starts a sibling container and a project prefix would not match. Run-scoped here so this
    # leg's `down -v` cannot reach a developer's own launcher volume.
    echo "SANDBOX_WORK_VOLUME=sandbox-work-$COMPOSE_PROJECT_NAME"
    if [ "$DOMAIN_LEG" = 1 ]; then
        echo "SITE_DOMAIN=$DOMAIN"
        echo "TLS_MODE=owncert"
        echo "EVALS_AUTH_COOKIE_PASSWORD=$(openssl rand -base64 32)"
        echo "EVALS_SECRET_KEY=$(openssl rand -base64 32)"
    fi
    if [ "$KAFKA_LEG" = 1 ]; then
        echo "EVALS_INGEST_SPOOL_MODE=kafka"
        echo "EVALS_INGEST_SPOOL_KAFKA_CONSUMERS=$KAFKA_CONSUMERS"
    fi
} > "$TMP/.env"
chmod 600 "$TMP/.env"

# Every denied name passed explicitly empty on the `up` line too — belt to the unset loop's
# suspenders; an empty assignment is what Compose's `${VAR:-default}` interpolation reads.
_empty_cred_assignments=()
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    _empty_cred_assignments+=("${_cred_var}=")
done

if [ "$NEGATIVE_HEALTH" = 1 ]; then
    echo "$P: NEGATIVE: booting with the backend's health probe forced to fail; the frontend must wait, not run…"
    if (cd "$TMP" && env "${_empty_cred_assignments[@]}" $COMPOSE up -d --build); then
        echo "$P: FAIL, 'up -d' succeeded with a backend that can never be healthy; the frontend did not wait on service_healthy" >&2
        exit 1
    fi
    _fe_state="$(cd "$TMP" && $COMPOSE ps -a --format json 2>/dev/null | jq -rs '.[] | select(.Service == "frontend") | .State' | head -1)"
    case "${_fe_state:-absent}" in
        created|absent) echo "$P: negative ok: 'up -d' refused (backend unhealthy) and the frontend is '${_fe_state:-absent}': waiting, never started" ;;
        *) echo "$P: FAIL, the frontend is '${_fe_state}' beside an unhealthy backend; only 'created' (waiting) proves the dependency held" >&2; exit 1 ;;
    esac
    if open_boot_wait_healthy "$P" "$TMP" "$COMPOSE" 60 postgres backend frontend 2>/dev/null; then
        echo "$P: FAIL, the health wait reported healthy for a stack whose backend probe is 'false'" >&2; exit 1
    fi
    [ "$OPEN_BOOT_ELAPSED_S" -lt 60 ] || { echo "$P: FAIL, the health wait ran to its ${OPEN_BOOT_ELAPSED_S}s budget instead of failing on the unhealthy backend" >&2; exit 1; }
    echo "$P: negative ok: the health wait failed in ${OPEN_BOOT_ELAPSED_S}s on the unhealthy backend rather than running out its budget"
    echo "$P: NEGATIVE ok: the readiness signal is proven able to fail"
    exit 0
fi

echo "$P: booting docker-compose.yml (self-host artifact) — production profile, real Dockerfiles, sandbox-runner included, no denied credential…"
(cd "$TMP" && env "${_empty_cred_assignments[@]}" $COMPOSE up -d --build)

fail=0

# The stack's own readiness signal first (epic 7 clause 4, #1189): `docker compose ps` must
# report healthy for exactly the four services docs/self-hosting/setup.mdx names, and for no
# other, before any HTTP probe below is allowed to stand in for it. 320 s clears the backend
# probe's own give-up point (15 s start_period plus 60 retries x 5 s = 315 s), so this budget is
# never what ends the wait; Docker's verdict is. In practice `up -d` above already blocked on
# backend health through the frontend's service_healthy condition, so this returns at once.
open_boot_wait_healthy "$P" "$TMP" "$COMPOSE" 320 "${SERVICES[@]}" || fail=1
open_boot_assert_healthy_set "$P" "$TMP" "$COMPOSE" "${SERVICES[@]}" || fail=1

# Through Caddy on the plain-HTTP site, because that is what a fresh self-host reaches first.
# frontend/caddy/base.caddy's :8000 site proxies the prefixes its @backend matcher names and serves
# the SPA for everything else — so `/v3/api-docs`, which the matcher does not name, is NOT a backend
# probe here (it falls through to index.html), unlike the dev leg; and under the production profile
# springdoc is disabled anyway.
open_boot_wait_for "$P" "GET /            (frontend, via caddy :$HTTP_PORT)" "$BASE/"          200 90 || fail=1
open_boot_wait_for "$P" "GET /api/v1/me   (backend,  via caddy)"            "$BASE/api/v1/me" 401 90 || fail=1
open_boot_wait_for "$P" "GET /auth/me     (backend,  via caddy)"            "$BASE/auth/me"   401    || fail=1
# #1255: the status IS the assertion. This route is bearer-scoped, so the backend answers 401 —
# while an unproxied path does not 404, it falls through to the SPA and answers index.html with a
# 200. So 401 proves the @backend matcher carries it and 200 proves it does not, which is the exact
# failure this leg missed for as long as the matcher omitted the prefix. `unit` is supplied only to
# keep the URL a realistic one: TenantContext is MeteringController.timeseries's first parameter and
# Spring resolves arguments in declaration order, so TenantArgumentResolver's 401 is raised before
# any @RequestParam is looked at and a missing one could not answer first anyway.
open_boot_wait_for "$P" "GET /v1/usage/*   (backend,  via caddy — not the SPA)" \
    "$BASE/v1/usage/timeseries?unit=ingested_spans" 401 || fail=1

# The production profile must actually be the one that booted — that is the whole point of this
# leg (AuthRequiredInProdGuard, application-production.yaml). Proved BEHAVIOURALLY, from inside
# the stack's own network: application-production.yaml sets `springdoc.api-docs.enabled: false`,
# so the backend answers /v3/api-docs with 404 under production and 200 under the default
# profile. (Through Caddy that path is not proxied — it falls through to the SPA — hence the
# direct probe. busybox:1.36 is already local: sandbox-runner-work-init pulled it.) A grep of the
# backend log for Spring's "profile is active" line was the first draft of this assertion and
# proved unreliable across runs (green on 33627844257, absent on 33628754275 with an otherwise
# identical boot) — it is kept below as a non-fatal note so the next miss carries evidence.
_backend_cid="$(cd "$TMP" && $COMPOSE ps -q backend 2>/dev/null || true)"
_backend_net="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$_backend_cid" 2>/dev/null || true)"
_apidocs_status="$(docker run --rm --network "$_backend_net" busybox:1.36 \
    wget -S -q -O /dev/null -T 10 http://backend:8080/v3/api-docs 2>&1 | grep -oE 'HTTP/[0-9.]+ [0-9]{3}' | head -1 || true)"
case "$_apidocs_status" in
    *" 404")
        echo "$P: backend /v3/api-docs (direct, in-network) -> 404: springdoc disabled, so application-production.yaml is live (ok)" ;;
    *)
        echo "$P: backend /v3/api-docs (direct, in-network) -> '${_apidocs_status:-no response}', wanted 404." >&2
        echo "    application-production.yaml (springdoc.api-docs.enabled=false) did not apply, so the" >&2
        echo "    self-host default SPRING_PROFILES_ACTIVE=production was not what booted." >&2
        fail=1 ;;
esac
_backend_log="$(cd "$TMP" && $COMPOSE logs --no-color backend 2>/dev/null || true)"
if printf '%s\n' "$_backend_log" | grep -qF 'profile is active: \"production\"' \
   || printf '%s\n' "$_backend_log" | grep -qF 'profile is active: "production"'; then
    echo "$P: (note) backend log carries Spring's 'profile is active: production' line"
else
    echo "$P: (note) Spring's 'profile is active' line is NOT in the backend log — not gating; lines mentioning 'profile':" >&2
    printf '%s\n' "$_backend_log" | grep -n -i 'profile' | head -5 >&2 || true
fi

# Every default (non-profile) service must be RUNNING, not just created: sandbox-runner in
# particular starts unconditionally in this file (docker.sock mount, group_add, published
# image) and its launcher exits at boot on a config it rejects — a self-hoster would see that
# on their first `docker compose ps`, so this check does too.
for _svc in "${SERVICES[@]}"; do
    if (cd "$TMP" && $COMPOSE ps --status running --services 2>/dev/null) | grep -qx "$_svc"; then
        echo "$P: service '$_svc' is running (ok)"
    else
        echo "$P: service '$_svc' is NOT running. Its log tail:" >&2
        (cd "$TMP" && $COMPOSE logs --no-color --tail=40 "$_svc" 2>/dev/null) >&2 || true
        fail=1
    fi
done

# The credential deny-list, post-boot (rendered config + every container's own env). Same
# environment as the `up` above — the exports higher up are what make `config` render at all.
open_boot_check_denied_credentials "$P" "$TMP" "$COMPOSE" "${SERVICES[@]}" || fail=1

if [ "$KAFKA_LEG" = 1 ]; then
    echo "$P: KAFKA: profile kafka, EVALS_INGEST_SPOOL_MODE=kafka, $KAFKA_CONSUMERS consumers (#984)…"
    # 1. Durability is the whole reason to opt in, so the broker's own process line must carry the
    # explicit fsync flag docker-compose.yml passes (the image's developer_mode would otherwise
    # bypass fsync). Read from /proc rather than the log: the flag is the binary's argument.
    _rp_args="$(cd "$TMP" && $COMPOSE exec -T redpanda sh -c 'for f in /proc/[0-9]*/cmdline; do tr "\0" " " < "$f"; echo; done 2>/dev/null' | grep -E '(^| )/opt/redpanda/bin/redpanda ' | head -1 || true)"
    case "$_rp_args" in
        *"--unsafe-bypass-fsync=false"*) echo "$P: redpanda runs with --unsafe-bypass-fsync=false (ok)" ;;
        *) echo "$P: FAIL, the redpanda binary's arguments lack --unsafe-bypass-fsync=false: '${_rp_args:-none}'" >&2; fail=1 ;;
    esac
    # 2. The backend creates its topics on first use; the drainers claim from boot, so both exist
    # well before the health wait above returned.
    _topics="$(cd "$TMP" && $COMPOSE exec -T redpanda rpk topic list 2>/dev/null || true)"
    for _t in tessary.ingest tessary.ingest.dead-letter; do
        if printf '%s\n' "$_topics" | grep -qE "^${_t}[[:space:]]"; then
            echo "$P: topic '$_t' exists (ok)"
        else
            echo "$P: FAIL, topic '$_t' was not created by the backend; rpk topic list:" >&2
            printf '%s\n' "$_topics" >&2; fail=1
        fi
    done
    # 3. One group member per configured drainer, each holding partitions: the parallel drain is
    # observable from the broker, not just a property. The group can lag the topic by a rebalance,
    # so give it a short wait. A partition row is nine columns (TOPIC PARTITION CURRENT-OFFSET
    # LOG-START-OFFSET LOG-END-OFFSET LAG MEMBER-ID HOST CLIENT-ID); an unassigned partition prints
    # the last three empty, so only full rows count and the member id is read by position.
    _members=""
    for _i in $(seq 1 30); do
        _describe="$(cd "$TMP" && $COMPOSE exec -T redpanda rpk group describe tessary-ingest 2>/dev/null || true)"
        _members="$(printf '%s\n' "$_describe" | awk 'NF == 9 && $1 == "tessary.ingest" { print $7 }' | sort -u | grep -c . || true)"
        _stable="$(printf '%s\n' "$_describe" | grep -cE '^STATE[[:space:]]+Stable' || true)"
        if [ "$_members" = "$KAFKA_CONSUMERS" ] && [ "$_stable" = 1 ]; then break; fi
        sleep 2
    done
    if [ "$_members" = "$KAFKA_CONSUMERS" ]; then
        echo "$P: consumer group tessary-ingest is Stable with $_members members holding partitions (ok)"
    else
        echo "$P: FAIL, consumer group tessary-ingest shows ${_members:-0} members with partitions, wanted $KAFKA_CONSUMERS; rpk group describe:" >&2
        printf '%s\n' "${_describe:-}" >&2; fail=1
    fi
fi

if [ "$DOMAIN_LEG" = 1 ]; then
    echo "$P: DOMAIN: SITE_DOMAIN=$DOMAIN, TLS_MODE=owncert (#1225)…"
    HTTPS_BASE="https://$DOMAIN:$HTTPS_PORT"
    _resolve=(--resolve "$DOMAIN:$HTTPS_PORT:127.0.0.1")

    # 1. The derived origins, as the backend resolved and logged them.
    _origin_line="$(printf '%s\n' "$_backend_log" | grep -F 'public origin' | head -1 || true)"
    _want="public origin https://$DOMAIN/ from SITE_DOMAIN=$DOMAIN (TLS_MODE=owncert, EVALS_AUTH_FRONTEND_URL=https://$DOMAIN/, WORKOS_REDIRECT_URI=https://$DOMAIN/auth/callback)"
    if printf '%s\n' "$_origin_line" | grep -qF "$_want"; then
        echo "$P: backend derived every origin from SITE_DOMAIN alone (ok)"
    else
        echo "$P: FAIL, backend did not log the derived origins. Wanted: $_want" >&2
        echo "    got: ${_origin_line:-<no 'public origin' line>}" >&2; fail=1
    fi

    # 2. The hostname is served on :HTTPS_PORT with the mounted certificate, and no ACME attempt was made.
    _mode_code="$(curl -sk "${_resolve[@]}" -o /dev/null -w '%{http_code}' "$HTTPS_BASE/auth/mode" || true)"
    if [ "$_mode_code" = 200 ]; then
        echo "$P: GET /auth/mode over https://$DOMAIN:$HTTPS_PORT -> 200 (ok)"
    else
        echo "$P: FAIL, GET /auth/mode over https://$DOMAIN:$HTTPS_PORT -> '${_mode_code:-none}', wanted 200" >&2; fail=1
    fi
    _subject="$(printf '' | openssl s_client -connect "127.0.0.1:$HTTPS_PORT" -servername "$DOMAIN" 2>/dev/null | openssl x509 -noout -subject 2>/dev/null || true)"
    case "$_subject" in
        *"CN"*"$DOMAIN"*) echo "$P: the served certificate is the mounted one ($_subject) (ok)" ;;
        *) echo "$P: FAIL, served certificate subject '$_subject' is not the mounted CN=$DOMAIN" >&2; fail=1 ;;
    esac
    _frontend_log="$(cd "$TMP" && $COMPOSE logs --no-color frontend 2>/dev/null || true)"
    if printf '%s\n' "$_frontend_log" | grep -qiE 'acme|letsencrypt|obtaining certificate'; then
        echo "$P: FAIL, the frontend log shows ACME activity in owncert mode:" >&2
        printf '%s\n' "$_frontend_log" | grep -iE 'acme|letsencrypt|obtaining certificate' | head -5 >&2; fail=1
    else
        echo "$P: no ACME activity in the frontend log (ok)"
    fi

    # 3. The sign-in landing follows the domain: the degrade branch of GET /auth/login redirects
    #    to the derived origin, not to localhost.
    _login_loc="$(curl -s -o /dev/null -w '%{redirect_url}' "$BASE/auth/login" || true)"
    case "$_login_loc" in
        "https://$DOMAIN/"*) echo "$P: GET /auth/login lands on $_login_loc (ok)" ;;
        *) echo "$P: FAIL, GET /auth/login redirects to '${_login_loc:-none}', wanted https://$DOMAIN/…" >&2; fail=1 ;;
    esac

    # 4. Two refusals, each naming its key. The backend alone is recreated with the bad value; the
    #    rest of the stack stays up. `up -d backend` returns once the container is created, and the
    #    guard throws during context refresh, so the log line is what to wait for. 300 s: a recreated
    #    backend re-runs Liquibase against the live database before any guard runs.
    _refusal() {
        local label="$1" needle="$2"; shift 2
        local saved="$TMP/.env.saved"
        cp "$TMP/.env" "$saved"
        printf '%s\n' "$@" >> "$TMP/.env"
        (cd "$TMP" && env "${_empty_cred_assignments[@]}" $COMPOSE up -d --no-deps backend >/dev/null 2>&1) || true
        local deadline=$((SECONDS + 300)) found=0
        while [ "$SECONDS" -lt "$deadline" ]; do
            if (cd "$TMP" && $COMPOSE logs --no-color --since 10m backend 2>/dev/null) | grep -qF "$needle"; then found=1; break; fi
            sleep 3
        done
        cp "$saved" "$TMP/.env"
        if [ "$found" = 1 ]; then
            echo "$P: negative ok: $label refused the boot, naming $needle"
        else
            echo "$P: FAIL, $label did not refuse within 300s (wanted '$needle' in the backend log)" >&2
            (cd "$TMP" && $COMPOSE logs --no-color --tail=20 backend 2>/dev/null) >&2 || true
            fail=1
        fi
    }
    _refusal "a scheme-prefixed SITE_DOMAIN" "SITE_DOMAIN must be a bare hostname" "SITE_DOMAIN=https://$DOMAIN"
    _refusal "a disagreeing EVALS_AUTH_FRONTEND_URL" "EVALS_AUTH_FRONTEND_URL is https://other.test/" "EVALS_AUTH_FRONTEND_URL=https://other.test/"
    # Back to the good configuration; the frontend waits on the backend being healthy again.
    (cd "$TMP" && env "${_empty_cred_assignments[@]}" $COMPOSE up -d --no-deps backend >/dev/null 2>&1) || true
    open_boot_wait_healthy "$P" "$TMP" "$COMPOSE" 320 backend || fail=1

    # 5. Upstream mode: a forwarded client address is honoured from TRUSTED_PROXIES and from nobody
    #    else. Only the frontend is recreated; Caddy's JSON access log carries client_ip.
    _forwarded_client_ip() {
        local nonce="$1"
        curl -s -o /dev/null -H 'X-Forwarded-For: 203.0.113.9' "$BASE/auth/mode?probe=$nonce" || true
        sleep 1
        (cd "$TMP" && $COMPOSE logs --no-color --since 2m frontend 2>/dev/null) | grep -F "probe=$nonce" | head -1 \
            | grep -oE '"client_ip":"[^"]*"' | head -1 || true
    }
    _set_frontend_env() {
        local saved="$TMP/.env.saved"
        cp "$TMP/.env" "$saved"
        grep -vE '^(TLS_MODE|TRUSTED_PROXIES)=' "$saved" > "$TMP/.env"
        printf '%s\n' "$@" >> "$TMP/.env"
        (cd "$TMP" && env "${_empty_cred_assignments[@]}" $COMPOSE up -d --no-deps frontend >/dev/null 2>&1)
        open_boot_wait_for "$P" "GET / (frontend recreated)" "$BASE/" 200 60 >/dev/null || fail=1
    }
    _set_frontend_env "TLS_MODE=upstream"
    _ip_untrusted="$(_forwarded_client_ip "untrusted-$$")"
    case "$_ip_untrusted" in
        *203.0.113.9*) echo "$P: FAIL, upstream mode with TRUSTED_PROXIES blank honoured a forged X-Forwarded-For ($_ip_untrusted)" >&2; fail=1 ;;
        '"client_ip":"'*) echo "$P: TRUSTED_PROXIES blank: forged X-Forwarded-For ignored, client_ip is the peer ($_ip_untrusted) (ok)" ;;
        *) echo "$P: FAIL, no access-log line with client_ip for the untrusted probe" >&2; fail=1 ;;
    esac
    _set_frontend_env "TLS_MODE=upstream" "TRUSTED_PROXIES=0.0.0.0/0"
    _ip_trusted="$(_forwarded_client_ip "trusted-$$")"
    case "$_ip_trusted" in
        *203.0.113.9*) echo "$P: TRUSTED_PROXIES set: forwarded client address honoured ($_ip_trusted) (ok)" ;;
        *) echo "$P: FAIL, upstream mode with TRUSTED_PROXIES set did not honour X-Forwarded-For (got '${_ip_trusted:-none}')" >&2; fail=1 ;;
    esac
fi

[ "$fail" = 0 ] || {
    echo "$P: FAILED — see above." >&2
    exit 1
}
if [ "$KAFKA_LEG" = 1 ]; then
    echo "$P: KAFKA ok: the kafka profile boots the bundled Redpanda into the healthy set with fsync on," \
         "the backend creates its topics, and the consumer group holds $KAFKA_CONSUMERS members with partitions."
    exit 0
fi
if [ "$DOMAIN_LEG" = 1 ]; then
    echo "$P: DOMAIN ok: SITE_DOMAIN alone derives every origin, the hostname is served with the mounted" \
         "certificate and no ACME attempt, both misconfigurations refuse the boot by name, and upstream mode" \
         "honours a forwarded client address from TRUSTED_PROXIES only."
    exit 0
fi
echo "$P: the self-host artifact (docker-compose.yml) boots keyless under the production profile -" \
     "backend up behind the auth guard, frontend/Caddy serving :${HTTP_PORT}, sandbox-runner running on" \
     "the docker sandbox default, no denied credential anywhere."
