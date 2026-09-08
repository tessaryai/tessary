# SPDX-License-Identifier: Apache-2.0
"""The eval's seam into the shipping tool-error detector — PROGRAM.md §12.

`ToolErrorDetector`, `ToolErrorRate` and the config record were written pure (no Spring, no database,
no clock) so this harness could drive them directly, and this module is what collects on that: every
number the runs report comes out of the Java classes themselves, reached through a `jshell` process
running `bridge.jsh` against the compiled backend.

If the bridge cannot start, every entry point FAILS LOUDLY rather than falling back to a Python
restatement of the arithmetic. behaviour drift is why: its Java port and its Python harness silently
diverged for a release, and every offline number measured in that window described a detector that does
not ship.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"
DRIVER = Path(__file__).resolve().parent / "bridge.jsh"
SENTINEL = "TOOL-ERROR-BRIDGE OK"

#: Modules holding the classes bridge.jsh imports. A list, not one path: `02e217d0` split the backend
#: into twelve Maven modules, so `app/target/classes` holds none of the detector.
CLASS_MODULES = ("analysis",)

MAVEN_ARTIFACTS = (
    ("com/fasterxml/jackson/core", "jackson-databind"),
    ("com/fasterxml/jackson/core", "jackson-core"),
    ("com/fasterxml/jackson/core", "jackson-annotations"),
    ("org/jspecify", "jspecify"),
)


class BridgeUnavailable(RuntimeError):
    """The JVM or the compiled backend is missing. Raised with what to run, never swallowed."""


def java_home() -> Path | None:
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / "jshell").exists():
        return Path(home)
    for candidate in ("/opt/homebrew/opt/openjdk@25", "/usr/local/opt/openjdk@25"):
        if (Path(candidate) / "bin" / "jshell").exists():
            return Path(candidate)
    found = shutil.which("jshell")
    return Path(found).parent.parent if found else None


def _newest(paths: list[Path]) -> Path | None:
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def classpath() -> str:
    dirs = [BACKEND / m / "target" / "classes" for m in CLASS_MODULES]
    missing = [d for d in dirs if not d.exists()]
    if missing:
        raise BridgeUnavailable(
            "the backend has not been compiled in this worktree — missing:\n  "
            + "\n  ".join(str(d) for d in missing)
            + "\ncompile it once first:\n"
            "  export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && cd backend && mvn -q -pl app -am compile"
        )
    entries = [str(d) for d in dirs]
    m2 = Path(os.environ.get("MAVEN_REPO_LOCAL", Path.home() / ".m2" / "repository"))
    for group, artifact in MAVEN_ARTIFACTS:
        jar = _newest(sorted(
            p
            for p in (m2 / group / artifact).glob(f"*/{artifact}-*.jar")
            # -sources/-javadoc jars carry no classes, and a fresh IDE download gives
            # them the newest mtime — jshell then "compiles" against empty jars.
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))
        ))
        if jar is None:
            raise BridgeUnavailable(f"no {artifact} jar under {m2 / group / artifact}")
        entries.append(str(jar))
    return ":".join(entries)


def replay(series: list[dict], h: float, min_effect_size: float = 0.05, min_baseline_calls: int = 500) -> list[dict]:
    """One jshell round trip: every tool's series in, one alarm-or-not per tool out.

    Batched because a JVM start is ~2s and a floor sweep replays every tool at a dozen candidate
    thresholds.
    """
    home = java_home()
    if home is None:
        raise BridgeUnavailable(
            "no jshell on PATH or in JAVA_HOME — export JAVA_HOME=/opt/homebrew/opt/openjdk@25"
        )
    cp = classpath()
    with tempfile.TemporaryDirectory() as tmp:
        in_path, out_path = Path(tmp) / "in.json", Path(tmp) / "out.json"
        in_path.write_text(
            json.dumps(
                {"h": h, "min_effect_size": min_effect_size, "min_baseline_calls": min_baseline_calls, "series": series}
            )
        )
        proc = subprocess.run(
            [
                str(home / "bin" / "jshell"),
                "--class-path", cp,
                f"-R-Dte.in={in_path}",
                f"-R-Dte.out={out_path}",
                str(DRIVER),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        # jshell exits 0 whatever a snippet did, so the sentinel is the only honest success signal.
        if SENTINEL not in proc.stdout:
            raise BridgeUnavailable(
                "the jshell bridge did not complete — the shipping classes were NOT reached, so no "
                "number here would describe the detector that ships.\n" + proc.stdout + proc.stderr
            )
        return json.loads(out_path.read_text())["results"]
