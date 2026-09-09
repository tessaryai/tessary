# syntax=docker/dockerfile:1
# SPDX-License-Identifier: Apache-2.0
#
# The CI toolchain image for .github/workflows/check.yml.
#
# WHAT THIS IS FOR. That workflow spends six steps assembling toolchains before a single gate runs:
# setup-java, pnpm/action-setup, setup-node, `pnpm install`, setup-python, and "Install Caddy". None
# of that is work the gate cares about; it is the same set of installs on every PR, and the cost is
# almost entirely network rather than compute. Baking them into one image turns those steps into a
# `docker pull` the runner mostly has warm.
#
# THE FIVE TOOLCHAINS come straight out of scripts/check.sh's own header, which lists them and says
# which gate needs each:
#
#   Temurin JDK 25   -- check-backend.sh hard-fails on any other major before Maven starts
#   Node 24 + pnpm   -- check-frontend.sh runs tsc and a real vite build; pnpm's version is pinned
#                       to frontend/package.json's `packageManager` field, not floated
#   Python 3.13      -- six gates shell out to `python3` (version-consistency, open-boundary,
#                       license-headers, caddy, compose-artifact, export-denylist)
#   uv               -- check-classifier-parity.sh and the classifiers/ Python gates
#   caddy            -- check-caddy.sh, which runs `caddy validate` and nothing else
#
# AND ONE THE LIST DOES NOT NAME: Maven. check-backend.sh's last line is `exec mvn -B -T 2 verify`,
# and check.sh's header folds Maven into "Java" because ubuntu-latest ships it preinstalled and
# actions/setup-java only had to supply the JDK. A container is not ubuntu-latest. Leave Maven out
# and the backend gate dies on `mvn: command not found`, so it is pinned and installed here too.
# If you are auditing this file against that header and counting to five, this is the sixth.
#
# EVERY VERSION IS PINNED AND EVERY DOWNLOAD IS CHECKSUM-VERIFIED. The point of an image is that
# two runs a month apart get the identical toolchain; a `curl | sh` installer or an unpinned apt
# package would give that away for nothing. Bumping any toolchain is editing its ARG pair — version
# and hash move together, always.
#
# NOTE ON HASH ALGORITHMS, because they differ by upstream and mixing them up produces a confusing
# failure rather than a clear one: Node and uv publish SHA-256, Caddy and Maven publish SHA-512.
# The `sha256sum`/`sha512sum` call in each block matches what that project actually publishes.
#
# CADDY_VERSION AND CADDY_SHA512 BELOW ARE DUPLICATED IN check.yml's "Install Caddy" step. They are
# deliberately the same pin and must be bumped together: while the `container:` key stays commented
# out over there, that step is the one that actually runs, and a drift between the two would mean
# the image and the live job validate Caddyfiles with different Caddy versions.

FROM eclipse-temurin:25.0.4_7-jdk-noble

# Toolchain pins. Each hash is copied verbatim from the publisher's own checksum file:
#   NODE     https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt
#   UV       https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/<asset>.sha256
#   CADDY    https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_checksums.txt
#   MAVEN    https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/<asset>.sha512
ARG NODE_VERSION=24.21.0
ARG NODE_SHA256=fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6

# Pinned to frontend/package.json's `packageManager` field. If you bump one, bump the other: the
# whole reason to bake pnpm rather than let corepack resolve it at run time is that the image and
# the lockfile agree by construction, and a silent divergence here would produce a store-layout
# difference that only shows up as a confusing `pnpm install --frozen-lockfile` failure.
ARG PNPM_VERSION=11.15.1

ARG PYTHON_VERSION=3.13
ARG UV_VERSION=0.12.11
ARG UV_SHA256=4ae93e0f148a18434cc094072547cec88912fc4a72b984183c7d0d0e9586cb5e
ARG CADDY_VERSION=2.11.4
ARG CADDY_SHA512=8220d1f013b6f27510247b2360c9e0ca9f018feebd82515f07635318b34ff9777ccc8fd0b6e6f2486ce3a33fe389fbb7db12d05baa474f4587509fb4f5ebf1c9
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

# The only apt install in this file, and it is base OS plumbing rather than a toolchain: curl and
# ca-certificates to fetch the pinned tarballs, xz-utils because Node ships .tar.xz, git because
# check-export-denylist.sh and several gates shell out to it, and bash because every scripts/*.sh
# starts with `#!/usr/bin/env bash` and Ubuntu's default /bin/sh is dash.
RUN apt-get update \
 && apt-get install --no-install-recommends -y \
      bash \
      ca-certificates \
      curl \
      git \
      tar \
      xz-utils \
 && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------------------------
# Maven
# ---------------------------------------------------------------------------------------------
# backend/.mvn/extensions.xml loads the Maven build-cache extension, which is a Maven 3.9 feature;
# do not drift this to Maven 4 without reading that file and .mvn/maven-build-cache-config.xml.
RUN set -euo pipefail; \
    cd /tmp; \
    curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
         -o maven.tar.gz \
         "https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    echo "${MAVEN_SHA512}  maven.tar.gz" | sha512sum --check --strict -; \
    mkdir -p /opt/maven; \
    tar -xzf maven.tar.gz -C /opt/maven --strip-components=1; \
    rm maven.tar.gz; \
    ln -s /opt/maven/bin/mvn /usr/local/bin/mvn; \
    mvn --version

# ---------------------------------------------------------------------------------------------
# Node + pnpm
# ---------------------------------------------------------------------------------------------
# --strip-components=1 lands node's bin/, lib/, include/ and share/ directly under /usr/local, which
# is how the official node image does it and why no PATH change is needed.
RUN set -euo pipefail; \
    cd /tmp; \
    curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
         -o node.tar.xz \
         "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz"; \
    echo "${NODE_SHA256}  node.tar.xz" | sha256sum --check --strict -; \
    tar -xJf node.tar.xz -C /usr/local --strip-components=1; \
    rm -f node.tar.xz /usr/local/CHANGELOG.md /usr/local/LICENSE /usr/local/README.md; \
    node --version; \
    npm --version

# Installed through npm at an exact version rather than from pnpm's own release tarball: npm
# verifies the registry tarball's integrity hash over TLS, so the pin is still exact, and it avoids
# a second hardcoded hash that would have to be bumped in lockstep with package.json anyway.
# Corepack is deliberately not used — it resolves `packageManager` at run time, which is a network
# call on every job and defeats the point of baking the toolchain in.
RUN set -euo pipefail; \
    npm install --global "pnpm@${PNPM_VERSION}"; \
    npm cache clean --force; \
    pnpm --version

# ---------------------------------------------------------------------------------------------
# uv, and Python 3.13 through it
# ---------------------------------------------------------------------------------------------
# Ubuntu 24.04 (noble) ships Python 3.12, and 3.13 is not in its archive. The usual fix is the
# deadsnakes PPA, which is a third-party apt repo — exactly the machinery Layer 3 removed from
# check.yml's Caddy step, and it would reintroduce an unpinned, network-dependent install. uv is
# already a required toolchain here and can install a managed CPython itself, so 3.13 comes from
# the tool the repo already depends on rather than from a fourth package source.
RUN set -euo pipefail; \
    cd /tmp; \
    curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
         -o uv.tar.gz \
         "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz"; \
    echo "${UV_SHA256}  uv.tar.gz" | sha256sum --check --strict -; \
    tar -xzf uv.tar.gz -C /usr/local/bin --strip-components=1; \
    rm uv.tar.gz; \
    uv --version

# Set as ENV, not just ARG, so that `uv run` at job time resolves the interpreter baked in here
# instead of downloading its own into a container-local HOME on first use.
ENV UV_PYTHON_INSTALL_DIR=/opt/uv-python

# The gates invoke the interpreter as bare `python3` (and check-classify-service.sh's helpers as
# `python`), so both names have to resolve on PATH; a uv-managed install is only discoverable
# through uv until it is linked.
RUN set -euo pipefail; \
    uv python install "${PYTHON_VERSION}"; \
    ln -s "$(uv python find "${PYTHON_VERSION}")" /usr/local/bin/python3; \
    ln -s /usr/local/bin/python3 /usr/local/bin/python; \
    python3 --version; \
    python --version

# ---------------------------------------------------------------------------------------------
# Caddy
# ---------------------------------------------------------------------------------------------
# Same pin, same tarball and same SHA-512 as check.yml's "Install Caddy" step; see the note at the
# top of this file about keeping the two in step. Only the binary is extracted — the tarball's
# LICENSE and README.md have no business in /usr/local/bin.
RUN set -euo pipefail; \
    cd /tmp; \
    curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
         -o caddy.tar.gz \
         "https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_amd64.tar.gz"; \
    echo "${CADDY_SHA512}  caddy.tar.gz" | sha512sum --check --strict -; \
    tar -xzf caddy.tar.gz -C /usr/local/bin caddy; \
    chmod 0755 /usr/local/bin/caddy; \
    rm caddy.tar.gz; \
    caddy version

# A last assertion that every tool the gate reaches for is actually on PATH in the final image, run
# at build time so a broken image fails in the build that produced it rather than in someone's PR.
# `command -v` and not a version call: this is about resolvability, and each block above already
# proved its own tool executes.
RUN set -euo pipefail; \
    for t in java mvn node npm pnpm python3 uv caddy git; do \
      command -v "$t" >/dev/null || { echo "toolchain image: '$t' is not on PATH" >&2; exit 1; }; \
    done; \
    echo "toolchain image: java=$(java -version 2>&1 | head -1)"

WORKDIR /workspace
