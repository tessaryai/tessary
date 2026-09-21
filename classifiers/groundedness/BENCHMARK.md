# Measuring the groundedness head on your Mac

This measures how long the groundedness classifier takes to score one response on your machine:
CPU against the Apple GPU, by input length, with a parity check that the GPU gives the same answer.
It takes about 40 minutes of mostly unattended machine time and needs 10 GB of disk.

## Before you start

- An Apple silicon Mac (M1 or later). Intel Macs run everything on CPU and the GPU rows are skipped.
- macOS 14 or later, Xcode command line tools (`xcode-select --install`).
- [uv](https://docs.astral.sh/uv/) (`brew install uv`).
- The `tessary` repository on the branch that carries the harness (`classifiers/groundedness/bench_latency.py`,
  `bench_report.py`, `bench_page.py`).
- Docker Desktop only if you also want the container rows (step 6). Not needed for the GPU question.

Close other heavy applications while the runs go; the numbers are medians over four inputs per
length bucket and a browser with 40 tabs will show up in them.

## 1. Set up the Python environment

```bash
cd tessary/classifiers
uv sync --extra train
uv run --extra train python -c "import torch; print('mps:', torch.backends.mps.is_available())"
```

`mps: True` means the GPU path is available.

## 2. Get the model

The model is public: `tessaryai/groundedness-token-v1` (MIT). Download the checkpoint and its ONNX
export into the artifacts directory (about 3.2 GB):

```bash
uv run --extra train hf download tessaryai/groundedness-token-v1 \
  --local-dir artifacts/groundedness/groundedness-token-v1
export CK=artifacts/groundedness/groundedness-token-v1
```

## 3. Build the input set

The inputs are 24 real responses from the RAGTruth test split, four per length bucket, extended
with real documents from other rows where the corpus has nothing that long. The seed is fixed, so
you get the same 24 as everyone else. This downloads the dataset (about 50 MB) once.

```bash
uv run --extra train python -m groundedness.bench_latency \
  --checkpoint $CK --build-inputs artifacts/groundedness/bench/inputs.json
```

It prints the token count per input; the buckets are <512, 512-1k, 1k-2k, 2k-4k, 4k-8k and 8k(cut).

## 4. Run the four runs that answer the question

Each run scores the 24 inputs once after two warm-up passes and writes one JSON file. Run them one
at a time; nothing else on the machine.

```bash
B="uv run --extra train python -m groundedness.bench_latency --inputs artifacts/groundedness/bench/inputs.json"
R=artifacts/groundedness/bench

# CPU reference: PyTorch fp32 on 8 threads (use your performance-core count if it differs)
$B --engine torch-cpu --threads 8 --label torch-cpu-t8 --out $R/torch-cpu-t8.json

# The GPU, fp32 and fp16, each checked against the reference
$B --engine torch-mps --dtype fp32 --label torch-mps-fp32 --reference $R/torch-cpu-t8.json --out $R/torch-mps-fp32.json
$B --engine torch-mps --dtype fp16 --label torch-mps-fp16 --reference $R/torch-cpu-t8.json --out $R/torch-mps-fp16.json

# The engine the container ships, ONNX Runtime fp32, same threads
$B --engine ort-cpu --model $CK/onnx/model.onnx --threads 8 --label ort-cpu-fp32-t8 --reference $R/torch-cpu-t8.json --out $R/ort-cpu-fp32-t8.json
```

Each prints a line per input as it goes (`8k(cut) 8259 tok 3.480s p=0.9863`) and a summary line at
the end. The whole set is roughly 15 minutes on an M4 Pro; an M1 will take longer on the CPU rows.

Optional extra rows, same shape: `--engine torch-cpu --threads 2` and `--threads 4` show how the
CPU scales; `--engine ort-coreml` records whether CoreML can place the graph (on the M4 Pro it
cannot, and fails at session build; that failure is itself a result).

## 5. Make the table

```bash
uv run --extra train python -m groundedness.bench_report
```

This folds every JSON in the bench directory into one markdown table: median seconds per bucket,
throughput per minute, and the largest change in the response-level score against the CPU
reference. A parity of `0.0000` means the engine gives the same answer; fp16 is expected to show a
few hundredths.

## 6. Optional: the served container

This is the path a Docker install actually runs, and on a Mac it is CPU-only by construction
(Docker Desktop cannot pass the GPU through). Building the image downloads the model again inside
the build and takes 10 to 20 minutes.

```bash
cd tessary
docker compose -f docker-compose.dev.yml build classify
docker run -d --name bench-classify --cpus 4 --memory 8g -p 18091:8080 \
  -e PORT=8080 -e CLASSIFY_API_KEY=dev-classify-key tessary-classify-dev:latest
until curl -sf http://127.0.0.1:18091/healthz >/dev/null; do sleep 5; done
cd classifiers
$B --engine http --url http://127.0.0.1:18091 --label http-container-cpus4 --reference $R/torch-cpu-t8.json --out $R/http-container-cpus4.json
docker rm -f bench-classify
```

Expect the 8k inputs to fail with a connection reset at 8 GB: the container is killed for memory on
those. That is a known finding, not a mistake in your setup.

## 7. What to send back

- The table from step 5.
- Your chip and memory: `system_profiler SPHardwareDataType | grep -E "Chip|Memory"`, and
  `sw_vers -productVersion`.
- Anything that failed, with the last few lines it printed.

## What the M4 Pro (8 performance cores, 24 GB) produced, for comparison

| config | <512 | 512-1k | 1k-2k | 2k-4k | 4k-8k | 8k(cut) | parity |
|---|---|---|---|---|---|---|---|
| torch-cpu-t8 | 0.19s | 0.24s | 0.61s | 1.08s | 3.49s | 9.01s | reference |
| torch-mps-fp32 | 0.09s | 0.11s | 0.31s | 0.53s | 1.52s | 3.48s | 0.0000 |
| torch-mps-fp16 | 0.10s | 0.10s | 0.26s | 0.43s | 1.21s | 2.70s | 0.0343 |
| ort-cpu-fp32-t8 | 0.20s | 0.26s | 0.96s | 1.53s | 7.26s | 19.36s | 0.0000 |
| http-container-cpus4 (as shipped) | 2.18s | 2.45s | 6.04s | 10.11s | 26.99s | killed | 0.0000 |

The GPU rows are what we want a second machine to confirm: a 2 to 3 times gain over the best CPU
engine with identical scores, and a 10 to 25 times gain over the container as shipped.

## If something goes wrong

- `mps: False` on Apple silicon: the PyTorch wheel is the wrong one; `uv sync --extra train
  --reinstall-package torch`.
- The MPS run stops on the 8k inputs with an out-of-memory error: your Mac has 16 GB or less. Add
  `--max-tokens 4096` to skip that bucket and say so in what you send back.
- `Token indices sequence length is longer than...` while building inputs is a warning from the
  tokenizer, not an error.
- The container build fails on the model download: it is keyless by design; check your network, and
  confirm `curl -sI https://huggingface.co/tessaryai/groundedness-token-v1` answers 200.
