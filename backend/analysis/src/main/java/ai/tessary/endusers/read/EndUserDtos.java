// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The Users view reads. Codes and numbers only: the frontend words them. Days are UTC day numbers. Null means not
 * computed, never zero. A user ID appears only as its readable text, which is null once its last trace is gone.
 */
public final class EndUserDtos {

    private EndUserDtos() {}

    /**
     * The Users page top. Before the first snapshot the state is {@code collecting} and every judged field is null.
     *
     * @param asOf the snapshot day
     * @param historySince the day facts start
     */
    public record EndUserOverview(
            @JsonProperty("page_state") PageState pageState,
            @JsonProperty("page_state_reason") @Nullable PageStateReason pageStateReason,
            @JsonProperty("reason_numbers") Map<String, Double> reasonNumbers,
            @JsonProperty("as_of") @Nullable Integer asOf,
            @JsonProperty("history_since") @Nullable Integer historySince,
            @JsonProperty("counted") @Nullable Integer counted,
            @JsonProperty("set_aside") @Nullable Map<SetAsideReason, Integer> setAside,
            @JsonProperty("stages") @Nullable StageCounts stages,
            @JsonProperty("stop_stage_state") @Nullable StopStageState stopStageState,
            @JsonProperty("stop_stage_numbers") @Nullable Map<String, Double> stopStageNumbers,
            @JsonProperty("banners") List<Banner> banners,
            @JsonProperty("limits") List<LimitLine> limits,
            @JsonProperty("trust") @Nullable TrustNumbers trust) {}

    /**
     * What the judge stored for one issue as of the snapshot day.
     *
     * @param rank place in the issue list, from 1
     * @param usersHit covered hit user IDs on the snapshot day
     * @param groupId the group of findings that fire together; null when alone
     */
    public record EndUserIssueNumbers(
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("shown") boolean shown,
            @JsonProperty("rank") @Nullable Integer rank,
            @JsonProperty("user_ids_hit") int usersHit,
            @JsonProperty("compared") @Nullable Integer compared,
            @JsonProperty("lost") @Nullable Double lost,
            @JsonProperty("low") @Nullable Double low,
            @JsonProperty("high") @Nullable Double high,
            @JsonProperty("group_id") @Nullable String groupId,
            @JsonProperty("diagnostics") IssueDiagnostics diagnostics,
            @JsonProperty("curve") @Nullable List<CurvePoint> curve) {}

    /**
     * One issue of the list: the lineage's current case and its stored numbers.
     *
     * @param sinceDay the first day a covered user ID was hit
     */
    public record EndUserIssueRow(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_ref") String caseRef,
            @JsonProperty("title") String title,
            @JsonProperty("detector") String detector,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("since_day") @Nullable Integer sinceDay,
            @JsonProperty("issue") EndUserIssueNumbers issue) {}

    /** Cases only, in the stored rank. */
    public record EndUserIssueList(
            @JsonProperty("page_state") PageState pageState,
            @JsonProperty("as_of") @Nullable Integer asOf,
            @JsonProperty("issues") List<EndUserIssueRow> issues) {}

    /**
     * One issue by its case. {@code issue} is null before the first snapshot and for a case the last judge run did not
     * see.
     *
     * @param silentNow hit user IDs silent now; only with evidence of loss and few accounts
     */
    public record EndUserIssue(
            @JsonProperty("page_state") PageState pageState,
            @JsonProperty("as_of") @Nullable Integer asOf,
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_ref") String caseRef,
            @JsonProperty("title") String title,
            @JsonProperty("detector") String detector,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("case_state") String caseState,
            @JsonProperty("resolved_at") @Nullable String resolvedAt,
            @JsonProperty("since_day") @Nullable Integer sinceDay,
            @JsonProperty("silent_now") @Nullable Integer silentNow,
            @JsonProperty("issue") @Nullable EndUserIssueNumbers issue) {}

    /** @param weeksSinceHit whole weeks from the first hit to the snapshot day */
    public record EndUserSilentId(
            @JsonProperty("id_text") @Nullable String idText,
            @JsonProperty("weeks_since_hit") int weeksSinceHit,
            @JsonProperty("days_silent") @Nullable Integer daysSilent,
            @JsonProperty("stage") Stage stage,
            @JsonProperty("grade") @Nullable Grade grade) {}

    /**
     * The drawer. Empty, with the verdict, for every verdict but evidence of loss and few accounts.
     *
     * @param verdict null before the first snapshot and for a case the last judge run did not see
     */
    public record EndUserSilentIds(
            @JsonProperty("verdict") @Nullable Verdict verdict,
            @JsonProperty("ids") List<EndUserSilentId> ids,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /** @param hitBy case references of the issues with evidence of loss or few accounts that hit this user ID */
    public record EndUserIdRow(
            @JsonProperty("id_text") @Nullable String idText,
            @JsonProperty("state") @Nullable UserIdState state,
            @JsonProperty("substate") @Nullable Substate substate,
            @JsonProperty("grade") @Nullable Grade grade,
            @JsonProperty("days_silent") @Nullable Integer daysSilent,
            @JsonProperty("p_stop") @Nullable Double pStop,
            @JsonProperty("hit_by") List<String> hitBy) {}

    public record EndUserIdPage(
            @JsonProperty("page_state") PageState pageState,
            @JsonProperty("stage") Stage stage,
            @JsonProperty("ids") List<EndUserIdRow> ids,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /**
     * The Users block of the case page. The count is live; {@code issue} is what the last judge run stored.
     *
     * @param usersHit null for a metric shift
     * @param sinceDay the first day a counted user ID was hit
     * @param flaggedTraces refs that count as hits, with or without a user ID
     * @param refusal the page state when the page shows no stage numbers
     */
    public record EndUserCaseBlock(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_ref") String caseRef,
            @JsonProperty("page_state") PageState pageState,
            @JsonProperty("count_kind") CountKind countKind,
            @JsonProperty("user_ids_hit") @Nullable Integer usersHit,
            @JsonProperty("since_day") @Nullable Integer sinceDay,
            @JsonProperty("flagged_traces") int flaggedTraces,
            @JsonProperty("flagged_traces_no_user_id") int flaggedTracesNoUserId,
            @JsonProperty("refusal") @Nullable PageState refusal,
            @JsonProperty("issue") @Nullable EndUserIssueNumbers issue) {}

    /** One Triage chip. {@code lost}, {@code low} and {@code high} only with evidence of loss. */
    public record EndUserHitCount(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_ref") String caseRef,
            @JsonProperty("count_kind") CountKind countKind,
            @JsonProperty("user_ids_hit") @Nullable Integer usersHit,
            @JsonProperty("refusal") @Nullable PageState refusal,
            @JsonProperty("lost") @Nullable Double lost,
            @JsonProperty("low") @Nullable Double low,
            @JsonProperty("high") @Nullable Double high) {}

    /** The open cases and the cases resolved in the last 7 days. */
    public record EndUserHitCounts(@JsonProperty("cases") List<EndUserHitCount> cases) {}

    public record EndUserEraseRequest(
            @JsonProperty("user_id") @NotNull String userId) {}

    /** @param deletedRows rows removed across every table that holds the user key */
    public record EndUserErasure(
            @JsonProperty("deleted_rows") int deletedRows) {}
}
