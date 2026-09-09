#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Built-artifact NOTICE coverage check: legal and security sign-off on what a real build actually
# ships.
#
# The gate this script is one leg of: "the NOTICE file's attribution list is verified against the
# actual filesystem contents of each built artifact, not the git tree." A dependency manifest
# (backend/pom.xml, frontend/package.json) says what should resolve; it does not prove what a real
# build actually bakes into the image, a transitive jar Maven's own resolver adds, a font binary
# Vite copies into dist, a base-image package's own bundled license file. Only the artifact itself
# can answer that, so this script builds it and looks.
#
# SCOPE: this script's own backend and frontend images only. It never reads or names any other
# artifact's build directory, since doing so would trip check-open-boundary.sh rule 5 (a script
# under scripts/ naming that path is a red `task check` in the public export).
#
# WHAT THIS DOES.
#   1. Builds the open backend and frontend images from docker-compose.yml, the same build
#      definitions `docker compose up` uses in production and check-open-boot-selfhost.sh proves
#      boot with. Not reinvented here. The tags they land under are pinned below.
#   2. `docker create` + `docker cp`, never `docker run`, pulls out exactly the layer each
#      Dockerfile actually assembles (backend: /app, the extracted Spring Boot layers plus the
#      Pyroscope agent jar; frontend: /srv, the built static bundle) without starting either
#      container or needing a database, a JDBC URL, or any of the other env `up` would demand.
#   3. Backend: every *.jar found under the extracted layers is opened as a zip and checked for a
#      top-level or META-INF NOTICE-shaped entry. A jar that ships one is a real, unarguable
#      attribution-forwarding obligation (Apache License 2.0 §4(d)); its presence is compared
#      against the root NOTICE file by a keyword derived from the jar's own filename. A hit with no
#      corresponding NOTICE mention is a FAILURE: the exact "present in the artifact, absent from
#      NOTICE" case the gate text names.
#   4. Frontend: the built dist is scanned for font binaries (the bundled Geist / Geist Mono /
#      Space Grotesk variable fonts, plus Lucide's icons) and for the license text that has to
#      travel with them. Both halves are failures, not notes: the fonts must be named in
#      NOTICE, and their license text must physically be in the image (SIL OFL 1.1 §2 / ISC).
#      frontend/scripts/copy-dep-licenses.mjs writes the texts into dist/licenses/ during
#      `pnpm run build`, which is what makes the second half enforceable.
#
# WHAT THIS DOES NOT PROVE: READ BEFORE TRUSTING A GREEN RUN.
#   - It does not resolve a full dependency graph. A jar's presence is discovered by walking the
#     files the Dockerfile's own COPY actually produced, not by cross-referencing Maven's resolved
#     classpath; a jar with no NOTICE entry of its own is silently fine here even if its license
#     has some OTHER forwarding obligation this script does not know how to detect (that gap is
#     exactly what NOTICE's own "NOT INDEPENDENTLY VERIFIED" section names).
#   - The keyword match is a case-insensitive substring test against the jar's own base filename
#     (stripped of version and extension), a real but blunt instrument. A NOTICE entry filed under
#     a different name than the jar (e.g. the aggregate "AWS SDK for Java" NOTICE covering a
#     `software.amazon.awssdk:*` jar whose filename doesn't literally contain "aws") can produce a
#     false FAILURE; this script's own hard-coded ALIAS map exists for exactly that reason and is
#     not exhaustive.
#   - It does not verify that a jar's NOTICE-file CONTENT matches what NOTICE actually reproduces:
#     only that some attribution obligation was found and some mention of it exists. A stale quote
#     (upstream rewords its NOTICE, ours doesn't) passes here.
#   - It does not check the base OS images (eclipse-temurin JRE, caddy:2-alpine) for their own
#     bundled package licenses/legal directories at all. Those ship real attribution content
#     (the JRE's `legal/` tree in particular) and are entirely outside this script's coverage.
#   - It checks that SOME license text travels with the fonts, not that EACH bundled package's own
#     text is among it: the assertion is "the licenses/ directory is non-empty", so dropping one of
#     the four rows from copy-dep-licenses.mjs's PACKAGES table while leaving the others would pass
#     here. That per-package correctness is enforced on the producing side instead: the script
#     fails the build if any row cannot be resolved and copied, which is the only side that knows
#     which packages the build actually pulled in.
#   - It builds and inspects one architecture (whatever `docker build` resolves to on the host
#     running this script), not a multi-arch manifest.
#
# NEEDS DOCKER AND MINUTES, same cost class as check-open-boot.sh and check-dependency-audit.sh.
# NOT wired into `task check` / scripts/check.sh's per-PR pipeline (see its EXCLUDED manifest row,
# same disposition and same reasoning as dependency-audit: this is not a per-commit gate, it is a
# tool a human runs before a release). Invoke it directly:
#   bash scripts/check-notice-coverage.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
P="check-notice-coverage"

if ! command -v docker >/dev/null 2>&1; then
    echo "$P: docker is not installed — this check needs it to build the images it inspects." >&2
    exit 1
fi
if [ ! -f NOTICE ]; then
    echo "$P: FAIL, no NOTICE file at the repo root to check the artifacts against." >&2
    exit 1
fi

TMP="$(mktemp -d)"
_cleanup() {
    local status=$?
    [ -n "${BACKEND_CID:-}" ] && docker rm -f "$BACKEND_CID" >/dev/null 2>&1 || true
    [ -n "${FRONTEND_CID:-}" ] && docker rm -f "$FRONTEND_CID" >/dev/null 2>&1 || true
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

echo "$P: building the open backend + frontend images from docker-compose.yml…"
# POSTGRES_USER/POSTGRES_PASSWORD are only read by the backend service's `environment:` block, but
# Compose interpolates the WHOLE file before deciding what to build, so their `:?must be set` guard
# fires on a bare `build` too; dummy, run-scoped values, never used for anything but parsing.
# BACKEND_IMAGE/FRONTEND_IMAGE are docker-compose.yml's own override seams for the two `image:`
# tags. Pinning them to run-scoped names is what lets `docker create` below address exactly the
# images this build just produced, without parsing the compose file for the tag it defaulted to and
# without colliding with (or clobbering) a real `tessaryai/tessary:*-<version>` a developer already
# has locally.
BACKEND_IMAGE=tessary-backend:notice-coverage-check
FRONTEND_IMAGE=tessary-frontend:notice-coverage-check

env POSTGRES_USER=notice-check POSTGRES_PASSWORD=notice-check \
    IMAGE_VERSION=notice-coverage-check \
    BACKEND_IMAGE="$BACKEND_IMAGE" FRONTEND_IMAGE="$FRONTEND_IMAGE" \
    docker compose -f docker-compose.yml build backend frontend >&2

BACKEND_CID="$(docker create "$BACKEND_IMAGE")"
FRONTEND_CID="$(docker create "$FRONTEND_IMAGE")"

mkdir -p "$TMP/backend" "$TMP/frontend"
docker cp "$BACKEND_CID:/app" "$TMP/backend/app" >/dev/null
docker cp "$FRONTEND_CID:/srv" "$TMP/frontend/srv" >/dev/null

fail=0

# ---- backend: every bundled jar, checked for its own NOTICE-shaped entry ----
echo "$P: scanning the backend image's jars for bundled NOTICE files…"

NOTICE_TEXT_LOWER="$(tr '[:upper:]' '[:lower:]' < NOTICE)"

_jars="$(find "$TMP/backend/app" -name '*.jar' -type f | sort)"
if [ -z "$_jars" ]; then
    echo "$P: FAIL, no jars found under the extracted backend image — the layer copy is empty or the image layout changed." >&2
    fail=1
fi

jar_count=0
notice_bearing=0
while IFS= read -r _jar; do
    [ -z "$_jar" ] && continue
    jar_count=$((jar_count + 1))
    base="$(basename "$_jar")"
    # strip a trailing -<version>.jar (or plain .jar) to get the artifact's bare name
    keyword="$(printf '%s' "$base" | sed -E 's/-[0-9][0-9A-Za-z.._-]*\.jar$//; s/\.jar$//' | tr '[:upper:]' '[:lower:]')"

    entry="$(unzip -l "$_jar" 2>/dev/null | awk '{print $NF}' | grep -iE '(^|/)(NOTICE)(\.(txt|md))?$' | head -1 || true)"
    [ -z "$entry" ] && continue
    notice_bearing=$((notice_bearing + 1))

    # ALIAS: a small, explicit map from artifact keyword to the string this jar's NOTICE actually
    # surfaces under in the root NOTICE file, for the cases where the two names genuinely differ
    # (the AWS SDK's NOTICE covers many `software.amazon.awssdk:*` jars whose filenames don't
    # contain "aws"; grpc-netty-shaded's forwarding obligation is filed under "netty", not its own
    # artifact name). NOT exhaustive; see the header's "WHAT THIS DOES NOT PROVE".
    case "$keyword" in
        lambda|http-auth-aws|http-auth|sdk-core|auth|regions|profiles|aws-*|*-endpoints-spi|*-json-protocol)
            check_kw="aws sdk" ;;
        grpc-netty-shaded)
            check_kw="netty" ;;
        swagger-*|*-swagger*|springdoc-*)
            check_kw="swagger" ;;
        tomcat-embed-*)
            check_kw="tomcat" ;;
        *)
            check_kw="$keyword" ;;
    esac

    if printf '%s' "$NOTICE_TEXT_LOWER" | grep -qF "$check_kw"; then
        echo "  ok           $base (NOTICE entry: $entry) — '$check_kw' is in the root NOTICE"
    else
        echo "  FINDING      $base (NOTICE entry: $entry) — '$check_kw' is NOT mentioned in the root NOTICE" >&2
        fail=1
    fi
done <<< "$_jars"
echo "$P: $jar_count jar(s) inspected, $notice_bearing carry their own NOTICE-shaped entry."

# ---- frontend: the known font-binary gap, checked for real rather than assumed ----
echo "$P: scanning the frontend image for bundled font binaries…"
_fonts="$(find "$TMP/frontend/srv" -type f \( -iname '*.woff2' -o -iname '*.woff' -o -iname '*.ttf' -o -iname '*.otf' \) | sort)"
if [ -z "$_fonts" ]; then
    echo "  none found — either the build changed and no longer bundles font binaries, or the font packages moved. Update NOTICE's font paragraph either way."
else
    font_count=$(printf '%s\n' "$_fonts" | grep -c . || true)
    echo "  $font_count font file(s) found, e.g.:"
    printf '%s\n' "$_fonts" | head -3 | sed 's/^/    /'
    if printf '%s' "$NOTICE_TEXT_LOWER" | grep -qE 'fontsource|geist'; then
        echo "  ok           NOTICE's font paragraph covers this (see NOTICE for the still-open manual-check item)"
    else
        echo "  FINDING      font binaries are bundled but NOTICE says nothing about them" >&2
        fail=1
    fi
    # SIL OFL 1.1 §2 (and ISC, for Lucide) require the copyright notice AND the license text to
    # accompany every redistributed copy of the font. The artifact being redistributed is this
    # image, so the obligation is on /srv: naming the packages in NOTICE, which the branch above
    # checks, does not discharge it. frontend/scripts/copy-dep-licenses.mjs writes the texts to
    # dist/licenses/ as the last step of `pnpm run build`, and dist becomes /srv, so an empty
    # result here means that build step did not run (or was dropped from package.json's `build`,
    # or frontend/Dockerfile stopped COPYing frontend/scripts), and the image ships font binaries
    # with no license. That is a real compliance failure, and it fails.
    _font_license="$(find "$TMP/frontend/srv" -type f \( -iname 'OFL*' -o -iname 'LICENSE*' -o -iname '*NOTICE*' \) | sort)"
    if [ -z "$_font_license" ]; then
        echo "  FINDING      font binaries are bundled but NO license text travels with them in the image — frontend/scripts/copy-dep-licenses.mjs did not run (check frontend/package.json's \`build\` and frontend/Dockerfile's \`COPY frontend/scripts\`)" >&2
        fail=1
    else
        _lic_count=$(printf '%s\n' "$_font_license" | grep -c . || true)
        echo "  ok           $_lic_count license text file(s) travel with the fonts in the image, e.g.:"
        printf '%s\n' "$_font_license" | head -4 | sed 's|^|    |'
    fi
fi

if [ "$fail" -ne 0 ]; then
    echo "$P: FAIL — see the finding(s) above." >&2
    exit 1
fi
echo "$P: ok — every NOTICE-bearing jar in the backend image is accounted for in NOTICE, and the bundled frontend fonts are both named there and shipped with their license text (see this script's header for what that does and does not prove)."
