You are authoring one **agentic** grader. It produces a **binary verdict** by running an agent in a sandbox — running `git diff`, running tests, exploring files — instead of one judge call reading text.

Reach for `kind: agentic` only when the verdict requires inspecting **the result the agent under test produced**, not just the output text. "Did the repo end up correct? Does the `git diff` implement the request? Do the tests pass?" A static judge can't decide those from the reply alone. If a judge reading the output text *can* decide, use `llm_judge` — it's cheaper and has no drift.

**You only emit the spec. tessary (the runner) executes it.** The plugin never runs an agentic grader, and `validate.py` never runs it — it only checks the spec's shape.

The authoritative `agent_spec` shape and the agentic-grader invariants are in `AUTHORING_CONTRACT.md` § "Agentic graders" + `contract/grader.schema.json` — don't restate them. Here is a well-formed example to author against:

```yaml
kind: agentic
agent_spec:
  harness: opencode
  sandbox:
    image: <string>              # e.g. the project's CI image, or opencode/base:latest
    network: none                # most restrictive the task allows
  allowed_tools: [bash, read, git]
  task_prompt: |                 # self-contained; ends in ONE binary decision
    Check out the session's repo at the final turn. Run `git diff <base> <head>` and decide
    whether the change correctly implements the user's request from the conversation. Run the
    test suite with `<cmd>`; a failing suite is an automatic FAIL.
  verdict_contract: |            # how the agent signals its result so the runner can parse it
    Print exactly one line `VERDICT: PASS` or `VERDICT: FAIL` as the final line of output.
  budgets: { max_turns: 20, max_cost_usd: 0.50, timeout_s: 600 }
```

The judgment calls that example doesn't make for you:

- **Binary only** — `expected_verdict` is `pass` / `fail` (or `not_applicable` with `applies_when`); agentic never scores 1–5, so never emit `expected_level`.
- **No judge body** — the `task_prompt` *is* the grading instruction; don't also emit `judge_prompt` / `rubric` / `deterministic_check` / `execution_spec`.
- **Least privilege** — pick the most restrictive `sandbox.network` the task allows and the minimal `allowed_tools`. A grader that needs no network MUST set `network: none`; one that only reads files should not request `git`.

## Writing the `task_prompt`

- **Self-contained.** The agent gets the conversation/output under test plus the sandbox — nothing else. State where the artifact is (the session repo, the diff range) and what "correct" means concretely.
- **Ends in one binary decision.** Not "evaluate the change" — "decide PASS if the diff implements the request AND the tests pass, FAIL otherwise."
- **Name the commands.** If the verdict depends on tests, give the exact test command. Don't make the agent guess the build system.
- **Match the `verdict_contract`.** The task_prompt's final instruction and the verdict_contract must agree on exactly how the result is emitted (e.g. the `VERDICT: PASS|FAIL` final line).

> Per-grader `self_tests` were removed in contract v7 — do not emit them. A grader's behavior is
> validated platform-side against golden datasets (real captured spans labeled per grader).
