# Tests for the bundle-contract surface

These began as tests for **public** code from a **private** repo, deliberately — and half of that
design is still live.

`validate.py` is the bundle validator — 1,200-odd lines that decide whether a `.tessary/` bundle is
well-formed before it ever reaches this platform. It was authored in the public
[`tessaryai/plugins`](https://github.com/tessaryai/plugins) repo and vendored here; since the plugin
dropped its synthesis machinery (v0.23.0), the validator is **platform-owned** and this directory is
its home. The observer is the bundle's writer now, so its validator lives beside it: the E2B
analyzer template bakes `validate.py` + `pipeline_io.py` from here
(`sandbox-runner/agent-sandbox/build.ts`), and the in-VM `tessary-evals-validate` wrapper runs
this exact copy.

The **docs** half of the contract is still vendored from the public repo — `output_format.md`,
`AUTHORING_CONTRACT.md`, `grader.schema.json`, `CHANGELOG.md` (see
`scripts/lib/vendored-plugin-files.sh`). The plugins repo deliberately runs no PR CI: a workflow
there would put its configuration, its logs, and its fixtures in the open. So the enforcement lives
here: `scripts/check-vendored-plugin.sh` runs these tests and diffs the vendored docs against the
plugin's live `main` on every CI run — reading a public repo from private CI leaves nothing behind
in the public repo.

**Scope.** These cover the contract surface this platform actually consumes — chiefly the
code-tracked facts (`output_schema`, `tools`, `capabilities`) whose semantics the bundle format
pins. They are not a general test suite for the validator, and shouldn't grow into one: anything
that doesn't affect what the platform imports or the observer authors doesn't need a test here.

Run them with `bash scripts/check-vendored-plugin.sh`, or via `task check`.
