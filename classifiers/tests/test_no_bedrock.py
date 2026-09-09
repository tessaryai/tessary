# SPDX-License-Identifier: Apache-2.0
"""The Bedrock ban, pinned. A guardrail nobody tests is a comment with extra steps.

Owner instruction 2026-08-21: "forbid bedrock usage for anything now onwards. create a strict
guardrail." Three layers, each tested here:

  1. RUNTIME — `framework.no_bedrock` patches boto3 so a Bedrock client cannot be constructed.
  2. BACKEND SELECTION — `BedrockJudge` refuses to construct, `TESSARY_JUDGE=bedrock` is refused,
     and ambient AWS credentials can no longer silently select it (that last one is how dev work
     ended up on a billed API in the first place).
  3. STATIC — `scripts/check-no-bedrock.sh` fails on a new, undisarmed call site.

The tests below are written so they FAIL if the guard is removed, not merely so they pass today.
"""

from __future__ import annotations

import os
import pathlib
import subprocess
import sys

import pytest

from framework.no_bedrock import BedrockForbidden

REPO = pathlib.Path(__file__).resolve().parents[2]


# --- 1. runtime -----------------------------------------------------------------------------
@pytest.mark.parametrize("service", ["bedrock", "bedrock-runtime", "bedrock-agent-runtime",
                                     "bedrock-mantle", "BEDROCK-RUNTIME"])
def test_every_bedrock_service_name_is_blocked(service: str) -> None:
    boto3 = pytest.importorskip("boto3")
    with pytest.raises(BedrockForbidden):
        boto3.client(service, region_name="us-east-1")


def test_a_session_cannot_smuggle_one_through() -> None:
    """`boto3.client` is the obvious door; `Session.client` is the one a workaround reaches for."""
    boto3 = pytest.importorskip("boto3")
    with pytest.raises(BedrockForbidden):
        boto3.Session().client("bedrock-runtime", region_name="us-east-1")


def test_other_aws_services_still_work() -> None:
    """The ban is Bedrock, not AWS. A guard that broke S3 would be reverted within the week."""
    boto3 = pytest.importorskip("boto3")
    assert boto3.client("s3", region_name="us-east-1") is not None


def test_the_guard_is_wired_into_the_framework_package() -> None:
    """Importing anything from `framework` must arm the guard — the import is easy to lose to a
    tidy-up of 'unused' imports, and unarmed it protects nothing."""
    assert "from . import no_bedrock" in (REPO / "classifiers/framework/__init__.py").read_text()


# --- 2. backend selection --------------------------------------------------------------------
def test_bedrock_judge_refuses_to_construct() -> None:
    from framework.judge import BedrockJudge

    with pytest.raises(BedrockForbidden):
        BedrockJudge()


def test_explicit_bedrock_selection_is_refused(monkeypatch: pytest.MonkeyPatch) -> None:
    from framework.judge import default_judge

    monkeypatch.setenv("TESSARY_JUDGE", "bedrock")
    with pytest.raises(BedrockForbidden):
        default_judge()


def test_ambient_aws_credentials_no_longer_select_a_backend(monkeypatch: pytest.MonkeyPatch) -> None:
    """THE regression that matters. `default_judge` used to sniff AWS credentials and return a
    BedrockJudge, so merely having creds on the machine routed dev work onto a billed API without
    anyone choosing it. Credentials must never again pick the backend."""
    from framework.judge import ClaudeCliJudge, default_judge

    monkeypatch.delenv("TESSARY_JUDGE", raising=False)
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "AKIAFAKE")
    monkeypatch.setenv("AWS_REGION", "us-east-1")
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert isinstance(default_judge(), ClaudeCliJudge)


# The attribution generator has the same hard-wiring, and its own version of this test travels
# with the classifier it guards rather than living here — that module is not part of the open
# tree, so a test importing it could only ever be skipped in this file.


# --- 3. static gate ---------------------------------------------------------------------------
def test_the_static_gate_passes_on_the_current_tree() -> None:
    r = subprocess.run(["bash", "scripts/check-no-bedrock.sh"], cwd=REPO,
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


# Every shape a Bedrock client can be reached through. The keyword, botocore and SDK-wrapper forms
# each passed the gate cleanly until 2026-08-26 — testing only the positional form was the reason
# the hole survived, so the mutation check now carries all of them.
BYPASS_FORMS = [
    ('positional', 'import boto3\nc = boto3.client("bedrock-runtime")\n'),
    ('keyword', 'import boto3\nc = boto3.client(service_name="bedrock-runtime")\n'),
    ('botocore', 'import botocore.session\n'
                 'c = botocore.session.get_session().create_client("bedrock-runtime")\n'),
    ('sdk-wrapper', 'from anthropic import AnthropicBedrock\nc = AnthropicBedrock()\n'),
]


@pytest.mark.parametrize("form,source", BYPASS_FORMS, ids=[f for f, _ in BYPASS_FORMS])
def test_the_static_gate_catches_a_new_call_site(form: str, source: str) -> None:
    """Mutation check: plant an undisarmed Bedrock call site and the gate must fail. Without this the
    gate could be silently broken (a bad regex, a wrong path) and still report OK forever."""
    planted = REPO / "classifiers" / "_bedrock_guard_probe.py"
    planted.write_text(source)
    try:
        r = subprocess.run(["bash", "scripts/check-no-bedrock.sh"], cwd=REPO,
                           capture_output=True, text=True)
        assert r.returncode != 0, f"the gate did not catch the {form} form"
        assert "_bedrock_guard_probe" in (r.stdout + r.stderr)
    finally:
        planted.unlink(missing_ok=True)


def test_a_disarmed_file_is_allowed_through(tmp_path: pathlib.Path) -> None:
    """The other half of the rule: a file that imports the guard may keep the reference, because it
    provably cannot reach Bedrock. Without this the only way to satisfy the gate would be deleting
    the owner's exploratory probe scripts."""
    planted = REPO / "classifiers" / "_bedrock_guard_probe2.py"
    planted.write_text('from framework import no_bedrock\nimport boto3\n'
                       'c = boto3.client("bedrock-runtime")\n')
    try:
        r = subprocess.run(["bash", "scripts/check-no-bedrock.sh"], cwd=REPO,
                           capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr
    finally:
        planted.unlink(missing_ok=True)
