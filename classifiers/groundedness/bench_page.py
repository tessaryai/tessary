# SPDX-License-Identifier: Apache-2.0
"""Render the latency matrix as one HTML page (the report), from the folded bench results."""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path

from groundedness.bench_latency import BUCKETS
from groundedness.bench_report import fold

# External reference measurements for the same model class and buckets (cloud vCPUs and a T4), for comparison.
REFERENCE = [
    ("395M, 2 vCPU (reference)", [3.3, 6.0, 10.7, 36.6, 96.0, 165.0]),
    ("395M, 4 vCPU (reference)", [2.1, 3.7, 6.3, 19.8, 51.0, 86.0]),
    ("395M, T4 GPU (reference)", [0.09, 0.17, 0.30, 1.0, 2.8, 4.5]),
]

GROUPS = [
    ("PyTorch, CPU (host, fp32)", ["torch-cpu-t2", "torch-cpu-t4", "torch-cpu-t8", "torch-cpu-t12"]),
    ("PyTorch, Apple GPU via Metal (host)", ["torch-mps-fp32", "torch-mps-fp16"]),
    ("ONNX Runtime, CPU (host; the served engine)", ["ort-cpu-fp32-t2", "ort-cpu-fp32-t4", "ort-cpu-fp32-t8",
                                                     "ort-cpu-int8-t2", "ort-cpu-int8-t4", "ort-cpu-int8-t8"]),
    ("The served container (Docker, CPU-only by construction), as shipped", ["http-container-cpus2", "http-container-cpus4",
                                                                              "http-container-cpus8"]),
    ("The served container with ORT threads pinned to the CPU quota", ["http-container-cpus2-threads2",
                                                                          "http-container-cpus4-threads4",
                                                                          "http-container-cpus8-threads8",
                                                                          "http-container-cpus8-threads8-mem14g"]),
    ("The served code running natively on macOS (node + onnxruntime-node, no VM)", ["native-node-threads4",
                                                                                     "native-node-threads8"]),
]

PRETTY = {
    "torch-cpu-t2": "torch fp32, 2 threads", "torch-cpu-t4": "torch fp32, 4 threads", "torch-cpu-t8": "torch fp32, 8 threads",
    "torch-cpu-t12": "torch fp32, 12 threads", "torch-mps-fp32": "torch Metal, fp32", "torch-mps-fp16": "torch Metal, fp16",
    "ort-cpu-fp32-t2": "ORT fp32, 2 threads", "ort-cpu-fp32-t4": "ORT fp32, 4 threads", "ort-cpu-fp32-t8": "ORT fp32, 8 threads",
    "ort-cpu-int8-t2": "ORT int8, 2 threads", "ort-cpu-int8-t4": "ORT int8, 4 threads", "ort-cpu-int8-t8": "ORT int8, 8 threads",
    "http-container-cpus2": "classify-service, 2 CPUs", "http-container-cpus4": "classify-service, 4 CPUs",
    "http-container-cpus8": "classify-service, 8 CPUs",
    "http-container-cpus2-threads2": "classify-service, 2 CPUs, 2 threads",
    "http-container-cpus4-threads4": "classify-service, 4 CPUs, 4 threads",
    "http-container-cpus8-threads8": "classify-service, 8 CPUs, 8 threads",
    "http-container-cpus8-threads8-mem14g": "classify-service, 8 CPUs, 8 threads, 14 GB",
    "native-node-threads4": "classify-service on the host, 4 threads",
    "native-node-threads8": "classify-service on the host, 8 threads",
}


def cell(b):
    if not b:
        return "<td class='num'>—</td>"
    return f"<td class='num'>{b['median_s']:.2f}<span class='sub'>{b['per_min']:.0f}/min</span></td>"


def render(rows: list[dict], inputs: dict, out: Path) -> None:
    by = {r["label"]: r for r in rows}
    bucket_names = [n for n, _, _ in BUCKETS]
    counts = {n: sum(1 for i in inputs["inputs"] if i["bucket"] == n) for n in bucket_names}
    synth = {n: sum(1 for i in inputs["inputs"] if i["bucket"] == n and i["synthetic_docs"]) for n in bucket_names}

    def table_rows():
        s = ""
        for title, labels in GROUPS:
            present = [l for l in labels if l in by]
            if not present:
                continue
            s += f"<tr class='group'><th colspan='{len(bucket_names) + 3}'>{html.escape(title)}</th></tr>"
            for l in present:
                r = by[l]
                meds = [r["buckets"][n]["median_s"] for n in bucket_names if r["buckets"][n]]
                whole = sorted(meds)[len(meds) // 2] if meds else None
                par = r["parity"]
                if par is None:
                    par_s = "reference"
                    cls = ""
                else:
                    d = par["max_abs_delta"]
                    par_s = f"{d:.4f}"
                    cls = " bad" if d > 0.01 else ""
                gpu = " gpu" if "mps" in l else ""
                s += f"<tr class='cfg{gpu}'><th>{html.escape(PRETTY.get(l, l))}</th>"
                s += "".join(cell(r["buckets"][n]) for n in bucket_names)
                s += f"<td class='num'>{'—' if whole is None else f'{whole:.2f}'}<span class='sub'>{'' if whole is None else f'{60 / whole:.0f}/min'}</span></td>"
                s += f"<td class='num{cls}'>{par_s}</td></tr>"
        for title, secs in REFERENCE:
            s += f"<tr class='ref'><th>{html.escape(title)}</th>" + "".join(
                f"<td class='num'>{v:.2f}<span class='sub'>{60 / v:.0f}/min</span></td>" for v in secs) + "<td class='num'>—</td><td class='num'>—</td></tr>"
        return s

    # Bars: the 4k-8k bucket, the one that decides the tail.
    bar_rows = []
    for _, labels in GROUPS:
        for l in labels:
            if l in by and by[l]["buckets"]["4k-8k"]:
                bar_rows.append((PRETTY.get(l, l), by[l]["buckets"]["4k-8k"]["median_s"], "mps" in l))
    bmax = max(v for _, v, _ in bar_rows) if bar_rows else 1
    bars = "".join(
        f"<div class='bar'><span class='lbl'>{html.escape(n)}</span><span class='track'><span class='fill{' gpu' if g else ''}' style='width:{100 * v / bmax:.1f}%'></span></span><span class='val'>{v:.2f} s</span></div>"
        for n, v, g in bar_rows)

    page = f"""<title>Groundedness Head Latency</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
:root {{ --bg:#f7f6f2; --ink:#1f2328; --muted:#5d6570; --rule:#d9d5cc; --card:#ffffff; --accent:#b45309; --accent-soft:#fde8cc; --bad:#9f1239; --ref:#eef0f3; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --bg:#15171a; --ink:#e8e6e1; --muted:#9aa2ad; --rule:#33383f; --card:#1d2024; --accent:#f59e0b; --accent-soft:#3a2a10; --bad:#fb7185; --ref:#23272d; }} }}
:root[data-theme="dark"] {{ --bg:#15171a; --ink:#e8e6e1; --muted:#9aa2ad; --rule:#33383f; --card:#1d2024; --accent:#f59e0b; --accent-soft:#3a2a10; --bad:#fb7185; --ref:#23272d; }}
body {{ background:var(--bg); color:var(--ink); font-family:'IBM Plex Sans', system-ui, sans-serif; font-size:15px; line-height:1.5; margin:0; }}
main {{ max-width: 1080px; margin: 0 auto; padding: 40px 24px 64px; }}
h1 {{ font-size: 28px; font-weight:600; letter-spacing:-0.01em; margin:0 0 4px; text-wrap:balance; }}
h2 {{ font-size: 18px; font-weight:600; margin: 40px 0 10px; }}
p {{ max-width: 68ch; }}
.eyebrow {{ font-family:'IBM Plex Mono', monospace; font-size:12px; letter-spacing:0.08em; text-transform:uppercase; color:var(--muted); }}
.facts {{ display:grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:12px; margin: 20px 0 8px; }}
.fact {{ background:var(--card); border:1px solid var(--rule); padding:12px 14px; }}
.fact b {{ display:block; font-size:22px; font-weight:600; font-variant-numeric: tabular-nums; }}
.fact span {{ color:var(--muted); font-size:13px; }}
.scroll {{ overflow-x:auto; border:1px solid var(--rule); background:var(--card); }}
table {{ border-collapse:collapse; width:100%; font-variant-numeric: tabular-nums; }}
th, td {{ padding: 7px 10px; border-bottom:1px solid var(--rule); text-align:left; vertical-align:top; white-space:nowrap; }}
thead th {{ font-family:'IBM Plex Mono', monospace; font-size:12px; color:var(--muted); font-weight:500; letter-spacing:0.04em; }}
tr.group th {{ background:var(--bg); font-size:13px; color:var(--muted); font-weight:600; padding-top:12px; }}
tr.cfg th {{ font-weight:500; }}
tr.gpu td.num {{ background: var(--accent-soft); }}
tr.ref th, tr.ref td {{ background: var(--ref); color: var(--muted); }}
td.num {{ font-family:'IBM Plex Mono', monospace; font-size:13px; text-align:right; }}
td.num .sub {{ display:block; font-size:11px; color:var(--muted); }}
td.bad {{ color: var(--bad); font-weight:500; }}
.bars {{ background:var(--card); border:1px solid var(--rule); padding: 12px 14px; }}
.bar {{ display:grid; grid-template-columns: 220px 1fr 70px; gap:10px; align-items:center; margin: 4px 0; font-size:13px; }}
.bar .track {{ background:var(--bg); height:14px; border:1px solid var(--rule); display:block; }}
.bar .fill {{ display:block; height:100%; background: var(--muted); }}
.bar .fill.gpu {{ background: var(--accent); }}
.bar .val {{ font-family:'IBM Plex Mono', monospace; text-align:right; }}
ul {{ max-width: 72ch; padding-left: 20px; }}
li {{ margin: 6px 0; }}
code {{ font-family:'IBM Plex Mono', monospace; font-size: 13px; }}
.note {{ border-left: 3px solid var(--accent); padding: 8px 14px; background: var(--card); max-width: 72ch; }}
</style>
<main>
<div class="eyebrow">tessaryai/groundedness-token-v1 · ModernBERT-large, 395M · one response per forward pass</div>
<h1>Groundedness Head Latency</h1>
<p>Median seconds to score one response, by the encoded length of evidence plus answer, on this laptop. Same 24 real RAGTruth responses for every configuration; parity is the largest change in the response-level P(unsupported) against fp32 PyTorch.</p>
<div class="facts">
  <div class="fact"><b>Apple M4 Pro</b><span>8 performance + 4 efficiency cores, 24 GB unified</span></div>
  <div class="fact"><b>Docker VM</b><span>12 CPUs, 20 GB, no GPU (Metal cannot be passed through)</span></div>
  <div class="fact"><b>{sum(counts.values())} inputs</b><span>4 per bucket; {sum(synth.values())} of them extended with real documents from other rows to reach the long buckets</span></div>
  <div class="fact"><b>8,192 tokens</b><span>the head's window; longer evidence is cut from the end of the context</span></div>
</div>

<h2>The matrix</h2>
<div class="scroll"><table>
<thead><tr><th>configuration</th>{''.join(f'<th>{n}</th>' for n in bucket_names)}<th>whole set</th><th>parity (max Δp)</th></tr></thead>
<tbody>{table_rows()}</tbody>
</table></div>
<p class="eyebrow" style="margin-top:8px">seconds per response, throughput per minute beneath · amber rows run on the GPU · grey rows are external reference measurements on cloud hardware, different inputs</p>

<h2>The bucket that decides the tail: 4k to 8k tokens</h2>
<div class="bars">{bars}</div>

<h2>What the numbers say</h2>
<ul>
<li><b>The served container is the bottleneck, not the model.</b> As shipped, the container at 4 CPUs takes 2.2 s on a short response that the same ONNX engine scores in 0.25 s on the host: nine times slower. Three causes, each measured: ONNX Runtime spawns a thread per host core (12) inside a 2 or 4 CPU quota and spins against it, which pinning threads to the quota fixes (2.2 s → 0.9 s at 4 CPUs, 5.9 s → 1.7 s at 2); the Linux VM on Apple silicon costs about 2 times on its own (the same node code on the host: 0.42 s); and the node layer costs about 1.7 times over a plain ONNX session and far more at length (38 s versus 25 s at 8k).</li>
<li><b>8k inputs cannot be scored in the container as sized.</b> Every 8k response killed the process at 8 GB (OOM confirmed by Docker); at 14 GB they complete, in 63 s. ONNX Runtime materialises the full attention matrix; PyTorch's attention kernel does not, and scores the same input in 9 s on CPU at 3.5 GB. Production's 6 GB task has the same ceiling.</li>
<li><b>The GPU is real but modest on a Mac.</b> Metal fp32 is 2.3 to 3.2 times faster than 8 CPU threads at every length with bit-identical scores; fp16 adds 20 to 30 percent but moves scores by up to 0.034, enough to cross a band edge on a borderline response. The reference T4 row, on the same model class, is 35 times over 2 vCPU.</li>
<li><b>PyTorch is the faster CPU engine at length.</b> Equal to ONNX Runtime under 1k tokens, 2 times faster at 8k, and it is what the GPU path runs anyway.</li>
<li><b>Threads stop paying at 8.</b> The four efficiency cores add nothing; 4 to 8 threads is worth 1.6 times on long inputs and little under 1k, where fixed cost dominates.</li>
<li><b>Naive int8 is not shippable.</b> Dynamic quantisation gives 1.2 to 1.4 times on CPU but shifts scores by up to 0.25 (mean 0.07). It would need calibration and a re-run of the gate before it could replace fp32.</li>
<li><b>CoreML is out.</b> The execution provider can place 15 of the graph's 1,891 nodes and fails to build the model.</li>
<li><b>Length is the cost everywhere.</b> Every engine spends most of its time above 2k tokens. A 4k cap on evidence, or ranking passages and keeping the best, cuts the tail more than any engine change here.</li>
</ul>

<h2>Where this leaves the decision</h2>
<div class="note">
<p><b>Two fixes to the container are free and large:</b> pin ONNX Runtime's threads to the CPU quota (one session option; 2.5 times at 4 CPUs) and stop accepting inputs the memory cannot hold (cap evidence at 4k tokens, or size memory for 8k). Together they take a 4 CPU container from 2.2 s to under 1 s on ordinary responses and remove the crash.</p>
<p><b>The larger step is changing the engine, not adding a GPU.</b> A PyTorch serving process is 2 times faster than ONNX Runtime on CPU at length, does not blow up at 8k, and runs the same code on CUDA in production and on Metal on a developer's Mac, where it reaches 0.09 s on short responses and 3.5 s at 8k. On a Mac the GPU is only reachable from a host process, and the win over 8 CPU threads is about 3 times; on a Linux box with an NVIDIA card the win is the reference T4 row's 35 times. That engine is the right home for both.</p>
</div>

<h2>How it was run</h2>
<p><code>classifiers/groundedness/bench_latency.py</code> built the inputs once and ran each engine at batch 1 after two warm-up passes, sequentially with nothing else on the machine; the container runs use the shipped open image under <code>docker run --cpus N</code>. Results are the JSON files under <code>classifiers/artifacts/groundedness/bench/</code>; <code>bench_report.py</code> folds them and this page is <code>bench_page.py</code>.</p>
</main>
"""
    out.write_text(page)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench-dir", default="artifacts/groundedness/bench")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rows = fold(Path(a.bench_dir))
    inputs = json.loads((Path(a.bench_dir) / "inputs.json").read_text())
    render(rows, inputs, Path(a.out))
    print("wrote", a.out, "with", len(rows), "configs")


if __name__ == "__main__":
    main()
