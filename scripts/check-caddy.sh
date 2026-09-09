#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Caddyfile gate. Two halves:
#
# SYNTAX: the dev Caddyfile, and every configuration frontend/caddy/render.sh can emit for the open
# image: localhost only, a domain in each of the three TLS modes, and upstream mode with
# trusted proxies. owncert is validated against a throwaway self-signed pair, because
# `caddy validate` loads the certificate it is told to serve.
#
# COVERAGE: every operation in the open OpenAPI spec is proxied to the backend by the configs that
# actually ship. A validating config can still be a broken one — an unproxied /v1/... path
# does not 404, it falls through to the SPA and answers index.html with a 200, so the failure is a
# page of HTML where an API response belongs, reported by nothing.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
P="check-caddy"

caddy validate --config Caddyfile >/dev/null 2>&1 || { echo "$P: RED  the dev Caddyfile does not validate" >&2; caddy validate --config Caddyfile >&2; exit 1; }
echo "$P: ok   dev Caddyfile"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 \
    -subj "/CN=tessary.test" -keyout "$TMP/tls.key" -out "$TMP/tls.crt" >/dev/null 2>&1

_render_ok() {
    local name="$1"; shift
    env -i PATH="$PATH" "$@" sh frontend/caddy/render.sh > "$TMP/$name.caddy" \
        || { echo "$P: RED  render failed: $name" >&2; exit 1; }
    caddy validate --config "$TMP/$name.caddy" --adapter caddyfile >/dev/null 2>&1 \
        || { echo "$P: RED  rendered configuration does not validate: $name" >&2; caddy validate --config "$TMP/$name.caddy" --adapter caddyfile >&2; exit 1; }
    echo "$P: ok   render + validate: $name"
}
_render_refused() {
    local name="$1" needle="$2"; shift 2
    if env -i PATH="$PATH" "$@" sh frontend/caddy/render.sh > "$TMP/$name.caddy" 2> "$TMP/$name.err"; then
        echo "$P: RED  render did not refuse: $name" >&2; exit 1
    fi
    grep -qF "$needle" "$TMP/$name.err" || { echo "$P: RED  $name refused without naming '$needle':" >&2; cat "$TMP/$name.err" >&2; exit 1; }
    echo "$P: ok   render refused, naming $needle: $name"
}

_render_ok localhost
_render_ok acme      SITE_DOMAIN=tessary.test ACME_EMAIL=ops@tessary.test
_render_ok owncert   SITE_DOMAIN=tessary.test TLS_MODE=owncert TLS_CERT_FILE="$TMP/tls.crt" TLS_KEY_FILE="$TMP/tls.key"
_render_ok upstream  SITE_DOMAIN=tessary.test TLS_MODE=upstream TRUSTED_PROXIES="10.0.0.0/8,192.168.0.0/16"
_render_ok mixed-case SITE_DOMAIN=" Tessary.Test " TLS_MODE=" OwnCert" TLS_CERT_FILE="$TMP/tls.crt" TLS_KEY_FILE="$TMP/tls.key"
_render_ok odd-email  SITE_DOMAIN=tessary.test ACME_EMAIL='ops&co|tls@tessary.test'
_render_refused acme-no-email  ACME_EMAIL SITE_DOMAIN=tessary.test
_render_refused scheme-prefixed SITE_DOMAIN SITE_DOMAIN=https://tessary.test TLS_MODE=owncert
_render_refused unknown-mode    TLS_MODE SITE_DOMAIN=tessary.test TLS_MODE=cloudflare
grep -q 'https://tessary.test' "$TMP/acme.caddy" || { echo "$P: RED  acme render carries no site block" >&2; exit 1; }
grep -q 'trusted_proxies static 10.0.0.0/8 192.168.0.0/16' "$TMP/upstream.caddy" || { echo "$P: RED  upstream render lost TRUSTED_PROXIES" >&2; exit 1; }
! grep -q 'https://tessary.test' "$TMP/upstream.caddy" || { echo "$P: RED  upstream render must not open a TLS site" >&2; exit 1; }
grep -q 'email ops&co|tls@tessary.test' "$TMP/odd-email.caddy" || { echo "$P: RED  an address with sed-special characters was corrupted" >&2; exit 1; }
grep -q 'https://tessary.test {' "$TMP/mixed-case.caddy" || { echo "$P: RED  the domain and mode are not case-folded" >&2; exit 1; }
echo "$P: every rendered configuration validates and every bad combination is refused by name"

# The rendered localhost configuration is what the open image serves, and the dev Caddyfile is what
# `task dev` serves; both carry the same @backend matcher, and both are checked so a route added to
# one alone cannot pass. The acme render is included because its site block repeats the matcher.
python3 scripts/lib/caddy-proxies-spec.py \
    backend/contract/src/main/resources/openapi/tessary-api.json \
    Caddyfile "$TMP/localhost.caddy" "$TMP/acme.caddy" \
    || { echo "$P: RED  the proxy config does not match the open spec (see above)" >&2; exit 1; }
echo "$P: ok   every operation in the open spec reaches the backend"
