#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The self-host artifact's readiness contract, asserted on the rendered compose config (epic 7
# clause 4, #1189). "Up" means serving, and the stack says so itself: exactly the services
# docs/self-hosting/setup.mdx tells a self-hoster will show `healthy` carry a probe, the frontend
# waits on the backend being healthy rather than merely started, and every probe's interval is
# short enough that the readiness signal adds a stated, small latency to the boot clock. The boot
# leg (scripts/check-open-boot-selfhost.sh) proves the probes go green on a real boot; this gate
# proves the file still says what that leg and the docs rely on, and runs inside `task check`
# because `docker compose config` needs Docker's CLI and nothing else.
set -euo pipefail
P=check-selfhost-health
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
for tool in docker jq; do command -v "$tool" >/dev/null || { echo "$P: $tool is required" >&2; exit 2; }; done

DOC="docs/self-hosting/setup.mdx"
MAX_INTERVAL_S=5
# The services the setup page names as reporting healthy, read from the page's own Check so the
# docs and the file cannot drift apart silently: the sentence must name each with `healthy`.
documented="$(grep -oE '`docker compose ps` shows [^.]*`healthy`' "$DOC" | grep -oE '`[a-z-]+`' | tr -d '`' | grep -v '^healthy$' | sort -u)"
[ -n "$documented" ] || { echo "$P: $DOC has no 'docker compose ps shows ... healthy' Check to read the healthy set from" >&2; exit 1; }

cfg="$(mktemp)"; trap 'rm -f "$cfg"' EXIT
# The interpolation defaults are what a fresh clone renders with; no value here is a credential.
HTTP_PORT=80 HTTPS_PORT=443 PNPM_VERSION=0 docker compose -f docker-compose.yml config --format json > "$cfg" 2>/dev/null \
    || { echo "$P: docker compose config failed to render docker-compose.yml" >&2; exit 1; }

fail=0
probed="$(jq -r '.services | to_entries[] | select(.value.healthcheck.test != null) | .key' "$cfg" | sort -u)"
if [ "$probed" != "$documented" ]; then
    echo "$P: RED  services with a health probe {$(printf '%s' "$probed" | tr '\n' ' ')} are not the services $DOC says show healthy {$(printf '%s' "$documented" | tr '\n' ' ')}" >&2; fail=1
else
    echo "$P: ok   health probes on exactly the services the setup page names: $(printf '%s' "$documented" | tr '\n' ' ')"
fi

cond="$(jq -r '.services.frontend.depends_on.backend.condition // empty' "$cfg")"
if [ "$cond" = service_healthy ]; then
    echo "$P: ok   frontend waits on backend with condition: service_healthy"
else
    echo "$P: RED  frontend.depends_on.backend.condition is '${cond:-absent}', wanted service_healthy" >&2; fail=1
fi

# A rendered duration is a compose duration string (`5s`, `1m30s`); an interval longer than
# MAX_INTERVAL_S adds that much pure latency to the stop-the-clock signal on a stack that is
# already serving.
_seconds() {
    local d="$1" total=0 n unit
    while [ -n "$d" ]; do
        n="${d%%[a-z]*}"; d="${d#"$n"}"; unit="${d%%[0-9]*}"; d="${d#"$unit"}"
        case "$unit" in
            h) total=$((total + n * 3600)) ;;
            m) total=$((total + n * 60)) ;;
            s|'') total=$((total + n)) ;;
            ms|us|ns) ;;
            *) echo "$P: cannot read duration '$1'" >&2; return 1 ;;
        esac
    done
    printf '%s' "$total"
}
while IFS='|' read -r svc interval; do
    [ -n "$svc" ] || continue
    secs="$(_seconds "$interval")" || { fail=1; continue; }
    if [ "$secs" -le "$MAX_INTERVAL_S" ] && [ "$secs" -gt 0 ]; then
        echo "$P: ok   $svc probe interval ${secs}s (at most ${MAX_INTERVAL_S}s of added latency)"
    else
        echo "$P: RED  $svc probe interval '${interval}' exceeds the ${MAX_INTERVAL_S}s the boot clock is budgeted for" >&2; fail=1
    fi
done < <(jq -r '.services | to_entries[] | select(.value.healthcheck.test != null) | "\(.key)|\(.value.healthcheck.interval // "0")"' "$cfg")

[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: ok, the self-host artifact's readiness contract holds on the rendered config"
