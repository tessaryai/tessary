#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The self-host quickstart rehearsal (epic 7 clause 2, #1191; clause 5's ladder walk, #1194; and
# clause 3's clock). Under R1 this script is the outsider: it executes docs/self-hosting/setup.mdx's
# own command blocks, verbatim and in order, from the inputs a reader has and nothing else, and
# asserts every observable the page states as the page words it.
#
# THE INPUT SET, stated and enforced: the published repository (a clean-room export of this tree,
# which is where docker-compose.yml, .env.example and scripts/emit-span.js come from; the page's own
# prerequisite tells the reader to clone it), the published docs (setup.mdx in that export), and the
# published images. No Taskfile, no variable the page does not name, no port moved off the compose
# defaults. The environment is scrubbed of every cloud credential and every variable the compose
# file interpolates before the first block runs.
#
# THREE FAILURE CLASSES, enforced by construction:
#   1. every bash fence on the page, counted page-wide and not only inside <Step>, is executed;
#      there is no skip list, because nothing on the page is skipped;
#   2. the harness sets no environment variable the page does not tell the reader to set: the
#      compose's interpolated names and every COMPOSE_* are unset up front, no `.env` exists before
#      the first documented command (except the page's own DOCKER_SOCK_GID prerequisite, when the
#      host needs it), and after the page's .env block the file is asserted to be `.env.example`
#      plus exactly the lines that block adds;
#   3. every action that is not a verbatim block goes through `_glue <name>`, which refuses a name
#      not in GLUE below, where each is paired with the sentence on the page it stands in for.
#
# CLAUSE 3'S CLOCK: wall clock is captured immediately before the first documented command and
# again the moment the ladder first reports `fitting`; total and post-pull seconds are printed, and
# the total must be under 600 s on the pulled path.
#
#   bash scripts/check-selfhost-quickstart.sh          the documented path (images PULLED)
#   bash scripts/check-selfhost-quickstart.sh --build  ONE enumerated deviation: `docker compose
#       build` stands in for the page's `docker compose pull`, for a tree whose images are not yet
#       published. Printed loudly, recorded as "built, not pulled", and the 600 s threshold does not
#       apply (the page says it applies to the pulled path only).
#
# Three more deviations exist ONLY for harnesses that layer on this one (clause 9's zero-egress
# window is the first); each is printed loudly when used and none is a reader's option:
#   --overlay <file>      copied into the export as docker-compose.override.yml, which the page's own
#                         verbatim `docker compose` commands then pick up (how clause 9 pins the
#                         stack to an internal network with a DNS sink)
#   --env-line KEY=value  written into .env before the first documented command, the way a reader who
#                         wants that setting from the first boot does it; the key must be one
#                         docs/self-hosting/configuration.mdx documents, and the line is glue "the
#                         reader sets it per the configuration page"
#   --after <script>      run in the export, with TMP, ORG, PROJ, JAR and BASE exported, after every
#                         documented step and before teardown; clause 9's vantage assertions live there
#
# Needs Docker, the network (to pull), and ports 80 and 443 free on the host, because those are
# the compose file's defaults and the page names no other. NEVER RUN AGENT-SIDE. Run by a person
# (`task check:selfhost:quickstart`) or the dispatch-only open-edition-boot.yml; EXCLUDED from
# `task check`. No cron: #1184 owns re-arming.
set -euo pipefail
P=check-selfhost-quickstart
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
BUILD=0; OVERLAY=""; AFTER=""; ENV_LINES=""
while [ $# -gt 0 ]; do
    case "$1" in
        --build) BUILD=1 ;;
        --overlay) [ -n "${2:-}" ] || { echo "$P: --overlay needs a file" >&2; exit 2; }; OVERLAY="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift ;;
        --after) [ -n "${2:-}" ] || { echo "$P: --after needs a script" >&2; exit 2; }; AFTER="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift ;;
        --env-line) [ -n "${2:-}" ] || { echo "$P: --env-line needs KEY=value" >&2; exit 2; }; ENV_LINES="$ENV_LINES$2
"; shift ;;
        *) echo "$P: unknown argument '$1' (accepts --build, --overlay <file>, --env-line KEY=value, --after <script>)" >&2; exit 2 ;;
    esac
    shift
done
while IFS= read -r _l; do
    [ -n "$_l" ] || continue
    _k="${_l%%=*}"
    grep -q "\`$_k\`" docs/self-hosting/configuration.mdx || { echo "$P: --env-line $_k is not a variable docs/self-hosting/configuration.mdx documents; a reader has no such line" >&2; exit 2; }
done <<EOF
$ENV_LINES
EOF
for tool in docker jq curl python3; do command -v "$tool" >/dev/null || { echo "$P: $tool is required" >&2; exit 2; }; done

# shellcheck source=lib/cloud-credential-denylist.sh
. "$ROOT/scripts/lib/cloud-credential-denylist.sh"
# shellcheck source=lib/open-boot-lib.sh
. "$ROOT/scripts/lib/open-boot-lib.sh"

# Failure class 2: nothing the reader was not told to set. Every cloud credential, and every name
# docker-compose.yml interpolates, is unset before the first documented command.
for _v in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do unset "$_v" 2>/dev/null || true; done
for _v in $(grep -oE '\$\{[A-Z_0-9]+' docker-compose.yml | tr -d '${' | sort -u); do
    [ "$_v" = PWD ] || unset "$_v" 2>/dev/null || true
done
for _v in $(env | grep -oE '^COMPOSE_[A-Z_]+' || true); do unset "$_v" 2>/dev/null || true; done
unset DOCKER_DEFAULT_PLATFORM 2>/dev/null || true

# Failure class 3: every non-verbatim action, paired with the sentence on the page it stands in
# for. `_glue` refuses any name not listed here.
GLUE="
signup|Create the first account with an email and password (the page describes the sign-up screen; the harness posts the same form to /auth/signup)
whoami|Tessary creates your organization and a Default project during sign-up (the harness reads their slugs from /auth/me and the project list, which the browser does on landing)
mint|a Bearer token already minted (the connect gate mints a WRITE-scoped API key on mount; the harness calls the same endpoint with the same name and scope)
token-substitution|Copy the Header field on the gate, keep the part after Bearer (the page's emit block carries <token>; the harness substitutes the minted value)
ps|docker compose ps shows ... healthy (the Check; read through docker compose ps --format json)
open|Open http://localhost ... The sign-up screen loads (the Check; one GET of the page's own address)
logs|docker compose logs backend no longer carries the placeholder warning (the Check; one read of the backend log)
env-line|a variable the configuration page documents, set in .env before the first boot the way that page says (only when --env-line is passed; the key is checked against the page)
overlay|a docker-compose.override.yml the layering harness plants, which the page's own verbatim commands pick up (only when --overlay is passed)
after|the layering harness's own assertions, run in the export after every documented step (only when --after is passed)
ladder|the gate opens on its own ... the ladder below reads fitting (the Checks; polled from /onboarding, the endpoint the Setup page renders)
gate|a live count of spans received versus spans tagged (the gate's own numbers, read from /substrate/status, the endpoint the gate polls)
sock-gid|The group id of the Docker socket as containers see it ... if it prints anything but 999, write DOCKER_SOCK_GID=<that number> into a .env file (the prerequisite, run with the page's own command; asserted afterwards by opening the socket from inside the sandbox runner)
control|A second account whose gate mints a token and emits nothing must stay at listening (#1194's control arm; not on the page, run every time so the fitting assertion cannot be vacuous)
"
# `_glue <name> [command...]`: refuses a name outside GLUE, prints what it stands in for, and when
# a command follows, runs it, so an action outside the table cannot be executed through this path.
_glue() {
    local name="$1"; shift
    printf '%s\n' "$GLUE" | grep -q "^$name|" || { echo "$P: FAIL, the harness took an action ('$name') that is neither a documented block nor an enumerated glue step" >&2; exit 1; }
    # To stderr, so a caller capturing the wrapped command's stdout still shows the line.
    echo "$P: glue [$name] stands in for: $(printf '%s\n' "$GLUE" | grep "^$name|" | cut -d'|' -f2)" >&2
    [ $# -eq 0 ] || "$@"
}

TMP="$(mktemp -d)"
DOC="$TMP/docs/self-hosting/setup.mdx"
STEPS="$TMP/.steps.tsv"
BODY="$TMP/.body"
_cleanup() {
    local status=$?
    if [ -f "$TMP/docker-compose.yml" ]; then
        echo "$P: tearing down the stack…"
        (cd "$TMP" && docker compose down -v --remove-orphans) >/dev/null 2>&1 || true
        docker run --rm -v "$TMP:/t" busybox:1.36 sh -c 'rm -rf /t/.local' >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

# The page's own prerequisite: a free `tessary` project name. The compose file pins it, the
# page says so, and this harness does not rename it.
if docker compose ls -a --format json 2>/dev/null | jq -e '.[] | select(.Name == "tessary")' >/dev/null 2>&1; then
    echo "$P: a Compose project named tessary exists on this host (running or stopped); the page's prerequisite is not met and this run would adopt and then tear it down" >&2; exit 1
fi

echo "$P: exporting the working tree (the published repository) -> $TMP"
bash "$ROOT/scripts/lib/export-simulate.sh" "$TMP"
[ -f "$DOC" ] || { echo "$P: the export carries no $DOC" >&2; exit 1; }
if [ -n "$OVERLAY" ]; then
    _glue overlay
    cp "$OVERLAY" "$TMP/docker-compose.override.yml"
    echo "$P: DEVIATION (--overlay): $OVERLAY is in the export as docker-compose.override.yml; every verbatim 'docker compose' below runs against it"
fi

# The step list is EXTRACTED from the page: every <Step title="..."> and the bash fences inside it,
# plus each <Check> body, in document order. Editing a command on the page changes what runs here.
python3 - "$DOC" "$STEPS" <<'PY'
import re, sys
doc, out = open(sys.argv[1]).read(), open(sys.argv[2], "w")
title = None
for m in re.finditer(r'<Step title="([^"]+)">(.*?)</Step>', doc, re.S):
    title, body = m.group(1), m.group(2)
    for fence in re.findall(r"```bash\n(.*?)```", body, re.S):
        cmd = "\n".join(l.strip() for l in fence.strip("\n").splitlines())
        out.write("block\t%s\t%s\n" % (title, cmd.replace("\n", "\x1f")))
    for check in re.findall(r"<Check>(.*?)</Check>", body, re.S):
        out.write("check\t%s\t%s\n" % (title, " ".join(check.split())))
PY
n_blocks="$(grep -c '^block' "$STEPS" || true)"
n_fences="$(grep -cE '^[[:space:]]*```bash' "$DOC" || true)"
[ "$n_blocks" -eq "$n_fences" ] || { echo "$P: the page carries $n_fences bash fences but only $n_blocks sit inside a <Step title=\"...\">; a fence the extraction cannot see is a command a reader runs and this harness does not" >&2; exit 1; }
[ "$n_blocks" -ge 4 ] || { echo "$P: extracted only $n_blocks command blocks from the page; the extraction is broken or the page is" >&2; exit 1; }
# Failure class 2: no .env a reader does not have, before the first documented command. The one the
# page's prerequisite tells a Linux reader to write is written the same way, and only when needed.
[ ! -f "$TMP/.env" ] || { echo "$P: the export carries a .env before the first documented command" >&2; exit 1; }
_glue sock-gid
SOCK_GID="$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock busybox:1.36 stat -c %g /var/run/docker.sock 2>/dev/null || true)"
[ -n "$SOCK_GID" ] || { echo "$P: FAIL, the page's prerequisite command printed nothing; the socket's group cannot be read on this host" >&2; exit 1; }
if [ "$SOCK_GID" != 999 ]; then
    printf 'DOCKER_SOCK_GID=%s\n' "$SOCK_GID" > "$TMP/.env"
    echo "$P: the page's command prints $SOCK_GID for the socket's group, so .env carries DOCKER_SOCK_GID=$SOCK_GID as the prerequisite says"
else
    SOCK_GID=""
    echo "$P: the page's command prints 999 for the socket's group, so the prerequisite writes nothing"
fi
if [ -n "$ENV_LINES" ]; then
    _glue env-line
    printf '%s' "$ENV_LINES" >> "$TMP/.env"
    echo "$P: .env carries, from before the first boot as the configuration page says: $(printf '%s' "$ENV_LINES" | tr '\n' ' ')"
fi
echo "$P: extracted $n_blocks command blocks and $(grep -c '^check' "$STEPS" || true) Checks from setup.mdx:"
awk -F'\t' '$1 == "block" {gsub(/\037/, " ; ", $3); print "    [" $2 "] " $3}' "$STEPS"

_run_block() {
    # Verbatim, in the export, in a subshell whose environment is the scrubbed one above.
    local title="$1" cmd="$2"
    echo "$P: === [$title]"
    printf '%s\n' "$cmd" | tr '\037' '\n' | sed "s/^/$P:   $ /"
    (cd "$TMP" && bash -eo pipefail -c "$(printf '%s' "$cmd" | tr '\037' '\n')")
}

BASE=http://localhost
_req() {
    local method="$1" path="$2" body="${3:-}" jar="${4:-}" bearer="${5:-}"
    local -a args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path" -H 'X-Requested-With: XMLHttpRequest' --max-time 20)
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    [ -n "$jar" ] && args+=(-b "$jar" -c "$jar")
    [ -n "$bearer" ] && args+=(-H "Authorization: Bearer $bearer")
    curl "${args[@]}" 2>/dev/null || echo 000
}
_stage() { _req GET "/api/orgs/$1/projects/$2/onboarding" "" "$3" >/dev/null; jq -r '.data.stage // empty' "$BODY"; }

executed=0
T0=$(date +%s); T_PULL=""; T_FITTING=""; PULL_MODE=pulled
echo "$P: clock started at $(date -u +%FT%TZ) (clause 3: before the first documented command)"

# Account state the checkpoints thread through.
JAR="$TMP/.jar"; ORG=""; PROJ=""; TOKEN=""; RUN_EMAIL="quickstart-$(date +%s)@example.com"

# The step list is read on its own descriptor: a `docker compose exec -T` inside the loop would
# otherwise drain the list through the loop's stdin and end the rehearsal after the first Check.
while IFS=$'\t' read -r -u 3 kind title payload; do
    case "$kind" in
    block)
        cmd="$payload"
        if [ "$title" = "Pull the images" ] && [ "$BUILD" = 1 ]; then
            echo "$P: === [$title] DEVIATION (--build): 'docker compose build' stands in for the page's 'docker compose pull'; the images are not published yet, so this run is built, not pulled, and no threshold applies"
            # Build what the tree builds, then pull the images it does not (postgres, busybox), so
            # everything is local before the window a layering harness opens.
            (cd "$TMP" && docker compose build && docker compose pull --ignore-buildable) >/dev/null 2>&1 || { echo "$P: FAIL, docker compose build or the base-image pull failed" >&2; exit 1; }
            # The stamp is one whole second after the pull, so `docker events --since` cannot
            # include the pull's own events from the same second.
            PULL_MODE=built; T_PULL=$(date +%s); executed=$((executed + 1)); sleep 1; date +%s > "$TMP/.window-start"; continue
        fi
        if [ "$title" = "Connect your traces" ]; then
            # The page says: create the account, land on the gate, copy the Header field, run the block.
            _glue signup
            code="$(_req POST /auth/signup "$(jq -nc --arg e "$RUN_EMAIL" '{email:$e,password:"quickstart-rehearsal-passphrase"}')" "$JAR")"
            [ "$code" = 200 ] || { echo "$P: FAIL, POST /auth/signup -> $code" >&2; exit 1; }
            _glue whoami
            _req GET /auth/me "" "$JAR" >/dev/null; ORG="$(jq -r '.data.orgs[0].slug // empty' "$BODY")"
            _req GET "/api/orgs/$ORG/projects" "" "$JAR" >/dev/null; PROJ="$(jq -r '.data[0].slug // empty' "$BODY")"
            [ -n "$ORG" ] && [ -n "$PROJ" ] || { echo "$P: FAIL, no org/project after signup" >&2; exit 1; }
            echo "$P: account created; org=$ORG project=$PROJ"
            _glue ladder
            st="$(_stage "$ORG" "$PROJ" "$JAR")"
            if [ "$st" = not_connected ]; then echo "$P: checkpoint 1 (before the mint): stage=$st (ok)"; else echo "$P: FAIL, checkpoint 1 expected not_connected before any key exists, got '$st' (a pre-existing key?)" >&2; exit 1; fi
            _glue mint
            code="$(_req POST "/api/orgs/$ORG/projects/$PROJ/api-keys" '{"name":"OTLP ingest","scope":"write"}' "$JAR")"
            [ "$code" = 201 ] || { echo "$P: FAIL, the gate's mint -> $code" >&2; exit 1; }
            TOKEN="$(jq -r '.data.plaintext // empty' "$BODY")"; [ -n "$TOKEN" ] || { echo "$P: FAIL, mint returned no plaintext" >&2; exit 1; }
            case "$(jq -r '.data.key.scope // empty' "$BODY")" in write) ;; *) echo "$P: FAIL, the minted key is not WRITE-scoped: $(jq -c '.data.key' "$BODY")" >&2; exit 1 ;; esac
            st="$(_stage "$ORG" "$PROJ" "$JAR")"
            if [ "$st" = listening ]; then echo "$P: checkpoint 2 (after the mint, before the emit): stage=$st (ok)"; else echo "$P: FAIL, checkpoint 2 expected listening after the mint, got '$st'" >&2; exit 1; fi
            _glue token-substitution
            cmd="${payload//<token>/$TOKEN}"
        fi
        _run_block "$title" "$cmd" || { echo "$P: FAIL, the documented block under [$title] exited non-zero" >&2; exit 1; }
        executed=$((executed + 1))
        [ "$title" = "Pull the images" ] && T_PULL=$(date +%s)
        if [ -n "$T_PULL" ] && [ ! -f "$TMP/.window-start" ]; then
            # The images are local from here on; a layering harness reads this as the moment after
            # which no registry pull may happen (clause 9's host-daemon vantage). One whole second
            # later than the pull, so the pull's own events cannot share the stamp's second.
            sleep 1; date +%s > "$TMP/.window-start"
        fi
        if [ "$title" = "Generate a value for each and put them in .env" ]; then
            # Failure class 2, asserted: .env is .env.example plus exactly what the page's block adds
            # (two TESSARY_ lines), plus the prerequisite line when the host needed it. Asserted BEFORE
            # any enumerated --env-line is appended, so the page's own block is what is measured.
            extra="$(diff "$TMP/.env.example" "$TMP/.env" | grep '^>' | sed 's/^> //' || true)"
            unexpected="$(printf '%s\n' "$extra" | grep -vE '^TESSARY_(AUTH_COOKIE_PASSWORD|SECRET_KEY)=' | grep -vE "^DOCKER_SOCK_GID=${SOCK_GID:-NONE}\$" | grep -vxF -f <(printf '%s' "$ENV_LINES"; echo '#none#') | grep . || true)"
            n_keys="$(printf '%s\n' "$extra" | grep -cE '^TESSARY_(AUTH_COOKIE_PASSWORD|SECRET_KEY)=' || true)"
            if [ -n "$unexpected" ] || [ "$n_keys" -ne 2 ]; then
                echo "$P: FAIL, after the page's .env block the file is not .env.example plus its two keys; extra lines: $(printf '%s' "$unexpected" | tr '\n' ' ') (keys added: $n_keys)" >&2; exit 1
            fi
            echo "$P: .env differs from .env.example by exactly the two keys the page's block appends${SOCK_GID:+, the DOCKER_SOCK_GID prerequisite line}${ENV_LINES:+ and the enumerated configuration-page line} (ok)"
        fi
        if [ "$title" = "Connect your traces" ]; then
            _glue ladder
            for _ in $(seq 1 30); do st="$(_stage "$ORG" "$PROJ" "$JAR")"; [ "$st" = fitting ] && break; sleep 1; done
            T_FITTING=$(date +%s)
            if [ "$st" = fitting ]; then echo "$P: checkpoint 3 (after the emit): stage=$st (ok); clock stopped at $(date -u +%FT%TZ)"; else echo "$P: FAIL, checkpoint 3 expected fitting after the emit, got '$st'" >&2; exit 1; fi
            first_at="$(jq -r '.data.first_trace_at // empty' "$BODY")"
            first_epoch="$(python3 -c 'import sys,datetime; print(int(datetime.datetime.fromisoformat(sys.argv[1].replace("Z","+00:00")).timestamp()))' "$first_at" 2>/dev/null || echo 0)"
            if [ "$first_epoch" -ge "$T0" ]; then echo "$P: first_trace_at=$first_at is after this run's start clock (ok; no leftover span)"; else echo "$P: FAIL, first_trace_at='$first_at' predates this run's clock ($T0): a leftover span" >&2; exit 1; fi
            _glue gate _req GET "/api/orgs/$ORG/projects/$PROJ/substrate/status" "" "$JAR" >/dev/null
            echo "$P: substrate: spans_received=$(jq -r '.data.spans_received' "$BODY") tagged_spans=$(jq -r '.data.tagged_spans' "$BODY") has_tagged_span=$(jq -r '.data.has_tagged_span' "$BODY")"
            [ "$(jq -r '.data.has_tagged_span' "$BODY")" = true ] || { echo "$P: FAIL, the gate's own signal (has_tagged_span) is not true after the documented emit" >&2; exit 1; }
        fi
        ;;
    check)
        echo "$P: --- Check under [$title]: \"$payload\""
        case "$title" in
        "Start the services")
            _glue ps
            open_boot_wait_healthy "$P" "$TMP" "docker compose" 320 postgres backend frontend sandbox-runner || exit 1
            open_boot_assert_healthy_set "$P" "$TMP" "docker compose" postgres backend frontend sandbox-runner || exit 1
            init_state="$(cd "$TMP" && docker compose ps -a --format json | jq -rs '.[] | select(.Service == "sandbox-runner-work-init") | "\(.State)/\(.ExitCode)"')"
            if [ "$init_state" = "exited/0" ]; then echo "$P: sandbox-runner-work-init is exited (0) (ok)"; else echo "$P: FAIL, sandbox-runner-work-init is '$init_state', the page says exited (0)" >&2; exit 1; fi
            _glue open
            code="$(_req GET / )"
            if grep -qi '<html' "$BODY" && [ "$code" = 200 ]; then echo "$P: GET $BASE/ -> 200, an HTML page: the sign-up screen's shell loads (ok)"; else echo "$P: FAIL, GET $BASE/ -> $code" >&2; exit 1; fi
            # The page says the backend warns on every boot that runs a placeholder; the first boot
            # does, so the warning must be present now, or the Restart Check below proves nothing.
            # The log is captured whole before it is searched: `grep -q` closing a pipe early makes
            # `docker compose logs` die of SIGPIPE, and under pipefail a match then reads as a miss.
            _glue logs
            backend_log="$(cd "$TMP" && docker compose logs --no-color backend 2>/dev/null || true)"
            if printf '%s\n' "$backend_log" | grep -q 'Running on the shipped placeholder value'; then
                echo "$P: the first boot's backend log carries the placeholder warning, as the page says it will (ok; the Restart Check has something to clear)"
            else
                echo "$P: FAIL, the first boot ran the shipped placeholders but the backend log carries no warning; the page's claim is false or the log was not read" >&2; exit 1
            fi
            # The prerequisite's promise: the sandbox runner can reach the Docker socket.
            _glue sock-gid
            if (cd "$TMP" && docker compose exec -T sandbox-runner sh -c 'test -w /var/run/docker.sock' </dev/null); then
                echo "$P: the sandbox runner can open /var/run/docker.sock (ok)"
            else
                echo "$P: FAIL, the sandbox runner cannot write /var/run/docker.sock; the page's DOCKER_SOCK_GID prerequisite did not hold on this host (socket group ${SOCK_GID:-unknown})" >&2; exit 1
            fi
            ;;
        "Restart")
            # The restart recreates the backend; wait for it to be serving again before reading the
            # log, or the grep reads a log that has not been written yet.
            _glue ps
            open_boot_wait_healthy "$P" "$TMP" "docker compose" 320 postgres backend frontend sandbox-runner || exit 1
            _glue logs
            backend_log="$(cd "$TMP" && docker compose logs --no-color backend 2>/dev/null || true)"
            [ -n "$backend_log" ] || { echo "$P: FAIL, the backend log after the restart is empty; nothing was read" >&2; exit 1; }
            if printf '%s\n' "$backend_log" | grep -q 'Running on the shipped placeholder value'; then
                echo "$P: FAIL, the backend log still carries the placeholder warning after the documented .env step and restart" >&2; exit 1
            fi
            echo "$P: the backend log carries no placeholder warning (ok)"
            ;;
        *) echo "$P: FAIL, a Check under [$title] has no assertion in this harness; add one or enumerate it" >&2; exit 1 ;;
        esac
        ;;
    esac
done 3< "$STEPS"

# Failure class 1, closed: every fence on the page ran.
if [ "$executed" -ne "$n_blocks" ]; then
    echo "$P: FAIL, $n_blocks blocks on the page, $executed executed" >&2; exit 1
fi
echo "$P: every one of the $n_blocks command blocks on the page was executed"

# #1194's control arm: mint only, emit nothing, the ladder must not move past listening.
_glue control
CJAR="$TMP/.cjar"
code="$(_req POST /auth/signup "$(jq -nc --arg e "control-$RUN_EMAIL" '{email:$e,password:"quickstart-rehearsal-passphrase"}')" "$CJAR")"
[ "$code" = 200 ] || { echo "$P: FAIL, control signup -> $code" >&2; exit 1; }
_req GET /auth/me "" "$CJAR" >/dev/null; CORG="$(jq -r '.data.orgs[0].slug // empty' "$BODY")"
_req GET "/api/orgs/$CORG/projects" "" "$CJAR" >/dev/null; CPROJ="$(jq -r '.data[0].slug // empty' "$BODY")"
_req POST "/api/orgs/$CORG/projects/$CPROJ/api-keys" '{"name":"OTLP ingest","scope":"write"}' "$CJAR" >/dev/null
sleep 5
cst="$(_stage "$CORG" "$CPROJ" "$CJAR")"
if [ "$cst" = listening ]; then echo "$P: control arm: a mint with no emit reads '$cst' (ok; the fitting assertion above is not vacuous)"; else echo "$P: FAIL, the control arm (mint only) reads '$cst', not listening" >&2; exit 1; fi

if [ -n "$AFTER" ]; then
    _glue after
    echo "$P: DEVIATION (--after): running $AFTER in the export"
    (cd "$TMP" && TMP="$TMP" ORG="$ORG" PROJ="$PROJ" JAR="$JAR" BASE="$BASE" bash "$AFTER") || { echo "$P: FAIL, the --after script exited non-zero" >&2; exit 1; }
fi

# Clause 3's numbers.
total=$((T_FITTING - T0)); post_pull=$((T_FITTING - T_PULL))
# A pulled image reports its registry digest; a built one has none and reports its local id.
digests="$(cd "$TMP" && docker compose images --format json 2>/dev/null | jq -r '.[] | "\(.Repository):\(.Tag)"' | sort -u | while read -r ref; do
    d="$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}} (built, no registry digest){{end}} {{.Size}}' "$ref" 2>/dev/null || echo unknown)"; printf '%s=%s;' "$ref" "$d"; done)"
echo "$P: CLOCK total=${total}s post_pull=${post_pull}s images=$PULL_MODE arch=$(uname -m) host=$(uname -s) digests=$digests"
if [ "$PULL_MODE" = pulled ] && [ "$total" -gt 600 ]; then
    echo "$P: FAIL, the documented sequence took ${total}s on the pulled path; the page's ten minutes is 600 s" >&2; exit 1
fi
echo "$P: ok, the documented steps, run verbatim from the published inputs, reach fitting in ${total}s ($PULL_MODE)"
