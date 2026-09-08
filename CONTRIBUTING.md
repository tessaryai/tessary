# Contributing to Tessary

This guide explains how to open a pull request against Tessary, what to expect from review, and the license and AI-disclosure terms that apply to your contribution.

## You don't need to open a pull request

A detailed issue is a valid contribution on its own. If you've found a bug, hit a confusing edge case, or have a well-reasoned feature request, open an issue and describe it clearly: what you expected, what happened instead, and how to reproduce it. You don't need to write code or submit a pull request for it to count.

## Before you open a pull request

Check for an existing issue first. If there isn't one, open one and use it to align on the approach before writing a lot of code: this avoids a pull request getting closed as out of scope after the work is already done.

Keep the change focused. A pull request that mixes an unrelated refactor with the actual fix is harder to review and more likely to stall.

Follow the existing patterns in the area you're touching, and run the project's checks locally before pushing (see [`devdocs/guides/local-dev.md`](./devdocs/guides/local-dev.md) and `task check`).

Add tests for new behavior. Describe your test strategy in the pull request description: what you tested, how, and what edge cases you considered.

## What to expect

We read every pull request, but response time depends on load. We may close a pull request that's out of scope, that would add long-term maintenance burden the change doesn't justify, or that goes stale without response. You're always welcome to reopen with updates. Security-sensitive or critical-path changes may take longer, or get escalated to a specific reviewer.

## License

By submitting a pull request, you agree that your contribution is licensed under this repository's Apache License 2.0, on the same terms as the rest of the codebase. This is sometimes called "inbound equals outbound." We don't require a separate Contributor License Agreement or Developer Certificate of Origin sign-off: submitting the pull request is the grant.

## AI-assisted contributions

Much of this codebase is written with AI assistance, and we don't expect contributors to work differently. A few ground rules apply.

**You own what you submit.** Understand your change, test it, and be able to explain how it works and why it's correct without re-prompting an LLM. AI makes it easier to skip that work: don't skip it.

**Prove it works.** "It compiles" or "tests pass" isn't enough on its own. Describe what you actually verified in the pull request description.

**Disclose it.** If an agent authored or meaningfully co-authored the change, say so in the pull request description. This isn't a black mark: it helps reviewers calibrate what to look at closely.

**AI assistance doesn't change the license grant above.** Whoever submits the pull request makes the Apache 2.0 grant and is responsible for having the right to do so, including for any AI-generated content it contains.

We'll close a pull request under this policy if it shows no sign of having been run, tested, or understood by the person submitting it.

## Reporting a security issue

Don't open a public issue for that. See [`SECURITY.md`](./SECURITY.md).
