// SPDX-License-Identifier: Apache-2.0
/*
 * Every sentence of the Users view, from the numbers the backend sends. Expected strings are written by hand: the board
 * and brief texts word for word, the spec and logic.py texts in user ID words, and the copy written for this unit. The
 * bugs worth catching: a number printed raw (a share as 0.94, 1014 with no comma, a range high first), a day number
 * printed as a count, a number the backend left out printed as "undefined" or a dash, a count that says "users" where
 * it counts user IDs, an em dash, and a curly apostrophe.
 */
import { describe, expect, it } from "vitest";
import type {
  EndUserBanner,
  EndUserIdRow,
  EndUserIssueNumbers,
  EndUserLimitCode,
  EndUserOverview,
  EndUserVerdict,
} from "../../api/types";
import {
  BANNERS,
  C118_CASE_BLOCK,
  C118_NUMBERS,
  C118_SILENT_IDS,
  C121_NUMBERS,
  C123_NUMBERS,
  C125_NUMBERS,
  C127_NUMBERS,
  NO_DIAGNOSTICS,
  PAGE_STATES,
  REFUSED,
  STOP_STAGES,
  STOPPED_IDS,
  issueNumbers,
  overview,
  utcDay,
} from "../../test/usersFixtures";
import * as words from "./usersWords";
import {
  HELD_STATE,
  NO_CASES,
  NO_ID_TEXT,
  activeCaption,
  activeHeader,
  bannerText,
  caseCountLine,
  chartAria,
  countedLine,
  dayLabel,
  funnelAria,
  groupNote,
  groupRefs,
  groupTitle,
  hitChip,
  hitChipTooltip,
  idState,
  idWhy,
  issueHeadline,
  issueHowCounted,
  issueNote,
  issueRowLabel,
  limitText,
  lossLabel,
  lossText,
  lostShort,
  more,
  moreSilent,
  noClearLoss,
  openCase,
  pageNotice,
  percent,
  railHeader,
  rowChartAria,
  silentHeader,
  silentNowCount,
  silentState,
  stageTab,
  stopStage,
  stoppedHeader,
  thousands,
  tooFew,
  trustLine,
  verdictWord,
  weekTick,
  weeksAgo,
  whyStopped,
} from "./usersWords";

const banner = (
  code: EndUserBanner["code"],
  numbers: Record<string, number>,
  routes: string[] = [],
): EndUserBanner => ({
  code,
  numbers,
  routes,
});
const row = (over: Partial<EndUserIdRow>): EndUserIdRow => ({
  id_text: "usr_1",
  state: "active",
  substate: null,
  grade: null,
  days_silent: 0,
  p_stop: null,
  hit_by: [],
  ...over,
});
const refused = (over: Partial<EndUserOverview>) => overview({ ...REFUSED, ...over });
const partial = (over: Partial<EndUserOverview>) =>
  overview({
    page_state: "partial_coverage",
    reason_numbers: { covered_share: 0.95, ident_share: 0.9 },
    ...over,
  });

/** Every page state notice: board texts first, then the copy written for this unit. */
const NOTICES: [string, EndUserOverview, { title: string; text: string }][] = [
  [
    "no user ID across all history",
    PAGE_STATES.noUserId,
    {
      title: "No user ID",
      text: "Only 12% of all traces carry a user ID. Send user.id on every trace, the same for one person across sessions.",
    },
  ],
  [
    "no user ID in the last 28 days",
    PAGE_STATES.noUserIdLast28,
    {
      title: "No user ID",
      text: "Only 12% of traces in the last 28 days carry a user ID. Send user.id on every trace, the same for one person across sessions.",
    },
  ],
  [
    "too early",
    PAGE_STATES.tooEarly,
    {
      title: "Too early",
      text: "14 days of user ID history (21 needed) and 16 user IDs (20 needed). The page fills in on its own.",
    },
  ],
  [
    "user IDs not reliable: keys",
    PAGE_STATES.heavyIds,
    {
      title: "User IDs not reliable: keys",
      text: "22 user IDs carry the traffic of more than one user (40% of ID-days: 31 sessions a day, 18 hours a day). The other user IDs cannot be taken as single users.",
    },
  ],
  [
    "user IDs replaced",
    PAGE_STATES.idsReplaced,
    {
      title: "User IDs not reliable: replaced",
      text: "Of 35 user IDs that ended and had a repeating job, 34 went on under a new user ID. User IDs are replaced, so a silent user ID is not a lost user.",
    },
  ],
  [
    "partial coverage",
    PAGE_STATES.partialCoverage,
    {
      title: "Partial coverage",
      text: "Numbers use covered user IDs only. Set aside: 31 that run like a service, 11 that look like keys. A banner names each reason.",
    },
  ],
  [
    "not computed",
    PAGE_STATES.notComputed,
    {
      title: "Not computed",
      text: "This project is over the size budget for the daily job. Coverage and user IDs hit still show.",
    },
  ],
  [
    "too early with few user IDs",
    refused({
      page_state: "too_early",
      page_state_reason: "few_ids",
      reason_numbers: {
        span_days: 30,
        ids: 12,
        span_days_needed: 21,
        ids_needed: 20,
      },
    }),
    {
      title: "Too early",
      text: "30 days of user ID history (21 needed) and 12 user IDs (20 needed). The page fills in on its own.",
    },
  ],
  [
    "keys with no session days",
    refused({
      page_state: "ids_unreliable",
      page_state_reason: "heavy_ids",
      reason_numbers: { ids: 22, id_day_share: 0.4, hours_per_day: 17.6 },
    }),
    {
      title: "User IDs not reliable: keys",
      text: "22 user IDs carry the traffic of more than one user (40% of ID-days: 18 hours a day). The other user IDs cannot be taken as single users.",
    },
  ],
  [
    "keys with no day numbers",
    refused({
      page_state: "ids_unreliable",
      page_state_reason: "heavy_ids",
      reason_numbers: { ids: 22, id_day_share: 0.4 },
    }),
    {
      title: "User IDs not reliable: keys",
      text: "22 user IDs carry the traffic of more than one user (40% of ID-days). The other user IDs cannot be taken as single users.",
    },
  ],
  [
    "partial coverage after an ID loss, nothing set aside",
    partial({ page_state_reason: "id_loss", set_aside: {} }),
    {
      title: "Partial coverage",
      text: "Numbers use covered user IDs only. A banner names each reason.",
    },
  ],
  [
    "partial coverage with no set-aside counts",
    partial({ page_state_reason: "some_ids_replaced", set_aside: null }),
    {
      title: "Partial coverage",
      text: "Numbers use covered user IDs only. A banner names each reason.",
    },
  ],
  [
    "partial coverage by a low user ID share",
    partial({
      page_state_reason: "low_id_share",
      reason_numbers: { covered_share: 0.95, ident_share: 0.64 },
      set_aside: { service: 4 },
    }),
    {
      title: "Partial coverage",
      text: "Numbers use covered user IDs only. Set aside: 4 that run like a service. Only 64% of all traces carry a user ID.",
    },
  ],
  [
    "partial coverage with every set-aside reason, largest first",
    partial({
      page_state_reason: "ids_set_aside",
      set_aside: {
        odd_shape: 3,
        token_link: 3,
        non_returning_group: 9,
        machine_only: 2,
        aggregate: 1,
        service: 5,
      },
    }),
    {
      title: "Partial coverage",
      text: "Numbers use covered user IDs only. Set aside: 9 in groups that do not return, 5 that run like a service, 3 with an odd ID format, 3 that copy the session token, 2 with machine traffic only, 1 that look like keys. A banner names each reason.",
    },
  ],
  [
    "too early after a recent ID change",
    refused({
      page_state: "too_early",
      page_state_reason: "recent_id_change",
      reason_numbers: {
        span_days: 40,
        ids: 300,
        span_days_needed: 21,
        ids_needed: 20,
      },
    }),
    {
      title: "Too early",
      text: "User IDs were changed recently, so old and new user IDs cannot be told apart yet. The page fills in on its own.",
    },
  ],
  [
    "user IDs not seen again",
    refused({
      page_state: "ids_unreliable",
      page_state_reason: "ids_not_seen_again",
      reason_numbers: {
        covered_share: 0.6,
        token_link_share: 0.1,
        return_later: 0.02,
      },
    }),
    {
      title: "User IDs not reliable: not seen again",
      text: "Most user IDs are not seen again after their first days, so a silent user ID is not a lost user. Send the same user.id for one person across sessions.",
    },
  ],
  [
    "user IDs copy the session token",
    refused({
      page_state: "ids_unreliable",
      page_state_reason: "ids_copy_session_token",
      reason_numbers: { covered_share: 0.05, token_link_share: 0.8 },
    }),
    {
      title: "User IDs not reliable: session tokens",
      text: "User IDs change with every session, like a session token, so a silent user ID is not a lost user. Send the same user.id for one person across sessions.",
    },
  ],
  [
    "collecting",
    refused({ page_state: "collecting", as_of: null }),
    {
      title: "Collecting history",
      text: "The first daily run has not finished yet. The page fills in on its own.",
    },
  ],
];

/** Every banner shape: board texts first, then logic.py texts, then the copy written for this unit. */
const BANNER_TEXTS: [string, EndUserBanner, string][] = [
  [
    "group or key IDs",
    BANNERS.groupOrKeyIds,
    "18 user IDs look like groups or keys (9% of ID-days). Counts on this page are user IDs. One ID can hold many people, and people who leave inside an ID that stays active cannot be seen.",
  ],
  [
    "shared silence, held",
    BANNERS.sharedSilence,
    "From Nov 10, 31% of regular user IDs went silent in the same week (usual: 9%). 212 user IDs are held as a possible break until Dec 8: between 40 and 118 of them have stopped. A break or a loss; not yet known.",
  ],
  [
    "ID loss on one route",
    BANNERS.idLoss,
    "Since Nov 3, 38% of the traffic on companion.reply arrives without a user ID (before: 2%). About 60 user IDs that look stopped are probably still active. Stop counts after this date are too high until the user ID is sent again.",
  ],
  [
    "season level",
    BANNERS.seasonLevel,
    "User IDs that no issue touched use the app at 72% of their usual rate since about Nov 8. This can be a season or a problem that no finding covers.",
  ],
  [
    "cannot be named yet",
    banner("cannot_be_named_yet", STOP_STAGES.cannot_be_named_yet.numbers),
    "About 230 user IDs have probably stopped. Only 58 can be named, because one silence is weak evidence here (the most it proves: 64%).",
  ],
  [
    "ID loss on no named route",
    { ...BANNERS.idLoss, routes: [] },
    "Since Nov 3, 38% of the traffic arrives without a user ID (before: 2%). About 60 user IDs that look stopped are probably still active. Stop counts after this date are too high until the user ID is sent again.",
  ],
  [
    "ID loss on two routes",
    { ...BANNERS.idLoss, routes: ["companion.reply", "companion.plan"] },
    "Since Nov 3, 38% of the traffic on companion.reply and companion.plan arrives without a user ID (before: 2%). About 60 user IDs that look stopped are probably still active. Stop counts after this date are too high until the user ID is sent again.",
  ],
  [
    "ID loss on three routes",
    {
      ...BANNERS.idLoss,
      routes: ["companion.reply", "companion.plan", "companion.recall"],
    },
    "Since Nov 3, 38% of the traffic on companion.reply, companion.plan, and companion.recall arrives without a user ID (before: 2%). About 60 user IDs that look stopped are probably still active. Stop counts after this date are too high until the user ID is sent again.",
  ],
  [
    "ID change with its first day",
    banner("id_change", { first_day: utcDay("2026-11-03"), cannot_follow: 12 }),
    "User IDs were changed (first on Nov 3). 12 user IDs that cannot be followed are not counted as stopped.",
  ],
  [
    "ID change with no first day",
    banner("id_change", { cannot_follow: 12 }),
    "User IDs were changed. 12 user IDs that cannot be followed are not counted as stopped.",
  ],
  [
    "tracer",
    banner("tracer", { ended: 35, continued: 9 }),
    "Of 35 user IDs that ended and had a repeating job, 9 went on under a new user ID. A silent user ID may be a person who is still here.",
  ],
  [
    "shared silence that came back",
    banner("shared_silence", { returned_day: utcDay("2026-11-20") }),
    "The user IDs that went silent together came back from Nov 20 (a break).",
  ],
  [
    "shared silence that did not come back",
    banner("shared_silence", { silent_since_day: utcDay("2026-10-13") }),
    "User IDs that went silent together did not come back in 4 weeks; they now count like any other silent user ID (silent since Oct 13).",
  ],
  ["page refusal", banner("page_refusal", { ident_share: 0.12 }), "Numbers are held back; the page state says why."],
  [
    "outage with its drop",
    banner("outage", { day: utcDay("2026-11-12"), days: 1, drop_share: 0.64 }),
    "Traces fell by 64% on Nov 12 on every route; silences that start then may be a tracing outage.",
  ],
  [
    "outage with no drop",
    banner("outage", { day: utcDay("2026-11-12"), days: 2 }),
    "Traces fell on Nov 12 on every route; silences that start then may be a tracing outage.",
  ],
];

/** Every limit line: the spec and logic.py texts in user ID words, then the copy written for this unit. */
const LIMITS: [EndUserLimitCode, Record<string, number>, string][] = [
  [
    "group_or_key_ids",
    { ids: 18, id_day_share: 0.09 },
    "18 user IDs look like groups or keys (9% of ID-days). Counts on this page are user IDs. One ID can hold many people, and people who leave inside an ID that stays active cannot be seen.",
  ],
  [
    "tracer_cannot_test",
    {},
    "This app has no repeating jobs that could show a change of user IDs. The checks on this page confirm user IDs, not people.",
  ],
  [
    "few_covered_ids",
    { ids: 412 },
    "This app has 412 user IDs. An issue needs about 30 comparable hit user IDs, and verdicts on small apps can change from week to week.",
  ],
  [
    "cannot_be_named_yet",
    STOP_STAGES.cannot_be_named_yet.numbers,
    "About 230 user IDs have probably stopped. Only 58 can be named, because one silence is weak evidence here (the most it proves: 64%).",
  ],
  [
    "named_right_low",
    { named_right: 0.71, named_found: 0.55 },
    "The page names only 55% of the user IDs that stay away; the stop stage gives the expected count.",
  ],
  [
    "named_found_low",
    { named_found: 0.55 },
    "The page names only 55% of the user IDs that stay away; the stop stage gives the expected count.",
  ],
  [
    "no_anchor",
    { days_needed: 14 },
    "The stop odds are not yet checked against this app's past (about 14 more days needed).",
  ],
  ["lenient_basis", {}, "Weak activity basis: traffic that may be automatic counts as activity."],
  [
    "unattended_share",
    { ids: 140 },
    "140 user IDs are unattended or automation only, more than 10% of covered user IDs.",
  ],
  [
    "one_day_share",
    { one_day_share: 0.41 },
    "41% of user IDs are seen on one day only; some are guests, not lost users.",
  ],
  ["return_cycle_too_long", { days: 40 }, "A return cycle longer than 40 days cannot be learned from this history."],
  ["repeat_contact", {}, "On this app a repeat contact may be the failure, so a return is not read as health yet."],
  [
    "facts_not_kept",
    { from_day: utcDay("2026-10-02"), to_day: utcDay("2026-10-05"), days: 4 },
    "Activity from Oct 2 to Oct 5 was not kept.",
  ],
  [
    "named_right_low",
    { named_right: 0.71 },
    "User IDs named as stopped at past dates stayed away in only 71% of cases; the stop stage gives the expected count.",
  ],
  ["no_anchor", { days_needed: 0 }, "The stop odds are not yet checked against this app's past."],
  ["id_loss", { ids: 75 }, "75 user IDs may look stopped because some of their traffic arrives without a user ID."],
  ["id_change", { ids: 12 }, "12 user IDs were changed and cannot be followed; they are not counted as stopped."],
  [
    "shared_silence",
    { ids: 212 },
    "212 user IDs went silent in the same week as many others, so their stops are less certain.",
  ],
  ["account_event", {}, "User IDs of one account left together, so many stops here may be one event."],
  [
    "facts_not_kept",
    { from_day: utcDay("2026-10-02"), to_day: utcDay("2026-10-02"), days: 1 },
    "Activity on Oct 2 was not kept.",
  ],
  ["imports_outside_window", {}, "Imported traces dated outside the live window are not counted."],
];

const FEW_ACCOUNTS = issueNumbers({
  verdict: "few_accounts",
  user_ids_hit: 52,
  compared: 44,
  lost: 8,
  low: 0,
  high: 19,
  diagnostics: {
    ...NO_DIAGNOSTICS,
    accounts: 3,
    estimated: 40,
    account_lines: [
      {
        account: 1,
        ids: 12,
        hit: 5,
        silent: 3,
        silent_since_day: utcDay("2026-11-03"),
      },
      { account: 2, ids: 8, hit: 4, silent: 0, silent_since_day: null },
    ],
  },
});
const ONE_ACCOUNT = issueNumbers({
  ...FEW_ACCOUNTS,
  diagnostics: {
    ...NO_DIAGNOSTICS,
    accounts: 1,
    estimated: 40,
    account_lines: [
      {
        account: 1,
        ids: 30,
        hit: 20,
        silent: 1,
        silent_since_day: utcDay("2026-11-03"),
      },
    ],
  },
});
const MOVED = issueNumbers({
  verdict: "moved_not_added",
  user_ids_hit: 90,
  compared: 80,
  lost: 9,
  low: 0,
  high: 20,
  diagnostics: {
    ...NO_DIAGNOSTICS,
    first_evidence_day: utcDay("2026-10-20"),
    pop_high: 3.4,
  },
});
const NEARLY_ALL = issueNumbers({
  verdict: "reaches_nearly_all",
  user_ids_hit: 300,
  lost: 7,
  low: 0,
  high: 12,
});
const NEARLY_ALL_UNREAD = issueNumbers({
  verdict: "reaches_nearly_all",
  shown: false,
  user_ids_hit: 300,
});
const NO_MEASURABLE = issueNumbers({
  verdict: "no_measurable_loss",
  user_ids_hit: 400,
  compared: 380,
  lost: 1,
  low: 0,
  high: 6,
  diagnostics: { ...NO_DIAGNOSTICS, stopped: 40, expected: 39, h: 21 },
});
const hidden = (verdict: EndUserVerdict) => issueNumbers({ verdict, shown: false, user_ids_hit: 30 });

/** The verdict word, the issue's one-sentence note and its one-line row label, for every verdict. */
const VERDICTS: [string, EndUserIssueNumbers, string, string, string][] = [
  [
    "evidence of loss (C-118)",
    C118_NUMBERS,
    "Evidence of loss",
    "41 of 96 compared hit user IDs have probably stopped in the 21 days after a hit. 18 were expected from similar user IDs not yet hit.",
    "about 23 lost (11 to 35)",
  ],
  [
    "not enough evidence (C-121)",
    C121_NUMBERS,
    "Not enough evidence",
    "13 of 66 compared hit user IDs have probably stopped in the 21 days after a hit. 9 were expected from similar user IDs not yet hit.",
    "no clear loss",
  ],
  [
    "always present (C-123)",
    C123_NUMBERS,
    "Always present",
    "Present since the first day of data. Hit user IDs stop more often (about 6, up to 15), but there is no time before it to compare with, so this is not shown as a cause.",
    "always present · cannot compare",
  ],
  [
    "cannot measure yet (C-125)",
    C125_NUMBERS,
    "Cannot measure yet",
    "A stop starts to show about 18 days after the last use here. Half of the hit user IDs were hit less than that ago. If every hit user ID that is silent today stays away: up to 4.",
    "Too early to measure: hit less than 18 days ago",
  ],
  [
    "too few user IDs (C-127)",
    C127_NUMBERS,
    "Too few user IDs",
    "17 hit user IDs can be compared; 30 are needed.",
    "Too few user IDs to compare: 17 of the 30 needed",
  ],
  [
    "moved, not added",
    MOVED,
    "Moved, not added",
    "Hit user IDs stop more often than similar user IDs (about 9), but the stops of all user IDs on this part of the agent did not rise after Oct 20 (at most 3). The issue marks user IDs that leave; it is not shown as a cause.",
    "moved, not added · no claim",
  ],
  [
    "few accounts",
    FEW_ACCOUNTS,
    "Few accounts",
    "The hits sit in 3 accounts. User IDs of one account leave together, so this is 3 cases, not 40. 5 of the 12 user IDs of Account 1 were hit and 3 of them are silent since Nov 3; 4 of the 8 user IDs of Account 2 were hit and none of them are silent.",
    "few accounts · no claim",
  ],
  [
    "few accounts, one account",
    ONE_ACCOUNT,
    "Few accounts",
    "The hits sit in 1 account. User IDs of one account leave together, so this is 1 case, not 40. 20 of the 30 user IDs of Account 1 were hit and 1 of them is silent since Nov 3.",
    "few accounts · no claim",
  ],
  [
    "reaches nearly all user IDs, read before and after",
    NEARLY_ALL,
    "Reaches nearly all user IDs",
    "Nearly all user IDs on this part of the agent were hit, so the stops of all user IDs after the start are compared with the 28 days before: about 7 more (at most 12).",
    "nearly all hit · no claim",
  ],
  [
    "reaches nearly all user IDs, no reading",
    NEARLY_ALL_UNREAD,
    "Reaches nearly all user IDs",
    "Nearly all user IDs on this part of the agent were hit; no before-and-after reading exists.",
    "Reaches nearly all user IDs: none left to compare with",
  ],
  [
    "no measurable loss",
    NO_MEASURABLE,
    "No measurable loss",
    "40 of 380 compared hit user IDs have probably stopped in the 21 days after a hit. 39 were expected from similar user IDs not yet hit.",
    "no measurable loss",
  ],
  [
    "not comparable",
    hidden("not_comparable"),
    "Not comparable",
    "Hit user IDs stop clearly less often than similar user IDs not yet hit, so the comparison does not hold.",
    "Not comparable: hit user IDs stop less often than similar ones",
  ],
  [
    "page refused",
    hidden("page_refused"),
    "Not measured",
    "The page shows a state in place of numbers, so no issue gets a number.",
    "No number while the page shows a state",
  ],
  [
    "hits not complete",
    hidden("hits_not_complete"),
    "Hits not complete",
    "The detector of a finding in this issue does not record every hit, so the comparison would be biased.",
    "Hits not complete: the detector does not record every hit",
  ],
  [
    "history too short",
    hidden("history_too_short"),
    "History too short",
    "Tessary keeps less user ID history than the estimate needs.",
    "History too short: fewer days kept than the estimate needs",
  ],
  [
    "cannot tell yet",
    hidden("cannot_tell_yet"),
    "Cannot tell yet",
    "User IDs in this app return less often than the history can show.",
    "Cannot tell yet: user IDs here return less often than the history can show",
  ],
  [
    "not applicable",
    hidden("not_applicable"),
    "No hit user IDs",
    "This issue shifts a measure for all traffic; it has no hit user IDs.",
    "Shifts a measure for all traffic; no hit user IDs",
  ],
];

/** Each ID list row: its State column and its Why column. */
const ID_ROWS: [string, EndUserIdRow, string, string][] = [
  ["stopped", STOPPED_IDS.ids[0], "stopped", "silent 52 days; user IDs like this stayed away in 94% of past cases"],
  [
    "probably stopped",
    STOPPED_IDS.ids[6],
    "probably stopped",
    "silent 33 days; user IDs like this stayed away in 74% of past cases",
  ],
  [
    "unattended",
    row({ grade: "unattended", days_silent: 20, p_stop: 0.7 }),
    "unattended",
    "silent 20 days; automated runs continue",
  ],
  [
    "not yet checked",
    row({
      state: "stopped",
      grade: "not_yet_checked",
      days_silent: 30,
      p_stop: 0.85,
    }),
    "not yet checked",
    "silent 30 days; not yet checked against this app's past",
  ],
  [
    "held",
    row({ substate: "held", days_silent: 9, p_stop: 0.4 }),
    "held as a possible break",
    "silent 9 days, in the same week as many other user IDs",
  ],
  [
    "overdue",
    row({ substate: "overdue", days_silent: 12, p_stop: 0.45 }),
    "silent, not judged",
    "silent 12 days; about 45% chance that it does not come back",
  ],
  ["active today", row({ days_silent: 0 }), "active", "active today"],
  ["active yesterday", row({ days_silent: 1 }), "active", "active 1 day ago"],
  ["active 3 days ago", row({ days_silent: 3 }), "active", "active 3 days ago"],
  ["new", row({ state: "new", days_silent: 2 }), "new", "new; active 2 days ago"],
  [
    "unknown",
    row({ state: "unknown", days_silent: null }),
    "not judged",
    "not judged while the page shows a state in place of numbers",
  ],
];

describe("numbers", () => {
  it("W1 lossLabel prints the rounded estimate and its range low first", () => {
    expect(lossLabel(23, 11, 35)).toBe("about 23 lost (11 to 35)");
    expect(lossLabel(22.6, 10.6, 35.4)).toBe("about 23 lost (11 to 35)");
  });

  it("W2 tooFew names the comparable hit user IDs and the 30 needed", () => {
    expect(tooFew(17)).toBe("17 hit user IDs can be compared; 30 are needed.");
  });

  it("W3 whyStopped prints the stop odds as a whole percent", () => {
    expect(whyStopped(52, 0.94)).toBe("silent 52 days; user IDs like this stayed away in 94% of past cases");
  });

  it("W4 thousands puts a comma in a count of four digits", () => {
    expect(thousands(1014)).toBe("1,014");
    expect(thousands(999)).toBe("999");
  });

  it("W5 noClearLoss gives only the upper limit", () => {
    expect(noClearLoss(14)).toBe("no clear loss; if today's silences last, up to 14");
  });

  it("W6 hitChip counts user IDs, and says so when identity is not checked yet", () => {
    expect(hitChip(112)).toBe("112 user IDs hit");
    expect(hitChip(1406)).toBe("1,406 user IDs hit");
    expect(hitChipTooltip("id_values", null)).toBe("user ID values; identity not checked yet");
    expect(hitChipTooltip("covered", null)).toBe("Counts user IDs, not people. Opens the Users view.");
    expect(hitChipTooltip("not_applicable", null)).toBe(
      "This issue shifts a measure for all traffic; it has no hit user IDs.",
    );
  });

  it("names the page state in the chip tooltip when the page shows no numbers", () => {
    expect(hitChipTooltip("covered", "too_early")).toBe(
      "Counts user IDs, not people. The Users page has no numbers now: too early.",
    );
    expect(hitChipTooltip("covered", "ids_unreliable")).toBe(
      "Counts user IDs, not people. The Users page has no numbers now: user IDs not reliable.",
    );
    expect(hitChipTooltip("id_values", "collecting")).toBe("user ID values; identity not checked yet");
  });

  it("prints a UTC day number as a short date, the same in every time zone", () => {
    expect(dayLabel(utcDay("2026-11-03"))).toBe("Nov 3");
    expect(dayLabel(utcDay("2026-12-08"))).toBe("Dec 8");
  });

  it("prints a share as a whole percent", () => {
    expect(percent(0.75)).toBe("75%");
    expect(percent(1)).toBe("100%");
  });
});

describe("fixed lines", () => {
  it("W7 heldState", () => expect(HELD_STATE).toBe("held as a possible break"));
  it("W8 noIdText", () => expect(NO_ID_TEXT).toBe("ID text no longer kept"));
  it("W9 noCases", () =>
    expect(NO_CASES).toBe("No cases yet. An issue shows here when a finding becomes a case in Triage."));

  it.each([
    [
      "TRIAGE_FOOTER",
      "Each row shows the user IDs it hit. A loss mark appears only when the Users page finds evidence of loss. The order of cases does not change.",
    ],
    ["ISSUES_HEADING", "Issues, by users lost"],
    ["FUNNEL_HEADING", "Where your user IDs are"],
    ["SEE_USER_IDS", "See user IDs"],
    ["SILENT_CAPTION", "quiet longer than usual"],
    ["UPDATED_DAILY", "Updated daily"],
    ["HOW_COUNTED_SHOW", "How is this counted?"],
    ["HOW_COUNTED_HIDE", "Hide how this is counted"],
    [
      "ISSUES_OVERLAP",
      "An issue's users lost are stops above similar user IDs not yet hit. Counts of different issues overlap.",
    ],
    ["NOT_PROOF", "A difference in stops, not proof of cause."],
    ["SILENT_NOW", "Hit user IDs that are silent now"],
    [
      "SILENT_NOTE",
      "One user's stop is not proof the issue caused it. The issue number compares these user IDs with similar user IDs not yet hit.",
    ],
    ["SEE_USERS_LOST", "See users lost"],
    ["ARROW", "→"],
    ["PAGE_TITLE", "Users"],
    ["NOT_APPLICABLE", "This issue shifts a measure for all traffic; it has no hit user IDs."],
    [
      "STOPPED_EXPLAIN",
      "A user ID is named when it is clearly more likely gone than not, judged against this app's own record of returns. The expected count also adds the odds of user IDs that are not named.",
    ],
    ["AT_RISK", "at risk"],
    ["RETENTION_ARIA", "Retention from the hit day"],
  ] as const)("%s", (key, text) => {
    expect(words[key]).toBe(text);
  });

  it("names the columns and the chart legends of the boards", () => {
    expect(words.ISSUE_COLUMNS).toEqual({
      issue: "Issue",
      hit: "User IDs hit",
      curve: "Still active, weeks 1 to 8 after the hit",
    });
    expect(words.SILENT_COLUMNS).toEqual({
      id: "User ID",
      days: "Days silent",
      state: "State",
    });
    expect(words.ID_COLUMNS).toEqual({
      id: "User ID",
      state: "State",
      why: "Why",
      hitBy: "Hit by",
    });
    expect(words.ROW_LEGEND).toEqual({
      hit: "hit",
      similar: "similar user IDs not yet hit",
    });
    expect(words.SMALL_LEGEND).toEqual({
      hit: "hit",
      similar: "similar, not yet hit",
    });
    expect(words.LARGE_LEGEND).toEqual({
      title: "Still active, by week after the hit",
      hit: "hit user IDs, from their hit day",
      similar: "similar user IDs not yet hit, same days",
      lost: "user IDs lost",
      band: "likely range of the hit line",
    });
    expect(words.AT_RISK_ROWS).toEqual({ hit: "hit", similar: "similar" });
    expect(words.ISSUE_METHOD).toEqual([
      "Each hit user ID is compared, from its hit day, with similar user IDs that were not yet hit on that day.",
      "Two user IDs of one person count as one stop and one new user ID.",
    ]);
  });
});

describe("W10 the texts of board P6, from the fixture numbers", () => {
  it.each(NOTICES.slice(0, 7))("page state %s", (_name, o, notice) => {
    expect(pageNotice(o)).toEqual(notice);
  });

  it("shows no notice on an ok page", () => {
    expect(pageNotice(overview())).toBeNull();
  });

  it.each([
    [
      STOP_STAGES.not_judged_yet,
      {
        label: "Probably stopped · not judged yet",
        value: null,
        unit: null,
        caption: "the past check has 2 of 4 dates",
        note: "The stop odds are not yet checked against this app's past (about 14 more days needed).",
      },
    ],
    [
      STOP_STAGES.cannot_tell_yet,
      {
        label: "Probably stopped · cannot tell yet",
        value: null,
        unit: null,
        caption: "users here return less often than this history can show",
        note: "One silence is weak evidence in this app. This stage waits for a longer history.",
      },
    ],
    [
      STOP_STAGES.history_too_short,
      {
        label: "Probably stopped · history too short",
        value: null,
        unit: null,
        caption: "90 days are kept; 120 are needed",
        note: "Stop counts and issue numbers need 120 days of user ID history.",
      },
    ],
    [
      STOP_STAGES.mixed_id_formats,
      {
        label: "Probably stopped · mixed ID formats",
        value: null,
        unit: null,
        caption: "one-day user IDs and account user IDs may share one format",
        note: "41% of user IDs are seen on one day only; some are guests, not lost users.",
      },
    ],
    [
      STOP_STAGES.expected_only,
      {
        label: "Probably stopped · expected only",
        value: "about 141",
        unit: "expected",
        caption: "none named",
        note: "Past checks named the right user IDs in only 71% of cases (80% needed), so no list.",
      },
    ],
    [
      STOP_STAGES.cannot_be_named_yet,
      {
        label: "Probably stopped · cannot be named yet",
        value: "about 230",
        unit: "expected",
        caption: "58 named",
        note: "About 230 user IDs have probably stopped. Only 58 can be named, because one silence is weak evidence here (the most it proves: 64%).",
      },
    ],
  ])("stop stage $state", ({ state, numbers }, card) => {
    expect(stopStage(state, numbers)).toEqual(card);
  });

  it("shows the named count and the expected count of P1 when stops can be named", () => {
    const o = overview();
    expect(stopStage(o.stop_stage_state!, o.stop_stage_numbers!)).toEqual({
      label: "Probably stopped",
      value: "125",
      unit: null,
      caption: "about 141 expected",
      note: null,
    });
  });

  it.each(BANNER_TEXTS.slice(0, 4))("banner %s", (_name, b, text) => {
    expect(bannerText(b)).toBe(text);
  });

  it.each([VERDICTS[3], VERDICTS[4]])("verdict %s", (_name, issue, word, note) => {
    expect(verdictWord(issue.verdict)).toBe(word);
    expect(issueNote(issue)).toBe(note);
  });
});

describe("the other page states, banners and limits", () => {
  it.each(NOTICES.slice(7))("page state %s", (_name, o, notice) => {
    expect(pageNotice(o)).toEqual(notice);
  });

  it("does not judge stops before the past check, with every past date in", () => {
    expect(stopStage("not_judged_yet", { anchors: 4, anchors_needed: 4 })).toEqual({
      label: "Probably stopped · not judged yet",
      value: null,
      unit: null,
      caption: "the past check has 4 of 4 dates",
      note: "The stop odds are not yet checked against this app's past.",
    });
  });

  it("gives the expected count with no measured past check", () => {
    expect(
      stopStage("expected_only", {
        stopped_expected: 141,
        stopped_low: 118,
        stopped_high: 164,
        named_right_needed: 0.8,
      }),
    ).toEqual({
      label: "Probably stopped · expected only",
      value: "about 141",
      unit: "expected",
      caption: "none named",
      note: "Past checks cannot yet tell how often a named user ID stays away, so no list.",
    });
  });

  it.each(BANNER_TEXTS.slice(4))("banner %s", (_name, b, text) => {
    expect(bannerText(b)).toBe(text);
  });

  it.each(LIMITS)("limit %s %o", (code, numbers, text) => {
    expect(limitText({ code, numbers, ids: 0, never_cut: false })).toBe(text);
  });
});

describe("issues", () => {
  it.each(VERDICTS)("%s: word, note and row label", (_name, issue, word, note, label) => {
    expect(verdictWord(issue.verdict)).toBe(word);
    expect(issueNote(issue)).toBe(note);
    expect(issueRowLabel(issue)).toBe(label);
  });

  it("gives the loss in words, and the headline of the issue page", () => {
    expect(lossText(C118_NUMBERS)).toBe("about 23 user IDs lost (11 to 35)");
    expect(issueHeadline(C118_NUMBERS)).toBe("About 23 user IDs lost (11 to 35)");
    expect(lossText(C121_NUMBERS)).toBe("no clear loss; if today's silences last, up to 14");
    expect(issueHeadline(C121_NUMBERS)).toBe("No clear loss; if today's silences last, up to 14");
    expect(lostShort(23)).toBe("about 23 lost");
  });

  it("says how an issue is counted, leaving out a count the backend did not give", () => {
    expect(issueHowCounted(C118_NUMBERS, utcDay("2026-09-22"))).toBe(
      "112 user IDs hit since Sep 22. 96 are compared: 8 were hit too late to count, and 8 had no similar user IDs to compare with.",
    );
    const counts = (late_hits: number | null, unmatched: number | null) =>
      issueNumbers({
        user_ids_hit: 51,
        compared: 44,
        diagnostics: { ...NO_DIAGNOSTICS, late_hits, unmatched },
      });
    expect(issueHowCounted(counts(null, 3), null)).toBe(
      "51 user IDs hit. 44 are compared: 3 had no similar user IDs to compare with.",
    );
    expect(issueHowCounted(counts(2, null), null)).toBe(
      "51 user IDs hit. 44 are compared: 2 were hit too late to count.",
    );
    expect(issueHowCounted(counts(null, null), utcDay("2026-10-01"))).toBe(
      "51 user IDs hit since Oct 1. 44 are compared.",
    );
  });

  it("words the group row of findings that fire together", () => {
    expect(groupRefs(["C-119", "C-126"])).toBe("C-119 + C-126");
    expect(groupNote(2)).toBe("2 findings that fire together, shown as one");
    expect(groupTitle(["Agent drops its persona", "Agent switches language mid-chat"])).toBe(
      "Agent drops its persona · Agent switches language mid-chat",
    );
  });

  it("labels the charts for screen readers with both end values", () => {
    expect(rowChartAria("C-118", 8, 0.57, 0.86, "about 23 lost (11 to 35)")).toBe(
      "C-118, still active 8 weeks after the hit: hit user IDs 57%, similar user IDs not yet hit 86%; about 23 lost (11 to 35)",
    );
    expect(chartAria(8, 0.57, 0.86, "about 23 user IDs lost (11 to 35)")).toBe(
      "Still active by week after the hit, weeks 1 to 8: hit user IDs fall to 57%, similar user IDs not yet hit to 86%; about 23 user IDs lost (11 to 35)",
    );
    expect(weekTick(3)).toBe("wk 3");
  });

  it("words the links of the issue page", () => {
    expect(openCase("C-118")).toBe("Open case C-118");
    expect(silentNowCount(46)).toBe("Hit user IDs that are silent now · 46");
  });
});

describe("the Users page top", () => {
  it("gives the trust line, with the caveat only when the past check cannot test people", () => {
    const base = "Probably stopped is judged against this app's own record of returns, not a fixed number of days.";
    expect(trustLine(overview().trust)).toBe(
      `${base} User IDs named as stopped at past dates stayed away in 93% of cases (this checks IDs, not people).`,
    );
    expect(trustLine({ named_right: 0.93, named_found: 0.71, caveat: false })).toBe(
      `${base} User IDs named as stopped at past dates stayed away in 93% of cases.`,
    );
    expect(trustLine(null)).toBe(base);
  });

  it("says how many user IDs are counted and set aside, and since when", () => {
    const o = overview();
    expect(countedLine(o.counted!, o.set_aside, o.history_since)).toBe(
      "1,186 user IDs are counted; 42 are set aside: 31 that run like a service, 11 that look like keys. History since Jul 14.",
    );
    expect(countedLine(1186, {}, null)).toBe("1,186 user IDs are counted.");
    expect(countedLine(1186, null, utcDay("2026-07-14"))).toBe("1,186 user IDs are counted. History since Jul 14.");
  });

  it("words the funnel", () => {
    expect(activeCaption(61)).toBe("including 61 new");
    expect(funnelAria(1014, 47, 125)).toBe("1,014 active user IDs, 47 silent and not judged, 125 probably stopped");
    expect(funnelAria(1014, 47, null)).toBe("1,014 active user IDs, 47 silent and not judged");
  });
});

describe("the drawer of hit user IDs that are silent now", () => {
  it("heads the drawer with the case and its verdict", () => {
    expect(railHeader("C-118", "evidence_of_loss")).toBe("C-118 · evidence of loss");
    expect(railHeader("C-118", "few_accounts")).toBe("C-118 · few accounts");
  });

  it("groups by the weeks since the hit", () => {
    expect(weeksAgo(8, 8)).toBe("Hit 8 weeks ago");
    expect(weeksAgo(6, 7)).toBe("Hit 6 to 7 weeks ago");
    expect(weeksAgo(1, 1)).toBe("Hit 1 week ago");
  });

  it("counts the rows after the page with their weeks", () => {
    const { more: rest, more_weeks_low, more_weeks_high } = C118_SILENT_IDS;
    expect(moreSilent(rest, more_weeks_low!, more_weeks_high!)).toBe("+ 38 more, hit 1 to 4 weeks ago");
    expect(moreSilent(5, 2, 2)).toBe("+ 5 more, hit 2 weeks ago");
  });

  it("gives the state of each row: the grade, or the stage when there is none", () => {
    expect(C118_SILENT_IDS.ids.map(silentState)).toEqual([
      "stopped",
      "stopped",
      "stopped",
      "stopped",
      "probably stopped",
      "probably stopped",
      "probably stopped",
      "silent, not judged",
    ]);
  });
});

describe("the case block", () => {
  it("CB1 gives the count line in two parts, the count first", () => {
    expect(caseCountLine(C118_CASE_BLOCK)).toEqual({
      lead: "112 user IDs hit",
      rest: " since Sep 22. 9 of 1,406 flagged traces had no user ID.",
    });
    expect(caseCountLine({ ...C118_CASE_BLOCK, since_day: null })).toEqual({
      lead: "112 user IDs hit",
      rest: ". 9 of 1,406 flagged traces had no user ID.",
    });
  });
});

describe("the ID list", () => {
  it("heads each stage tab", () => {
    expect(stoppedHeader(125, 141)).toBe("125 user IDs named, about 141 expected.");
    expect(activeHeader(1014, 61)).toBe("1,014 user IDs are active, including 61 new.");
    expect(silentHeader(47)).toBe("47 user IDs are quieter than usual but not named as stopped.");
    expect(words.ACTIVE_EXPLAIN).toBe(
      "A user ID stays active while it returns within its usual time, judged against this app's own record of returns.",
    );
    expect(words.SILENT_EXPLAIN).toBe(
      "This holds user IDs whose stop odds are too low to name them, user IDs held as a possible break, and user IDs whose automated runs continue while the person is silent.",
    );
    expect(words.HIT_BY_NOTE).toBe('"Hit by" names only issues with evidence of loss or few accounts.');
  });

  it("labels the tabs with their counts, and a withheld count is left out", () => {
    expect(stageTab("active", 1014)).toBe("Active · 1,014");
    expect(stageTab("silent", 47)).toBe("Silent, not judged · 47");
    expect(stageTab("stopped", null)).toBe("Probably stopped");
    expect(more(116)).toBe("+ 116 more");
  });

  it.each(ID_ROWS)("row %s", (_name, r, state, why) => {
    expect(idState(r)).toBe(state);
    expect(idWhy(r)).toBe(why);
  });
});

describe("W11 every string uses plain punctuation", () => {
  const strings = (v: unknown): string[] =>
    typeof v === "string" ? [v] : typeof v === "object" && v !== null ? Object.values(v).flatMap(strings) : [];
  const all = [
    ...strings(words),
    ...NOTICES.flatMap(([, , n]) => [n.title, n.text]),
    ...BANNER_TEXTS.map(([, , text]) => text),
    ...LIMITS.map(([, , text]) => text),
    ...VERDICTS.flatMap(([, , ...texts]) => texts),
    ...ID_ROWS.flatMap(([, , ...texts]) => texts),
  ];

  it.each(all)("%s", (s) => {
    expect(s).not.toContain("—");
    expect(s).not.toContain("’");
  });
});
