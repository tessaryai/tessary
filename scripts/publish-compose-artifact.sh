#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Publishes docker-compose.yml as the OCI artifact behind
# docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
# Tags: `compose` (floating) and `compose-<version>` (pinned); both carry this release's pinned image
# defaults. Refuses a tag that already resolves to an image. The file is build-stripped
# (strip-compose-build.py, or publish would push local images) then version-pinned (pin-compose-version.py).
# Version: --version=<v>, else the newest `v<semver>` tag. --with-env is never passed: it would publish
# the publisher's .env.
# bash scripts/publish-compose-artifact.sh [--repo=<ref>] [--version=<v>] [--dry-run]
# Pushed with oras, not `docker compose publish`, whose prompts exited 0 having written nothing on a runner.
# The registry is checked after the push. Needs a registry login. NEVER RUN AGENT-SIDE.
set -euo pipefail
P=publish-compose-artifact
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Overridden by --version, which release.yml passes since its own run creates the tag.
VERSION="$(git -C "$ROOT" tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | head -n1)"
VERSION="${VERSION#v}"
REPOS=()
DRY_RUN=0
# `compose-<version>` is deletable; `compose` is a floating alias that cannot be rolled back. release.yml
# writes the pinned tag before finalize and the floating one in finalize. `both` is for manual runs.
TAGS=both
for arg in "$@"; do
    case "$arg" in
        --repo=*) REPOS+=("${arg#--repo=}") ;;
        --version=*) VERSION="${arg#--version=}" ;;
        --tags=pinned|--tags=floating|--tags=both) TAGS="${arg#--tags=}" ;;
        --tags=*) echo "$P: --tags takes pinned, floating or both" >&2; exit 2 ;;
        --dry-run) DRY_RUN=1 ;;
        *) echo "$P: unknown argument '$arg'" >&2; exit 2 ;;
    esac
done
if [ "${#REPOS[@]}" -eq 0 ]; then
    # Docker Hub only: a second registry is a second way to publish half a release.
    REPOS=("docker.io/tessaryai/tessary")
fi
[ -n "$VERSION" ] || {
    echo "$P: no version. Pass --version=<semver>, or tag a release first — this repository has no" >&2
    echo "$P: VERSION file, and the newest v<semver> git tag is what this reads when you don't." >&2
    exit 1
}

ORAS="${ORAS:-$(command -v oras || true)}"
[ -n "$ORAS" ] || {
    echo "$P: oras is not on PATH. It is what pushes the artifact (see the header)." >&2
    echo "$P: Install a checksum-verified copy with:" >&2
    echo "$P:   ORAS=\"\$(bash scripts/lib/install-oras.sh \"\$HOME/.local/bin\")\"" >&2
    exit 1
}

bash "$ROOT/scripts/check-compose-artifact.sh"

# Beside docker-compose.yml, because Compose resolves relative paths against the file's directory.
STRIPPED="$ROOT/.compose-artifact-publish.yml"
PINNED="$ROOT/.compose-artifact-publish.pinned.yml"
PUSHDIR="$(mktemp -d)"
trap 'rm -f "$STRIPPED" "$PINNED"; rm -rf "$PUSHDIR"' EXIT
python3 "$ROOT/scripts/lib/strip-compose-build.py" docker-compose.yml "$STRIPPED"
python3 "$ROOT/scripts/lib/pin-compose-version.py" "$STRIPPED" "$PINNED" "$VERSION"
mv "$PINNED" "$STRIPPED"

case "$TAGS" in
    pinned)   WANT=("compose-${VERSION}") ;;
    floating) WANT=("compose") ;;
    both)     WANT=("compose" "compose-${VERSION}") ;;
esac
REFS=()
for repo in "${REPOS[@]}"; do
    for tag in "${WANT[@]}"; do
        REFS+=("${repo}:${tag}")
    done
done

# Every tag is checked before any is written, so a refusal cannot follow a partial push.
echo "$P: checking all ${#REFS[@]} target tag(s) before writing any of them"
for ref in "${REFS[@]}"; do
    # An artifact tag must never sit on top of an image manifest.
    if raw=$(docker buildx imagetools inspect "$ref" --raw 2>/dev/null); then
        if printf '%s' "$raw" | grep -q 'application/vnd.docker.compose.file+yaml'; then
            echo "$P: ok       $ref already holds a compose artifact; it will be replaced (expected on a re-release)"
        else
            echo "$P: REFUSING — $ref already resolves and is NOT a compose artifact." >&2
            echo "       Publishing would overwrite an image manifest. Nothing has been pushed." >&2
            exit 1
        fi
    else
        echo "$P: ok       $ref does not exist yet"
    fi
done

# Under its published name, since oras stamps the file name into the layer's title annotation.
cp "$STRIPPED" "$PUSHDIR/docker-compose.yml"
COMPOSE_VERSION="$(docker compose version --short 2>/dev/null || echo unknown)"
cat > "$PUSHDIR/annotations.json" <<JSON
{
  "docker-compose.yml": {
    "com.docker.compose.file": "docker-compose.yml",
    "com.docker.compose.version": "${COMPOSE_VERSION}"
  }
}
JSON

for ref in "${REFS[@]}"; do
    if [ "$DRY_RUN" = 1 ]; then
        echo "$P: would publish $ref"
        continue
    fi
    echo "$P: publishing $ref"
    ( cd "$PUSHDIR" && "$ORAS" push "$ref" \
        --artifact-type application/vnd.docker.compose.project \
        --annotation-file annotations.json \
        docker-compose.yml:application/vnd.docker.compose.file+yaml )
done

[ "$DRY_RUN" = 1 ] && exit 0

# Checked, not believed: a publish has exited 0 having done nothing.
for ref in "${REFS[@]}"; do
    if raw=$(docker buildx imagetools inspect "$ref" --raw 2>/dev/null) \
       && printf '%s' "$raw" | grep -q 'application/vnd.docker.compose.file+yaml'; then
        echo "$P: ok       $ref is published and is a compose artifact"
    else
        echo "$P: FAILED — $ref did not publish; the registry does not hold a compose artifact there." >&2
        exit 1
    fi
done
echo "$P: published ${WANT[*]} to: ${REPOS[*]}"
