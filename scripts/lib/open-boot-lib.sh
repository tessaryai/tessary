# SPDX-License-Identifier: Apache-2.0
# shellcheck shell=bash
# Shared pieces of the two open-edition BOOT legs — scripts/check-open-boot.sh (the dev stack,
# docker-compose.dev.yml, plus the authenticated triage flow) and scripts/check-open-boot-selfhost.sh
# (the self-host artifact, docker-compose.yml, production profile) — factored here (#1052) so the
# second leg did not copy the first's readiness poll and credential deny-list checks. Sourced, not
# executed; every function takes a log prefix so each leg's lines stay attributable in a CI log.
#
# Callers must have sourced scripts/lib/cloud-credential-denylist.sh first (CLOUD_CREDENTIAL_DENYLIST).
#
# Lives under scripts/lib/ for the same reason dev-compose.sh does: it is not a gate itself, so
# scripts/check.sh's manifest-completeness glob (`scripts/check-*.sh`) must not see it, and
# check-open-boundary.sh rule 5's `ls scripts/*.sh` target set does not recurse.

# open_boot_wait_for <prefix> <desc> <url> <want-http-code> [tries]
# Poll rather than sleep-and-hope: 45 tries at 2s (90s) is generous for a cold `--build`, matching
# the order of magnitude #886's own boot measurement reported. Returns 1 (and says what it last
# saw) when the budget runs out.
# Both waits leave the seconds they spent in OPEN_BOOT_ELAPSED_S, because epic 7 clause 3's
# clock is measured through them rather than beside them.
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
# The stack's own readiness signal (epic 7 clause 4, #1189): every named service must report
# `healthy` in `docker compose ps --format json`, and it is that, not an HTTP probe standing in
# for it, that says "up". Returns 1 with the offending states when the budget runs out, or
# EARLY when any named service is already `unhealthy` or has exited, because waiting on a
# container Docker has given up on only hides the failure behind a timeout.
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
# The POST-boot credential deny-list (#878), two passes because they catch two different bugs:
#   (a) `docker compose config` — the RENDERED compose file, after every ${VAR:-default}
#       interpolation AND every `env_file` resolves. Catches a default value baked into the
#       compose YAML itself (a future `E2B_API_KEY: ${E2B_API_KEY:-some-default}`).
#   (b) `docker exec <container> env` on every named service — the CONTAINER's actual runtime
#       env, which is what a value baked into a Dockerfile `ENV` line (never touching compose at
#       all) would only show up in. `docker compose config` cannot see that class of leak.
# Prints every hit (values redacted) and returns 1 if there was any; 0 when the stack is clean.
# Must run with the SAME environment the stack was brought up with — `config` re-interpolates,
# and a compose file with `${VAR:?must be set}` keys renders nothing at all without them.
open_boot_check_denied_credentials() {
    local prefix="$1" dir="$2" compose="$3"; shift 3
    local hits=0 _cred_var _resolved _rendered_config _svc _cid _container_env _hit

    echo "$prefix: checking the rendered compose config for a denied credential…"
    _rendered_config="$(cd "$dir" && $compose config 2>/dev/null || true)"
    for _cred_var in "${CLOUD_CREDENTIAL_DENYLIST[@]}"; do
        # `|| true` is load-bearing: under `set -euo pipefail` a denylisted var that is simply
        # ABSENT from the rendered config makes grep exit 1, the pipeline fails, the assignment
        # fails, and errexit kills the caller silently — no "FAILED" line, only the EXIT-trap
        # teardown and exit 1. That was the whole story of the first dispatched gate run
        # (33613152714, fixed in #1043). Absent IS the passing case.
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
# <overlay-db-resources-dir> is the paid overlay's `db` module resources root, the same directory
# tessary-paid/scripts/check-overlay-schema.sh calls $PAID_RESOURCES ($PAID/db/src/main/resources),
# and the same one the Taskfile probe hands the sibling instruments as MIGPOP_OVERLAY_DIR /
# EQCHK_OVERLAY_DIR (#1076: OPEN_BOOT_OVERLAY_DIR is this feature's own name for that convention).
# Resolves db/changelog/paid/db.changelog-paid.yaml under it, walks the changeset SQL files it
# includes, greps every `CREATE TABLE public.<name>` and prints the deduped, sorted bare names,
# one per line, to stdout.
#
# The table-name-derivation expression below is COPIED, not sourced, from check-overlay-schema.sh's
# own rule1(), sourcing a tessary-paid/ script from an open one would itself be an open->paid
# reference and fail check-open-boundary.sh's rule 5 sweep (tessary-paid/OPEN-CORE.md's partition-commit row:
# "no open file names an overlay relation"). Keep the two expressions in sync by hand if either
# changes; they are two lines of grep, not worth a shared file that would have to live somewhere
# both sides can reach without crossing the boundary either way.
#
# Two distinct non-error outcomes, both spelled out on purpose, a silent one here is exactly the
# #1043 bug class open_boot_check_denied_credentials's comment above already tells that story for:
#   - directory unset, absent, or present but not (yet) an overlay checkout, no
#     db/changelog/paid/db.changelog-paid.yaml under it, which is also what an empty/nonexistent
#     tmp dir looks like: this is the documented "no overlay to derive table names from" SKIP.
#     One spoken line to stderr, empty stdout, returns 0, it is the caller's job to decide that
#     an unset directory means its own assertion gets skipped too (exactly what check-open-boot.sh's
#     OPEN_BOOT_OVERLAY_DIR-gated block below does).
#   - a real db.changelog-paid.yaml IS present, but it derives ZERO table names: this is NOT "an
#     empty overlay is fine", a real `tessary-paid/db` checkout with a real changelog always
#     resolves at least the P0000 baseline's tables, so zero here means a file it lists went
#     missing or the CREATE TABLE shape changed underneath this grep. check-overlay-schema.sh's own
#     rule1() already treats this shape as a hard failure rather than "nothing to check against,
#     therefore trivially true", this function matches that judgment call rather than re-deciding
#     it.
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
        echo "open_boot_overlay_table_names: derived zero overlay table names from $master's own CREATE TABLE statements - failing loud rather than treating zero-to-check-against as trivially true (the #1043 bug class)" >&2
        return 1
    fi

    printf '%s\n' "$names"
    return 0
}
