# Tessary writing style guide

This guide defines how Tessary writes across the product, documentation, website, support, and company communications. Follow the Tessary [voice and tone](voice-and-tone.md) guide alongside it.

## Write clearly

Prefer the clearest accurate wording.

- Use familiar words when they are as precise as technical alternatives.
- Keep sentences focused on one main idea.
- Remove words that do not change the meaning.
- Use concrete descriptions instead of abstract claims.
- Explain what something does instead of describing it as powerful, robust, seamless, or best-in-class.
- Add technical detail when it helps the reader understand, evaluate, or act.

Clarity takes priority over sounding clever.

## Use American English

Use American spelling and grammar.

Use:

- Behavior, not behaviour
- Analyze, not analyse
- Center, not centre
- License as both the noun and verb

## Address the reader directly

Use a mix of direct commands and “you.”

Use direct commands for instructions and actions:

- Select **Create project**.
- Connect your repository.
- Review the affected traces.

Use “you” when the reader’s situation, choice, or outcome matters:

- You can disable the classifier for a specific environment.
- If you change the model, Tessary starts a new baseline.

Do not refer to the reader as “the user” unless discussing users as a group or distinguishing between different roles.

## Prefer active voice

Make the person or system performing an action clear.

Use:

- Tessary analyzes every trace.
- Connect your repository to investigate code changes.

Avoid:

- Every trace is analyzed by Tessary.
- The repository should be connected.

Passive voice is acceptable when the action or result matters more than who performed it.

## Use the present tense

Describe current behavior in the present tense:

- Tessary creates a finding when a classifier detects an issue.

Use the future tense only for something that will happen later:

- The classifier will begin running after the next deployment.

Do not use “will” merely to describe normal product behavior.

## Use contractions naturally

Use contractions when they make writing sound more direct and conversational:

- You’ll see the new finding after the classifier runs.
- Tessary doesn’t change your trace data.

Avoid contractions when they could reduce clarity or make a serious statement feel too casual.

## Capitalization

### Top-level page titles

Use title case for the names of top-level pages:

- Findings
- Project Settings
- Notification Settings

### Everything within a page

Use sentence case for section headings, buttons, menu items, tabs, field labels, and other interface text:

- Slack and email alerts
- Create new project
- Investigation history

When unsure whether something is a top-level page, use sentence case.

### Names and acronyms

Always preserve the official capitalization of proper names, product names, and acronyms. Match interface text exactly when referring to a label, button, menu item, or page.

## Acronyms and technical terms

Define every acronym and technical term the first time it appears in a standalone piece of content.

Use the acronym first, followed by the full term in parentheses:

- RCA (root-cause analysis)
- SLO (service-level objective)

Use the acronym alone after defining it.

Explain technical terms in context. Do not interrupt the reader with a definition when a short plain-language alternative would work better.

## Product and feature names

Use descriptive names by default. A name should help someone understand what the thing does without requiring them to learn Tessary’s internal language.

Avoid:

- Invented names that do not communicate a function
- Branded names for ordinary product concepts
- Names based on internal implementation details
- Different names for the same concept across the product and documentation

Use the Tessary [glossary](product-glossary.md) as the authority for established terminology.

## Punctuation

### Oxford comma

Use a comma before the final item in a series:

- Tessary detects, investigates, and explains production issues.

### Dashes

Do not use em dashes. Use a comma, colon, parentheses, or a new sentence instead.

Use a hyphen for compound modifiers:

- open-source platform
- low-frequency issue
- production-ready configuration

Do not hyphenate a compound when it does not modify a noun:

- Tessary is open source.
- The issue occurs at low frequency.

### Colons

Use a colon to introduce an explanation, example, or list:

- Tessary found one likely cause: the model changed during the deployment.

Do not capitalize the first word after a colon unless it begins a complete sentence or is a proper name.

### Exclamation marks

Use exclamation marks sparingly. Do not use them to manufacture excitement or soften serious information.

## Numbers

Use numerals for measurements, percentages, quantities, durations, versions, and technical values:

- 3 classifiers
- 5% of traces
- 20 seconds
- version 2.1

Spell out a number when it begins a sentence. Rewrite the sentence when practical to avoid beginning with a number.

Use commas in numbers with four or more digits:

- 1,000 traces
- 25,000 findings

Use the `%` symbol with numerals. Do not write “percent” unless required by the context.

## Dates and times

Write dates unambiguously:

- September 3, 2026
- September 3
- 2026-09-03 in technical formats

Avoid ambiguous numeric dates such as `09/03/2026`.

Include a time zone when readers could be in different locations:

- 9:00 AM Eastern Time
- 6:30 PM India Standard Time

Use the 12-hour clock in general writing. Use the 24-hour clock when required by a technical format.

## Lists

Use a bulleted list when order does not matter. Use a numbered list when order matters or the reader must complete a sequence.

Keep list items grammatically parallel. Start every item with the same type of word or phrase where possible.

Use complete sentences and ending punctuation when list items contain multiple sentences. Short labels and fragments do not require ending punctuation.

## Links

Write link text that describes the destination or action.

Use:

- Read the installation guide.
- Review the classifier configuration.

Avoid:

- Click here.
- Learn more.
- Read this.

Do not expose a full URL when descriptive link text is available, except in code, configuration, or contexts where the reader needs the URL itself.

## Interface references

Use the exact text shown in the interface. Format the names of buttons, fields, tabs, menus, and pages consistently.

Do not describe an interface element only by its location or appearance:

- Select **Create classifier**, not “Select the blue button on the right.”

Avoid directional language that can become incorrect when the interface changes.

## Inclusive and accessible language

Write about people respectfully and specifically.

- Use gender-neutral language when gender is irrelevant.
- Avoid idioms and cultural references that make instructions harder to understand.
- Avoid language that assigns blame to the reader.
- Describe the problem and the recovery path instead.

Do not rely on color, position, or an icon alone to communicate meaning.

## Words and phrases to avoid

Avoid words that exaggerate, obscure, patronize, or make unsupported claims:

- Obviously
- Simply
- Just
- Easy
- Effortless
- Seamless
- Revolutionary
- Best-in-class
- Magic
- Leverage, when “use” works
- Utilize, when “use” works
- Empower, when the specific outcome can be stated

These words are not automatically forbidden. Use one only when it communicates something accurate and necessary.

## Open source

Use “open source” as a noun or when it follows the noun:

- Tessary is open source.
- Contribute to the open source.

Use “open-source” as a compound modifier before a noun:

- An open-source agent reliability platform
- The open-source repository

## Break a rule when clarity requires it

These rules support clear and consistent writing. They should not force awkward, inaccurate, or unnatural language.

When departing from a rule, make the decision intentionally and remain consistent within the same piece of content.
