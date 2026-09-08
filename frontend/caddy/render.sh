#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Renders the open frontend image's Caddyfile to stdout from the environment (#1225).
#
#   SITE_DOMAIN      bare hostname; blank means the :8000 plain-HTTP listener only
#   TLS_MODE         acme (default) | owncert | upstream, read only when SITE_DOMAIN is set
#   ACME_EMAIL       required in acme mode: the Let's Encrypt account is registered to it
#   TRUSTED_PROXIES  CIDRs (space- or comma-separated) whose X-Forwarded-* headers Caddy honours; blank trusts nobody
#
# The domain, the account address and the certificate paths are substituted here rather than left
# as Caddy {$ENV} placeholders, so the rendered file (/config/Caddyfile in the container) shows the
# values Caddy actually runs with. A bad combination fails here, at container start, with the key
# named, instead of as a Caddy parse error or a certificate that never arrives. The backend's PublicOriginGuard applies the same rules
# to its own copy of these variables, so the two halves of the stack refuse the same misconfiguration.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
domain="$(printf '%s' "${SITE_DOMAIN:-}" | tr 'A-Z' 'a-z' | sed 's/^ *//; s/ *$//')"
mode="$(printf '%s' "${TLS_MODE:-acme}" | tr 'A-Z' 'a-z' | sed 's/^ *//; s/ *$//')"
proxies="$(printf '%s' "${TRUSTED_PROXIES:-}" | tr ',' ' ')"

fail() { echo "frontend: refusing to start: $*" >&2; exit 1; }
esc() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }
site() {
    sed -e "s|@SITE_DOMAIN@|$(esc "$domain")|g" \
        -e "s|@ACME_EMAIL@|$(esc "${ACME_EMAIL:-}")|g" \
        -e "s|@TLS_CERT_FILE@|$(esc "${TLS_CERT_FILE:-/certs/tls.crt}")|g" \
        -e "s|@TLS_KEY_FILE@|$(esc "${TLS_KEY_FILE:-/certs/tls.key}")|g" "$here/$1"
}

printf '{\n\tadmin off\n'
if [ -n "$proxies" ]; then
    printf '\tservers {\n\t\ttrusted_proxies static %s\n\t\tclient_ip_headers X-Forwarded-For\n\t}\n' "$proxies"
fi
printf '}\n\n'
cat "$here/base.caddy"

[ -n "$domain" ] || exit 0

case "$domain" in
    *://*|*/*|*:*|*' '*) fail "SITE_DOMAIN must be a bare hostname such as tessary.acme-corp.com, not '$domain'" ;;
esac
case "$mode" in
    acme)
        [ -n "${ACME_EMAIL:-}" ] || fail "ACME_EMAIL is required when SITE_DOMAIN is set and TLS_MODE is acme; Let's Encrypt registers the certificate account to it"
        site site-acme.caddy ;;
    owncert)
        site site-owncert.caddy ;;
    upstream)
        [ -n "$proxies" ] || echo "frontend: TLS_MODE=upstream with TRUSTED_PROXIES blank: forwarded headers from the terminator are not trusted, so the backend sees the terminator's address as the client" >&2 ;;
    *)
        fail "TLS_MODE must be one of acme, owncert or upstream, not '$mode'" ;;
esac
