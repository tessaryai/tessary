# Prompt craft

Every prompt the platform sends to a model lives as markdown under
`prompt-craft/<purpose>/`, in the resources of the module that owns it. One directory per purpose.

| module · purpose | who reads it | what it is for |
|---|---|---|
| `analysis` · `triage/` | `classifier/finding/BehaviorTriageEngine` | Layer-2: is a detector's claim about production traffic true? |
| `analysis` · `rca/` | `rca/AgenticRcaEngine` | Layer-3: what change caused it? |

## What belongs there, and what does not

Only prose. Which paragraph applies to a given finding is **logic**, and it stays in the engine,
which still chooses and concatenates. `PromptCraft`'s javadoc owns the full why; the rule of thumb:

> If a human would edit it to change **what** the model reads, it is prose.
> If a human would edit it to change **when** the model reads it, it is code.

`rca/response_schema.json` keeps its own extension: it is data the prompt carries, not prose and not
control flow.

## Changing one

These strings are what the model reads, so they are pinned byte-for-byte by
`PromptResourceParityTest` against goldens in `src/test/resources/prompt-golden/`. Editing a file
here fails that test until you re-capture:

    mvn -pl analysis -am test -Dtest=PromptResourceParityTest \
        -Dsurefire.failIfNoSpecifiedTests=false -Dprompt.golden.capture=true

Re-capture only when you **meant** to change the prompt. Running it to make a red test go green is
exactly the failure the pin exists to prevent — it turns an accidental whitespace edit into a
silent change in how a lane rules.

The `evaluation` module used to also ship `prompt-craft/grader-judge/` — manifest-driven, composed by
`CraftLibrary#compose` rather than read file by file. The module, the craft assets, and the reference
document that described the path they served are gone.

## Adding a purpose

Drop in `prompt-craft/<purpose>/` in your own module's resources, read it with `PromptCraft.text("<purpose>", "file.md")`, and add
the constants to `PromptResourceParityTest#pinned`. Resources resolve across the whole classpath, so
a module ships its own prompts without any other module knowing they exist.

Placeholders are `{{name}}`, substituted by `PromptCraft.text(purpose, file, vars)`. Prefer composing
whole files over threading many variables: a prompt with fifteen holes in it is code again.
