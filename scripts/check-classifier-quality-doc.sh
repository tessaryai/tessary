#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Pins the measured-quality reference page (devdocs/reference/classifier-quality.md, which lives
# outside this tree — see CQ_DOC below) to the config it describes.
#
# WHY. That page states the measured precision/recall/F1 of the classifiers that fire on customer
# traffic, and those numbers are only true for the heads and thresholds they were measured against.
# Retrain a head or move a threshold without updating the page and it keeps asserting a quality it
# no longer has, which is worse than no page because it reads as verified.
#
# WHAT IS AND IS NOT CHECKED. The machine-readable half only: served model revisions (CQ_MODELS)
# and thresholds (BuiltInClassifierCatalog, which is in this tree). Whether a number is still
# right for a changed eval set is a judgement no script can make, and stays a co-update rule in
# AGENTS.md.
#
# The page carries its expected values in HTML comments of the form
#   <!-- pinned: key=value key=value -->
# so the assertion lives next to the prose it protects rather than in a table only this script reads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# WHERE ITS TWO INPUTS LIVE, AND WHY THEY ARE VARIABLES. The page and the populated model manifest
# live outside this tree; the catalog they're compared against is here. This script may not name
# that other location — check-open-boundary.sh enforces that — so the caller passes the paths in,
# and the defaults are just what this checkout has (hence the skip branches below). Same shape as
# check-migrations-populated.sh's MIGPOP_OVERLAY_EXPECTS, for the same reason. A caller with access
# to that other tree sets both; nothing else does.
CQ_DOC="${CQ_DOC:-devdocs/reference/classifier-quality.md}"
CQ_MODELS="${CQ_MODELS:-classify-service/models.json}"
export CQ_DOC CQ_MODELS

# This tree has nothing to pin: the page isn't here, and the manifest it would read is the empty
# `{}` this tree ships. Say so rather than dying on an unguarded open() or a KeyError. The gate
# registry also marks this check SKIP here, but the branch stays so a direct call (Taskfile, CI)
# cannot report a traceback as red.
if [ ! -f "$CQ_DOC" ]; then
  echo "classifier-quality-doc skipped: $CQ_DOC is not in this checkout (no measured-quality page to pin)"
  exit 0
fi
if [ ! -s "$CQ_MODELS" ] || [ "$(tr -d '[:space:]' < "$CQ_MODELS")" = '{}' ]; then
  echo "classifier-quality-doc skipped: $CQ_MODELS is an empty manifest, so there are no served revisions to pin against"
  exit 0
fi

python3 - <<'PY'
import json, re, sys

import os

DOC = os.environ['CQ_DOC']
doc = open(DOC, encoding='utf-8').read()

pinned = {}
for block in re.findall(r'<!--\s*pinned:(.*?)-->', doc, re.S):
    for k, v in re.findall(r'([a-z_]+)=(\S+)', block):
        pinned[k] = v

models = json.load(open(os.environ['CQ_MODELS'], encoding='utf-8'))
catalog = open(
    'backend/analysis/src/main/java/ai/tessary/classifier/catalog/BuiltInClassifierCatalog.java',
    encoding='utf-8').read()


# Both frustration and groundedness carry threshold_high/threshold_low, so a bare search finds
# whichever block appears first — each block is identified by something only that classifier's
# config contains.
def config_block(marker):
    """The config-string literal for the classifier whose block contains `marker`."""
    for block in re.findall(r'"\{\\"threshold_high.*?\}",', catalog, re.S):
        if marker in block:
            return block
    return None


def number_in(block, key):
    if block is None:
        return None
    m = re.search(r'\\"' + key + r'\\":\s*([0-9.]+)', block)
    return m.group(1) if m else None


frustration_cfg = config_block('attribution_head')
groundedness_cfg = config_block('threshold_low\\":0.6}')

expected = {
    'groundedness_revision': models['groundedness']['revision'],
    'frustration_revision': models['frustration']['revision'],
    'attribution_revision': models['attribution']['revision'],
    'attribution_threshold': number_in(frustration_cfg, 'attribution_threshold'),
    'frustration_high': number_in(frustration_cfg, 'threshold_high'),
    'frustration_low': number_in(frustration_cfg, 'threshold_low'),
    'groundedness_high': number_in(groundedness_cfg, 'threshold_high'),
    'groundedness_low': number_in(groundedness_cfg, 'threshold_low'),
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
