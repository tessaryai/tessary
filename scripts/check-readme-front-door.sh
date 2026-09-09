#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The README is the front door (epic 7 clause 8, #1196): its first quickstart is the self-host
# compose path and agrees with the setup page, it says the open edition signs in with email and
# password, it links the published docs, and it carries the D6 telemetry disclosure with an opt-out
# key that RESOLVES. Reds on substance, not on two deleted literals:
#
#   1. the README's first fenced command is the PUBLISHED ONE-COMMAND INSTALL, above any `task dev`
#      line, and every `docker compose` command in a README fence is one of setup.mdx's own
#      extracted blocks (clause 2's set), so the two pages cannot drift apart. The literal moved
#      from `docker compose up -d` to the `oci://` reference when the compose file itself began
#      shipping as a published artifact: `docker compose up -d` is the FROM-A-CLONE path now, and a
#      front door that leads with it sends a reader to clone a repository they do not need.
#   2. the telemetry key the README prints is the same name .env.example ships and the backend
#      binds (application.yaml's `${KEY:...}`), and the heartbeat host the README names is the one
#      the backend client hardcodes, so renaming either reds the README rather than stranding a
#      reader;
#   3. the disclosure names what is sent (install id, version, bucketed counts) and what is never
#      sent (trace content), and the reinstall limitation #955 requires;
#   4. the auth claim: "email and password" appears, and every line naming WorkOS also names the
#      hosted product, so no self-hoster is routed to a provider they do not have;
#   5. the README links docs/index.mdx or docs/self-hosting/setup.mdx.
#
#   bash scripts/check-readme-front-door.sh              the tree
#   bash scripts/check-readme-front-door.sh --negative   also, in a scratch copy: rename the opt-out
#                                                        key (red), move `task dev` above the compose
#                                                        block (red), strip the hosted pairing from a
#                                                        WorkOS line (red)
# Pure text; a RUN|RUN row in scripts/check.sh.
set -euo pipefail
P=check-readme-front-door
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
NEGATIVE=0
for arg in "$@"; do
    case "$arg" in
        --negative) NEGATIVE=1 ;;
        --root=*) ROOT="${arg#--root=}" ;;
        *) echo "$P: unknown argument '$arg' (accepts --negative, --root=<dir>)" >&2; exit 2 ;;
    esac
done
cd "$ROOT"
README=README.md
DOC=docs/self-hosting/setup.mdx
ENV_EXAMPLE=.env.example
APP_YAML=backend/app/src/main/resources/application.yaml
CLIENT=backend/core/src/main/java/ai/tessary/telemetry/HomeTessaryClient.java
fail=0
# The command the marketing site publishes verbatim, and therefore the one the front door must
# lead with. Kept as one literal so this file and scripts/check-compose-artifact.sh cannot disagree
# about what the install command is.
INSTALL_CMD="docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y"

_fences() { awk '/^```/{f=!f; next} f' "$1"; }

echo "$P: --- 1. the self-host compose path comes first and matches the setup page"
first_cmd="$(_fences "$README" | grep -vE '^\s*(#|$)' | head -1 || true)"
if [ "$first_cmd" = "$INSTALL_CMD" ]; then echo "$P: ok   the README's first command is the published install, '$INSTALL_CMD'"; else echo "$P: RED  the README's first command is '$first_cmd', not '$INSTALL_CMD'" >&2; fail=1; fi
compose_line="$(grep -nF -x "$INSTALL_CMD" <(_fences "$README") | head -1 | cut -d: -f1 || true)"
taskdev_line="$(grep -n '^task dev' <(_fences "$README") | head -1 | cut -d: -f1 || true)"
if [ -z "$compose_line" ]; then
    echo "$P: RED  the README's command blocks carry no '$INSTALL_CMD' at all" >&2; fail=1
elif [ -z "$taskdev_line" ]; then
    echo "$P: ok   the published install is present and no 'task dev' block competes with it"
elif [ "$compose_line" -lt "$taskdev_line" ]; then
    echo "$P: ok   the published install sits above the first 'task dev' in the README's command blocks"
else
    echo "$P: RED  the README's command blocks put 'task dev' (line $taskdev_line of the fences) before the published install ($compose_line)" >&2; fail=1
fi
doc_blocks="$(python3 - "$DOC" <<'PY'
import re, sys
doc = open(sys.argv[1]).read()
for m in re.finditer(r'<Step title="([^"]+)">(.*?)</Step>', doc, re.S):
    for fence in re.findall(r"```bash\n(.*?)```", m.group(2), re.S):
        for line in fence.strip("\n").splitlines():
            print(line.strip())
PY
)"
while IFS= read -r line; do
    [ -n "$line" ] || continue
    if printf '%s\n' "$doc_blocks" | grep -qxF -- "$line"; then
        echo "$P: ok   README command '$line' is one of the setup page's extracted blocks"
    else
        echo "$P: RED  README command '$line' is not a block the setup page carries; the two pages have drifted" >&2; fail=1
    fi
done < <(_fences "$README" | grep -E '^docker compose ' || true)

echo "$P: --- 2. the opt-out key and the heartbeat host resolve"
key="$(grep -oE 'TESSARY_TELEMETRY_[A-Z_]+' "$README" | sort -u || true)"
if [ "$(printf '%s\n' "$key" | grep -c .)" -ne 1 ]; then echo "$P: RED  the README names $(printf '%s\n' "$key" | grep -c .) telemetry keys, wanted exactly one" >&2; fail=1; fi
for k in $key; do
    if grep -qE "^#? ?$k=" "$ENV_EXAMPLE"; then echo "$P: ok   $k is in $ENV_EXAMPLE"; else echo "$P: RED  $k is not in $ENV_EXAMPLE" >&2; fail=1; fi
    if grep -qE "\\$\\{$k(:[^}]*)?\\}" "$APP_YAML"; then echo "$P: ok   $k is bound in $APP_YAML"; else echo "$P: RED  $k is not bound in $APP_YAML; the backend would ignore it" >&2; fail=1; fi
done
host="$(grep -oE 'home\.tessary\.ai' "$README" | head -1 || true)"
if [ -n "$host" ] && grep -q "https://$host" "$CLIENT"; then echo "$P: ok   the README names $host, which is $CLIENT's base URL"; else echo "$P: RED  the README's heartbeat host '${host:-none}' is not the backend client's" >&2; fail=1; fi

echo "$P: --- 3. the disclosure says what is sent, what is not, and the reinstall limitation"
# Read from the disclosure section alone (its heading to the next heading), not the whole README.
disclosure="$(awk '/^### What a self-hosted instance sends home/{f=1; next} f && /^#/{f=0} f' "$README")"
[ -n "$disclosure" ] || { echo "$P: RED  the README has no 'What a self-hosted instance sends home' section" >&2; fail=1; }
for phrase in 'install id' 'app version' 'bucketed counts' 'never carries' 'reinstall' 'every 24 hours' 'schema version' 'timestamp'; do
    if printf '%s' "$disclosure" | grep -qi -- "$phrase"; then echo "$P: ok   the disclosure names '$phrase'"; else echo "$P: RED  the disclosure section does not say '$phrase'" >&2; fail=1; fi
done

echo "$P: --- 4. the open edition signs in with email and password; WorkOS is only ever the hosted product's"
if grep -qi 'email and password' "$README"; then echo "$P: ok   'email and password' is stated"; else echo "$P: RED  the README never says the open edition signs in with email and password" >&2; fail=1; fi
# The pairing is the hosted product by name, so "self-hosted ... WorkOS" cannot satisfy it.
while IFS= read -r l; do
    if printf '%s' "$l" | grep -qE 'Tessary Cloud|app\.tessary\.ai'; then echo "$P: ok   a WorkOS mention is paired with the hosted product by name"; else echo "$P: RED  a WorkOS mention routes a self-hoster wrong: $(printf '%s' "$l" | cut -c1-100)" >&2; fail=1; fi
done < <(grep -i 'WorkOS' "$README" || true)
# The README's one in-app destination is held to the rendered label the same way setup.mdx's are.
if grep -q 'Settings → Sources' "$README" && grep -qF 'label: "Sources"' frontend/src/shell/nav.tsx; then
    echo "$P: ok   the README's 'Settings → Sources' is a label the app renders"
else
    echo "$P: RED  the README names 'Settings → Sources' but frontend/src/shell/nav.tsx no longer renders 'Sources' (or the README dropped the destination)" >&2; fail=1
fi

echo "$P: --- 5. the README links the published docs"
if grep -qE '\]\(\./docs/(index\.mdx|self-hosting/setup\.mdx)\)' "$README"; then echo "$P: ok   docs/index.mdx or the setup page is linked"; else echo "$P: RED  the README links neither docs/index.mdx nor docs/self-hosting/setup.mdx" >&2; fail=1; fi

if [ "$NEGATIVE" = 1 ]; then
    echo "$P: --- negative: three planted faults must each be red"
    T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
    for f in "$README" "$DOC" "$ENV_EXAMPLE" "$APP_YAML" "$CLIENT" frontend/src/shell/nav.tsx; do mkdir -p "$T/$(dirname "$f")"; cp "$f" "$T/$f"; done
    sed -i.bak 's/TESSARY_TELEMETRY_ENABLED/TESSARY_TELEMETRY_RENAMED/g' "$T/$README"
    if bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, a renamed opt-out key passed" >&2; exit 1; fi
    echo "$P: negative ok: a renamed opt-out key is red"; cp "$README" "$T/$README"
    python3 - "$T/$README" <<'PY'
import sys; p=sys.argv[1]; s=open(p).read()
s=s.replace("```bash\ndocker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y\n```", "```bash\ntask dev\n```", 1); open(p,'w').write(s)
PY
    if bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, 'task dev' as the first command passed" >&2; exit 1; fi
    echo "$P: negative ok: 'task dev' displacing the compose block is red"; cp "$README" "$T/$README"
    sed -i.bak 's/Tessary Cloud runs the same code behind WorkOS AuthKit/Self-hosted installs sign in through WorkOS AuthKit/' "$T/$README"
    if bash "$SELF" --root="$T" >/dev/null 2>&1; then echo "$P: FAIL, a WorkOS line with no hosted pairing passed" >&2; exit 1; fi
    echo "$P: negative ok: a WorkOS mention that routes a self-hoster to a provider is red"
fi
[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: ok, the README is the front door the setup page describes"
