// SPDX-License-Identifier: Apache-2.0
import { Template } from 'e2b';

/**
 * E2B template for the agent sandbox. Same base image as the sibling Dockerfile: bump both together.
 * This SDK execs /bin/bash, which Alpine lacks, and E2B's runCmd is not root where Docker's RUN is;
 * both build cleanly and fail on every run, so re-run build.ts after touching any runCmd below.
 */
export const template = Template()
  // Alpine: the Debian base carried 13 unfixable perl CRITICALs, and Alpine has no perl. A literal tag
  // so it can be diffed against the sibling Dockerfile's FROM.
  .fromImage('node:24-alpine3.24')
  // `apk upgrade` because node:24-alpine's openssl lags the 3.24 repo. Do not bump npm to patch its
  // vendored tar: npm>=12 turns `allowScripts` off and silently skips opencode-ai's install script.
  // bash because this SDK execs /bin/bash; jq for triage's checks/ scripts.
  .runCmd('apk upgrade --no-cache && apk add --no-cache bash git ca-certificates python3 py3-pip make g++ jq', { user: 'root' })
  // Same version as @opencode-ai/sdk below: agent-stream.js reads message parts whose field names move
  // between releases.
  .npmInstall('opencode-ai@1.18.30', { g: true })
  // Size only: npm installs every ~180 MB platform variant on musl. Prune by the inode backing
  // bin/opencode.exe, since npm's pick differs per arch; the empty-inode guard must abort. Mirrored in
  // the sibling Dockerfile.
  .runCmd(
    'set -e; D=/usr/local/lib/node_modules/opencode-ai; INO="$(stat -c %i "$D/bin/opencode.exe")"; [ -n "$INO" ] || exit 1; for p in "$D"/node_modules/opencode-*; do [ -d "$p" ] || continue; [ "$(stat -c %i "$p/bin/opencode" 2>/dev/null)" = "$INO" ] || rm -rf "$p"; done; opencode --version >/dev/null; npm cache clean --force',
    { user: 'root' },
  )
  // Not execute-only here, unlike the Dockerfile: E2B's build does not keep mode 0111 and the sandbox user
  // has passwordless sudo. On E2B the session's MCP tokens are guarded only by the microVM.
  .runCmd('cp /usr/local/bin/node /usr/local/bin/node-sealed', { user: 'root' })
  // npm, not pnpm: no lockfile to honour and npm is already in the base image. Byte-identical to the
  // sibling Dockerfile's install line; undici is also pinned in package.json, bump all three together.
  .runCmd(
    'mkdir -p /home/user && cd /home/user && npm install @opencode-ai/sdk@1.18.30 undici@^6.28.0 && npm cache clean --force',
  )
  .setEnvs({ HOME: '/home/user', PIP_BREAK_SYSTEM_PACKAGES: '1' })
  .runCmd('mkdir -p /home/user')
  .setWorkdir('/home/user')
  // agent-stream.js and mcp-relay.js must be baked beside the scripts that require them.
  .copy('agent-stream.js', '/home/user/agent-stream.js')
  .copy('mcp-relay.js', '/home/user/mcp-relay.js')
  .copy('rca.js', '/home/user/rca.js')
  .copy('triage.js', '/home/user/triage.js')
  .copy('run.js', '/home/user/run.js')
  .copy('session.js', '/home/user/session.js');
