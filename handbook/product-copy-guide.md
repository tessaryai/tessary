# Tessary product copy guide

This guide defines how Tessary writes inside product interfaces. Follow the Tessary [voice and tone](voice-and-tone.md) guide and [writing style guide](writing-style-guide.md) alongside it.

## Help the reader act

Product copy should help someone understand:

1. What happened
2. What it means
3. What they can do next

Not every interface needs to state all three. Show the minimum information needed at the current stage, then make further detail available when the reader needs it.

## Keep the interface concise

Use progressive disclosure to keep the main interface focused.

- Lead with the essential fact or action.
- Place supporting context where it becomes relevant.
- Move technical evidence and secondary details into expandable sections or deeper views.
- Do not repeat information already clear from the interface.
- Do not shorten copy until its meaning becomes ambiguous.

Concise does not mean incomplete.

## Refer to Tessary when useful

Use “Tessary” when naming the actor clarifies what happened:

- Tessary found a sustained increase in errors.
- Tessary could not connect to the repository.

Omit the actor when the action or state is already clear:

- No results yet
- Repository connected
- Analysis in progress

Do not use “we” as the voice of the interface.

## Page titles

A page title should name the place or primary object:

- Projects
- Settings
- Notification Settings

Do not turn page titles into instructions or marketing statements.

## Section headings

Use headings that describe the information below them:

- What changed
- Supporting evidence
- Likely cause
- Recommended action

Avoid vague headings when something more specific is available:

- Overview
- Details
- Information
- Insights

Use a general heading when its meaning is already clear from the surrounding context.

## Buttons and actions

Choose the shortest label that clearly describes the action.

Use:

- Create
- Connect repository
- Start analysis
- Save changes
- Delete project

Avoid unnecessary words:

- Click to create
- Proceed with connection
- Yes, save my changes

Start action labels with a verb when practical. Use the same label for the same action throughout the product.

## Links

Use links for navigation and secondary actions. Use buttons for actions that change data or product state.

Link text should describe its destination:

- View affected data
- Review settings
- Read the setup guide

Avoid generic text such as “Learn more” when a specific destination can be named.

## Field labels and helper text

Use a short noun or noun phrase for a field label:

- Project name
- Repository URL
- Evaluation model

Use helper text only when it explains a constraint, consequence, format, or unfamiliar concept:

- Enter a value between 0 and 1.
- Changing this setting starts a new baseline.

Do not use helper text to repeat the label.

Placeholder text should show an example or expected format. It should not replace a visible field label.

## Describe results by what happened

Titles for system-generated results should state the observed change, outcome, or condition.

Use:

- Error rate increased by 42%
- Response duration increased after deployment
- No data received in the past 6 hours

Avoid naming only the mechanism that detected it:

- Anomaly detected
- Classifier alert
- Analysis result
- Issue found

Use the most concrete meaningful measurement available. Do not imply impact unless the evidence supports it.

## Separate observation from interpretation

Use explicit wording to distinguish facts from conclusions.

For observations:

- Tessary observed...
- The data shows...
- The increase began...
- 73% of affected records...

For conclusions:

- Tessary identified this as the likely cause...
- The evidence suggests...
- This may be related to...
- Tessary has not found enough evidence to determine the cause.

Do not present a suspected cause as a confirmed fact.

## Communicate impact concretely

Explain the effect before assigning a severity or urgency label.

Use:

- 18% of requests are affected.
- Responses are taking 4.2 seconds longer.
- No data has been received since 3:20 PM.

Avoid relying on labels alone:

- Critical issue
- High severity
- Major degradation

Labels can help readers sort and prioritize information, but they should not replace an explanation of the impact.

Do not use urgent language unless the evidence and required response justify it.

## Empty states

An empty state should explain the current state and the next available action.

When setup is required:

> No data received  
> Complete the setup to start receiving data.  
> **View setup guide**

When nothing requires attention:

> Nothing to review  
> Tessary has not identified anything that requires your attention.

When filters produce no results:

> No results match these filters  
> Change or clear the filters to see more results.  
> **Clear filters**

Do not use empty states as advertising space.

## Error messages

An error message should explain:

1. What failed
2. How to recover

Add the cause or technical detail when it helps the reader fix the problem.

Use:

> Connection failed  
> Check that Tessary has access, then try again.

Avoid:

> Something went wrong.

Do not blame the reader or imply that recovery is easy. Preserve entered information whenever possible and say when data was not saved.

Use technical error codes only when they help with troubleshooting or support. Keep them secondary to the plain-language explanation.

## Warnings

Use a warning when an action has an important consequence but can still proceed.

State the consequence directly:

> Changing this setting starts a new baseline. Existing results remain available.

Do not use a warning merely to ask whether someone is sure.

## Destructive actions

A destructive confirmation should name the action and its consequence.

Title:

> Delete this project?

Body:

> This permanently deletes the project and its data.

Actions:

- Delete project
- Cancel

Use the specific action as the confirmation label. Do not use “Yes,” “Confirm,” or “Continue.”

Do not make the destructive action visually or verbally ambiguous.

## Success messages

Confirm an action when the result is not immediately visible or when reassurance is useful:

- Repository connected
- Project created
- Settings saved

Do not show a success message when the updated interface already makes the result obvious.

Say what completed. Avoid generic celebration such as “Success!” or “Awesome!”

## Loading and progress

Describe what is happening when an operation takes long enough to require feedback:

- Connecting...
- Analyzing data...
- Saving changes...

If the reader can leave safely, say so. If the operation can take a long time, set an honest expectation without promising an exact duration unless it is reliable.

Do not use “Almost done” unless the system knows that it is almost done.

## Notifications

A notification should communicate:

1. What happened
2. The concrete impact
3. The next action, when one is useful

Write the title so it can be understood without opening Tessary.

Use notifications for meaningful changes, not routine product activity.

## Tooltips

Use a tooltip for a short explanation that helps someone understand an unfamiliar control, metric, or term.

Do not place essential instructions, warnings, or recovery steps only in a tooltip. If someone needs the information to complete a task safely, show it in the interface.

## Setup and onboarding

Focus each step on one outcome. Explain why information is required when the reason is not obvious.

Avoid introductory copy that delays the first useful action.

Show progress when setup contains several required steps. Make optional steps clearly optional.

## Product character

The product can have character, but usefulness comes first.

Keep personality subtle and appropriate to the situation. Do not add humor to compensate for vague copy or an unclear workflow. As the seriousness of the situation increases, favor precision and restraint.
