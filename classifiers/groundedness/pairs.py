# SPDX-License-Identifier: Apache-2.0
"""The labelled (premise, claim) set, and how premises of different shapes are built from it.

WHY IT IS AUTHORED RATHER THAN SOURCED. A public NLI or faithfulness corpus gives short
crowd-written premises. What this classifier actually sees is several thousand characters of
retrieved policy prose, of which one passage is relevant and the rest are adjacent subjects from
the same corpus. Adjacent-subject distractors are the whole difficulty: they mention the same
units (days, amounts, percentages) about a different rule. No public set reproduces that, so the
documents here are written to.

EFFECTIVE SAMPLE SIZE IS THE CLAIM COUNT, NOT THE PAIR COUNT. Every claim is instantiated in
several premise shapes, so the pairs are deliberately NOT independent. A confidence interval on
recall must use the number of distinct CLAIMS. `summary()` prints both so a reader cannot take the
larger number by accident.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

from .labels import is_positive


@dataclass(frozen=True, slots=True)
class Doc:
    """One retrieved document, as a `retrieved_doc` row would carry it."""

    id: str
    domain: str
    text: str


@dataclass(frozen=True, slots=True)
class Claim:
    """One asserted sentence from an agent's answer, labelled against `source` alone."""

    text: str
    kind: str
    source: str  # the Doc.id this claim is judged against
    decontextualized: bool = False


@dataclass(slots=True)
class Pair:
    """One scored row: a premise (one or more documents, concatenated) and a claim."""

    premise: str
    claim: str
    label: int
    source: str
    slices: dict[str, str] = field(default_factory=dict)
    #: The premise's documents, kept separately because `premise` is their concatenation and the
    #: boundaries are NOT recoverable from it — a document contains blank lines of its own, so
    #: splitting the joined string on "\n\n" yields paragraphs, not documents. That mistake made
    #: the first `doc-aligned` ablation score 72 paragraph chunks while reporting 6 documents.
    #: Production has the same problem for the same reason (SubstrateReadRepository joins with a
    #: bare "\n"), which is exactly what a document-aligned serving path would have to change.
    documents: tuple[str, ...] = ()


# --------------------------------------------------------------------------------------------
# Documents. Four domains, five passages each. Within a domain the passages are adjacent
# subjects, which is what makes them hard distractors rather than obvious ones.
# --------------------------------------------------------------------------------------------

DOCS: tuple[Doc, ...] = (
    # ---- retail -----------------------------------------------------------------------------
    Doc("retail.returns", "retail",
        "Returns. Items purchased online may be returned within 14 days of the delivery date, "
        "provided they are unworn and carry their original tags. Returns are accepted at any "
        "staffed counter or by prepaid courier label. Refunds are issued to the original payment "
        "method within five to seven business days after the returned item is received and "
        "inspected at the warehouse. Items marked final sale, and any item whose tags have been "
        "removed, fall outside this window and cannot be returned or exchanged."),
    Doc("retail.shipping", "retail",
        "Delivery. Standard shipping is dispatched within one business day and arrives in three to "
        "five business days. Express shipping is dispatched the same day when ordered before two in "
        "the afternoon and arrives the next business day. Delivery to offshore addresses adds two "
        "business days. A tracking link is sent by email once the parcel leaves the fulfilment "
        "centre, and the courier attempts delivery twice before returning the parcel to the "
        "nearest collection point."),
    Doc("retail.price_adjust", "retail",
        "Price adjustments. If an item is reduced in price within seven days of purchase, the "
        "difference may be claimed once per item as a credit to the original payment method. Price "
        "adjustments are not available on items bought with a promotional code, on clearance "
        "stock, or during advertised sale events. Claims are made through the order history page "
        "and are reviewed within two business days by the customer accounts team."),
    Doc("retail.warranty", "retail",
        "Warranty on electronics. Powered goods carry a two-year manufacturer warranty against "
        "defects in materials and workmanship, beginning on the delivery date. The warranty does "
        "not cover accidental damage, liquid ingress, or wear to batteries and other consumable "
        "components. A warranty claim requires the original order reference. Approved claims are "
        "resolved by repair where practicable, and by replacement with an equivalent model where "
        "repair is not."),
    Doc("retail.loyalty", "retail",
        "Loyalty programme. Members earn one point for every unit of currency spent on full-price "
        "items, and points post to the account once the order is marked delivered. One hundred "
        "points converts to a fixed-value credit that may be applied at checkout. Points expire "
        "eighteen months after they are earned. Membership tiers are recalculated each January "
        "based on the preceding twelve months of qualifying spend, and tier benefits apply for the "
        "full following year."),

    # ---- saas billing -----------------------------------------------------------------------
    Doc("saas.billing_cycle", "saas",
        "Billing cycle. Subscriptions are billed monthly in advance on the calendar day the "
        "subscription started. Where that day does not exist in a shorter month, the charge falls "
        "on the last day of that month. Invoices are issued at the moment of charge and are "
        "available under Billing for seven years. Annual plans are billed in one payment at the "
        "start of the term and carry a discount equivalent to two months."),
    Doc("saas.trial", "saas",
        "Free trial. New organisations receive a fourteen-day trial of the Pro plan with no card "
        "required. The trial begins when the first user signs in, not when the organisation is "
        "created. At the end of the trial the organisation reverts to the Free plan and all data is "
        "retained; nothing is deleted and no charge is made. A trial may be extended once by seven "
        "days on request to support."),
    Doc("saas.cancellation", "saas",
        "Cancellation. A subscription may be cancelled at any time from the Billing page. "
        "Cancellation takes effect at the end of the current paid period, and the plan's features "
        "remain available until then. No pro-rata refund is issued for the remainder of a period "
        "that has already been charged. An annual plan cancelled within fourteen days of its first "
        "charge is refunded in full."),
    Doc("saas.overage", "saas",
        "Usage overage. Each plan includes a monthly allowance of included events. Usage beyond the "
        "allowance is charged per thousand events at the plan's published overage rate and appears "
        "as a separate line on the next invoice. Overage is calculated on events accepted, not "
        "events submitted, so rejected batches are never billed. An organisation may set a hard cap "
        "that rejects traffic rather than incurring overage."),
    Doc("saas.seats", "saas",
        "Seats. A seat is occupied by any user who has signed in during the billing period. Adding "
        "a seat mid-period is charged pro rata from the day it is added. Removing a seat takes "
        "effect at the end of the period and is not refunded. Service accounts and API tokens do "
        "not occupy seats. The Free plan is limited to three seats and the Pro plan has no seat "
        "limit."),

    # ---- insurance --------------------------------------------------------------------------
    Doc("ins.claim_window", "insurance",
        "Notifying a claim. A motor claim must be notified within thirty days of the incident, "
        "whether or not a third party is involved and whether or not you intend to claim for your "
        "own damage. Notification is made by telephone or through the online claims portal. A claim "
        "notified after thirty days may still be accepted where there is good reason for the delay, "
        "at the insurer's discretion."),
    Doc("ins.excess", "insurance",
        "Excess. The compulsory excess shown on your schedule applies to every claim for damage to "
        "your own vehicle. Where you have chosen a voluntary excess, the two amounts are added "
        "together and the total is deducted from any settlement. No excess applies to a windscreen "
        "repair carried out by an approved repairer, and a reduced excess applies to a windscreen "
        "replacement."),
    Doc("ins.exclusions", "insurance",
        "What is not covered. The policy does not cover loss or damage arising while the vehicle is "
        "driven by a person not named on the schedule, used for hire or reward, or used in any "
        "competition or track event. Wear and tear, mechanical breakdown and loss of value "
        "following a repair are excluded. Personal belongings left in an unattended vehicle are "
        "covered only up to the limit shown for personal effects."),
    Doc("ins.settlement", "insurance",
        "Settlement. Once liability is established and the engineer's report is received, "
        "settlement is paid within ten working days. Where the vehicle is declared a total loss the "
        "settlement is the market value immediately before the incident, not the price paid. If "
        "finance is outstanding the settlement is paid to the finance provider first and any "
        "balance to you."),
    Doc("ins.documents", "insurance",
        "Documents we need. For any claim we need the policy number, the date and place of the "
        "incident, and the registration of every vehicle involved. For a theft claim we also need "
        "the crime reference number and all sets of keys. Photographs of the damage speed the "
        "engineer's assessment and may remove the need for a physical inspection."),

    # ---- travel -----------------------------------------------------------------------------
    Doc("travel.changes", "travel",
        "Changing a booking. A booking may be changed up to three hours before departure, subject "
        "to the change fee for your fare type and any difference in fare. Flexible fares carry no "
        "change fee. Changes are made in Manage Booking or through the contact centre. Name changes "
        "are not permitted on any fare type; a booking in the wrong name must be cancelled and "
        "rebooked."),
    Doc("travel.baggage", "travel",
        "Baggage. Every fare includes one cabin bag within the published size limits. Checked "
        "baggage is included on Plus and Flex fares at one piece of twenty-three kilograms, and may "
        "be added to a Basic fare for a fee that is lower online than at the airport. Sports "
        "equipment and musical instruments are carried as checked baggage and must be declared in "
        "advance."),
    Doc("travel.refunds", "travel",
        "Refunds. A Basic fare is not refundable. A Plus fare is refundable to a travel credit "
        "valid for twelve months. A Flex fare is refundable to the original payment method. Where a "
        "flight is cancelled by the airline, every fare type is refundable to the original payment "
        "method regardless of the fare rules, and the refund is processed within seven days."),
    Doc("travel.checkin", "travel",
        "Check-in. Online check-in opens thirty hours before departure and closes one hour before "
        "departure. Airport check-in desks close forty-five minutes before departure on domestic "
        "services and sixty minutes before departure on international services. A passenger who has "
        "not checked in by the relevant deadline is not accepted for travel and the fare is treated "
        "as a no-show."),
    Doc("travel.seats", "travel",
        "Seat selection. Seats may be selected at booking or at any time before check-in closes. "
        "Selection is free on Flex fares and chargeable on Basic and Plus fares. Passengers "
        "travelling with a child under twelve are seated together at no charge. The airline may "
        "reassign a selected seat for operational or safety reasons, and refunds the seat charge "
        "where it does."),
)

DOCS_BY_ID = {d.id: d for d in DOCS}


# --------------------------------------------------------------------------------------------
# Claims. Each is judged against its `source` document alone.
# --------------------------------------------------------------------------------------------

CLAIMS: tuple[Claim, ...] = (
    # ---- retail -----------------------------------------------------------------------------
    Claim("You have 90 days from delivery to return the jacket.", "contradiction", "retail.returns"),
    Claim("Refunds are credited as store credit rather than to the card you paid with.",
          "contradiction", "retail.returns"),
    Claim("Final sale items can be exchanged as long as you keep the tags on.",
          "contradiction", "retail.returns"),
    Claim("Express orders placed in the afternoon still go out the same day and arrive the next "
          "business day, and offshore addresses are unaffected.", "compound", "retail.shipping"),
    Claim("You can claim a price adjustment within seven days, and it also applies to items bought "
          "in the sale.", "compound", "retail.price_adjust"),
    Claim("You have 14 days from delivery to return it, as long as the tags are still on.",
          "support", "retail.returns"),
    Claim("Standard delivery takes three to five business days after dispatch.",
          "support", "retail.shipping"),
    Claim("The warranty is handled by our repair partner in Leeds.",
          "neutral_addition", "retail.warranty"),
    Claim("Returned parcels are inspected by two people before a refund is released.",
          "neutral_addition", "retail.returns"),
    Claim("Your refund of 24.74 was issued on 3 March to the card ending 4412.",
          "tool_fact", "retail.returns"),
    Claim("You currently have 1,840 points, which expire in November next year.",
          "tool_fact", "retail.loyalty"),
    Claim("It expires five years after you earn it.", "contradiction", "retail.loyalty", True),
    Claim("It is dispatched within one business day.", "support", "retail.shipping", True),

    # ---- saas -------------------------------------------------------------------------------
    Claim("The trial runs for 30 days and starts the moment you create the organisation.",
          "contradiction", "saas.trial"),
    Claim("When the trial ends your data is deleted unless you add a card.",
          "contradiction", "saas.trial"),
    Claim("Cancelling stops your access immediately and refunds the rest of the month pro rata.",
          "contradiction", "saas.cancellation"),
    Claim("Annual plans are billed up front and carry a discount worth four months.",
          "compound", "saas.billing_cycle"),
    Claim("Overage is billed per thousand events on the next invoice, and it counts everything you "
          "submit including rejected batches.", "compound", "saas.overage"),
    Claim("Cancelling leaves your plan's features available until the end of the period you have "
          "already paid for.", "support", "saas.cancellation"),
    Claim("Removing a seat takes effect at the end of the billing period.",
          "support", "saas.seats"),
    Claim("Invoices are also emailed to the billing contact as a PDF attachment.",
          "neutral_addition", "saas.billing_cycle"),
    Claim("Trials are approved by our sales team before they start.",
          "neutral_addition", "saas.trial"),
    Claim("Your organisation used 2.4 million events last month, 400,000 over the allowance.",
          "tool_fact", "saas.overage"),
    Claim("You have 14 seats occupied out of 20 purchased.", "tool_fact", "saas.seats"),
    Claim("They do occupy a seat, so each one adds to your bill.", "contradiction", "saas.seats", True),
    Claim("They are available under Billing for seven years.", "support", "saas.billing_cycle", True),

    # ---- insurance --------------------------------------------------------------------------
    Claim("You must tell us about the accident within 7 days or the claim is invalid.",
          "contradiction", "ins.claim_window"),
    Claim("You only need to notify us if a third party is involved.",
          "contradiction", "ins.claim_window"),
    Claim("Your voluntary excess replaces the compulsory excess rather than adding to it.",
          "contradiction", "ins.excess"),
    Claim("A total loss is settled at the market value before the incident, and that means the "
          "price you originally paid for the car.", "compound", "ins.settlement"),
    Claim("You must notify a motor claim within thirty days, and a late claim is always refused.",
          "compound", "ins.claim_window"),
    Claim("There is no excess on a windscreen repair done by an approved repairer.",
          "support", "ins.excess"),
    Claim("Settlement is paid within ten working days of the engineer's report.",
          "support", "ins.settlement"),
    Claim("Claims notified at the weekend are assigned to a handler on the next working day.",
          "neutral_addition", "ins.claim_window"),
    Claim("The engineer will normally inspect the vehicle at an approved garage.",
          "neutral_addition", "ins.documents"),
    Claim("Your settlement of 8,450 was paid to Northbridge Finance on 14 April.",
          "tool_fact", "ins.settlement"),
    Claim("Your compulsory excess is 350 and your voluntary excess is 250.",
          "tool_fact", "ins.excess"),
    Claim("It is covered even when the driver is not named on your schedule.",
          "contradiction", "ins.exclusions", True),
    Claim("They are deducted together from any settlement.", "support", "ins.excess", True),

    # ---- travel -----------------------------------------------------------------------------
    Claim("You can change the booking up to 30 minutes before departure.",
          "contradiction", "travel.changes"),
    Claim("A name change is fine as long as you do it before check-in opens.",
          "contradiction", "travel.changes"),
    Claim("A Basic fare is refundable to a travel credit valid for twelve months.",
          "contradiction", "travel.refunds"),
    Claim("Online check-in opens thirty hours before departure and stays open until the gate "
          "closes.", "compound", "travel.checkin"),
    Claim("Checked baggage is included on Plus and Flex at one piece, and the allowance is thirty-"
          "two kilograms.", "compound", "travel.baggage"),
    Claim("If the airline cancels your flight, every fare type is refunded to the card you paid "
          "with.", "support", "travel.refunds"),
    Claim("Seat selection is free on a Flex fare.", "support", "travel.seats"),
    Claim("Change fees are waived for passengers in the loyalty programme's top tier.",
          "neutral_addition", "travel.changes"),
    Claim("Cabin bags are weighed at the gate on busy departures.",
          "neutral_addition", "travel.baggage"),
    Claim("Your Flex fare of 312.40 was refunded to the card ending 8871 on 2 June.",
          "tool_fact", "travel.refunds"),
    Claim("You are in seat 14C and check-in closes at 16:40.", "tool_fact", "travel.checkin"),
    Claim("It closes fifteen minutes before departure on international services.",
          "contradiction", "travel.checkin", True),
    Claim("It is refundable to the original payment method.", "support", "travel.refunds", True),
)


# --------------------------------------------------------------------------------------------
# Premise shapes. The axis the reducer and the window budget actually move.
# --------------------------------------------------------------------------------------------

#: `name -> (total documents in the premise, 0-based index the source document sits at)`.
#: `single` is the regime the 40-claim spot check was taken in, and the only one where the
#: reducer cannot matter. The rest are what production looks like: EVIDENCE_ROWS is 6, so
#: `six_deep` is the realistic worst case rather than a stress test.
PREMISE_SHAPES: dict[str, tuple[int, int]] = {
    "single": (1, 0),
    "three_first": (3, 0),
    "four_mid": (4, 2),
    "six_deep": (6, 4),
}


def _distractors_for(claim: Claim, count: int) -> list[Doc]:
    """Documents to surround the source with: same domain first, since adjacent subjects from one
    corpus are the hard case, then other domains once the domain runs out."""
    same = [d for d in DOCS if d.domain == DOCS_BY_ID[claim.source].domain and d.id != claim.source]
    other = [d for d in DOCS if d.domain != DOCS_BY_ID[claim.source].domain]
    return (same + other)[:count]


def build_pairs() -> list[Pair]:
    """Every claim in every premise shape. Deterministic: no sampling, no shuffling."""
    out: list[Pair] = []
    for claim in CLAIMS:
        src = DOCS_BY_ID[claim.source]
        for shape, (total, position) in PREMISE_SHAPES.items():
            distractors = _distractors_for(claim, total - 1)
            docs = distractors[:]
            docs.insert(min(position, len(docs)), src)
            out.append(
                Pair(
                    documents=tuple(d.text for d in docs),
                    premise="\n\n".join(d.text for d in docs),
                    claim=claim.text,
                    label=is_positive(claim.kind),
                    source=f"authored:{claim.source}",
                    slices={
                        "kind": claim.kind,
                        "shape": shape,
                        "domain": src.domain,
                        "decontextualized": str(claim.decontextualized).lower(),
                    },
                )
            )
    return out


def write_jsonl(path: str | Path, rows: Iterable[Pair]) -> int:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with path.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(asdict(row), ensure_ascii=False) + "\n")
            n += 1
    return n


def read_jsonl(path: str | Path) -> Iterator[Pair]:
    with Path(path).open(encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                d = json.loads(line)
                yield Pair(d["premise"], d["claim"], int(d["label"]), d["source"], d.get("slices", {}))


def summary() -> str:
    pairs = build_pairs()
    by_kind: dict[str, int] = {}
    for c in CLAIMS:
        by_kind[c.kind] = by_kind.get(c.kind, 0) + 1
    pos_claims = sum(1 for c in CLAIMS if is_positive(c.kind))
    lines = [
        f"claims: {len(CLAIMS)}  (positive {pos_claims}, negative {len(CLAIMS) - pos_claims})",
        f"pairs:  {len(pairs)}  = {len(CLAIMS)} claims x {len(PREMISE_SHAPES)} premise shapes",
        "",
        "EFFECTIVE SAMPLE SIZE FOR A RECALL INTERVAL IS THE CLAIM COUNT, NOT THE PAIR COUNT:",
        f"  the {len(pairs)} pairs reuse {len(CLAIMS)} claims, so they are not independent.",
        "",
        "claims by kind:",
    ]
    for kind, n in sorted(by_kind.items()):
        lines.append(f"  {kind:<18} {n:>3}   label={is_positive(kind)}")
    lines.append("")
    lines.append(f"documents: {len(DOCS)} across {len(set(d.domain for d in DOCS))} domains")
    lines.append(f"premise chars by shape: " + ", ".join(
        f"{s}={len(next(p for p in pairs if p.slices['shape'] == s).premise)}" for s in PREMISE_SHAPES))
    lines.append(f"decontextualized claims: {sum(1 for c in CLAIMS if c.decontextualized)}")
    return "\n".join(lines)


if __name__ == "__main__":
    print(summary())
