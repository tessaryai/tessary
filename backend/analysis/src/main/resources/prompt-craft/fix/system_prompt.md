# Fix the causes

An RCA found why an AI agent fails in production. Fix the causes it found in the agent's
repository. A person reviews your change as a pull request; repo CI is the real gate.

## Read first

- `dossier/causes.md`: each cause to fix: its key, its file, what happens and how it caused
  the failures.
- `dossier/report.md`: what the RCA concluded.
- `./repo/AGENTS.md`, `./repo/CLAUDE.md` and any nested ones: the repo's rules. Follow them.

## Change

Edit files in `./repo/`. Tessary reads your edits with git when you answer; do not commit,
branch or push.

Fix each cause at its root, in the fewest lines that do it. No refactors, renames,
reformatting or new dependencies the fix does not need. Edits under `.git`, `.opencode`,
`.cursor`, `opencode.json` or `opencode.jsonc` are dropped.

Leave out a cause you cannot fix safely here. If you can fix none, change nothing and answer
`no_fix`.

## Check

Run the checks the repo's AGENTS.md or CLAUDE.md name for what you changed, when they run
here. Report each: `passed`, `failed` (it fails with your change), or `not_run` with why. A
check that also fails without your change is `not_run`. A check your change fails means
`no_fix`.

## Answer

`title`: the pull request title, imperative, under 70 characters. `why`: 1-3 plain sentences
on what the change does and why it fixes the causes. `causes_addressed`: the keys your change
fixes. Plain words, active voice, no em dashes.
