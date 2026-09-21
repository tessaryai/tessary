# SPDX-License-Identifier: Apache-2.0
"""A spend ceiling for GPU training that is enforced, not remembered.

A budget held only in someone's head is a budget that gets exceeded on the run where something goes
wrong — which is the run most likely to be resubmitted. This keeps a ledger of every job submitted,
prices each one at its WORST CASE (`max_run` at the full on-demand rate), and refuses a submission
whose worst case would take the total past the ceiling.

WORST CASE, NOT EXPECTED CASE. A job that hangs bills until `max_run` stops it, so `max_run` is the
only honest number to budget against. Reserving the worst case means the ledger over-counts when
jobs finish early; `reconcile` corrects that from the actual billable seconds AWS reports, so the
headroom comes back rather than being lost to pessimism.

The AWS-side `aws_budgets_budget` in the infrastructure repo alerts at 80/100% of a monthly ceiling.
It does not BLOCK anything, and it is monthly rather than per-project. This is the blocking half.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

#: ml.g5.xlarge, Training usage, ap-south-1, on-demand. Verified against the AWS Pricing API rather
#: than assumed; re-check it if the region or instance type changes.
PRICE_PER_HOUR = 1.69
INSTANCE_TYPE = "ml.g5.xlarge"
REGION = "ap-south-1"

#: The ceiling for ALL training on this project, across every provider; raised once to fund the
#: v3-large arm and the experiment programme after the base-size run
#: reached 0.780 hit rate against the reference's 0.840.
BUDGET_USD = 150.00

LEDGER = Path("data/groundedness/sm_ledger.json")


class BudgetExceeded(RuntimeError):
    """Raised instead of submitting. The message says what would have been spent and what is left."""


@dataclass(slots=True)
class Entry:
    job_name: str
    submitted_at: str
    max_run_s: int
    reserved_usd: float
    actual_usd: float | None = None
    note: str = ""
    #: "aws" (SageMaker, reconciled from billable seconds) or "azure" (Azure ML; reconciled by hand
    #: from the job's duration, since there is no per-job billing API worth the dependency).
    provider: str = "aws"
    price_per_hour: float = PRICE_PER_HOUR

    @property
    def charged(self) -> float:
        """Actual spend where known, else the reservation. Never the smaller of the two."""
        return self.actual_usd if self.actual_usd is not None else self.reserved_usd


def _load(path: Path) -> list[Entry]:
    if not path.exists():
        return []
    return [Entry(**e) for e in json.loads(path.read_text(encoding="utf-8"))["entries"]]


def _save(path: Path, entries: list[Entry]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "budget_usd": BUDGET_USD,
                "price_per_hour": PRICE_PER_HOUR,
                "instance_type": INSTANCE_TYPE,
                "region": REGION,
                "spent_usd": round(sum(e.charged for e in entries), 4),
                "entries": [asdict(e) for e in entries],
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def cost_of(max_run_s: int, price_per_hour: float = PRICE_PER_HOUR) -> float:
    return max_run_s / 3600.0 * price_per_hour


def spent(path: Path = LEDGER) -> float:
    return sum(e.charged for e in _load(path))


def remaining(path: Path = LEDGER) -> float:
    return BUDGET_USD - spent(path)


def reserve(job_name: str, max_run_s: int, note: str = "", path: Path = LEDGER,
            provider: str = "aws", price_per_hour: float = PRICE_PER_HOUR) -> Entry:
    """Record a job's worst-case cost, or refuse if it would breach the ceiling. One ceiling for
    every provider: an Azure job draws from the same $150 as a SageMaker one."""
    entries = _load(path)
    already = sum(e.charged for e in entries)
    reserved = cost_of(max_run_s, price_per_hour)
    if already + reserved > BUDGET_USD:
        raise BudgetExceeded(
            f"refusing to submit {job_name!r}: worst case ${reserved:.2f} "
            f"({max_run_s / 3600:.2f}h at ${PRICE_PER_HOUR}/h) on top of ${already:.2f} already "
            f"committed would exceed the ${BUDGET_USD:.2f} ceiling by "
            f"${already + reserved - BUDGET_USD:.2f}.\n"
            f"Lower --max-run, or reconcile finished jobs to release their unused reservation "
            f"(uv run --extra sagemaker python -m groundedness.sm_budget --reconcile)."
        )
    entry = Entry(
        job_name=job_name,
        submitted_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        max_run_s=max_run_s,
        reserved_usd=round(reserved, 4),
        note=note,
        provider=provider,
        price_per_hour=price_per_hour,
    )
    entries.append(entry)
    _save(path, entries)
    return entry


def release(job_name: str, path: Path = LEDGER) -> bool:
    """Drop a reservation for a job that was never submitted.

    Reserving BEFORE submitting is right — a job that starts unrecorded is how a ceiling is breached
    unnoticed — but it means a failed submission leaves budget held against a job that does not
    exist. Without this, every failed attempt permanently shrinks the ceiling. Only ever removes
    entries with no actual spend recorded, so it can never erase a real charge.
    """
    entries = _load(path)
    kept = [e for e in entries if not (e.job_name == job_name and e.actual_usd is None)]
    if len(kept) == len(entries):
        return False
    _save(path, kept)
    return True


def reconcile(path: Path = LEDGER, region: str = REGION) -> int:
    """Replace reservations with what AWS actually billed, releasing unused headroom."""
    import boto3

    sm = boto3.client("sagemaker", region_name=region)
    entries = _load(path)
    updated = 0
    for e in entries:
        if e.provider != "aws":
            continue  # Azure entries are settled by az_submit --settle, not by the SageMaker API
        if e.actual_usd is not None:
            continue
        try:
            d = sm.describe_training_job(TrainingJobName=e.job_name)
        except Exception:
            continue
        if d.get("TrainingJobStatus") in ("Completed", "Failed", "Stopped"):
            seconds = d.get("BillableTimeInSeconds")
            if seconds is None:
                continue
            e.actual_usd = round(cost_of(seconds), 4)
            e.note = (e.note + f" [{d['TrainingJobStatus'].lower()}, {seconds}s billed]").strip()
            updated += 1
    if updated:
        _save(path, entries)
    return updated


def report(path: Path = LEDGER) -> str:
    entries = _load(path)
    lines = [
        f"budget ${BUDGET_USD:.2f}   spent ${spent(path):.2f}   remaining ${remaining(path):.2f}",
        f"({INSTANCE_TYPE} @ ${PRICE_PER_HOUR}/h in {REGION}; "
        f"{remaining(path) / PRICE_PER_HOUR:.2f} GPU-hours left)",
    ]
    if entries:
        lines.append("")
        lines.append(f"  {'job':<44} {'max_run':>8} {'reserved':>9} {'actual':>8}")
        for e in entries:
            actual = f"${e.actual_usd:.2f}" if e.actual_usd is not None else "-"
            lines.append(
                f"  {e.job_name[:44]:<44} {e.max_run_s / 3600:>7.2f}h ${e.reserved_usd:>8.2f} {actual:>8}"
            )
    return "\n".join(lines)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--reconcile", action="store_true", help="pull actual billed seconds from AWS")
    args = ap.parse_args()
    if args.reconcile:
        print(f"reconciled {reconcile()} job(s)\n")
    print(report())
