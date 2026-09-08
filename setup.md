# Set up Tessary

Install Tessary on the user's machine, start it, verify the frontend is running, and give the user the URL.

Two rules for the whole run:

- **Report progress.** One short line after each step, so the user can follow along.
- **Stop and ask on any blocker or decision.** A missing prerequisite, a service that will not come up, a port that is taken. Never guess, and never substitute a different install method.

## 1. Check prerequisites

```bash
docker info
docker compose version
```

Docker must be running, and Docker Compose must be v2.34 or newer.

## 2. Start Tessary

```bash
docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
```

## 3. Verify Tessary is running

```bash
docker compose -p tessary ps
```

Wait until `postgres`, `backend`, `frontend`, and `sandbox-runner` are healthy, and `sandbox-runner-work-init` has exited successfully.

Then verify the frontend:

```bash
curl -fsS -o /dev/null -w '%{http_code}\n' http://localhost/
```

Continue only when it returns `200`.

## 4. Hand over

Tell the user:

> Tessary is running at **http://localhost**. Open it to continue setup.

Stop here. The Tessary setup flow handles everything else.

## Troubleshooting

If setup fails, diagnose the problem before proceeding:

| Problem | Action |
| --- | --- |
| Docker is unavailable | Stop and tell the user Docker needs to be installed or started. |
| Docker Compose is older than v2.34 | Stop and tell the user to upgrade Docker Compose. |
| The stack does not become healthy | Run `docker compose -p tessary ps` and inspect the unhealthy service's logs. |
| `backend` does not become healthy | Run `docker compose -p tessary logs backend`. |
| `frontend` remains `created` | Check the backend first. The frontend waits for it. |
| `sandbox-runner` fails with a Docker socket permission error | Tell the user Docker socket permissions need to be fixed before continuing. |
| Port 80 or 443 is already in use | Stop and tell the user which port is conflicting. |
| Image pull fails | Run `docker compose -f oci://docker.io/tessaryai/tessary:compose pull` to surface the underlying error. |
| Frontend does not return `200` | Treat setup as incomplete and diagnose the frontend before handing over the URL. |
