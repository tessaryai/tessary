# SPDX-License-Identifier: Apache-2.0
# shellcheck shell=bash
# Shared pieces of scripts/check-open-boot.sh (dev stack) and scripts/check-open-boot-selfhost.sh
# (self-host artifact), so the second leg doesn't duplicate the first's readiness poll and
# credential deny-list checks. Sourced, not executed; every function takes a log prefix so each
# leg's lines stay attributable in a CI log.
#
# Callers must source scripts/lib/cloud-credential-denylist.sh first (CLOUD_CREDENTIAL_DENYLIST).
#
# Lives under scripts/lib/, not scripts/, so scripts/check.sh's manifest glob and
# check-open-boundary.sh rule 5 don't pick it up as a gate.

# open_boot_wait_for <prefix> <desc> <url> <want-http-code> [tries]
# Polls rather than sleeping blind: 45 tries at 2s (90s) covers a cold `--build`. Returns 1 (and
# says what it last saw) when the budget runs out.
# Both waits leave the seconds they spent in OPEN_BOOT_ELAPSED_S, measured through the poll
# rather than beside it.
OPEN_BOOT_ELAPSED_S=0
open_boot_wait_for() {
    local prefix="$1" desc="$2" url="$3" want="$4" tries="${5:-45}" code t0
    t0=$(date +%s)
    while [ "$tries" -gt 0 ]; do
        code="$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
        if [ "$code" = "$want" ]; then
            OPEN_BOOT_ELAPSED_S=$(( $(date +%s) - t0 ))
            echo "$prefix: $desc -> $code (ok, ${OPEN_BOOT_ELAPSED_S}s)"
            return 0
        fi
        tries=$((tries - 1))
        sleep 2
    done
    OPEN_BOOT_ELAPSED_S=$(( $(date +%s) - t0 ))
    echo "$prefix: $desc never reached $want (last seen: ${code:-none}) at $url after ${OPEN_BOOT_ELAPSED_S}s" >&2
    return 1
}

# open_boot_wait_healthy <prefix> <project-dir> <compose-cmd> <budget-seconds> <service>...
# Every named service must report `healthy` in `docker compose ps --format json`; that, not an
# HTTP probe standing in for it, is what says "up". Returns 1 with the offending states when the
# budget runs out, or early when a service is already `unhealthy` or has exited, so a container
# Docker has given up on doesn't hide behind a timeout.
open_boot_wait_healthy() {
    local prefix="$1" dir="$2" compose="$3" budget="$4"; shift 4
    local t0 rows state health svc bad pending
    t0=$(date +%s)
    while :; do
        rows="$(cd "$dir" && $compose ps -a --format json 2>/dev/null | jq -rs '.[] | "\(.Service) \(.State) \(.Health)"' 2>/dev/null || true)"
        bad=""; pending=""
        for svc in "$@"; do
            state="$(printf '%s\n' "$rows" | awk -v s="$svc" '$1 == s {print $2; exit}')"
            health="$(printf '%s\n' "$rows" | awk -v s="$svc" '$1 == s {print $3; exit}')"
            case "$state/$health" in
                running/healthy) ;;
                running/starting|running/|created/*|/*) pending="$pending $svc(${state:-absent}${health:+/$health})" ;;
                *) bad="$bad $svc($state${health:+/$health})" ;;
            esac
        done
        OPEN_BOOT_ELAPSED_S=$(( $(date +%s) - t0 ))
        if [ -n "$bad" ]; then
            echo "$prefix: the stack reports a service Docker has given up on:$bad (after ${OPEN_BOOT_ELAPSED_S}s)" >&2
            return 1
        fi
        if [ -z "$pending" ]; then
            echo "$prefix: docker compose ps reports healthy for $* (${OPEN_BOOT_ELAPSED_S}s)"
            return 0
        fi
        if [ "$OPEN_BOOT_ELAPSED_S" -ge "$budget" ]; then
            echo "$prefix: not every service reached healthy within ${budget}s; still pending:$pending" >&2
            return 1
        fi
        sleep 2
    done
}

# open_boot_assert_healthy_set <prefix> <project-dir> <compose-cmd> <service>...
# Exactly the named services report healthy, and no other service does: a probe added to a
# service the docs do not name changes what `docker compose ps` shows a self-hoster, and the
# setup page's Check is asserted as the page words it.
open_boot_assert_healthy_set() {
    local prefix="$1" dir="$2" compose="$3"; shift 3
    local want got
    want="$(printf '%s\n' "$@" | sort)"
    got="$(cd "$dir" && $compose ps -a --format json 2>/dev/null | jq -rs '.[] | select(.Health == "healthy") | .Service' | sort)"
    if [ "$want" = "$got" ]; then
        echo "$prefix: the healthy set is exactly {$(printf '%s' "$want" | tr '\n' ' ' | sed 's/ $//')} (ok)"
        return 0
    fi
    echo "$prefix: the healthy set is {$(printf '%s' "$got" | tr '\n' ' ')}, the docs name {$(printf '%s' "$want" | tr '\n' ' ')}" >&2
    return 1
}

_open_boot_denylist_pattern() {
    local IFS='|'
    printf '%s' "${CLOUD_CREDENTIAL_DENYLIST[*]}"
}

# open_boot_check_denied_credentials <prefix> <project-dir> <compose-cmd> <service>...
# Two passes, because they catch different bugs:
#   (a) `docker compose config`: the rendered compose file after `${VAR:-default}` interpolation
#       and `env_file` resolution. Catches a default value baked into the compose YAML itself.
#   (b) `docker exec <container> env` on every named service: the container's actual runtime env,
#       the only place a value baked into a Dockerfile `ENV` line would show up.
#       `docker compose config` can't see that class of leak.
# Prints every hit (values redacted) and returns 1 if there was any; 0 when the stack is clean.
# Must run with the same environment the stack was brought up with: `config` re-interpolates, and
# a compose file with `${VAR:?must be set}` keys renders nothing without them.
open_boot_check_denied_credentials() {
    local prefix="$1" dir="$2" compose="$3"; shift 3
    local hits=0 _cred_var _resolved _rendered_config _svc _cid _container_env _hit

    echo "$prefix: checking the rendered compose config for a denied credential…"
    _rendered_config="$(cd "$dir" && $compose config 2>/dev/null || true)"
    for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
        # `|| true` is load-bearing: under `set -euo pipefail`, a denylisted var absent from the
        # rendered config makes grep exit 1, the pipeline fails, the assignment fails, and
        # errexit kills the caller silently: no "FAILED" line, just the EXIT-trap teardown.
        # Absent IS the passing case.
        _resolved="$(printf '%s\n' "$_rendered_config" \
            | grep -E "^[[:space:]]*${_cred_var}:" \
            | head -1 \
            | sed -E 's/^[^:]*:[[:space:]]*//' \
            | tr -d "\"'" || true)"
        if [ -n "$_resolved" ] && [ "$_resolved" != "null" ]; then
            echo "$prefix: $_cred_var resolved to a non-empty value in the rendered compose config" >&2
            hits=1
        fi
    done

    echo "$prefix: checking every running container's own env for a denied credential…"
    for _svc in "$@"; do
        _cid="$(cd "$dir" && $compose ps -q "$_svc" 2>/dev/null || true)"
        [ -z "$_cid" ] && continue
        _container_env="$(docker exec "$_cid" env 2>/dev/null || true)"
        _hit="$(printf '%s\n' "$_container_env" | grep -E "^($(_open_boot_denylist_pattern))=.+" || true)"
        if [ -n "$_hit" ]; then
            echo "$prefix: container '$_svc' ($_cid) carries a denied credential in its own env:" >&2
            printf '%s\n' "$_hit" | sed -E 's/=.*/=<redacted>/' >&2
            hits=1
        fi
    done
    return "$hits"
}

# open_boot_overlay_table_names <overlay-db-resources-dir>
# Resolves db/changelog/paid/db.changelog-paid.yaml under <dir>, walks the changeset SQL files
# it includes, and prints the deduped, sorted `CREATE TABLE public.<name>` names, one per line.
#
# The table-name-derivation expression is copied, not sourced, from check-overlay-schema.sh's own
# rule1(): sourcing it from this file would cross the boundary check-open-boundary.sh's rule 5
# enforces. Keep the two in sync by hand; it's two lines of grep, not worth sharing a file both
# sides would have to reach.
#
# Two non-error outcomes, both deliberate:
#   - no overlay checkout under <dir> (unset, absent, or missing the changelog): a documented
#     SKIP. One line to stderr, empty stdout, returns 0.
#   - the changelog is present but derives zero table names: a real changelog always resolves at
#     least the baseline tables, so zero means a listed file went missing or the CREATE TABLE
#     shape changed underneath this grep. Treated as a hard failure, matching
#     check-overlay-schema.sh's own judgment call.
open_boot_overlay_table_names() {
    local dir="$1" master files f names

    if [ -z "$dir" ] || [ ! -d "$dir" ]; then
        echo "open_boot_overlay_table_names: no overlay directory given (or '$dir' does not exist) - skipping, nothing to derive table names from" >&2
        return 0
    fi

    master="$dir/db/changelog/paid/db.changelog-paid.yaml"
    if [ ! -f "$master" ]; then
        echo "open_boot_overlay_table_names: '$dir' has no db/changelog/paid/db.changelog-paid.yaml - not a paid db module resources root, skipping, nothing to derive table names from" >&2
        return 0
    fi

    # Same "file: db/changelog/paid/changes/…" line shape check-overlay-schema.sh's
    # paid_included_files() parses.
    files="$(sed -n 's|^ *file: *\(db/changelog/paid/changes/.*\)$|\1|p' "$master")"
    if [ -z "$files" ]; then
        echo "open_boot_overlay_table_names: $master includes no changes/ file - nothing to derive table names from" >&2
        return 1
    fi

    names=""
    for f in $files; do
        if [ ! -f "$dir/$f" ]; then
            echo "open_boot_overlay_table_names: $master includes $f, which does not exist on disk" >&2
            return 1
        fi
        names="$names $(grep -oE '^CREATE TABLE public\.[A-Za-z0-9_]+' "$dir/$f" | sed 's/^CREATE TABLE public\.//')"
    done
    names="$(printf '%s\n' $names | sort -u | grep -v '^$' || true)"

    if [ -z "$names" ]; then
        echo "open_boot_overlay_table_names: derived zero overlay table names from $master's own CREATE TABLE statements - failing loud rather than treating zero-to-check-against as trivially true" >&2
        return 1
    fi

    printf '%s\n' "$names"
    return 0
}
