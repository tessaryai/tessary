#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Pins the measured-quality reference page (devdocs/reference/classifier-quality.md) to the config
# it describes.
#
# WHY. That page states the measured precision/recall/F1 of the classifiers that fire on customer
# traffic, and those numbers are only true for the heads and thresholds they were measured against.
# Retrain a head or move a threshold without updating the page and it keeps asserting a quality it
# no longer has, which is worse than no page because it reads as verified.
#
# WHAT IS AND IS NOT CHECKED. The machine-readable half only: the groundedness model revision the
# server pins (DEFAULT_REVISION in classifiers/groundedness/serve.py) and the thresholds
# (BuiltInClassifierCatalog). All three files are in this tree. Whether a number is still right for a changed eval set
# is a judgement no script can make, and stays a co-update rule in AGENTS.md.
#
# The page carries its expected values in HTML comments of the form
#   <!-- pinned: key=value key=value -->
# so the assertion lives next to the prose it protects rather than in a table only this script reads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A missing page means it was moved or deleted, which is a failure, not a reason to skip: skipping
# would turn this gate green exactly when the page is gone.
DOC=devdocs/reference/classifier-quality.md
if [ ! -f "$DOC" ]; then
  echo "check-classifier-quality-doc: $DOC is missing. Put the measured-quality page back, or" >&2
  echo "  update the path in this script and in the heredoc below." >&2
  exit 1
fi

python3 - <<'PY'
import json, re, sys

DOC = 'devdocs/reference/classifier-quality.md'
doc = open(DOC, encoding='utf-8').read()

pinned = {}
for block in re.findall(r'<!--\s*pinned:(.*?)-->', doc, re.S):
    for k, v in re.findall(r'([a-z_]+)=(\S+)', block):
        pinned[k] = v

serve = open('classifiers/groundedness/serve.py', encoding='utf-8').read()
catalog = open(
    'backend/analysis/src/main/java/ai/tessary/classifier/catalog/BuiltInClassifierCatalog.java',
    encoding='utf-8').read()


def module_config(classifier_id):
    """The config JSON of the catalog module whose id is `classifier_id`, parsed.

    The module is found by its id literal; its config is the first string literal in it that opens
    a JSON object, joined across `+` concatenations and unescaped. Numbers stay the text the source
    spells them with, so a pin compares against exactly what is written there."""
    start = re.search(r'new ClassifierModelModule\(\s*"' + re.escape(classifier_id) + '"', catalog)
    if start is None:
        return None
    end = catalog.find('new ClassifierModelModule(', start.end())
    body = catalog[start.end():end if end != -1 else len(catalog)]
    m = re.search(r'"\{(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*', body)
    if m is None:
        return None
    pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(0))
    try:
        return json.loads(''.join(pieces).replace('\\"', '"'), parse_float=str, parse_int=str)
    except json.JSONDecodeError:
        return None


revision = re.search(r'^DEFAULT_REVISION\s*=\s*"([0-9a-f]+)"', serve, re.M)
groundedness_cfg = module_config('groundedness') or {}

# The detector reads `threshold`, and an older config's `threshold_high` only when `threshold` is absent.
expected = {
    'groundedness_threshold': groundedness_cfg.get('threshold', groundedness_cfg.get('threshold_high')),
    'groundedness_revision': revision.group(1) if revision else None,
}

problems = []
for key, actual in expected.items():
    if actual is None:
        problems.append(f"  {key}: could not read the current value from source")
    elif key not in pinned:
        problems.append(f"  {key}: not pinned in {DOC} (current value is {actual})")
    elif pinned[key] != actual:
        problems.append(f"  {key}: doc pins {pinned[key]}, source says {actual}")

if problems:
    print(f"FAILED: {DOC} no longer describes what is deployed\n", file=sys.stderr)
    print("\n".join(problems), file=sys.stderr)
    print(
        "\n  A head or threshold moved. Re-measure, update the numbers on that page, and update the"
        "\n  `<!-- pinned: ... -->` comment beside them. Do not just edit the pin.",
        file=sys.stderr,
    )
    raise SystemExit(1)

print(f"classifier-quality-doc check OK: {len(expected)} pinned values match")
PY
