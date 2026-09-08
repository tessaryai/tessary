#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The egress-capture harness for epic 6 clause 9, condition 2 (#1154): proves the self-hosted open
# edition's default deployment reaches exactly one outbound destination, home.tessary.ai, and that
# EVALS_TELEMETRY_ENABLED=false actually suppresses it rather than merely documenting that it should.
#
# WHY A NEW FILE AND NOT check-open-boot-selfhost.sh. That script boots docker-compose.yml and
# proves the stack comes up behind the auth guard with no denied CREDENTIAL present — a static
# assertion over rendered config and container env (open_boot_check_denied_credentials). It has no
# opinion on what the stack actually DIALS once it is up, and it was never meant to: adding a
# packet capture to an already-dense boot check would bury one failure mode inside another script's
# unrelated exit code. This file's only job is: what did the network actually see leave the bridge.
#
# WHY scripts/lib/, NOT scripts/check-egress-capture.sh. scripts/check.sh's
# `_assert_manifest_complete()` globs `scripts/check-*.sh` and `scripts/lib/check-*.sh` and hard-fails
# if either set has a row the manifest doesn't, INCLUDING scripts that are EXCLUDED from `task
# check` itself — check-open-boot.sh and check-open-boot-selfhost.sh both still carry manifest rows
# for exactly that reason (Taskfile.yml:275,278). A file under scripts/lib/ not matching `check-*.sh`
# needs no such row — the same reason dev-compose.sh and open-boot-lib.sh live here rather than at
# the top level (open-boot-lib.sh's own header makes the identical argument for the manifest glob
# plus check-open-boundary.sh rule 5's separate `ls scripts/*.sh` sweep, which also does not
# recurse). This is deliberate, not an oversight: wiring this into the manifest as a bare
# check-egress-capture.sh would make `task check`/`check:open` — which run in CI on every PR with no
# Docker and no elevated network capability — either fail hard or need a permanent EXCLUDED
# carve-out for a script nothing in CI can run yet. Simpler to keep the name off the glob until this
# graduates into a real gate.
#
# WHAT THIS DOES NOT DO. It is not epic 7's #1197 three-vantage egress harness (compose-network
# vantage, host-daemon vantage, served-page vantage). This is the compose-network vantage ALONE,
# built to the minimum #1154 needs to make a sign-off claim about the self-hosted default. #1197
# should CALL this script's capture-and-diff logic as one of its three vantages rather than
# re-deriving DNS-capture code a second time; it should not replace this file or duplicate it.
#
# NEVER RUN AGENT-SIDE, EVER. Same rule as check-open-boot.sh and check-open-boot-selfhost.sh, for
# the same reason (needs Docker to build and boot a real stack) plus one more: it also needs
# tcpdump and enough host privilege to sniff a bridge interface, which an agent sandbox has neither
# of and should not be granted. Run by a human (`task egress:capture`). The human runs it once,
# reads the destinations it printed, and records the result — date, destinations seen, kill-switch
# outcome — as a line near clause 9's evidence directly in tessary-paid/OPEN-CORE.md. This script does not write
# to tessary-paid/OPEN-CORE.md itself and no separate dated sign-off artifact should exist elsewhere for this.
#
# WHAT IT PROVES, PER PASS. Two passes, same export, same compose file, only
# EVALS_TELEMETRY_ENABLED flipped between them:
#   pass 1 (EVALS_TELEMETRY_ENABLED=true, the shipped default per D6): boot the stack, wait for it
#     to come up, let one heartbeat window elapse (TelemetryHeartbeat's own initialDelay is 5s plus
#     up to 25s of jitter — see backend/surfaces/.../telemetry/TelemetryHeartbeat.java — so this
#     pass waits comfortably past that), then read back every DNS query name and every outbound TCP
#     SYN observed on the compose network's bridge interface for the whole window. Expect exactly
#     one distinct destination hostname: home.tessary.ai. Anything else is a real finding, not
#     something this script papers over — it prints every distinct destination it saw and fails
#     the pass if the set is not exactly {home.tessary.ai}.
#   pass 2 (EVALS_TELEMETRY_ENABLED=false): same boot, same wait, same capture window. Expect ZERO
#     destinations of any kind — the contract's own wording is "zero outbound calls including DNS
#     resolution" (devdocs/reference/telemetry-contract.md §1, docs/self-hosting/configuration.mdx's
#     Telemetry <Tip>), so even a DNS-only, connection-refused attempt at home.tessary.ai is a
#     failure of this pass, not a partial pass.
#
# WHY A HOST-SIDE tcpdump ON THE BRIDGE INTERFACE, NOT A SIDECAR CONTAINER. A capture container
# joined to the SAME user-defined bridge network only sees traffic addressed to or from ITSELF —
# Linux bridges do not mirror unicast traffic between two other ports to a third port without
# explicit port-mirroring (which Docker's bridge driver does not expose), so a sidecar cannot
# observe backend's or sandbox-runner's own egress by simply joining the network. The host CAN:
# every container's veth pair terminates on the compose network's own Linux bridge device on the
# host side (Docker's naming convention for a user-defined network is `br-<first 12 chars of the
# network's ID>`), and a tcpdump listening on THAT device sees every packet crossing it in either
# direction, unicast included. That is why this script runs on the host, needs tcpdump on PATH
# (not a container), and — on most hosts — needs to run as root or with CAP_NET_RAW/CAP_NET_ADMIN
# to open that interface in promiscuous capture mode; this is exactly the same privilege class
# check-open-boot-selfhost.sh's Docker socket access already assumes for its runner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
P="egress-capture"

# The one destination the shipped default is allowed to reach, per D6 and the published table this
# pass is proving (docs/self-hosting/configuration.mdx's Telemetry section). Kept as a variable,
# not inlined into every check below, so a future contract-version bump that adds a genuinely new
# destination has exactly one line to change.
ALLOWED_HOST_WHEN_ON="home.tessary.ai"

command -v tcpdump >/dev/null 2>&1 || {
    echo "$P: tcpdump is not on PATH. This harness captures the compose network's bridge interface" >&2
    echo "    from the host — there is no container-side substitute (see this file's header). Install" >&2
    echo "    tcpdump (apt-get install tcpdump / brew install tcpdump) and re-run." >&2
    exit 1
}
command -v docker >/dev/null 2>&1 || { echo "$P: docker is not on PATH." >&2; exit 1; }
if [ "$(id -u)" -ne 0 ]; then
    echo "$P: (note) not running as root — if the capture below reports a permission error opening" >&2
    echo "    the bridge interface, re-run this script with sudo. Docker itself already needs" >&2
    echo "    root/docker-group access, so this asks for nothing new in kind, only in scope." >&2
fi

# shellcheck source=lib/cloud-credential-denylist.sh
. "$ROOT/scripts/lib/cloud-credential-denylist.sh"
# shellcheck source=open-boot-lib.sh
. "$ROOT/scripts/lib/open-boot-lib.sh"

HTTP_PORT="${EGRESS_CAPTURE_HTTP_PORT:-18100}"
HTTPS_PORT="${EGRESS_CAPTURE_HTTPS_PORT:-18543}"
BASE="http://localhost:${HTTP_PORT}"

# Same clean-room isolation check-open-boot-selfhost.sh uses, and the same reason: a shared
# COMPOSE_PROJECT_NAME would reuse the developer's own stack, its volume, and its network — and a
# network this script did not create is a network whose bridge interface name it cannot safely
# assume nothing else is also talking on.
export COMPOSE_PROJECT_NAME="egress-capture-$$"
COMPOSE="docker compose -f docker-compose.yml"
TMP=""
CAPTURE_PID=""
# capture_pass's result channel — see the comment on capture_pass itself for why this is a plain
# global instead of the `x="$(capture_pass ...)"` command-substitution pattern this used to use.
CAPTURE_DESTINATIONS=""

_cleanup() {
    local status=$?
    [ -n "$CAPTURE_PID" ] && kill "$CAPTURE_PID" 2>/dev/null || true
    if [ -n "$TMP" ]; then
        if (cd "$TMP" 2>/dev/null); then
            echo "$P: tearing down the stack ($TMP)…"
            (cd "$TMP" && $COMPOSE down -v --remove-orphans) || true
            docker run --rm -v "$TMP:/t" busybox:1.36 sh -c 'rm -rf /t/.local /t/.data' >/dev/null 2>&1 || true
        fi
        rm -rf "$TMP"
    fi
    exit "$status"
}
trap _cleanup EXIT

for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
    unset "$_cred_var" 2>/dev/null || true
done

# capture_pass <label> <telemetry-enabled true|false> <window-seconds>
#   1. writes a fresh .env into the export (same minimum set check-open-boot-selfhost.sh generates
#      — run-scoped, generated, never reused — plus EVALS_TELEMETRY_ENABLED for this pass)
#   2. boots docker-compose.yml
#   3. resolves this project's bridge interface off the compose network's real Docker network ID
#   4. tcpdump's that interface for <window-seconds>, filtering to DNS (udp port 53) and outbound
#      TCP SYNs (new connection attempts, not their replies) — anything else on the bridge is
#      inter-container chatter this script has no reason to care about
#   5. tears the stack down, leaves the set of distinct destination hostnames the window observed
#      in the global CAPTURE_DESTINATIONS (DNS query names take priority; a bare SYN with no
#      preceding DNS query is reported by IP, which the false-pass fails on regardless, and the
#      true-pass should never produce since a hardcoded IP with no DNS lookup would itself be a
#      finding worth surfacing, not silently passing under the theory that "at least it wasn't
#      home.tessary.ai")
capture_pass() {
    local label="$1" telemetry_enabled="$2" window="$3"
    local pcap net_id bridge_if destinations

    # capture_pass is called as a PLAIN statement below (`capture_pass ... || fail=1`), never
    # wrapped in `x="$(capture_pass ...)"` — command substitution forks the whole function body
    # into a subshell, and this function's job is precisely to mutate the top-level TMP and
    # CAPTURE_PID (so pass 2 reuses pass 1's export, and so the EXIT trap can always find a
    # tmpdir/pid to tear down, including on the early `return 1` paths below). A subshelled
    # assignment to either would silently vanish the instant the function returned. Its "return
    # value" — the destination set — goes out the same way: through the global
    # CAPTURE_DESTINATIONS, set right before every return, not through stdout.
    CAPTURE_DESTINATIONS=""
    echo "$P: [$label] exporting the working tree -> re-using $TMP if this is pass 2, fresh otherwise" >&2
    if [ -z "$TMP" ]; then
        TMP="$(mktemp -d)"
        bash "$ROOT/scripts/lib/export-simulate.sh" "$TMP" >&2
    fi

    {
        echo "POSTGRES_USER=evals"
        echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)"
        echo "EVALS_SECRET_KEY=$(openssl rand -base64 32)"
        echo "EVALS_AUTH_COOKIE_PASSWORD=$(openssl rand -base64 32)"
        echo "HTTP_PORT=$HTTP_PORT"
        echo "HTTPS_PORT=$HTTPS_PORT"
        echo "POSTGRES_DATA_DIR=$TMP/.local/postgres"
        echo "EVALS_TELEMETRY_ENABLED=$telemetry_enabled"
    } > "$TMP/.env"
    chmod 600 "$TMP/.env"

    echo "$P: [$label] booting docker-compose.yml (EVALS_TELEMETRY_ENABLED=$telemetry_enabled)…" >&2
    (cd "$TMP" && $COMPOSE up -d --build) >&2
    open_boot_wait_for "$P" "[$label] GET / (frontend, via caddy :$HTTP_PORT)" "$BASE/" 200 45 >&2 \
        || echo "$P: [$label] (note) frontend never answered 200 — capturing anyway; a stack that never came up cannot ping anyone, which is its own kind of finding." >&2

    # The compose network's real ID, not its human name — the bridge device name Docker assigns
    # is derived from the ID, never the `name:` label docker-compose.yml gives it.
    net_id="$(docker network ls --filter "name=${COMPOSE_PROJECT_NAME}_evals" --format '{{.ID}}' | head -1)"
    if [ -z "$net_id" ]; then
        echo "$P: [$label] could not find the compose network '${COMPOSE_PROJECT_NAME}_evals' — nothing to capture." >&2
        return 1
    fi
    bridge_if="br-${net_id:0:12}"
    if ! ip link show "$bridge_if" >/dev/null 2>&1; then
        echo "$P: [$label] bridge interface '$bridge_if' not found on this host. This capture method" >&2
        echo "    assumes Docker's default Linux bridge driver; it does not work on Docker Desktop for" >&2
        echo "    macOS/Windows, where containers run inside a VM this host cannot see the bridge of." >&2
        echo "    Run this on a Linux Docker host (a CI runner or a Linux workstation)." >&2
        return 1
    fi

    pcap="$TMP/${label}.pcap"
    echo "$P: [$label] capturing $bridge_if for ${window}s (DNS + outbound SYN only) -> $pcap" >&2
    tcpdump -i "$bridge_if" -w "$pcap" -U 'udp port 53 or (tcp[tcpflags] & tcp-syn != 0 and tcp[tcpflags] & tcp-ack == 0)' >&2 &
    CAPTURE_PID=$!
    sleep "$window"
    kill "$CAPTURE_PID" 2>/dev/null || true
    wait "$CAPTURE_PID" 2>/dev/null || true
    CAPTURE_PID=""

    echo "$P: [$label] tearing down this pass's stack…" >&2
    (cd "$TMP" && $COMPOSE down -v --remove-orphans) >&2 || true

    # DNS query names first (tcpdump's own decoder), falling back to raw destination IPs for any
    # SYN that carried no preceding query — printed, never silently dropped, because a hardcoded-IP
    # egress with no DNS lookup is not "nothing to report", it is the thing this capture exists to
    # catch. `-n`: no reverse-DNS on the read side: that lookup is itself an outbound query and
    # would corrupt what we are trying to measure, at read time and against the wrong window.
    # tcpdump's decoded query line is TYPE-then-NAME, e.g. `43690+ A? home.tessary.ai. (32)` — the
    # query type always precedes the hostname, never follows it — so the grep anchors on `A?`/`A`
    # first and the hostname is everything after it, not before.
    destinations="$( { \
        tcpdump -n -r "$pcap" udp port 53 2>/dev/null \
            | grep -oE 'A\??[[:space:]]+[A-Za-z0-9._-]+\.[A-Za-z]{2,}\.' \
            | sed -E 's/^A\??[[:space:]]+//; s/\.$//'; \
        tcpdump -n -r "$pcap" 'tcp[tcpflags] & tcp-syn != 0 and tcp[tcpflags] & tcp-ack == 0' 2>/dev/null \
            | grep -oE '> [0-9.]+\.[0-9]+' | awk '{print $2}' | sed -E 's/\.[0-9]+$//'; \
    } | sort -u )"

    echo "$P: [$label] distinct destinations observed:" >&2
    if [ -z "$destinations" ]; then
        echo "$P: [$label]   (none)" >&2
    else
        printf '%s\n' "$destinations" | sed "s/^/$P: [$label]   /" >&2
    fi
    # Hand the result back through the global — see the comment at the top of this function.
    CAPTURE_DESTINATIONS="$destinations"
}

# 90s: comfortably past TelemetryHeartbeat's own worst-case initialDelay (5s base + up to 25s
# jitter — see backend/surfaces/.../telemetry/TelemetryHeartbeat.java), plus boot time margin. The
# 24h steady-state interval is not exercised here; this capture proves the FIRST ping fires (or
# doesn't), which is the assertion condition 2 actually needs — nobody is proposing a 24-hour CI job.
WINDOW="${EGRESS_CAPTURE_WINDOW_SECONDS:-90}"

fail=0

capture_pass "telemetry-on" "true" "$WINDOW" || fail=1
pass1_destinations="$CAPTURE_DESTINATIONS"
if [ "$pass1_destinations" != "$ALLOWED_HOST_WHEN_ON" ]; then
    echo "$P: FAIL — with EVALS_TELEMETRY_ENABLED=true, expected exactly one destination" >&2
    echo "    ($ALLOWED_HOST_WHEN_ON) and saw: '${pass1_destinations:-none}'" >&2
    fail=1
fi

capture_pass "telemetry-off" "false" "$WINDOW" || fail=1
pass2_destinations="$CAPTURE_DESTINATIONS"
if [ -n "$pass2_destinations" ]; then
    echo "$P: FAIL — with EVALS_TELEMETRY_ENABLED=false, expected ZERO destinations (the contract's" >&2
    echo "    own claim is zero outbound calls including DNS) and saw: '$pass2_destinations'" >&2
    fail=1
fi

[ "$fail" = 0 ] || {
    echo "$P: FAILED — see above. Do not silently narrow the published egress table to match a" >&2
    echo "    surprise finding; a real new destination here is a code bug or a doc bug, and either" >&2
    echo "    way it needs its own fix, not a wider allowlist in this script." >&2
    exit 1
}
echo "$P: PASS — telemetry on reaches exactly {$ALLOWED_HOST_WHEN_ON}; telemetry off reaches nothing."
echo "$P: record this result (date, destinations, kill-switch outcome) as a line near clause 9's" \
     "evidence in tessary-paid/OPEN-CORE.md — no separate dated sign-off artifact, per the ruling on this issue."
