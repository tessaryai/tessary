#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Manifest-resolution assertion for the four published Tessary images.
# Everything else this repo gates checks CODE. This checks the REGISTRY — the one
# thing no unit test, no compile step and no boundary script can see: whether the tag a self-hoster
# is about to pull actually resolves, was stamped with the version it's tagged as, and covers the
# architectures the docs claim.
#
# WHY THIS EXISTS, CONCRETELY: a self-host boot failed with `manifest unknown`
# because `docker-compose.yml`'s SANDBOX_RUNNER_IMAGE default named a tag nothing had ever
# published. `docker compose config` cannot catch that — it renders the string, it does not ask a
# registry whether the string means anything. Nothing before this script did either.
#
# NO REGISTRY LOGIN. Every image this repo publishes is public; `imagetools inspect` against a
# public repo needs no credential, and this script must run for a contributor who has never touched
# Docker Hub auth. If it ever needs a login to pass, that is itself a bug in what got published.
#
# NEVER RUN AGENT-SIDE, EVER — needs Docker and the network. Not part of `task check` (see its
# EXCLUDED manifest row in scripts/check.sh, same shape as open-boot-selfhost's). Run by a human
# (`task check:selfhost:images`), by the release rehearsal before its first timed command —
# "before the clock starts", so a broken pull fails as a broken pull, not a slow boot — and by
# `.github/workflows/boot-checks.yml`.
#
# WHAT IT CHECKS, per image reference:
#   (a) `docker buildx imagetools inspect <ref>` resolves at all (no login).
#   (b) the manifest's `org.opencontainers.image.version` label equals the tag's own version
#       suffix — an image tagged `backend-0.1.0` that reads `version=dev` is a failed publish
#       wearing a passing tag.
#   (c) the manifest lists an entry for every architecture EXPECTED (amd64 + arm64) — a
#       single-arch manifest under a tag the docs claim is multi-arch is silent breakage for
#       exactly the half of self-hosters running the other architecture.
#
# WHICH REFERENCES: every image `docker compose config` resolves for docker-compose.yml's default
# profile (backend, frontend, sandbox-runner), PLUS AGENT_IMAGE — an env value, not a compose
# `image:` key, so `docker compose pull` never fetches it, but it is still a documented pull
# instruction (covering it is required for exactly that reason: an env-var image
# reference "must resolve" is a claim just like a compose `image:` line is, and the difference in
# mechanism is not a difference in whether it needs checking).
#
# EACH OF THE FOUR IS CHECKED TWICE, at `<service>-<version>` and at `<service>-latest`, because
# both are load-bearing now and they fail differently. The repository holds no version literal —
# the git tag release.yml pushes is the only source of truth — so a clone with TESSARY_VERSION
# unset pulls `-latest`, and a `-latest` that finalize failed to repoint is a stale image
# nothing else in this repo can see. The pinned reference is what a `.env` and the published
# compose artifact name.
#
# WHERE THE EXPECTED VERSION COMES FROM: the newest `v<semver>` git tag, or `--version=<v>`. There
# is no VERSION file to read, deliberately — a file could not be read by the compose defaults or
# server.js that actually consume the version, so it was never anything but a hand-copied second
# source of truth. Before the first release is tagged this script has nothing to check and says so.
#
# MUST GO RED ON A MISTYPED TAG — proven, not asserted: run with
#   SELFHOST_IMAGES_SELFTEST=1 bash scripts/check-selfhost-images.sh
# which substitutes one deliberately wrong tag (a real repo, a tag that cannot exist) for the
# backend reference and asserts THIS SCRIPT reports failure and exits non-zero. See the PR body for
# a captured run of this self-test.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION=""
for arg in "$@"; do
    case "$arg" in
        --version=*) VERSION="${arg#--version=}" ;;
        *) echo "check-selfhost-images: unknown argument '$arg' (accepts --version=<semver>)" >&2; exit 2 ;;
    esac
done

if ! command -v docker >/dev/null 2>&1; then
    echo "check-selfhost-images: needs docker (buildx imagetools) — not found on PATH" >&2
    exit 1
fi

EXPECTED_ARCHES="amd64 arm64" # every published image is amd64+arm64.
fail=0

# `docker buildx imagetools inspect --raw` on a manifest LIST returns the list's own JSON, which
# has no top-level OCI labels of its own (labels live per-platform, on each child image's config).
# So this reads the labels off the FIRST child manifest's config — good enough to catch "the whole
# publish forgot IMAGE_VERSION", which is what actually happens when a build-arg is missing; a
# publish that stamped only ONE arch correctly is a scenario this script does not distinguish from
# a correct one, and is out of scope for a same-version-everywhere assumption that already holds
# for every image this repo builds (backend/Dockerfile:82-88 and friends pass the SAME
# IMAGE_VERSION build-arg to every platform in one `docker buildx build --platform a,b` call).
check_ref() {
    local ref="$1" expect_version="$2" label="$3"
    echo "--- $label: $ref"

    local raw
    if ! raw=$(docker buildx imagetools inspect "$ref" --raw 2>&1); then
        echo "FAIL: $label ($ref) does not resolve:" >&2
        echo "$raw" | sed 's/^/  /' >&2
        fail=1
        return
    fi

    local media_type
    media_type=$(printf '%s' "$raw" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("mediaType",""))' 2>/dev/null || true)
    local manifests
    manifests=$(printf '%s' "$raw" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("\n".join(m.get("platform",{}).get("architecture","") for m in d.get("manifests",[])))' 2>/dev/null || true)

    if [ -z "$manifests" ]; then
        echo "FAIL: $label ($ref) is not a manifest list — cannot assert multi-arch coverage (mediaType=$media_type)." >&2
        fail=1
        return
    fi

    for arch in $EXPECTED_ARCHES; do
        if ! printf '%s\n' "$manifests" | grep -qx "$arch"; then
            echo "FAIL: $label ($ref) has no $arch entry. Manifest lists: $(printf '%s' "$manifests" | tr '\n' ' ')" >&2
            fail=1
        fi
    done

    local child_digest
    child_digest=$(printf '%s' "$raw" | python3 -c 'import json,sys; d=json.load(sys.stdin); ms=d.get("manifests",[]); print(ms[0]["digest"] if ms else "")' 2>/dev/null || true)
    if [ -z "$child_digest" ]; then
        echo "FAIL: $label ($ref) has no child manifest to inspect for its version label." >&2
        fail=1
        return
    fi

    local repo="${ref%%:*}"
    local actual_version
    actual_version=$(docker buildx imagetools inspect "${repo}@${child_digest}" --format '{{index .Image.Config.Labels "org.opencontainers.image.version"}}' 2>/dev/null || true)
    if [ "$actual_version" != "$expect_version" ]; then
        echo "FAIL: $label ($ref) org.opencontainers.image.version=\"$actual_version\", expected \"$expect_version\" (the tag's own version)." >&2
        fail=1
    else
        echo "OK: resolves, ${#EXPECTED_ARCHES} arches present, version=$actual_version"
    fi
}

if [ -z "$VERSION" ]; then
    VERSION="$(git tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | head -n1)"
    VERSION="${VERSION#v}"
fi
if [ -z "$VERSION" ]; then
    echo "check-selfhost-images: no released version to check. This repository has no VERSION file;" >&2
    echo "check-selfhost-images: the newest v<semver> git tag is the source of truth, and there is" >&2
    echo "check-selfhost-images: none yet — dispatch .github/workflows/release.yml first, or pass" >&2
    echo "check-selfhost-images: --version=<semver> to check a specific release." >&2
    exit 1
fi
echo "check-selfhost-images: checking version $VERSION (from ${1:-the newest v<semver> git tag})"
DOCKER_REPO="tessaryai/tessary"

# Mirrors the exact defaults docker-compose.yml resolves — kept literal here rather than
# shelling out to `docker compose config`, so this script has no dependency on a rendered .env; if
# these two ever drift, check-open-boundary.sh's own docker-compose.yml assertions are a faster
# place to catch it than a registry probe.
BACKEND_REF="${DOCKER_REPO}:backend-${VERSION}"
FRONTEND_REF="${DOCKER_REPO}:frontend-${VERSION}"
SANDBOX_RUNNER_REF="${DOCKER_REPO}:sandbox-runner-${VERSION}"
AGENT_REF="${DOCKER_REPO}:agent-sandbox-${VERSION}"

if [ "${SELFHOST_IMAGES_SELFTEST:-0}" = "1" ]; then
    echo "SELFHOST_IMAGES_SELFTEST=1: substituting a deliberately mistyped tag for backend." >&2
    BACKEND_REF="${DOCKER_REPO}:backend-0.0.0-does-not-exist"
fi

check_ref "$BACKEND_REF" "$VERSION" "backend"
check_ref "$FRONTEND_REF" "$VERSION" "frontend"
check_ref "$SANDBOX_RUNNER_REF" "$VERSION" "sandbox-runner"
check_ref "$AGENT_REF" "$VERSION" "agent-sandbox (AGENT_IMAGE — not a compose image: key, still a documented pull, D10)"

# The floating half. `<service>-latest` is what an unset TESSARY_VERSION resolves to in a clone, so
# a `-latest` still carrying an older release's version label is exactly the stale-pin failure this
# design was changed to make impossible — checked here, where a registry can actually say so.
if [ "${SELFHOST_IMAGES_SELFTEST:-0}" != "1" ]; then
    for service in backend frontend sandbox-runner agent-sandbox; do
        check_ref "${DOCKER_REPO}:${service}-latest" "$VERSION" "${service}-latest (the clone default)"
    done
fi

if [ "${SELFHOST_IMAGES_SELFTEST:-0}" = "1" ]; then
    if [ "$fail" -eq 0 ]; then
        echo "SELFTEST FAILED: a mistyped tag was expected to fail this script, and it did not." >&2
        exit 1
    fi
    echo "SELFTEST OK: the mistyped tag correctly failed this script (see FAIL lines above)." >&2
    exit 0
fi

if [ "$fail" -ne 0 ]; then
    echo "check-selfhost-images: FAILED — see FAIL lines above." >&2
    exit 1
fi
echo "check-selfhost-images: all four published image references resolve, version-stamped, multi-arch."
