# Launcher sidecar image. Holds the E2B SDK + the E2B_API_KEY (env); never runs
# untrusted grader code itself — it only orchestrates microVMs.
#
# Node 22, not 20: pnpm 11 requires node >=22.13 and dies on 20 with ERR_UNKNOWN_BUILTIN_MODULE.
# Matches the nodejs22.x Lambda runtime; server.js is plain ESM-free CommonJS over the e2b SDK,
# with nothing 20-specific to lose.
#
# ALPINE, not Debian. Every CRITICAL this image carried was an unfixable Debian package:
# bookworm gave 4 (zlib1g CVE-2023-45853, "will_not_fix", plus 3 perl-base), trixie cleared the
# zlib one and left the 3 perl CVEs — CVE-2026-13221 / -42496 / -8376, which Debian's own tracker
# marks `vulnerable` on EVERY release, fixed only in forky/sid, and rates "Minor issue". perl-base
# is Essential=yes so it cannot be removed from a Debian image at all. Alpine simply has no perl.
# Measured on the built image, not assumed: 3 CRITICAL -> 0, and 524 MB -> 402 MB. Verified beyond
# the scan too — the container boots and answers /healthz with 200, and `e2b` imports.
# Nothing here is exposed to musl risk: the sole runtime dependency (e2b) is pure JS, there is no
# native addon, no python and no compiler. This matches sandbox-runner/agent-sandbox/Dockerfile
# and its template.ts twin, which moved to Alpine at the same time.
# NOTE this is no longer classify-service's base (that one is still node:22-slim); it was, and the
# divergence is deliberate, not drift.
FROM node:22-alpine3.24

WORKDIR /app
# Global install rather than corepack — see frontend/Dockerfile. PNPM_VERSION comes from the build
# orchestrator (Taskfile `PNPM_VERSION` locally; the `PNPM_VERSION` Actions variable in CI); the
# default is the same pin, held equal by scripts/check-required-inputs.sh (#1190).
ARG PNPM_VERSION=11.15.1
# npm@12.0.2 pinned alongside pnpm to clear CVE-2026-59873 (node-tar, bundled inside npm's own
# node_modules, not an app dependency): the base's stock npm 10.9.8 bundles tar 7.5.11, and no npm
# release before 12.0.0 bundles the fixed tar 7.5.19. npm is only a build-time bootstrap here
# (never invoked again — the line below installs with pnpm, and CMD is `node server.js`), so this
# has no runtime effect. The cache clean is size only: npm otherwise leaves ~38 MB in ~/.npm that
# nothing at runtime reads.
#
# The agent-sandbox recipes fix the SAME CVE a different way (they overwrite npm's vendored tar in
# place and leave npm at 10.9.8) and that asymmetry is deliberate: npm >=12 defaults `allowScripts`
# off, so under npm 12 `npm install re2@^1.26.1` completes with a warning but never runs re2's
# node-gyp build, leaving a package that throws MODULE_NOT_FOUND on require. Verified directly on
# node:22-trixie-slim and again on node:22-alpine3.24. This image installs with pnpm and compiles no native addon, so npm 12 is
# safe HERE and only here. Do not copy this line into agent-sandbox.
RUN npm i -g npm@12.0.2 pnpm@${PNPM_VERSION} \
    && npm cache clean --force
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --prod
# (No pnpm-workspace.yaml for this package — it has no settings to carry: e2b ships no build scripts
# and needs no overrides.)

COPY server.js ./

# The image's version identity (epic 5 clause 3, #1135; extended here per epic 7 clause 1, #941);
# same convention and same "dev" fallback as backend/Dockerfile, so the release workflow's OCI
# label assertion has something to check on every published image, not just backend/frontend.
# Written to a file as well as a LABEL for the same reason backend/Dockerfile gives: a derived
# image can COPY a file from its base and cannot read its base's labels.
ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION
RUN printf '%s\n' "$IMAGE_VERSION" > /app/IMAGE_VERSION

ENV PORT=8080
EXPOSE 8080
USER node
CMD ["node", "server.js"]
