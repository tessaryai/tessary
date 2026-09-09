# Documentation

The map of everything in `devdocs/`. This tree carries the platform's internals — how the system
works and why it is shaped the way it is. Partner- and operator-facing documentation is published
from [`../docs/`](../docs/index.mdx) instead, and product terminology is defined once in
[`../handbook/product-glossary.md`](../handbook/product-glossary.md).

New to the project? Start with [`reference/architecture.md`](./reference/architecture.md) for how it
is built, then [`modules.md`](./modules.md) for the reactor layering.

Docs are organised by *type* (a [Diátaxis](https://diataxis.fr)-style split), so you can tell
at a glance whether a page teaches, explains, instructs, or just states facts.

## Start here
| Doc | What it's for |
|---|---|
| [reference/architecture.md](./reference/architecture.md) | How it is built. |
| [modules.md](./modules.md) | How the eleven modules layer. |

> **Looking for the product thesis?** It is not in this repo, by design. It lives in Tessary's internal
> *Agent Reliability* document.
> `docs/` carries how the system works; that document carries what we are building and why. The working
> summary an engineer needs mid-change is in [`../AGENTS.md`](../AGENTS.md) § *Strategic context*.

## Reference — *what it is* (stable, factual)
| Doc | What it's for |
|---|---|
| [reference/principles.md](./reference/principles.md) | The platform's standing principles and constraints — the rules the system is built to, grouped by area. |
| [reference/architecture.md](./reference/architecture.md) | Backend layout, naming conventions, SPI seams, feature slices, and the per-package module inventory. |
| [modules.md](./modules.md) | The eleven-module Maven reactor: the layering, why each package sits where it does, and the invariants the poms enforce. |
| [reference/data-model.md](./reference/data-model.md) | The full Postgres schema as a UML/ER diagram + table inventory. |
| [reference/prompt-craft.md](./reference/prompt-craft.md) | Where every prompt the platform sends a model lives, what belongs in markdown vs code, and how to change one without silently changing a lane's behaviour. |
| [reference/classifier-extension-interface.md](./reference/classifier-extension-interface.md) | The public classifier extension interface: the six ports a classifier attaches through, auto-configuration discovery, packaging, versioning, failure isolation, and where an extension may run. |
| [reference/trace-schema.md](./reference/trace-schema.md) | How heterogeneous traces normalise into the canonical substrate. |
| [reference/ingestion-contract/](./reference/ingestion-contract/README.md) | **The wire contract**: every field producers send that the platform consumes (single table). Doc-first — change it before changing producer/ingest code. |
| [`frontend/DESIGN_SYSTEM.md`](../frontend/DESIGN_SYSTEM.md) | Frontend design language: tokens, components, data-viz vocabulary, density modes. Lives beside `frontend/tokens.css`, which it documents, rather than being mirrored here. |
| [voice-and-tone.md](../handbook/voice-and-tone.md) | How user-visible copy is written: UI strings, errors, Slack, CLI, docs prose. Hard rules, banned words, worked examples. |
| [`../handbook/product-glossary.md`](../handbook/product-glossary.md) | Domain vocabulary (call site, failure mode, classifier, finding, case, …). Authoritative for terminology across code, UI and docs; `reference/data-model.md` maps each term onto its table. |
| [reference/auth-and-mcp.md](./reference/auth-and-mcp.md) | WorkOS cookie session, device-link, API keys / MCP tokens, and the hosted `/mcp` surface. |
| [reference/test-suite.md](./reference/test-suite.md) | Backend test shape (unit vs `@SpringBootTest`), cost model, contract/OpenAPI gates, and why slices are packages. |
| [reference/config-keys.md](./reference/config-keys.md) | Every config prefix → `@ConfigurationProperties` class, the `@Value` exceptions, and the yaml-declared env vars. |
| [reference/telemetry-naming.md](./reference/telemetry-naming.md) | Platform-emitted OTel / Langfuse span & trace names: kebab product verbs, metadata keys, Alloy filter, inventory. |
| [reference/telemetry-contract.md](./reference/telemetry-contract.md) | The home.tessary.ai ping shape, license-check endpoint, opt-out env var, and versioning rules — the heartbeat client and the `telemetry` package it lives in. |
| [reference/media-contract.md](./reference/media-contract.md) | The media contract: supported modalities (images and PDFs), storage, and export. |

## Concepts — *understand why/how* (subsystems)
| Doc | What it's for |
|---|---|
| [concepts/substrate-model.md](./concepts/substrate-model.md) | Why the substrate is shaped span/trace/session — producer ids, span identity, lock ordering, the settle protocol. Implemented and live since 2026-08-14. |
| [concepts/deviation-math.md](./concepts/deviation-math.md) | The arithmetic behind the three launch classifiers: W1 on log sketches, the derived false-alarm bar, the Bernoulli CUSUM, and which numbers are measured versus assumed. |
| [concepts/alerting.md](./concepts/alerting.md) | How a case reaches a human: the `case_opened` rule, why quiet hours defer rather than drop, and what the message carries. |
| [concepts/pii-redaction.md](./concepts/pii-redaction.md) | Layered PII redaction: write-path guard, client-side option, rules playground. |

## Guides — *how do I* (task-oriented runbooks)

> Partner- and operator-facing setup lives in the published docs, not here:
> [`../docs/self-hosting/setup.mdx`](../docs/self-hosting/setup.mdx) is the connect-your-traces
> walkthrough, and it is the copy the `check:selfhost:quickstart` gate rehearses.

| Doc | What it's for |
|---|---|
| [guides/ingest-runbook.md](./guides/ingest-runbook.md) | Operator-side: ingest's stated objectives, the dashboard, and how to diagnose it without having built it. |
| [guides/local-dev.md](./guides/local-dev.md) | Run the stack locally: bare metal, Docker dev (tmux), Docker prod. |
| [guides/provider-keys.md](./guides/provider-keys.md) | Bring your own model provider keys in the open build: org-scoped setup, the fresh empty state, and why every provider now requires its own key with no ambient fallback. |
| [guides/common-tasks.md](./guides/common-tasks.md) | Cross-stack recipes: schema change, new classifier, contract-upgrade pointer. |
| [guides/upgrade-contract.md](./guides/upgrade-contract.md) | Re-vendor a new version of the eval-author contract. |
| [guides/storage-migrations.md](./guides/storage-migrations.md) | The storage SPIs and the triggers for switching backends. |

---

Imperative agent-facing conventions live outside `docs/`, next to the code they govern:
[`../AGENTS.md`](../AGENTS.md) (repo-wide + the documentation-placement policy),
[`../backend/AGENTS.md`](../backend/AGENTS.md), [`../frontend/AGENTS.md`](../frontend/AGENTS.md).

---

**Editing docs?** Follow the placement policy in [`../AGENTS.md`](../AGENTS.md) §
*Documentation policy*. Keep each page in its category. Standing rules and constraints live in
[reference/principles.md](./reference/principles.md), not prose buried in a guide. When a migration changes a table or
relationship, update [reference/data-model.md](./reference/data-model.md) in the same PR (the
agent loop enforces this — see [`guides/common-tasks.md`](./guides/common-tasks.md) → "Schema change").
