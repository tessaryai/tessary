# Launcher sidecar image. Holds the E2B SDK + the E2B_API_KEY (env); never runs
# untrusted grader code itself — it only orchestrates microVMs.
#
# Node 24, not 22: 22 has been maintenance-only since 2025-10-21 and is EOL 2027-04-30, where 24
# runs to 2028-04-30. The floor that used to justify this line still holds and is not the reason
# for it — pnpm 11 requires node >=22.13 and dies on 20 with ERR_UNKNOWN_BUILTIN_MODULE — so
# package.json's `engines` stays at >=22.13: that is what server.js CAN run on, not what we ship.
# server.js is plain ESM-free CommonJS over the e2b SDK with nothing major-specific either way.
# (The nodejs22.x Lambda runtime this used to match is gone; the SandboxRunner Lambda
# implementation was removed along with grading — see devdocs/reference/architecture.md.)
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
# NOTE this is no longer classify-service's base (that one is node:24-slim — same Node major since
# the 22->24 bump, still Debian). The remaining divergence is deliberate, not drift:
# classify-service needs glibc for onnxruntime-node's and sharp's prebuilt binaries, which is
# exactly the musl exposure this image has none of.
FROM node:24-alpine3.24

WORKDIR /app
# node:24-alpine3.24 is rebuilt on Node's release cadence, not Alpine's, so its openssl
# (libcrypto3/libssl3) lags whatever is current in the 3.24 repo — the only criticals this image
# has ever carried. Upgrading here rather than pinning a base image is the trade this repo makes
# knowingly: two builds a week apart can resolve different package versions, which costs exact
# reproducibility and buys not waiting on someone else's release schedule. Mirrored in
# agent-sandbox's own apk line.
RUN apk upgrade --no-cache
# Global install rather than corepack — see frontend/Dockerfile. PNPM_VERSION comes from the build
# orchestrator (Taskfile `PNPM_VERSION` locally; the `PNPM_VERSION` Actions variable in CI); the
# default is the same pin, held equal by scripts/check-required-inputs.sh.
ARG PNPM_VERSION=11.15.1
# npm is a build-time bootstrap only — the line below installs with pnpm and CMD is
# `node server.js`. THE npm@12.0.2 PIN THAT USED TO BE ON THIS LINE IS GONE AND SHOULD NOT COME
# BACK: it existed to clear CVE-2026-59873 (node-tar, vendored inside npm's own node_modules, not
# an app dependency) because node:22-alpine3.24's stock npm 10.9.8 vendored tar 7.5.11 and no npm
# before 12.0.0 vendored the fixed 7.5.19. node:24-alpine3.24 ships npm 11.19.0, whose vendored tar
# is already 7.5.19, so the pin now buys nothing and only costs the `allowScripts` hazard that made
# it unsafe to copy into agent-sandbox. The cache clean is size only: npm otherwise leaves ~38 MB
# in ~/.npm that nothing at runtime reads.
RUN npm i -g pnpm@${PNPM_VERSION} \
    && npm cache clean --force
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --prod
# (No pnpm-workspace.yaml for this package — it has no settings to carry: e2b ships no build scripts
# and needs no overrides.)

COPY server.js ./

# Both package managers are build-time bootstraps and neither survives into the runtime: the
# install above is done, and CMD is `node server.js`. They are the only things in this image that
# vendor a copy of node-tar, which is where every HIGH it has carried has come from — npm vendors
# one at /usr/local/lib/node_modules/npm/node_modules/tar and pnpm vendors two more under its own
# dist/. Deleting them is strictly better than the version pin this line replaces (see the
# npm@12.0.2 note above, removed in the Node 24 commit): a pin has to be re-chased every time the
# advisory feed moves past it — that pin was already stale, reaching a tar 7.5.19 that has since
# picked up a finding of its own — where a deleted package cannot acquire a new CVE.
#
# This is a LATER LAYER, so it clears the scanner without reclaiming the bytes; the earlier layers
# still hold them. Merging it into the install layer would save the space but would also mean
# deleting npm in the same RUN that uses it. Correctness first; the image is not size-critical.
RUN rm -rf /usr/local/lib/node_modules/npm \
           /usr/local/lib/node_modules/pnpm \
           /usr/local/lib/node_modules/corepack \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/pnpm \
             /usr/local/bin/pnpx /usr/local/bin/corepack

# The image's version identity, same convention and same "dev" fallback as
# backend/Dockerfile, so the release workflow's OCI
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
