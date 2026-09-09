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
 * What the sample project actually shows a first-time user, asserted on the generated rows rather than
 * on the generator's intent. Every case here is a defect this dataset shipped with once: a reader who
 * opens "Sample project" is being shown a claim about what the product looks like in use, and a nonsense
 * ticket subject or a JSON blob in the preview column undoes that claim faster than an empty project
 * would have.
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

    /**
     * The two placeholders that used to be filled from one flat "products" pool, which produced
     * "How do I add teammates to Storage upgrade?" and "How to cancel API credits subscription".
     */
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

    /**
     * Every ticket ends on an action a reader can name — a drafted reply, a hand-off to tier 2, or a
     * completed export. It used to end 5% of the time straight after {@code classify_intent}, with an
     * {@code ok} status on every span, no reply and no error: not an abandoned conversation, just a
     * trace that reads as though the generator stopped halfway.
     */
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

    /** Every ticket's body refers to a date at or before the day the ticket was filed. */
    @Test
    void aTicketNeverReferencesADateAfterItself() {
        for (TraceV2Row trace : generate().traces()) {
            assertNotNull(trace.startedAt());
        }
        // The generator subtracts 2..10 days before formatting, so the only way this can regress is a
        // change that formats the trace's own start again — which the subject/body assertions below
        // would not catch on their own.
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
        // Only "previous" is true of what this generator computes; a "pinned" reference would put
        // "versus its own recent window (pinned reference)" into a finding's basis.
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
     * The defect that made the sample project's headline gesture fail: every seeded finding had zero
     * {@code finding_evidence} rows, so {@code RcaAnalysisService} threw before it started and a
     * "Run RCA" press returned {@code SUBJECT_NOT_FOUND} — a message naming the finding, over a
     * table nobody had written. Asserted on the generated stats rather than on the seeder, because
     * the sides have to come out of the SAME spans the drift numbers were computed from.
     */
    @Test
    void everyDriftStatCarriesBothEvidenceSides_soRcaCanRunOnIt() {
        for (SampleShowcase.DriftStat stat : generate().driftStats()) {
            assertFalse(
                    stat.baselineTraceIds().isEmpty(),
                    stat.classifierKey() + "/" + stat.subjectId() + " has no baseline to compare against");
            assertFalse(
                    stat.memberTraceIds().isEmpty(), stat.classifierKey() + "/" + stat.subjectId() + " flags nothing");
            // RcaAnalysisService.sides subtracts the flagged set from the baseline. A trace on both
            // sides would let the agent cite one trace as both halves of its own comparison.
            Set<String> overlap = new HashSet<>(stat.baselineTraceIds());
            overlap.retainAll(stat.memberTraceIds());
            assertTrue(overlap.isEmpty(), stat.subjectId() + " cites the same trace on both sides: " + overlap);
        }
    }

    /**
     * {@code tool_error} claims a FRACTION, and {@code sides} reads its witnesses as the flagged set
     * and its members as what that was a fraction of. Collapse the two and {@code failingCohortShape}
     * — the check whose whole job is describing what the failures share — gets handed the whole
     * population and reports the shape of ordinary traffic instead.
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
