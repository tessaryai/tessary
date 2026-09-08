# SPDX-License-Identifier: Apache-2.0
"""The eval's seam into the shipping detector — PLAN.md §9.

`MetricDriftDetector`, `MetricHistogram` and `MetricSuppression` were all written pure (no Spring, no
database, no clock) so that this harness could drive them directly, and this module is what collects
on that: every number the three runs report comes out of the Java classes themselves, reached through
a `jshell` process running `bridge.jsh` against the compiled backend. Nothing here re-implements the
statistic. What the Python side owns is *which comparisons happen* — windowing, bucketing, injection —
and that boundary is stated exactly in the module README so nobody has to infer it from the code.

`derive` extends the same seam one step earlier, to the corpus itself: `ActionSymbol` decides which
spans share a tool bucket, and `TokenUsage` + `TokenPriceBook` decide what a turn cost and whether it
has a cost at all. Both are shipped decisions about the POPULATION rather than about the statistic, and
a Python restatement of either would move the false-positive rate the null run reports without moving
anything that ships.

Why jshell rather than a JNI/py4j bridge or a small Java main: it needs no dependency the repo does
not already have, no addition to the backend's build, and no new top-level tooling. The cost is a
~2s JVM start per call, which is why the protocol is batched — one request carries every sketch and
every decision a run needs.

Requires the backend modules `MODULES` names to have been compiled at least once. It is a read of
build output, never a build: this module never invokes Maven, because a 15-minute gate triggered by
an eval script is a gate nobody runs the eval behind.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"

#: The reactor modules whose build output the driver compiles against. The backend was one module
#: when this bridge was written and is twelve now, so a single `app/target/classes` reaches nothing:
#: the detector, the sketch, the suppression rule and `ActionSymbol` are in `analysis`, `TokenUsage`
#: and `TokenPriceBook` (plus the vendored price snapshot it reads as a classpath resource) are in
#: `substrate`, and both sit on `shared` and `core`. Naming the four rather than globbing every
#: module keeps the driver's classpath a stated dependency: a class moving to a module not listed
#: here fails at compile with the class named, instead of being picked up silently from wherever it
#: landed.
MODULES = ("analysis", "substrate", "core", "shared")
DRIVER = Path(__file__).resolve().parent / "bridge.jsh"
SENTINEL = "METRIC-DRIFT-BRIDGE OK"

#: The runtime classpath the driver needs beyond the backend's own classes. Jackson because the
#: sketch serializes through it and the protocol here is JSON; jspecify because the detector's
#: signatures carry `@Nullable` and javac reads the annotation type when it compiles a snippet
#: against them.
#:
#: The detector, the histogram, the suppression rule and `ActionSymbol` are pure — no Spring, which is
#: the whole point of their being reachable from here at all. `TokenPriceBook` is the one exception and
#: it is a deliberate one: pricing a corpus needs the vendored LiteLLM snapshot, the book reads it
#: through `ClassPathResource` and logs through slf4j, and a second price table on the Python side
#: would be a book to keep in step whose divergence is silent. So spring-core and slf4j-api ride along
#: for that one class; nothing here starts a context or resolves a bean.
MAVEN_ARTIFACTS = (
    ("com/fasterxml/jackson/core", "jackson-databind"),
    ("com/fasterxml/jackson/core", "jackson-core"),
    ("com/fasterxml/jackson/core", "jackson-annotations"),
    ("org/jspecify", "jspecify"),
    ("org/springframework", "spring-core"),
    ("org/slf4j", "slf4j-api"),
)


class BridgeUnavailable(RuntimeError):
    """The JVM or the compiled backend is missing. Raised with what to run, never swallowed.

    An eval that quietly fell back to a Python re-implementation of the arithmetic would report
    numbers for a detector nobody ships, which is worse than reporting nothing.
    """


def java_home() -> Path | None:
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / "jshell").exists():
        return Path(home)
    # The repo's documented toolchain: `java` is not on PATH on the Mac dev box, so the Homebrew JDK
    # is checked before giving up (classifiers/metric_drift/README.md says the same).
    for candidate in ("/opt/homebrew/opt/openjdk@25", "/usr/local/opt/openjdk@25"):
        if (Path(candidate) / "bin" / "jshell").exists():
            return Path(candidate)
    found = shutil.which("jshell")
    return Path(found).parent.parent if found else None


def _newest(paths: list[Path]) -> Path | None:
    """The highest version of one artifact in the local repository, by mtime.

    Version strings sort badly (`2.9.0` after `2.18.1` lexically) and the backend pins its Jackson
    through a BOM this module deliberately does not parse, so "whatever Maven downloaded most
    recently for this repo" is both simpler and right in practice. A mismatch surfaces immediately as
    a compile error from jshell rather than as a wrong number.
    """
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def module_classes() -> list[Path]:
    """Each listed module's build output, in classpath order. Empty when nothing is compiled."""
    return [d for m in MODULES if (d := BACKEND / m / "target" / "classes").exists()]


def classpath() -> str:
    compiled = module_classes()
    missing = [m for m in MODULES if not (BACKEND / m / "target" / "classes").exists()]
    if missing:
        raise BridgeUnavailable(
            f"no build output for backend module(s) {', '.join(missing)} — compile once first:\n"
            "  export JAVA_HOME=/opt/homebrew/opt/openjdk@25\n"
            "  cd backend && mvn -q -pl analysis,substrate -am compile"
        )
    override = os.environ.get("METRIC_DRIFT_CLASSPATH")
    if override:
        return ":".join([*(str(p) for p in compiled), override])

    m2 = Path(os.environ.get("MAVEN_REPO_LOCAL", Path.home() / ".m2" / "repository"))
    entries = [str(p) for p in compiled]
    for group, artifact in MAVEN_ARTIFACTS:
        jar = _newest(sorted(
            p
            for p in (m2 / group / artifact).glob(f"*/{artifact}-*.jar")
            # -sources/-javadoc jars carry no classes, and a fresh IDE download gives
            # them the newest mtime — jshell then "compiles" against empty jars.
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))
        ))
        if jar is None:
            raise BridgeUnavailable(
                f"no {artifact} jar under {m2 / group / artifact} — build the backend once so Maven "
                "populates the local repository, or set METRIC_DRIFT_CLASSPATH."
            )
        entries.append(str(jar))
    return ":".join(entries)


@dataclass
class Sketch:
    """One window's raw samples, in the measure's own units — milliseconds or dollars.

    Sent raw and binned on the Java side by the real `MetricHistogram`, so the grid, the edge
    counters and the log are the shipping ones. Sending pre-binned counts would put the one piece of
    arithmetic the whole statistic rests on back on this side of the wire.
    """

    id: str
    values: list[float]
    grid: str = "duration"
    bins: int = 320

    def payload(self) -> dict:
        if any(v < 0 for v in self.values):
            raise ValueError(f"sketch {self.id} carries a negative sample; log of it is not a number")
        return {"id": self.id, "grid": self.grid, "bins": self.bins, "values": self.values}


@dataclass
class Job:
    """One comparison: a reference window, a current window, and the operating point to judge at."""

    id: str
    measure: str
    reference: str  # "pinned" | "previous"
    cur: str
    bucket_key: str
    ref: str | None = None
    w1_floor: float = 0.18
    min_sample: int = 150
    bins: int = 320

    def payload(self) -> dict:
        return {
            "id": self.id,
            "measure": self.measure,
            "reference": self.reference,
            "ref": self.ref,
            "cur": self.cur,
            "bucket_key": self.bucket_key,
            "w1_floor": self.w1_floor,
            "min_sample": self.min_sample,
            "bins": self.bins,
        }


@dataclass
class SuppressionRequest:
    """One turn shift offered to §6.1's rule, with the tool shifts that might account for it."""

    id: str
    turn_job: str
    turn_bucket_key: str
    turn_call_sites: list[str]
    tools: list[dict] = field(default_factory=list)  # {"job", "bucket_key", "call_sites"}
    explained_by_fraction: float = 0.5

    def payload(self) -> dict:
        return {
            "id": self.id,
            "turn_job": self.turn_job,
            "turn_bucket_key": self.turn_bucket_key,
            "turn_call_sites": self.turn_call_sites,
            "tools": self.tools,
            "explained_by_fraction": self.explained_by_fraction,
        }


@dataclass
class Decision:
    """A `MetricDriftDetector.Decision`, verbatim, plus the strings a finding would have carried."""

    id: str
    fired: bool
    measure: str
    reference: str
    w1_log: float
    ratio: float
    direction: str
    n_ref: int
    n_cur: int
    silence: str | None
    cause_key: str
    title: str
    ref_p50: float | None
    cur_p50: float | None
    ref_p95: float | None
    cur_p95: float | None

    @classmethod
    def of(cls, row: dict) -> Decision:
        return cls(
            id=row["id"],
            fired=row["fired"],
            measure=row["measure"],
            reference=row["reference"],
            w1_log=row["w1_log"],
            ratio=row["ratio"],
            direction=row["direction"],
            n_ref=row["n_ref"],
            n_cur=row["n_cur"],
            silence=row["silence"],
            cause_key=row["cause_key"],
            title=row["title"],
            ref_p50=row.get("ref_p50"),
            cur_p50=row.get("cur_p50"),
            ref_p95=row.get("ref_p95"),
            cur_p95=row.get("cur_p95"),
        )


@dataclass
class SymbolRequest:
    """One dispatchable span's raw `(kind, name)`, to be minted into an `ActionSymbol` bucket key."""

    id: str
    kind: str | None
    name: str | None

    def payload(self) -> dict:
        return {"id": self.id, "kind": self.kind, "name": self.name}


@dataclass
class PriceRequest:
    """One turn's llm leaves, `[{"model": ..., "usage": {...}}]`, to be summed and priced.

    The usage blobs go over RAW. Their key names lie about their own semantics across providers, which
    is exactly why `TokenUsage` owns them and why nothing on this side pre-sums them.
    """

    id: str
    leaves: list[dict]

    def payload(self) -> dict:
        return {"id": self.id, "leaves": self.leaves}


@dataclass
class Derived:
    #: Per symbol id: the `ActionSymbol` bucket key.
    symbols: dict[str, str]
    #: Per price id: {"cost_usd", "input_tokens", "output_tokens", "cache_read_tokens",
    #: "cache_write_tokens"}. `cost_usd` is None exactly where the sweep would abstain — one unpriced
    #: leaf abstains the whole turn, and unpriced is not free.
    priced: dict[str, dict]


@dataclass
class Response:
    decisions: dict[str, Decision]
    #: Per sketch id: {"count", "underflow", "overflow"}. A non-empty overflow is PLAN.md §11's
    #: signal that a grid's range is wrong, and it is invisible in the decisions themselves.
    edges: dict[str, dict]
    #: Per suppression id: {"suppressed_by", "suppressed_by_bucket", "covered"} — all null when the
    #: turn shift stands on its own.
    suppression: dict[str, dict]


def decide(
    sketches: list[Sketch],
    jobs: list[Job],
    suppression: list[SuppressionRequest] | None = None,
    *,
    timeout_seconds: int = 900,
) -> Response:
    """Run one batch through the real classes. One JVM start, however many decisions."""
    payload = _invoke(
        {
            "sketches": [s.payload() for s in sketches],
            "jobs": [j.payload() for j in jobs],
            "suppression": [s.payload() for s in (suppression or [])],
        },
        timeout_seconds,
    )
    return Response(
        decisions={row["id"]: Decision.of(row) for row in payload["jobs"]},
        edges={row["id"]: row for row in payload["sketch_edges"]},
        suppression={row["id"]: row for row in payload["suppression"]},
    )


def derive(
    symbols: list[SymbolRequest],
    priced: list[PriceRequest],
    *,
    timeout_seconds: int = 900,
) -> Derived:
    """Resolve what a corpus EXPORT could not: tool bucket keys, and priced cost.

    Separate entry point from `decide` because it answers a different question at a different time —
    this runs once, when a corpus is loaded, and shapes the population every later comparison is made
    over. Same driver, same protocol, same JVM start; the sections `decide` fills are simply empty.
    """
    payload = _invoke(
        {"symbols": [s.payload() for s in symbols], "priced": [p.payload() for p in priced]},
        timeout_seconds,
    )
    return Derived(
        symbols={row["id"]: row["bucket_key"] for row in payload["symbols"]},
        priced={row["id"]: row for row in payload["priced"]},
    )


def _invoke(request: dict, timeout_seconds: int) -> dict:
    """One jshell round trip. Sections the caller omits arrive as absent and iterate as empty."""
    home = java_home()
    if home is None:
        raise BridgeUnavailable(
            "no jshell on PATH or in JAVA_HOME — export JAVA_HOME=/opt/homebrew/opt/openjdk@25 "
            "(the repo's documented toolchain; `java` is deliberately not on PATH here)."
        )
    cp = classpath()

    with tempfile.TemporaryDirectory(prefix="metric-drift-bridge-") as tmp:
        in_path = Path(tmp) / "request.json"
        out_path = Path(tmp) / "response.json"
        in_path.write_text(json.dumps(request))
        proc = subprocess.run(
            [
                str(home / "bin" / "jshell"),
                "--class-path",
                cp,
                f"-R-Dmd.in={in_path}",
                f"-R-Dmd.out={out_path}",
                "-q",
                str(DRIVER),
            ],
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        # jshell exits 0 whatever a snippet did, so the sentinel is the only honest success signal.
        if SENTINEL not in proc.stdout or not out_path.exists():
            raise BridgeUnavailable(
                "the jshell bridge did not complete — the shipping classes were NOT reached, so no "
                f"number below is about the code that ships.\nstdout:\n{proc.stdout}\n"
                f"stderr:\n{proc.stderr}"
            )
        return json.loads(out_path.read_text())
