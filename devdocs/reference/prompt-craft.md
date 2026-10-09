# Prompt craft

Every prompt the platform sends to a model lives as markdown under
`prompt-craft/<purpose>/`, in the resources of the module that owns it. One directory per purpose.

| module · purpose | who reads it | what it is for |
|---|---|---|
| `analysis` · `triage/` | `classifier/finding/BehaviorTriageEngine` | Layer-2: is a detector's claim about production traffic true? |
| `analysis` · `rca/` | `rca/AgenticRcaEngine` | Layer-3: why does a confirmed finding happen? One prompt for every classifier. |

### The RCA files

| file | what it holds | how it reaches the agent |
|---|---|---|
| `rca/prompt.md` | The one investigative prompt, with `{placeholders}` | The prompt, filled by `AgenticRcaEngine.buildPrompt` |
| `rca/repo_present.md` | The repo is at HEAD; the code that matters is the code live at the onset | In place of `{repo}` when a repo is cloned. Its `{onset_commit}` is filled by the sandbox after the clone |
| `rca/repo_absent.md` | No repo; the code side is unread | In place of `{repo}` otherwise |
| `rca/baseline_present.md` | The `baseline` rows are the traffic before the onset | In place of `{baseline}` when the evidence has `baseline` rows; nothing otherwise |
| `rca/tools.md` | The MCP tools and how to page the evidence | `dossier/tools.md` |
| `rca/response_schema.json` | The output contract, including what `high` and `medium` mean | The run's JSON schema |
| `rca/methods/<classifier>.md` | One built-in classifier's instrument: what it measures, how its evidence reads, its quirks, where its code lives | `dossier/method.md`, chosen by classifier key. The two drift classifiers share `metric_drift.md`. A user or regex classifier gets none, and the prompt does not name the file |

A method file states facts about the instrument only. It never lists causes, examples of causes, or look-alikes: a list of candidates anchors the agent on them, and a line telling it to look further does not undo that. `RcaMethodFilesTest` holds each file to the four sections and keeps it off `get_finding`.

## What belongs there, and what does not

Only prose. Which paragraph applies to a given finding is **logic**, and it stays in the engine,
which still chooses and concatenates. `PromptCraft`'s javadoc owns the full why; the rule of thumb:

> If a human would edit it to change **what** the model reads, it is prose.
> If a human would edit it to change **when** the model reads it, it is code.

`rca/response_schema.json` keeps its own extension: it is data the prompt carries, not prose and not
control flow.

## Changing one

These strings are what the model reads, so they are pinned byte-for-byte by
`PromptResourceParityTest` against goldens in `src/test/resources/prompt-golden/`. A text the engine
loads into a constant is pinned through that constant; an RCA method file, loaded per run, is
pinned through its resource. Editing a file here fails that test until you re-capture:

    mvn -pl analysis -am test -Dtest=PromptResourceParityTest \
        -Dsurefire.failIfNoSpecifiedTests=false -Dprompt.golden.capture=true

Re-capture only when you **meant** to change the prompt. Running it to make a red test go green is
exactly the failure the pin exists to prevent — it turns an accidental whitespace edit into a
silent change in how a lane rules. Capture writes goldens and never deletes one: when you remove a
prompt file, delete its golden too, or `every_golden_belongs_to_a_pinned_text` fails.

The `evaluation` module used to also ship `prompt-craft/grader-judge/` — manifest-driven, composed by
`CraftLibrary#compose` rather than read file by file. The module, the craft assets, and the reference
document that described the path they served are gone.

## Adding a purpose

Drop in `prompt-craft/<purpose>/` in your own module's resources, read it with `PromptCraft.text("<purpose>", "file.md")`, and add
each file to `PromptResourceParityTest#pinned`, by its constant or by its resource. Resources resolve across the whole classpath, so
a module ships its own prompts without any other module knowing they exist.
