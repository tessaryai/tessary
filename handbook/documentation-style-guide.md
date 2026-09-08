# Tessary documentation style guide

This guide defines how Tessary documentation is selected, structured, and presented. Follow the Tessary [voice and tone](voice-and-tone.md) guide and [writing style guide](writing-style-guide.md) alongside it.

## Write for the reader of the page

Tessary documentation serves different readers in different contexts.

The primary reader is a backend engineer evaluating Tessary for their team. Other common readers include:

- DevOps engineers deploying and operating Tessary
- Engineers integrating Tessary with their systems
- Engineers and product teams using Tessary
- Open-source contributors working on Tessary

Identify the reader and their goal before writing a page. Do not try to serve every audience equally on every page.

## Explain Tessary, not common tools

Assume readers are experienced with the common tools required for their task.

Explain:

- Tessary concepts and terminology
- Tessary-specific configuration and behavior
- Decisions readers must make when using Tessary
- Requirements or consequences that are not obvious
- Unusual interactions with external systems

Do not explain common engineering concepts unless understanding them is necessary to use Tessary correctly. Link to an authoritative external source when readers may need background information.

Never assume familiarity with Tessary merely because you assume technical competence.

## Choose the document type

Every page should have one primary purpose. Organize documentation into four types:

| Type | Reader’s goal | Approach |
| --- | --- | --- |
| Tutorial | Learn | Guided |
| Explanation | Understand | Reflective |
| How-to guide | Complete a task | Goal-first |
| Reference | Look something up | Neutral |

Avoid mixing these purposes until the page becomes difficult to follow. Link between document types instead of forcing one page to serve every need.

## Tutorials

A tutorial helps someone learn by completing a guided experience.

A tutorial should:

- Define what the reader will build or accomplish.
- Follow one reliable path from beginning to end.
- Introduce concepts when the reader encounters them.
- Provide enough explanation to understand each action.
- Show expected results at meaningful checkpoints.
- End with a working outcome and suggested next steps.

Keep choices to a minimum. A tutorial is not the place to document every alternative or configuration option.

Do not require readers to make decisions they are not yet equipped to make.

## Explanations

An explanation helps someone understand a concept, system, decision, or behavior.

An explanation should:

- State the subject and why it matters.
- Describe how the parts relate.
- Explain relevant reasoning, constraints, and tradeoffs.
- Use examples or diagrams when they improve understanding.
- Link to how-to guides for implementation.
- Link to reference pages for exact details.

Do not turn an explanation into a sequence of instructions. Its purpose is understanding, not task completion.

## How-to guides

A how-to guide helps someone complete a specific task.

Begin with the goal. Include only the context necessary to complete it.

A how-to guide should:

1. State what the reader will accomplish.
2. List actual prerequisites.
3. Present the recommended approach first.
4. Divide the task into clear, ordered steps.
5. Show expected results after important steps.
6. Explain alternatives after the recommended path.
7. Link to troubleshooting and reference material where needed.

Use task-oriented titles:

- Connect a repository
- Configure notifications
- Change the evaluation model

Do not use a how-to guide to teach the entire product or explain every underlying concept.

## Reference

Reference documentation provides accurate information for readers who need to look something up.

Reference content should be:

- Complete within its stated scope
- Consistent in structure
- Neutral and factual
- Easy to scan
- Organized around the product, interface, or API it describes

Use tables, parameter lists, schemas, and examples when they make comparison or lookup easier.

Document:

- Accepted values
- Defaults
- Required and optional fields
- Constraints
- Return values
- Side effects
- Errors
- Version or availability requirements

Do not use reference content to persuade, teach progressively, or recommend an approach unless the recommendation is part of the product’s intended use.

## Start pages directly

Open with what the page helps the reader understand or accomplish.

Use:

> Connect Tessary to GitHub so investigations can include repository changes.

Avoid:

> In this guide, we will walk you through the process of connecting Tessary to GitHub.

Do not begin with company positioning, broad background, or a summary of what the document itself contains.

## State prerequisites precisely

Include only prerequisites that are required for the procedure.

Be specific:

- A GitHub account with permission to install GitHub Apps
- Docker Engine 27 or later
- Administrator access to the Tessary project

Do not use vague prerequisites such as “basic technical knowledge” or “a properly configured environment.”

Link to setup instructions when a prerequisite requires a separate task.

## Recommend one path first

When several approaches are valid, lead with the one Tessary recommends for most readers.

Explain briefly why it is recommended. Present alternatives afterward, along with the situations in which they are appropriate.

Do not present every option equally when Tessary has a clear recommendation.

## Write procedures as actions

Use numbered steps for ordered procedures. Begin each step with the action the reader should take.

Keep one primary action in each step. Add supporting explanation after the action.

Use:

1. Open **Project Settings**.
2. Select **Connect repository**.
3. Choose the repository you want to connect.

Avoid hiding required actions inside paragraphs or combining several unrelated actions into one step.

Make optional steps explicitly optional.

## Show expected results

After a meaningful step, tell the reader what they should observe.

Examples:

- Tessary displays the repository as connected.
- The service returns a `200` response.
- New traces begin appearing within the project.

Place the expected result directly after the relevant step. Do not make the reader wait until the end of the page to learn whether an earlier action worked.

Include verification when success is not already unmistakable.

## Code examples

Optimize code examples for the smallest focused demonstration of the concept or task.

A code example should:

- Contain only the code relevant to the point being explained.
- Work when copied into the stated context.
- Use realistic names and values.
- Identify placeholders clearly.
- Include required imports or setup when they are not already established.
- Follow the conventions of the language or tool.
- Avoid production credentials, personal data, and real secrets.

Explain what the example does before or immediately after showing it.

Do not include unrelated abstractions, error handling, or production architecture merely to make a focused example appear complete. Link to a fuller example when readers need production context.

## Commands

Put commands in code blocks and identify the shell when useful.

Show commands separately from their output. Do not include the prompt character unless the reader must type it.

Use placeholders that are clearly distinguishable:

```bash
tessary projects get <project-id>
```

Explain where the reader can find each placeholder value.

Do not include destructive commands without explaining their effect first.

## Screenshots

Prefer annotated screenshots when visual context helps the reader identify an interface element or understand a result.

Annotations should:

- Direct attention to the relevant part of the interface.
- Remain legible at the displayed size.
- Avoid covering important content.
- Use consistent visual treatment.
- Communicate the same meaning without relying on color alone.

Crop screenshots to the relevant area, while preserving enough context for orientation.

Do not use a screenshot when text or code communicates the information more clearly. Never place essential instructions only inside an image.

Use realistic sample data without exposing customer information, credentials, personal information, or internal systems.

## Diagrams

Use a diagram when relationships, sequence, or architecture are difficult to explain clearly in prose.

Keep diagrams focused on one idea. Label components using the same terminology as the product and documentation.

Provide enough surrounding text for the reader to understand the diagram without relying on visual interpretation alone.

Do not use decorative diagrams.

## Notes and warnings

Use a note for relevant information that does not belong in the main procedure.

Use a warning when an action can cause data loss, downtime, security risk, unexpected cost, or another significant consequence.

State the consequence directly. Do not use warnings for ordinary information or general emphasis.

## Troubleshooting

Organize troubleshooting content around what the reader observes.

For each problem, provide:

1. The symptom
2. The likely cause or causes
3. The steps to resolve it
4. The expected result

Use the exact error message as a heading or searchable phrase when practical.

Place the most likely and least disruptive resolution first. Separate diagnostic steps from corrective actions.

Do not blame the reader or describe a problem as obvious, simple, or trivial.

## Links and navigation

Link to the page that directly answers the reader’s next question.

Use links to:

- Separate explanation from procedure
- Connect tutorials to deeper material
- Move optional detail out of the main flow
- Provide authoritative background on external tools
- Avoid duplicating reference information

Do not rely on links to compensate for missing information required to complete the current task.

Avoid repeating the same information across several pages when one canonical page can serve as the source.

## Product and interface references

Use the same terminology as the product and [glossary](product-glossary.md).

Match interface labels exactly. If the interface changes, update the documentation rather than describing the old and new labels together indefinitely.

Describe actions by their labels, not only by position, color, or icon.

## Versions and availability

State when instructions, behavior, or features apply only to specific versions, deployment types, or release stages.

Place the qualification before the reader begins the affected procedure.

Do not describe planned behavior as if it is currently available.

When behavior differs between versions or deployment types, make the difference explicit and help the reader identify which instructions apply.

## Keep marketing out of documentation

Documentation should help readers evaluate, understand, and use Tessary.

Support claims with specific behavior, evidence, or measurable outcomes. Do not use slogans, competitive claims, or promotional adjectives in place of technical information.

It is appropriate to explain why a capability matters. It is not appropriate to turn that explanation into a sales pitch.

## Make pages easy to find and scan

Use titles and headings that contain the words readers are likely to search for.

Keep sections focused. Use lists and tables when they make information easier to scan, but do not split connected ideas into fragments unnecessarily.

Put the information most relevant to the page’s goal first.

## Break a rule when the reader benefits

These patterns exist to make documentation useful and predictable. Depart from them when another structure serves the reader’s goal more clearly.

Make the decision intentionally and remain consistent within the page or documentation section.
