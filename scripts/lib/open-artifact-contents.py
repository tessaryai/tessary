# SPDX-License-Identifier: Apache-2.0
"""Enumerate one exported image filesystem and diff it against the open-artifact deny list.

Reads a `docker export` tar (never runs the image: an image that cannot start on this host is
still an image whose contents can be listed), walks every file, opens every jar, and reports each
rule from scripts/lib/open-artifact-denylist.txt that matches. Prints the enumeration the clause
asks for (what the artifact holds, by class) before the verdict, so a green run is a statement
about a listed set rather than a silent pass.

    python3 open-artifact-contents.py --role backend --tar image.tar \
        --denylist scripts/lib/open-artifact-denylist.txt --open-modules shared,contract,...

Exit 0 on a clean artifact, 1 on any finding, 2 on a usage error.
"""

from __future__ import annotations

import argparse
import io
import re
import sys
import tarfile
import zipfile

TEXT_ROOTS = {"frontend": ("srv/",)}
TEXT_EXTS = (".js", ".mjs", ".html", ".css", ".map", ".json")
MAX_TEXT_BYTES = 64 * 1024 * 1024


def load_rules(path: str) -> list[tuple[str, str, str]]:
    rules = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            kind, rest = line.split("|", 1)
            pattern, why = rest.rsplit("|", 1)
            rules.append((kind, pattern, why))
    return rules


def jar_stem(name: str) -> str:
    base = name.rsplit("/", 1)[-1]
    return re.sub(r"-\d.*$", "", base[:-4]) if base.endswith(".jar") else base


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--role", required=True, choices=["backend", "frontend", "sandbox-runner", "agent-sandbox"])
    ap.add_argument("--tar", required=True)
    ap.add_argument("--denylist", required=True)
    ap.add_argument("--open-modules", default="", help="comma-separated open reactor modules (backend only)")
    args = ap.parse_args()

    rules = load_rules(args.denylist)
    text_rules = [(re.compile(pattern), why) for kind, pattern, why in rules if kind == "text"]
    open_modules = {m for m in args.open_modules.split(",") if m}
    findings: list[str] = []
    files = 0
    jars = 0
    jar_entries = 0
    own_jars: list[str] = []
    text_files = 0

    with tarfile.open(args.tar) as tar:
        for member in tar:
            path = member.name.lstrip("./")
            # Path rules see every member, links included: a symlink or hardlink at /app/paid is a
            # paid mount whether or not anything is behind it in this layer.
            for kind, pattern, why in rules:
                if kind == "path" and pattern in "/" + path:
                    findings.append(f"path {path}: {why}")
                elif kind == "ext" and path.endswith(pattern):
                    findings.append(f"model weight {path}: {why}")
                elif kind == "jar" and path.endswith(".jar") and pattern in path.rsplit("/", 1)[-1]:
                    findings.append(f"jar {path}: {why}")
            if not member.isfile():
                continue
            files += 1
            if path.endswith(".jar"):
                jars += 1
                fh = tar.extractfile(member)
                if fh is None:
                    continue
                try:
                    with zipfile.ZipFile(io.BytesIO(fh.read())) as zf:
                        names = zf.namelist()
                except zipfile.BadZipFile:
                    findings.append(f"jar {path}: not a readable zip")
                    continue
                jar_entries += len(names)
                if any(n.startswith("ai/tessary/evals/") for n in names):
                    stem = jar_stem(path)
                    own_jars.append(stem)
                    if open_modules and stem not in open_modules:
                        findings.append(f"jar {path}: an ai.tessary jar that is not an open reactor module ({stem})")
                # Entry rules match as substrings of "/" + entry and jar rules run over entry base
                # names, so a fat jar (BOOT-INF/classes/ai/tessary/paid/, BOOT-INF/lib/launchdarkly-*.jar)
                # is as visible as the extracted layout backend/Dockerfile produces today.
                for kind, pattern, why in rules:
                    if kind == "entry":
                        hit = next((n for n in names if "/" + pattern in "/" + n), None)
                        if hit:
                            findings.append(f"jar {path} entry {hit}: {why}")
                    elif kind == "jar":
                        hit = next((n for n in names if n.endswith(".jar") and pattern in n.rsplit("/", 1)[-1]), None)
                        if hit:
                            findings.append(f"jar {path} nested jar {hit}: {why}")
            elif args.role in TEXT_ROOTS and path.startswith(TEXT_ROOTS[args.role]) and path.endswith(TEXT_EXTS):
                if member.size > MAX_TEXT_BYTES:
                    continue
                fh = tar.extractfile(member)
                if fh is None:
                    continue
                text_files += 1
                body = fh.read().decode("utf-8", errors="replace")
                for regex, why in text_rules:
                    hit = regex.search(body)
                    if hit:
                        findings.append(f"bundle {path} matches {hit.group(0)!r}: {why}")

    print(f"{args.role}: {files} files, {jars} jars ({jar_entries} entries), {text_files} bundle text files scanned")
    if own_jars:
        print(f"{args.role}: ai.tessary jars = {', '.join(sorted(set(own_jars)))}")
    if findings:
        for f in findings:
            print(f"{args.role}: FINDING {f}")
        return 1
    print(f"{args.role}: clean against {len(rules)} rules")
    return 0


if __name__ == "__main__":
    sys.exit(main())
