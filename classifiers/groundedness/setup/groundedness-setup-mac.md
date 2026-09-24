# Set up the Groundedness model on a Mac

Run Tessary's Groundedness model on this Mac for development, then point the Tessary stack on this
Mac at it.

Rules for the whole run:

- **Report progress.** One short line after each step, so the user can follow along.
- **Stop and ask on any blocker or decision.** A failed check, a download that won't finish, a port
  that is taken. Never guess, and never substitute a different install method.
- **Never install anything silently.** If a tool is missing, tell the user the install command.

`serve.py SHA-256: 70128aafdac8c44bff4dd17b8639cba2cf89d6ab9f7c5708ac906125cb62a71c`

## Which version

Take `<ref>` from the URL you were given for this file: the path segment after `/blob/`, for
example `v1.3.0` or `main`. Every download below uses that `<ref>`, so the model server matches the
Tessary version that linked here.

## 1. What this does

It runs the Groundedness model on this Mac's GPU, as a plain process outside Docker. Tessary's
containers reach it at `http://host.docker.internal:18080` and score answers while it runs. When the
process stops, Tessary stops scoring and picks up where it left off once the model is back.

## 2. Check this Mac

```bash
uname -m                 # must print arm64 (Apple silicon)
sysctl -n hw.memsize     # must be 17179869184 (16 GB) or more
df -g ~                  # the Avail column must be 6 or more
command -v uv            # must print a path
docker compose -p tessary ps
```

- If `uv` is missing, tell the user to install it with `brew install uv` or
  `curl -LsSf https://astral.sh/uv/install.sh | sh`, and wait.
- Tessary's stack must be running on this Mac, with `backend` healthy.
- Find the stack's `.env`. It lives in the directory the stack was started from:

  ```bash
  docker inspect "$(docker compose -p tessary ps -q backend)" \
    --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}  {{index .Config.Labels "com.docker.compose.project.config_files"}}'
  ```

  The first value is that directory; the second is the compose files it was started with. Keep
  both for step 9.

If any check fails, tell the user which one and stop.

## 3. Existing install

If `~/.tessary/groundedness/key` exists, and either `curl -s localhost:18080/healthz` answers or the
PID in `~/.tessary/groundedness/serve.pid` is running, the model is already set up. Go to
[Restart](#restart) instead of setting it up again.

## 4. Confirm

Tell the user, then ask whether to continue:

> This downloads the Groundedness model (about 1.6 GB) and, on first run, PyTorch, then starts the
> model on this Mac's GPU. It runs as a background process until you stop it or restart the Mac.

## 5. Get serve.py

```bash
mkdir -p ~/.tessary/groundedness
curl -fsSL -o ~/.tessary/groundedness/serve.py \
  https://raw.githubusercontent.com/tessaryai/tessary/<ref>/classifiers/groundedness/serve.py
shasum -a 256 ~/.tessary/groundedness/serve.py
```

The checksum must equal the `serve.py SHA-256` line at the top of this file. If it differs, delete
the download, tell the user, and stop.

## 6. Create the key

```bash
( umask 077; openssl rand -hex 24 > ~/.tessary/groundedness/key )
```

## 7. Start the model

```bash
cd ~/.tessary/groundedness
CLASSIFY_API_KEY="$(cat key)" nohup uv run serve.py --host 127.0.0.1 --port 18080 \
  > serve.log 2>&1 &
echo $! > serve.pid
```

`serve.py` listens on this Mac only. Docker Desktop routes `host.docker.internal` to it, and every
request must carry the key.

Wait until the model answers:

```bash
curl -s localhost:18080/healthz
```

It is ready when the answer lists `"groundedness"` under `heads`. The first start installs PyTorch
and downloads the model, so allow up to 15 minutes. Poll every 15 seconds and show the user the last
line of `serve.log` while you wait. If the process exits, show the end of `serve.log` and stop.

## 8. Point Tessary at the model

In the `.env` from step 2, add these three lines, replacing any existing line for the same key:

```bash
TESSARY_OBSERVER_ENCODER_URL=http://host.docker.internal:18080
TESSARY_OBSERVER_ENCODER_API_KEY=<the contents of ~/.tessary/groundedness/key>
TESSARY_GROUNDEDNESS_CLASSIFIER_MODE=dev
```

Show the user the diff. Leave every other line as it was.

## 9. Restart Tessary

Recreate the backend so it reads the new lines. Run from the directory in step 2:

- **Started from the registry** (the compose files are under a cache directory):
  `docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y`
- **Started from a checkout with `docker-compose.yml`:** `docker compose up -d`
- **The repository's dev stack** (`docker-compose.dev.yml`, started with `task dev`): `task rb`

Wait until `docker compose -p tessary ps` shows `backend` healthy.

## 10. Done

Tell the user:

> Open Classifiers. Groundedness turns on by itself once Tessary can reach the model.

## Restart

Use this when the model was set up before and has stopped, for example after the Mac restarted.

1. If `curl -s localhost:18080/healthz` lists `"groundedness"` under `heads`, the model is running.
   Say so and stop.
2. Otherwise start it again with the saved key, exactly as in [step 7](#7-start-the-model). Never
   create a new key when `~/.tessary/groundedness/key` exists: Tessary already holds that one.
3. If `serve.py` is missing, get it again as in [step 5](#5-get-servepy).
4. If the stack's `.env` lacks any of the three lines from [step 8](#8-point-tessary-at-the-model),
   add them and restart Tessary as in [step 9](#9-restart-tessary).

Tessary scores what arrived while the model was down within a few minutes of it answering.

## Stop

```bash
kill "$(cat ~/.tessary/groundedness/serve.pid)"
```

Tessary shows "No scores since" the last time it scored, and resumes from where it stopped once the
model is back. Turning Groundedness off in Tessary does not stop this process.

## Options

Pass these to `serve.py` in step 7.

| Flag | Default | When to change it |
| --- | --- | --- |
| `--dtype` | `fp32` | `fp16` is 20 to 30 percent faster and halves memory, and moves scores by up to 0.03. Use it on a 16 GB Mac that runs short of memory. |
| `--port` | `18080` | Any free port. Change the URL in `.env` to match. |
| `--max-inflight` | `1` | Requests scored at once. One GPU scores one at a time, so leave it. |

## Troubleshooting

| Problem | Action |
| --- | --- |
| `uv run` fails while installing packages | Show the end of `serve.log`. A network or proxy error needs the user to fix the connection. |
| `serve.log` shows `on cpu` instead of `on mps` | The GPU was not found. Check `uname -m` is `arm64` and that uv's Python is arm64 (`uv run python -c "import platform; print(platform.machine())"`). |
| The process exits with an out-of-memory error | Close other large apps, or restart it with `--dtype fp16`. |
| Port 18080 is taken | Tell the user what holds it (`lsof -i :18080`). Use `--port` with a free port and change the URL in `.env`. |
| Groundedness never starts scoring | Check what the backend saw: `docker compose -p tessary logs backend \| grep "encoder "`. An `unreachable` reason means the container can't reach the Mac: check the URL is `http://host.docker.internal:18080` and the model is running. |
| The backend log shows `401` on sweeps | The key in `.env` differs from `~/.tessary/groundedness/key`. Copy the file's contents into `.env` and restart Tessary. |
| Tessary shows "No scores since" | The model stopped. Follow [Restart](#restart). |
