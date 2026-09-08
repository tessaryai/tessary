#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Walk every YAML in an evals/ directory and report the type of every field path.
Surfaces shape drift between what `evals plugin` actually emits and what the
Java model expects.

Usage:
    python3 scripts/audit-evals-shape.py <path-to-evals-dir>
    python3 scripts/audit-evals-shape.py <path-to-evals-dir> --strict
    python3 scripts/audit-evals-shape.py <path-to-evals-dir> --filter graders/

Modes:
    default       — print every observed (field-path, type) pair; warn on
                    polymorphic fields with ⚠.
    --strict      — exit non-zero if any field is polymorphic OR if any file
                    failed to parse. Suitable for CI smoke after upgrading the
                    skill.
    --filter <p>  — only consider files whose path contains <p> (handy for
                    narrowing to graders/ or a single grader id).

Read this when:
    - You added a field to the skill and want to see whether the Java side
      already accepts it.
    - An import is failing on a "Cannot deserialize" or "Unrecognized field"
      and you want to find every other file that might trip the same bug.
    - You're about to bump the skill version and want the diff of what's
      changing in the output shape.

Output:
    grader.self_tests[].call_site_outputs.report_session   list<str> | str   POLYMORPHIC
      └─ list<str>  e.g. chain__...__persona_specific_signal_flattened.yaml
      └─ str        e.g. chain__...__auth_findings_leak_downstream.yaml

The script is read-only — no DB calls, no network. Safe to run against any
evals/ checkout, anywhere.
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

try:
    import yaml  # PyYAML
except ImportError:
    sys.exit("audit-evals-shape.py: requires PyYAML. Install: pip install pyyaml")


def yaml_type(v: Any) -> str:
    """Render a Python value as a Jackson-flavoured type signature."""
    if v is None: return "null"
    if isinstance(v, bool): return "bool"
    if isinstance(v, int): return "int"
    if isinstance(v, float): return "float"
    if isinstance(v, str): return "str"
    if isinstance(v, list):
        inner = sorted({yaml_type(x) for x in v}) if v else ["?"]
        return f"list<{'|'.join(inner)}>"
    if isinstance(v, dict): return "map"
    return type(v).__name__


def walk(prefix: str, value: Any, observed: dict, source_file: str) -> None:
    t = yaml_type(value)
    observed[prefix][t].add(source_file)
    if isinstance(value, dict):
        for k, vv in value.items():
            walk(f"{prefix}.{k}", vv, observed, source_file)
    elif isinstance(value, list):
        for vv in value:
            if isinstance(vv, (dict, list)):
                walk(f"{prefix}[]", vv, observed, source_file)
            else:
                observed[f"{prefix}[]"][yaml_type(vv)].add(source_file)


def audit(root: Path, file_filter: str | None) -> tuple[dict, list[tuple[str, str]]]:
    """Return (observed shape, parse errors)."""
    observed: dict = defaultdict(lambda: defaultdict(set))
    errors: list[tuple[str, str]] = []

    pipe = root / "pipeline.yaml"
    if pipe.is_file() and (file_filter is None or file_filter in "pipeline.yaml"):
        try:
            doc = yaml.safe_load(pipe.read_text())
            walk("pipeline", doc, observed, "pipeline.yaml")
        except Exception as e:
            errors.append(("pipeline.yaml", str(e)))

    gdir = root / "graders"
    if gdir.is_dir():
        # Grader filenames nest as folders (schema 0.14.0), so recurse.
        for f in sorted(gdir.rglob("*.yaml")):
            rel = f"graders/{f.relative_to(gdir).as_posix()}"
            if file_filter is not None and file_filter not in rel:
                continue
            try:
                doc = yaml.safe_load(f.read_text())
                walk("grader", doc, observed, rel)
            except Exception as e:
                errors.append((rel, str(e)))

    return observed, errors


def print_report(observed: dict, errors: list[tuple[str, str]]) -> int:
    """Print human report; return number of polymorphic fields found."""
    polymorphic_count = 0
    for path in sorted(observed.keys()):
        types = observed[path]
        if len(types) == 1:
            (t, files), = types.items()
            print(f"  {path:80s}  {t:30s}  (in {len(files)} file{'s' if len(files) != 1 else ''})")
        else:
            polymorphic_count += 1
            ts = " | ".join(sorted(types.keys()))
            print(f"⚠ {path:80s}  {ts:30s}  POLYMORPHIC")
            for t in sorted(types.keys()):
                sample = next(iter(types[t]))
                more = f" (+{len(types[t]) - 1} more)" if len(types[t]) > 1 else ""
                print(f"     └─ {t:25s} e.g. {sample}{more}")

    if errors:
        print()
        print(f"⚠ {len(errors)} file(s) failed to parse:")
        for name, msg in errors:
            first_line = msg.splitlines()[0] if msg else ""
            print(f"  - {name}: {first_line}")

    print()
    print(f"summary: {len(observed)} field paths, "
          f"{polymorphic_count} polymorphic, "
          f"{len(errors)} parse error{'s' if len(errors) != 1 else ''}")
    return polymorphic_count


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Audit the shape of an evals plugin output directory."
    )
    parser.add_argument("dir", help="Path to the evals/ directory.")
    parser.add_argument(
        "--strict", action="store_true",
        help="Exit non-zero if any field is polymorphic or any file fails to parse."
    )
    parser.add_argument(
        "--filter", default=None,
        help="Only audit files whose path contains this substring."
    )
    args = parser.parse_args(argv[1:])

    root = Path(args.dir).expanduser().resolve()
    if not root.is_dir():
        print(f"audit-evals-shape: not a directory: {root}", file=sys.stderr)
        return 2

    observed, errors = audit(root, args.filter)
    polymorphic = print_report(observed, errors)

    if args.strict and (polymorphic > 0 or errors):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
