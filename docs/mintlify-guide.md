# Mintlify implementation guide

This guide tells documentation agents how to implement Tessary documentation using Mintlify. It supplements the content rules in `/handbook` and does not replace or repeat them.

## Start with the existing repository

Before changing a page:

- Read `docs.json` to understand the current navigation, theme, and site configuration.
- Read nearby `.mdx` files and reuse established patterns.
- Check whether a reusable snippet or component already exists.
- Use the repository's existing preview and validation commands.
- Consult the [Mintlify documentation](https://www.mintlify.com/docs) when syntax or behavior is uncertain.

Do not introduce a new component pattern, custom React component, or global configuration convention when an established repository pattern already solves the problem.

## Page files

Use `.mdx` for documentation pages that contain Mintlify components. Begin each published page with frontmatter:

```mdx
---
title: "Page title"
description: "A concise description of what the reader can accomplish or understand."
---
```

The frontmatter `title` supplies the page's H1. Do not add another `#` heading in the page body. Begin body sections with `##`.

Keep file paths stable. If a path must change, update `docs.json`, internal links, and any related-page references in the same change.

## Navigation and `docs.json`

Treat `docs.json` as the source of truth for site-wide Mintlify configuration and navigation.

- Add a new page to navigation unless it is intentionally unlisted.
- Place pages according to the reader's task, not the implementation's internal structure.
- Reuse the existing root navigation pattern.
- Keep group and page labels concise and distinct.
- Do not change branding, integrations, SEO, or global navigation unless the task requires it.
- Do not use hidden pages for confidential content. Hiding a page is not access control.

## Choose components by purpose

Use components only when they make content easier to scan, follow, compare, or verify. Prefer plain Markdown when it communicates the information as clearly.

| Need | Use | Guidance |
| --- | --- | --- |
| Sequential procedure | `<Steps>` and `<Step>` | Use when order matters. Include verification after important steps. |
| Equivalent languages, frameworks, or installation methods | `<Tabs>` and `<Tab>` | Keep choices parallel. Put the recommended path first when appropriate. |
| Equivalent code examples | `<CodeGroup>` | Use when readers need the same example in multiple languages or formats. |
| Important contextual information | `<Note>` or `<Info>` | Use sparingly for information readers could otherwise miss. |
| Helpful, optional advice | `<Tip>` | Keep it nonessential to completing the task. |
| Risk or likely mistake | `<Warning>` | State the concrete risk and how to avoid it. |
| Destructive or severe consequence | `<Danger>` | Reserve for consequences that justify the stronger treatment. |
| Confirmed successful state | `<Check>` | Use for an expected result or completed prerequisite, not decoration. |
| Optional detail | `<Accordion>` | Use for secondary detail that most readers can skip. Do not hide required steps. |
| Navigation to a small set of related pages | `<Card>` or `<CardGroup>` | Use descriptive titles and destinations. Do not turn ordinary links into cards. |
| Screenshot or visual | `<Frame>` | Add useful alt text and an optional caption. Keep essential instructions in text. |
| File or directory hierarchy | `<Tree>` | Use only when hierarchy is the point. |
| API input or output field | `<ParamField>` or `<ResponseField>` | Include accurate type, requirement, default, and constraint information. |

Do not stack several callouts, nest components unnecessarily, or use visual components only to make a page look fuller.

## Common patterns

### Procedures

```mdx
<Steps>
  <Step title="Complete the action">
    Explain the action and include the smallest focused command or example.

    <Check>State the expected result.</Check>
  </Step>
</Steps>
```

### Alternatives

```mdx
<Tabs>
  <Tab title="Recommended option">
    Explain the recommended approach.
  </Tab>
  <Tab title="Alternative option">
    Explain when this approach is appropriate.
  </Tab>
</Tabs>
```

Do not use tabs for sequential steps or unrelated topics. Content inside every tab must remain complete enough to follow without reading another tab.

### Callouts

```mdx
<Warning>
  Changing this setting restarts the service. Save active work before continuing.
</Warning>
```

Callouts must communicate information, not merely emphasize a sentence. Keep the title or first sentence specific enough to understand while scanning.

### Related pages

```mdx
<CardGroup cols={2}>
  <Card title="Descriptive destination" icon="book" href="/path/to-page">
    Explain what the reader will find there.
  </Card>
</CardGroup>
```

Use inline links within prose when navigation is not a primary purpose of the section.

## Code and commands

- Set the correct language on every fenced code block.
- Make examples copyable without including prompts such as `$` or `>`.
- Use placeholders that clearly indicate what readers must replace.
- Never include real credentials, internal URLs, customer data, or production identifiers.
- Use `<CodeGroup>` only for equivalent examples. Do not use it to separate consecutive commands.
- Use the repository's established filename labels and highlighting syntax when present.
- Explain any non-obvious placeholder immediately before or after the example.

## Images

- Store image assets using the repository's existing asset structure.
- Wrap screenshots in `<Frame>` when that matches nearby pages.
- Use annotated screenshots when the annotation helps the reader locate or understand something.
- Write alt text that communicates the image's useful information.
- Keep required actions, values, and warnings in the page text.
- Do not rely on screenshots for information likely to change frequently when text or code is clearer.

## API documentation

Use generated OpenAPI pages when the repository's OpenAPI specification is authoritative. Do not manually duplicate generated endpoint definitions.

For manually maintained API content:

- Use `<ParamField>` for request parameters.
- Use `<ResponseField>` for response properties.
- Document required status, types, defaults, accepted values, and constraints accurately.
- Include focused request and response examples where they help readers verify integration behavior.
- Keep prose explanations outside field components when they apply to multiple fields.

## Reuse and abstraction

Reuse a shared snippet when the same substantive content must appear on multiple pages. Do not create a snippet for a short phrase or content that is likely to diverge by context.

Before creating a custom MDX or React component:

1. Confirm that Markdown and built-in Mintlify components are insufficient.
2. Check for an existing local component.
3. Keep the component presentational and reusable.
4. Preview it on desktop and narrow layouts.

## Validation

Before finishing a Mintlify documentation change:

- Confirm that the MDX renders without errors.
- Confirm that component tags, properties, and nesting are valid.
- Test internal links and navigation entries.
- Check headings and the table of contents for a sensible hierarchy.
- Check tabs, accordions, cards, and code examples in the preview.
- Check both desktop and narrow layouts when the change affects layout.
- Confirm that no required information is available only through hover, color, or an image.

If the repository does not provide a validation command, preview the site with the current Mintlify CLI workflow and report anything that could not be checked.

## Mintlify references

- [Components overview](https://www.mintlify.com/docs/components)
- [Pages and frontmatter](https://www.mintlify.com/docs/organize/pages)
- [`docs.json` settings](https://www.mintlify.com/docs/organize/settings)
- [Navigation](https://www.mintlify.com/docs/organize/navigation)
- [API documentation](https://www.mintlify.com/docs/api-playground/overview)

Use these references for syntax and platform behavior. Use `/handbook` for Tessary's writing, terminology, audience, and content decisions.
