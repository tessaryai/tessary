# Documentation agent instructions

## Scope

These instructions apply to all files under `/docs`.

## Required reading

Before writing or editing documentation, read:

- `/handbook/documentation-style-guide.md`
- `/handbook/writing-style-guide.md`
- `/handbook/voice-and-tone.md`
- `/handbook/product-glossary.md`

Read `/handbook/brand-foundations.md` when describing Tessary, its purpose, or its positioning.

The handbook is authoritative. Do not restate its rules in this file.

Read `mintlify-guide.md` (in this folder) before implementing a change: page structure, `docs.json`, components, and validation. It supplements the handbook and does not replace it.

## Before making changes

- Read the target page and related documentation.
- Inspect the relevant code, configuration, and interface.
- Confirm the intended reader and document type.
- Do not invent product behavior, terminology, defaults, or interface labels.
- Ask for clarification when the implementation does not establish the answer.

## Editing constraints

- Preserve the page’s purpose and document type.
- Keep changes within the requested scope.
- Do not rewrite unrelated content.
- Use terminology defined in `/handbook/product-glossary.md`.
- Update affected links and related references when necessary.
- Link to canonical information instead of duplicating it.

## Verification

Before finishing:

- Verify commands, configuration, defaults, and product behavior.
- Confirm that links and referenced interface labels are current.
- Review the change against the required handbook guides.
- Run `docs/vale/check.sh` and resolve any errors it reports. It only covers a narrow, mechanical
  subset of the handbook (see `docs/vale/README.md`); passing it is not a substitute for the
  review above.
- Report any assumptions or details that could not be verified.
