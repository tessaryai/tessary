// SPDX-License-Identifier: Apache-2.0
/*
 * Copy the license text of every FONT and ICON package we redistribute into the built output,
 * so the text physically travels with the binaries in the shipped image.
 *
 * WHY THIS EXISTS. The frontend bundles three variable fonts under the SIL Open Font License 1.1
 * and Lucide's icon set under ISC. OFL 1.1 §2 requires the copyright notice AND the license
 * itself to accompany every redistributed copy of the font, whether or not it is sold or
 * bundled; ISC requires the notice too. Naming a package in the root NOTICE does NOT satisfy
 * either — the obligation attaches to the artifact, and the artifact is the image. Vite copies
 * only the .woff2 files a stylesheet references, never the packages' license files, so before
 * this script the built image carried the font binaries and none of their text.
 *
 * A STANDALONE SCRIPT, not a Vite plugin, on purpose: it is a compliance step, so it has to be
 * runnable and assertable on its own (`node scripts/copy-dep-licenses.mjs --out <dir>`) rather
 * than only observable as a side effect of a full build. It runs AFTER `vite build` — see
 * package.json's `build` — because vite empties outDir on start and would delete what we wrote.
 *
 * WHERE THE OUTPUT GOES. dist/licenses/, which frontend/Dockerfile's
 * `COPY --from=build /app/dist /srv` carries into the runtime image, and which
 * scripts/check-notice-coverage.sh then finds by searching /srv recursively for names matching
 * LICENSE, OFL or NOTICE — that gate FAILS if this script did not run, so the two are a matched pair.
 *
 * FAILS LOUDLY, never silently. A missing package or a missing/empty LICENSE aborts the build.
 * That is the whole point: the earlier version of this idea was written against a file named
 * `OFL.txt`, which no package ships — it would have copied nothing, exited 0, and left the
 * compliance gap open behind a green build.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

// Every package whose license text must ship. `license` is documentation for the reader — the
// authority is the LICENSE file we copy, not this table. Keep in sync with frontend/package.json's
// dependencies: a font or icon package added there needs a row here, or it ships uncovered.
const PACKAGES = [
  { name: '@fontsource-variable/geist', license: 'SIL OFL 1.1' },
  { name: '@fontsource-variable/geist-mono', license: 'SIL OFL 1.1' },
  { name: '@fontsource-variable/space-grotesk', license: 'SIL OFL 1.1' },
  { name: 'lucide-react', license: 'ISC' },
];

// All four ship their text as a file named exactly `LICENSE` at the package root. Checked in
// order so a package that renames it (LICENSE.txt, or an OFL.txt someone finally does ship)
// still resolves instead of failing the build for a filename.
const LICENSE_FILENAMES = ['LICENSE', 'LICENSE.txt', 'LICENSE.md', 'OFL.txt', 'OFL.md'];

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

/*
 * Resolve a package's directory by walking node_modules upward from this file, rather than via
 * require.resolve / import.meta.resolve. Two reasons, both real here: the fontsource packages'
 * main entry is a .css file, which the resolver will not load, and their `exports` map decides
 * whether `<pkg>/package.json` is even addressable. Reading the directory directly answers the
 * only question we have — where on disk is this package — and works through pnpm's symlinked
 * node_modules/<pkg> layout, since fs follows the link.
 */
function packageDir(name) {
  let dir = root;
  for (;;) {
    const candidate = path.join(dir, 'node_modules', name);
    if (fs.existsSync(path.join(candidate, 'package.json'))) return candidate;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

// `@fontsource-variable/geist` -> `fontsource-variable-geist`, so the copied files sort together
// and carry no path separators. The `LICENSE-` prefix is what makes check-notice-coverage.sh's
// `-iname 'LICENSE*'` find them.
function outputName(name) {
  return `LICENSE-${name.replace(/^@/, '').replace(/\//g, '-')}.txt`;
}

function main() {
  const argv = process.argv.slice(2);
  const outFlag = argv.indexOf('--out');
  const outDir = path.resolve(root, outFlag === -1 ? 'dist/licenses' : argv[outFlag + 1]);

  const problems = [];
  const written = [];
  fs.mkdirSync(outDir, { recursive: true });

  for (const { name, license } of PACKAGES) {
    const dir = packageDir(name);
    if (!dir) {
      problems.push(`${name}: not installed — no node_modules/${name} above ${root}`);
      continue;
    }
    const found = LICENSE_FILENAMES.map((f) => path.join(dir, f)).find((f) => fs.existsSync(f));
    if (!found) {
      problems.push(`${name}: no license file at ${dir} (looked for ${LICENSE_FILENAMES.join(', ')})`);
      continue;
    }
    const text = fs.readFileSync(found, 'utf8');
    // An empty license file satisfies the letter of "a file is present" and none of the
    // obligation. Treat it as missing.
    if (text.trim() === '') {
      problems.push(`${name}: ${found} is empty`);
      continue;
    }
    const dest = path.join(outDir, outputName(name));
    fs.writeFileSync(dest, text);
    written.push(`  ${path.relative(root, dest)}  (${name}, ${license})`);
  }

  if (problems.length > 0) {
    console.error('copy-dep-licenses: FAIL — cannot ship these packages without their license text:');
    for (const p of problems) console.error(`  ${p}`);
    console.error("If a package was removed from the build, remove its row from PACKAGES here too.");
    process.exit(1);
  }

  console.log(`copy-dep-licenses: wrote ${written.length} license file(s) to ${path.relative(root, outDir)}/`);
  for (const w of written) console.log(w);
}

main();
