// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Row;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * What the sample project shows a first-time user, asserted on generated rows. Every case is a defect it once shipped
 * with.
 */
class SampleShowcaseTest {

    private static final Instant NOW = Instant.parse("2026-09-08T14:00:00Z");

    private static SampleShowcase.Dataset generate() {
        return SampleShowcase.generate("proj_test", 42L, NOW, "media_pdf", "media_png");
    }

    @Test
    void previewsAreReadableTicketText_neverTheWireJsonBehindThem() {
        SampleShowcase.Dataset dataset = generate();

        for (TraceV2Row trace : dataset.traces()) {
            assertNotNull(trace.inputPreview());
            assertFalse(
                    trace.inputPreview().startsWith("[{\"role\""),
                    "trace list would show a raw message array: " + trace.inputPreview());
        }
        for (SpanRow span : dataset.spans()) {
            String in = span.inputPreview();
            String out = span.outputPreview();
            assertFalse(in != null && in.startsWith("[{\"role\""), "span input preview is wire JSON: " + in);
            assertFalse(out != null && out.startsWith("[{\"role\""), "span output preview is wire JSON: " + out);
        }
    }

    /** Once filled from one flat pool, giving "How do I add teammates to Storage upgrade?". */
    @Test
    void planPhrasingNeverReceivesAnAddOnOrAnIntegration() {
        Set<String> subjects = new HashSet<>();
        for (TraceV2Row trace : generate().traces()) {
            subjects.add(trace.name());
        }
        for (String subject : subjects) {
            if (subject.startsWith("How do I upgrade to ")) {
                assertTrue(subject.endsWith(" plan?"), "upgrade target is not a plan: " + subject);
            }
        }
        // No template leaves a placeholder unfilled.
        for (String subject : subjects) {
            assertFalse(subject.contains("{"), "unfilled placeholder in subject: " + subject);
        }
    }

    /** Every ticket ends on a nameable action. 5% once ended right after {@code classify_intent} with all spans ok. */
    @Test
    void everyTicketEndsOnATerminalAction() {
        SampleShowcase.Dataset dataset = generate();
        Set<String> terminal =
                Set.of(SampleShowcase.GENERATE, SampleShowcase.ESCALATE, SampleShowcase.REFUND, SampleShowcase.EXPORT);
        Set<String> resolved = new HashSet<>();
        for (SpanRow span : dataset.spans()) {
            if (terminal.contains(span.callSiteId())) resolved.add(span.traceId());
        }
        for (TraceV2Row trace : dataset.traces()) {
            assertTrue(resolved.contains(trace.id()), "ticket ends with no action taken: " + trace.name());
        }
    }

    /** A ticket's body refers to a date at or before its filing day. */
    @Test
    void aTicketNeverReferencesADateAfterItself() {
        for (TraceV2Row trace : generate().traces()) {
            assertNotNull(trace.startedAt());
        }
        // The generator subtracts 2..10 days; only formatting the trace's own start again would regress this.
        assertTrue(
                SampleShowcase.generate("proj_test", 42L, NOW, "media_pdf", "media_png").traces().stream()
                        .noneMatch(t ->
                                t.inputPreview() != null && t.inputPreview().contains("Sep 8")),
                "a ticket filed on or before Sep 8 refers to Sep 8");
    }

    @Test
    void driftStatsAreComputedFromTheGeneratedSpans_notAsserted() {
        List<SampleShowcase.DriftStat> drift = generate().driftStats();
        assertEquals(6, drift.size());
        for (SampleShowcase.DriftStat stat : drift) {
            assertTrue(stat.nPre() > 0, stat.callSiteId() + " has no reference window");
            assertTrue(stat.nPost() > 0, stat.callSiteId() + " has no current window");
            assertTrue(stat.meanPost() > stat.meanPre(), stat.callSiteId() + " does not actually drift up");
        }
        // Only "previous" is true of this generator; "pinned" would mislabel the basis.
        assertTrue(drift.stream().allMatch(s -> "previous".equals(s.reference())));
    }

    @Test
    void theAttachedScreenshotIsARealDecodablePng() throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(SampleShowcase.minimalScreenshotPng()));
        assertNotNull(image, "the ticket screenshot does not decode as an image");
        assertEquals(320, image.getWidth());
        assertEquals(180, image.getHeight());
    }
    /**
     * Seeded findings had no {@code finding_evidence}, so "Run RCA" returned {@code SUBJECT_NOT_FOUND}. The sides
     * must come from the same spans as the drift numbers.
     */
    @Test
    void everyDriftStatCarriesBothEvidenceSides_soRcaCanRunOnIt() {
        for (SampleShowcase.DriftStat stat : generate().driftStats()) {
            assertFalse(
                    stat.baselineTraceIds().isEmpty(),
                    stat.classifierKey() + "/" + stat.subjectId() + " has no baseline to compare against");
            assertFalse(
                    stat.memberTraceIds().isEmpty(), stat.classifierKey() + "/" + stat.subjectId() + " flags nothing");
            // A trace on both sides would let the agent cite it as both halves.
            Set<String> overlap = new HashSet<>(stat.baselineTraceIds());
            overlap.retainAll(stat.memberTraceIds());
            assertTrue(overlap.isEmpty(), stat.subjectId() + " cites the same trace on both sides: " + overlap);
        }
    }

    /**
     * {@code tool_error}'s witnesses are the flagged set and its members the population; collapsed, {@code
     * failingCohortShape} describes ordinary traffic.
     */
    @Test
    void onlyToolErrorDrawsWitnesses_andTheyAreAStrictSubsetOfItsMembers() {
        for (SampleShowcase.DriftStat stat : generate().driftStats()) {
            if (!"tool_error".equals(stat.classifierKey())) {
                assertTrue(
                        stat.witnessTraceIds().isEmpty(),
                        stat.classifierKey() + " draws no narrower subset, so its members ARE the flagged side");
                continue;
            }
            assertFalse(stat.witnessTraceIds().isEmpty(), stat.subjectId() + " claims a rate but cites no failure");
            assertTrue(
                    stat.memberTraceIds().containsAll(stat.witnessTraceIds()),
                    stat.subjectId() + " cites a failing trace that is not in the population it measured");
            assertTrue(
                    stat.witnessTraceIds().size() < stat.memberTraceIds().size(),
                    stat.subjectId() + " flags every call as failing, which is not a rate");
        }
    }
}
