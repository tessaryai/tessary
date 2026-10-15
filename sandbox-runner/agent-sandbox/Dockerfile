# The agent runtime the docker sandbox backend spawns per request. template.ts builds the same
# runtime for E2B: mirror every change in both, opencode-ai's version above all.
# Build from the repo root: docker build -f sandbox-runner/agent-sandbox/Dockerfile -t <tag> .
# No CMD: the launcher names the script per run. Alpine because Debian's perl CRITICALs are unfixable.
FROM node:24-alpine3.24

# Do not bump npm to patch its vendored tar: npm>=12 turns `allowScripts` off and silently skips
# opencode-ai's install script. Patch the vendored copy instead.

# bash because the e2b SDK execs /bin/bash; jq for triage's checks/ scripts. `apk upgrade` because the
# base's openssl lags the 3.24 repo. Nothing is deleted here: npm, python3, pip, git and the compilers
# are the agent's working surface.
RUN apk upgrade --no-cache \
    && apk add --no-cache bash git ca-certificates python3 py3-pip make g++ jq

# Pinned with @opencode-ai/sdk below and template.ts's pair; bump all four together. The prune is
# size only: npm installs every platform variant on musl. Prune by the inode backing bin/opencode.exe,
# since npm's pick differs per arch, and abort if stat fails rather than delete the live one.
RUN set -e; \
    npm install -g opencode-ai@1.18.30; \
    D=/usr/local/lib/node_modules/opencode-ai; \
    INO="$(stat -c %i "$D/bin/opencode.exe")"; \
    [ -n "$INO" ] || { echo "could not stat opencode.exe" >&2; exit 1; }; \
    for p in "$D"/node_modules/opencode-*; do \
      [ -d "$p" ] || continue; \
      [ "$(stat -c %i "$p/bin/opencode" 2>/dev/null)" = "$INO" ] || rm -rf "$p"; \
    done; \
    [ -x "$(command -v opencode)" ] && opencode --version >/dev/null; \
    npm cache clean --force

# Execute-only node for session.js: a process started from an unreadable binary is not dumpable, so the
# agent cannot read its memory (MCP tokens) or environment (SESSION_SECRET) through /proc.
RUN cp /usr/local/bin/node /usr/local/bin/node-sealed && chmod 0111 /usr/local/bin/node-sealed

ENV HOME=/home/user
ENV PIP_BREAK_SYSTEM_PACKAGES=1

# Creating the dir owned by `node` up front avoids a 73 MB chown layer. COPY still needs --chown:
# BuildKit takes ownership from the flag, not USER.
RUN mkdir -p /home/user && chown -R node:node /home/user
USER node
WORKDIR /home/user

# npm, not pnpm, as in template.ts; keep this line byte-identical to its install.
RUN npm install @opencode-ai/sdk@1.18.30 undici@^6.28.0 \
    && npm cache clean --force

# agent-stream.js and mcp-relay.js must be baked beside the scripts that require them.
COPY --chown=node:node sandbox-runner/agent-sandbox/agent-stream.js /home/user/agent-stream.js
COPY --chown=node:node sandbox-runner/agent-sandbox/mcp-relay.js /home/user/mcp-relay.js
COPY --chown=node:node sandbox-runner/agent-sandbox/lane.js /home/user/lane.js
COPY --chown=node:node sandbox-runner/agent-sandbox/session.js /home/user/session.js

# Also written to a file: a derived image can COPY a file from its base but cannot read its labels.
ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION
RUN printf '%s\n' "$IMAGE_VERSION" > /home/user/IMAGE_VERSION
