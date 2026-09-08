#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write docker-compose.yml back out with its floating image defaults pinned to one version.

THE SECOND of the two transforms between the file a contributor edits and the OCI artifact a
one-command install pulls (scripts/lib/strip-compose-build.py is the first). It exists because the
repository holds NO version literal: the git tag `v<semver>` that .github/workflows/release.yml
pushes is the only source of truth for a published version, so docker-compose.yml's own defaults
float (`${TESSARY_VERSION:-latest}`) and something has to put the release's version into the copy
that ships. That something is this script, run by scripts/publish-compose-artifact.sh, which is
also where the version comes from — the tag, not a file.

Why the artifact must be pinned when the repository file is not: a clone has the file in front of
the operator and a `.env` to write TESSARY_VERSION into, so a floating default there is a
followable stack. A remote `docker compose -f oci://...:compose up -d -y` has neither, and the
published config must be able to name only images from its own release or newer — which is
epic 7 clause 1's pinning requirement, discharged here rather than by a hand-typed fallback.

The transform is line-based and deliberately narrow, for the same reason the build strip is: a
YAML round-trip would reformat the file and discard every comment, and those comments are the
file's documentation. It substitutes exactly one token, refuses to run if the file does not carry
the expected number of them, and refuses to change any line that is not one of them —
scripts/check-compose-artifact.sh asserts that last property against a rendered diff.

    python3 scripts/lib/pin-compose-version.py <in.yml> <out.yml> <version>
"""

import re
import sys

FLOATING = "${TESSARY_VERSION:-latest}"
# Three `image:` keys (backend, frontend, sandbox-runner) plus sandbox-runner's AGENT_IMAGE env
# value, which is an env value rather than a compose `image:` key but is still a pull instruction
# the artifact hands a remote install (D10's own reasoning).
EXPECTED_SITES = 4
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.\-]+)?$")


def pin(text: str, version: str) -> str:
    return text.replace(FLOATING, "${TESSARY_VERSION:-%s}" % version)


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    src, dst, version = sys.argv[1], sys.argv[2], sys.argv[3]
    if not SEMVER.match(version):
        print(f"pin-compose-version: '{version}' is not a semantic version", file=sys.stderr)
        return 1
    with open(src, encoding="utf-8") as fh:
        text = fh.read()

    found = text.count(FLOATING)
    if found != EXPECTED_SITES:
        print(
            f"pin-compose-version: {src} carries {found} floating image default(s) "
            f"({FLOATING}), expected {EXPECTED_SITES}. Either a service was added or removed "
            "(update EXPECTED_SITES here) or one default was written with a version literal, "
            "which scripts/check-version-consistency.sh forbids.",
            file=sys.stderr,
        )
        return 1

    pinned = pin(text, version)
    if FLOATING in pinned:
        print(f"pin-compose-version: {src} still carries {FLOATING} after the substitution", file=sys.stderr)
        return 1

    # The substitution is the ONLY difference, line for line. A line-based edit is only safe if it
    # can say so; this is that statement, checked rather than asserted.
    changed = [
        (a, b)
        for a, b in zip(text.splitlines(), pinned.splitlines())
        if a != b and pin(a, version) != b
    ]
    if changed or len(text.splitlines()) != len(pinned.splitlines()):
        print(f"pin-compose-version: {src} changed on a line the substitution does not explain", file=sys.stderr)
        return 1

    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(pinned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
