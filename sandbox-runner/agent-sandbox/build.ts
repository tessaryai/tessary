// SPDX-License-Identifier: Apache-2.0
import 'dotenv/config';
import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { Sandbox, Template, defaultBuildLogger } from 'e2b';
import { template } from './template';

/**
 * Builds, verifies and tags the agent sandbox's E2B cloud template.
 *
 * THE ONE PLACE THAT TALKS TO E2B. Every call the release makes lives here rather than inlined in
 * .github/workflows/release.yml, for the same reason scripts/publish-compose-artifact.sh owns
 * every oras call: a workflow step cannot be run on a laptop, and this path's failures (a template
 * that builds and then cannot exec, see template.ts's header) are exactly the ones you want to
 * reproduce by hand.
 *
 *   tsx build.ts                     build + publish under the default tag (the human path)
 *   tsx build.ts --release=<semver>  build IF THE RECIPE CHANGED, tag <semver> + recipe-<hash>
 *   tsx build.ts --verify=<semver>   boot :<semver> and prove the runtime actually works
 *   tsx build.ts --promote=<semver>  move `latest` and `default` onto :<semver>
 *   tsx build.ts --rollback=<semver> remove the <semver> tag (the release's own rollback)
 *
 * Needs E2B_API_KEY for the team that owns the template, in the environment or ./.env.
 *
 * THE FOUR RELEASE VERBS MAP ONTO release.yml'S JOB ORDER, and the split is the same one that
 * governs the docker images: --release and --verify are ADDITIVE (new tags nobody is using, which
 * --rollback can simply delete), while --promote moves floating tags that the previous release is
 * still serving from and deletion cannot undo. That is why --promote runs in finalize and nowhere
 * earlier.
 */

/** The template's name inside our own E2B project. Build and tag operations use this. */
const NAME = 'tessary-agent-sandbox';
/**
 * What everybody ELSE writes, and therefore what compose, server.js and --verify use. E2B
 * namespaces a template by its project slug, so the bare NAME above resolves only for a key
 * belonging to this project — a self-hoster who sets SANDBOX_BACKEND=e2b with their own key needs
 * the namespaced form or `Sandbox.create` finds nothing. Held equal to docker-compose.yml's
 * E2B_ANALYZER_TEMPLATE default by scripts/check-version-consistency.sh.
 */
const PUBLIC_REF = 'tessary/tessary-agent-sandbox';

/**
 * Files template.ts bakes into /home/user/tessary-contract — the bundle contract the observer's
 * prompts point the agent at. Staged from the platform's own contract/ copies (the single source
 * of truth since the plugin dropped its synthesis machinery) into ./vendor/, because the e2b
 * build context is this directory and cannot reach ../../contract directly.
 */
const CONTRACT_FILES = [
  'validate.py',
  'pipeline_io.py',
  'AUTHORING_CONTRACT.md',
  'output_format.md',
  'grader.schema.json',
];

/** cpuCount/memoryMB are build INPUTS — a change to either produces a different sandbox. */
const RESOURCES = { cpuCount: 2, memoryMB: 2048 };

/**
 * Everything that can change what the built template contains. Anything listed here is hashed into
 * the recipe tag below; anything NOT listed is, by that omission, a claim that it cannot affect the
 * image. Keep it exhaustive — a missed input means a changed template silently reuses an old build.
 */
const RECIPE_INPUTS = [
  'template.ts',
  'agent-stream.js',
  'rca.js',
  'triage.js',
  'tessary-evals-validate',
  ...CONTRACT_FILES.map((f) => `../../contract/${f}`),
];

function stageContract() {
  const src = path.resolve(__dirname, '../../contract');
  const vendor = path.resolve(__dirname, 'vendor');
  fs.rmSync(vendor, { recursive: true, force: true });
  fs.mkdirSync(vendor);
  for (const f of CONTRACT_FILES) {
    fs.copyFileSync(path.join(src, f), path.join(vendor, f));
  }
}

/**
 * A content address for the recipe, carried as a tag on the build it produced.
 *
 * THIS IS HOW "HAS IT CHANGED?" GETS ANSWERED WITHOUT ANY STATE IN THIS REPO. The alternative —
 * `git diff <previous tag> -- sandbox-runner/agent-sandbox/` — is a statement about the SOURCE, and
 * it is wrong in precisely the case that matters: a release whose template build failed leaves the
 * source unchanged at the next attempt, so a diff-based check would skip the rebuild and tag a
 * version onto a template that never got the change. Asking the registry which recipes it already
 * holds is self-correcting, because the thing being asked is the thing being published.
 *
 * The path is hashed alongside the bytes so that moving a file is a change, and the inputs are
 * sorted so the digest cannot depend on the order of the list above.
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

/** GitHub Actions reads job outputs off stdout as `key=value`; a local run just sees them. */
function emit(key: string, value: string) {
  console.log(`${key}=${value}`);
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
  }
}

/**
 * The one thing the SDK has no helper for. `public` decides whether a key from ANOTHER E2B project
 * can resolve PUBLIC_REF at all, and it is a console toggle a human can flip back without touching
 * this repo — so the release ASSERTS it rather than setting it. Asserting is deliberate: a
 * workflow that quietly re-publishes a template somebody deliberately made private is a worse
 * failure than a red release telling them which it is.
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

/** Build and publish under the default tag: the documented by-hand path, unchanged. */
async function buildDefault() {
  stageContract();
  const info = await Template.build(template, NAME, { ...RESOURCES, onBuildLogs: defaultBuildLogger() });
  console.log(`built template '${info.name}' (id=${info.templateId}) tags=${info.tags?.join(',')}`);
}

/**
 * ADDITIVE ONLY. Writes `<version>` and `recipe-<hash>`, both new, both removable by --rollback.
 * Writes NEITHER `latest` NOR `default` — those are --promote's, because they already point at the
 * previous release and deleting them is an outage rather than a rollback.
 */
async function release(version: string) {
  const recipe = recipeHash();
  emit('recipe', recipe);

  const existing = await Template.getTags(NAME).catch(() => []);
  const match = existing.find((t) => t.tag === recipe);
  if (match) {
    // The recipe is already published, so there is nothing to build — just give this release's
    // number to the build that already carries it. A rebuild here would be identical by
    // construction and cost ~10 minutes to prove it.
    await Template.assignTags(`${NAME}:${recipe}`, [version]);
    console.log(`recipe unchanged (${recipe}); tagged existing build ${match.buildId} as ${version}`);
    emit('rebuilt', 'false');
    emit('build_id', match.buildId);
    return;
  }

  stageContract();
  const info = await Template.build(template, NAME, {
    ...RESOURCES,
    tags: [version, recipe],
    onBuildLogs: defaultBuildLogger(),
  });
  console.log(`built ${info.name} id=${info.templateId} build=${info.buildId} tags=${info.tags?.join(',')}`);
  emit('rebuilt', 'true');
  emit('build_id', info.buildId);
}

/**
 * THE STEP THAT CAN ACTUALLY GO RED, and the reason it exists is written in template.ts's own
 * header: this SDK hardcodes `cmd: "/bin/bash"`, E2B's runCmd is not root where Docker's RUN is,
 * and BOTH of those produce a template that BUILDS CLEANLY and then fails on every single run. No
 * image build can surface either. So the release boots the thing and runs commands through it.
 *
 * It runs on the dedup path too. "We did not rebuild" is a statement about the recipe, not about
 * whether the published build still resolves and boots.
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
    // Each of these is a distinct failure this release must not ship, not a smoke test for its own
    // sake: bash missing (the SDK cannot exec at all), the opencode prune having deleted the
    // variant on PATH, PyYAML absent (the validator crashes in-VM), a script or contract file that
    // never got copied, and the native re2 addon failing to load.
    const checks: Array<[string, string]> = [
      ['bash is the exec shell', 'echo "$BASH_VERSION" | grep -q .'],
      ['opencode runs', 'opencode --version'],
      ['pyyaml is baked', 'python3 -c "import yaml"'],
      ['validator is on PATH', 'command -v tessary-evals-validate'],
      ['contract is baked', 'test -f /home/user/tessary-contract/validate.py'],
      ['agent scripts are baked', 'test -f /home/user/rca.js && test -f /home/user/triage.js && test -f /home/user/agent-stream.js'],
      ['native modules load', 'cd /home/user && node -e "require(\'re2\'); require(\'acorn\')"'],
    ];
    const failed: string[] = [];
    for (const [label, cmd] of checks) {
      const r = await sbx.commands.run(cmd, { timeoutMs: 60_000 }).catch((e: unknown) => ({
        exitCode: 1,
        stderr: e instanceof Error ? e.message : String(e),
        stdout: '',
      }));
      if (r.exitCode === 0) {
        console.log(`  ok    ${label}`);
      } else {
        console.error(`  FAIL  ${label}: ${(r.stderr || r.stdout || '').trim().slice(0, 400)}`);
        failed.push(label);
      }
    }
    if (failed.length) throw new Error(`${ref} booted but ${failed.length} check(s) failed: ${failed.join(', ')}`);
    console.log(`OK: ${ref} resolves, boots and passes ${checks.length} checks`);
  } finally {
    await sbx.kill().catch(() => {});
  }
}

/**
 * THE COMMIT POINT'S SHARE OF THE WORK. `latest` is what compose's floating default resolves to;
 * `default` is what a BARE `tessary-agent-sandbox` reference means to E2B, which is what every
 * config written before this change says. Both move together so the two spellings can never
 * disagree about which build is current.
 */
async function promote(version: string) {
  const res = await Template.assignTags(`${NAME}:${version}`, ['latest', 'default']);
  console.log(`promoted ${NAME}:${version} -> latest, default (build ${res.buildId})`);
}

/**
 * The failure path only. Takes away the version tag this run added, and DELIBERATELY LEAVES
 * `recipe-<hash>`: it names a build by its contents, so it cannot come to mean the wrong thing, and
 * leaving it is what lets the retry skip a rebuild it already paid for.
 */
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
