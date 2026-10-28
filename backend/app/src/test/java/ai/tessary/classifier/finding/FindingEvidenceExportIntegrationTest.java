// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EarlierMessage;
import ai.tessary.classifier.frustration.JevFrustrationQuestion;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RateClassifierFixture;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The bulk evidence export against the real schema: text cut in SQL with a margin and masked before the final cut,
 * pages closed by a text budget without losing a row across the cursor, and every payload attribute carried.
 */
@SpringBootTest
record FindingEvidenceExportIntegrationTest(
        FindingRepository findings,
        FindingEvidenceRepository evidence,
        FindingService behaviorDrift,
        SubstrateV2Fixtures fx,
        RateClassifierFixture rates,
        TenantService tenants) {

    private static final String AWS = "AKIA" + "QYLPMN5HHHFPZAM2";
    private static final String AWS_MASKED = "AKIA…ZAM2";
    private static final long SIX_MIB = 6L * 1024 * 1024;

    /** Catches a SQL cut at exactly {@code text_chars}: the mask then sees half a key and {@code AKIAQYLPMN} leaks. */
    @Test
    @DisplayName("a credential that straddles the cut is masked before the export cuts it")
    void export_masksCredentialStraddlingTheCut() {
        String pid = project("finding-export-mask");
        String findingId = firing(pid, "gram-export-mask");
        String filler = "x".repeat(3_989);
        String input = filler + " " + AWS + " " + "y".repeat(100);
        member(pid, findingId, List.of(span(pid, "trace-mask", input, null)));

        FindingEvidenceRepository.ExportPage page = evidence.exportPage(
                pid,
                findingId,
                null,
                new FindingEvidenceRepository.ExportLimits(4_000, 2_000, SIX_MIB),
                null,
                false,
                false);

        assertEquals(1, page.rows().size());
        String exported = page.rows().get(0).input();
        assertEquals(filler + " " + AWS_MASKED + " " + EvidenceExportText.CUT_MARK, exported);
        assertFalse(String.valueOf(exported).contains("AKIAQYLPMN"), "the raw key reached the export");
    }

    /**
     * A row costs 1,000 + 512 = 1,512 against a 2,500 budget: one row is under it, the second reaches it and closes
     * the page, so 7 rows page as 2, 2, 2, 1. Catches a budget stop that seeds the cursor from a row it dropped
     * (skipped forever) or counts the over-fetched row (read twice).
     */
    @Test
    @DisplayName("a page closed by the text budget resumes on the next row, losing and repeating none")
    void export_byteBudgetPage_noRowLostAcrossCursor() {
        String pid = project("finding-export-budget");
        String findingId = firing(pid, "gram-export-budget");
        List<String> seeded = new ArrayList<>();
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String trace = "trace-budget-" + i;
            seeded.add(trace);
            refs.add(span(pid, trace, "a".repeat(1_000), null));
        }
        member(pid, findingId, refs);

        List<Integer> sizes = new ArrayList<>();
        List<String> read = new ArrayList<>();
        String cursor = null;
        do {
            FindingEvidenceRepository.ExportPage page = evidence.exportPage(
                    pid,
                    findingId,
                    null,
                    new FindingEvidenceRepository.ExportLimits(4_000, 2_000, 2_500),
                    cursor,
                    false,
                    false);
            sizes.add(page.rows().size());
            page.rows().forEach(r -> read.add(r.measured().traceId()));
            cursor = page.nextCursor();
        } while (cursor != null && sizes.size() < 10);

        assertEquals(List.of(2, 2, 2, 1), sizes);
        assertEquals(seeded, read);
    }

    /**
     * The repository reads 200 rows per query, so a page larger than that is read in more than one query. Catches the
     * next query starting on the wrong row, which drops or repeats rows at the join.
     */
    @Test
    @DisplayName("a page longer than one read keeps every row once and in order")
    void export_pageLongerThanOneReadKeepsEveryRow() {
        String pid = project("finding-export-chunks");
        String findingId = firing(pid, "gram-export-chunks");
        List<String> seeded = new ArrayList<>();
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            String trace = String.format(Locale.ROOT, "trace-chunk-%03d", i);
            seeded.add(trace);
            refs.add(FindingEvidenceRepository.Ref.trace(trace));
        }
        member(pid, findingId, refs);

        FindingEvidenceRepository.ExportPage page = evidence.exportPage(
                pid,
                findingId,
                null,
                new FindingEvidenceRepository.ExportLimits(4_000, 2_000, SIX_MIB),
                null,
                false,
                false);

        assertEquals(
                seeded, page.rows().stream().map(r -> r.measured().traceId()).toList());
        assertNull(page.nextCursor());
    }

    /** Catches a short allow-list leaking into the export, or a long value dropped instead of cut. */
    @Test
    @DisplayName("the export carries every payload attribute and marks a long value it cut")
    void export_carriesEveryAttributeAndMarksLongValues() {
        String pid = project("finding-export-attributes");
        String findingId = firing(pid, "gram-export-attributes");
        String attributes = "{\"deploy\":\"v1\",\"host\":\"h-1\",\"n\":3,\"ok\":true,"
                + "\"nested\":{\"region\":\"eu\"},\"prompt\":\"" + "z".repeat(5_000) + "\"}";
        member(pid, findingId, List.of(span(pid, "trace-attributes", "in", attributes)));

        FindingEvidenceRepository.ExportPage page = evidence.exportPage(
                pid,
                findingId,
                null,
                new FindingEvidenceRepository.ExportLimits(4_000, 2_000, SIX_MIB),
                null,
                false,
                false);

        assertEquals(1, page.rows().size());
        Map<String, Object> exported = page.rows().get(0).attributes();
        assertNotNull(exported);
        // jsonb keeps no key order, so the keys are compared as a set.
        assertEquals(Set.of("deploy", "host", "n", "ok", "nested", "prompt"), exported.keySet());
        assertEquals("v1", exported.get("deploy"));
        assertEquals("h-1", exported.get("host"));
        assertEquals(3, exported.get("n"));
        assertEquals(true, exported.get("ok"));
        assertEquals(Map.of("region", "eu"), exported.get("nested"));
        assertEquals("z".repeat(4_000) + EvidenceExportText.CUT_MARK, exported.get("prompt"));
    }

    /**
     * A frustration witness is a session row and a trace row; both carry the last four messages the detector judged
     * the flagged turn against, roles kept, and the member row carries none. Catches the session row (no trace, no
     * span, no payload) reading as a bare id, so the agent cannot see the exchange that led to the flag.
     */
    @Test
    @DisplayName("a frustration witness row carries the messages before the flagged turn")
    void export_sessionRowCarriesThePriorTurns() {
        String pid = rates.project("finding-export-frustration", Capability.FRUSTRATION);
        String findingId = frustrationFinding(
                pid,
                "{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"},"
                        + "{\"role\":\"user\",\"content\":\"c\"},{\"role\":\"assistant\",\"content\":\"d\"},"
                        + "{\"role\":\"user\",\"content\":\"e\"}");

        BehaviorDtos.EvidenceExportPage page = behaviorDrift.exportEvidence(pid, findingId, null, 4_000, null);

        List<EarlierMessage> expected = List.of(
                new EarlierMessage("assistant", "b"),
                new EarlierMessage("user", "c"),
                new EarlierMessage("assistant", "d"),
                new EarlierMessage("user", "e"));
        assertEquals(3, page.rows().size());
        for (BehaviorDtos.EvidenceExportRow row : page.rows()) {
            String label = row.measured().role() + ":" + row.measured().sessionId() + ":"
                    + row.measured().traceId();
            if (FindingEvidenceRow.Role.WITNESS.equals(row.measured().role())) {
                assertEquals(expected, row.priorMessages(), label);
            } else {
                assertNull(row.priorMessages(), label);
            }
        }
    }

    /**
     * A user pasted a key into a turn before the flagged one. Catches the prior messages exported as stored, which
     * puts the raw key in a file the export says is masked, and uncut, which overruns the page's text budget.
     */
    @Test
    @DisplayName("a credential in a message before the flagged turn is masked and the message cut")
    void export_masksCredentialInPriorMessages() {
        String pid = rates.project("finding-export-frustration-mask", Capability.FRUSTRATION);
        String findingId = frustrationFinding(
                pid,
                "{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"},"
                        + "{\"role\":\"user\",\"content\":\"my key is " + AWS + " " + "y".repeat(500) + "\"},"
                        + "{\"role\":\"assistant\",\"content\":\"d\"},{\"role\":\"user\",\"content\":\"e\"}");

        BehaviorDtos.EvidenceExportPage page = behaviorDrift.exportEvidence(pid, findingId, null, 200, null);

        String pasted = "my key is " + AWS_MASKED + " " + "y".repeat(500);
        EarlierMessage expected = new EarlierMessage("user", pasted.substring(0, 200) + EvidenceExportText.CUT_MARK);
        int witnesses = 0;
        for (BehaviorDtos.EvidenceExportRow row : page.rows()) {
            if (!FindingEvidenceRow.Role.WITNESS.equals(row.measured().role())) continue;
            witnesses++;
            List<EarlierMessage> prior = Objects.requireNonNull(row.priorMessages());
            assertEquals(expected, prior.get(1));
            assertFalse(prior.toString().contains("AKIAQYLPMN"), "the raw key reached the export");
        }
        assertEquals(2, witnesses);
    }

    /**
     * Prior messages are attached after the repository closes its page, so each witness row is charged their ceiling
     * up front. The member row costs its overhead and the first witness row reaches the budget, so the page closes
     * there and resumes on the second. Catches a page the messages push past its stated bound.
     */
    @Test
    @DisplayName("a witness row is charged its prior messages against the page budget")
    void export_chargesPriorMessagesToThePage() {
        String pid = rates.project("finding-export-frustration-charge", Capability.FRUSTRATION);
        String findingId = frustrationFinding(pid, "{\"role\":\"user\",\"content\":\"a\"}");

        FindingEvidenceRepository.ExportPage page = evidence.exportPage(
                pid,
                findingId,
                null,
                new FindingEvidenceRepository.ExportLimits(4_000, 2_000, 16_000, 16_000),
                null,
                false,
                false);

        assertEquals(
                List.of(FindingEvidenceRow.Role.MEMBER, FindingEvidenceRow.Role.WITNESS),
                page.rows().stream().map(r -> r.measured().role()).toList());
        assertNotNull(page.nextCursor(), "the second witness row is on the next page");
    }

    /**
     * A stored request whose earlier messages are not a list. Catches one malformed request failing the whole export
     * instead of leaving its rows without prior messages.
     */
    @Test
    @DisplayName("a stored request whose earlier messages do not parse exports its rows without them")
    void export_unreadablePriorMessagesExportNone() {
        String pid = rates.project("finding-export-frustration-unreadable", Capability.FRUSTRATION);
        String findingId = frustrationFindingRaw(pid, "\"not a list\"");

        BehaviorDtos.EvidenceExportPage page = behaviorDrift.exportEvidence(pid, findingId, null, 4_000, null);

        assertEquals(3, page.rows().size());
        for (BehaviorDtos.EvidenceExportRow row : page.rows()) {
            assertNull(row.priorMessages(), row.measured().role());
        }
    }

    /** A frustration finding on one conversation: a member session, and a witness session and turn. */
    private String frustrationFinding(String pid, String earlierMessages) {
        return frustrationFindingRaw(pid, "[" + earlierMessages + "]");
    }

    /** As {@link #frustrationFinding}, with the stored {@code earlier_messages} value given whole. */
    private String frustrationFindingRaw(String pid, String earlierMessages) {
        ClassifierRow signal = rates.builtIn(pid, BuiltInDetector.Kind.FRUSTRATION);
        String callSite = "cs-support";
        String conv = "conv-export";
        String trace = "trace-export-flagged";
        Instant onset = Instant.parse("2026-10-01T10:00:00Z");
        String findingId = Objects.requireNonNull(findings.recordRecomputedRate(
                        Ids.ulid(),
                        pid,
                        signal.classifierKey(),
                        CauseKey.frustration(signal.id(), callSite),
                        FindingRow.Cause.FRUSTRATION_RATE,
                        callSite,
                        FindingRow.SubjectKind.CLASSIFIER,
                        signal.id(),
                        signal.name(),
                        null,
                        1,
                        callSite,
                        onset.toString(),
                        "{}",
                        onset.plusSeconds(3_600).toString(),
                        onset.minusSeconds(3_600).toString(),
                        onset.plusSeconds(7_200).toString()))
                .findingId();
        rates.frustrationAssessment(
                pid,
                signal,
                trace,
                conv,
                callSite,
                onset.plusSeconds(60),
                true,
                JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD),
                "{\"state\":{\"current_user_message\":\"still broken\",\"earlier_messages\":" + earlierMessages
                        + "},\"questions\":[]}");
        String now = Instant.now().toString();
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.session(conv)),
                now);
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                List.of(FindingEvidenceRepository.Ref.session(conv), FindingEvidenceRepository.Ref.trace(trace)),
                now);
        return findingId;
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private FindingEvidenceRepository.Ref span(
            String pid, String traceId, @Nullable String input, @Nullable String attributes) {
        SubstrateV2Fixtures.SpanRef ref = fx.spanSeed(pid)
                .traceId(traceId)
                .kind("llm")
                .at(Instant.now())
                .payload(input, null, attributes)
                .writeRef();
        return FindingEvidenceRepository.Ref.span(ref.traceId(), ref.spanId());
    }

    private void member(String pid, String findingId, List<FindingEvidenceRepository.Ref> refs) {
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                refs,
                Instant.now().toString());
    }

    private String firing(String pid, String gram) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        pid,
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + gram,
                        gram,
                        1,
                        "cs-a",
                        "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}",
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }
}
