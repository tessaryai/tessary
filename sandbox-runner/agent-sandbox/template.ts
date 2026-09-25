// SPDX-License-Identifier: Apache-2.0
import { Template } from 'e2b';

/**
 * E2B v2 SDK template for the agent sandbox: a microVM that runs OpenCode over a materialized
 * dossier, and over the target repo at HEAD when the caller sends a clone URL.
 *
 * It needs network egress — github.com (clone) + Bedrock. It carries no credentials: the launcher
 * injects a short-lived clone token (in the URL) and the agent's auth env at run time. The sandbox
 * sits idle until the launcher runs `node /home/user/rca.js <input.json>` (no start command).
 *
 * Build/publish with the sibling build.ts (see README): `pnpm exec tsx build.ts`.
 *
 * BASE IMAGE PARITY: this template and the sibling Dockerfile (the Docker-backend agent
 * image) are both node:24-alpine3.24. Do not bump one without the other. See the sibling
 * Dockerfile's header for the full account.
 *
 * ALPINE, AND THE TWO THINGS IT FORCES. E2B supports Alpine as a base (their docs list
 * Debian/Ubuntu, Fedora/RHEL, Arch and Alpine; only images with no /etc/os-release — scratch,
 * distroless, nix — are rejected) and this SDK's own `fromAlpineImage()` defaults to variant 3.24.
 * But:
 *   1. `bash` MUST be installed. This SDK hardcodes `cmd: "/bin/bash"` when it starts a process,
 *      and launcher/server.js runs every RCA/triage through `sbx.commands.run(...)`. Alpine ships
 *      busybox `ash` as /bin/sh and no /bin/bash, so omitting it makes the template build fine and
 *      then fail at exec time on EVERY run — a failure no image build can surface.
 *   2. There is no `.apkInstall()`. The builder exposes aptInstall/pipInstall/npmInstall/bunInstall
 *      only, so the package line below is a raw `.runCmd('apk add ...')` where the sibling
 *      Dockerfile can use its native `RUN apk add`.
 *
 * AND ONE THING E2B ITSELF FORCES, unrelated to Alpine: every `.runCmd` that writes OUTSIDE
 * /home/user needs `{ user: 'root' }`. Docker's `RUN` is root by default; E2B's runCmd is NOT, so
 * a command the sibling Dockerfile runs happily fails here with `Permission denied` on
 * /usr/local/lib. The typed helpers (.aptInstall/.npmInstall/.pipInstall) elevate on their own;
 * raw runCmds do not. This was caught by an actual `Template.build`, not by review — a docker
 * build can never surface it, so re-run build.ts after touching any runCmd below.
 */
export const template = Template()
  // node 24, not 22: 22 is maintenance-only since 2025-10-21 and EOL 2027-04-30, 24 runs to
  // 2028-04-30.
  //
  // Alpine, not Debian: every CRITICAL left on the Debian recipe was an unfixable Debian package.
  // bookworm carried 16; trixie cleared libsqlite3-0 and zlib1g and left 13 — all perl
  // (CVE-2026-13221 / -42496 / -8376, `vulnerable` on EVERY Debian release, fixed only in
  // forky/sid and rated "Minor issue" by Debian's own tracker) plus linux-libc-dev CVE-2026-43185.
  // perl cannot be dropped from a Debian image: perl-base is Essential=yes and git pulls in the
  // rest. Alpine has no perl and no libc6-dev, so the set disappears — measured on the sibling
  // Dockerfile's fully built image with `trivy image`: 13 CRITICAL -> 0. `fromImage` rather than
  // `fromAlpineImage('3.24')` so this line stays a literal tag the sibling Dockerfile's FROM can
  // be diffed against; the two resolve to the same base.
  .fromImage('node:24-alpine3.24')
  // NO TAR PATCH HERE ANY MORE, and it should not come back — the sibling Dockerfile dropped the
  // same step in the same commit. It used to copy tar@7.5.19 over the copy npm vendors at
  // usr/local/lib/node_modules/npm/node_modules/tar, because node:22-alpine3.24's npm 10.9.8
  // vendored 7.5.11 and carried CRITICAL CVE-2026-59873 (gzip-bomb DoS). node:24-alpine3.24 ships
  // npm 11.19.0, whose vendored tar is already 7.5.19 — verified on the base — so the step became
  // a no-op the moment `fromImage` above moved. If it ever recurs, patch the vendored copy again
  // rather than bumping npm: npm>=12 flips `allowScripts` off by default, which silently skips
  // opencode-ai's install script below.
  //
  // `apk upgrade` first, for the reason the sibling Dockerfile gives on this line: node:24-alpine3.24
  // is rebuilt on Node's cadence, not Alpine's, so its openssl lags the 3.24 repo and is the only
  // critical this recipe carries. NOTHING IS DELETED HERE, unlike launcher/Dockerfile, which drops
  // npm and pnpm — this is a sandbox for agent code, so npm, python3, pip, git and the compilers
  // are its runtime working surface rather than build-time bootstraps.
  //
  // Then the packages: git + the agent's clone; python3/py3-pip/make/g++ for the agent's own
  // scripts (the working surface above); bash because THIS SDK execs /bin/bash and Alpine has none
  // (see the header); jq so triage's own checks/ scripts can shape MCP JSON on the command line.
  // A raw runCmd, not `.aptInstall`, because the builder has no apkInstall — see the header.
  .runCmd('apk upgrade --no-cache && apk add --no-cache bash git ca-certificates python3 py3-pip make g++ jq', { user: 'root' })
  // OpenCode — provides the `opencode` binary agent-stream.js starts as a server. Pinned to the
  // SAME version as @opencode-ai/sdk below: they ship in lockstep, and agent-stream.js reads
  // message parts whose field names have moved between releases (see partsOf/toolCallsOf). Bump
  // both together.
  .npmInstall('opencode-ai@1.18.30', { g: true })
  // Size only, not correctness. opencode ships its runtime as a ~180 MB platform binary in
  // optionalDependencies and on musl npm installs every variant matching the arch — 2 on arm64,
  // 4 on amd64 (both -baseline flavours too) — while executing exactly one.
  //
  // PRUNE BY INODE, NOT BY NAME: `bin/opencode.exe` is a hardlink to whichever variant npm
  // resolved, and that choice is made at install time and differs per arch. Asking the filesystem
  // which variant backs the binary on PATH stays correct across arches, across -baseline variants
  // and across future renames, where a hand-written package list does not. The empty-inode guard
  // must abort rather than fall through to deleting every variant. Mirrored in the sibling
  // Dockerfile's own opencode RUN.
  .runCmd(
    'set -e; D=/usr/local/lib/node_modules/opencode-ai; INO="$(stat -c %i "$D/bin/opencode.exe")"; [ -n "$INO" ] || exit 1; for p in "$D"/node_modules/opencode-*; do [ -d "$p" ] || continue; [ "$(stat -c %i "$p/bin/opencode" 2>/dev/null)" = "$INO" ] || rm -rf "$p"; done; opencode --version >/dev/null; npm cache clean --force',
    { user: 'root' },
  )
  // Modules the in-VM scripts require from /home/user — @opencode-ai/sdk drives that server.
  //
  // DELIBERATELY npm, though the repo is otherwise on pnpm — do not "fix" this. There is no
  // lockfile to honour here, the sandbox is disposable so pnpm's shared store buys
  // nothing, and npm is already in the base image while pnpm would be another install step.
  // undici pins to ^6 ON PURPOSE: byte-identical to the sibling Dockerfile's install line so the
  // recipes for this one runtime never drift (see the header). 6.x declares node >=18.17 with no
  // upper bound. undici 8 (node >=22.19) would work on node:24-alpine3.24 but must be bumped everywhere
  // together: here, the sibling Dockerfile, AND agent-sandbox/package.json (the host-local
  // backend, also ^6.28.0). agent-stream.js needs it to lift fetch's 300s headers timeout.
  .runCmd(
    'mkdir -p /home/user && cd /home/user && npm install @opencode-ai/sdk@1.18.30 undici@^6.28.0 && npm cache clean --force',
  )
  // HOME must match the runtime user (e2b runs sandboxes as `user`) so the runtime `opencode`
  // finds its config; PIP_BREAK_SYSTEM_PACKAGES lets the agent `pip install` further deps at
  // runtime (PEP 668 externally-managed).
  .setEnvs({ HOME: '/home/user', PIP_BREAK_SYSTEM_PACKAGES: '1' })
  .runCmd('mkdir -p /home/user')
  .setWorkdir('/home/user')
  // Shared OpenCode runner required by rca.js/triage.js — must be baked beside them or their
  // `require('./agent-stream')` is a runtime crash. mcp-relay.js is required the same way, by
  // agent-stream.js itself, on the triage path only.
  .copy('agent-stream.js', '/home/user/agent-stream.js')
  .copy('mcp-relay.js', '/home/user/mcp-relay.js')
  .copy('rca.js', '/home/user/rca.js')
  .copy('triage.js', '/home/user/triage.js');
