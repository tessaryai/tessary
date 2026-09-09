#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Publish docker-compose.yml as the OCI artifact behind the one-command install:
#
#   docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
#
# Two tags per registry, and they are two different promises:
#   compose            the floating one the marketing site publishes. Someone who does not want to
#                      think about versions runs this and gets the newest release.
#   compose-<version>  the pinned one. Someone who wants the same stack next month names it.
# BOTH point at a file whose TESSARY_VERSION default is this release's version, so neither is a
# "latest images" reference — a floating COMPOSE tag still resolves to PINNED image tags. That is
# the whole ordering guarantee: the config a caller gets can name images from this release or a
# newer one, never an older one.
#
# NEITHER TAG CAN SHADOW AN IMAGE TAG. `tessaryai/tessary` publishes images as
# `<service>-<version>` and `<service>-latest` (release.yml's merge-manifests and finalize jobs);
# `compose` and
# `compose-<version>` collide with neither, and this script refuses to run if a tag it is about to
# write already resolves as an image manifest.
#
# WHAT IS PUBLISHED IS A TRANSFORMED COPY, not docker-compose.yml itself. TWO transforms, in order:
#   1. BUILD-STRIPPED — scripts/lib/strip-compose-build.py. Publish runs `docker push` for every
#      service with a `build:` block, which from this runner would push local images over the
#      release's own manifests.
#   2. VERSION-PINNED — scripts/lib/pin-compose-version.py. The repository holds NO version
#      literal (the git tag release.yml pushes is the only source of truth), so docker-compose.yml's
#      image defaults float to `${TESSARY_VERSION:-latest}` and this step stamps THIS release's
#      version into each of them. That is what keeps both promises above true: a `compose` tag
#      hands a caller a config pinned to its own release, from a file that carries no version at
#      all. Skipping it would publish a floating config, which is the one thing the ordering
#      guarantee cannot survive.
# scripts/check-compose-artifact.sh asserts that each transform changes what it claims to change
# and nothing else, so the artifact cannot drift from the file in the repository.
#
# WHERE THE VERSION COMES FROM: `--version=<v>`, which release.yml passes from its own dispatch
# input. With no flag this script reads the newest `v<semver>` git tag, so a human running it by
# hand against an already-tagged release does not have to retype the number. There is no VERSION
# file to read, deliberately.
#
# --with-env IS NEVER PASSED. It would bake the PUBLISHER'S .env into a public artifact.
#
#   bash scripts/publish-compose-artifact.sh                       Docker Hub, version from the newest git tag
#   bash scripts/publish-compose-artifact.sh --repo=<ref>          one repository (the rehearsal's use)
#   bash scripts/publish-compose-artifact.sh --version=<v>         override the version suffix
#   bash scripts/publish-compose-artifact.sh --dry-run             print what it would publish
#
# PUSHED WITH ORAS, NOT `docker compose publish`. What ships is a plain OCI artifact — one YAML
# blob, media type application/vnd.docker.compose.file+yaml, under artifact type
# application/vnd.docker.compose.project, with an empty config descriptor. oras writes exactly that
# shape (verified against a real published artifact, manifest field for manifest field) and is
# built for scripts: it takes no input, asks nothing, and its exit code is its own.
#
# `docker compose publish` is a convenience wrapper aimed at a human at a terminal, and every one
# of its human affordances cost this repository a release. It prompts to confirm the file's bind
# mount declaration (the docker socket) SEPARATELY from `-y`; on a runner that prompt reads EOF,
# answers No, and the command EXITS 0 HAVING WRITTEN NOTHING — run 34336599569 reported publishing
# two tags that did not exist. Feeding it `yes` fixed that and broke the next release a different
# way: compose closes stdin once it has its answers, `yes` takes SIGPIPE, and `set -o pipefail`
# turned a successful publish into a failed job, so run 34337898320 rolled back an artifact it had
# just written correctly. Neither failure is reachable from a tool with no prompts.
#
# THE REGISTRY STILL HAS THE LAST WORD. oras exiting 0 is not taken as evidence either; the check
# after the push loop asks the registry whether each ref is really there and really is a compose
# artifact. That check is why switching publishers is safe to do: release.yml writes the pinned tag
# and renders it back BEFORE the commit point, so a publisher that produced something Compose
# cannot read fails the run while everything is still reversible.
#
# Requires a registry login for each repository it writes — oras reads the same
# ~/.docker/config.json that `docker login` and docker/login-action write. NEVER RUN AGENT-SIDE.
set -euo pipefail
P=publish-compose-artifact
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The newest release tag, `v` stripped — overridden by --version below, which is what release.yml
# passes (its own run is what CREATES the tag, so it cannot read one).
VERSION="$(git -C "$ROOT" tag --list 'v[0-9]*' --sort=-v:refname 2>/dev/null | head -n1)"
VERSION="${VERSION#v}"
REPOS=()
DRY_RUN=0
# WHICH OF THE TWO TAGS TO WRITE. They are not interchangeable: `compose-<version>` is new on every
# release and can be deleted again, while `compose` is a FLOATING ALIAS that already points at the
# previous release and cannot be rolled back by deleting it. release.yml therefore writes the
# pinned one before it commits (so a publish that does not work fails while everything is still
# reversible) and the floating one only in finalize, alongside `<service>-latest`. `both` stays the
# default so a human running this by hand against an already-tagged release gets both.
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
    # Docker Hub only. The GHCR mirror was dropped from the release path (see release.yml's
    # header): a second registry that can fail independently of the first is a second way to
    # publish half a release, and it was doing exactly that.
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

# The file has to be publishable before anything is pushed anywhere; a red here is a repository
# problem, not a registry one, and it should read as one.
bash "$ROOT/scripts/check-compose-artifact.sh"

# Both transforms write BESIDE docker-compose.yml, not into a temp directory: Compose resolves
# relative paths against the file's own directory, so a copy elsewhere renders differently.
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

# EVERY tag is checked before ANY tag is written. Checking inside the publish loop would let a
# refusal on the second reference land after the first was already pushed, which is a partial
# release — and the whole point of this check is that a mistake here damages the published images.
echo "$P: checking all ${#REFS[@]} target tag(s) before writing any of them"
for ref in "${REFS[@]}"; do
    # An artifact tag must never be sitting on top of an image. `imagetools inspect --raw` on a
    # compose artifact returns a manifest that names the compose media type; an IMAGE there means
    # the tag namespace was mixed up and publishing would replace it.
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

# THE FILE IS COPIED IN UNDER ITS PUBLISHED NAME. oras derives the layer's
# `org.opencontainers.image.title` from the path it is given, so pushing `.compose-artifact-*.yml`
# would stamp this publisher's scratch filename into a public artifact. Pushing `docker-compose.yml`
# from a directory of its own is what makes the annotation read like the file a consumer gets.
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

# PUBLISHING IS NOT BELIEVED, IT IS CHECKED. The exit code above has already been observed to be 0
# for a publish that silently did nothing, so the only trustworthy evidence is the registry saying
# the tag is there and is a compose artifact.
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
