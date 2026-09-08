#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Docs reference gate — three checks over the repo's markdown:
#
#   1. every relative markdown link `[text](path)` resolves to a real file;
#   2. every backticked `*.md`/`*.mdx` filename names a markdown file that exists somewhere in the tree;
#   3. every page docs/docs.json's Mintlify nav names resolves to a real page file.
#
# MINTLIFY LINKS ARE ROUTES, NOT PATHS. Inside docs/, `[Configuration](/self-hosting/configuration)`
# names the published route for docs/self-hosting/configuration.mdx, extension left off. Check 1
# resolves a leading-slash target in a docs/ page that way; everywhere else a link is still a plain
# relative path. Widening this script to `.mdx` without that rule reports all nine existing
# cross-links in the published set as broken.
#
# `.mdx` JOINED THE SCANNED SET IN #1192, AND CHECK 3 CAME WITH IT. This script filtered on
# `rel.endswith('.md')`, and every page this project actually PUBLISHES is `.mdx` — docs/index.mdx
# plus the five under docs/self-hosting/. So all six published pages, and docs.json itself, sat
# outside every check in the repo while the gate printed a green file count: the same false-green
# shape rule 6 of check-open-boundary.sh had before #1105. The published pages happened to be
# link-clean, which is what made it invisible. Check 3 exists because docs.json is the ONLY thing
# that says which pages the site has, nothing had ever opened it, and a nav entry naming a page
# that was renamed or deleted is a 404 in the published site that no other check in this repo can
# see.
#
# WHY THIS EXISTS. AGENTS.md § Documentation policy already says "After moving or renaming a doc,
# grep -rn for the old path and retarget every link in the same PR". Nothing enforced it, and the
# rule has been missed repeatedly: #693 deleted launch.md and left nine citations behind, #181
# deleted self_test_patterns.md and left three references inside the judge-authoring prompts that
# ship to the model, and #824 deleted the shipped plan docs and needed #825 to clean up after it.
# A rule that is only remembered is a rule that rots; this is the half a machine can hold.
#
# CHECK 2 IS THE ONE THAT MATTERS. All three incidents above cited the dead file as a BACKTICKED
# NAME, not a markdown link, so a link checker alone would have caught none of them — #825 said so
# in as many words. Check 1 still earns its place (it caught two glossary links that were a
# directory level short), but check 2 is the one shaped like the actual failure.
#
# SCOPE OF CHECK 2. Backticked `*.md` and `*.mdx` names only — not `.py`/`.json`/`.java`, where docs
# legitimately name forward references to planned files and package-suffix shorthands, and where
# the false-positive rate would be high enough that the gate would be switched off. A bare
# basename match anywhere in the tree is enough: the point is "this document still exists", not
# "this path is exactly right", which keeps package-relative citations from crying wolf.
#
# GENERATED_DOCS are the names that legitimately do not exist in the tree: files an agent or a
# tool WRITES at runtime, described here so a reader knows what to expect. Adding a name is a
# deliberate act — if a doc citation starts failing, the first question is whether the doc was
# deleted, not whether to add it here.
#
# FENCED BLOCKS ARE SKIPPED. A doc that teaches link syntax, or quotes a broken-link bug report,
# puts markdown inside a ``` fence; treating that as a citation is the cry-wolf failure this gate
# is trying to avoid. Inline single-backtick spans still count — that is where citations live.
#
# EXISTENCE IS CASE-SENSITIVE even on a case-insensitive filesystem (macOS APFS). os.path.exists
# would accept `Foo.MD` for `foo.md` locally and fail on CI's ext4 up to a week later, which would
# break the "local green ⇒ CI green" property this gate is supposed to have.
#
# ONLY GIT-TRACKED FILES ARE SCANNED. The repo hosts full nested checkouts at .claude/worktrees/
# and .crew/workspaces/ (agent-loop and crew scratch clones). Walking the filesystem finds their
# vendored contract/CHANGELOG.md copies and reports thousands of "broken" links in files that are
# not ours and are not even tracked. git ls-files is the correct definition of "this repo's docs".
#
# contract/ is exempt entirely: it is a verbatim vendored copy of the plugin repo (AGENTS.md §
# Repo map), so its references point at that repo's layout and are not ours to retarget.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

python3 - <<'PY'
import os, re, subprocess, sys

SKIP = {'node_modules', '.venv', '.git', '.jj', 'target', 'dist', '__pycache__', 'out', 'artifacts',
        '.claude', '.crew'}
LINK = re.compile(r'(?<!\!)\[[^\]]*\]\(([^)\s]+)')
TICKED_MD = re.compile(r'`([A-Za-z0-9_./\-]+\.mdx?)`')
FENCE = re.compile(r'^\s*(```|~~~)')

# Runtime-written documents, not repo files. Each is produced by a tool or an agent mid-run.
GENERATED_DOCS = {
    'ESCALATION.md',   # crew writes it into .crew/<slug>/ when it escalates
    'verify.md',       # agent-loop writes it into state/screenshots/<n>/
    'finding.md',      # agent-sandbox / RCA agent output
    'checklist.md',    # agent-sandbox output
    'report.md',       # the evals-synth plugin writes it into the .tessary/ bundle
    'ANNOTATION.md',   # annotation run output, classifiers/
    'TRIAL_LOG.md',    # annotation trial log, classifiers/experiments/
    'sop.md',          # per-project SOP the conformance compiler emits
}

def exists_cased(path):
    """os.path.exists, but honouring case on case-insensitive filesystems."""
    path = os.path.normpath(path)
    if not os.path.exists(path):
        return False
    head, tail = os.path.split(path)
    if not tail:                                     # root or trailing separator
        return True
    try:
        if tail not in os.listdir(head or '.'):
            return False
    except OSError:
        return False
    return exists_cased(head) if head not in ('', os.sep, '.') else True


def tracked_files():
    """Every file git tracks OR would track — untracked-but-not-ignored included, so a doc added
    in this very commit is checked. None when git is unavailable / this is not a checkout.
    .claude/worktrees and .crew are both gitignored, so --exclude-standard drops them."""
    try:
        out = subprocess.run(
            ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'],
            capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return [f for f in out.stdout.decode('utf-8', 'replace').split('\0') if f]


def walked_files():
    found = []
    for dirpath, dirnames, filenames in os.walk('.'):
        dirnames[:] = [d for d in dirnames if d not in SKIP]
        for name in filenames:
            found.append(os.path.relpath(os.path.join(dirpath, name), '.'))
    return found


md_paths, md_names = [], set()
tracked = tracked_files()
if tracked is None:
    # #889 AC5: a real jj workspace (no .git of its own) is normal, current, load-bearing
    # operation for this repo's own dev workflow — degrade and continue. A tree with NEITHER
    # .git NOR .jj is a different animal: the untethered-export case #889's own "one wrinkle
    # worth recording" paragraph warns about (e.g. a bare `git archive | tar -x` with no
    # `git init`). A filesystem walk there would be a vacuous pass with no real enforcement —
    # fail loud instead and point at the fix (scripts/lib/export-simulate.sh, which leaves a
    # real .git behind so this check runs for real).
    if os.path.isdir('.jj'):
        print(
            "check-docs-links: git ls-files unavailable (a jj workspace has no .git of its own), "
            "falling back to a filesystem walk. Nested checkouts are skipped by name rather than "
            "by git, so this run is a weaker guarantee than one from the primary repo.",
            file=sys.stderr,
        )
    else:
        print(
            "check-docs-links: git ls-files unavailable and this is not a jj workspace either "
            "(no .git, no .jj) -- this looks like a naive export (e.g. a bare `git archive | tar "
            "-x` with no `git init`), the untethered-tree case #889 warns about: a filesystem "
            "walk here would be a vacuous pass with no real enforcement. Use "
            "scripts/lib/export-simulate.sh instead -- it leaves a real .git behind so this check "
            "runs for real.",
            file=sys.stderr,
        )
        sys.exit(1)

# `.mdx` as well as `.md` (#1192): the published pages are all `.mdx`, and a gate that cannot see
# the files a stranger actually reads is not a gate.
for rel in (tracked if tracked is not None else walked_files()):
    if rel.endswith(('.md', '.mdx')):
        md_paths.append(rel)
        md_names.add(os.path.basename(rel))

broken, dangling = [], []
for path in sorted(md_paths):
    if path.startswith('contract' + os.sep):        # vendored verbatim; not ours to fix
        continue
    base = os.path.dirname(path)
    in_fence = False
    with open(path, encoding='utf-8', errors='replace') as fh:
        for lineno, line in enumerate(fh, 1):
            if FENCE.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for match in LINK.finditer(line):
                target = match.group(1)
                if target.startswith(('http://', 'https://', 'mailto:', '#')):
                    continue
                bare = target.split('#')[0]
                if not bare:
                    continue
                if bare.startswith('/') and path.startswith('docs' + os.sep):
                    # A Mintlify cross-link is a SITE ROUTE, not a filesystem path: from any page,
                    # `/self-hosting/setup` is docs/self-hosting/setup.mdx with the extension left
                    # off. Resolving it relative to the citing file (or as an absolute filesystem
                    # path) would report every correct cross-link in the published set as broken —
                    # which is what a naive widening of this check to `.mdx` does first.
                    route = bare.rstrip('/').lstrip('/')
                    if not any(exists_cased(os.path.join('docs', route + ext))
                               for ext in ('.mdx', '.md', '')):
                        broken.append((path, lineno, target))
                elif not exists_cased(os.path.join(base, bare)):
                    broken.append((path, lineno, target))
            for match in TICKED_MD.finditer(line):
                name = os.path.basename(match.group(1))
                if name not in md_names and name not in GENERATED_DOCS:
                    dangling.append((path, lineno, match.group(1)))

# ---- 3. every page docs.json's nav names resolves ----
# Mintlify resolves a nav entry as a path under docs/ with the extension left off, so
# "self-hosting/setup" is docs/self-hosting/setup.mdx. Entries can be plain strings or nested
# group objects; external entries carry an href instead of a page path and are not ours to resolve.
nav_broken = []
NAV = os.path.join('docs', 'docs.json')
if os.path.exists(NAV):
    import json
    try:
        nav_doc = json.load(open(NAV, encoding='utf-8'))
    except ValueError as exc:
        print(f"FAILED: {NAV} is not valid JSON: {exc}", file=sys.stderr)
        raise SystemExit(1)

    def nav_pages(node):
        """Every page-path string reachable under any "pages" array, at any nesting depth."""
        if isinstance(node, dict):
            for key, value in node.items():
                if key == 'pages' and isinstance(value, list):
                    for entry in value:
                        if isinstance(entry, str):
                            yield entry
                        else:
                            yield from nav_pages(entry)
                else:
                    yield from nav_pages(value)
        elif isinstance(node, list):
            for entry in node:
                yield from nav_pages(entry)

    for page in nav_pages(nav_doc):
        if page.startswith(('http://', 'https://', 'mailto:')):
            continue
        if not any(exists_cased(os.path.join('docs', page + ext)) for ext in ('.mdx', '.md')):
            nav_broken.append(page)

if broken or dangling or nav_broken:
    if broken:
        print("FAILED: markdown links that do not resolve\n", file=sys.stderr)
        for path, lineno, target in broken:
            print(f"  {path}:{lineno}  ->  {target}", file=sys.stderr)
        print("", file=sys.stderr)
    if dangling:
        print("FAILED: backticked .md filenames naming a document that no longer exists\n", file=sys.stderr)
        for path, lineno, target in dangling:
            print(f"  {path}:{lineno}  ->  `{target}`", file=sys.stderr)
        print(
            "\n  If the document was deleted, drop the citation and keep the claim it supported."
            "\n  If a tool writes it at runtime, add the name to GENERATED_DOCS in this script.",
            file=sys.stderr,
        )
    if nav_broken:
        print("FAILED: docs/docs.json nav entries naming a page that does not exist\n", file=sys.stderr)
        for page in nav_broken:
            print(f"  docs/docs.json  ->  {page}  (expected docs/{page}.mdx)", file=sys.stderr)
        print(
            "\n  Every nav entry is a page in the published site. An entry with no file behind it"
            "\n  is a 404 for a reader who clicks it. Retarget the entry or restore the page.",
            file=sys.stderr,
        )
    print(
        f"\n{len(broken) + len(dangling) + len(nav_broken)} dangling reference(s). Retarget them in"
        " the PR that moved or deleted the file (AGENTS.md § Documentation policy).",
        file=sys.stderr,
    )
    raise SystemExit(1)

mdx_count = sum(1 for p in md_paths if p.endswith('.mdx'))
print(f"docs-links check OK: {len(md_paths)} markdown files ({mdx_count} .mdx), "
      f"{len(list(nav_pages(nav_doc))) if os.path.exists(NAV) else 0} docs.json nav entries")
PY
