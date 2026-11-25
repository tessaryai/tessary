// SPDX-License-Identifier: Apache-2.0
/*
 * The sample values of the Users boards P0 to P6, in the wire shapes of the end-users reads. The snapshot day is
 * Nov 24, 2026: C-118 opened Sep 22 is 63 days old and C-125 hit since Nov 20 is 4 days old, as on the boards.
 */
import type {
  EndUserBanner,
  EndUserCaseBlock,
  EndUserCurvePoint,
  EndUserHitCounts,
  EndUserIdPage,
  EndUserIdRow,
  EndUserIssue,
  EndUserIssueDiagnostics,
  EndUserIssueList,
  EndUserIssueNumbers,
  EndUserIssueRow,
  EndUserOverview,
  EndUserSilentId,
  EndUserSilentIds,
  EndUserStopStageState,
} from "../api/types";

/** The UTC day number of an ISO date, as the backend counts days. */
export const utcDay = (iso: string) => Date.parse(iso) / 86_400_000;

export const AS_OF = utcDay("2026-11-24");

export const NO_DIAGNOSTICS: EndUserIssueDiagnostics = {
  stopped: null,
  expected: null,
  upper: null,
  h: null,
  late_hits: null,
  unmatched: null,
  t_vis: null,
  pop_high: null,
  first_evidence_day: null,
  accounts: null,
  estimated: null,
  account_lines: null,
};

/** The similar user IDs still at risk in weeks 1 to 8 of the C-118 chart (P2). */
const SIMILAR_AT_RISK = [1012, 998, 981, 960, 944, 925, 903, 880];

/** Weekly steps 1 to n: the hit and similar lines, the board's band around the hit line and the at-risk rows. */
function curve(hit: number[], similar: number[], atRisk: number[]): EndUserCurvePoint[] {
  return hit.slice(1).map((h, i) => {
    const week = i + 1;
    const half = 0.03 + 0.006 * week;
    return {
      week,
      hit: h,
      similar: similar[week],
      band_low: h - half,
      band_high: Math.min(1, h + half),
      at_risk_hit: atRisk[i],
      at_risk_similar: SIMILAR_AT_RISK[i],
    };
  });
}

/** The numbers of one issue; a verdict with no number unless `over` gives one. */
export function issueNumbers(over: Partial<EndUserIssueNumbers>): EndUserIssueNumbers {
  return {
    verdict: "not_enough_evidence",
    shown: true,
    rank: null,
    user_ids_hit: 0,
    compared: null,
    lost: null,
    low: null,
    high: null,
    group_id: null,
    diagnostics: NO_DIAGNOSTICS,
    curve: null,
    ...over,
  };
}

export const C118_NUMBERS = issueNumbers({
  verdict: "evidence_of_loss",
  rank: 1,
  user_ids_hit: 112,
  compared: 96,
  lost: 23,
  low: 11,
  high: 35,
  diagnostics: {
    ...NO_DIAGNOSTICS,
    stopped: 41,
    expected: 18,
    h: 21,
    late_hits: 8,
    unmatched: 8,
  },
  curve: curve(
    [1, 0.88, 0.79, 0.72, 0.67, 0.63, 0.6, 0.58, 0.57],
    [1, 0.97, 0.94, 0.92, 0.9, 0.89, 0.88, 0.87, 0.86],
    [96, 90, 82, 71, 57, 46, 33, 21],
  ),
});

export const C121_NUMBERS = issueNumbers({
  rank: 2,
  user_ids_hit: 74,
  compared: 66,
  lost: 4,
  low: 0,
  high: 14,
  diagnostics: { ...NO_DIAGNOSTICS, stopped: 13, expected: 9, h: 21 },
  curve: curve(
    [1, 0.95, 0.91, 0.88, 0.86, 0.84, 0.83],
    [1, 0.97, 0.94, 0.92, 0.9, 0.89, 0.88],
    [66, 60, 52, 41, 30, 19],
  ),
});

const C119_NUMBERS = issueNumbers({
  rank: 3,
  user_ids_hit: 51,
  compared: 44,
  lost: 2,
  low: 0,
  high: 9,
  group_id: "g-119-126",
  diagnostics: { ...NO_DIAGNOSTICS, stopped: 8, expected: 6, h: 21 },
  curve: curve([1, 0.95, 0.91, 0.88, 0.86, 0.85], [1, 0.97, 0.94, 0.92, 0.9, 0.89], [44, 39, 31, 22, 13]),
});

export const C123_NUMBERS = issueNumbers({
  verdict: "always_present",
  rank: 4,
  user_ids_hit: 208,
  compared: 180,
  lost: 6,
  low: 0,
  high: 15,
  curve: curve(
    [1, 0.95, 0.91, 0.88, 0.86, 0.85, 0.84, 0.83, 0.82],
    [1, 0.97, 0.94, 0.92, 0.9, 0.89, 0.88, 0.87, 0.86],
    [180, 171, 160, 151, 140, 131, 120, 108],
  ),
});

export const C125_NUMBERS = issueNumbers({
  verdict: "cannot_measure_yet",
  shown: false,
  user_ids_hit: 27,
  diagnostics: { ...NO_DIAGNOSTICS, upper: 4, t_vis: 18 },
});

export const C127_NUMBERS = issueNumbers({
  verdict: "too_few_ids",
  shown: false,
  user_ids_hit: 19,
  compared: 17,
});

function row(
  caseRef: string,
  title: string,
  detector: string,
  subjectLabel: string,
  sinceDay: number | null,
  issue: EndUserIssueNumbers,
): EndUserIssueRow {
  return {
    case_id: `01JCASE${caseRef.replace("-", "")}`,
    case_ref: caseRef,
    title,
    detector,
    subject_label: subjectLabel,
    since_day: sinceDay,
    issue,
  };
}

/** P1: the issue list in rank order; C-119 and C-126 fire together and share one group. */
export const ISSUE_LIST: EndUserIssueList = {
  page_state: "ok",
  as_of: AS_OF,
  issues: [
    row(
      "C-118",
      "Agent forgets facts the user shared in earlier chats",
      "classifier",
      "Memory recall",
      utcDay("2026-09-22"),
      C118_NUMBERS,
    ),
    row(
      "C-121",
      "Replies take over 20 s in the evening",
      "metric_drift",
      "Latency shift",
      utcDay("2026-10-12"),
      C121_NUMBERS,
    ),
    row(
      "C-119",
      "Agent drops its persona and answers like a generic assistant",
      "classifier",
      "Persona",
      utcDay("2026-10-20"),
      C119_NUMBERS,
    ),
    row("C-126", "Agent switches language mid-chat", "classifier", "Language", utcDay("2026-10-20"), C119_NUMBERS),
    row("C-123", "Agent pushes back harshly when the user disagrees", "frustration", "Frustration", null, C123_NUMBERS),
    row(
      "C-125",
      "Agent asks the same question twice in one chat",
      "classifier",
      "Repetition",
      utcDay("2026-11-20"),
      C125_NUMBERS,
    ),
    row(
      "C-127",
      "Reminders arrive in the wrong time zone",
      "classifier",
      "Reminders",
      utcDay("2026-11-02"),
      C127_NUMBERS,
    ),
  ],
};

/** P1 top: an ok page with the funnel, the trust line and the how-counted numbers. */
export function overview(over: Partial<EndUserOverview> = {}): EndUserOverview {
  return {
    page_state: "ok",
    page_state_reason: null,
    reason_numbers: {},
    as_of: AS_OF,
    history_since: utcDay("2026-07-14"),
    counted: 1186,
    set_aside: { service: 31, aggregate: 11 },
    stages: {
      counted: 1186,
      active: 1014,
      new: 61,
      silent: 47,
      held: 0,
      stopped_named: 125,
      stopped_expected: 141,
      stopped_low: 118,
      stopped_high: 164,
    },
    stop_stage_state: "expected_and_named",
    stop_stage_numbers: {
      stopped_expected: 141,
      stopped_low: 118,
      stopped_high: 164,
      stopped_named: 125,
    },
    banners: [],
    limits: [],
    trust: { named_right: 0.93, named_found: 0.71, caveat: true },
    ...over,
  };
}

export const REFUSED: Partial<EndUserOverview> = {
  counted: null,
  set_aside: null,
  stages: null,
  stop_stage_state: null,
  stop_stage_numbers: null,
  trust: null,
};

/** P6: the page states that replace the funnel and the issue numbers. */
export const PAGE_STATES = {
  noUserId: overview({
    ...REFUSED,
    page_state: "no_user_id",
    page_state_reason: "low_id_share",
    reason_numbers: { ident_share: 0.12 },
    history_since: utcDay("2026-10-03"),
  }),
  noUserIdLast28: overview({
    ...REFUSED,
    page_state: "no_user_id",
    page_state_reason: "low_id_share_28",
    reason_numbers: { ident_share_28: 0.12 },
    history_since: utcDay("2026-10-03"),
  }),
  tooEarly: overview({
    ...REFUSED,
    page_state: "too_early",
    page_state_reason: "short_history",
    reason_numbers: {
      span_days: 14,
      ids: 16,
      span_days_needed: 21,
      ids_needed: 20,
    },
  }),
  heavyIds: overview({
    ...REFUSED,
    page_state: "ids_unreliable",
    page_state_reason: "heavy_ids",
    reason_numbers: {
      ids: 22,
      id_day_share: 0.4,
      sessions_per_day: 31,
      hours_per_day: 18,
    },
  }),
  idsReplaced: overview({
    ...REFUSED,
    page_state: "ids_unreliable",
    page_state_reason: "ids_replaced",
    reason_numbers: { ended: 35, continued: 34 },
  }),
  partialCoverage: overview({
    page_state: "partial_coverage",
    page_state_reason: "ids_set_aside",
    reason_numbers: { covered_share: 0.86, ident_share: 0.93 },
    set_aside: { service: 31, aggregate: 11 },
  }),
  notComputed: overview({
    ...REFUSED,
    page_state: "not_computed",
    as_of: null,
    history_since: null,
  }),
};

/** P6: the states of the Probably stopped stage, each with its numbers. */
export const STOP_STAGES: Record<
  Exclude<EndUserStopStageState, "expected_and_named">,
  { state: EndUserStopStageState; numbers: Record<string, number> }
> = {
  not_judged_yet: {
    state: "not_judged_yet",
    numbers: { anchors: 2, anchors_needed: 4, days_needed: 14 },
  },
  cannot_tell_yet: {
    state: "cannot_tell_yet",
    numbers: { return_cycle_days: 42 },
  },
  history_too_short: {
    state: "history_too_short",
    numbers: { horizon_days: 90, horizon_days_needed: 120 },
  },
  mixed_id_formats: {
    state: "mixed_id_formats",
    numbers: { one_day_share: 0.41 },
  },
  expected_only: {
    state: "expected_only",
    numbers: {
      stopped_expected: 141,
      stopped_low: 118,
      stopped_high: 164,
      named_right: 0.71,
      named_right_needed: 0.8,
    },
  },
  cannot_be_named_yet: {
    state: "cannot_be_named_yet",
    numbers: {
      stopped_expected: 230,
      stopped_low: 190,
      stopped_high: 270,
      stopped_named: 58,
      plateau: 0.64,
    },
  },
};

/** P6: the banners above the funnel, one per fact. */
export const BANNERS: Record<"groupOrKeyIds" | "sharedSilence" | "idLoss" | "seasonLevel", EndUserBanner> = {
  groupOrKeyIds: {
    code: "group_or_key_ids",
    numbers: { ids: 18, id_day_share: 0.09 },
    routes: [],
  },
  sharedSilence: {
    code: "shared_silence",
    numbers: {
      start_day: utcDay("2026-11-10"),
      share_silent: 0.31,
      share_usual: 0.09,
      ids: 212,
      held_until_day: utcDay("2026-12-08"),
      stopped_low: 40,
      stopped_high: 118,
    },
    routes: [],
  },
  idLoss: {
    code: "id_loss",
    numbers: {
      onset_day: utcDay("2026-11-03"),
      ids: 60,
      share_after: 0.38,
      share_before: 0.02,
    },
    routes: ["companion.reply"],
  },
  seasonLevel: {
    code: "season_level",
    numbers: { level: 0.72, since_day: utcDay("2026-11-08") },
    routes: [],
  },
};

/** P2: the issue page of C-118. */
export const C118_ISSUE: EndUserIssue = {
  page_state: "ok",
  as_of: AS_OF,
  case_id: "01JCASEC118",
  case_ref: "C-118",
  title: "Agent forgets facts the user shared in earlier chats",
  detector: "classifier",
  subject_label: "Memory recall",
  case_state: "open",
  resolved_at: null,
  since_day: utcDay("2026-09-22"),
  silent_now: 46,
  issue: C118_NUMBERS,
};

const silent = (id_text: string, weeks_since_hit: number, days_silent: number, grade: EndUserSilentId["grade"]) =>
  ({
    id_text,
    weeks_since_hit,
    days_silent,
    stage: grade === "stopped" || grade === "probably_stopped" ? "stopped" : "silent",
    grade,
  }) satisfies EndUserSilentId;

/** P3: the first page of the drawer, oldest hit first; 38 more follow, hit 1 to 4 weeks ago. */
export const C118_SILENT_IDS: EndUserSilentIds = {
  verdict: "evidence_of_loss",
  ids: [
    silent("usr_7Hq2kD", 8, 52, "stopped"),
    silent("usr_0Fz81r", 8, 49, "stopped"),
    silent("usr_Mx4a7Q", 7, 41, "stopped"),
    silent("usr_Jt8c2W", 6, 38, "stopped"),
    silent("usr_Rb1n6E", 6, 33, "probably_stopped"),
    silent("usr_Gk5p3Z", 5, 29, "probably_stopped"),
    silent("usr_Sv9d1H", 5, 24, "probably_stopped"),
    silent("usr_Ye2m8T", 5, 19, null),
  ],
  more: 38,
  more_weeks_low: 1,
  more_weeks_high: 4,
  next_cursor: "c-2",
};

const idRow = (id_text: string, grade: EndUserIdRow["grade"], days_silent: number, p_stop: number, hitBy: string) =>
  ({
    id_text,
    state: "stopped",
    substate: null,
    grade,
    days_silent,
    p_stop,
    hit_by: hitBy ? [hitBy] : [],
  }) satisfies EndUserIdRow;

/** P5: the first page of the Probably stopped list, most days silent first; 116 more follow. */
export const STOPPED_IDS: EndUserIdPage = {
  page_state: "ok",
  stage: "stopped",
  ids: [
    idRow("usr_7Hq2kD", "stopped", 52, 0.94, "C-118"),
    idRow("usr_0Fz81r", "stopped", 49, 0.94, "C-118"),
    idRow("usr_Qa2k8R", "stopped", 47, 0.93, ""),
    idRow("usr_Wn6e1F", "stopped", 44, 0.92, ""),
    idRow("usr_Mx4a7Q", "stopped", 41, 0.91, "C-118"),
    idRow("usr_Bh4t7Y", "stopped", 38, 0.9, ""),
    idRow("usr_Rb1n6E", "probably_stopped", 33, 0.74, "C-118"),
    idRow("usr_Kc8m3D", "probably_stopped", 27, 0.71, ""),
    idRow("usr_Ux1p5S", "probably_stopped", 23, 0.66, ""),
  ],
  next_cursor: "c-2",
};

/** P4: the Users block of the C-118 case page. */
export const C118_CASE_BLOCK: EndUserCaseBlock = {
  case_id: "01JCASEC118",
  case_ref: "C-118",
  page_state: "ok",
  count_kind: "covered",
  user_ids_hit: 112,
  since_day: utcDay("2026-09-22"),
  flagged_traces: 1406,
  flagged_traces_no_user_id: 9,
  refusal: null,
  issue: C118_NUMBERS,
};

/** P0: the Triage chips; only C-118 carries a loss. */
export const HIT_COUNTS: EndUserHitCounts = {
  cases: [
    {
      case_id: "01JCASEC118",
      case_ref: "C-118",
      count_kind: "covered",
      user_ids_hit: 112,
      refusal: null,
      lost: 23,
      low: 11,
      high: 35,
    },
    {
      case_id: "01JCASEC125",
      case_ref: "C-125",
      count_kind: "covered",
      user_ids_hit: 27,
      refusal: null,
      lost: null,
      low: null,
      high: null,
    },
    {
      case_id: "01JCASEC121",
      case_ref: "C-121",
      count_kind: "covered",
      user_ids_hit: 74,
      refusal: null,
      lost: null,
      low: null,
      high: null,
    },
    {
      case_id: "01JCASEC119",
      case_ref: "C-119",
      count_kind: "covered",
      user_ids_hit: 51,
      refusal: null,
      lost: null,
      low: null,
      high: null,
    },
    {
      case_id: "01JCASEC127",
      case_ref: "C-127",
      count_kind: "covered",
      user_ids_hit: 19,
      refusal: null,
      lost: null,
      low: null,
      high: null,
    },
    {
      case_id: "01JCASEC123",
      case_ref: "C-123",
      count_kind: "covered",
      user_ids_hit: 208,
      refusal: null,
      lost: null,
      low: null,
      high: null,
    },
  ],
};
