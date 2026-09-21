# SPDX-License-Identifier: Apache-2.0
"""Generate additional documents and claims with a judge, to attack two gaps in `pairs.py`.

GAP 1 — SINGLE AUTHOR. `pairs.py` was written by one person who wrote both the claims and the
documents they contradict, which biases the set toward contradictions that person finds obvious.
Generating from a different process does not remove that bias, but it stops it being the only
source in the corpus.

GAP 2 — PREMISES TOO SHORT TO EXERCISE THE WINDOW BUDGET. `pairs.py`'s largest premise is ~2,700
characters, which four chunks already cover, so the `+8 chunks` ablation arm was a no-op rather
than a negative result. Production assembles up to six `retrieved_doc` rows of 4,000 characters
(`EVIDENCE_ROWS` x `EVIDENCE_CHARS_PER_ROW`), so documents here target ~3,500-4,000 characters and
the six-document premise lands near the real 20,000-character clamp.

LABELS ARE INTENT, NOT TRUTH. The generator is told which kind to write, so the kind is a label by
CONSTRUCTION — and a generator that misses is indistinguishable from one that hits, which is
exactly the failure `pairs.py`'s single-author bias already has. So nothing here is usable until
`verify_corpus.py` labels it with an INDEPENDENT judge pass and the disagreements are dropped.
`main` refuses to write a corpus marked verified; only the verifier sets that.

Run (slow — one CLI process per call):
    TESSARY_JUDGE=cli uv run python -m groundedness.generate_corpus --domains 6
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from framework.judge import default_judge

from .labels import KIND_DESCRIPTIONS

OUT = Path("data/groundedness/generated_corpus.json")

#: Deliberately unlike `pairs.py`'s four (retail, saas, insurance, travel). Same shape of material
#: — rule-bearing policy prose with numbers, deadlines and exceptions — in subject matter the
#: authored set never touches.
DOMAINS = [
    ("healthcare", "a hospital outpatient department's patient-facing policies"),
    ("banking", "a retail bank's account, card and dispute policies"),
    ("tenancy", "a residential letting agency's tenancy policies"),
    ("university", "a university registry's assessment and enrolment regulations"),
    ("logistics", "a freight carrier's shipment, customs and liability terms"),
    ("telecom", "a mobile network operator's contract, roaming and billing policies"),
    ("energy", "an energy supplier's tariff, metering and switching policies"),
    ("employment", "an employer's leave, expenses and probation policies"),
    ("pharmacy", "a community pharmacy's dispensing, substitution and controlled-drug policies"),
    ("airline_cargo", "an air cargo handler's acceptance, dangerous-goods and storage rules"),
    ("library", "a public library's lending, reservation and fines policies"),
    ("veterinary", "a veterinary practice's treatment, consent and payment policies"),
    ("council_tax", "a local authority's council tax liability, discount and appeals rules"),
    ("gym", "a fitness chain's membership, freeze and cancellation terms"),
    ("childcare", "a nursery's attendance, fees and collection policies"),
    ("conveyancing", "a conveyancer's searches, exchange and completion terms"),
    ("warranty_claims", "a consumer electronics maker's repair, return and out-of-warranty terms"),
    ("catering", "a contract caterer's booking, allergen and cancellation terms"),
    ("dental", "a dental practice's treatment, cancellation and payment plan policies"),
    ("storage", "a self-storage operator's access, insurance and arrears rules"),
    ("courier", "a same-day courier's collection, proof-of-delivery and claims terms"),
    ("recruitment", "a recruitment agency's placement, rebate and timesheet terms"),
    ("broadband", "an ISP's installation, fair-use and service-credit policies"),
    ("car_rental", "a car hire firm's fuel, damage-waiver and late-return terms"),
    ("events", "a venue's booking, deposit and force-majeure terms"),
    ("laundry", "a commercial laundry's turnaround, loss and stain-liability terms"),
    ("printing", "a print shop's proofing, reprint and turnaround policies"),
    ("landscaping", "a grounds-maintenance contractor's visit, weather and invoicing terms"),
]

_DOC_SYSTEM = """You write realistic internal policy documentation.

Return STRICT JSON only. No prose before or after, no markdown fence.

Format: [{"id": "<short_snake_case>", "text": "<the document>"}]

Rules:
- Exactly 5 documents, on FIVE DIFFERENT SUBJECTS within the domain.
- Each document 3500-4000 characters. Write real, detailed policy prose in full sentences.
- The subjects must be ADJACENT, not unrelated: each should mention concrete quantities of a
  similar type (day counts, monetary amounts, percentages, deadlines) so that a reader skimming
  one could mistake it for another. This is the point of the exercise.
- State rules definitely: "within 14 days", "up to 500", "not covered where". Avoid hedging.
- No personal names, no real company names, no invented URLs."""

_CLAIM_SYSTEM = """You write claims an AI support agent might assert, to test a contradiction detector.

Return STRICT JSON only. No prose before or after, no markdown fence.

Format: [{"text": "<the claim, one sentence>", "kind": "<kind>", "source": "<document id>"}]

Write claims of these kinds, against the documents given:
{kinds}

Rules:
- Each claim is ONE sentence an agent would plausibly say to a customer.
- "contradiction": it must conflict with a SPECIFIC stated fact in its source document. Change a
  number, a deadline, or invert a rule. It must be genuinely incompatible, not merely different in
  emphasis.
- "support": the source document states it. Reword rather than copy verbatim.
- "neutral_addition": plausible, on-topic, and the source document is SILENT on it. It must not
  conflict with anything stated.
- "tool_fact": a specific figure about one customer's own account (an amount, a date, a reference)
  of the kind a database lookup returns. The document can neither confirm nor deny it.
- "compound": one sentence asserting TWO things, where exactly one conflicts with the source.
- "compound_support": one sentence asserting TWO things, BOTH stated by the source. Same shape and
  length as "compound"; a reader must check the document to tell them apart.
- Spread claims across all five documents.
- Do not mention the document, the policy, or this task in the claim itself."""


_HARD_CLAIM_SYSTEM = _CLAIM_SYSTEM.replace(
    '- "contradiction": it must conflict with a SPECIFIC stated fact in its source document. Change a\n'
    '  number, a deadline, or invert a rule. It must be genuinely incompatible, not merely different in\n'
    '  emphasis.',
    '- "contradiction": SUBTLE. Do NOT simply change a number. Use one of: a figure wrong by a small\n'
    '  margin (14 -> 12 days); the right rule attached to the wrong case or category; an exception\n'
    '  dropped or a condition inverted in a subordinate clause; two true facts combined into a false\n'
    '  implication; a correct quantity with the wrong unit or direction (before/after, per month/per\n'
    '  year). It must still be genuinely incompatible with the document on a careful reading.',
).replace(
    '- "support": the source document states it. Reword rather than copy verbatim.',
    '- "support": the source states it, but paraphrase HEAVILY: different sentence structure,\n'
    '  synonyms for every key term, figures spelled out or converted (a fortnight, 14 days, two weeks).\n'
    '  Lexical overlap with the source should be low.',
)


def _json_array(raw: str) -> list:
    match = re.search(r"\[.*\]", raw, re.DOTALL)
    if not match:
        raise ValueError(f"judge returned no JSON array (first 200 chars): {raw[:200]!r}")
    return json.loads(match.group(0))


def generate_domain(judge, domain: str, description: str, per_kind: int, hard: bool = False) -> dict:
    docs = _json_array(judge.complete(_DOC_SYSTEM, f"Domain: {description}"))
    docs = [{"id": f"{domain}.{d['id']}", "domain": domain, "text": d["text"]} for d in docs]

    kinds = "\n".join(
        f'- "{k}" x{per_kind}: {v}' for k, v in KIND_DESCRIPTIONS.items()
    )
    catalogue = "\n\n".join(f"[{d['id']}]\n{d['text']}" for d in docs)
    system = (_HARD_CLAIM_SYSTEM if hard else _CLAIM_SYSTEM).replace("{kinds}", kinds)
    claims = _json_array(judge.complete(system, f"Documents:\n\n{catalogue}"))
    known = {d["id"] for d in docs}
    kept = [
        {"text": c["text"], "kind": c["kind"], "source": c["source"], "domain": domain}
        for c in claims
        if c.get("kind") in KIND_DESCRIPTIONS and c.get("source") in known and c.get("text")
    ]
    return {"documents": docs, "claims": kept, "dropped": len(claims) - len(kept)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--start", type=int, default=0, help="index into DOMAINS to begin at")
    ap.add_argument("--domains", type=int, default=len(DOMAINS), help="how many domains from --start")
    ap.add_argument("--per-kind", type=int, default=3, help="claims per kind per domain")
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--hard", action="store_true",
                    help="subtle contradictions and heavily-paraphrased support: the claims a real "
                         "agent produces, not the ones a generator finds easy to write")
    args = ap.parse_args()

    # allow_fake=False: FakeJudge is a refusal-keyword heuristic. Letting it write a corpus would
    # produce something that looks like data and is noise.
    judge = default_judge(allow_fake=False)
    print(f"judge: {type(judge).__name__}")

    documents: list[dict] = []
    claims: list[dict] = []
    for domain, description in DOMAINS[args.start : args.start + args.domains]:
        try:
            got = generate_domain(judge, domain, description, args.per_kind, args.hard)
        except Exception as exc:  # one bad domain must not lose the others
            print(f"  {domain}: FAILED ({type(exc).__name__}: {str(exc)[:90]})")
            continue
        documents.extend(got["documents"])
        claims.extend(got["claims"])
        lens = [len(d["text"]) for d in got["documents"]]
        print(
            f"  {domain}: {len(got['documents'])} docs "
            f"({min(lens)}-{max(lens)} chars), {len(got['claims'])} claims"
            + (f", {got['dropped']} malformed dropped" if got["dropped"] else "")
        )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "verified": False,  # only verify_corpus.py may set this
                "generator": type(judge).__name__,
                "documents": documents,
                "claims": claims,
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"\nwrote {out}: {len(documents)} documents, {len(claims)} claims (UNVERIFIED)")
    print("next: uv run python -m groundedness.verify_corpus")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
