#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# STRICT GUARDRAIL: no AWS Bedrock in this repo's Python tooling.
#
# Owner instruction, 2026-08-21: "forbid bedrock usage for anything now onwards. create a strict
# guardrail." This is the static half. The runtime half is classifiers/framework/no_bedrock.py,
# which patches boto3 so a Bedrock client cannot be constructed at all.
#
# WHY BOTH HALVES. The runtime guard only protects code that imports the framework; a new script
# that reaches for boto3 directly would sail past it. This grep is what catches THAT — it fails the
# build the moment a new Bedrock call site appears, rather than the moment someone notices the bill.
#
# WHAT IS AND IS NOT A VIOLATION
#   violation      constructing a Bedrock client, or naming bedrock as a judge/generator backend
#   NOT a violation  the string "aws.bedrock" written as a `gen_ai.provider.name` SPAN ATTRIBUTE by
#                    the corpus emitters. That is synthetic trace DATA describing a provider, not a
#                    call to one, and banning the word would make the emitters unable to describe
#                    the traffic the platform is built to ingest.
#
# DELIBERATELY OUT OF SCOPE: the Java backend's production grading path. Disabling the platform's
# serving runtime would be an outage, not a guardrail. Extending the ban there is a separate call.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0

# The scanned roots are the ones that exist. classifiers/ ships in BOTH editions — the
# open half (framework/, tool_error/, metric_drift/, data_gen/) stayed when the research half moved
# into the overlay — so in practice both roots are present everywhere and this loop's absent-root
# arm is now the rare case rather than the public-repo norm it was written for. It stays: an absent
# root is not a violation, it is a smaller scan. Each grep below runs over $ROOTS only and never
# with stderr discarded, so an unreadable root is an error and not a vacuous pass, and the roots that
# were and were not scanned are printed so a green run says what it covered.
#
# The overlay's own Python is deliberately NOT a root here, and never was: this gate guards the
# OPEN tree's promise, the overlay carries its own, and this file may not name that directory
# anyway (boundary rule 5).
ROOTS=""
ABSENT=""
for r in classifiers scripts; do
  if [ -d "$r" ]; then ROOTS="$ROOTS $r"; else ABSENT="$ABSENT $r"; fi
done
ROOTS="${ROOTS# }"
echo "check-no-bedrock: scanned roots:$( [ -n "$ROOTS" ] && printf ' %s' $ROOTS )${ABSENT:+ (absent, not scanned:$ABSENT)}"

# 1. A file may construct a Bedrock client ONLY if it has disarmed itself by importing the runtime
#    guard. That is the rule with teeth: mentioning Bedrock is allowed in code that provably cannot
#    reach it, and nowhere else. It lets the owner's probe scripts stay in the tree as a record of
#    what those endpoints accept, while making running them fail loudly instead of spending money.
#
#    The pattern matches the service name ANYWHERE in the call's argument list, not just as the
#    first positional string, because `client(service_name="bedrock-runtime")` is the same call and
#    used to sail straight through. `create_client` is here too: botocore's session API reaches AWS
#    without touching `boto3.client`, so the runtime guard never sees it.
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  file="${hit%%:*}"
  case "$file" in
    classifiers/framework/no_bedrock.py|classifiers/tests/test_no_bedrock.py) continue ;;
  esac
  if grep -q "no_bedrock" "$file"; then
    continue                      # disarmed: importing the guard makes the call raise
  fi
  echo "FORBIDDEN: Bedrock client construction in an UNDISARMED file" >&2
  echo "  $hit" >&2
  echo "  (if this file must keep the reference, import framework.no_bedrock so it cannot run)" >&2
  fail=1
done <<< "$(grep -rniE "(^|[^a-z_])(client|create_client)\(([^)]*)bedrock" --include='*.py' --exclude-dir=.venv -r $ROOTS || [ $? -eq 1 ])"

# 2. No new BedrockJudge instantiation. The class survives only to raise a message that names the
#    alternative; constructing it anywhere is a mistake the runtime guard would turn into a crash.
if hits=$(grep -rn --include='*.py' --exclude-dir=.venv -E "BedrockJudge\(" $ROOTS \
          | grep -v "framework/judge.py" | grep -v "tests/test_no_bedrock.py"); then
  echo "FORBIDDEN: BedrockJudge instantiation" >&2
  echo "$hits" >&2
  fail=1
fi

# 2b. SDK wrappers that reach Bedrock without a boto3 client of their own. The runtime guard patches
#     boto3, so these are invisible to it — this grep is the only thing standing in front of them.
if hits=$(grep -rnE "AnthropicBedrock|ChatBedrock|BedrockConverse|BedrockLLM|BedrockEmbeddings|BedrockChat" \
          --include='*.py' --exclude-dir=.venv $ROOTS \
          | grep -v "tests/test_no_bedrock.py"); then
  echo "FORBIDDEN: a Bedrock SDK wrapper (these bypass the boto3 runtime guard entirely)" >&2
  echo "$hits" >&2
  fail=1
fi

# 3. The runtime kill-switch must stay wired into the framework package. A guard nobody imports is
#    decoration, and this is the line most likely to be lost to a tidy-up of "unused" imports.
#    THIS RULE NOW RUNS IN THE PUBLIC EXPORT, for the first time. classifiers/ used to be
#    private in its entirety, so the skip below was the normal outcome everywhere the export
#    reached and the rule only ever fired in a full checkout. Keeping framework/ open
#    means the package this rule guards is one of the things the public repo now ships — and the
#    guard being wired into it is a promise made to the people reading that repo, not an internal
#    hygiene note. The skip stays for a checkout with no classifier tree at all; it is no longer
#    the public-repo path.
if [ ! -f classifiers/framework/__init__.py ]; then
  echo "check-no-bedrock: rule 3 skipped, no classifiers/framework/ in this checkout at all, so there is no framework package to carry the runtime guard"
elif ! grep -q "from . import no_bedrock" classifiers/framework/__init__.py; then
  echo "FORBIDDEN: classifiers/framework/__init__.py no longer imports the no_bedrock guard" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "AWS Bedrock is banned in this repo's Python tooling. Use the owner's logged-in \`claude\`" >&2
  echo "CLI (framework.judge.ClaudeCliJudge). It is ~100s/call and does not parallelise; that cost" >&2
  echo "is accepted deliberately, because these credentials bill the owner's AWS account and the" >&2
  echo "CLI runs on a subscription already paid for. See classifiers/framework/no_bedrock.py." >&2
  exit 1
fi

echo "no-bedrock check OK"
