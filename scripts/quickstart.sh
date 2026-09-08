#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The FROM-A-CLONE install path (#1227): pull the published images, bring the stack up from this
# repository's own docker-compose.yml, and wait until it is actually reachable before printing the
# URL. Someone with nothing cloned does not need this script or this repository at all — their
# install is one command against the published compose artifact (setup.md):
#
#   docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
#
# Usage:
#   bash scripts/quickstart.sh
#
# No .env required (#1230). This script still does not GENERATE secrets, and the reason it never
# should is unchanged: the two sealing keys are what every session cookie and every stored provider
# credential are sealed with, and a script that invents a different value on each run (or on every
# `docker compose down && up`, having written nothing durable) locks the operator out of their own
# data. What changed is that refusing to start is no longer the alternative — docker-compose.yml
# ships a documented placeholder for each, so a localhost test drive needs no configuration at all,
# and the backend refuses to start on a placeholder the moment SITE_DOMAIN names a real host. See
# docs/self-hosting/setup.mdx for when to replace them.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker does not appear to be running." >&2
  echo "Start Docker Desktop (or your Docker daemon) and re-run this script -- it is safe to" >&2
  echo "re-run: 'docker compose pull' and 'docker compose up -d' are both idempotent." >&2
  exit 1
fi

echo "Pulling images..."
docker compose pull

echo "Starting the stack..."
docker compose up -d

echo "Waiting for the frontend to answer..."
HTTP_PORT="${HTTP_PORT:-80}"
url="http://localhost:${HTTP_PORT}"
deadline=$((SECONDS + 120))
until curl -fsS -o /dev/null "$url" 2>/dev/null; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "" >&2
    echo "ERROR: ${url} never answered within 120s." >&2
    echo "Check container state and logs:" >&2
    echo "  docker compose ps" >&2
    echo "  docker compose logs backend" >&2
    echo "  docker compose logs frontend" >&2
    exit 1
  fi
  printf "."
  sleep 2
done
echo ""

# Every default service carries its own healthcheck now (#1189), so `ps` is the general instrument
# and the HTTP poll above is the specific one; both are printed because they answer different
# questions -- "is the whole stack up" versus "does the port a browser opens actually answer".
docker compose ps

echo ""
echo "Tessary is up: ${url}"
echo "Create the first account there to continue -- see docs/self-hosting/setup.mdx."
if [ ! -f .env ]; then
  echo ""
  echo "This instance is running on the placeholder sealing keys docker-compose.yml ships."
  echo "They are public, so keep it on localhost until you replace them:"
  echo "  cp .env.example .env   # then uncomment the two key blocks and set your own"
  echo "  openssl rand -base64 32"
fi
