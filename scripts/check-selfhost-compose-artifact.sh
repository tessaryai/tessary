#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The one-command install, end to end, against a real registry (the one-command install).
#
# scripts/check-compose-artifact.sh is the cheap gate: it reads docker-compose.yml and asserts the
# properties a publishable file must have. This is the expensive one, and it is the only thing that
# can answer the question the marketing site's hero actually makes — does
#
#   docker compose -f oci://<registry>/tessaryai/tessary:compose up -d -y
#
# produce a running Tessary for someone who has cloned nothing? It publishes the artifact to a
# throwaway registry on this host, then runs that exact command shape from an EMPTY directory with
# no .env, no repository, and no build context, and asserts:
#
#   (a) the artifact resolves over oci:// and renders
#   (b) the rendered config names this release's PINNED image tags, never a -latest alias
#   (c) no service the default profile starts mounts a host path
#   (d) `up -d` reaches `healthy` for postgres, backend, frontend and sandbox-runner
#   (e) the frontend answers on the published port
#   (f) THE WORKING DIRECTORY IS STILL EMPTY — the install's own promise, and the one thing the
#       old ${PWD}/.data and ./.local binds silently broke
#   (g) `down -v` removes the named volumes, so the host is back where it started
#
# WHY A LOCAL REGISTRY WITH TLS AND NOT PLAIN HTTP: `docker compose publish` speaks HTTPS to
# everything, localhost included, and honours neither an insecure-registries setting nor
# DOCKER_CONFIG/certs.d — measured, not assumed. It DOES honour SSL_CERT_FILE, which Go's x509
# reads on Linux but not on macOS, so every publish and consume below runs inside a `docker:cli`
# container (Linux) with the host's Docker socket mounted. That also pins the Compose version doing
# the publishing, rather than inheriting whatever the operator has.
#
# IMAGES: the four release images do not exist on any registry until a human dispatches
# release.yml, so this script BUILDS them locally under the exact tags the STAMPED artifact names
# (it exports TESSARY_VERSION, which is what the repository's floating defaults resolve against)
# and lets Compose's default `missing` pull policy find them. That is a fair substitute for a
# published pull — the artifact, the tags and the resolution path are the real ones — and it is
# what makes this runnable before the first release. It is NOT a substitute for
# scripts/check-selfhost-images.sh, which asks the real registry whether the real tags resolve.
#
# NEVER RUN AGENT-SIDE, EVER — needs Docker, builds four images and boots a stack. Run by a human
# (`task check:selfhost:compose`) or .github/workflows/boot-checks.yml. Not part of
# `task check`; see its EXCLUDED manifest row in scripts/check.sh.
set -euo pipefail
P=check-selfhost-compose-artifact
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v docker >/dev/null 2>&1 || { echo "$P: needs docker" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "$P: the Docker daemon is not reachable" >&2; exit 1; }

# The version this rehearsal pretends to publish. There is no VERSION file: the git tag
# release.yml pushes is the only source of truth, and before the first release there is no tag
# either — so fall back to a value that is obviously not a release. What matters here is only that
# the same version is used to BUILD the images, to STAMP the artifact and to ASSERT the pin, which
# is exactly the three-way agreement a real release has.
VERSION="$(git tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | head -n1)"
VERSION="${VERSION#v}"
VERSION="${VERSION:-0.0.0-rehearsal}"
# docker-compose.yml's image defaults FLOAT (`${TESSARY_VERSION:-latest}`), so exporting this is
# what makes `docker compose build` below tag the images under the pinned names the stamped
# artifact will go on to name. Without it the build produces `-latest` tags and the pinned artifact
# resolves nothing.
export TESSARY_VERSION="$VERSION"
HTTP_PORT="${COMPOSE_ARTIFACT_HTTP_PORT:-18100}"
HTTPS_PORT="${COMPOSE_ARTIFACT_HTTPS_PORT:-18543}"
REG_NAME="tessary-compose-artifact-registry-$$"
REG_PORT="${COMPOSE_ARTIFACT_REGISTRY_PORT:-5599}"
# host.docker.internal resolves to the host from inside a container on Docker Desktop, and is added
# explicitly with --add-host on Linux, so one registry hostname works on both.
REG_HOST="host.docker.internal:${REG_PORT}"
REPO="${REG_HOST}/tessaryai/tessary"
# COMPOSE_PROJECT_NAME outranks the file's `name: tessary`, so this never touches a
# developer's own stack or its volumes.
PROJECT="compose-artifact-$$"
# The one volume COMPOSE_PROJECT_NAME does not isolate: docker-compose.yml gives `sandbox-work` an
# explicit `name:` because the launcher hands that exact string to the Engine API. Run-scoped, so
# this rehearsal's `down -v` cannot reach a developer's own launcher volume.
WORK_VOLUME="sandbox-work-${PROJECT}"
WORK="$(mktemp -d)"
CERTS="$WORK/certs"
# The install's own promise is about the directory the operator runs the command in. This one is
# created empty and asserted empty afterwards.
EMPTY_DIR="$WORK/empty"
mkdir -p "$CERTS" "$EMPTY_DIR"

_dc() {
    # Every compose invocation runs inside docker:cli so SSL_CERT_FILE is honoured (see the header).
    docker run --rm \
        -v /var/run/docker.sock:/var/run/docker.sock \
        -v "$WORK:$WORK" -v "$ROOT:$ROOT" \
        --add-host host.docker.internal:host-gateway \
        -e SSL_CERT_FILE="$CERTS/reg.crt" \
        -e COMPOSE_PROJECT_NAME="$PROJECT" \
        -e HTTP_PORT="$HTTP_PORT" -e HTTPS_PORT="$HTTPS_PORT" \
        -e SANDBOX_WORK_VOLUME="$WORK_VOLUME" \
        -w "$1" docker:cli sh -c "$2"
}

_cleanup() {
    local status=$?
    echo "$P: tearing down…"
    _dc "$EMPTY_DIR" "docker compose -p $PROJECT down -v --remove-orphans" >/dev/null 2>&1 || true
    docker rm -f "$REG_NAME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
    exit "$status"
}
trap _cleanup EXIT

echo "$P: (0) building the four images under the tags docker-compose.yml resolves…"
docker compose -f docker-compose.yml build >/dev/null
# agent-sandbox is not a compose service — the launcher pulls it over docker.sock at run time — so
# it is built by name here, matching AGENT_IMAGE's default.
docker build -q -f sandbox-runner/agent-sandbox/Dockerfile -t "tessaryai/tessary:agent-sandbox-${VERSION}" . >/dev/null

echo "$P: (0) starting a throwaway TLS registry on :${REG_PORT}…"
openssl req -newkey rsa:2048 -nodes -keyout "$CERTS/reg.key" -x509 -days 1 -out "$CERTS/reg.crt" \
    -subj "/CN=tessary-compose-artifact-registry" \
    -addext "subjectAltName=DNS:host.docker.internal,IP:127.0.0.1" >/dev/null 2>&1
docker run -d --name "$REG_NAME" -p "${REG_PORT}:5000" \
    -v "$CERTS:/certs" \
    -e REGISTRY_HTTP_TLS_CERTIFICATE=/certs/reg.crt \
    -e REGISTRY_HTTP_TLS_KEY=/certs/reg.key \
    registry:2 >/dev/null
for _ in $(seq 1 30); do
    curl -fsS --cacert "$CERTS/reg.crt" "https://127.0.0.1:${REG_PORT}/v2/" >/dev/null 2>&1 && break
    sleep 1
done

echo "$P: (0) publishing the artifact…"
# BOTH transforms a real release applies, in the same order scripts/publish-compose-artifact.sh
# applies them: strip the build sections, then stamp this version into the floating image defaults.
# Skipping the stamp would publish a floating config and assertion (b) below would be checking a
# file no release ever produces.
python3 "$ROOT/scripts/lib/strip-compose-build.py" docker-compose.yml "$ROOT/.compose-artifact-rehearsal.yml"
python3 "$ROOT/scripts/lib/pin-compose-version.py" \
    "$ROOT/.compose-artifact-rehearsal.yml" "$ROOT/.compose-artifact-rehearsal.pinned.yml" "$VERSION"
mv "$ROOT/.compose-artifact-rehearsal.pinned.yml" "$ROOT/.compose-artifact-rehearsal.yml"
trap 'rm -f "$ROOT/.compose-artifact-rehearsal.yml" "$ROOT/.compose-artifact-rehearsal.pinned.yml"; _cleanup' EXIT
_dc "$ROOT" "docker compose -f .compose-artifact-rehearsal.yml publish -y ${REPO}:compose"

fail=0
OCI="oci://${REPO}:compose"

echo "$P: (a) the artifact resolves over oci:// and renders"
if ! rendered="$(_dc "$EMPTY_DIR" "docker compose -f $OCI config" 2>&1)"; then
    echo "$P: RED  the published artifact does not render:" >&2
    printf '%s\n' "$rendered" | sed 's/^/      /' >&2
    exit 1
fi
echo "$P: ok   $OCI renders from an empty directory with no .env"

echo "$P: (b) the rendered config names this release's pinned image tags"
for service in backend frontend sandbox-runner; do
    if printf '%s\n' "$rendered" | grep -qF "tessaryai/tessary:${service}-${VERSION}"; then
        echo "$P: ok   ${service} -> tessaryai/tessary:${service}-${VERSION}"
    else
        echo "$P: RED  the artifact does not pin ${service} to tessaryai/tessary:${service}-${VERSION}" >&2; fail=1
    fi
done
if printf '%s\n' "$rendered" | grep -qE 'tessaryai/tessary:[a-z-]+-latest'; then
    echo "$P: RED  the artifact names a -latest image alias; a published config must pin" >&2; fail=1
else
    echo "$P: ok   no -latest alias anywhere in the rendered config"
fi

echo "$P: (c) no service the default profile starts mounts a host path"
if printf '%s\n' "$rendered" | grep -E '^ +source: /' | grep -vE 'source: /(var/run/docker.sock|proc|sys)$' >/dev/null; then
    echo "$P: RED  the rendered remote config carries a host-path bind:" >&2
    printf '%s\n' "$rendered" | grep -E '^ +source: /' | sed 's/^/      /' >&2; fail=1
else
    echo "$P: ok   every mount is a named volume (or the docker socket)"
fi

echo "$P: (d) up -d -y from the empty directory, and every service reaches healthy"
# -y as published, not as a scripted convenience: the prompt it answers is TTY-gated, so this
# leg passed without it and could not have caught the flag going missing from the docs.
if ! _dc "$EMPTY_DIR" "docker compose -f $OCI up -d -y"; then
    echo "$P: RED  'docker compose -f $OCI up -d -y' failed" >&2
    _dc "$EMPTY_DIR" "docker compose -p $PROJECT ps -a" >&2 || true
    exit 1
fi
deadline=$((SECONDS + 400))
while :; do
    states="$(_dc "$EMPTY_DIR" "docker compose -p $PROJECT ps --format '{{.Service}} {{.Health}}'" 2>/dev/null || true)"
    unhealthy=0
    for svc in postgres backend frontend sandbox-runner; do
        printf '%s\n' "$states" | grep -qx "$svc healthy" || unhealthy=1
    done
    [ "$unhealthy" = 0 ] && break
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "$P: RED  not every service reached healthy within the budget. State:" >&2
        printf '%s\n' "$states" | sed 's/^/      /' >&2
        _dc "$EMPTY_DIR" "docker compose -p $PROJECT logs --no-color --tail=40 backend" >&2 || true
        fail=1
        break
    fi
    sleep 5
done
[ "$fail" = 0 ] && echo "$P: ok   postgres, backend, frontend and sandbox-runner are all healthy"

echo "$P: (e) the frontend answers on :${HTTP_PORT}"
if curl -fsS -o /dev/null --max-time 10 "http://localhost:${HTTP_PORT}/"; then
    echo "$P: ok   http://localhost:${HTTP_PORT}/ serves the app"
else
    echo "$P: RED  http://localhost:${HTTP_PORT}/ did not answer" >&2; fail=1
fi

echo "$P: (f) the working directory is still empty"
leftovers="$(ls -A "$EMPTY_DIR")"
if [ -n "$leftovers" ]; then
    echo "$P: RED  the install wrote into the operator's working directory:" >&2
    printf '%s\n' "$leftovers" | sed 's/^/      /' >&2; fail=1
else
    echo "$P: ok   nothing was written beside the command"
fi

echo "$P: (g) down -v removes the stack's named volumes"
_dc "$EMPTY_DIR" "docker compose -p $PROJECT down -v --remove-orphans" >/dev/null
for vol in "${PROJECT}_tessary-postgres-data" "$WORK_VOLUME"; do
    if docker volume inspect "$vol" >/dev/null 2>&1; then
        echo "$P: RED  volume '$vol' survived 'down -v'" >&2; fail=1
    else
        echo "$P: ok   volume '$vol' is gone"
    fi
done

[ "$fail" = 0 ] || { echo "$P: FAILED — see above." >&2; exit 1; }
echo "$P: the published artifact installs Tessary from an empty directory with nothing cloned," \
     "pins this release's images, writes nothing outside Docker, and tears down clean."
