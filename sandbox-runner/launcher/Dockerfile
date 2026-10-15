# Launcher sidecar image: holds the E2B SDK and key and only orchestrates sandboxes. Alpine because
# Debian's perl CRITICALs are unfixable; the only runtime dependency (e2b) is pure JS.
FROM node:24-alpine3.24

WORKDIR /app
# The base's openssl lags the 3.24 repo; upgrading costs exact reproducibility. Mirrored in agent-sandbox.
RUN apk upgrade --no-cache
# Global install rather than corepack (see frontend/Dockerfile); the default is kept equal to the
# Taskfile's PNPM_VERSION by hand.
ARG PNPM_VERSION=11.15.1
# npm is a build-time bootstrap only. The cache clean is size only.
RUN npm i -g pnpm@${PNPM_VERSION} \
    && npm cache clean --force
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --prod

COPY server.js ./

# Both package managers are build-time only and are the only things vendoring node-tar, so deleting
# them beats chasing a version pin. A later layer: it clears the scanner without reclaiming bytes.
RUN rm -rf /usr/local/lib/node_modules/npm \
           /usr/local/lib/node_modules/pnpm \
           /usr/local/lib/node_modules/corepack \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/pnpm \
             /usr/local/bin/pnpx /usr/local/bin/corepack

# Also written to a file: a derived image can COPY a file from its base but cannot read its labels.
ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION
RUN printf '%s\n' "$IMAGE_VERSION" > /app/IMAGE_VERSION

ENV PORT=8080
EXPOSE 8080
USER node
CMD ["node", "server.js"]
