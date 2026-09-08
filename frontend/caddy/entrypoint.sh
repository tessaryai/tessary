#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Renders the Caddyfile for this container's environment, then runs Caddy on it (#1225).
set -eu
/etc/caddy/render.sh > /config/Caddyfile
exec caddy run --config /config/Caddyfile --adapter caddyfile
