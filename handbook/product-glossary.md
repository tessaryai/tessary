# Tessary product glossary

This glossary defines Tessary’s canonical product terminology. Use these terms consistently across the product, documentation, website, support, and company communications.

Do not introduce a synonym for an established product concept merely to make the writing feel less repetitive. Consistency is more useful than variety.

## Agent

A system that uses an AI model and one or more tool calls to perform a task. This includes systems that use RAG (retrieval-augmented generation), where retrieving information is part of the agent’s execution.

Use “agent” when referring to the complete system, not only the model within it.

Use:

- Tessary monitors agents running in production.
- The agent called the retrieval tool before responding.

Avoid using “agent” and “model” interchangeably. The model is one component of the agent.

## Trace

One complete execution of an agent.

A trace can include the agent’s input, output, model calls, tool calls, intermediate steps, timing, cost, and related metadata.

Use:

- Tessary evaluates every trace.
- Open the trace to review the agent’s execution.

Do not use “trace” to refer to an individual model call, tool call, message, or step within the execution.

## Classifier

A check that evaluates traces against a defined condition.

Classifiers are designed to run cheaply enough to evaluate every trace. A classifier creates a finding when its condition is met.

Use:

- The classifier evaluates traces for tool-call errors.
- Create a classifier for the condition you want to monitor.

Do not use “classifier” as a general term for every evaluation, analysis, or model used by Tessary.

## Finding

A change detected in production behavior.

A classifier creates a finding when its defined condition is met. A finding records what changed and the production evidence associated with that change. It is not yet a validated issue.

Use:

- Tessary created a finding after response duration increased.
- Review the finding to see what changed.

Do not use “alert,” “anomaly,” “incident,” or “case” as interchangeable terms for a finding.

## Triage

The process of determining whether a finding represents a real issue.

Triage reviews the finding and its evidence. If triage determines that the finding is a real issue, it becomes a case. A high-confidence secret leak and a frustration finding do not go through triage; see Case.

Use “triage” as either a noun or a verb when the meaning is clear:

- Triage determined that the finding was a real issue.
- Tessary is triaging the finding.

Do not use “triage” to refer to the complete investigation or resolution of an issue.

## Case

A validated issue that requires attention.

A finding becomes a case after triage determines that it represents a real issue.

A classifier can rule its own finding at filing when the finding's numbers are the claim. A high-confidence secret leak and a frustration finding are ruled this way, so they open a case without triage.

Use:

- Triage created a case from the finding.
- Review the case to understand the issue.

Do not use “case” for every finding or suspected issue. A case indicates that the issue has been validated.

## Session

The messages that share a trace's session. The frustration classifier counts a session once in each hour it has a scored message; a total across hours is session-hours.

Use:

- The call site's rate of frustrated sessions increased.

Do not use “conversation” as a synonym for session, and do not use “session” for a single trace or a single message.

## Groundedness

The classifier that checks whether an agent’s answers are supported by their source: the documents the agent retrieved, or the prompt when it retrieved none. A model scores each sentence of an answer against that source.

A flagged answer is not a finding on its own. Tessary tracks each call site’s share of traces with a flagged answer and creates a finding when that share rises above the call site’s normal. A groundedness finding goes through triage.

Use:

- The groundedness classifier flagged two sentences in the answer.
- Answers on this call site became less grounded.

Groundedness measures whether the source supports a statement, not whether the statement is true.

## Flagged answer

An answer in which the groundedness classifier marked at least one sentence as unsupported by the answer’s source.

Use:

- Review the flagged answers to see the marked sentences beside the retrieved documents.

Do not use “flagged answer” for a finding or a case. A finding counts traces with a flagged answer; one flagged answer does not create a finding.

Do not describe a flagged answer as a confirmed error. A flag says the model found a sentence unsupported; the reader checks it against the source.

## Disposition

What a person said a closed frustration or groundedness case turned out to be: **Fixed** or **False alarm**. The case page no longer asks for one, so only cases closed before that change carry it, and their closing line still names it.

## Metrics

The page that charts a project’s spend, latency, traces, LLM calls, tool calls and cases over a time window. Metrics counts; it does not judge. A number on Metrics never creates a finding.

Use:

- Open Metrics to see spend by call site over the last 7 days.
- Metrics leaves unpriced calls out of the spend figure and says how many there were.

Write “Metrics” with a capital M, as the page name. Do not use “analytics,” “Vitals,” or “stats” for it.

## Dashboard

A named set of widgets on the Metrics page. Every project has a built-in default dashboard, which cannot be edited; a person can copy it, or create a new dashboard, and edit that.

Use:

- Duplicate the default dashboard to change its widgets.
- The dashboard shows the last 30 days.

Do not use “dashboard” for other pages of the product, such as Triage or Traces.

## Widget

One chart on a dashboard: a number, a time series, or a table. A widget is a saved query, so the same query sent to the API returns the same figures.

Use:

- Add a widget that shows p95 latency over time.
- The widget counts tool calls by tool.

Do not use “chart,” “card,” or “panel” as a synonym for widget in product copy. A widget contains a chart.

## Prompt

A named template that an agent fetches from Tessary and fills in at run time. A prompt is text or a list of chat messages, and it can hold variables written as `{{name}}`.

A prompt belongs to one project. A `/` in its name puts it in a folder, for example `support/greeting`.

Use:

- Create a prompt for the support agent's greeting.
- The agent fetches the prompt by name.

Do not use “prompt” for the messages that a trace recorded. Those are the trace’s input.

## Prompt version

One saved state of a prompt. Each save creates a new version with the next number, and a version never changes after it is created.

Use:

- Version 3 shortens the system message.
- Compare version 2 and version 4.

Do not use “revision” or “draft” for a prompt version.

## Deployment label

A name that points at one version of a prompt, such as `production` or `staging`. An agent fetches a prompt by its label, so moving the label to another version changes what runs without a code change. A label is on one version at a time.

`latest` is not a deployment label you set. It always points at the highest version number.

Use:

- Move the `production` label to version 4.
- Roll back by moving `production` to version 3.

Write “deployment label” the first time on a page. After that, “label” is clear only when no form field is nearby, because “label” also means a field label (see the [product copy guide](product-copy-guide.md)). Do not use “tag” for a deployment label: a tag only groups prompts and changes nothing an agent fetches.

## User ID

The value a trace carries in `user.id`: the stable ID of one end user of the agent, or of one account, the same across sessions, days and devices.

Tessary counts user IDs, not people. One user ID can hold several people, and one person can use several user IDs. A session ID, a request ID, a daily cookie, an API key or a workspace is not a user ID.

Use:

- 1,240 user IDs are counted.
- Only 15% of traces carry a user ID.

Write “user ID” in sentences and “user IDs” in every count. Do not count “users”, “people”, “customers” or “accounts” when the number counts user IDs.

## Users

The page that shows which user IDs are active, silent or probably stopped, and which issues came before more stops than expected.

Write “Users” with a capital U, as the page name. In a count, write “user IDs”, not “users”.

## User IDs hit

The number of different user IDs whose traces are in the evidence of an issue.

A hit says that the issue reached the user ID. It does not say that the user ID stopped, or that the issue caused a stop.

Use:

- The case hit 40 user IDs.
- 3 traces in the evidence have no user ID and are not in the count.

Do not use “user IDs hit” as a count of losses or of affected people.

## User IDs lost

An estimate of how many more hit user IDs have probably stopped than expected from similar user IDs not yet hit. The page gives it as “about N” with a range from low to high.

Only the verdict “evidence of loss” claims a loss. The other verdicts that show a number say “no clear loss” and give the most the loss can be, and some verdicts show no number. Counts of different issues overlap and do not add up.

Use:

- About 12 user IDs lost (5 to 20).
- No clear loss; if today’s silences last, up to 9.

The short form “users lost” appears only in labels: the issue list heading, the issue page title, the link to the issue page and the line that explains the count. In a number, write “user IDs lost”. Do not state user IDs lost as a fact about people, and do not state it as proof that the issue caused the stops.

## Issue

A case as the Users page shows it. When a case of the same cause opens again, the earlier and later cases are one issue.

A finding with no case is not an issue on the Users page.

Use:

- Issues, by users lost
- This issue hit 40 user IDs.

Do not use “issue” for a finding that has no case.

## Probably stopped

The stage of a user ID that is clearly more likely gone than not. Tessary judges this against the app’s own record of returns, not against a fixed number of days.

The stage gives a named count and an expected count. The expected count also adds the stop odds of user IDs that are not named, so it is often larger. Each named user ID has the grade “stopped” or “probably stopped”.

Use:

- 80 user IDs named, about 95 expected.

Do not use “churned”, “lost”, “gone” or “inactive” for this stage. Do not say that a probably stopped user ID stopped because of an issue.

## Silent, not judged

The stage of a user ID that is quieter than usual but is not named as stopped. It holds user IDs whose stop odds are too low to name them, user IDs held as a possible break, and user IDs whose automated runs continue while the person is silent.

The three stages Active, Silent, not judged and Probably stopped add up to the user IDs that are counted.

Use:

- Silent, not judged · 30

Do not use “at risk”, “slowing” or “likely to leave” for this stage. Tessary does not judge those.

## Held as a possible break

The state of a user ID that went silent in the same week as many other regular user IDs.

Silence that many user IDs share can be a break, such as an outage, a holiday or a tracing change, or it can be a loss. Tessary does not know which yet. A held user ID counts in Silent, not judged, and a banner names the date until which it is held.

Use:

- 150 user IDs are held as a possible break until Mar 3. A break or a loss; not yet known.

Do not count a held user ID as stopped.

## Verdict

The word the Users page puts on an issue to say what its numbers can claim. The page tests the verdicts in the order of the table below, and the first one that applies is the verdict. When the page shows a state in place of numbers, such as No user ID or Too early, no issue gets a number. A case that shifts a measure for all traffic has no hit user IDs and gets no verdict. Every verdict still shows the user IDs hit.

| Verdict | What it means | Loss number shown |
| --- | --- | --- |
| Hits not complete | The detector of a finding in this issue does not record every hit, so the comparison would be biased. | None |
| History too short | Tessary keeps less user ID history than the estimate needs. | None |
| Cannot tell yet | User IDs in this app return less often than the history can show. | None |
| Cannot measure yet | A stop starts to show some days after the last use, and half of the hit user IDs were hit less than that ago. | The most the loss can be |
| Too few user IDs | Too few hit user IDs can be compared with similar user IDs not yet hit. The page says how many can be compared and that 30 are needed. | None |
| Reaches nearly all user IDs | The issue hits nearly every user ID that uses that part of the agent, so too few similar user IDs are left to compare with. The number comes from the stops of all those user IDs before and after the issue started. | An estimate with no lower limit |
| Not comparable | Hit user IDs stop clearly less often than similar user IDs not yet hit, so the comparison does not hold. | None |
| Always present | The issue was present from the first day of data, so there is no time before it to compare with. It is not shown as a cause. | An estimate with no lower limit |
| Few accounts | The hits sit in a few accounts. User IDs of one account leave together, so the hits count as a few cases, not many. | An estimate with no lower limit |
| Evidence of loss | More hit user IDs probably stopped than expected, by more than chance and the issue’s own random test allow, and the stops of all user IDs on that part of the agent rose. | User IDs lost, with a range |
| Moved, not added | Hit user IDs stop more often, but the stops of all user IDs on that part of the agent did not rise after the issue started. The issue marks user IDs that leave; it is not shown as a cause. | An estimate with no lower limit |
| No measurable loss | Enough hit user IDs were compared, and the most the loss can be is small. This does not prove that the loss is zero. | An estimate with its range |
| Not enough evidence | None of the verdicts above applies. | An estimate with no lower limit |

Use:

- Evidence of loss: about 12 user IDs lost (5 to 20).
- Too few user IDs to compare: 21 of the 30 needed.

Only “evidence of loss” says that an issue came before a loss. Do not describe any other verdict as a loss, and do not describe “evidence of loss” as proof of cause.

## How the terms relate

1. An **agent** produces a **trace**.
2. A **classifier** evaluates the trace against a defined condition.
3. The classifier creates a **finding** when that condition is met.
4. **Triage** determines whether the finding represents a real issue. A high-confidence secret leak or a frustration finding is ruled at filing instead.
5. A validated finding becomes a **case**.
6. On the Users page, a case is an **issue**. Tessary counts the **user IDs hit** by the issue and gives a **verdict** on whether more of them stopped than expected.

## Capitalization

Use lowercase for these terms in sentences:

- agent
- trace
- classifier
- finding
- triage
- case
- dashboard
- widget

- prompt
- prompt version
- deployment label

- user ID
- issue
- verdict
- each verdict word, such as evidence of loss

Write “ID” in capitals in “user ID”. Write a verdict word or a stage name in sentence case when it stands alone as a label, such as “Evidence of loss” on a badge or “Probably stopped” on a tab.

Capitalize them when required by the Tessary [writing style guide](writing-style-guide.md), such as in a top-level page title.
