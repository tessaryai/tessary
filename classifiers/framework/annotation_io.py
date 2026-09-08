# SPDX-License-Identifier: Apache-2.0
"""Argilla push/pull — the bridge between framework JSONL and the annotation UI.

The chosen team process (PROGRAM.md §1③): the teacher LLM generates or pre-labels rows, a
human verifies every one in Argilla, and the corrected labels come back as the dataset of
record. This module is that round trip:

- ``push_for_labeling`` — create (or reuse) an Argilla dataset from ``LabeledExample`` rows,
  with the judge/construction label attached as a **suggestion** the annotator accepts or
  corrects (never as silent truth), and ``context`` rendered above the scored message.
- ``pull_labeled`` — read back submitted responses as ``LabeledExample`` rows; a human
  response always outranks the suggestion.
- ``pull_annotations`` — the same responses as raw ``(item_id, annotator_id, label)`` triples,
  feeding ``framework.agreement`` (human-vs-judge κ: the pushed suggestion is included as
  annotator ``"judge"``).

Server: the dev compose service (`task classifiers:up`), loopback-bound. Env (dev defaults):
``ARGILLA_API_URL`` = http://localhost:16900, ``ARGILLA_API_KEY`` = argilla.apikey.
Deps: the ``annotation`` extra (argilla>=2.8), imported lazily.
"""

from __future__ import annotations

import os
from typing import TYPE_CHECKING, Sequence

from .agreement import AnnotationRow
from .schema import LabeledExample

if TYPE_CHECKING:
    from .judge import LabelSpec

DEFAULT_API_URL = "http://localhost:16900"
DEFAULT_API_KEY = "argilla.apikey"  # dev default from docker-compose.dev.yml; override via env
DEFAULT_WORKSPACE = "classifiers"

_JUDGE_ANNOTATOR = "judge"


def _client():
    import argilla as rg  # lazy — annotation extra

    return rg.Argilla(
        api_url=os.environ.get("ARGILLA_API_URL", DEFAULT_API_URL),
        api_key=os.environ.get("ARGILLA_API_KEY", DEFAULT_API_KEY),
    )


def _ensure_workspace(client, name: str):
    import argilla as rg

    ws = client.workspaces(name)
    if ws is None:
        ws = rg.Workspace(name=name, client=client)
        ws.create()
    return ws


def push_for_labeling(
    dataset_name: str,
    rows: Sequence[LabeledExample],
    spec: "LabelSpec",
    *,
    workspace: str = DEFAULT_WORKSPACE,
    guidelines: str | None = None,
    suggestions: bool = True,
) -> int:
    """Create/extend an Argilla dataset from rows; the row's label rides as a suggestion.

    ``suggestions=False`` pushes BLIND — no judge label shown — for calibration passes where the
    human must label independently (human-vs-judge κ is then computed by joining the pulled
    responses back to the local JSONL's judge labels on the stable record ids).

    Field layout: ``context`` (the serialization-contract rendering of prior turns, shown above
    the scored message; empty for single-turn rows) and ``text`` (the message being labeled).
    Question: one ``LabelQuestion`` with the spec's positive/negative names. Records get a
    stable ``id`` (``<source>/<index>``) so re-pushing the same rows updates instead of
    duplicating. Returns the number of records logged.
    """
    import argilla as rg  # lazy

    client = _client()
    _ensure_workspace(client, workspace)

    dataset = client.datasets(name=dataset_name, workspace=workspace)
    if dataset is None:
        settings = rg.Settings(
            fields=[
                rg.TextField(name="context", title="Prior turns (oldest first)", required=False),
                rg.TextField(name="text", title="Message to label"),
            ],
            questions=[
                rg.LabelQuestion(
                    name="label",
                    labels=[spec.positive, spec.negative],
                    title=f"Is this {spec.positive}?",
                )
            ],
            metadata=[rg.TermsMetadataProperty(name="source")],
            guidelines=guidelines or spec.system,
            distribution=rg.TaskDistribution(min_submitted=1),  # solo-annotator phase
        )
        dataset = rg.Dataset(name=dataset_name, workspace=workspace, settings=settings, client=client)
        dataset.create()

    records = [
        rg.Record(
            id=f"{row.source}/{i}",
            fields={"context": row.context or "", "text": row.text},
            metadata={"source": row.source},
            suggestions=(
                [
                    rg.Suggestion(
                        question_name="label",
                        value=spec.positive if row.label == 1 else spec.negative,
                        agent=_JUDGE_ANNOTATOR,
                    )
                ]
                if suggestions
                else []
            ),
        )
        for i, row in enumerate(rows)
    ]
    dataset.records.log(records)
    return len(records)


def _iter_submitted(dataset, spec: "LabelSpec"):
    """Yield (record, response) for every submitted 'label' response."""
    valid = {spec.positive, spec.negative}
    for record in dataset.records(with_suggestions=True, with_responses=True):
        for response in record.responses["label"]:
            status = getattr(response, "status", None)
            status_value = getattr(status, "value", status)  # ResponseStatus enum -> str
            if status_value in ("submitted", None) and response.value in valid:
                yield record, response


def pull_labeled(
    dataset_name: str,
    spec: "LabelSpec",
    *,
    workspace: str = DEFAULT_WORKSPACE,
) -> list[LabeledExample]:
    """Read back human-verified rows. One row per record with >=1 submitted response; if
    multiple annotators responded, the first submitted response wins for the LABEL (use
    ``pull_annotations`` + ``framework.agreement`` to adjudicate disagreements first)."""
    client = _client()
    dataset = client.datasets(name=dataset_name, workspace=workspace)
    if dataset is None:
        raise ValueError(f"Argilla dataset {dataset_name!r} not found in workspace {workspace!r}")

    out: dict[str, LabeledExample] = {}
    for record, response in _iter_submitted(dataset, spec):
        rid = str(record.id)
        if rid in out:
            continue
        out[rid] = LabeledExample(
            text=record.fields["text"],
            label=1 if response.value == spec.positive else 0,
            source=f"argilla/{dataset_name}",
            meta={"record_id": rid, "annotator": str(response.user_id)},
            context=record.fields.get("context") or "",
        )
    return list(out.values())


def pull_annotations(
    dataset_name: str,
    spec: "LabelSpec",
    *,
    workspace: str = DEFAULT_WORKSPACE,
    include_judge: bool = True,
) -> list[AnnotationRow]:
    """All submitted responses as (item_id, annotator_id, label) triples for agreement math.

    With ``include_judge`` the pushed suggestion joins as annotator ``"judge"`` — so
    ``cohen_kappa(rows, "<user-id>", "judge")`` is the solo-phase human-vs-judge measurement
    with no extra plumbing.
    """
    client = _client()
    dataset = client.datasets(name=dataset_name, workspace=workspace)
    if dataset is None:
        raise ValueError(f"Argilla dataset {dataset_name!r} not found in workspace {workspace!r}")

    rows: list[AnnotationRow] = []
    seen_judge: set[str] = set()
    for record, response in _iter_submitted(dataset, spec):
        rid = str(record.id)
        rows.append((rid, str(response.user_id), 1 if response.value == spec.positive else 0))
        if include_judge and rid not in seen_judge:
            seen_judge.add(rid)
            for suggestion in record.suggestions:
                if suggestion.question_name == "label":
                    rows.append((rid, _JUDGE_ANNOTATOR, 1 if suggestion.value == spec.positive else 0))
    return rows
