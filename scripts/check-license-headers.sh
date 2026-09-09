#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# License-header gate: every source file in the publishable tree carries
# `SPDX-License-Identifier: Apache-2.0` near its top, in the comment syntax that file type actually
# understands. `bash scripts/check-license-headers.sh` checks; `bash scripts/check-license-headers.sh
# --fix` inserts the missing headers in place, once, mechanically; see the FIX MODE paragraph below.
#
# SCOPE: the publishable tree, not the whole checkout. This scanner reuses
# scripts/lib/export-denylist.txt, the single must-not-publish declaration already read by
# scripts/lib/export-simulate.sh, scripts/check-open-boundary.sh and
# scripts/lib/check-export-denylist.sh, as another consumer, rather than inventing a second
# exclusion list that could drift from it. Every `delete` row is out of scope here for the same
# reason it never reaches the public export: that code is not redistributed under Apache-2.0, so
# stamping an Apache-2.0 SPDX header on it would assert a license that does not actually travel
# with it. If a directory is ever un-deleted from export-denylist.txt, this scanner starts covering
# it automatically, with no second list to remember to update.
#
# EXTENSIONS COVERED, AND WHY THESE AND NOT OTHERS. .java, .py, .ts, .tsx, .js and .mjs, the
# languages this codebase ships in, all with a real line-comment syntax. Not covered, as a
# whole-extension decision (distinct from the per-path exceptions file below, which is for files
# that are an in-scope extension and still don't get a header):
#   .json / .jsonl   cannot syntactically hold a comment at all (JSON has no comment syntax); there
#                     is no per-file judgment to make, the format simply cannot carry one.
#   .sql              Liquibase changelog files under backend/**/db/changelog/ persist an MD5
#                     checksum per changeset the first time it runs; prepending a header comment to
#                     an already-applied changelog file changes that checksum and breaks Liquibase's
#                     validation on every environment that already ran it. The handful of non-
#                     changelog .sql fixtures under scripts/lib/ would be safe to header, but
#                     splitting "this .sql is a changelog, that one is a fixture" file-by-file inside
#                     one extension is exactly the kind of judgment call a future file addition would
#                     silently fall through, so the whole extension stays out rather than risk it.
#   .xml              build/tool configuration (pom.xml, the PMD/SpotBugs rulesets, logback config),
#                     not application source, the same distinction Apache's own release policy draws
#                     between a package's content and its build tooling.
#   .jsh              exactly two files exist repo-wide (classifiers/metric_drift/bridge.jsh,
#                     classifiers/tool_error/bridge.jsh) and both are already out of scope under
#                     classifiers/ above; nothing to decide today.
#
# THE PER-PATH EXCEPTIONS FILE: scripts/lib/license-header-exceptions.txt. Specific, checked-in,
# one path per line, reviewed same as scripts/lib/scrub-allowlist.txt; never a directory glob, so a
# new file added under an already-exempted path is not silently exempt. That file's own header logs
# the category audit performed (vendored files, test fixtures, generated files) and which of them
# actually produced a row today.
#
# MERGE ENFORCEMENT, THE INTERIM POSTURE. Nothing GitHub-native makes this check's failure actually
# block a merge today, so:
#   - it is wired into `task check`, the thing every contributor is expected to run before opening
#     a PR, the same de facto per-PR enforcement point `task check` already is for
#     check-open-boundary.sh, check-docs-links.sh and every other fast source-tree gate here;
#   - it also runs as a weekly `schedule` + `workflow_dispatch` job in ci.yml, a backstop that
#     covers a skipped local run.
#   Until a merge-blocking required status check is available, a broken header reaching `main` is
#   caught at the next `task check` run or the next Monday's cron, not refused at the merge button.
#   That is a real gap, named plainly rather than implied away by a green job.
#
# FIX MODE. `--fix` inserts `<comment>SPDX-License-Identifier: Apache-2.0` as the first line after
# a shebang (if the file has one) or as the literal first line otherwise, before `package` for
# Java, before any existing top-of-file comment or docstring for everything else (a leading
# `#`/`//` comment ahead of a Python module docstring does not change what the docstring is; the
# same holds for a leading comment ahead of a `package-info.java` Javadoc or a TypeScript
# triple-slash reference directive, since only a real statement ahead of them would). It never
# touches a file that already has the header anywhere in its first 5 lines, and never touches a
# file in the exceptions list or outside scope above.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
P="license-headers"

DENYLIST="scripts/lib/export-denylist.txt"
EXCEPTIONS="scripts/lib/license-header-exceptions.txt"
for f in "$DENYLIST" "$EXCEPTIONS"; do
    [ -f "$f" ] || { echo "$P: $f is missing." >&2; exit 1; }
done

FIX=0
case "${1:-}" in
    --fix) FIX=1 ;;
    "") ;;
    *) echo "$P: unknown argument '$1' (only --fix)" >&2; exit 1 ;;
esac

FIX="$FIX" DENYLIST="$DENYLIST" EXCEPTIONS="$EXCEPTIONS" python3 - <<'PY'
import fnmatch, os, re, subprocess, sys

fix = os.environ['FIX'] == '1'
denylist_path = os.environ['DENYLIST']
exceptions_path = os.environ['EXCEPTIONS']
P = 'license-headers'

EXT_COMMENT = {
    '.java': '//', '.ts': '//', '.tsx': '//', '.js': '//', '.mjs': '//',
    '.py': '#', '.sh': '#',
}
HEADER_TEXT = 'SPDX-License-Identifier: Apache-2.0'
HEADER_RE = {
    '//': re.compile(r'^//\s*' + re.escape(HEADER_TEXT) + r'\s*$'),
    '#':  re.compile(r'^#\s*' + re.escape(HEADER_TEXT) + r'\s*$'),
}


def rows(path):
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line.strip() or line.strip().startswith('#'):
            continue
        yield line


# ---- publishable-tree scope, derived from export-denylist.txt's `delete` rows ----
deleted_dirs, deleted_files, deleted_globs = [], set(), []
for line in rows(denylist_path):
    parts = line.split('|')
    if len(parts) != 4:
        continue
    pattern, kind, scope, _reason = parts
    if kind != 'delete':
        continue
    if scope == 'dir':
        deleted_dirs.append(pattern)
    elif scope == 'file':
        deleted_files.add(pattern)
    elif scope == 'glob':
        deleted_globs.append(pattern)


def out_of_scope(path):
    if path in deleted_files:
        return True
    for d in deleted_dirs:
        if path == d or path.startswith(d + '/'):
            return True
    base = os.path.basename(path)
    for g in deleted_globs:
        if fnmatch.fnmatch(base, g):
            return True
    return False


# ---- the reviewed, per-path exceptions list ----
exceptions = []
fail = False
for line in rows(exceptions_path):
    if '|' not in line:
        print(f"{P}: malformed exceptions row (need path|reason): {line}", file=sys.stderr)
        fail = True
        continue
    path, _reason = line.split('|', 1)
    exceptions.append(path)
    if not os.path.isfile(path):
        print(f"{P}: DEAD EXCEPTION ROW: '{path}' does not exist in this checkout — the path moved "
              f"or is gone; fix or remove the row in {exceptions_path}.", file=sys.stderr)
        fail = True
exceptions = set(exceptions)

# ---- the file list: tracked + untracked-not-ignored, so a file added in this very change is
# checked too (same rationale as check-docs-links.sh's tracked_files()) ----
tracked = subprocess.run(
    ['git', 'ls-files', '--cached', '--others', '--exclude-standard'],
    capture_output=True, text=True, check=True,
).stdout.splitlines()

targets = []
for path in tracked:
    if not path:
        continue
    _, ext = os.path.splitext(path)
    if ext not in EXT_COMMENT:
        continue
    if out_of_scope(path):
        continue
    if path in exceptions:
        continue
    targets.append(path)

# An exception row for a file that is out of scope for another reason (moved under a denylist
# directory, or no longer an in-scope extension) is not a dead row by the check above: it still
# resolves on disk, it is just not doing anything. That is a smaller problem than a dead row and
# not one this gate fails on; a reviewer can see it is a no-op the moment they read the diff.

missing = []
wrong_syntax = []
for path in sorted(targets):
    _, ext = os.path.splitext(path)
    comment = EXT_COMMENT[ext]
    try:
        with open(path, encoding='utf-8', errors='surrogateescape') as f:
            lines = f.readlines()
    except OSError as e:
        print(f"{P}: could not read {path}: {e}", file=sys.stderr)
        fail = True
        continue

    start = 1 if lines and lines[0].startswith('#!') else 0
    window = lines[start:start + 5]
    pat = HEADER_RE[comment]
    if any(pat.match(l.rstrip('\n')) for l in window):
        continue

    # Present but in the wrong comment syntax for this file type (e.g. a `//` header pasted into a
    # Python file) is distinct from simply missing: call it out by name rather than re-inserting a
    # second, correct header above a wrong one nobody would then notice to remove.
    other_pat = HEADER_RE['#'] if comment == '//' else HEADER_RE['//']
    if any(other_pat.match(l.rstrip('\n')) for l in window):
        wrong_syntax.append(path)
        continue

    missing.append((path, comment, start, lines))

if fix:
    fixed = 0
    for path, comment, start, lines in missing:
        header_line = f"{comment} {HEADER_TEXT}\n"
        new_lines = lines[:start] + [header_line] + lines[start:]
        # Write to a sibling temp file and rename over the original rather than truncating it in
        # place. Same reason any atomic-replace does this, plus one specific to this script: this
        # file (scripts/check-license-headers.sh) is itself a target, and a still-running bash
        # process reads its own script from an already-open file descriptor. os.replace swaps the
        # directory entry to a new inode without disturbing that descriptor, where an in-place
        # truncate-and-rewrite of the same inode a self-hosting run is actively reading from does
        # not make that guarantee.
        tmp_path = f"{path}.license-header.tmp"
        with open(tmp_path, 'w', encoding='utf-8', newline='') as f:
            f.writelines(new_lines)
        os.replace(tmp_path, path)
        fixed += 1
    print(f"{P}: --fix inserted the header into {fixed} file(s)")
    if wrong_syntax:
        print(f"{P}: {len(wrong_syntax)} file(s) carry the header in the WRONG comment syntax and "
              f"were left alone — fix these by hand, see the list below.", file=sys.stderr)
        for p in wrong_syntax:
            print(f"  {p}", file=sys.stderr)
        sys.exit(1)
    sys.exit(1 if fail else 0)

# ---- check mode ----
if missing:
    print(f"{P}: {len(missing)} file(s) in the publishable tree are missing "
          f"'{HEADER_TEXT}' in the correct comment syntax:", file=sys.stderr)
    for path, comment, _start, _lines in missing:
        print(f"  {path} (expected `{comment} {HEADER_TEXT}`)", file=sys.stderr)
    print(f"{P}: run `bash scripts/check-license-headers.sh --fix` to insert them.", file=sys.stderr)
    fail = True

if wrong_syntax:
    print(f"{P}: {len(wrong_syntax)} file(s) carry the header in the wrong comment syntax for their "
          f"file type:", file=sys.stderr)
    for p in wrong_syntax:
        print(f"  {p}", file=sys.stderr)
    fail = True

if fail:
    sys.exit(1)

print(f"{P}: ok ({len(targets)} file(s) in scope, {len(exceptions)} exception(s), all carry the header)")
PY
