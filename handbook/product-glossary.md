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

## How the terms relate

1. An **agent** produces a **trace**.
2. A **classifier** evaluates the trace against a defined condition.
3. The classifier creates a **finding** when that condition is met.
4. **Triage** determines whether the finding represents a real issue. A high-confidence secret leak or a frustration finding is ruled at filing instead.
5. A validated finding becomes a **case**.

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

Capitalize them when required by the Tessary [writing style guide](writing-style-guide.md), such as in a top-level page title.
