#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# Puts a verified `oras` on PATH for scripts/publish-compose-artifact.sh. VERSION and both checksums
# (from the project's GPG-signed checksums file) move together by hand; a mismatch is fatal, since the
# binary is handed registry credentials. Idempotent.   bash scripts/lib/install-oras.sh <dir>
set -euo pipefail
P=install-oras

VERSION=1.3.4
SHA256_linux_amd64=f27adb935022d94df8dc77719c322dda592c78a0d57a6f7dcdd8d900b248c454
SHA256_linux_arm64=15702c6e3a4a56a8bd8ac5c17efdbcab56d9bada661ccbcf017f5b10c1d89399
SHA256_darwin_amd64=  # not pinned: nothing in CI runs here, add the row before relying on it
SHA256_darwin_arm64=217761a9500242ff473de8656b5aca21136ff39e17e9e61fd8936bbfd902704c

DEST="${1:-}"
[ -n "$DEST" ] || { echo "$P: usage: bash scripts/lib/install-oras.sh <dir>" >&2; exit 2; }
mkdir -p "$DEST"

case "$(uname -s)" in
    Linux)  OS=linux ;;
    Darwin) OS=darwin ;;
    *)      echo "$P: unsupported OS $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    *)             echo "$P: unsupported architecture $(uname -m)" >&2; exit 2 ;;
esac

if [ -x "$DEST/oras" ] && "$DEST/oras" version 2>/dev/null | grep -q "^Version:[[:space:]]*${VERSION}$"; then
    echo "$P: oras ${VERSION} already present"
    printf '%s\n' "$DEST/oras"
    exit 0
fi

eval "WANT=\${SHA256_${OS}_${ARCH}:-}"
[ -n "$WANT" ] || {
    echo "$P: no checksum pinned for ${OS}/${ARCH}. Add it from" >&2
    echo "$P:   https://github.com/oras-project/oras/releases/download/v${VERSION}/oras_${VERSION}_checksums.txt" >&2
    exit 1
}

TARBALL="oras_${VERSION}_${OS}_${ARCH}.tar.gz"
URL="https://github.com/oras-project/oras/releases/download/v${VERSION}/${TARBALL}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "$P: fetching ${URL}"
curl -fsSL --retry 3 -o "$TMP/$TARBALL" "$URL"

GOT="$(shasum -a 256 "$TMP/$TARBALL" 2>/dev/null | cut -d' ' -f1 || sha256sum "$TMP/$TARBALL" | cut -d' ' -f1)"
if [ "$GOT" != "$WANT" ]; then
    echo "$P: CHECKSUM MISMATCH for ${TARBALL}" >&2
    echo "$P:   expected $WANT" >&2
    echo "$P:   got      $GOT" >&2
    echo "$P: refusing to install. This binary is handed registry credentials; do not continue." >&2
    exit 1
fi
echo "$P: sha256 ok  ${GOT}"

tar -xzf "$TMP/$TARBALL" -C "$TMP" oras
install -m 0755 "$TMP/oras" "$DEST/oras"
"$DEST/oras" version | sed "s/^/$P: /"
printf '%s\n' "$DEST/oras"
