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
import time
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
    # The backend sends at most 16 responses per request (MAX_RESPONSES_PER_REQUEST), so a full
    # batch of 16 is the normal case and must pass; one more is refused.
    full = [{"passages": ["p"], "answer": "a"}] * 16
    assert serve.validate({"head": "groundedness", "responses": full}) == full
    with pytest.raises(ValueError, match="exceeds"):
        serve.validate({"head": "groundedness", "responses": [{"passages": ["p"], "answer": "a"}] * 17})


def _until(condition, what: str, timeout_s: float = 5.0) -> None:
    """Wait for another thread to reach a state, failing the test instead of hanging it."""
    deadline = time.monotonic() + timeout_s
    while not condition():
        assert time.monotonic() < deadline, f"timed out waiting for {what}"
        time.sleep(0.001)


def _acquire_in_thread(gate) -> tuple[threading.Thread, list]:
    got: list = []
    waiter = threading.Thread(target=lambda: got.append(gate.acquire()), daemon=True)
    waiter.start()
    return waiter, got


def test_gate_admits_a_queued_request_when_the_slot_frees_and_refuses_past_the_queue():
    gate = serve.Gate(max_inflight=1, max_queue=1, timeout_s=10)
    assert gate.acquire() is True, "the one slot, taken without queueing"

    waiter, got = _acquire_in_thread(gate)
    _until(lambda: got or gate.waiting == 1, "the first waiter to queue")
    assert not got, f"the queued request returned {got} while the slot was still held"
    started = time.monotonic()
    assert gate.acquire() is False, "the queue's one place is taken, so the next request is refused"
    assert time.monotonic() - started < 5, "refused at once, not after waiting out the 10 s timeout"
    gate.release()
    waiter.join(5)
    assert got == [True], "the queued request gets the slot the moment it is released"
    assert gate.waiting == 0, "and leaves the queue, freeing its place"

    waiter, got = _acquire_in_thread(gate)
    _until(lambda: got or gate.waiting == 1, "the second waiter to queue")
    gate.release()
    waiter.join(5)
    assert got == [True], "a later request can queue and be admitted too"
    gate.release()

    timing_out = serve.Gate(max_inflight=1, max_queue=1, timeout_s=0.01)
    assert timing_out.acquire() is True
    assert timing_out.acquire() is False, "a waiter whose timeout passes is refused"
    assert timing_out.waiting == 0, "and leaves the queue"

    strict = serve.Gate(max_inflight=1, max_queue=0, timeout_s=10)
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


class _RecordingTokenizer:
    """Records how encode() calls the tokenizer."""

    def __init__(self):
        self.calls: list[tuple[tuple, dict]] = []

    def __call__(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        return {}


def test_encode_puts_the_prompt_first_and_truncates_only_the_prompt():
    """Training encoded (prompt, answer) and cut only the prompt at MAX_LENGTH. Answer first, the
    answer cut, or another length would score sentences on tokens the model never saw."""
    tok = _RecordingTokenizer()
    passages, question, answer = ["The sky is blue."], "What colour is the sky?", "Blue."
    serve.encode(tok, passages, question, answer)
    assert tok.calls == [(
        (serve.lettuce_prompt(passages, question), answer),
        {"truncation": "only_first", "max_length": 8192, "return_offsets_mapping": True, "return_tensors": None},
    )]


class _CountingTokenizer:
    """Answers every text with `n` token ids and records the texts it was given."""

    def __init__(self, n: int):
        self.n = n
        self.seen: list[str] = []

    def __call__(self, text: str, add_special_tokens: bool = True) -> dict:
        assert add_special_tokens is False, "the bound counts the answer's own tokens"
        self.seen.append(text)
        return {"input_ids": [7] * self.n}


def test_fit_answer_cuts_to_the_char_cap_then_leaves_room_for_one_context_token():
    # MAX_LENGTH 8192 less [CLS] and two [SEP]s less one context token: 8188 answer tokens fit.
    long_answer = "a" * (serve.MAX_ANSWER_CHARS + 1000)
    fits = _CountingTokenizer(8188)
    assert serve.fit_answer(fits, long_answer) == "a" * serve.MAX_ANSWER_CHARS
    assert fits.seen == ["a" * serve.MAX_ANSWER_CHARS], "the char cap applies before tokens are counted"
    with pytest.raises(ValueError, match="too long to score"):
        serve.fit_answer(_CountingTokenizer(8189), long_answer)


class _FakeModel:
    def __init__(self):
        self.calls: list = []

    def eval(self):
        self.calls.append("eval")
        return self

    def half(self):
        self.calls.append("half")
        return self

    def to(self, device):
        self.calls.append(("to", device))
        return self


@pytest.mark.parametrize("dtype, halved", [("fp16", True), ("fp32", False)])
def test_head_casts_the_model_to_half_only_for_fp16(monkeypatch, dtype, halved):
    model = _FakeModel()
    fake_transformers = SimpleNamespace(
        AutoTokenizer=SimpleNamespace(from_pretrained=lambda name, **kw: object()),
        AutoModelForTokenClassification=SimpleNamespace(from_pretrained=lambda name, **kw: model))
    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace())
    monkeypatch.setitem(sys.modules, "transformers", fake_transformers)
    head = serve.Head("some/model", "rev", "cuda", dtype)
    assert ("half" in model.calls) is halved, model.calls
    assert ("to", "cuda") in model.calls and head.dtype == dtype


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


def _post(url: str, body, key: str = "k") -> tuple[int, dict, dict]:
    """(status, headers, JSON body). `body` is sent as is when it is bytes, else as JSON."""
    data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, dict(r.headers), json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), json.loads(e.read())


class _Serving:
    """serve.py's handler on a free local port, around a fake head."""

    def __init__(self, head, gate=None, clock=None):
        gate = gate or serve.Gate(1, 1, 1.0)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0),
                                          serve.make_handler(head, "k", gate, clock or serve.IdleClock()))
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def __enter__(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


def test_classify_scores_each_response_and_healthz_reports_heads_and_idle():
    request = json.loads((CONTRACT / "classify-request.json").read_text())
    fixed = json.loads((CONTRACT / "classify-response.json").read_text())["scores"]
    calls = []

    def score(passages, question, answer):
        calls.append((passages, question, answer))
        return fixed[len(calls) - 1]

    head = SimpleNamespace(device="mps", dtype="fp32", score=score)
    now = _FakeTime(100.0)
    with _Serving(head, clock=serve.IdleClock(now)) as s:
        now.t = 130.0
        health = _get(f"{s.base}/healthz")
        assert health == {"ok": True, "heads": ["groundedness"], "device": "mps", "dtype": "fp32", "idle_seconds": 30}
        assert _get(f"{s.base}/healthz")["idle_seconds"] == 30, "a health probe does not reset the idle clock"

        assert _post(f"{s.base}/classify", request, "wrong")[0] == 401
        assert calls == [], "an unauthorized request scores nothing"
        assert _get(f"{s.base}/healthz")["idle_seconds"] == 30, "an unauthorized request does not reset it"
        status, _, body = _post(f"{s.base}/classify", request)
        assert (status, body) == (200, {"scores": fixed}), "one score per response, in request order"
        want = [(r["passages"], r.get("question"), r["answer"]) for r in request["responses"]]
        assert calls == want, "each response's passages, question and answer reach the head as sent"
        assert calls[1][1] is None, "the summary response has no question, and the head is told so"
        assert _get(f"{s.base}/healthz")["idle_seconds"] == 0, "a finished /classify request does"
        now.t = 145.0
        assert _get(f"{s.base}/healthz")["idle_seconds"] == 15


ONE = {"head": "groundedness", "responses": [{"passages": ["p"], "question": "q?", "answer": "a"}]}


def test_a_failed_scoring_call_is_a_500_and_frees_the_only_slot():
    outcomes = iter([RuntimeError("CUDA out of memory"), {"unsupported": 0.1, "conflict": 0.0, "spans": []}])

    def score(passages, question, answer):
        out = next(outcomes)
        if isinstance(out, Exception):
            raise out
        return out

    head = SimpleNamespace(device="cuda", dtype="fp16", score=score)
    with _Serving(head, serve.Gate(1, 0, 1.0)) as s:
        status, _, body = _post(f"{s.base}/classify", ONE)
        assert (status, body) == (500, {"error": "scoring failed: RuntimeError"})
        status, _, body = _post(f"{s.base}/classify", ONE)
        assert status == 200, "the failed call released the one slot, so this is not a 429"
        assert body == {"scores": [{"unsupported": 0.1, "conflict": 0.0, "spans": []}]}


def test_a_refusal_from_the_head_or_a_malformed_body_is_a_400():
    def score(passages, question, answer):
        raise ValueError("groundedness answer too long to score")

    head = SimpleNamespace(device="cuda", dtype="fp16", score=score)
    with _Serving(head, serve.Gate(1, 0, 1.0)) as s:
        status, _, body = _post(f"{s.base}/classify", ONE)
        assert (status, body) == (400, {"error": "groundedness answer too long to score"})
        assert _post(f"{s.base}/classify", b"{not json")[0] == 400, "malformed JSON"
        assert _post(f"{s.base}/classify", b"[]")[0] == 400, "JSON that is not an object"
        assert _post(f"{s.base}/classify", ONE)[0] == 400, "and every refusal released the one slot"


def test_a_request_past_the_one_busy_slot_gets_429_with_retry_after():
    entered, release = threading.Event(), threading.Event()

    def score(passages, question, answer):
        entered.set()
        assert release.wait(5), "the test never released the blocked scoring call"
        return {"unsupported": 0.0, "conflict": 0.0, "spans": []}

    head = SimpleNamespace(device="cuda", dtype="fp16", score=score)
    with _Serving(head, serve.Gate(1, 0, 1.0)) as s:
        first: list = []
        holder = threading.Thread(target=lambda: first.append(_post(f"{s.base}/classify", ONE)), daemon=True)
        holder.start()
        assert entered.wait(5), "the first request never reached the head"
        status, headers, body = _post(f"{s.base}/classify", ONE)
        assert (status, headers.get("Retry-After"), body) == (429, "2", {"error": "at capacity, retry later"})
        release.set()
        holder.join(5)
        assert first and first[0][0] == 200


def test_idle_clock_counts_a_request_in_flight_as_activity():
    now = _FakeTime(0.0)
    clock = serve.IdleClock(now)
    clock.start()
    now.t = 3600.0
    assert clock.seconds() == 0, "a request in flight is activity, however long it runs"
    clock.finish()
    now.t = 3600.0 + 9 * 60
    assert clock.seconds() == 9 * 60


class _Readings:
    """A now() that returns the given times in order, one per call, and fails once they run out,
    so a watch that never stops ends the test instead of hanging it."""

    def __init__(self, *times: float):
        self.times = list(times)
        self.last = None

    def __call__(self) -> float:
        assert self.times, "watch_idle kept checking past the idle limit without stopping"
        self.last = self.times.pop(0)
        return self.last


def test_idle_watch_keeps_watching_under_the_limit_and_stops_once_at_it():
    now = _Readings(0.0, 9 * 60, 10 * 60)  # startup, then one reading per check
    clock = serve.IdleClock(now)
    stops = []
    serve.watch_idle(clock, 10, lambda: stops.append(now.last), interval_s=0)
    assert stops == [10 * 60], "not at 9 idle minutes, and exactly once at 10"


def _fake_torch(mps: bool, cuda: bool) -> SimpleNamespace:
    return SimpleNamespace(backends=SimpleNamespace(mps=SimpleNamespace(is_available=lambda: mps)),
                           cuda=SimpleNamespace(is_available=lambda: cuda))


@pytest.mark.parametrize("requested, mps, cuda, picked", [
    ("cpu", True, True, "cpu"),  # an explicit --device wins over any accelerator found
    ("auto", True, True, "mps"),  # Apple silicon first
    ("auto", False, True, "cuda"),
    ("auto", False, False, "cpu"),  # no accelerator still serves, slowly
])
def test_pick_device_honours_the_request_then_prefers_an_accelerator(monkeypatch, requested, mps, cuda, picked):
    monkeypatch.setitem(sys.modules, "torch", _fake_torch(mps, cuda))
    assert serve.pick_device(requested) == picked


def test_only_the_two_routes_answer_and_everything_else_is_404():
    def score(passages, question, answer):
        raise AssertionError("no route but /classify scores")

    head = SimpleNamespace(device="cuda", dtype="fp16", score=score)
    with _Serving(head) as s:
        with pytest.raises(urllib.error.HTTPError) as got:
            urllib.request.urlopen(f"{s.base}/healthz/extra", timeout=5)
        assert (got.value.code, json.loads(got.value.read())) == (404, {"error": "not found"})
        status, _, body = _post(f"{s.base}/classify/extra", ONE)
        assert (status, body) == (404, {"error": "not found"}), "an authorized POST off /classify scores nothing"
