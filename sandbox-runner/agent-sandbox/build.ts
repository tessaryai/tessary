// SPDX-License-Identifier: Apache-2.0
import 'dotenv/config';
import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { CommandExitError, Sandbox, Template, defaultBuildLogger } from 'e2b';
import { template } from './template';

/**
 * Builds, verifies and tags the agent sandbox's E2B template; the release calls only this so every
 * step can be reproduced locally. --release and --verify only add tags; --promote moves the floating
 * tags the previous release still serves, so it runs in finalize.
 */

const NAME = 'tessary-agent-sandbox';
/**
 * E2B namespaces a template by project slug, so a key from another project needs this form. Held equal
 * to docker-compose.yml's default by scripts/check-version-consistency.sh.
 */
const PUBLIC_REF = 'tessary/tessary-agent-sandbox';

const RESOURCES = { cpuCount: 2, memoryMB: 2048 };

/** Everything that can change the built template; a missed input silently reuses an old build. */
const RECIPE_INPUTS = [
  'template.ts',
  'agent-stream.js',
  'mcp-relay.js',
  'lane.js',
  'session.js',
];

/**
 * Asks the registry which recipes it holds rather than diffing source, which would skip the rebuild
 * after a failed template build. The path is hashed too so a move is a change.
 */
function recipeHash(): string {
  const h = crypto.createHash('sha256');
  h.update(JSON.stringify(RESOURCES));
  for (const rel of [...RECIPE_INPUTS].sort()) {
    h.update('\0');
    h.update(rel);
    h.update('\0');
    h.update(fs.readFileSync(path.resolve(__dirname, rel)));
  }
  return `recipe-${h.digest('hex').slice(0, 12)}`;
}

function emit(key: string, value: string) {
  console.log(`${key}=${value}`);
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
  }
}

/**
 * `public` is a console toggle, so the release asserts it rather than silently re-publishing a
 * template someone made private.
 */
async function templateInfo(): Promise<{ templateID: string; public: boolean; names: string[] }> {
  const key = process.env.E2B_API_KEY;
  if (!key) throw new Error('E2B_API_KEY is not set');
  const domain = process.env.E2B_DOMAIN || 'e2b.dev';
  const res = await fetch(`https://api.${domain}/v2/templates`, { headers: { 'X-API-KEY': key } });
  if (!res.ok) throw new Error(`GET /v2/templates -> ${res.status} ${await res.text()}`);
  const list = (await res.json()) as Array<{ templateID: string; public: boolean; names: string[] }>;
  const found = list.find((t) => (t.names || []).some((n) => n === PUBLIC_REF || n === NAME));
  if (!found) {
    throw new Error(
      `no template named ${PUBLIC_REF} in this project. Either E2B_API_KEY belongs to a different `
        + `team, or the template was never built — run \`pnpm exec tsx build.ts\` with the team's key.`,
    );
  }
  return found;
}

async function buildDefault() {
  const info = await Template.build(template, NAME, { ...RESOURCES, onBuildLogs: defaultBuildLogger() });
  console.log(`built template '${info.name}' (id=${info.templateId}) tags=${info.tags?.join(',')}`);
}

/** Never writes `latest` or `default`: they point at the previous release, and deleting them is an outage. */
async function release(version: string) {
  const recipe = recipeHash();
  emit('recipe', recipe);

  const existing = await Template.getTags(NAME).catch(() => []);
  const match = existing.find((t) => t.tag === recipe);
  if (match) {
    await Template.assignTags(`${NAME}:${recipe}`, [version]);
    console.log(`recipe unchanged (${recipe}); tagged existing build ${match.buildId} as ${version}`);
    emit('rebuilt', 'false');
    emit('build_id', match.buildId);
    return;
  }

  const info = await Template.build(template, NAME, {
    ...RESOURCES,
    tags: [version, recipe],
    onBuildLogs: defaultBuildLogger(),
  });
  console.log(`built ${info.name} id=${info.templateId} build=${info.buildId} tags=${info.tags?.join(',')}`);
  emit('rebuilt', 'true');
  emit('build_id', info.buildId);
}

const SEALED_NODE = '/usr/local/bin/node-sealed';

type CheckResult = { exitCode: number | null; stdout: string; stderr: string; error?: string };

/** A CommandExitError's message is only "exit status <n>"; the reason is on its stdout/stderr. */
async function runCheck(sbx: Sandbox, cmd: string): Promise<CheckResult> {
  try {
    const r = await sbx.commands.run(cmd, { timeoutMs: 60_000 });
    return { exitCode: r.exitCode, stdout: r.stdout, stderr: r.stderr };
  } catch (e) {
    if (e instanceof CommandExitError) return { exitCode: e.exitCode, stdout: e.stdout, stderr: e.stderr };
    return { exitCode: null, stdout: '', stderr: '', error: e instanceof Error ? e.message : String(e) };
  }
}

function indent(text: string): string {
  const lines = text.trim().slice(0, 2000);
  return lines ? lines.split('\n').map((l) => `      ${l}`).join('\n') : '      (empty)';
}

/**
 * Boots the template, since a missing bash or a non-root runCmd builds cleanly and fails every run.
 * Runs on the dedup path too.
 */
async function verify(version: string) {
  const info = await templateInfo();
  if (!info.public) {
    throw new Error(
      `${PUBLIC_REF} is not public. A self-hoster's own E2B key cannot resolve it, so the template `
        + `reference this release stamps into the compose artifact would be dead for everyone `
        + `outside this team. Re-publish it in the E2B console's Templates tab (or `
        + `\`e2b template publish ${NAME}\`) and re-run.`,
    );
  }
  if (!info.names.includes(PUBLIC_REF)) {
    throw new Error(
      `this project publishes the template as ${info.names.join(', ')}, but this repo references `
        + `${PUBLIC_REF}. The project slug changed; update PUBLIC_REF here and the `
        + `E2B_ANALYZER_TEMPLATE defaults check-version-consistency.sh holds equal to it.`,
    );
  }

  const ref = `${PUBLIC_REF}:${version}`;
  console.log(`booting ${ref} (templateID=${info.templateID}, public=${info.public})`);
  const sbx = await Sandbox.create(ref, { timeoutMs: 180_000 });
  try {
    const checks: Array<[string, string]> = [
      ['bash is the exec shell', 'echo "$BASH_VERSION" | grep -q .'],
      ['opencode runs', 'opencode --version'],
      ['jq runs', 'jq --version'],
      ['python3 runs', 'python3 --version'],
      [
        'agent scripts are baked',
        'test -f /home/user/lane.js && test -f /home/user/agent-stream.js && test -f /home/user/mcp-relay.js'
          + ' && test -f /home/user/session.js',
      ],
      ['the session node is executable', `test -x ${SEALED_NODE}`],
      ['the session node starts', `${SEALED_NODE} -e 0`],
    ];
    const failed: string[] = [];
    for (const [label, cmd] of checks) {
      const r = await runCheck(sbx, cmd);
      if (r.exitCode === 0) {
        console.log(`  ok    ${label}`);
      } else {
        console.error(`  FAIL  ${label} (exit ${r.exitCode})`);
        if (r.error) console.error(`    error: ${r.error}`);
        console.error(`    stdout:\n${indent(r.stdout)}\n    stderr:\n${indent(r.stderr)}`);
        failed.push(label);
      }
    }
    if (failed.length) throw new Error(`${ref} booted but ${failed.length} check(s) failed: ${failed.join(', ')}`);
    console.log(`OK: ${ref} resolves, boots and passes ${checks.length} checks`);
  } finally {
    await sbx.kill().catch(() => {});
  }
}

/** `default` is what a bare template name means to E2B; both move together. */
async function promote(version: string) {
  const res = await Template.assignTags(`${NAME}:${version}`, ['latest', 'default']);
  console.log(`promoted ${NAME}:${version} -> latest, default (build ${res.buildId})`);
}

/** Keeps `recipe-<hash>`: it names a build by content, and keeping it lets the retry skip the rebuild. */
async function rollback(version: string) {
  await Template.removeTags(NAME, [version]);
  console.log(`removed tag ${version} from ${NAME}`);
}

function argOf(flag: string): string | undefined {
  const hit = process.argv.slice(2).find((a) => a.startsWith(`--${flag}=`));
  return hit?.slice(flag.length + 3);
}

async function main() {
  const verbs = ['release', 'verify', 'promote', 'rollback'] as const;
  const verb = verbs.find((v) => argOf(v) !== undefined);
  if (!verb) return buildDefault();

  const version = argOf(verb)!;
  if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(version)) {
    throw new Error(`--${verb} takes a semantic version, got '${version}'`);
  }
  if (verb === 'release') return release(version);
  if (verb === 'verify') return verify(version);
  if (verb === 'promote') return promote(version);
  return rollback(version);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
