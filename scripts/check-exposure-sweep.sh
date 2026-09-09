#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The fresh-deployment exposure sweep (epic 6 clause 8, #1153; re-run unmodified by epic 7's #1198).
#
# Boots the self-host artifact, docker-compose.yml, exactly as check-open-boot-selfhost.sh does — a
# faithful export, the production profile, no credential supplied — then attacks it from outside
# with nothing but what the quickstart ships, and from a neighbouring tenant with an ordinary
# account. Five arms, each recording every probe it makes as `METHOD PATH -> CODE  note` so a
# green run is a listed result set rather than a silent pass:
#
#   1. unauthenticated probe   every operation in the open OpenAPI spec, plus the actuator, MCP,
#                              OTLP and springdoc paths the spec does not list, called with no
#                              cookie and no bearer. A 2xx is a finding unless the path is
#                              unauthenticated BY DESIGN (the allowlist below, each entry with why).
#   2. default credential      the rendered compose and every container env carry no denied
#                              credential; the database holds no session and no membership before
#                              the first signup; a guessed default login fails; the Postgres port
#                              is not published; and (2b, last, since it takes the backend down)
#                              the shipped placeholder sealing keys make the backend REFUSE to boot
#                              under a real SITE_DOMAIN, which is what keeps a forgeable session
#                              cookie off any host that is not localhost.
#   3. served bundle           the frontend's /srv, byte for byte: no source map, no
#                              sourceMappingURL, no credential-shaped string, no absolute build
#                              path, nothing from the export's forbidden-string list (EXPOSURE_
#                              FORBIDDEN — see the variable below; the list moved into the overlay
#                              at #1293 and this script may not name the overlay itself).
#   4. SSRF and traversal      a listener on the stack network; every customer-supplied URL field
#                              (provider base_url_override, source baseUrl) is pointed at it and
#                              at the loopback backend, and every id-shaped path segment is given
#                              a traversal payload. A connection to the listener, or a 2xx on a
#                              traversal, is a finding.
#   5. cross-tenant            two fresh accounts; every read/list operation under an org is
#                              called by tenant B with tenant A's slugs (ids are a well-formed
#                              placeholder ULID, so the answer is the tenant gate's, 403 or 404),
#                              and B's project-bound bearer is aimed at A's org routes. A 2xx is
#                              a finding.
#
# The result set is written to scripts/lib/exposure-sweep-baseline.txt with --record, and every
# later run diffs itself against that file: a probe that changed status is reported by name, which
# is what lets #1198 re-run this instrument unmodified and state what moved. The exit code is
# the FINDINGS, never the diff — a baseline is a record, not an allowlist.
#
# NEVER RUN AGENT-SIDE, EVER — needs Docker, the network and minutes. Run by a human
# (`task check:exposure:sweep`) or the dispatch-only open-edition-boot.yml. EXCLUDED from
# `task check` (see scripts/check.sh's manifest).
set -euo pipefail
P=check-exposure-sweep
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RECORD=0
for arg in "$@"; do
    case "$arg" in
        --record) RECORD=1 ;;
        *) echo "$P: unknown argument '$arg' (accepts --record)" >&2; exit 2 ;;
    esac
done
for tool in docker curl jq python3 shasum; do command -v "$tool" >/dev/null || { echo "$P: $tool is required" >&2; exit 2; }; done

# ARM 3's FORBIDDEN-STRING LIST, AND WHY IT IS A VARIABLE (#1293 review). #1293 relocated
# export-forbidden-strings.txt into the paid overlay, and this script may not name the overlay --
# check-open-boundary.sh rule 5 fails any scripts/*.sh outside its four-name allowlist that does --
# so the caller passes the path in. The default is the pre-#1293 open location, which no longer
# exists in either repo; `task check:exposure:sweep` sets it to the overlay copy. Same shape and
# same reason as check-classifier-quality-doc.sh's CQ_DOC.
#
# When the list is not there the sweep does NOT quietly drop the loop and leave the row out of the
# result set, which is what it used to do: the run then printed no `BUNDLE forbidden-strings hits`
# row at all while still exiting on FINDINGS, so the only signal was a baseline diff the exit code
# ignores. It records `not-scanned` instead, so the absence is a visible row rather than a silence.
# It is NOT a finding: the public repo has no overlay, so open-edition-boot.yml legitimately runs
# this arm without the list, and a permanently red public gate is the failure mode that gets a gate
# switched off.
EXPOSURE_FORBIDDEN="${EXPOSURE_FORBIDDEN:-scripts/lib/export-forbidden-strings.txt}"
docker info >/dev/null 2>&1 || { echo "$P: the docker daemon is not reachable" >&2; exit 2; }

# shellcheck source=scripts/lib/cloud-credential-denylist.sh
. "$ROOT/scripts/lib/cloud-credential-denylist.sh"
# shellcheck source=scripts/lib/open-boot-lib.sh
. "$ROOT/scripts/lib/open-boot-lib.sh"

BASELINE="$ROOT/scripts/lib/exposure-sweep-baseline.txt"
SPEC="$ROOT/backend/contract/src/main/resources/openapi/tessary-api.json"
TMP="$(mktemp -d)"
export COMPOSE_PROJECT_NAME="exposure-sweep-$$"
COMPOSE=""
LISTENER="exposure-sweep-listener-$$"
RESULTS="$TMP/results.txt"; : > "$RESULTS"
FINDINGS="$TMP/findings.txt"; : > "$FINDINGS"
_cleanup() {
    local status=$?
    docker rm -f "$LISTENER" >/dev/null 2>&1 || true
    if [ -n "$COMPOSE" ]; then
        echo "$P: tearing down the stack ($TMP)"
        (cd "$TMP" && $COMPOSE down -v --remove-orphans) >/dev/null 2>&1 || true
        docker run --rm -v "$TMP:/t" busybox:1.36 sh -c 'rm -rf /t/.local /t/.data' >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

echo "$P: exporting the working tree -> $TMP"
bash "$ROOT/scripts/lib/export-simulate.sh" "$TMP" >/dev/null
COMPOSE="docker compose -f docker-compose.yml"
HTTP_PORT="${EXPOSURE_SWEEP_HTTP_PORT:-18200}"
HTTPS_PORT="${EXPOSURE_SWEEP_HTTPS_PORT:-18643}"
BASE="http://localhost:${HTTP_PORT}"
DOCKER_SOCK_GID=999
if [ -S /var/run/docker.sock ]; then
    DOCKER_SOCK_GID="$(stat -L -c %g /var/run/docker.sock 2>/dev/null || stat -L -f %g /var/run/docker.sock 2>/dev/null || echo 999)"
fi
if [ -z "${PNPM_VERSION:-}" ]; then
    PNPM_VERSION="$(sed -n 's/^  PNPM_VERSION: "\(.*\)"$/\1/p' "$ROOT/Taskfile.yml" | head -1)"
fi
{
    echo "HTTP_PORT=$HTTP_PORT"
    echo "HTTPS_PORT=$HTTPS_PORT"
    echo "POSTGRES_DATA_DIR=$TMP/.local/postgres"
    # Run-scoped, for the same reason check-open-boot-selfhost.sh does it: `sandbox-work` carries an
    # explicit `name:` in docker-compose.yml, so COMPOSE_PROJECT_NAME does not isolate it.
    echo "SANDBOX_WORK_VOLUME=sandbox-work-$COMPOSE_PROJECT_NAME"
    echo "DOCKER_SOCK_GID=$DOCKER_SOCK_GID"
    echo "PNPM_VERSION=$PNPM_VERSION"
} > "$TMP/.env"
chmod 600 "$TMP/.env"
_empty_cred_assignments=()
for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do _empty_cred_assignments+=("${_cred_var}="); done
echo "$P: booting docker-compose.yml, production profile, no credential supplied"
(cd "$TMP" && env "${_empty_cred_assignments[@]}" $COMPOSE up -d --build) >/dev/null 2>&1
open_boot_wait_for "$P" "GET /          (frontend via caddy :$HTTP_PORT)" "$BASE/"          200 90
open_boot_wait_for "$P" "GET /api/v1/me (backend via caddy)"              "$BASE/api/v1/me" 401 90
NET="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(cd "$TMP" && $COMPOSE ps -q backend)")"

# ---- helpers ------------------------------------------------------------------------------------
BODY="$TMP/body"
# _req <method> <path> [json-body] [cookie-jar] [bearer]; prints the status code, body in $BODY.
_req() {
    local method="$1" path="$2" body="${3:-}" jar="${4:-}" bearer="${5:-}"
    local -a args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path" -H 'X-Requested-With: XMLHttpRequest' --max-time 20)
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    [ -n "$jar" ] && args+=(-b "$jar" -c "$jar")
    [ -n "$bearer" ] && args+=(-H "Authorization: Bearer $bearer")
    curl "${args[@]}" 2>/dev/null || echo 000
}
_record() { printf '%s %s -> %s  %s\n' "$1" "$2" "$3" "${4:-}" >> "$RESULTS"; }
# Caddy serves the SPA shell for any path it does not proxy, so a 200 whose body is index.html is
# the frontend's fallback, not a backend answer: the path was never reached. Exact, by hash of the
# shell fetched once from GET /, not by a heuristic a backend page could satisfy.
SPA_HASH="$(curl -s "$BASE/" | shasum -a 256 | awk '{print $1}')"
_is_spa_shell() { [ "$(shasum -a 256 < "$BODY" | awk '{print $1}')" = "$SPA_HASH" ]; }
# The backend directly, on the stack network, bypassing Caddy: what a self-hoster who publishes
# port 8080 (or a neighbour on the same Docker network) would reach.
# $1 = method, $2 = path, $3 = content type (default JSON), $4 = body.
_direct() {
    local method="$1" path="$2" ctype="${3:-application/json}" body="${4:-}"
    local -a args=(-s -o /dev/null -w '%{http_code}' --max-time 15 -X "$method" "http://backend:8080$path" -H "Content-Type: $ctype")
    case "$method" in POST|PUT|PATCH) args+=(--data-binary "$body") ;; esac
    docker run --rm --network "$NET" curlimages/curl:8.12.1 "${args[@]}" 2>/dev/null || echo 000
}
_finding() { echo "$P: FINDING $*" >&2; echo "$*" >> "$FINDINGS"; }
# A 5xx on customer input is not an exposure by itself, but an unexplained one hides whatever
# threw; the backend's own exception lines for the last few seconds go into the log so the row
# can be dispositioned without a re-run.
_explain_5xx() {
    case "$1" in 5[0-9][0-9])
        echo "$P:   5xx on $2 $3; backend exception lines:" >&2
        (cd "$TMP" && $COMPOSE logs --no-color --since 15s backend 2>/dev/null) | grep -F "$3" | grep -E 'ERROR|Exception|Caused by' | tail -3 | cut -c1-300 | sed "s/^/$P:     /" >&2 ;;
    esac
}
_is_2xx() { case "$1" in 2[0-9][0-9]) return 0 ;; *) return 1 ;; esac; }
_psql() { (cd "$TMP" && $COMPOSE exec -T postgres psql -U "${POSTGRES_USER:-tessary}" -d "${POSTGRES_DB:-tessary}" -tAc "$1") 2>/dev/null | tr -d '[:space:]'; }

# Path variables get plausible, well-formed values so a probe reaches the handler's auth check
# rather than a 400 from a malformed segment.
_fill() {
    local p="$1"
    p="${p//\{orgSlug\}/$ORG_A}"; p="${p//\{projectSlug\}/$PROJECT_A}"
    p="${p//\{provider\}/OPENAI}"; p="${p//\{lane\}/triage}"; p="${p//\{nodeKind\}/case}"
    p="${p//\{key\}/sweep}"; p="${p//\{userCode\}/ABCD-EFGH}"; p="${p//\{commitSha\}/0123456789abcdef0123456789abcdef01234567}"
    p="$(printf '%s' "$p" | sed -E 's/\{[a-zA-Z]+\}/01ARZ3NDEKTSV4RRFFQ69G5FAV/g')"
    printf '%s' "$p"
}
ORG_A="sweep-org"; PROJECT_A="sweep-project"
# Required query parameters per operation (the spec's synthetic `ctx` excluded), so a probe reaches
# the handler's own gate instead of failing parameter binding in front of it.
QP="$TMP/qp.txt"
jq -r '.paths | to_entries[] | .key as $p | .value | to_entries[] | select(.value.parameters != null) | .key as $m | [.value.parameters[] | select(.in=="query" and .required==true and .name!="ctx") | .name] | select(length>0) | "\($m|ascii_upcase) \($p) \(join(","))"' "$SPEC" > "$QP"
_query_for() {
    local names; names="$(awk -v m="$1" -v p="$2" '$1==m && $2==p {print $3}' "$QP")"
    # Empty output on a false test is fine; a non-zero status inside `$(...)` in an assignment is
    # not, under errexit.
    [ -n "$names" ] && printf '?%s' "$(printf '%s' "$names" | sed 's/,/=sweep\&/g; s/$/=sweep/')"
    return 0
}

# ---- the fresh-database facts, then the two tenant accounts (before the probe storm trips the
# signup rate limiter) --------------------------------------------------------------------------
echo "$P: fresh database, then two tenant accounts"
sessions="$(_psql 'SELECT count(*) FROM session')"; members="$(_psql 'SELECT count(*) FROM org_membership')"
_record DB "session rows before first signup" "${sessions:-?}" ""
_record DB "org_membership rows before first signup" "${members:-?}" ""
[ "${sessions:-1}" = 0 ] || _finding "the fresh database already holds ${sessions:-?} session row(s): a shipped login"
[ "${members:-1}" = 0 ] || _finding "the fresh database already holds ${members:-?} membership row(s): a shipped account"
# Guessed default logins first: the credential rate limiter allows a handful of attempts per window,
# and arm 1's unauthenticated /auth posts would otherwise spend them and turn a 401 into a 429.
code="$(_req POST /auth/login '{"email":"admin@example.com","password":"password"}')"
_record POST "/auth/login (admin@example.com/password)" "$code" "default-credential attempt"
_is_2xx "$code" && _finding "a default credential logged in (admin@example.com / password)"
code="$(_req POST /auth/login '{"email":"admin@tessary.ai","password":"changeme"}')"
_record POST "/auth/login (admin@tessary.ai/changeme)" "$code" "default-credential attempt"
_is_2xx "$code" && _finding "a default credential logged in (admin@tessary.ai / changeme)"

_signup() {
    local jar="$1" email="$2" body code
    body="$(jq -nc --arg e "$email" --arg p "exposure-sweep-$(date +%s)-passphrase" '{email:$e,password:$p}')"
    local tries=8
    while :; do
        code="$(_req POST /auth/signup "$body" "$jar")"
        [ "$code" = 429 ] && [ "$tries" -gt 0 ] && { tries=$((tries - 1)); sleep 10; continue; }
        break
    done
    [ "$code" = 200 ] || { echo "$P: signup for $email -> $code" >&2; cat "$BODY" >&2; exit 1; }
    code="$(_req GET /auth/me "" "$jar")"; [ "$code" = 200 ] || { echo "$P: /auth/me -> $code" >&2; exit 1; }
    jq -r '.data.orgs[0].slug' "$BODY"
}
JAR_A="$TMP/a.cookies"; JAR_B="$TMP/b.cookies"
ORG_A="$(_signup "$JAR_A" "sweep-a+$$@example.invalid")"; ORG_B="$(_signup "$JAR_B" "sweep-b+$$@example.invalid")"
_req GET "/api/orgs/$ORG_A/projects" "" "$JAR_A" >/dev/null; PROJECT_A="$(jq -r '.data[0].slug' "$BODY")"
_req GET "/api/orgs/$ORG_B/projects" "" "$JAR_B" >/dev/null; PROJECT_B="$(jq -r '.data[0].slug' "$BODY")"
_req POST "/api/orgs/$ORG_B/projects/$PROJECT_B/mcp-tokens" '{"name":"sweep"}' "$JAR_B" >/dev/null; TOKEN_B="$(jq -r '.data.plaintext // empty' "$BODY")"
echo "$P: two tenants: A=$ORG_A/$PROJECT_A, B=$ORG_B/$PROJECT_B"

# ---- arm 1: unauthenticated probe ------------------------------------------------------------
# Unauthenticated BY DESIGN, with why; anything else answering 2xx to a stranger is a finding.
_by_design() {
    case "$1 $2" in
        "GET /auth/mode") return 0 ;;          # tells the login page which flow to render
        "POST /auth/login") return 0 ;;        # the login itself; a wrong password is a 401 elsewhere
        "POST /auth/signup") return 0 ;;       # open registration, by design: docs/self-hosting/setup.mdx's "Secure the instance" states the posture (#1198)
        "GET /auth/callback"|"GET /auth/login") return 0 ;;   # the WorkOS redirect dance
        "POST /auth/logout"|"GET /auth/logout") return 0 ;;
        "POST /auth/link/start"|"POST /auth/link/poll"|"GET /auth/link/poll") return 0 ;;  # device link, plugin-facing
        "GET /git/github/callback"|"GET /git/github/manifest/callback") return 0 ;;        # GitHub redirects, state-checked inside
        "GET /actuator/health"|"GET /actuator/health/liveness"|"GET /actuator/health/readiness") return 0 ;;
        *) return 1 ;;
    esac
}
echo "$P: arm 1, unauthenticated probe over every spec operation"
n1=0
while read -r method path; do
    filled="$(_fill "$path")$(_query_for "$method" "$path")"; body=""
    case "$method" in POST|PUT|PATCH) body='{}' ;; esac
    code="$(_req "$method" "$filled" "$body")"
    # The credential routes sit behind a per-address limiter that the guessed logins and the two
    # signups have partly spent; a 429 is the limiter, not the route's answer, so wait and ask once
    # more, which keeps the recorded row about the route.
    if [ "$code" = 429 ]; then sleep 12; code="$(_req "$method" "$filled" "$body")"; fi
    n1=$((n1 + 1))
    if _is_2xx "$code" && _is_spa_shell; then
        # Caddy does not proxy this path, so the backend was never asked; ask it directly, since a
        # self-hoster who publishes :8080 or a neighbouring container reaches it that way.
        dcode="$(_direct "$method" "$filled" application/json "$body")"
        n1=$((n1 + 1))
        if _is_2xx "$dcode" && ! _by_design "$method" "$path"; then
            _record "$method" "$path" "$dcode" "not proxied by caddy; backend direct: UNAUTHENTICATED 2xx"
            _finding "unauthenticated $method $path answered $dcode on the backend directly (not proxied by caddy)"
        else
            _record "$method" "$path" "$dcode" "not proxied by caddy (SPA shell through :8000); backend direct"
        fi
    elif _is_2xx "$code" && ! _by_design "$method" "$path"; then
        _record "$method" "$path" "$code" "UNAUTHENTICATED 2xx"
        _finding "unauthenticated $method $path answered $code"
    else
        _record "$method" "$path" "$code" "$(_by_design "$method" "$path" && echo 'by design' || true)"
    fi
done < <(jq -r '.paths | to_entries[] | .key as $p | .value | keys[] | "\(. | ascii_upcase) \($p)"' "$SPEC" | sort -k2)
# Paths the spec does not list, probed twice: through Caddy as the internet sees them, and against
# the backend directly on the stack network as a published :8080 or a neighbouring container would.
for extra in "GET /actuator" "GET /actuator/env" "GET /actuator/heapdump" "GET /actuator/prometheus" "GET /actuator/beans" "GET /actuator/health" "GET /v3/api-docs" "GET /swagger-ui/index.html" "POST /mcp" "POST /v1/traces" "GET /healthz"; do
    method="${extra%% *}"; path="${extra#* }"
    ctype=application/json; body='{}'
    [ "$path" = /v1/traces ] && { ctype=application/x-protobuf; body=''; }   # OTLP consumes protobuf; JSON would only prove content negotiation
    if [ "$method" = POST ]; then
        code="$(curl -sS -o "$BODY" -w '%{http_code}' -X POST "$BASE$path" -H "Content-Type: $ctype" --data-binary "$body" --max-time 20 2>/dev/null || echo 000)"
    else
        code="$(_req "$method" "$path")"
    fi
    n1=$((n1 + 1))
    if _is_2xx "$code" && _is_spa_shell; then
        _record "$method" "$path" "$code" "via caddy: SPA shell, backend not reached"
    elif _is_2xx "$code" && ! _by_design "$method" "$path"; then
        _record "$method" "$path" "$code" "via caddy: UNAUTHENTICATED 2xx"
        _finding "unauthenticated $method $path answered $code through caddy"
    else
        _record "$method" "$path" "$code" "via caddy$(_by_design "$method" "$path" && echo ', by design' || true)"
    fi
    dcode="$(_direct "$method" "$path" "$ctype" "$body")"
    n1=$((n1 + 1))
    if _is_2xx "$dcode" && ! _by_design "$method" "$path"; then
        _record "$method" "$path" "$dcode" "backend direct: UNAUTHENTICATED 2xx"
        _finding "unauthenticated $method $path answered $dcode on the backend directly"
    else
        _record "$method" "$path" "$dcode" "backend direct$(_by_design "$method" "$path" && echo ', by design' || true)"
    fi
done
echo "$P: arm 1 done, $n1 probes"

# ---- arm 2: default credential ---------------------------------------------------------------
echo "$P: arm 2, default credential"
open_boot_check_denied_credentials "$P" "$TMP" "$COMPOSE" postgres backend frontend sandbox-runner || _finding "a denied cloud credential is present in the rendered compose or a container env"

# The database credential ships as a compose default too; what matters is whether the port is
# published. A row either way, so the baseline says what a self-hoster gets.
pg_ports="$(cd "$TMP" && $COMPOSE config 2>/dev/null | awk '/^  postgres:/{p=1} p&&/^  [a-z-]+:$/&&!/postgres:/{p=0} p&&/published:/{print $2}' | tr '\n' ',' | sed 's/,$//')"
_record DB "postgres host-published ports (default password is the compose placeholder)" "${pg_ports:-none}" ""
[ -z "$pg_ports" ] || _finding "postgres is published to the host on ${pg_ports} with the compose default password"

# ---- arm 3: served bundle ----------------------------------------------------------------------
echo "$P: arm 3, the served frontend bundle"
fcid="$(cd "$TMP" && $COMPOSE ps -q frontend)"
mkdir -p "$TMP/srv" && docker cp "$fcid:/srv/." "$TMP/srv" >/dev/null
maps="$(find "$TMP/srv" -name '*.map' | wc -l | tr -d ' ')"
_record BUNDLE "source-map files" "$maps" ""
[ "$maps" = 0 ] || _finding "$maps source map(s) are served from /srv"
if grep -rlE 'sourceMappingURL=' "$TMP/srv" --include='*.js' --include='*.css' >/dev/null 2>&1; then
    _finding "the bundle carries sourceMappingURL comments"; _record BUNDLE "sourceMappingURL" present ""
else _record BUNDLE "sourceMappingURL" absent ""; fi
secret_re='AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|sk-[A-Za-z0-9]{32,}|sk-proj-[A-Za-z0-9_-]{40,}|whsec_[A-Za-z0-9]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|ghp_[A-Za-z0-9]{36}|AIza[0-9A-Za-z_-]{35}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}|/Users/[a-z]|/home/[a-z]'
hits="$(grep -rEo "$secret_re" "$TMP/srv" --include='*.js' --include='*.html' --include='*.css' --include='*.json' 2>/dev/null | sort -u | head -20 || true)"
_record BUNDLE "credential-shaped or path-revealing strings" "$(printf '%s' "$hits" | grep -c . || true)" ""
[ -z "$hits" ] || _finding "the bundle carries credential-shaped or path-revealing strings: $(printf '%s' "$hits" | tr '\n' ' ' | cut -c1-300)"
if [ -f "$EXPOSURE_FORBIDDEN" ]; then
    fhits=0
    while IFS='|' read -r pattern kind _; do
        [ -n "$pattern" ] && [ "${pattern#\#}" = "$pattern" ] || continue
        if [ "$kind" = literal ]; then grep -rqF -- "$pattern" "$TMP/srv" 2>/dev/null && fhits=$((fhits + 1)) && _finding "the bundle carries forbidden string $pattern"
        else grep -rqE -- "$pattern" "$TMP/srv" 2>/dev/null && fhits=$((fhits + 1)) && _finding "the bundle matches forbidden pattern $pattern"; fi
    done < "$EXPOSURE_FORBIDDEN"
    _record BUNDLE "forbidden-strings hits" "$fhits" ""
else
    echo "$P: arm 3 did NOT scan for forbidden strings — '$EXPOSURE_FORBIDDEN' is not in this" >&2
    echo "     checkout. Set EXPOSURE_FORBIDDEN to the list to run that half." >&2
    _record BUNDLE "forbidden-strings hits" "not-scanned" "no list at $EXPOSURE_FORBIDDEN"
fi

# ---- arm 4: SSRF and traversal --------------------------------------------------------------
echo "$P: arm 4, SSRF and traversal"
# The provider path variable is the ModelProvider enum constant, upper case on the wire.
PROVIDER=OPENAI
docker run -d --rm --name "$LISTENER" --network "$NET" --network-alias sweep-listener busybox:1.36 \
    sh -c ': > /hits; while true; do nc -l -p 9999 > /req 2>/dev/null; [ -s /req ] && cat /req >> /hits && echo "--- hit" >> /hits; done' >/dev/null
for target in "http://sweep-listener:9999/v1" "http://backend:8080/actuator/env" "http://127.0.0.1:8080/actuator/env" "http://169.254.169.254/latest/meta-data/"; do
    body="$(jq -nc --arg u "$target" '{api_key:"sk-exposure-sweep-not-a-key",base_url_override:$u}')"
    code="$(_req PUT "/api/orgs/$ORG_A/providers/$PROVIDER" "$body" "$JAR_A")"
    _record PUT "/api/orgs/{orgSlug}/providers/$PROVIDER base_url_override=$target" "$code" "ssrf probe, error=$(jq -r '.meta.error.code // "-"' "$BODY" 2>/dev/null)"
    _explain_5xx "$code" PUT "/providers/$PROVIDER"
    code="$(_req GET "/api/orgs/$ORG_A/providers/catalog" "" "$JAR_A")"
    _record GET "/api/orgs/{orgSlug}/providers/catalog after base_url_override=$target" "$code" "ssrf probe"
    body="$(jq -nc --arg u "$target" '{provider:"langfuse",name:"sweep",baseUrl:$u,apiKey:"sweep"}')"
    code="$(_req POST "/api/orgs/$ORG_A/projects/$PROJECT_A/sources" "$body" "$JAR_A")"
    _record POST "/api/orgs/{orgSlug}/projects/{projectSlug}/sources baseUrl=$target" "$code" "ssrf probe, error=$(jq -r '.meta.error.code // "-"' "$BODY" 2>/dev/null)"
done
sleep 3
# A hit is a real request: bytes arrived on the socket. What arrived is kept for the record.
lhits="$(docker exec "$LISTENER" sh -c 'grep -c "^--- hit" /hits 2>/dev/null; true' | tail -1)"; lhits="${lhits:-0}"
[ "$lhits" = 0 ] || docker exec "$LISTENER" sh -c 'head -c 600 /hits' | sed "s/^/$P:   listener saw: /" >&2
_record LISTENER "connections from the stack to a customer-supplied internal URL" "$lhits" ""
[ "$lhits" = 0 ] || _finding "the backend connected $lhits time(s) to a customer-supplied URL on the internal network (SSRF)"
for trav in "..%2F..%2F..%2Fetc%2Fpasswd" "....//....//etc/passwd" "%2e%2e%2f%2e%2e%2fetc%2fpasswd"; do
    for path in "/api/orgs/$ORG_A/projects/$PROJECT_A/media/$trav" "/api/orgs/$ORG_A/projects/$PROJECT_A/traces/$trav" "/assets/$trav"; do
        code="$(_req GET "$path" "" "$JAR_A")"
        if _is_2xx "$code" && _is_spa_shell; then _record GET "$path" "$code" "traversal probe: SPA shell, nothing served"
        else _record GET "$path" "$code" "traversal probe"; fi
        if _is_2xx "$code" && grep -q 'root:' "$BODY" 2>/dev/null; then _finding "traversal $path returned file content"; fi
    done
done

# ---- arm 5: cross-tenant -----------------------------------------------------------------------
echo "$P: arm 5, cross-tenant substitution: B reads A"
n5=0
while read -r method path; do
    filled="$(_fill "$path")$(_query_for "$method" "$path")"
    code="$(_req "$method" "$filled" "" "$JAR_B")"
    n5=$((n5 + 1))
    if _is_2xx "$code"; then
        _record "$method" "$path" "$code" "CROSS-TENANT 2xx as B on A"
        _finding "tenant B read tenant A's $method $path ($code)"
    else
        _record "$method" "$path" "$code" "as B on A"
    fi
done < <(jq -r '.paths | to_entries[] | select(.key | test("^/api/orgs/\\{orgSlug\\}")) | .key as $p | .value | keys[] | select(. == "get") | "GET \($p)"' "$SPEC" | sort -k2)
# A query bearer is bound to the project it was issued for and the request carries no org or project
# field, so there is nothing to substitute on /v1/query/*; the control below shows the bearer works,
# and the probe after it aims that bearer at tenant A's org routes, where the filter must reject it.
code="$(_req POST /v1/query/count '{"dataset":"spans"}' "" "$TOKEN_B")"
_record POST "/v1/query/count (B's bearer, own project)" "$code" "bearer control: project-bound by construction"
n5=$((n5 + 1))
for bp in "/api/orgs/$ORG_A/projects" "/api/orgs/$ORG_A/projects/$PROJECT_A/traces" "/api/orgs/$ORG_A/projects/$PROJECT_A/api-keys"; do
    code="$(_req GET "$bp" "" "" "$TOKEN_B")"
    _record GET "$bp (B's bearer on A's org)" "$code" "cross-tenant bearer"
    n5=$((n5 + 1))
    _is_2xx "$code" && _finding "tenant B's bearer token read tenant A's $bp ($code)"
done
echo "$P: arm 5 done, $n5 probes"

# ---- arm 2, second half: the shipped keys refuse a real host --------------------------------
# docker-compose.yml ships its two sealing keys as public placeholders on purpose (#1230), so the
# credential that "still authenticates after first boot" is the session cookie anyone could forge
# against them. What makes that defensible is PlaceholderSecretGuard: with SITE_DOMAIN set to a real
# hostname and either key still at its default, the backend must REFUSE to start, naming the key.
# Proven here by re-creating the backend under a domain and watching it refuse.
echo "$P: arm 2b, the shipped placeholder keys must refuse to boot under a real domain"
(cd "$TMP" && env "${_empty_cred_assignments[@]}" SITE_DOMAIN=sweep.example.invalid $COMPOSE up -d --no-build backend) >/dev/null 2>&1 || true
refused=0
for _ in $(seq 1 30); do
    if (cd "$TMP" && $COMPOSE logs --no-color backend 2>/dev/null) | grep -qE 'Refusing to start|still hold the placeholder value'; then refused=1; break; fi
    sleep 3
done
# `restart: unless-stopped` relaunches the refused container every few seconds, so "is it running"
# flickers; what cannot flicker is whether anything answers behind Caddy. A backend that refused
# never serves, so /api/v1/me must not come back 401 from the auth filter.
serving=0
for _ in 1 2 3 4 5 6; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/me" 2>/dev/null || echo 000)" = 401 ] && serving=1 && break
    sleep 5
done
_record BOOT "backend under SITE_DOMAIN=sweep.example.invalid with the shipped keys" "$([ "$refused" = 1 ] && [ "$serving" = 0 ] && echo 'refused, nothing serving' || echo "not refused (guard log seen: $refused, serving: $serving)")" "placeholder-key guard"
if [ "$refused" != 1 ] || [ "$serving" != 0 ]; then
    _finding "the backend did not refuse to boot under a real domain with the shipped placeholder keys (guard log seen: $refused, serving: $serving)"
    echo "$P:   backend service state and log tail:" >&2
    (cd "$TMP" && $COMPOSE ps backend 2>/dev/null) | sed "s/^/$P:     /" >&2
    (cd "$TMP" && $COMPOSE logs --no-color --tail=40 backend 2>/dev/null) | grep -E 'Refus|placeholder|ERROR|Exception|Started|SITE_DOMAIN' | tail -8 | cut -c1-300 | sed "s/^/$P:     /" >&2
    (cd "$TMP" && env "${_empty_cred_assignments[@]}" SITE_DOMAIN=sweep.example.invalid $COMPOSE config 2>/dev/null) | grep -E 'SITE_DOMAIN' | head -3 | sed "s/^/$P:     rendered: /" >&2
fi

# ---- verdict and baseline --------------------------------------------------------------------
# The run's own slugs become placeholders in the recorded set. Project slugs are matched only as a
# path segment after /projects/, since the default project is literally named "default".
_placeholders() {
    sed -E "s#/orgs/$ORG_A(/|\$| )#/orgs/{orgA}\\1#g; s#/orgs/$ORG_B(/|\$| )#/orgs/{orgB}\\1#g; s#/projects/$PROJECT_A(/|\$| )#/projects/{projectA}\\1#g; s#/projects/$PROJECT_B(/|\$| )#/projects/{projectB}\\1#g; s#=$ORG_A/$PROJECT_A#={orgA}/{projectA}#g; s#=$ORG_B/$PROJECT_B#={orgB}/{projectB}#g" "$1"
}
sort -o "$RESULTS" "$RESULTS"
if [ "$RECORD" = 1 ]; then
    { echo "# Exposure-sweep result set (epic 6 clause 8, #1153). Regenerated by check-exposure-sweep.sh --record;"
      echo "# a later run diffs itself against this file and names every probe whose status changed (#1198)."
      echo "# Row shape: METHOD PATH -> CODE  note. Slugs and ids are the sweep's own placeholders."
      _placeholders "$RESULTS"; } > "$BASELINE"
    echo "$P: baseline recorded to scripts/lib/exposure-sweep-baseline.txt ($(grep -c -v '^#' "$BASELINE") rows)"
elif [ -f "$BASELINE" ]; then
    _placeholders "$RESULTS" > "$TMP/now.txt"
    if diff <(grep -v '^#' "$BASELINE") "$TMP/now.txt" > "$TMP/diff.txt"; then
        echo "$P: result set identical to the recorded baseline"
    else
        echo "$P: result set DIFFERS from the recorded baseline (re-record with --record once dispositioned):"; cat "$TMP/diff.txt"
    fi
fi
echo "$P: --- result set ($(wc -l < "$RESULTS" | tr -d ' ') probes)"; cat "$RESULTS"
if [ -s "$FINDINGS" ]; then
    echo "$P: FAIL, $(wc -l < "$FINDINGS" | tr -d ' ') finding(s):" >&2; cat "$FINDINGS" >&2; exit 1
fi
echo "$P: ok, no finding: no unauthenticated dangerous surface, no working default credential, no secret or map in the bundle, no SSRF hit, no cross-tenant read"
