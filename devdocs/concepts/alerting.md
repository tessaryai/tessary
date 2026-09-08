# Alerting — how a case reaches a human

Nobody should have to be looking at the app to find out something regressed. This explains what
fires, what it carries, and the two decisions in the design that are easy to get wrong.

Schema: [`data-model.md`](../reference/data-model.md) § *Alerts*. Config keys:
[`config-keys.md`](../reference/config-keys.md) § `evals.alert.*`. Capability gate:
`alerts_enabled`, on by default.

> **Slack is not part of the launch.** `slack_enabled` is off, so the launch delivery route is a signed
> generic webhook. Everything below is transport-agnostic except where it says otherwise; the Slack
> specifics are in *Transports* at the end.

## The one rule that covers the launch product

There are four rule types in the schema, but only three are evaluated today: `digest` and `brief`
(per-project scheduled roll-ups, unchanged) and `case_opened`, the one the launch product runs on.
`threshold` (a classifier's detections over a rolling window) is a retired rule type with live
history — migration `0089` translated every enabled threshold rule's parameters onto its classifier's
arming config and disabled the rules; the schema still accepts creating one, but `AlertWorker` never
evaluates it.

**A threshold rule could not have covered the three launch detectors anyway, and this is a property
of the detectors rather than a gap in alerting.** A threshold rule counts detections, and a detection
used to be a `verdict` row with `source='automatic'` — that table is gone; each per-span classifier
now writes to its own `*_detection` table (migration `0088`). `duration_drift` and `cost_drift` write
no per-span detection at all — they say a *population* moved, which is not a statement about any
individual turn — and `tool_error` recomputes a rate from an hourly aggregate rather than labelling
calls. There is no unit for a threshold rule to count for any of the three.

What all three do produce, by construction rather than by coincidence, is a **case**: they pass the
same Layer-2 triage gate, and a case is the thing a human is meant to act on. So coverage is
one rule for all detectors, present and future, instead of a per-detector mechanism that would need
a new entry every time a detector lands.

```
CaseLedger opens a case
        │
        ▼  (next heartbeat, ≤60s)
AlertWorker.notifyOpenedCases
        │   ├─ org not entitled to alerts?        → skip
        │   ├─ inside quiet hours / under cadence? → hold, anchor unchanged
        │   ├─ detector withheld for this org?     → skip that case
        │   ▼
CaseAlertEvaluator.due  →  one AlertEventRow per case, payload baked in
        │
        ▼  idempotent insert on (rule, case_id)
AlertFiredEvent  ─┬─►  AlertDeliveryListener  →  webhook / PagerDuty / Sentry / Linear / (Slack)
                  └─►  SlackBriefPublisher    →  the native Slack app's channel   (Slack only)
```

## Quiet hours defer; they never drop

This is the decision worth stating, because the other one is easy and wrong.

Suppressing a notification during quiet hours and moving on would mean a regression that opened at
2am is never mentioned again. The case exists in the product, but the whole point of alerting is
that nobody has to be looking — and silently discarding a third of the day's notifications makes
that false for a third of the day, in the least visible way possible.

So a held tick delivers nothing **and does not advance the rule's anchor**. Everything that opened
while the window was shut is still "since the anchor" when it reopens, and goes out then, stamped
with when the case actually opened. Cadence works the same way: it spaces deliveries, batching what
opened in between rather than dropping it. Snooze is the manual form of the same hold.

The anchor advances to the *last case delivered*, not to `now`, so the per-tick cap
(`CaseAlertEvaluator.PER_TICK_CAP`) paces a backlog instead of skipping it.

**Failure direction.** Every part of `AlertPolicy` degrades permissively: an unreadable attributes
blob, a malformed local time, an unknown zone all resolve to *notify*. The safe failure for a
notification policy is a partner who is notified; failing closed would produce a partner who is
silently not.

## What the message carries

A case-opened notification is written to be actionable **without opening the product**, so it is a
several-line message rather than a title (`AlertPayload.caseMessage`):

- the case reference and title — what moved, and by how much;
- the detector and call site (`__unattributed__` is dropped: a tool's failure rate belongs to the
  tool, so there is no call site to name);
- the detector's own `basis` sentence — why it crossed *that* detector's bar, which is what makes
  cases from different detectors comparable to a reader;
- **who ruled it real**: an evidence-only Layer-2 run, or a person. Those are claims of different
  strength and the message says which, for the same reason the case page does;
- a link back, when `evals.alert.app-base-url` is set. When it is not, the message carries no link
  rather than a broken one.

The payload is resolved once, at fire time, and stored on the row. Delivery is retried; a retry an
hour later must send what was true when the case opened, not a re-read of a case someone has since
resolved.

Connectors that want a title (PagerDuty, Sentry, Linear) get `AlertPayload.summary`, which for a
case is `C-118 · discover-sales-prospects turns are 1.40× slower`.

## On by default, and what that means

Every new project is seeded a `case_opened` rule, enabled (`CaseAlertSeedListener`). That notifies
nobody on its own — there is no channel yet — and the point is to remove the second step: the moment
a partner adds a destination in **Settings → Notifications**, cases reach them with no rule to compose.

Seeding is non-destructive and idempotent. A project that already has the rule keeps its policy, its
enabled flag and its anchor.

## Transports, and why Slack is its own capability

A rule decides *when*; a channel decides *where*. Five channel kinds exist — generic webhook, PagerDuty,
Sentry, Linear, and Slack — plus the native Slack app's own channel post, which is not a channel row at
all but a listener on the same event.

**Slack is gated separately from alerting**, on `slack_enabled`, which is off. The two are different
questions: alerting is the capability (a case reaches a human unattended) and Slack is one route to it. So
turning Slack off costs a transport rather than the capability, and the launch route is the signed generic
webhook — which is exactly why **Settings → Notifications leads with the webhook field** and shows the
Slack one only to an org that has the capability. A partner who will never see the Slack option still has
a working notification path, and never reads a mention of something they cannot have.

The gate is applied in three places, and the third is the one that is easy to forget:

1. **Creating or updating a channel** (`AlertChannelController`) — a `slack` channel is refused outright,
   so the refusal lands in front of the person rather than in a log.
2. **Delivering to one** (`AlertDeliveryDispatcher`) — a Slack channel created *before* the flag flipped is
   skipped at fan-out. Refusing only at write would leave every existing channel delivering forever; this
   is the same reasoning segment D applies to a withdrawn classifier's output. No delivery-attempt row is
   written, because nothing was attempted — a withheld transport is not a failed send.
3. **The native app**, outbound (`SlackBriefPublisher`) and inbound (`SlackMentionService`), both through
   `SlackCapability` — all in `tessary-paid/slack` since #842, joined by the route itself
   (`SlackMentionController`) and its `SlackMentionSource` port in #920, so an open build simply has no
   native app at all: without this jar the endpoint does not exist, and `AuthFilter` does not bypass its
   path either — an open-only `auth/SelfAuthenticatingPath` port (`tenancy`) replaces the hard-coded
   bypass #920 removed, empty by default. Webhook-channel delivery
   (item 2, `SlackDelivery`) is a different feature and stays open. The inbound gate is checked *after* the workspace install resolves, because the
   install is what names the organization, and it stays silent rather than replying — posting "you do not
   have Slack" into Slack is the one message the gate exists to prevent.

**Slack runs out of process.** The protocol — signature verification, bot tokens, Web API calls, webhook
posts — lives in the Slack adapter service (`tessary-paid/slack-service/`, a Python service using `slack_sdk`; not
part of the public export).
The backend holds no Slack credential and cannot reach Slack directly; it POSTs a composed message to the
adapter's `/deliver`, and the adapter calls back to `/internal/slack/mention` for the one thing it cannot
answer. Every gate above is still evaluated on this side, before the adapter is ever called — the adapter
makes no product decisions, which is what lets it be deleted whole if Slack never ships.

**A second, unrelated switch.** Whether the adapter is deployed and reachable (`evals.slack.base-url` +
`service-key` on this side, `SLACK_SIGNING_SECRET` + `SLACK_BOT_TOKEN` on its side) is deploy-level.
`slack_enabled` is per-org. Both must be on, each fails closed independently, and they are kept apart on
purpose — an unconfigured deploy is not the same fact as an org that is not entitled, and one switch would
make either of them undiagnosable.

## Two gates a firing passes

1. **`Capability.ALERTS`** for the project's org — resolved per project, fail-closed on an unknown
   project.
2. **The detector is still one the org has.** A case opened by a classifier the flag layer later
   withheld stays in Triage as history (that is segment D's decision — closing it would tell someone
   a regression fixed itself), but it does not page anyone. The whole argument for leaving such a
   case visible is that a screen someone chose to open is different from a page at 3am; firing about
   it would collapse that distinction.
