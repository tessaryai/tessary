# Frontend production image — Vite build → Caddy serves static + proxies /api.
#
# Build context is the REPO ROOT — NOT because Caddy needs anything outside frontend/ (it doesn't,
# see below), but because the `build` stage's `COPY frontend/...` lines need it. docker-compose.yml
# sets the context accordingly.

# ---- build ----
FROM node:24-alpine AS build
WORKDIR /app

# pnpm by explicit global install rather than corepack: image builds then never depend on
# corepack's registry signature check. PNPM_VERSION is supplied by the build orchestrator (the
# Taskfile locally, the PNPM_VERSION Actions variable in CI); the default is the same pin so a bare
# `docker build` works, and scripts/check-required-inputs.sh reds the moment it drifts from the
# Taskfile's (#1190).
ARG PNPM_VERSION=11.15.1
RUN npm i -g pnpm@${PNPM_VERSION}

# Cache deps. pnpm-workspace.yaml is NOT optional here: pnpm 11 keeps settings there rather than in
# package.json, and this package's overrides (the es-toolkit pin, the openapi-typescript peer) are
# load-bearing. Omit it and the install silently resolves a different tree.
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# Source + config
COPY frontend/tsconfig.json frontend/vite.config.ts frontend/index.html ./
# tokens.css is NOT optional and NOT part of ./src: `src/index.css` does `@import "../tokens.css"`,
# so the design-token layer has to land at /app/tokens.css, one level above the source tree, or the
# Tailwind plugin fails the build with "Can't resolve '../tokens.css' in '/app/src'". A local
# `vite build` never noticed the omission — the file is on disk there either way — so only this
# image build could catch it. Any future file `src/` reaches for with `../` needs a line here too.
COPY frontend/tokens.css ./
COPY frontend/public ./public
COPY frontend/src ./src
# scripts/ is part of `pnpm run build`, not a side tool: the build's last step is
# `node scripts/copy-dep-licenses.mjs`, which copies the font and icon packages' license text out
# of node_modules into dist/licenses/ so it travels into /srv with the binaries it covers (SIL OFL
# 1.1 §2 / ISC — see that script's header). Omit this line and the build dies at MODULE_NOT_FOUND
# rather than shipping quietly, which is the failure mode we want, but the fix is here.
COPY frontend/scripts ./scripts

RUN pnpm run build

# ---- runtime ----
# Stock caddy:2-alpine, no custom xcaddy build. The Cloudflare DNS-01 directives that used to
# justify a bespoke binary here were shaped for OUR OWN Cloudflare-proxied box, not for a
# self-hosted open image (#926). The configuration is rendered at container start by
# frontend/caddy/render.sh from SITE_DOMAIN and TLS_MODE (#1225): Let's Encrypt over TLS-ALPN-01,
# an operator-mounted certificate, or plain HTTP behind the operator's own TLS terminator with
# TRUSTED_PROXIES honoured.
FROM caddy:2-alpine AS runtime

# Non-root runtime (#926 — this was the one image of six that never dropped root). `setcap` runs in
# THIS stage, on the binary `caddy:2-alpine` already ships, in the same layer it's granted in —
# capabilities set on a binary do not survive a `COPY --from=`, so this only works because there is
# no cross-stage copy of the caddy binary any more. The grant itself is required, not optional: the
# TLS decision above means Caddy still binds :443, a privileged port, as a non-root process.
RUN apk add --no-cache libcap \
    && setcap 'cap_net_bind_service=+ep' /usr/bin/caddy \
    && addgroup -S caddy && adduser -S -G caddy caddy \
    && chown -R caddy:caddy /data /config /etc/caddy

COPY --from=build --chown=caddy:caddy /app/dist /srv
COPY --chown=caddy:caddy frontend/caddy/ /etc/caddy/

# The image's version identity (epic 5 clause 3, #1135); same convention and same "dev" fallback as
# backend/Dockerfile, so a paid frontend layer can one day compare against it the same way.
ARG IMAGE_VERSION=dev
ARG IMAGE_REVISION=unknown
LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION
RUN printf '%s\n' "$IMAGE_VERSION" > /srv/IMAGE_VERSION
USER caddy
EXPOSE 8000
CMD ["/etc/caddy/entrypoint.sh"]
