# Plain OCI build of the observer-analyzer runtime — the "agent image" D7's Docker sandbox
# driver (sandbox-runner/launcher/server.js, SANDBOX_BACKEND=docker) spawns as a fresh sibling
# container per request. `template.ts` (same directory) builds the SAME runtime as an E2B v2 SDK
# template instead, for the `e2b` backend. TWO INDEPENDENTLY MAINTAINED RECIPES FOR ONE RUNTIME —
# read template.ts's own header before touching either. Whichever changes, mirror the change in
# the other, opencode-ai's pinned version ABOVE ALL: agent-stream.js reads message-part field
# names that move between opencode-ai releases, and the two recipes silently drifting apart is
# exactly the "authored but nobody kept it current" failure mode this comment exists to prevent.
#
# BUILD CONTEXT IS THE REPO ROOT, not this directory — the second image (after frontend/Dockerfile,
# see the repo-root .dockerignore's own header) that needs it. This recipe COPYs from two places a
# per-directory context can't reach: `contract/` (the bundle-contract source of truth, staged into
# `./vendor/` by template.ts's sibling build.ts at E2B-build time — this Dockerfile reads the
# ORIGINAL files directly instead of relying on that gitignored staging step having already run)
# and this directory's own script files. `-f` is resolved against the CURRENT DIRECTORY, not the
# context (Compose is the other way around), so the path below carries the full prefix and the
# context does not repeat it:
#
#   docker build -f sandbox-runner/agent-sandbox/Dockerfile -t <tag> .
#
# No CMD/ENTRYPOINT: the docker driver runs `node <script>.js /work/input.json` per invocation
# (see runScriptInDocker), never a long-lived process.
#
# BASE IMAGE: node:22-alpine3.24, the SAME as template.ts (E2B), kept in lockstep with it.
# Why not 20: `re2@1.26.1` — pinned by every recipe, unchanged — declares
# `engines.node: "^22.22.2 || ^24.15.0 || >=26.0.0"`, and npm's own resolved node-gyp fails to even
# configure under Node 20 with an unrelated undici crash (`TypeError:
# webidl.util.markAsUncloneable is not a function`), verified on both linux/amd64 and linux/arm64.
# The launcher's own Dockerfile is on the same base for the same reasons.
#
# Why ALPINE and not Debian: every remaining CRITICAL on the Debian recipe was an unfixable Debian
# package. bookworm carried 16; moving to trixie cleared libsqlite3-0 and zlib1g and left 13, all
# of them perl (CVE-2026-13221 / -42496 / -8376, `vulnerable` on EVERY Debian release — fixed only
# in forky/sid, and marked "Minor issue" / no-dsa / postponed by Debian's own tracker) plus
# linux-libc-dev CVE-2026-43185. perl cannot be removed from a Debian image: perl-base is
# Essential=yes, and git pulls in the rest. Alpine has no perl at all and no libc6-dev, so the
# whole set disappears. Measured on the fully built image with `trivy image`: 13 CRITICAL -> 0.
#
# Alpine is a SUPPORTED E2B base (their docs list Debian/Ubuntu, Fedora/RHEL, Arch and Alpine;
# only images with no /etc/os-release — scratch, distroless, nix — are rejected), and the SDK's
# own `fromAlpineImage()` defaults to variant 3.24, which is what this pins.
#
# TWO THINGS ALPINE FORCES, both load-bearing — do not remove either:
#   1. `bash` in the apk list below. The e2b SDK hardcodes `cmd: "/bin/bash"` when it starts a
#      process, and launcher/server.js drives every RCA/triage run through `sbx.commands.run(...)`.
#      Alpine ships busybox `ash` as /bin/sh and NO /bin/bash, so without this the template builds
#      green and then fails at exec time on every single run. A docker build cannot catch it.
#   2. `apk add`, not `.aptInstall()`. The e2b builder exposes aptInstall/pipInstall/npmInstall but
#      has NO apkInstall, so template.ts's mirror of this line has to be a raw `.runCmd`.
#
# The musl swap itself is verified, not assumed: re2@1.26.1 compiles under Alpine's g++ and matches
# correctly, opencode-ai 1.18.13 installs and runs, PyYAML imports, and @opencode-ai/sdk loads.
FROM node:22-alpine3.24

# The `tar` module npm vendors (usr/local/lib/node_modules/npm/node_modules/tar) ships at 7.5.11 on
# this base — the distro choice above doesn't touch npm's payload — and carries CRITICAL
# CVE-2026-59873 (gzip-bomb DoS), fixed at tar@7.5.19. Patch the vendored copy in place rather than
# bumping npm itself: npm>=12 (the first release vendoring a fixed tar) flips `allowScripts` off by
# default, so `npm install re2@^1.26.1` below would complete with only a WARNING, never run
# node-gyp, and ship an re2 that throws MODULE_NOT_FOUND at require time. Verified directly.
# (launcher/Dockerfile fixes the same CVE with npm@12 instead — it installs with pnpm and builds no
# native addon, so the allowScripts default is harmless there. Do not copy that line into here.)
RUN npm install -g tar@7.5.19 --prefix /tmp/tarfix \
    && cp -r /tmp/tarfix/lib/node_modules/tar/* /usr/local/lib/node_modules/npm/node_modules/tar/ \
    && rm -rf /tmp/tarfix \
    && npm cache clean --force

# git + the agent's clone; python3/py3-pip run the baked bundle validator; make/g++ build re2 (a
# native addon); bash because the e2b SDK execs /bin/bash (see the header). Mirrored by
# template.ts's `.runCmd('apk add ...')` — which is a raw runCmd because the builder has no
# apkInstall. The base's python3 ships WITHOUT pip and WITHOUT PyYAML, so the validator
# (validate.py + pipeline_io.py, both of which `import yaml`) is a hard in-container crash without
# this.
RUN apk add --no-cache bash git ca-certificates python3 py3-pip make g++

# PyYAML is the validator's only required third-party Python dep, baked at build time (no
# per-run network install). --break-system-packages: Alpine's python3, like Debian's, marks the
# system environment PEP 668 externally-managed; PIP_BREAK_SYSTEM_PACKAGES below lets the agent
# `pip install` further deps at runtime without the flag.
RUN python3 -m pip install --no-cache-dir --break-system-packages pyyaml

# OpenCode — provides the `opencode` binary agent-stream.js starts as a server. PINNED TO THE SAME
# VERSION AS @opencode-ai/sdk BELOW AND AS template.ts's own two installs — they ship in lockstep,
# and agent-stream.js reads message parts whose field names have moved between releases. Bump all
# four together (this line, the npm install below, and template.ts's matching pair).
#
# The prune and the cache clean are size, not correctness. opencode ships its runtime as a
# ~180 MB platform binary in optionalDependencies, and on musl npm cannot rule the glibc builds
# out, so it installs every variant matching the arch and executes exactly one: 2 on arm64
# (356 MB), 4 on amd64 (708 MB — x64, x64-musl and BOTH -baseline flavours).
#
# PRUNE BY INODE, NOT BY NAME. `bin/opencode.exe` is a HARDLINK to whichever variant npm resolved,
# and that choice is made at install time and differs per arch (glibc on Debian, musl here). A
# hand-written `rm opencode-linux-<arch>` list is therefore guessing at two things — the naming
# scheme and npm's choice — and on amd64 it silently missed the two -baseline packages. Comparing
# inodes asks the filesystem which variant is actually wired to the binary on PATH, so this stays
# correct across arches, across -baseline variants, and across any future renaming.
#
# `set -e` plus the empty-inode guard matter: if stat ever fails, this must abort the build rather
# than fall through to a loop that deletes every variant including the live one.
RUN set -e; \
    npm install -g opencode-ai@1.18.13; \
    D=/usr/local/lib/node_modules/opencode-ai; \
    INO="$(stat -c %i "$D/bin/opencode.exe")"; \
    [ -n "$INO" ] || { echo "could not stat opencode.exe" >&2; exit 1; }; \
    for p in "$D"/node_modules/opencode-*; do \
      [ -d "$p" ] || continue; \
      [ "$(stat -c %i "$p/bin/opencode" 2>/dev/null)" = "$INO" ] || rm -rf "$p"; \
    done; \
    [ -x "$(command -v opencode)" ] && opencode --version >/dev/null; \
    npm cache clean --force

# Wrapper on PATH so a prompt can run validation as `tessary-evals-validate` without knowing the
# baked layout (see the script's own header). Lives under /usr/local/bin, so it is copied while
# this stage is still root — everything below this line runs as `node`.
COPY sandbox-runner/agent-sandbox/tessary-evals-validate /usr/local/bin/tessary-evals-validate
RUN chmod +x /usr/local/bin/tessary-evals-validate

ENV HOME=/home/user
ENV PIP_BREAK_SYSTEM_PACKAGES=1

# Non-root from here down. This USED to be a trailing
# `RUN chown -R node:node /home/user`, which cost a 73 MB layer all by itself: chown rewrites the
# metadata of every file under /home/user, so Docker re-stores the whole node_modules tree a second
# time. Creating the directory owned by `node` up front and doing the writes AS `node` produces the
# identical ownership for free. The COPYs below still carry --chown because BuildKit applies COPY
# ownership from the flag, not from the current USER.
RUN mkdir -p /home/user/tessary-contract && chown -R node:node /home/user
USER node
WORKDIR /home/user

# Modules the in-VM scripts require from /home/user — @opencode-ai/sdk drives the opencode server,
# undici lifts fetch's 300s headers timeout. acorn/acorn-walk/re2 backed the codegen
# self-correction harness that was deleted and are left here to stay byte-identical to template.ts's
# line — see that file for why trimming them waits for the next E2B rebuild.
# DELIBERATELY npm though the repo is otherwise on pnpm — see template.ts's own comment;
# the same reasoning applies unchanged here (no lockfile to honor, the container is disposable, npm
# is already in the base image). undici pins to ^6 so this line stays byte-identical to
# template.ts's (bump every recipe together; undici 8 needs node >=22.19, fine on node:22-alpine3.24).
# The cache clean is size only: npm leaves ~38 MB in $HOME/.npm that nothing at runtime reads.
RUN npm install acorn@^8.18.0 acorn-walk@^8.3.5 re2@^1.26.1 @opencode-ai/sdk@1.18.13 undici@^6.28.0 \
    && npm cache clean --force

# The bundle contract, copied straight from its repo-root source of truth (contract/) rather than
# from the gitignored ./vendor/ staging directory template.ts's build.ts populates — this recipe
# has no separate staging step because its build context already reaches the real files.
COPY --chown=node:node contract/validate.py /home/user/tessary-contract/validate.py
COPY --chown=node:node contract/pipeline_io.py /home/user/tessary-contract/pipeline_io.py
COPY --chown=node:node contract/AUTHORING_CONTRACT.md /home/user/tessary-contract/AUTHORING_CONTRACT.md
COPY --chown=node:node contract/output_format.md /home/user/tessary-contract/output_format.md
COPY --chown=node:node contract/grader.schema.json /home/user/tessary-contract/grader.schema.json

# Shared OpenCode runner required by rca.js/triage.js — must be baked beside them or their
# `require('./agent-stream')` is a runtime crash.
COPY --chown=node:node sandbox-runner/agent-sandbox/agent-stream.js /home/user/agent-stream.js
COPY --chown=node:node sandbox-runner/agent-sandbox/rca.js /home/user/rca.js
COPY --chown=node:node sandbox-runner/agent-sandbox/triage.js /home/user/triage.js

# The image's version identity: same convention and same "dev" fallback as backend/Dockerfile,
# so the release workflow's OCI
# label assertion has something to check on every published image, not just backend/frontend.
# Written to a file as well as a LABEL for the same reason backend/Dockerfile gives: a derived
# image can COPY a file from its base and cannot read its base's labels. WORKDIR here is
# /home/user (this recipe has no /app), so that's where the file goes.
ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION
RUN printf '%s\n' "$IMAGE_VERSION" > /home/user/IMAGE_VERSION
