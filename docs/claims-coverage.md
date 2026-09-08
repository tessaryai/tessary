# Published-docs claim coverage

Epic 7 clause 6 (#1192) requires that every command block, URL and observable claim across the six
published pages is either **executed** by clause 2's rehearsal or **enumerated here as unexecuted,
with a stated reason**. One row per page. This file is the enumeration half.

Nothing on this page is a promise that a claim is *wrong*. It is a promise that we know which claims
a machine has checked and which ones only a person has read.

## Status of the executing instrument

`scripts/check-selfhost-quickstart.sh` (#1191) extracts the executable step list from `setup.mdx`
(every `<Step>`'s bash fences and every `<Check>` body, in order) and runs it against a clean-room
export on the compose defaults; the "unexecuted" column below is therefore residual. `README.md` is a
published page under the same rule, and `scripts/check-readme-front-door.sh` (#1196) holds its
command blocks to that extracted set and its auth, telemetry and link claims to the tree. Two
static gates also run over these pages and are named per row where they apply:

- `scripts/check-docs-links.sh` — cross-links resolve, backticked doc names exist, and every
  `docs/docs.json` nav entry names a real page. Reads `.md` and `.mdx` since #1192.
- `scripts/check-open-boundary.sh` rule 6 — no published page resolves a path the public export
  deletes. Reads `.md` and `.mdx` since #1192.

## One row per page

| Page | What is unexecuted | Why |
| --- | --- | --- |
| `setup.md` (repository root) | Nothing, once the compose-artifact rehearsal runs. Its four commands ARE the rehearsal's own path: `docker info` / `docker compose version`, the `oci://` install, `docker compose -p tessary ps` to healthy, and the `curl` that must return `200`. `scripts/check-compose-artifact.sh` additionally holds the install command byte-for-byte equal to the string the marketing site publishes. | The one unexecuted claim left is the version floor ("Compose v2.34 or newer"), which is a statement about the reader's toolchain rather than about this stack, and which no boot on a modern runner can falsify. The troubleshooting table's actions are diagnostic instructions, not claims about behaviour. |
| `docs/index.mdx` | Every claim. The page is a nav hub: four cross-links and a one-paragraph positioning statement, no commands and no observables. | The cross-links **are** checked, by `check-docs-links.sh` check 1 under Mintlify route semantics. What is left is positioning prose, which no rehearsal can execute — it is judged, not run. |
| `docs/self-hosting/setup.mdx` | The repair-prompt observable (spans arriving with none tagged replaces the gate with the narrower prompt and a live count), the milestone rungs past `fitting` (`watching`, `finding`, `case`), the GitHub connection, and the `SITE_DOMAIN` refusal. **Executed** by `check-selfhost-quickstart.sh`: every command block on the page (`docker compose pull`, `docker compose up -d`, the `.env` block of `cp` plus `printf` of two `openssl rand` values, the second `up -d`, and the `emit-span.js` command), the two Checks as worded (`docker compose ps` healthy set and the init container exited 0; the placeholder warning gone from the backend log), the sign-up screen loading at `http://localhost`, sign-up creating the org and `Default` project, the gate's mint being a WRITE key, and the ladder reading `not_connected` → `listening` → `fitting` with a mint-only control staying at `listening`. | This is the page clause 2 exists to execute, and the harness now does; what remains unexecuted is what a first successful run never reaches. The claims listed as executed were, before #1191, verified by reading the source (#1227 shifted the line numbers, #1230 changed the recipe): `docker-compose.yml`'s backend service, `AuthController.java`'s `entryPageUrl` sending a first-run visitor to `/signup` rather than `/login` (with `Login.tsx`'s `firstRun` branch covering a typed `/login`), `AuthController.java:234` with `TenantServiceTest.ensureDefaultOrg_guaranteesExactlyOneDefaultProject`, `ConnectGate.tsx` (the gate itself, `frontend/src/views/onboarding/`) auto-issuing via `SourceConnect.tsx`'s exported `useIngestToken`, and `SubstrateController.java`'s `has_tagged_span` field driving the gate's own poll. The warn-versus-refuse boundary the Secure section describes is executed by `PlaceholderSecretGuardTest` (the refuse half) and by the rehearsal's first-boot warning assertion (the warn half). `docker compose pull` is the one block the rehearsal cannot yet run verbatim: it depends on #941 publishing every image, and until then the rehearsal's `--build` deviation stands in and the run is recorded as built. |
| `README.md` | The contributor dev loop (`task dev`, `task prod:*`, the bare-metal recipe), the architecture table, the product prose. | The README's self-host command block is required to be one of `setup.mdx`'s extracted blocks, so the rehearsal executes it by executing the page; `check-readme-front-door.sh` asserts the block sits above any `task dev` instruction, that the telemetry key the README prints resolves in `.env.example` and the backend's own property binding, that the heartbeat host it names is the backend's, that the auth claim names email and password and pairs every WorkOS mention with the hosted product, and that the page links the published docs. The contributor loop is the internal tree's, not a published-page claim. |
| `docs/self-hosting/custom-domain.mdx` | The DNS record itself, the `acme` mode's live Let's Encrypt issuance, and the WorkOS dashboard registration. | The self-host boot check's `--domain` leg (`scripts/check-open-boot-selfhost.sh --domain`) executes the rest: `SITE_DOMAIN` alone derives every origin (the `public origin` log line), the hostname answers on `HTTPS_PORT` with the mounted `owncert` pair and no ACME attempt, the two refusals name their keys, and `upstream` honours a forwarded client address from `TRUSTED_PROXIES` only. Live issuance needs a public DNS record the check cannot own; the mode is validated by `scripts/check-caddy.sh` rendering it. |
| `docs/self-hosting/configuration.mdx` | Every variable name, description and default in nine tables. | A rehearsal executes the documented *path*; it sets the two keys `setup.mdx`'s Secure section names and never touches the rest. Asserting all ~60 defaults would need a separate instrument that diffs the tables against `.env.example` and the Spring property classes, which no clause asks for (#1190 is building that check). The two variables on the setup path are covered by the `setup.mdx` row. Since #1230 the Postgres, secret-key, cookie-key and frontend-URL rows state a shipped default rather than "required, ships blank", so a drift here is a wrong default rather than a missing one. |
| `docs/self-hosting/deployment.mdx` | Every claim: the single-node topology, the service/state inventory, the network posture, and TLS via Caddy's ACME client. | Deployment describes a *hosted* posture — a public hostname, a real certificate, an EBS-backed volume. The gate ends at connected traces on a local stack, so nothing here is on the executed path. The port and topology claims were re-derived by hand from `docker-compose.yml` in #1192. |
| `docs/self-hosting/upgrading.mdx` | Every claim: the backup-first ordering, Liquibase migration behaviour on boot, the upgrade sequence, and TLS certificate-state preservation. | **Deliberately excluded from clause 6's executed set.** Upgrade rehearsal moves no clause of a gate that ends at connected traces (tessary-paid/OPEN-CORE.md, epic 7, "what this gate does not prove"). Executing it would need two published image versions and a populated database, which is a different instrument from a first-boot rehearsal. |
| `docs/self-hosting/troubleshooting.mdx` | Every claim. Eight symptom/cause/resolution sections, including the `pg_dump`/`pg_restore` backup procedure and the encoder-classifier dead end. | Troubleshooting documents *failure* states; a rehearsal that succeeds reaches none of them, and a harness that manufactured each one would be testing the failures rather than the documentation. **The zero-egress section (`## Unexpected outbound network calls`) is clause 9's**, not this clause's, and defers to it entirely. The backup procedure is unexecuted and stays that way for the same reason `upgrading.mdx` does. |

## Non-vacuity of the two gates

Both were planted against and both reverted, in #1192. Each turned exactly one gate red:

| Planted fault | `check-docs-links.sh` | `check-open-boundary.sh` |
| --- | --- | --- |
| A `docs.json` nav entry naming `self-hosting/backup-and-restore`, a page that does not exist | **red** (check 3) | green |
| `docs/self-hosting/troubleshooting.mdx` links `../../classifiers/README.md`, a path the export deletes | green (the file exists *here*) | **red** (rule 6) |

The second row is the split-brain case rule 6 exists for: the link resolves in this repo and would
404 in the public export, so only the boundary gate can see it.
