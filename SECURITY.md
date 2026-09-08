# Security policy

## Reporting a vulnerability

**Email security@tessary.ai.** That is the channel that works today. It is private,
maintainer-only, and not auto-forwarded anywhere.

**GitHub's private vulnerability reporting is not a channel here yet.** It is a
public-repository feature and this repository is private, so the Security tab has no
"Report a vulnerability" button to press. It becomes available once the repository is
public and a maintainer enables it, and at that point it is as good as the email address:
a private advisory thread with maintainers that never becomes a public issue while it's in
progress. Until you can actually see that button, it hasn't been turned on — use the email
address instead of falling back to a public issue.

Do not open a public issue for a suspected vulnerability. If you're unsure whether something
rises to that level, report it privately anyway; we'll downgrade it to a public issue
ourselves if it turns out not to be one.

## Response

**First response within three business days.** That's an acknowledgement naming who is
looking and what we need from you — not a fix, and not a commitment to patch on any
timeline. Fixes are best effort. This is open source, and a clock we keep is worth more
than a shorter one we miss.

This is a wider window than the one business day we commit to for partner support. That is
deliberate: partner support is a paid commitment, and this inbox is open to anyone.

The inbox is owned by **two named people, Akhil Varma and Nidhi Nair** — the same "not the
team" convention partner support runs on, not a second ownership model invented for this file.
They alternate monthly: one is primary for the month and the other is backup. The primary sends
that acknowledgement. If a report has sat for three business days without one, the backup picks it
up — that is the point of naming two people rather than one, and of a monthly swap rather than a
weekly rota a company this size cannot staff. Whoever is on it triages; anything that looks
exploitable is escalated immediately rather than sitting for the rest of the month. The same two
own a red run of this repository's secret scan (`.github/workflows/secret-scan.yml`, which runs
gitleaks over the checkout against `.gitleaks.toml`; it is dispatch-only, so a run is something a
human starts, not a weekly cron): its failures go to security@tessary.ai and are the month's
primary's to clear, on a one-business-day clock.

## Suppressing a known advisory

Automated dependency scanning (`scripts/check-dependency-audit.sh`, run on demand by a human with their
own NVD API key — it is not a CI job, and today it is the only dependency scanning that actually
runs: Dependabot alerts are switched off for this repository, and the CodeQL workflow is authored
but cannot run, because code scanning needs GitHub Advanced Security on a private repository.
Both become available when this repository goes public, and arming them is part of that cutover)
sometimes flags something that isn't worth fixing right
away — a transitive dependency with no runtime path, a fix that doesn't exist yet, or a risk the
team has decided to accept for a stated reason. Add an entry to
`.github/dependency-suppressions.yml` rather than letting the finding sit as a recurring red run
nobody acts on: one entry per advisory id, with a one-line reason and an expiry date. The same
rota above signs off on a suppression as on a vulnerability report — it is the same judgment
call, just made proactively instead of in response to an external report. An entry with no
expiry review date is exactly the kind of broken window this file is meant to avoid,
so the script drops expired entries automatically and the advisory re-fires as if unsuppressed.

Dependabot's own `ignore:` rules (in `.github/dependabot.yml`) are the parallel mechanism for
version-update PRs specifically — use those to stop Dependabot from proposing a bump the team has
already decided not to take, as distinct from suppressing a vulnerability finding.

## Supported versions

There is no version matrix yet — only one line exists. The latest commit on `main` (or the
most recent tagged release, once releases exist) is the only supported version. A report
against anything older is still welcome; we may just ask you to reproduce it against current
`main` first.
