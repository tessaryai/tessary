// SPDX-License-Identifier: Apache-2.0
import 'dotenv/config';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { Template, defaultBuildLogger } from 'e2b';
import { template } from './template';

/**
 * Builds + publishes the observer-analysis template to the E2B cloud under the
 * alias the launcher expects (E2B_ANALYZER_TEMPLATE=tessary-agent-sandbox).
 *
 * Run from this directory:
 *   pnpm install
 *   E2B_API_KEY=<your team's key> pnpm exec tsx build.ts     (or put E2B_API_KEY in ./.env)
 *
 * The alias is stable, so rebuilds update the same template; the launcher's
 * E2B_API_KEY must be for the SAME team this is built under.
 */
const ALIAS = 'tessary-agent-sandbox';

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

function stageContract() {
  const src = path.resolve(__dirname, '../../contract');
  const vendor = path.resolve(__dirname, 'vendor');
  fs.rmSync(vendor, { recursive: true, force: true });
  fs.mkdirSync(vendor);
  for (const f of CONTRACT_FILES) {
    fs.copyFileSync(path.join(src, f), path.join(vendor, f));
  }
}

async function main() {
  stageContract();
  const info = await Template.build(template, ALIAS, {
    cpuCount: 2,
    memoryMB: 2048,
    onBuildLogs: defaultBuildLogger(),
  });
  console.log(`built template '${info.name}' (id=${info.templateId}) tags=${info.tags?.join(',')}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
