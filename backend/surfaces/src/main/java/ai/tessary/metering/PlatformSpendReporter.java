// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.config.MeteringProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.metering.LlmUsageQueryRepository.OrgSpend;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily platform-spend report — the thing that makes launch requirement H answerable.
 *
 * <p>H is done when we can answer <i>"what did last week cost, and which org drove it"</i> without
 * reading a provider invoice. Every other usage read in this package is scoped to a single org, because
 * every one of them answers a customer's question; none of them can answer a question whose whole
 * subject is the comparison <em>between</em> orgs. There is no platform-admin role to hang an endpoint
 * on either, so the operator's view is emitted rather than served: once per closed UTC day, one
 * structured line per org that spent platform money, plus a total. Loki already receives these as
 * queryable key-value pairs (see {@link StructuredLog}), so "last week, by org" is a range query over
 * {@code event="llm.spend.daily"} and needs nothing built.
 *
 * <p><b>It reports, it does not enforce.</b> Decision D6 leaves platform-paid LLM uncapped at launch, and
 * H4 asks only that a cap be a decision we can take on evidence rather than one we are forced into. The
 * threshold in {@link MeteringProperties#getSpendWarnUsdPerOrgPerDay()} therefore changes a log level and
 * nothing else — no request is refused, no lane is stopped. The lever that does exist is elsewhere and is
 * deliberate: {@code triage_automatic_enabled} is off by default, and when it is on the recurrence
 * bar decides what is worth a ruling. The per-tick and per-project caps that used to sit above it are
 * gone — they rationed which findings existed as work rather than what reached the launcher.
 *
 * <p><b>Closed days only.</b> The report covers the UTC day strictly before today, so a day's number is
 * stable once printed and re-running the process cannot produce two different figures for the same day.
 * Re-emission on restart is harmless — this writes nothing.
 *
 * <p><b>Platform funding only.</b> BYO spend is the customer's own provider bill; folding it into a report
 * about our costs would overstate them, which is the same reason {@code llm_call} keeps the two apart.
 */
@Component
public class PlatformSpendReporter {

    private static final Logger log = LoggerFactory.getLogger(PlatformSpendReporter.class);

    /** Money is rendered to the cent — this is an operator's read, not an accounting export. */
    private static final int CENTS = 2;

    private final LlmUsageQueryRepository usage;
    private final MeteringProperties props;
    private final TraceMdcBridge traceBridge;

    /** The last closed day already reported, so a heartbeat does not re-print the same day all day. */
    private volatile String lastReportedDay = "";

    public PlatformSpendReporter(LlmUsageQueryRepository usage, MeteringProperties props, TraceMdcBridge traceBridge) {
        this.usage = usage;
        this.props = props;
        this.traceBridge = traceBridge;
    }

    /**
     * Hourly heartbeat, emitting at most once per closed day. An hourly tick rather than a daily cron
     * because a backend that restarts at 00:30 would miss a midnight-only schedule entirely, and the
     * report is the only record of that day's spend.
     */
    @Scheduled(fixedDelayString = "${tessary.metering.spend-report-heartbeat-ms:3600000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            try {
                report(Instant.now());
            } catch (RuntimeException e) {
                log.error(Markers.OPS, "platform spend report failed", e);
            }
        }
    }

    /** Emit the report for the closed UTC day before {@code now}, unless it has already been emitted. */
    void report(Instant now) {
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
        String day = dayStart.toString();
        if (day.equals(lastReportedDay)) return;

        List<OrgSpend> rows = usage.platformSpendByOrg(
                day, dayStart.plus(1, ChronoUnit.DAYS).toString(), props.getSpendReportOrgLimit());
        lastReportedDay = day;

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal triageTotal = BigDecimal.ZERO;
        long unpriced = 0;
        for (OrgSpend r : rows) {
            total = total.add(r.costUsd());
            triageTotal = triageTotal.add(r.triageCostUsd());
            unpriced += r.unpricedCalls();
            emitOrg(day, r);
        }

        StructuredLog.info(log, Markers.OPS, "llm.spend.daily.total")
                .field("day", day)
                .field("orgs", rows.size())
                .field("costUsd", money(total))
                .field("triageCostUsd", money(triageTotal))
                .field("unpricedCalls", unpriced)
                .message(
                        "platform LLM spend for %s: $%s across %d orgs ($%s of it triage)%s",
                        day.substring(0, 10),
                        money(total),
                        rows.size(),
                        money(triageTotal),
                        unpriced > 0 ? ", " + unpriced + " calls unpriced" : "")
                .log();
    }

    /**
     * One org's line. WARN above the threshold so an unusual day is visible in a tail filtered to
     * warnings, without any of it changing what the platform will actually do.
     */
    private void emitOrg(String day, OrgSpend r) {
        double warnAt = props.getSpendWarnUsdPerOrgPerDay();
        boolean loud = warnAt > 0 && r.costUsd().doubleValue() >= warnAt;
        StructuredLog.Builder line = loud
                ? StructuredLog.warn(log, Markers.OPS, "llm.spend.daily")
                : StructuredLog.info(log, Markers.OPS, "llm.spend.daily");
        line.field("day", day)
                .field("orgId", r.orgId())
                .field("orgSlug", r.orgSlug())
                .field("calls", r.calls())
                .field("totalTokens", r.totalTokens())
                .field("costUsd", money(r.costUsd()))
                .field("triageRuns", r.triageRuns())
                .field("triageCostUsd", money(r.triageCostUsd()))
                // The size of the blind spot behind the figure: calls the pricing catalog held no rate
                // for. Non-zero means the real cost is HIGHER than the number on this line.
                .field("unpricedCalls", r.unpricedCalls())
                .field("warnThresholdUsd", warnAt, loud)
                .message(
                        "%s spent $%s of platform LLM on %s across %d calls%s%s",
                        r.orgSlug(),
                        money(r.costUsd()),
                        day.substring(0, 10),
                        r.calls(),
                        r.triageRuns() > 0
                                ? " (" + r.triageRuns() + " triages, $" + money(r.triageCostUsd()) + ")"
                                : "",
                        loud ? " — above the $" + warnAt + "/day reporting threshold" : "")
                .log();
    }

    private static String money(BigDecimal v) {
        return v.setScale(CENTS, RoundingMode.HALF_UP).toPlainString();
    }
}
