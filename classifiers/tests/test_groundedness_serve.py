# SPDX-License-Identifier: Apache-2.0
"""The groundedness server's contract: it runs as one file with the dependencies its header declares,
its request validation and per-sentence reduction hold their shape, its copy of the training
encoding matches the answer key the original helpers produced, and `/healthz` reports what the
backend and the idle stop read.

serve.py is loaded from its path rather than as a package module, the way `uv run` loads it."""

from __future__ import annotations

import ast
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import threading
import tomllib
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

import pytest

CLASSIFIERS = Path(__file__).resolve().parents[1]
SERVE_PY = CLASSIFIERS / "groundedness" / "serve.py"
CONTRACT = CLASSIFIERS / "groundedness" / "contract"
ANSWER_KEY = Path(__file__).resolve().parent / "fixtures" / "groundedness_answer_key.json"

# A dependency's distribution name to the top-level module it installs, where the two differ.
PACKAGE_MODULES = {"transformers": "transformers", "torch": "torch"}


def _load_serve():
    spec = importlib.util.spec_from_file_location("groundedness_serve", SERVE_PY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


serve = _load_serve()


def test_validate_accepts_the_contract_and_refuses_the_rest():
    ok = serve.validate({"head": "groundedness", "responses": [{"passages": ["p"], "answer": "a"}]})
    assert ok == [{"passages": ["p"], "answer": "a"}]
    with pytest.raises(ValueError, match="unknown head: frustration"):
        serve.validate({"head": "frustration", "texts": ["x"]})
    with pytest.raises(ValueError, match="not texts or pairs"):
        serve.validate({"head": "groundedness", "texts": ["x"]})
    with pytest.raises(ValueError, match="non-empty array"):
        serve.validate({"head": "groundedness", "responses": [{"passages": [], "answer": "a"}]})
    with pytest.raises(ValueError, match="exceeds"):
        serve.validate({"head": "groundedness", "responses": [{"passages": ["p"], "answer": "a"}] * 17})


def test_gate_bounds_inflight_and_queue_and_answers_the_rest_with_a_refusal():
    gate = serve.Gate(max_inflight=1, max_queue=1, timeout_s=0.05)
    assert gate.acquire() is True, "the one slot, taken without queueing"
    assert gate.acquire() is False, "the one queue place times out waiting for the slot"
    gate.release()
    assert gate.acquire() is True, "the released slot is reusable"
    gate.release()
    strict = serve.Gate(max_inflight=1, max_queue=0, timeout_s=0.05)
    assert strict.acquire() is True, "no queue still means the free slot is granted"
    assert strict.acquire() is False, "and the second concurrent request is refused at once"


def test_reduce_takes_the_max_token_per_sentence_and_skips_short_ones():
    answer = "The refund takes two hours to arrive. Ok. Second sentence is also long enough."
    # Three "tokens" per sentence, laid over character offsets; one context token first (seq 0).
    offsets = [[0, 0], [0, 10], [10, 25], [25, 37], [38, 41], [42, 60], [60, 80]]
    seq = [0, 1, 1, 1, 1, 1, 1]
    o = [1.0, 0.0, 0.0]
    probs = [o, [0.9, 0.05, 0.05], [0.2, 0.7, 0.1], o, [0.0, 0.0, 1.0], [0.5, 0.1, 0.4], o]
    out = serve.reduce(probs, offsets, seq, answer)
    assert [s["start"] for s in out["spans"]] == [0, 42], "the 'Ok.' sentence is under MIN_SENT_CHARS"
    first, second = out["spans"]
    assert first["unsupported"] == pytest.approx(0.8) and first["conflict"] == pytest.approx(0.1)
    assert second["unsupported"] == pytest.approx(0.5) and second["conflict"] == pytest.approx(0.4)
    assert out["unsupported"] == pytest.approx(0.8) and out["conflict"] == pytest.approx(0.4)


def test_runs_alone_in_an_empty_directory(tmp_path):
    alone = tmp_path / "alone"
    alone.mkdir()
    shutil.copy(SERVE_PY, alone / "serve.py")
    subprocess.run([sys.executable, "-c", "import ast,sys; ast.parse(open('serve.py').read())"],
                   cwd=alone, check=True)

    # Fake torch and transformers, so the import needs neither installed. Anything that touches
    # them at import time gets a module with nothing in it and fails loudly.
    stubs = tmp_path / "stubs"
    stubs.mkdir()
    (stubs / "sitecustomize.py").write_text(
        "import sys, types\n"
        "for name in ('torch', 'transformers'):\n"
        "    sys.modules[name] = types.ModuleType(name)\n"
    )
    # -S leaves out site-packages and -E ignores PYTHONPATH, so sys.path is the standard library
    # plus the directory serve.py sits in. Nothing from this repository or its environment resolves.
    probe = (
        "import sys\n"
        f"sys.path.append({str(stubs)!r})\n"
        "import sitecustomize\n"
        "import serve\n"
        f"assert serve.__file__ == {str(alone / 'serve.py')!r}, serve.__file__\n"
        "leaked = sorted(m for m in sys.modules if m.split('.')[0] in ('groundedness', 'framework'))\n"
        "assert not leaked, leaked\n"
        "assert serve.validate({'head': 'groundedness', 'responses': [{'passages': ['p'], 'answer': 'a'}]})\n"
        "print('ok')\n"
    )
    run = subprocess.run([sys.executable, "-S", "-E", "-c", probe], cwd=alone, capture_output=True, text=True)
    assert run.returncode == 0 and run.stdout.strip() == "ok", run.stderr

    helped = subprocess.run([sys.executable, "-S", "-E", "serve.py", "--help"], cwd=alone,
                            capture_output=True, text=True)
    assert helped.returncode == 0, helped.stderr
    assert "--idle-exit-minutes" in helped.stdout


def _script_metadata(source: str) -> dict:
    """The PEP 723 `script` block, parsed with the reference regex from the PEP."""
    regex = r"(?m)^# /// (?P<type>[a-zA-Z0-9-]+)$\s(?P<content>(^#(| .*)$\s)+)^# ///$"
    blocks = [m for m in re.finditer(regex, source) if m.group("type") == "script"]
    assert len(blocks) == 1, "serve.py needs exactly one `# /// script` block"
    content = "".join(line[2:] if line.startswith("# ") else line[1:]
                      for line in blocks[0].group("content").splitlines(keepends=True))
    return tomllib.loads(content)


def test_header_lists_every_third_party_import():
    source = SERVE_PY.read_text()
    meta = _script_metadata(source)
    assert meta["requires-python"] == ">=3.11"
    declared = {PACKAGE_MODULES.get(n, n) for n in
                (re.match(r"[A-Za-z0-9_.-]+", d).group(0).lower().replace("-", "_") for d in meta["dependencies"])}

    imported = set()
    for node in ast.walk(ast.parse(source)):  # every import, including the ones inside functions
        if isinstance(node, ast.Import):
            imported |= {a.name.split(".")[0] for a in node.names}
        elif isinstance(node, ast.ImportFrom) and node.level == 0:
            imported.add(node.module.split(".")[0])
    third_party = imported - set(sys.stdlib_module_names) - {"__future__"}
    assert third_party, "the walk found no third-party import at all, so it is not reading serve.py"
    missing = third_party - declared
    assert not missing, f"imported by serve.py but not in its `# /// script` dependencies: {sorted(missing)}"


def _answer_key() -> dict:
    return json.loads(ANSWER_KEY.read_text(encoding="utf-8"))


def test_encoding_matches_the_answer_key():
    key = _answer_key()
    assert (key["model"], key["revision"]) == (serve.DEFAULT_MODEL, serve.DEFAULT_REVISION)
    assert key["max_length"] == serve.MAX_LENGTH and key["min_sent_chars"] == serve.MIN_SENT_CHARS
    assert len(key["cases"]) >= 12
    for case in key["cases"]:
        assert serve.lettuce_prompt(case["passages"], case["question"]) == case["prompt"], case["name"]
        got = [(s, e, len(t.strip()) >= serve.MIN_SENT_CHARS) for s, e, t in serve._sentences(case["answer"])]
        want = [(s["start"], s["end"], s["scored"]) for s in case["sentences"]]
        assert got == want, case["name"]


@pytest.mark.network
def test_encoding_matches_the_answer_key_on_the_pinned_tokenizer():
    transformers = pytest.importorskip("transformers")
    key = _answer_key()
    try:
        tok = transformers.AutoTokenizer.from_pretrained(key["model"], revision=key["revision"])
    except Exception as e:  # noqa: BLE001 — offline, or the hub is unreachable: this half can't run
        pytest.skip(f"the pinned tokenizer did not load: {e}")
    for case in key["cases"]:
        enc = serve.encode(tok, case["passages"], case["question"], case["answer"])
        assert enc["input_ids"] == case["input_ids"], case["name"]
        answer = [(i, o) for i, (o, s) in enumerate(zip(enc["offset_mapping"], enc.sequence_ids())) if s == 1]
        assert (answer[0][0], answer[-1][0] + 1) == (case["answer_token_start"], case["answer_token_end"]), case["name"]
        for sent in case["sentences"]:
            toks = [i for i, (a, b) in answer if a < sent["end"] and b > sent["start"]]
            got = (toks[0], toks[-1] + 1) if toks else (None, None)
            assert got == (sent["token_start"], sent["token_end"]), (case["name"], sent)


def _shape(value):
    """The JSON shape of a value: key names and types, with a list described by its first item."""
    if isinstance(value, dict):
        return {k: _shape(v) for k, v in sorted(value.items())}
    if isinstance(value, list):
        return [_shape(value[0])] if value else []
    return type(value).__name__


def test_contract_fixtures_round_trip():
    request = json.loads((CONTRACT / "classify-request.json").read_text())
    response = json.loads((CONTRACT / "classify-response.json").read_text())
    responses = serve.validate(request)
    assert any(r.get("question") for r in responses) and any(not r.get("question") for r in responses)
    assert len(response["scores"]) == len(responses)

    for r, fixed in zip(responses, response["scores"]):
        # One token per sentence, so `reduce` runs on this answer's real sentence ranges.
        sentences = list(serve._sentences(r["answer"]))
        offsets = [[0, 0]] + [[s, e] for s, e, _ in sentences]
        seq = [0] + [1] * len(sentences)
        probs = [[1.0, 0.0, 0.0]] + [[0.2, 0.7, 0.1]] * len(sentences)
        out = serve.reduce(probs, offsets, seq, r["answer"])
        assert _shape(out) == _shape(fixed)
        assert [(s["start"], s["end"]) for s in out["spans"]] == [(s["start"], s["end"]) for s in fixed["spans"]]
        assert fixed["unsupported"] == max(s["unsupported"] for s in fixed["spans"])
        assert fixed["conflict"] == max(s["conflict"] for s in fixed["spans"])


class _FakeTime:
    def __init__(self, t: float):
        self.t = t

    def __call__(self) -> float:
        return self.t


def _get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as r:
        return json.loads(r.read())


def _post(url: str, body: dict, key: str) -> int:
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def test_healthz_reports_heads_and_idle():
    fixed = json.loads((CONTRACT / "classify-response.json").read_text())["scores"]
    calls = iter(fixed * 2)
    head = SimpleNamespace(device="mps", dtype="fp32", score=lambda passages, question, answer: next(calls))
    now = _FakeTime(100.0)
    clock = serve.IdleClock(now)
    server = ThreadingHTTPServer(("127.0.0.1", 0), serve.make_handler(head, "k", serve.Gate(1, 1, 1.0), clock))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        now.t = 130.0
        health = _get(f"{base}/healthz")
        assert health == {"ok": True, "heads": ["groundedness"], "device": "mps", "dtype": "fp32", "idle_seconds": 30}
        assert _get(f"{base}/healthz")["idle_seconds"] == 30, "a health probe does not reset the idle clock"

        request = json.loads((CONTRACT / "classify-request.json").read_text())
        assert _post(f"{base}/classify", request, "wrong") == 401
        assert _get(f"{base}/healthz")["idle_seconds"] == 30, "an unauthorized request does not reset it"
        assert _post(f"{base}/classify", request, "k") == 200
        assert _get(f"{base}/healthz")["idle_seconds"] == 0, "a finished /classify request does"
        now.t = 145.0
        assert _get(f"{base}/healthz")["idle_seconds"] == 15
    finally:
        server.shutdown()
        server.server_close()


def test_idle_watch_stops_the_server_once_the_limit_passes():
    now = _FakeTime(0.0)
    clock = serve.IdleClock(now)
    clock.start()
    now.t = 3600.0
    assert clock.seconds() == 0, "a request in flight is activity, however long it runs"
    clock.finish()
    now.t = 3600.0 + 9 * 60
    assert clock.seconds() == 9 * 60

    stops = []
    now.t = 3600.0 + 10 * 60
    serve.watch_idle(clock, 10, lambda: stops.append(True), interval_s=0)
    assert stops == [True]
