# SPDX-License-Identifier: Apache-2.0
"""HARD BLOCK on AWS Bedrock. Importing this module makes a Bedrock call impossible in-process.

WHY THIS IS A KILL-SWITCH AND NOT A COMMENT
-------------------------------------------
Owner instruction, 2026-08-21: "make sure you do not use bedrock for anything" and then "forbid
bedrock usage for anything now onwards. create a strict guardrail."

A convention would not have held. The pull toward Bedrock is real and mechanical: the `claude` CLI
measures ~100 s per generation call and degrades under concurrency because it serialises
subscription calls, where Bedrock answers the same prompt in seconds and parallelises properly —
a 146-cell pass in 2.5 minutes against roughly an hour. That is exactly the kind of gradient a
future reader (or a future me) rationalises their way down at 2am with a deadline. So the ban is
enforced by the runtime rather than by everyone remembering.

The credentials configured on a developer machine bill the maintainer's own AWS account directly,
while the `claude` CLI runs on a subscription that is already paid for. Speed is not a good enough
reason to spend someone else's money. (The account id and the IAM principal are deliberately not
written here: this file publishes in the open edition, and the export's forbidden-content sweep
treats both as must-not-publish.)

WHAT IT DOES
------------
Wraps `boto3.client`, `boto3.Session.client` and `botocore.session.Session.create_client` so that
ANY service name containing "bedrock" raises `BedrockForbidden` at construction — before a request
is ever signed. That covers `bedrock-runtime`, `bedrock`, `bedrock-agent-runtime`, and the
`bedrock-mantle` endpoint the probe script pokes at, in positional or keyword form. Every other AWS
service is untouched, so S3/STS/etc. keep working.

`create_client` is patched because botocore's session API reaches AWS without going through
`boto3.client` at all — patching only boto3 left a door open that looked shut.

Imported from `framework/__init__.py`, so it is active for anything that touches the classifier
framework. `scripts/check-no-bedrock.sh` is the static half: it fails the build if a NEW Bedrock
call site appears anywhere in the Python tree.

DELIBERATELY OUT OF SCOPE: the Java backend's production grading path. That is the platform's
serving runtime, not dev tooling, and silently disabling it would be an outage rather than a
guardrail. If the ban is meant to extend there too, that is a separate, deliberate change.

    # to intentionally lift it (there is no reason to do this today):
    #   the guard has no off switch by design — delete the import if you truly mean it,
    #   in a commit that says why.
"""

from __future__ import annotations

import functools

_MESSAGE = (
    "AWS Bedrock is forbidden in this repo's Python tooling (owner instruction 2026-08-21). "
    "Attempted to create a boto3 client for service {service!r}.\n"
    "Use the owner's logged-in `claude` CLI instead — `framework.judge.ClaudeCliJudge`, or "
    "`frustration.attribution.synth.make_generator`, which is the single place a generator backend "
    "is chosen.\n"
    "It is slower (~100 s/call, does not parallelise). That is the accepted cost: these credentials "
    "bill the owner's AWS account, the CLI runs on a subscription already paid for."
)


class BedrockForbidden(RuntimeError):
    """Raised when anything tries to construct a Bedrock client."""


def _forbidden(service: str) -> bool:
    return "bedrock" in str(service).lower()


def install() -> None:
    """Patch boto3 so Bedrock clients cannot be constructed. Idempotent."""
    try:
        import boto3
    except ModuleNotFoundError:                      # boto3 is optional; nothing to guard
        return
    if getattr(boto3, "_bedrock_guard_installed", False):
        return

    real_client = boto3.client
    real_session_client = boto3.Session.client

    @functools.wraps(real_client)
    def guarded_client(service_name, *a, **kw):
        if _forbidden(service_name):
            raise BedrockForbidden(_MESSAGE.format(service=service_name))
        return real_client(service_name, *a, **kw)

    @functools.wraps(real_session_client)
    def guarded_session_client(self, service_name, *a, **kw):
        if _forbidden(service_name):
            raise BedrockForbidden(_MESSAGE.format(service=service_name))
        return real_session_client(self, service_name, *a, **kw)

    boto3.client = guarded_client
    boto3.Session.client = guarded_session_client

    # botocore's own session bypasses boto3.client entirely.
    try:
        import botocore.session

        real_create_client = botocore.session.Session.create_client

        @functools.wraps(real_create_client)
        def guarded_create_client(self, service_name, *a, **kw):
            if _forbidden(service_name):
                raise BedrockForbidden(_MESSAGE.format(service=service_name))
            return real_create_client(self, service_name, *a, **kw)

        botocore.session.Session.create_client = guarded_create_client
    except ModuleNotFoundError:                      # botocore absent; nothing to guard
        pass

    boto3._bedrock_guard_installed = True


install()
