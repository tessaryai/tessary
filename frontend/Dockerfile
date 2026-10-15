# Frontend production image — Vite build → Caddy serves static + proxies /api.
#
# Build context is the repo root (docker-compose.yml sets it).

# ---- build ----
FROM node:24-alpine AS build
WORKDIR /app

# A global install, not corepack, so the build never depends on corepack's registry signature check.
ARG PNPM_VERSION=11.15.1
RUN npm i -g pnpm@${PNPM_VERSION}

# pnpm-workspace.yaml carries the openapi-typescript peer override; without it the install resolves a different tree.
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# The type-check runs in CI (pnpm run lint), not here.
COPY frontend/tsconfig.json frontend/vite.config.ts frontend/index.html ./
# src/index.css imports ../tokens.css; anything else src/ reaches with ../ needs a line here too.
COPY frontend/tokens.css ./
COPY frontend/public ./public
COPY frontend/src ./src
# `pnpm run build` ends with scripts/copy-dep-licenses.mjs (font and icon license text into dist/licenses/).
COPY frontend/scripts ./scripts

RUN pnpm run build

# ---- runtime ----
# Stock caddy:2-alpine; frontend/caddy/render.sh renders the config at start from SITE_DOMAIN and TLS_MODE.
FROM caddy:2-alpine AS runtime

# Non-root, so setcap must run in this stage (capabilities do not survive COPY --from) to let caddy
# bind :443. apk upgrade picks up the openssl the base image lags on; curl and libcurl are deleted
# because nothing here calls them (the healthcheck uses busybox wget, caddy links no libcurl).
RUN apk upgrade --no-cache \
    && apk del --no-cache curl libcurl \
    && apk add --no-cache libcap \
    && setcap 'cap_net_bind_service=+ep' /usr/bin/caddy \
    && addgroup -S caddy && adduser -S -G caddy caddy \
    && chown -R caddy:caddy /data /config /etc/caddy

COPY --from=build --chown=caddy:caddy /app/dist /srv
COPY --chown=caddy:caddy frontend/caddy/ /etc/caddy/

ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION
RUN printf '%s\n' "$IMAGE_VERSION" > /srv/IMAGE_VERSION
USER caddy
EXPOSE 8000
CMD ["/etc/caddy/entrypoint.sh"]
