// SPDX-License-Identifier: Apache-2.0
// Every sentence of the Users view. The backend sends codes and numbers; this file words them.
import type {
  EndUserAccountLine,
  EndUserBanner,
  EndUserBannerCode,
  EndUserCaseBlock,
  EndUserCountKind,
  EndUserGrade,
  EndUserIdRow,
  EndUserIssueNumbers,
  EndUserLimitCode,
  EndUserLimitLine,
  EndUserOverview,
  EndUserPageState,
  EndUserSilentId,
  EndUserStage,
  EndUserStopStageState,
  EndUserTrust,
  EndUserVerdict,
} from "../../api/types";

type Numbers = Record<string, number>;

/** Spec 8.7: a verdict needs this many comparable hit user IDs. */
const COMPARABLE_NEEDED = 30;

// ---- Numbers and dates ----

export const thousands = (n: number) => n.toLocaleString("en-US");
const whole = (x: number) => thousands(Math.round(x));
export const percent = (share: number) => `${Math.round(100 * share)}%`;
const plural = (n: number, one: string, many: string) => `${thousands(n)} ${n === 1 ? one : many}`;
const sentenceCase = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const lowerFirst = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);
const LIST = new Intl.ListFormat("en-US", { type: "conjunction" });

const SHORT_DATE = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  timeZone: "UTC",
});
/** A UTC day number as the backend counts days, printed "Nov 3". */
export const dayLabel = (day: number) => SHORT_DATE.format(new Date(day * 86_400_000));

// ---- Loss numbers ----

export const lossLabel = (lost: number, low: number, high: number) =>
  `about ${whole(lost)} lost (${whole(low)} to ${whole(high)})`;
export const lostShort = (lost: number) => `about ${whole(lost)} lost`;
export const noClearLoss = (upper: number) => `no clear loss; if today's silences last, up to ${whole(upper)}`;
export const tooFew = (compared: number) =>
  `${thousands(compared)} hit user IDs can be compared; ${COMPARABLE_NEEDED} are needed.`;
export const whyStopped = (daysSilent: number, pStop: number) =>
  `silent ${daysSilent} days; user IDs like this stayed away in ${percent(pStop)} of past cases`;

// ---- Fixed lines ----

export const HELD_STATE = "held as a possible break";
export const NO_ID_TEXT = "ID text no longer kept";
/** The page title, the breadcrumb root and the label of the case block. */
export const PAGE_TITLE = "Users";
/** Follows the text of a link that opens a list: "See user IDs", "See users lost", the silent-now link. */
export const ARROW = "→";
export const NO_CASES = "No cases yet. An issue shows here when a finding becomes a case in Triage.";
export const NOT_APPLICABLE = "This issue shifts a measure for all traffic; it has no hit user IDs.";

export const STAGE_LABELS: Record<EndUserStage, string> = {
  active: "Active",
  silent: "Silent, not judged",
  stopped: "Probably stopped",
};

// ---- P0: the Triage chip ----

export const hitChip = (n: number) => `${thousands(n)} user IDs hit`;
const HIT_CHIP_TOOLTIPS: Record<EndUserCountKind, string> = {
  id_values: "user ID values; identity not checked yet",
  covered: "Counts user IDs, not people. Opens the Users view.",
  not_applicable: NOT_APPLICABLE,
};
/** The page state in a few words, as the chip tooltip names it. */
const PAGE_STATE_WORDS: Record<EndUserPageState, string> = {
  ok: "numbers shown",
  partial_coverage: "partial coverage",
  too_early: "too early",
  no_user_id: "no user ID",
  ids_unreliable: "user IDs not reliable",
  not_computed: "not computed",
  collecting: "collecting history",
};
/** A covered count on a page that shows no numbers names the page state. */
export const hitChipTooltip = (kind: EndUserCountKind, refusal: EndUserPageState | null) =>
  kind === "covered" && refusal != null
    ? `Counts user IDs, not people. The Users page has no numbers now: ${PAGE_STATE_WORDS[refusal]}.`
    : HIT_CHIP_TOOLTIPS[kind];
export const TRIAGE_FOOTER =
  "Each row shows the user IDs it hit. A loss mark appears only when the Users page finds evidence of loss. The order of cases does not change.";

// ---- P1: the Users page top ----

export const UPDATED_DAILY = "Updated daily";
export const HOW_COUNTED_SHOW = "How is this counted?";
export const HOW_COUNTED_HIDE = "Hide how this is counted";
export const FUNNEL_HEADING = "Where your user IDs are";
export const SEE_USER_IDS = "See user IDs";
export const SILENT_CAPTION = "quiet longer than usual";
export const ISSUES_OVERLAP =
  "An issue's users lost are stops above similar user IDs not yet hit. Counts of different issues overlap.";

export const activeCaption = (newIds: number) => `including ${thousands(newIds)} new`;

/** The funnel for screen readers; a withheld stop count is left out. */
export const funnelAria = (active: number, silent: number, stoppedNamed: number | null) =>
  [
    `${thousands(active)} active user IDs`,
    `${thousands(silent)} silent and not judged`,
    ...(stoppedNamed == null ? [] : [`${thousands(stoppedNamed)} probably stopped`]),
  ].join(", ");

/** The first How-counted line; the past-check share only when there is one. */
export function trustLine(trust: EndUserTrust | null): string {
  const base = "Probably stopped is judged against this app's own record of returns, not a fixed number of days.";
  if (trust == null) return base;
  const caveat = trust.caveat ? " (this checks IDs, not people)" : "";
  return `${base} User IDs named as stopped at past dates stayed away in ${percent(trust.named_right!)} of cases${caveat}.`;
}

const SET_ASIDE_WORDS: Record<string, string> = {
  aggregate: "that look like keys",
  service: "that run like a service",
  odd_shape: "with an odd ID format",
  token_link: "that copy the session token",
  non_returning_group: "in groups that do not return",
  machine_only: "with machine traffic only",
};

/** Largest reason first; equal counts keep the backend's order. */
const setAsideList = (setAside: Numbers) =>
  Object.entries(setAside)
    .sort(([, a], [, b]) => b - a)
    .map(([reason, n]) => `${thousands(n)} ${SET_ASIDE_WORDS[reason]}`)
    .join(", ");

const setAsideTotal = (setAside: Numbers | null) => Object.values(setAside ?? {}).reduce((a, b) => a + b, 0);

export function countedLine(counted: number, setAside: Numbers | null, historySince: number | null): string {
  const total = setAsideTotal(setAside);
  const aside = total > 0 ? `; ${thousands(total)} are set aside: ${setAsideList(setAside!)}` : "";
  const since = historySince == null ? "" : ` History since ${dayLabel(historySince)}.`;
  return `${thousands(counted)} user IDs are counted${aside}.${since}`;
}

// ---- P6: page states ----

export type Notice = { title: string; text: string };

const SEND_USER_ID = "Send the same user.id for one person across sessions.";
const FILLS_IN = "The page fills in on its own.";

/** Spec 3.5 and level 2: what replaces the funnel and the issue numbers; null on an ok page. */
export function pageNotice(o: EndUserOverview): Notice | null {
  const n = o.reason_numbers;
  const reason = o.page_state_reason;
  switch (o.page_state) {
    case "ok":
      return null;
    case "no_user_id":
      return {
        title: "No user ID",
        text: `${reason === "low_id_share_28" ? `Only ${percent(n.ident_share_28)} of traces in the last 28 days carry a user ID.` : `Only ${percent(n.ident_share)} of all traces carry a user ID.`} Send user.id on every trace, the same for one person across sessions.`,
      };
    case "too_early":
      return {
        title: "Too early",
        text:
          reason === "recent_id_change"
            ? `User IDs were changed recently, so old and new user IDs cannot be told apart yet. ${FILLS_IN}`
            : `${n.span_days} days of user ID history (${n.span_days_needed} needed) and ${thousands(n.ids)} user IDs (${n.ids_needed} needed). ${FILLS_IN}`,
      };
    case "ids_unreliable":
      return unreliable(o);
    case "partial_coverage": {
      const aside = setAsideTotal(o.set_aside) > 0 ? ` Set aside: ${setAsideList(o.set_aside!)}.` : "";
      const why =
        reason === "low_id_share"
          ? ` Only ${percent(n.ident_share)} of all traces carry a user ID.`
          : " A banner names each reason.";
      return {
        title: "Partial coverage",
        text: `Numbers use covered user IDs only.${aside}${why}`,
      };
    }
    case "not_computed":
      return {
        title: "Not computed",
        text: "This project is over the size budget for the daily job. Coverage and user IDs hit still show.",
      };
    case "collecting":
      return {
        title: "Collecting history",
        text: `The first daily run has not finished yet. ${FILLS_IN}`,
      };
  }
}

function unreliable(o: EndUserOverview): Notice {
  const n = o.reason_numbers;
  switch (o.page_state_reason) {
    case "heavy_ids": {
      const perDay = [
        ...(n.sessions_per_day == null ? [] : [`${whole(n.sessions_per_day)} sessions a day`]),
        ...(n.hours_per_day == null ? [] : [`${whole(n.hours_per_day)} hours a day`]),
      ];
      const detail = perDay.length ? `: ${perDay.join(", ")}` : "";
      return {
        title: "User IDs not reliable: keys",
        text: `${thousands(n.ids)} user IDs carry the traffic of more than one user (${percent(n.id_day_share)} of ID-days${detail}). The other user IDs cannot be taken as single users.`,
      };
    }
    case "ids_replaced":
      return {
        title: "User IDs not reliable: replaced",
        text: `Of ${thousands(n.ended)} user IDs that ended and had a repeating job, ${thousands(n.continued)} went on under a new user ID. User IDs are replaced, so a silent user ID is not a lost user.`,
      };
    case "ids_copy_session_token":
      return {
        title: "User IDs not reliable: session tokens",
        text: `User IDs change with every session, like a session token, so a silent user ID is not a lost user. ${SEND_USER_ID}`,
      };
    default:
      return {
        title: "User IDs not reliable: not seen again",
        text: `Most user IDs are not seen again after their first days, so a silent user ID is not a lost user. ${SEND_USER_ID}`,
      };
  }
}

// ---- P6: the Probably stopped stage ----

const STOP_STATE_WORDS: Record<EndUserStopStageState, string> = {
  history_too_short: "history too short",
  not_judged_yet: "not judged yet",
  cannot_tell_yet: "cannot tell yet",
  mixed_id_formats: "mixed ID formats",
  expected_only: "expected only",
  cannot_be_named_yet: "cannot be named yet",
  expected_and_named: "expected and named",
};

export type StopStageCard = {
  label: string;
  /** Null when the stage shows no count: the count is left out, never shown as a dash. */
  value: string | null;
  unit: string | null;
  caption: string;
  note: string | null;
};

/** The past check has no date yet; the days still needed when the rule gives them. */
const notChecked = (daysNeeded: number | undefined) =>
  `The stop odds are not yet checked against this app's past${daysNeeded ? ` (about ${whole(daysNeeded)} more days needed)` : ""}.`;
const oneDayShare = (share: number) =>
  `${percent(share)} of user IDs are seen on one day only; some are guests, not lost users.`;
const cannotBeNamed = (n: Numbers) =>
  `About ${whole(n.stopped_expected)} user IDs have probably stopped. Only ${whole(n.stopped_named)} can be named, because one silence is weak evidence here (the most it proves: ${percent(n.plateau)}).`;

/** The Probably stopped stage in one of its states; the other stages still show. */
export function stopStage(state: EndUserStopStageState, n: Numbers): StopStageCard {
  const label = `${STAGE_LABELS.stopped} · ${STOP_STATE_WORDS[state]}`;
  const blank = { label, value: null, unit: null };
  const expected = {
    label,
    value: `about ${whole(n.stopped_expected)}`,
    unit: "expected",
  };
  switch (state) {
    case "not_judged_yet":
      return {
        ...blank,
        caption: `the past check has ${n.anchors} of ${n.anchors_needed} dates`,
        note: notChecked(n.days_needed),
      };
    case "cannot_tell_yet":
      return {
        ...blank,
        caption: "users here return less often than this history can show",
        note: "One silence is weak evidence in this app. This stage waits for a longer history.",
      };
    case "history_too_short":
      return {
        ...blank,
        caption: `${n.horizon_days} days are kept; ${n.horizon_days_needed} are needed`,
        note: `Stop counts and issue numbers need ${n.horizon_days_needed} days of user ID history.`,
      };
    case "mixed_id_formats":
      return {
        ...blank,
        caption: "one-day user IDs and account user IDs may share one format",
        note: oneDayShare(n.one_day_share),
      };
    case "expected_only":
      return {
        ...expected,
        caption: "none named",
        note:
          n.named_right == null
            ? "Past checks cannot yet tell how often a named user ID stays away, so no list."
            : `Past checks named the right user IDs in only ${percent(n.named_right)} of cases (${percent(n.named_right_needed)} needed), so no list.`,
      };
    case "expected_and_named":
      return {
        label: STAGE_LABELS.stopped,
        value: whole(n.stopped_named),
        unit: null,
        caption: `about ${whole(n.stopped_expected)} expected`,
        note: null,
      };
    case "cannot_be_named_yet":
      return {
        ...expected,
        caption: `${whole(n.stopped_named)} named`,
        note: cannotBeNamed(n),
      };
  }
}

// ---- P6: banners and limit lines ----

const groupOrKey = (n: Numbers) =>
  `${thousands(n.ids)} user IDs look like groups or keys (${percent(n.id_day_share)} of ID-days). Counts on this page are user IDs. One ID can hold many people, and people who leave inside an ID that stays active cannot be seen.`;

const HOLD_WEEKS = 4;
const TRACING_OUTAGE = "on every route; silences that start then may be a tracing outage.";

const BANNERS: Record<EndUserBannerCode, (n: Numbers, routes: string[]) => string> = {
  page_refusal: () => "Numbers are held back; the page state says why.",
  group_or_key_ids: groupOrKey,
  id_loss: (n, routes) =>
    `Since ${dayLabel(n.onset_day)}, ${percent(n.share_after)} of the traffic${routes.length ? ` on ${LIST.format(routes)}` : ""} arrives without a user ID (before: ${percent(n.share_before)}). About ${whole(n.ids)} user IDs that look stopped are probably still active. Stop counts after this date are too high until the user ID is sent again.`,
  id_change: (n) =>
    `User IDs were changed${n.first_day == null ? "" : ` (first on ${dayLabel(n.first_day)})`}. ${thousands(n.cannot_follow)} user IDs that cannot be followed are not counted as stopped.`,
  tracer: (n) =>
    `Of ${thousands(n.ended)} user IDs that ended and had a repeating job, ${thousands(n.continued)} went on under a new user ID. A silent user ID may be a person who is still here.`,
  shared_silence: (n) => {
    if (n.returned_day != null) {
      return `The user IDs that went silent together came back from ${dayLabel(n.returned_day)} (a break).`;
    }
    if (n.silent_since_day != null) {
      return `User IDs that went silent together did not come back in ${HOLD_WEEKS} weeks; they now count like any other silent user ID (silent since ${dayLabel(n.silent_since_day)}).`;
    }
    return `From ${dayLabel(n.start_day)}, ${percent(n.share_silent)} of regular user IDs went silent in the same week (usual: ${percent(n.share_usual)}). ${thousands(n.ids)} user IDs are ${HELD_STATE} until ${dayLabel(n.held_until_day)}: between ${whole(n.stopped_low)} and ${whole(n.stopped_high)} of them have stopped. A break or a loss; not yet known.`;
  },
  season_level: (n) =>
    `User IDs that no issue touched use the app at ${percent(n.level)} of their usual rate since about ${dayLabel(n.since_day)}. This can be a season or a problem that no finding covers.`,
  cannot_be_named_yet: cannotBeNamed,
  outage: (n) =>
    n.drop_share == null
      ? `Traces fell on ${dayLabel(n.day)} ${TRACING_OUTAGE}`
      : `Traces fell by ${percent(n.drop_share)} on ${dayLabel(n.day)} ${TRACING_OUTAGE}`,
};

/** One banner above the funnel, one per fact. */
export const bannerText = (b: EndUserBanner) => BANNERS[b.code](b.numbers, b.routes);

const namedFoundLow = (share: number) =>
  `The page names only ${percent(share)} of the user IDs that stay away; the stop stage gives the expected count.`;

const LIMITS: Record<EndUserLimitCode, (n: Numbers) => string> = {
  group_or_key_ids: groupOrKey,
  tracer_cannot_test: () =>
    "This app has no repeating jobs that could show a change of user IDs. The checks on this page confirm user IDs, not people.",
  few_covered_ids: (n) =>
    `This app has ${thousands(n.ids)} user IDs. An issue needs about ${COMPARABLE_NEEDED} comparable hit user IDs, and verdicts on small apps can change from week to week.`,
  cannot_be_named_yet: cannotBeNamed,
  named_right_low: (n) =>
    n.named_found == null
      ? `User IDs named as stopped at past dates stayed away in only ${percent(n.named_right)} of cases; the stop stage gives the expected count.`
      : namedFoundLow(n.named_found),
  named_found_low: (n) => namedFoundLow(n.named_found),
  no_anchor: (n) => notChecked(n.days_needed),
  lenient_basis: () => "Weak activity basis: traffic that may be automatic counts as activity.",
  unattended_share: (n) =>
    `${thousands(n.ids)} user IDs are unattended or automation only, more than 10% of covered user IDs.`,
  id_loss: (n) =>
    `${thousands(n.ids)} user IDs may look stopped because some of their traffic arrives without a user ID.`,
  id_change: (n) =>
    `${thousands(n.ids)} user IDs were changed and cannot be followed; they are not counted as stopped.`,
  shared_silence: (n) =>
    `${thousands(n.ids)} user IDs went silent in the same week as many others, so their stops are less certain.`,
  one_day_share: (n) => oneDayShare(n.one_day_share),
  account_event: () => "User IDs of one account left together, so many stops here may be one event.",
  return_cycle_too_long: (n) =>
    `A return cycle longer than ${thousands(n.days)} days cannot be learned from this history.`,
  repeat_contact: () => "On this app a repeat contact may be the failure, so a return is not read as health yet.",
  facts_not_kept: (n) =>
    n.from_day === n.to_day
      ? `Activity on ${dayLabel(n.from_day)} was not kept.`
      : `Activity from ${dayLabel(n.from_day)} to ${dayLabel(n.to_day)} was not kept.`,
  imports_outside_window: () => "Imported traces dated outside the live window are not counted.",
};

/** One limit line under the funnel. */
export const limitText = (l: EndUserLimitLine) => LIMITS[l.code](l.numbers);

// ---- Issues: verdicts and their words ----

const VERDICT_WORDS: Record<EndUserVerdict, string> = {
  page_refused: "Not measured",
  hits_not_complete: "Hits not complete",
  history_too_short: "History too short",
  cannot_tell_yet: "Cannot tell yet",
  cannot_measure_yet: "Cannot measure yet",
  too_few_ids: "Too few user IDs",
  reaches_nearly_all: "Reaches nearly all user IDs",
  not_comparable: "Not comparable",
  always_present: "Always present",
  few_accounts: "Few accounts",
  evidence_of_loss: "Evidence of loss",
  moved_not_added: "Moved, not added",
  no_measurable_loss: "No measurable loss",
  not_enough_evidence: "Not enough evidence",
  not_applicable: "No hit user IDs",
};

/** The verdict as a badge, in sentence case. */
export const verdictWord = (v: EndUserVerdict) => VERDICT_WORDS[v];

/** Spec 8.7 "Shown": the stops of the compared hit user IDs against the expected stops. */
const stopsSentence = (i: EndUserIssueNumbers) =>
  `${whole(i.diagnostics.stopped!)} of ${thousands(i.compared!)} compared hit user IDs have probably stopped in the ${i.diagnostics.h} days after a hit. ${whole(i.diagnostics.expected!)} were expected from similar user IDs not yet hit.`;

const PART = "this part of the agent";

const accountLine = (a: EndUserAccountLine) =>
  `${thousands(a.hit)} of the ${thousands(a.ids)} user IDs of Account ${a.account} were hit and ${a.silent === 0 ? "none of them are silent" : `${thousands(a.silent)} of them ${a.silent === 1 ? "is" : "are"} silent since ${dayLabel(a.silent_since_day!)}`}`;

const NOTES: Record<EndUserVerdict, (i: EndUserIssueNumbers) => string> = {
  page_refused: () => "The page shows a state in place of numbers, so no issue gets a number.",
  hits_not_complete: () =>
    "The detector of a finding in this issue does not record every hit, so the comparison would be biased.",
  history_too_short: () => "Tessary keeps less user ID history than the estimate needs.",
  cannot_tell_yet: () => "User IDs in this app return less often than the history can show.",
  cannot_measure_yet: (i) =>
    `A stop starts to show about ${i.diagnostics.t_vis} days after the last use here. Half of the hit user IDs were hit less than that ago. If every hit user ID that is silent today stays away: up to ${whole(i.diagnostics.upper!)}.`,
  too_few_ids: (i) => tooFew(i.compared!),
  reaches_nearly_all: (i) =>
    i.shown
      ? `Nearly all user IDs on ${PART} were hit, so the stops of all user IDs after the start are compared with the 28 days before: about ${whole(i.lost!)} more (at most ${whole(i.high!)}).`
      : `Nearly all user IDs on ${PART} were hit; no before-and-after reading exists.`,
  not_comparable: () =>
    "Hit user IDs stop clearly less often than similar user IDs not yet hit, so the comparison does not hold.",
  always_present: (i) =>
    `Present since the first day of data. Hit user IDs stop more often (about ${whole(i.lost!)}, up to ${whole(i.high!)}), but there is no time before it to compare with, so this is not shown as a cause.`,
  few_accounts: (i) => {
    const d = i.diagnostics;
    const accounts = d.accounts!;
    return `The hits sit in ${plural(accounts, "account", "accounts")}. User IDs of one account leave together, so this is ${plural(accounts, "case", "cases")}, not ${thousands(d.estimated!)}. ${d.account_lines!.map(accountLine).join("; ")}.`;
  },
  evidence_of_loss: stopsSentence,
  moved_not_added: (i) =>
    `Hit user IDs stop more often than similar user IDs (about ${whole(i.lost!)}), but the stops of all user IDs on ${PART} did not rise after ${dayLabel(i.diagnostics.first_evidence_day!)} (at most ${whole(i.diagnostics.pop_high!)}). The issue marks user IDs that leave; it is not shown as a cause.`,
  no_measurable_loss: stopsSentence,
  not_enough_evidence: stopsSentence,
  not_applicable: () => NOT_APPLICABLE,
};

/** The one-sentence note of an issue, for every verdict: the stops when shown, else why there is no number. */
export const issueNote = (i: EndUserIssueNumbers) => NOTES[i.verdict](i);

const NO_CLAIM = "no claim";

const ROW_LABELS: Record<EndUserVerdict, (i: EndUserIssueNumbers) => string> = {
  page_refused: () => "No number while the page shows a state",
  hits_not_complete: () => "Hits not complete: the detector does not record every hit",
  history_too_short: () => "History too short: fewer days kept than the estimate needs",
  cannot_tell_yet: () => "Cannot tell yet: user IDs here return less often than the history can show",
  cannot_measure_yet: (i) => `Too early to measure: hit less than ${i.diagnostics.t_vis} days ago`,
  too_few_ids: (i) => `Too few user IDs to compare: ${thousands(i.compared!)} of the ${COMPARABLE_NEEDED} needed`,
  reaches_nearly_all: (i) =>
    i.shown ? `nearly all hit · ${NO_CLAIM}` : "Reaches nearly all user IDs: none left to compare with",
  not_comparable: () => "Not comparable: hit user IDs stop less often than similar ones",
  always_present: () => "always present · cannot compare",
  few_accounts: () => `few accounts · ${NO_CLAIM}`,
  evidence_of_loss: (i) => lossLabel(i.lost!, i.low!, i.high!),
  moved_not_added: () => `moved, not added · ${NO_CLAIM}`,
  no_measurable_loss: () => "no measurable loss",
  not_enough_evidence: () => "no clear loss",
  not_applicable: () => "Shifts a measure for all traffic; no hit user IDs",
};

/** The one line of an issue row: the gap label on its curve when shown, else the reason it has none. */
export const issueRowLabel = (i: EndUserIssueNumbers) => ROW_LABELS[i.verdict](i);

/** The loss of a shown issue: only "evidence of loss" claims one. */
export const lossText = (i: EndUserIssueNumbers) =>
  i.verdict === "evidence_of_loss"
    ? `about ${whole(i.lost!)} user IDs lost (${whole(i.low!)} to ${whole(i.high!)})`
    : noClearLoss(i.high!);
export const issueHeadline = (i: EndUserIssueNumbers) => sentenceCase(lossText(i));

/** Spec 3.5: the note of every issue on a page with the group or key banner. */
export const GROUP_CAVEAT = "Counted in user IDs; the range does not bound people.";

/** The issue note under the overview's banners: with the group or key banner it ends with GROUP_CAVEAT. */
export const issueNoteUnder = (i: EndUserIssueNumbers, banners: EndUserBanner[]) =>
  banners.some((b) => b.code === "group_or_key_ids") ? `${issueNote(i)} ${GROUP_CAVEAT}` : issueNote(i);

// ---- P1: the issue list ----

export const ISSUES_HEADING = "Issues, by users lost";
export const ISSUE_COLUMNS = {
  issue: "Issue",
  hit: "User IDs hit",
  curve: "Still active, weeks 1 to 8 after the hit",
};
export const ROW_LEGEND = {
  hit: "hit",
  similar: "similar user IDs not yet hit",
};
export const groupRefs = (refs: string[]) => refs.join(" + ");
export const groupNote = (findings: number) => `${findings} findings that fire together, shown as one`;
export const groupTitle = (titles: string[]) => titles.join(" · ");
export const rowChartAria = (ref: string, weeks: number, hitEnd: number, similarEnd: number, label: string) =>
  `${ref}, still active ${weeks} weeks after the hit: hit user IDs ${percent(hitEnd)}, similar user IDs not yet hit ${percent(similarEnd)}; ${label}`;

// ---- P2: the issue page ----

export const openCase = (ref: string) => `Open case ${ref}`;
export const RETENTION_ARIA = "Retention from the hit day";
export const LARGE_LEGEND = {
  title: "Still active, by week after the hit",
  hit: "hit user IDs, from their hit day",
  similar: "similar user IDs not yet hit, same days",
  lost: "user IDs lost",
  band: "likely range of the hit line",
};
export const SMALL_LEGEND = { hit: "hit", similar: "similar, not yet hit" };
export const AT_RISK = "at risk";
export const AT_RISK_ROWS = { hit: "hit", similar: "similar" };
export const weekTick = (week: number) => `wk ${week}`;
export const chartAria = (weeks: number, hitEnd: number, similarEnd: number, loss: string) =>
  `Still active by week after the hit, weeks 1 to ${weeks}: hit user IDs fall to ${percent(hitEnd)}, similar user IDs not yet hit to ${percent(similarEnd)}; ${loss}`;
export const silentNowCount = (n: number) => `${SILENT_NOW} · ${thousands(n)}`;
export const NOT_PROOF = "A difference in stops, not proof of cause.";

/** The first How-counted line of an issue; a count the backend did not give is left out. */
export function issueHowCounted(i: EndUserIssueNumbers, sinceDay: number | null): string {
  const { late_hits: late, unmatched } = i.diagnostics;
  const parts = [
    ...(late == null ? [] : [`${thousands(late)} were hit too late to count`]),
    ...(unmatched == null ? [] : [`${thousands(unmatched)} had no similar user IDs to compare with`]),
  ];
  const since = sinceDay == null ? "" : ` since ${dayLabel(sinceDay)}`;
  const why = parts.length ? `: ${parts.join(", and ")}` : "";
  return `${hitChip(i.user_ids_hit)}${since}. ${thousands(i.compared!)} are compared${why}.`;
}
export const ISSUE_METHOD = [
  "Each hit user ID is compared, from its hit day, with similar user IDs that were not yet hit on that day.",
  "Two user IDs of one person count as one stop and one new user ID.",
];

// ---- P3: the drawer ----

export const SILENT_NOW = "Hit user IDs that are silent now";
export const SILENT_NOTE =
  "One user's stop is not proof the issue caused it. The issue number compares these user IDs with similar user IDs not yet hit.";
export const SILENT_COLUMNS = {
  id: "User ID",
  days: "Days silent",
  state: "State",
};
export const CLOSE = "Close";
export const CLOSE_DRAWER = "Close drawer";
export const railHeader = (ref: string, verdict: EndUserVerdict) => `${ref} · ${lowerFirst(VERDICT_WORDS[verdict])}`;
const weeks = (low: number, high: number) => (low === high ? plural(low, "week", "weeks") : `${low} to ${high} weeks`);
export const weeksAgo = (low: number, high: number) => `Hit ${weeks(low, high)} ago`;
export const moreSilent = (n: number, low: number, high: number) =>
  `+ ${thousands(n)} more, hit ${weeks(low, high)} ago`;

const GRADE_WORDS: Record<EndUserGrade, string> = {
  stopped: "stopped",
  probably_stopped: "probably stopped",
  unattended: "unattended",
  not_yet_checked: "not yet checked",
};
/** The grade, or the stage in lower case when there is none. */
export const silentState = (r: EndUserSilentId) => (r.grade ? GRADE_WORDS[r.grade] : lowerFirst(STAGE_LABELS[r.stage]));

// ---- P4: the case block ----

export const SEE_USERS_LOST = "See users lost";

/** The count line in two parts, so the count can stand out; null counts belong to a metric shift, worded apart. */
export function caseCountLine(b: EndUserCaseBlock): {
  lead: string;
  rest: string;
} {
  const since = b.since_day == null ? "" : ` since ${dayLabel(b.since_day)}`;
  return {
    lead: hitChip(b.user_ids_hit!),
    rest: `${since}. ${thousands(b.flagged_traces_no_user_id)} of ${thousands(b.flagged_traces)} flagged traces had no user ID.`,
  };
}

// ---- P5: the ID list ----

export const ID_COLUMNS = {
  id: "User ID",
  state: "State",
  why: "Why",
  hitBy: "Hit by",
};
export const HIT_BY_NOTE = '"Hit by" names only issues with evidence of loss or few accounts.';
export const stoppedHeader = (named: number, expected: number) =>
  `${thousands(named)} user IDs named, about ${whole(expected)} expected.`;
export const STOPPED_EXPLAIN =
  "A user ID is named when it is clearly more likely gone than not, judged against this app's own record of returns. The expected count also adds the odds of user IDs that are not named.";
export const activeHeader = (active: number, newIds: number) =>
  `${thousands(active)} user IDs are active, including ${thousands(newIds)} new.`;
export const ACTIVE_EXPLAIN =
  "A user ID stays active while it returns within its usual time, judged against this app's own record of returns.";
export const silentHeader = (silent: number) =>
  `${thousands(silent)} user IDs are quieter than usual but not named as stopped.`;
export const SILENT_EXPLAIN =
  "This holds user IDs whose stop odds are too low to name them, user IDs held as a possible break, and user IDs whose automated runs continue while the person is silent.";
/** A withheld count is left out. */
export const stageTab = (stage: EndUserStage, n: number | null) =>
  n == null ? STAGE_LABELS[stage] : `${STAGE_LABELS[stage]} · ${thousands(n)}`;
export const more = (n: number) => `+ ${thousands(n)} more`;

/** A stopped user ID always has a grade, so the state alone words the other user IDs. */
type Ungraded = Exclude<NonNullable<EndUserIdRow["state"]>, "stopped">;

const ago = (days: number) => (days === 0 ? "today" : `${plural(days, "day", "days")} ago`);

const GRADE_WHY: Record<EndUserGrade, (r: EndUserIdRow) => string> = {
  stopped: (r) => whyStopped(r.days_silent!, r.p_stop!),
  probably_stopped: (r) => whyStopped(r.days_silent!, r.p_stop!),
  unattended: (r) => `silent ${r.days_silent} days; automated runs continue`,
  not_yet_checked: (r) => `silent ${r.days_silent} days; not yet checked against this app's past`,
};
const SUBSTATE_WHY: Record<NonNullable<EndUserIdRow["substate"]>, (r: EndUserIdRow) => string> = {
  held: (r) => `silent ${r.days_silent} days, in the same week as many other user IDs`,
  overdue: (r) => `silent ${r.days_silent} days; about ${percent(r.p_stop!)} chance that it does not come back`,
};
const STATE_WHY: Record<Ungraded, (r: EndUserIdRow) => string> = {
  active: (r) => `active ${ago(r.days_silent!)}`,
  new: (r) => `new; active ${ago(r.days_silent!)}`,
  unknown: () => "not judged while the page shows a state in place of numbers",
};
const SUBSTATE_WORDS: Record<NonNullable<EndUserIdRow["substate"]>, string> = {
  held: HELD_STATE,
  overdue: lowerFirst(STAGE_LABELS.silent),
};
const STATE_WORDS: Record<Ungraded, string> = {
  active: "active",
  new: "new",
  unknown: "not judged",
};

/** The State column: the grade, else the substate, else the state. */
export const idState = (r: EndUserIdRow) =>
  r.grade ? GRADE_WORDS[r.grade] : r.substate ? SUBSTATE_WORDS[r.substate] : STATE_WORDS[r.state as Ungraded];

/** The Why column (spec 7.2), in the same order as the State column. */
export const idWhy = (r: EndUserIdRow) =>
  r.grade ? GRADE_WHY[r.grade](r) : r.substate ? SUBSTATE_WHY[r.substate](r) : STATE_WHY[r.state as Ungraded](r);
