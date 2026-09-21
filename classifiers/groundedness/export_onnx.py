# SPDX-License-Identifier: Apache-2.0
"""Export a candidate NLI checkpoint to ONNX, the format classify-service actually serves.

WHY THIS IS ON THE CRITICAL PATH AND NOT A SHIPPING CHORE. Two decisions depend on it:

1. COST. `cost.py` measures torch, because that is all a Hugging Face checkpoint gives you. The
   service runs ONNX Runtime, which is materially faster on CPU, and document-aligned tiling is
   only affordable if that gap is large. A torch number can say "concerning"; only an ONNX number
   can say "yes" or "no".
2. TRANSFER. Every recall figure in this module was measured in torch. If the export does not score
   the same, those figures describe a model nobody serves — and we have already seen that gap be
   large: facebook/bart-large-mnli in torch versus Xenova's ONNX conversion of the same checkpoint
   differed by up to 0.297 on identical inputs.

So the export is followed by `parity_onnx.py`, which is what makes the numbers transferable rather
than merely encouraging.

INT8 IS OFFERED AND NOT ASSUMED. Dynamic quantization is the other large CPU speedup available, and
it changes the scores — `classify.js` already documents that q8 activation scales depend on batch
composition. It is exported under its own directory so parity and cost can judge it on its own
merits instead of it arriving as a free win.

Run:
    uv run --extra train python -m groundedness.export_onnx
    uv run --extra train python -m groundedness.export_onnx --int8
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

#: `artifacts/` is gitignored: an export is reproducible from this script and a pinned revision, so
#: the weights are never committed.
ARTIFACTS = Path("artifacts/groundedness")

#: MIT, and trained on MNLI alone. The FEVER-trained alternative scores better but was trained on
#: `facebook/anli` (CC BY-NC 4.0), which is not a dependency a commercial product should take on
#: the strength of a third party's MIT tag. See the module README.
DEFAULT_CHECKPOINT = "microsoft/deberta-large-mnli"


def export(checkpoint: str, out: Path, int8: bool = False) -> Path:
    from optimum.onnxruntime import ORTModelForSequenceClassification
    from transformers import AutoTokenizer

    out.mkdir(parents=True, exist_ok=True)
    print(f"exporting {checkpoint} -> {out}")
    model = ORTModelForSequenceClassification.from_pretrained(checkpoint, export=True)
    model.save_pretrained(out)
    AutoTokenizer.from_pretrained(checkpoint).save_pretrained(out)

    if int8:
        from optimum.onnxruntime import ORTQuantizer
        from optimum.onnxruntime.configuration import AutoQuantizationConfig

        q_out = Path(f"{out}-int8")
        q_out.mkdir(parents=True, exist_ok=True)
        print(f"quantizing (dynamic int8) -> {q_out}")
        quantizer = ORTQuantizer.from_pretrained(out)
        # arm64: the service runs ARM64/Graviton in production and this dev box is arm64 too, so
        # the quantization config matches the serving architecture rather than defaulting to avx.
        quantizer.quantize(
            save_dir=q_out,
            quantization_config=AutoQuantizationConfig.arm64(is_static=False, per_channel=False),
        )
        AutoTokenizer.from_pretrained(checkpoint).save_pretrained(q_out)
        # The quantizer leaves the fp32 graph behind in the output dir; drop it so a loader cannot
        # pick the wrong one and report quantized numbers for an fp32 graph.
        for stray in q_out.glob("model.onnx"):
            if (q_out / "model_quantized.onnx").exists():
                stray.unlink()
        for f in sorted(q_out.iterdir()):
            print(f"  {f.name}  {f.stat().st_size / 1e6:.0f} MB" if f.is_file() else f"  {f.name}/")

    print("\nexported files:")
    for f in sorted(out.iterdir()):
        if f.is_file():
            print(f"  {f.name}  {f.stat().st_size / 1e6:.0f} MB")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoint", default=DEFAULT_CHECKPOINT)
    ap.add_argument("--out", default=None)
    ap.add_argument("--int8", action="store_true", help="also export a dynamically quantized graph")
    ap.add_argument("--clean", action="store_true", help="remove an existing export first")
    args = ap.parse_args()

    out = Path(args.out) if args.out else ARTIFACTS / args.checkpoint.split("/")[-1]
    if args.clean and out.exists():
        shutil.rmtree(out)
    export(args.checkpoint, out, args.int8)
    print("\nnext: uv run --extra train python -m groundedness.parity_onnx")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
