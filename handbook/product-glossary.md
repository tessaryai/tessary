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

The messages that share a trace's session. For the frustration classifier, a session is the trace's thread when it has one, and its session otherwise; it counts frustrated sessions.

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

What a person says a resolved frustration case turned out to be: **Fixed** or **False alarm**.

Use:

- Resolve the case with the disposition that matches what happened.

Only a frustration case has a disposition. Other cases close on a one-line reason alone.

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

Capitalize them when required by the Tessary [writing style guide](writing-style-guide.md), such as in a top-level page title.
