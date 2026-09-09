#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Opt-out means silent: with TESSARY_TELEMETRY_ENABLED=false and no operator
# credential, a booted stack plus a full clause-2 rehearsal resolves and connects to nothing outside
# the published permitted set, observed at three vantage points, and the instrument is proven able to
# fail by a positive control and a planted call.
#
# HOW: this layers on scripts/check-selfhost-quickstart.sh through its three enumerated deviations.
#   --overlay  a docker-compose.override.yml that (a) makes the compose network `internal` (no
#              default route, so no packet can leave it), (b) adds a DNS sink on a fixed address
#              that logs every name it is asked and answers NXDOMAIN (scripts/lib/dns-sink.js on the
#              sandbox runner's own image, so nothing new is pulled), (c) points every service's
#              resolver at the sink (Docker's embedded resolver still answers service names itself
#              and forwards only what it cannot, so the sink sees exactly the external lookups), and
#              (d) gives the frontend a second, ordinary network for its published host port, since
#              an internal network cannot publish one (its own egress is ACME only, and only with
#              SITE_DOMAIN set, which this run does not).
#              Two limits, stated on the configuration page as well: the frontend keeps a route out
#              through that second network, so a connection it made to an IP literal (never a
#              name) would go unobserved; and the sandbox launcher pulls AGENT_IMAGE lazily at the
#              first analysis on its own bridge, which the rehearsal never reaches. The resolver
#              assumption, that Docker's embedded DNS forwards only what it cannot answer itself to
#              the `dns:` servers on the container's own networks, is the one the control arm and
#              the plant re-prove on every run.
#   --env-line TESSARY_TELEMETRY_ENABLED=false, the opt-out the configuration page documents.
#   --after    the vantage assertions, run in the export after every documented step:
#              1. compose network: the sink's log holds no name outside the permitted set;
#              2. host daemon: `docker events` shows no image pull since the window opened;
#              3. served page: every host string in every file Caddy serves (the whole static site,
#                 copied out of the frontend container, so lazily loaded route chunks count) is
#                 either the page's own or on scripts/lib/served-page-named-hosts.txt (hosts the
#                 bundle names without fetching, each with why), so a new origin anywhere in the
#                 bundle is red whether or not a grep can prove a fetch;
#              then the POSITIVE CONTROL: TESSARY_TELEMETRY_ENABLED=true appended to .env, the backend
#              recreated, and the sink must log home.tessary.ai (and nothing else new);
#              then the PLANT: one outbound call from inside the backend, which the sink must see.
#
# THE PERMITTED SET is published on docs/self-hosting/configuration.mdx ("What leaves your network");
# this script reads it from there rather than carrying its own, so the page and the check agree.
#
# Needs Docker, ports 80 and 443 free, and minutes. `--build` is passed through while the images
# are unpublished. NEVER RUN AGENT-SIDE. `task check:zero:egress`; a dispatch-only job in
# open-edition-boot.yml; EXCLUDED from `task check`; no cron.
set -euo pipefail
P=check-zero-egress
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
BUILD=""
for arg in "$@"; do
    case "$arg" in
        --build) BUILD=--build ;;
        *) echo "$P: unknown argument '$arg' (accepts --build)" >&2; exit 2 ;;
    esac
done
for tool in docker jq curl; do command -v "$tool" >/dev/null || { echo "$P: $tool is required" >&2; exit 2; }; done

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
SINK_IP=10.99.0.53

# The permitted set, read off the published page: one backticked hostname per row of the table
# under "What leaves your network". An unqualified compose service name never reaches the sink.
PERMITTED="$(awk '/^## What leaves your network/{f=1} f && /^## / && !/What leaves/{f=0} f && /^\| `/ {print $2}' docs/self-hosting/configuration.mdx | tr -d '`' | grep -v '^Destination$' | sort -u)"
[ -n "$PERMITTED" ] || { echo "$P: docs/self-hosting/configuration.mdx has no 'What leaves your network' table to read the permitted set from" >&2; exit 1; }
echo "$P: permitted set, from the configuration page: $(printf '%s' "$PERMITTED" | tr '\n' ' ')"

cp scripts/lib/dns-sink.js "$W/dns-sink.js"
cat > "$W/docker-compose.override.yml" <<YAML
networks:
  tessary:
    internal: true
    ipam:
      config:
        - subnet: 10.99.0.0/24
  ingress: {}
services:
  dns-sink:
    image: \${SANDBOX_RUNNER_IMAGE:-tessaryai/tessary:sandbox-runner-\${TESSARY_VERSION:-latest}}
    # Never pulled: the sandbox runner's build (or pull) already made this image local, and a
    # second build of the same tag would race it.
    pull_policy: never
    user: root
    entrypoint: ["node", "/dns-sink.js"]
    volumes:
      - $W/dns-sink.js:/dns-sink.js:ro
    networks:
      tessary:
        ipv4_address: $SINK_IP
  postgres:
    dns: [$SINK_IP]
  backend:
    dns: [$SINK_IP]
  sandbox-runner:
    dns: [$SINK_IP]
  frontend:
    dns: [$SINK_IP]
    networks: [tessary, ingress]
YAML

cat > "$W/after.sh" <<'AFTER'
#!/usr/bin/env bash
set -euo pipefail
P=check-zero-egress
PERMITTED="$(cat "$PERMITTED_FILE")"
fail=0
# An empty result is the best result, so no stage of this pipeline may abort on "no match".
_sink_names() { { docker compose logs --no-color dns-sink 2>/dev/null || true; } | { grep -oE 'query [^ ]+ from' || true; } | awk '{print $2}' | sed 's/\.$//' | sort -u; }
_unpermitted() { _sink_names | grep -vxF -f <(printf '%s\n' "$PERMITTED") || true; }

echo "$P: --- vantage 1, the compose network: every external name any service asked for"
names="$(_sink_names)"
echo "$P: the sink was asked for: ${names:-nothing}" | tr '\n' ' '; echo
bad="$(_unpermitted)"
if [ -n "$bad" ]; then echo "$P: RED  names outside the permitted set were looked up with the opt-out set: $(printf '%s' "$bad" | tr '\n' ' ')" >&2; fail=1; else echo "$P: ok   nothing outside the permitted set was looked up during the boot and the rehearsal"; fi
if printf '%s\n' "$names" | grep -qx 'home.tessary.ai'; then echo "$P: RED  home.tessary.ai was looked up although TESSARY_TELEMETRY_ENABLED=false" >&2; fail=1; else echo "$P: ok   home.tessary.ai was never looked up with the opt-out set"; fi

echo "$P: --- vantage 2, the host daemon: no image pull since the window opened"
since="$(cat "$TMP/.window-start")"
pulls="$(docker events --since "$since" --until "$(date +%s)" --filter type=image --filter event=pull --format '{{.Actor.Attributes.name}}' 2>/dev/null | sort -u || true)"
if [ -n "$pulls" ]; then echo "$P: RED  the daemon pulled during the window: $(printf '%s' "$pulls" | tr '\n' ' ')" >&2; fail=1; else echo "$P: ok   the daemon pulled nothing after the images were local"; fi

echo "$P: --- vantage 3, the served site: every host string in every file Caddy serves is its own or an enumerated named-only host"
# The whole static site, not the assets index.html happens to reference: route chunks the app
# loads lazily are files here too, and a host named in any of them is a host the browser can meet.
site="$(mktemp -d)"
docker compose cp frontend:/srv "$site/srv" >/dev/null 2>&1 || { echo "$P: RED  could not copy the served site out of the frontend container" >&2; fail=1; }
n_files="$(find "$site/srv" -type f \( -name '*.html' -o -name '*.js' -o -name '*.css' -o -name '*.mjs' -o -name '*.map' \) 2>/dev/null | wc -l | tr -d ' ')"
# A host is a dotted name: `//g` and `//sdk` in minified code are regex flags and path fragments,
# not origins, and no origin a browser would fetch from is a single label but localhost.
hosts="$(find "$site/srv" -type f \( -name '*.html' -o -name '*.js' -o -name '*.css' -o -name '*.mjs' -o -name '*.map' \) -exec cat {} + 2>/dev/null | grep -oE '(https?:)?//[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+' | sed -E 's#^(https?:)?//##' | grep -vE '^127\.0\.0\.1$' | sort -u || true)"
rm -rf "$site"
named_only="$(grep -v '^#' scripts/lib/served-page-named-hosts.txt | cut -d'|' -f1 | grep . || true)"
foreign="$(printf '%s\n' "$hosts" | grep -vxF -f <(printf '%s\n' "$named_only"; echo '#none#') | grep . || true)"
if [ -n "$foreign" ]; then
    echo "$P: RED  the served site names hosts that are neither its own nor on scripts/lib/served-page-named-hosts.txt: $(printf '%s' "$foreign" | tr '\n' ' ')" >&2; fail=1
else
    echo "$P: ok   $n_files served files name no host but their own and the enumerated named-only ones ($(printf '%s' "$hosts" | tr '\n' ' '))"
fi

echo "$P: --- positive control: telemetry on, the sink must see home.tessary.ai"
before="$(_sink_names)"
printf 'TESSARY_TELEMETRY_ENABLED=true\n' >> .env
docker compose up -d >/dev/null 2>&1
# The heartbeat fires 5 to 30 s after the scheduler starts, so the budget starts at backend healthy.
. scripts/lib/open-boot-lib.sh
open_boot_wait_healthy "$P" "$PWD" "docker compose" 320 backend || { echo "$P: RED  the backend did not come back healthy after the telemetry flip" >&2; fail=1; }
seen=0
for _ in $(seq 1 45); do
    if _sink_names | grep -qx 'home.tessary.ai'; then seen=1; break; fi
    sleep 2
done
if [ "$seen" = 1 ]; then echo "$P: ok   with telemetry on, the sink logged home.tessary.ai (the instrument sees the one permitted call)"; else echo "$P: RED  with telemetry on, no home.tessary.ai lookup reached the sink within 90 s; the instrument cannot see the call it exists to see" >&2; fail=1; fi
new="$(_sink_names | grep -vxF -f <(printf '%s\n' "$before") | grep -vx 'home.tessary.ai' || true)"
if [ -n "$new" ]; then echo "$P: RED  telemetry on looked up more than home.tessary.ai: $(printf '%s' "$new" | tr '\n' ' ')" >&2; fail=1; else echo "$P: ok   telemetry on added exactly home.tessary.ai and nothing else"; fi

echo "$P: --- plant: one outbound call from inside the backend must be seen"
docker compose exec -T backend bash -c 'exec 3<>/dev/tcp/planted-egress.example.invalid/80' >/dev/null 2>&1 || true
sleep 2
if _sink_names | grep -qx 'planted-egress.example.invalid'; then echo "$P: ok   the planted lookup from the backend reached the sink; the instrument is not vacuous"; else echo "$P: RED  a planted outbound call from the backend was not seen" >&2; fail=1; fi

[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: all three vantage points clean with the opt-out set; the positive control and the plant were both seen"
AFTER
chmod +x "$W/after.sh"
printf '%s\n' "$PERMITTED" > "$W/permitted.txt"
export PERMITTED_FILE="$W/permitted.txt"

bash scripts/check-selfhost-quickstart.sh $BUILD --overlay "$W/docker-compose.override.yml" --env-line TESSARY_TELEMETRY_ENABLED=false --after "$W/after.sh"
echo "$P: ok, opt-out means silent: no name outside the permitted set at any vantage point, and the instrument saw both the control and the plant"
