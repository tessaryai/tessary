# Users view

The Users view answers two questions about the end users of a customer's agent: which user IDs keep
coming back, and which issues came before more of them stopping than expected. It reads the
`user.id` producers already send, makes no model call, and changes nothing on the ingest path. Code:
`backend/analysis/.../endusers/` (the MCP tools are in `surfaces`' `mcp/`); the page is
`frontend/src/views/users/`. Install keys are in [config-keys.md](../reference/config-keys.md)
(`tessary.end-users.*`); tables are in [data-model.md](../reference/data-model.md) (`end_user_*`);
the product words, stage names and verdict table are in
[`product-glossary.md`](../../handbook/product-glossary.md) under *User ID* to *Verdict*. The public
page is [`docs/concepts/users.mdx`](../../docs/concepts/users.mdx).

## TL;DR

- Facts are stored; judgments are computed again every day. Only the fold and the learn step read
  traces, so the page keeps working after the traces are gone.
- An hourly hit sweep records which user IDs each finding's evidence touched. A daily job folds
  closed UTC days into facts keyed by a salted hash, reconciles hits, learns route classes, runs the
  judge as pure functions and writes one snapshot of codes and numbers.
- The judge is a fixed chain of stages. A page state that refuses withholds every number; seven
  guards, each with one install key, withhold a number or add a banner in narrower cases.
- An issue is a case lineage. Its verdict says what its numbers can claim, and only "evidence of
  loss" claims a loss.
- The readable ID text lives in one column and leaves with the ID's last trace. Hashed facts stay
  for the horizon, 400 days by default. No log line carries an ID or its key.

## One-pager

| Step | Runs | Reads | Writes |
|---|---|---|---|
| Hit sweep | hourly, job kind `end_user_hits`, one per project with new evidence | `finding_evidence`, two detection tables | `end_user_hit`, `end_user_hit_summary` |
| Fold | daily job `end_users`, stage 1 | settled, not deleted traces of the folded days | `end_user_day`, `end_user_day_null`, `end_user_version`, `end_user_alias_edge`, `end_user`, `end_user_fold_day` |
| Reconcile | stage 2 | evidence of open findings and of findings with evidence in the last 7 days | hit rows |
| Learn | stage 3 | live traces of the last 28 days | `end_user_route` classes and statistics |
| Judge | stage 4 | facts of the judge window (180 days) | `end_user_snapshot`, `end_user`, `end_user_issue`, `end_user_issue_hit`, `end_user_silence_event` |
| Horizon sweep | stage 5 | the horizon | deletes old facts; clears the text of IDs whose last trace is gone |

The daily job runs once per active project after 01:00 UTC for the last closed UTC day. Each stage is
safe to run again; `job.progress` holds the last stage done, and a retry starts after it. One job runs
at a time in a process, so the jobs hold at most one connection of the pool. Both kinds share the one
`job` table and claim through `LeasedJobSql`.

The read side (`read/EndUserController`, `EndUserReadService`) serves the snapshot: `/overview`,
`/issues`, `/issues/{caseId}`, `/issues/{caseId}/silent-ids`, `/ids`, `/cases/{caseId}` and
`/hit-counts` under `/api/orgs/{orgSlug}/projects/{projectSlug}/end-users`, plus
`POST /erasures`. No read runs the judge. `null` means not computed, never zero. The three MCP tools
(`get_end_users_overview`, `list_end_user_issues`, `get_end_user_issue`) return the same codes and
numbers. Every sentence the page shows is built in `frontend/src/views/users/usersWords.ts`; the
backend stores and serves codes.

## Keys and privacy

`facts/EndUserKeys` keys every fact row by the first 16 bytes of HMAC-SHA-256 over the trimmed ID,
with a random 32-byte salt per project. An empty or missing ID is the all-zero key. Days are UTC day
numbers. The salt sits in `end_user_project` as plain bytes: it stops a guessed ID from being checked
without database access, and it must work on a self-host where `SecretBox` is not configured.

The readable text is `end_user.id_text` and nowhere else. The horizon sweep sets it to null when the
ID's last trace is deleted, after the fold has saved the two things the judge still reads from it: the
shape signature (`facts/ShapeSignature`) and the account cluster hash. Retention deletes by start
time and keeps pinned traces, so an ID that still has a trace keeps its text. A trace TTL of 0 deletes
no trace and clears no text.

The horizon is the `job/EndUserHistoryHorizon` seam. Its open default, `TraceTtlHistoryHorizon`, is
the larger of the project's trace TTL and `history-days` (400); a TTL of 0 keeps facts for good. It is
not a retention data class, because another build can fix the TTL of every data class, and the facts
must still outlive the traces.

`POST /erasures` deletes every row of one user key. It needs a signed-in session and `ORG_MANAGE`.
A user ID is never in a URL path; the drawer's link to `traces?q=<id>` exists only while the text
does. Logs carry counts, the project ID, row counts and durations, and a failed stage logs only its
exception class, because a message can hold an ID.

## The fold

A run folds the last 7 closed days, every day after the last fold, and each older live day with a
trace created or rolled up since the last run (re-read 2 hours behind, because writers commit late).
The first run backfills from the first live trace day. Each day is replaced whole, in one
transaction. A day at the TTL edge is never replaced, and days that aged out before any run folded
them are marked as gaps, which the page names in a limit line.

A route is (trace name, call site), with `__unattributed__` for a trace with no call site. Per
(user key, day, route, version) the fold writes the trace count, the 32 smallest session hashes, a
24-bit hour mask, a 96-slot mask of 15-minute slots, up to 32 job keys (crc32 of the input preview
joined with the slot), and the counts with an input and with a parent. The version is in the key so
that the ID channel's per-version share is exact. Traces with no user ID go to `end_user_day_null`.
Alias edges record a session or thread in which one ID ends before another starts. The outage flag
on `end_user_fold_day` is set when every route falls under 30% of its 28-day median.

## Hits

The hit sweep stores every evidence role of each ref, joined to `COALESCE(span.user_id,
trace.user_id)`, with the trace's route and UTC start day. A session-only ref takes the user ID of the
session's earliest trace that has one, and the day of its last trace. Each hour it rebuilds every
finding with evidence newer than the watermark (re-read 2 hours behind), plus each open finding whose
hits come from a detection table, since those keep firing after their evidence stops. The first run
reads all live evidence. The daily reconcile catches a user ID a later batch filled in and evidence
that was removed.

Which rows count as hits is a rule picked when the rows are read, from `hits/EndUserHitSource`.
`OpenHitSource` covers the open detectors; another build adds a bean for its own kinds, and two
sources with a rule for one finding fail loudly.

| Detector | Hit rows | Reach | Complete |
|---|---|---|---|
| `tool_error` | `witness` | members | yes |
| `frustration` | `witness` | members | yes |
| `groundedness` | `witness`, one per trace | members | yes |
| custom per-span classifier (armed window, not faceted) | `member` | the call site's traffic | yes |
| `malformed_output` | rows of `malformed_output_detection`, with the detector's own filters | the call site's traffic | yes |
| `secret_leak` | rows of `secret_leak_detection`, with the facet, window and confidence filters | the call site's traffic | yes |
| `duration_drift`, `cost_drift` | `member` rows of the shifted window | the call site's traffic | yes (metric shift) |
| any other kind | `witness` when one exists, else `member`, `exemplar`, `changepoint` | members | no |

Malformed output and the secret-leak facet keep only 50 witnesses as evidence, which is why their
hits come from the detection tables. A metric shift is measured like every other issue; one with no
stored hit row has no user IDs hit and gets the verdict code `not_applicable` ("No hit user IDs").
An issue with a finding whose rule is not complete gets "Hits not complete" and no estimate, while
its guard is on.

An issue is a case lineage (`hits/IssueLineages`): a case and the earlier cases of the same cause.
A finding with no case is not an issue.

## Route classes

The learn step classes each route from its live traces of the last 28 days, by the first rule that
applies: C (never activity: mostly no user ID, or mostly child traces), A (paced by a person: it
follows earlier traces of the session with a person-like delay), S (on a schedule), M (a machine that
follows), C again (the app sends input and this route mostly has none), else B (one-shot or event).
A B route whose (ID, day) pairs mostly also have an A trace becomes A2 and counts like A. With no A
route, A2, B and M count as activity and the page prints "Weak activity basis". The schedule test
compares minutes of day across pairs of an ID's traces on different days, so it needs minutes, and
stays in the learn step on live traces. A route keeps its stored class while its statistic is within
0.05 of a cut.

## The judge

`judge/EndUserJudge` runs the stages below in this order over `judge.model` records, and no stage
reads a later one. Each logs its name, its row count and its duration.

| # | Stage | Class | Gives |
|---|---|---|---|
| 1 | routes | `Facts.storedRouteClasses` | the learned classes |
| 2 | aliases | `Aliases` | a representative for IDs that one session or thread joins; a member's days are its representative's |
| 3 | day series | `DaySeries` | per ID: active days, automation days, set-up days, left-censored flag |
| 4 | flags | `IdentityFlags` | keys, services, odd shapes, session-token copies |
| 5 | ID groups | `IdGroups` | groups and accounts; which IDs are covered |
| 6 | ID channel | `IdChannel` | ID loss (traffic that arrives without an ID) and ID change events |
| 7 | tracer | `Tracer` | whether repeating jobs went on under a new ID |
| 8 | page state | `PageStateRule` | the page state and its reason |
| 9 | issue hits | `IssueHits` | hit days per ID for each lineage |
| 10 | shared silence | `SharedSilence`, `SilenceClock` | silences many regular IDs share, and the IDs held as a possible break |
| 11 | stop odds | `StopCurves` | `p_stop` per ID against the app's own return record |
| 12 | past check | `PastCheck` | how often IDs named at past dates stayed away; the return cycle `F` |
| 13 | states | `States`, `Stages`, `StopStageRule`, `SeasonLevel`, `PageNotes` | each ID's state, grade and stage; stage counts; the stop stage; banners and limit lines |
| 14 | issues | `Estimate`, `OwnNull`, `PopulationReading`, `Verdicts`, `CoFiring`, `IssueRank`, `RetentionCurve` | each issue's estimate, verdict, rank, group and curve |

The page state refuses at once and relaxes only after two equal runs (the memory of the last two is
on `end_user_project`). A refused page skips the estimate, so every issue is `page_refused`. Above
`max-id-days` user-ID days in the window the job writes a snapshot with page state `not_computed`;
the fold and the hit sweep go on.

The three stages are Active (state `active` with no substate, and `new`), Silent, not judged
(substate `overdue`, held IDs, and grade `unattended`) and Probably stopped (state `stopped`, named,
with an expected count that adds `p_stop` over covered IDs). Set-aside IDs and IDs that cannot be
followed after an ID change are in no stage.

`Verdicts.decide` tests the verdicts in this order and stops at the first that applies:
`page_refused`, `not_applicable`, `hits_not_complete`, `history_too_short`, `cannot_tell_yet`,
`too_few_ids` (no estimate), `cannot_measure_yet`, `too_few_ids`, `reaches_nearly_all`,
`not_comparable`, `always_present`, `few_accounts`, `evidence_of_loss`, `moved_not_added`,
`no_measurable_loss`, `not_enough_evidence`. "Evidence of loss" needs a lower bound above zero, a
rate above the issue's own random draws, a large enough loss and no replaced IDs, and the second
contrast (`PopulationReading`, the stops of all user IDs on the reached routes before and after the
first evidence) must not contradict it. It contradicts the claim only when it has enough earlier
trials and its upper bound is well under the loss; a claim it contradicts is `moved_not_added`. With
no population reading, or too few earlier trials, the claim stands. Two issues whose shown hit traces overlap by half form one group.

The judge ports a reference program. Three frozen scenarios run it end to end in
`EndUserJudgeReferenceTest`, and the test states where its expected values come from. The port reads
differently from the reference in a few known ways: the schedule test reads the first 150 traces per
(route, ID) in the live 28 days, session hashes and job keys are capped at 32 a row, a day at the TTL
edge is never folded again, the window is 180 days, and `t0` is the stored history start day.

## Guards and their keys

Each guard withholds a number or adds a banner. A guard that is off removes only that guard; the
rule behind it stays. All seven default to true, under `tessary.end-users.guards.*`.

| Key | When it acts | Effect |
|---|---|---|
| `not-judged-without-past-check` | the past check has no named-right share yet | stop stage "not judged yet"; without the guard, only fewer than 4 past-check dates do this |
| `cannot-tell-yet` | the return cycle `F` reaches its 42-day clamp | stop stage and verdict "cannot tell yet"; adds the repeat-contact limit line |
| `history-too-short` | the horizon is under 120 days, or (for issues) the user ID history is under 42 days | stop stage and verdict "history too short" |
| `hits-not-complete` | an issue holds a finding whose hit rule is not complete | verdict "hits not complete", no estimate |
| `mixed-id-formats` | a format that holds a covered ID has 30% or more one-day IDs | stop stage "mixed ID formats"; the one-day limit line is never cut |
| `findings-fire-together` | two shown issues share half of their hit traces | they show as one group |
| `outage-banner` | a folded day has every route under 30% of its 28-day median | an outage banner naming the day |

Three cautions have no key, because turning them off would only remove a warning: the "A break or a
loss; not yet known" banner text, the heavy IDs named in a refusal, and the "user ID values; identity
not checked yet" tooltip shown before the first snapshot.
