#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write docker-compose.yml back out with every service's `build:` block removed.

This is the ONE transform between the file a contributor edits and the OCI artifact a
one-command install pulls (epic 7's one-command install), and it exists for one reason:
`docker compose publish` runs `docker push` for every service that carries a `build:` section.
In the release workflow that runner is logged in to Docker Hub as the publisher of
`tessaryai/tessary`, so publishing the file as-is would push whatever local image happens to be
tagged `tessaryai/tessary:backend-<version>` over the multi-arch manifest the release just built.
The published application images must come out of the release build and nothing else, so the
artifact is made from a copy with no build sections at all. Verified behaviour, not a precaution:
publishing the unstripped file prints `tessaryai/tessary:frontend-<version> Pushing` and then fails on
`push access denied` when the runner is not authorised — which on an authorised runner is not a
failure, it is the overwrite.

Dropping `build:` costs a remote install nothing: `--build` cannot work there anyway, because the
build contexts (`./backend`, `./sandbox-runner/launcher`) live in a repository it has not cloned.

The transform is deliberately line-based rather than a YAML round-trip: a round-trip would
reformat the file and discard every comment in it, and those comments are the file's documentation.
Callers must run the parity assertion in scripts/check-compose-artifact.sh, which renders both
files with `docker compose config` and requires that removing `build` from the original's rendering
makes the two identical — that is what makes a line-based edit safe.

    python3 scripts/lib/strip-compose-build.py <in.yml> <out.yml>
"""

import re
import sys


def strip_build_blocks(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    skip_indent: int | None = None
    for line in lines:
        if skip_indent is not None:
            if line.strip() == "":
                # A blank line inside a block is only inside it if something deeper follows; treat
                # it as a terminator, which is safe because a blank line is never load-bearing YAML.
                skip_indent = None
            else:
                indent = len(line) - len(line.lstrip(" "))
                if indent > skip_indent:
                    continue
                skip_indent = None
        m = re.match(r"^(\s+)build:\s*$", line)
        if m:
            skip_indent = len(m.group(1))
            continue
        out.append(line)
    return "".join(out)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8") as fh:
        text = fh.read()
    stripped = strip_build_blocks(text)
    if re.search(r"^\s+build:\s*$", stripped, re.MULTILINE):
        print(f"strip-compose-build: {src} still carries a `build:` key after the strip", file=sys.stderr)
        return 1
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(stripped)
    return 0


if __name__ == "__main__":
    sys.exit(main())
