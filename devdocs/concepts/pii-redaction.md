# PII redaction (server-side guard + client-side redaction + playground)

> Trust & compliance.

Privacy is gating for buyers handling user data. Redaction strips matched PII from span content on the
OTLP ingest path, before it lands in the platform's agent-native substrate. Coverage is **layered**,
and redaction is a **deliberate, declared transform** — not a silent clip of telemetry (see the
never-truncate rule in [`../reference/principles.md`](../reference/principles.md) § *Evaluation*).

It is pattern matching, and it runs on one of the four paths by which customer content enters the
platform. All four are stated, with evidence, under
[*The redaction boundary*](#the-redaction-boundary) below. Read that section before relying on this
one as a control.

## The three layers

1. **Server-side write-path guard (the backstop on the span-ingest path).**
   Redaction runs on the `SubstrateWriter` **drainer**. Immediately before a `RawEntry` batch is
   written, a project's *enabled* redaction rules are applied to its free-text, PII-bearing fields
   (`input`, `output`, the `gen_ai.input/output.messages` payloads, and string values in metadata).
   Structural fields (ids, timestamps, `name`, `model`, `source_url`, kind) are untouched. So no
   unredacted PII reaches the substrate **via that writer** — and because redaction runs before the
   previews are cut, `trace.input_preview`/`output_preview` are redacted by construction rather than
   separately.
   - The drainer is not, however, a chokepoint every ingest source tees through. `SubstrateWriter.enqueue`
     has exactly one production caller, `OtlpIngestService`. Three other paths write customer content
     without passing through it — see *The redaction boundary* below, which is the section to read
     before treating redaction as a control.
   - Master switch: `evals.redaction.enabled` (default on; the kill switch).
   - Engine: `redaction/RedactionEngine` (pure regex apply) + `redaction/RedactionService`
     (per-project compiled-rule cache, applied on the boundary).
   - A rule whose regex fails to compile is skipped at compile time — never applied, never fatal to a
     write.

2. **Client-side redaction (optional, before transmit).**
   A producer may apply its own regex rules to span attribute values *before* they leave the process, so
   PII is never transmitted — in addition to any producer-side switch that suppresses auto-instrumented
   prompt/completion bodies wholesale.

3. **Rules playground (authoring + testing).**
   Settings → PII redaction. Per-project CRUD over rules plus a live preview that applies a single
   (possibly unsaved) pattern — or the whole active rule set — to sample text and reports the redacted
   result and match count. The rules authored here are exactly the rules the server-side guard applies.

## The redaction boundary

A control whose real edge is narrower than a reader would infer is worse than no control, because it
is relied on. This section is the negative statement: **where redaction runs, and what it does not
catch where it does run.** Everything here is checkable against the file and line cited beside it.

On a self-hosted deployment, every gap this section documents stays inside the operator's own
infrastructure: a redaction miss on any of the four paths below is unredacted content sitting in
that operator's own Postgres, never a leak that reaches Tessary — there is no path from a
self-hosted install's data back to us (see the
[Telemetry](../../docs/self-hosting/configuration.mdx#telemetry) section of the self-hosting docs
for the one narrow exception, an anonymous heartbeat that never carries content).

### Coverage, per ingestion path

| Ingestion path | Entry point | Does redaction run? |
|---|---|---|
| **OTLP spans** (HTTP + gRPC) | `ingest/otlp/OtlpIngestService.java:161` → `SubstrateWriter.enqueue` → `SubstrateWriter.java:184` `redactBatch` | **Yes** — the drainer applies the project's enabled rules before the write |
| **Bundle / manual import** (the `.tessary/` bundle from the evals plugin) | `pipeline/ImportController.java` → `PipelineService` | **No.** The controller does not reference redaction, and it does not go through `SubstrateWriter`. Whatever free text the bundle carries — grader and SOP prose, examples, call-site descriptions — is stored as authored. It also carries no capability gate |
| **Slack mention relay** | `tessary-paid/slack-service/src/slack_service/platform_api.py:55-58` posts the raw message `text` to the platform's mention endpoint | **No** |
| **Sandbox-authored triage / RCA output** | `rca/E2bRcaSandbox.java`, `classifier/finding/E2bTriageSandbox.java` | **No.** Neither file references `redact`. The report text an agent writes back can quote the evidence it was given, and it is stored as written |

Only the first row passes through the guard. `SubstrateWriter.enqueue` has exactly one production
caller. A Controller who needs redaction on the other three should redact client-side, before
transmission (layer 2).

### What redaction does not catch, on the path where it does run

The defaults are prefix- and format-anchored on purpose — a looser rule redacts half of every payload
(see the credit-card correction below, which rewrote a retrieval score). Precision is bought with
recall, and this is the recall it costs.

| Not caught | Why, in the code |
|---|---|
| **A US SSN written without dashes** — `123 45 6789`, `123456789` | `BuiltInRedactionRules.java:50` requires the hyphens: `\b\d{3}-\d{2}-\d{4}\b` |
| **Any personal name** | There is no NER model, no gazetteer and no rule of that shape anywhere in the default set. A name in a prompt or a completion is stored verbatim |
| **Non-US phone formats** | `:60-63` is anchored on a 3-3-4 grouping with an optional country code. Most non-NANP formats — a UK 11-digit, an Indian 10-digit, a European grouping — do not match |
| **IPv6 addresses** | `:64` matches IPv4 dotted-quad only |
| **The body of a PEM private key** | `:121` redacts the `-----BEGIN … PRIVATE KEY-----` framing line only. That is deliberate (redacting the body means an unbounded `.` across newlines on every ingested body) and it means the base64 key material stays in the span |
| **Stripe keys** (`sk_live_…`, `rk_live_…`) | Not in the default set. The `sk-` credential rule is anchored on the hyphen form, which Stripe does not use |
| **`span.name`, `model`, `source_url`** | `RedactionService.java:104,105,108` copy these through untouched (lines 106-107 in between are the `redact(input)`/`redact(output)` calls) — only `input`, `output`, the two `gen_ai` message payloads and metadata strings are passed to `redact()`. A URL with an email or a token in its query string is not scanned |
| **Any value that arrived as a JSON number or boolean** | `RedactionEngine.java:322-324`: "Numbers, booleans and nulls are returned untouched… it is not that a rule is unlikely to match them, it is that no rule is ever offered them." An SSN or a card number serialized unquoted is invisible to every rule |
| **The interior of a long inline payload run** (base64 media, embedded blobs) | `RedactionEngine.java:170-180` skips the interior of a contiguous payload-character run once it exceeds `EDGE_MARGIN`×2 + `MIN_RUN` (512 + 512 + 512 ≈ 1.5 KB). The first and last 512 characters are still scanned; everything between them is preserved verbatim and never offered to a rule |

### A control redaction used to break: `secret_leak` — fixed by #1044

Through 2026-09-06 this was true: `SecretLeakDetector` (`classifier/detector/SecretLeakDetector.java:42-68`)
read `obs.output()` (`:117`) after the write-path guard had already replaced ten of its eleven strong
credential patterns with `[REDACTED_…]` markers, so a green `secret_leak` result on the OTLP path meant
nothing.

#1274 (2026-09-07) fixed it: the detector now also matches the five redaction-marker tokens
(`REDACTION_MARKERS`, `:63-68`) and fires at the confidence band the underlying raw shape carries, so the
marker redaction left behind is itself the evidence. `stripe-key` still has no marker to fall back on,
because redaction still doesn't cover Stripe's key format — that part is unchanged. #1044 is closed.

## Rules

A rule is a named regex `pattern` + a literal `replacement` token, with an `enabled` flag and a
`sort_order` (rules apply lowest-first, deterministically). A project may **disable** a built-in rule but
not edit or delete it. Custom rules are fully owned by the project.

### The defaults, in two tiers

- **Regulated PII** — email, US SSN, credit card, phone, IPv4.
- **Credentials** — `Authorization` bearer/basic values, JSON Web Tokens, provider API-key formats
  (`sk-`, `sk-ant-`, `AKIA`/`ASIA`, `gh?_`, `github_pat_`, `xox?-`, `AIza`, and our own `tsy_`), secret
  assignments (`api_key=…`, `password: …`), and private-key block headers.

The second tier exists because of what this product ingests rather than because of a regulation: an
agent's traces carry tool arguments, tool results, headers and environment dumps, so the
highest-severity thing likely to be sitting in a span body is a live secret. Each pattern is anchored on
a vendor's own prefix or on PEM framing, which is what keeps it precise enough to run by default — a
generic "forty base64 characters" rule would redact half of every payload we hold.

`tsy_` is in the list deliberately: an agent that talks to this platform holds a write-scoped ingest
token, and without that pattern it would land in its own traces.

**Replacements are substituted LITERALLY** (`Matcher.quoteReplacement`), so no built-in can use a
backreference — which is why the two patterns that match a key *and* its value replace both wholesale
rather than keeping the key. That is a deliberate trade: an operator-authored replacement must never be
able to inject regex.

### Seeding: reconciled, not seeded-once

`RedactionService.ensureSeeded` inserts every built-in a project is **missing, by name**, and it runs on
the write path (`compiledFor`) as well as on a read of the rule list.

Both halves of that are corrections, and both were live bugs:

1. The seed used to happen **only in `listRules`** — the Settings page read. The write-path guard applied
   whatever rules a project happened to have, so a project whose owner never opened Settings → PII
   redaction had **none**. Every default this platform shipped was, in practice, opt-in by clicking.
2. It used to short-circuit on "has any rule at all", so a newly added built-in reached new projects
   only — precisely backwards, since the projects with traffic are the old ones.

Matching by name also respects an operator's choices: a built-in they disabled is still a row, and is
therefore not missing. It does mean **renaming a template re-seeds it as a second row**, so names are as
immutable as the patterns.

Reconciliation also **re-points a built-in whose pattern has since been corrected**, because a built-in's
pattern is the platform's and not the project's — a rule can be disabled but never edited, so there is no
operator intent to overwrite. Without that, a *correction* would reach nobody. `enabled` is deliberately
never reconciled: that one is the operator's, and a default they turned off must not come back on a deploy.

The correction that made this necessary is worth keeping: the credit-card default was
`\b(?:\d[ -]*?){13,16}\b`, which matched the **sixteen fractional digits of `0.7799999999999999`** and
silently rewrote a retrieval score to `0.[REDACTED_CARD]`. A word boundary sits between `.` and `7`, so the
anchor did not stop it, and agent telemetry is full of floats like that — scores, probabilities, costs. It
is now `(?<![\d.])(?:\d[ -]?){12,15}\d(?![\d.])`, which still matches grouped and ungrouped Visa and
15-digit Amex and no longer matches a decimal. Measured over a 178 KB sample of synthetic traces: four
false positives before, zero after, and zero across all five credential rules.

### Who may author a rule

`custom_redaction_enabled` (off by default on the hosted tier; on by default in the open/self-hosted
edition, since it isn't in `CapabilityService.OFF_BY_DEFAULT`) gates **create / update / delete**, and previewing an
*unsaved* pattern. Reading the rule list, toggling a built-in, and previewing the whole active set stay
open to every org — that page is what a partner's security review reads to find out what we strip on the
ingest path, and gating it would hide the defaults along with the authoring tool.

Enforced through `redaction/CustomRuleGate`, an interface owned by `substrate` and implemented in
`product` (`plan/CapabilityCustomRuleGate`), because the capability layer sits a module *above* this one.
Before that seam existed the flag was declared and enforced nowhere.

Storage: `pii_redaction_rule`, scoped by `project_id`.
API: `/api/orgs/{org}/projects/{project}/redaction/{rules,rules/{id},rules/{id}/enabled,preview}`.
