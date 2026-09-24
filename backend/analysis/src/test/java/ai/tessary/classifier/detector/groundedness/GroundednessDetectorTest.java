// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.EncoderScorer.Response;
import ai.tessary.classifier.detector.EncoderScorer.ResponseScore;
import ai.tessary.classifier.detector.EncoderScorer.Span;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Unit coverage for the Groundedness built-in under the v5 "unsupported" contract: fires when the
 * token head finds the answer unsupported by its evidence, only on call sites gated in by shape, and
 * skips (never scores clean) blank fields, call sites with no/ungrounded shape, answers that assert
 * nothing checkable, and rag_answer turns whose conversation reached outside but captured nothing
 * readable. The encoder is faked at {@link EncoderScorer#scoreResponses}: what the detector SENDS
 * (passages as a list, the question, the whole answer) is asserted as carefully as what it does with
 * the score, because the head's input layout is part of the model.
 *
 * <p>Since v7 there is one threshold, and the sweep path ({@link GroundednessDetector#sweepBatch})
 * writes one assessment per scored answer, flagged or not; the assessments are recorded in memory
 * here and written for real in {@code GroundednessAssessmentIntegrationTest}.
 *
 * <p>{@code ai.tessary.testsupport.ClassifierObservations}'s two envelope builders are inlined below
 * as {@link #userInput} / {@link #assistantOutput}: this module's tests do not depend on {@code app},
 * and two one-line JSON builders are no reason to start.
 */
class GroundednessDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Set<String>> shapeLookups = new ArrayList<>();
    private final RecordingAssessments assessments = new RecordingAssessments();

    private static final ClassifierRow SIGNAL = new ClassifierRow(
            "cls-1",
            "p",
            "groundedness",
            "Groundedness",
            null,
            BuiltInDetector.Kind.GROUNDEDNESS,
            null,
            true,
            7,
            true,
            ClassifierRow.Mode.TRACKING,
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z");

    /** The assessments a sweep wrote, in order. */
    private static final class RecordingAssessments extends GroundednessAssessmentRepository {
        final List<Assessment> rows = new ArrayList<>();

        RecordingAssessments() {
            super(Mockito.mock(JdbcClient.class));
        }

        @Override
        public boolean insert(Assessment a) {
            rows.add(a);
            return true;
        }
    }

    /** A user turn as a gen_ai input envelope: {@code [{"role":"user","content":"…"}]}. */
    private static String userInput(String text) {
        return envelope("user", text);
    }

    /** An assistant turn as a gen_ai output envelope: {@code [{"role":"assistant","content":"…"}]}. */
    private static String assistantOutput(String text) {
        return envelope("assistant", text);
    }

    private static String envelope(String role, String text) {
        return "[{\"role\":\"" + role + "\",\"content\":" + quote(text) + "}]";
    }

    /** Minimal JSON string escaping for the fixture (quotes and backslashes). */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A head verdict with one whole-answer span, so {@code claim} in the firing is the answer. */
    private static ResponseScore verdict(double unsupported, double conflict, int answerLength) {
        return new ResponseScore(unsupported, conflict, List.of(new Span(0, answerLength, unsupported, conflict)));
    }

    /** A fake head that returns {@code unsupportedScores} in order and records what it was sent. */
    private static EncoderScorer head(List<Double> unsupportedScores, List<Response> seen) {
        return new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException("groundedness never calls score()");
            }

            @Override
            public List<Double> scorePairs(String head, List<Pair> pairs) {
                throw new AssertionError("groundedness is a token head since v5 — pairs must not be sent");
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                assertEquals("groundedness", head);
                seen.addAll(responses);
                List<ResponseScore> out = new ArrayList<>();
                for (int i = 0; i < responses.size(); i++) {
                    double u = unsupportedScores.get(i);
                    out.add(verdict(u, u / 2, responses.get(i).answer().length()));
                }
                return out;
            }
        };
    }

    private static EncoderScorer never(String why) {
        return new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                throw new AssertionError(why);
            }
        };
    }

    private GroundednessDetector detector(Map<String, String> shapeBySite, List<Double> unsupportedScores) {
        GroundingEvidenceReads evidence = (projectId, ids) -> Map.of();
        CallSiteShapeReads shapes = (projectId, ids) -> {
            shapeLookups.add(ids);
            return shapeBySite.entrySet().stream()
                    .filter(e -> ids.contains(e.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        };
        return new GroundednessDetector(
                head(unsupportedScores, new ArrayList<>()), shapes, evidence, assessments, mapper);
    }

    private static SubstrateObservation obs(
            @Nullable String callSiteId, @Nullable String input, @Nullable String output) {
        String storedInput = input == null ? null : userInput(input);
        String storedOutput = output == null ? null : assistantOutput(output);
        return new SubstrateObservation(
                "obs-1",
                "p",
                "t",
                "s",
                null,
                callSiteId,
                "llm",
                "chat",
                storedInput,
                storedOutput,
                null,
                "2026-01-01T00:00:00Z");
    }

    private static SubstrateObservation obs(
            String spanId, @Nullable String callSiteId, @Nullable String input, @Nullable String output) {
        SubstrateObservation o = obs(callSiteId, input, output);
        return new SubstrateObservation(
                spanId,
                o.projectId(),
                o.traceId(),
                o.sessionId(),
                o.projectVersionId(),
                o.callSiteId(),
                o.kind(),
                o.name(),
                o.input(),
                o.output(),
                o.toolError(),
                o.createdAt());
    }

    private static GroundingEvidenceReads docs(String... documents) {
        return (projectId, ids) -> Map.of("obs-1", new GroundingEvidenceReads.Evidence(List.of(documents), true));
    }

    @Test
    void highUnsupportedFiresOnAGroundedShape() throws Exception {
        String answer = "The candidate knows Java and GCP.";
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.99));
        Detection detection = d.detect(obs("cs-1", "5 years of Python and AWS experience.", answer), null);
        assertTrue(detection.fired());
        assertEquals(Detection.Severity.WARN, detection.severity());
        assertEquals(Detection.Confidence.HIGH, detection.confidence(), "0.99 is past the 0.975 high bar");
        // The fake head's conflict is half its unsupported, and its one span is the whole answer. An extract
        // call site with no retrieved documents is checked against its prompt.
        assertEquals(
                mapper.readTree("{\"head\":\"groundedness\",\"unsupported\":0.99,\"conflict\":0.495,"
                        + "\"premise_had_evidence\":false,"
                        + "\"flagged_sentences\":[{\"start\":0,\"end\":" + answer.length() + ",\"unsupported\":0.99}],"
                        + "\"claim\":\"" + answer + "\"}"),
                mapper.readTree(assertNotNull2(detection.evidenceJson())));
    }

    @Test
    void lowUnsupportedStaysQuiet() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.02));
        Detection detection =
                d.detect(obs("cs-1", "5 years of Python and AWS experience.", "The candidate knows Python."), null);
        assertFalse(detection.fired());
    }

    @Test
    @DisplayName("one threshold: 0.975 is flagged, just under it is scored clean, and there is no low band")
    void theThresholdIsTheOnlyBar() {
        String input = "5 years of Python and AWS experience.";
        String answer = "The candidate knows Java.";
        Detection at = detector(Map.of("cs-1", "extract"), List.of(0.975)).detect(obs("cs-1", input, answer), null);
        assertTrue(at.fired(), "0.975 reaches the threshold");
        assertEquals(Detection.Confidence.HIGH, at.confidence(), "every detection is HIGH");
        assertFalse(detector(Map.of("cs-1", "extract"), List.of(0.97))
                .detect(obs("cs-1", input, answer), null)
                .fired());
        assertFalse(
                detector(Map.of("cs-1", "extract"), List.of(0.7))
                        .detect(obs("cs-1", input, answer), null)
                        .fired(),
                "0.7 was the old review band; it no longer fires");
    }

    @Test
    void ungatedShapeNeverScoresOrCallsTheEncoder() {
        GroundednessDetector d = detector(Map.of("cs-1", "draft"), List.of());
        Detection detection = d.detect(obs("cs-1", "write me a poem about the sea", "Waves crash..."), null);
        assertFalse(detection.fired(), "an open-generation shape has no source content to be ungrounded from");
    }

    @Test
    void noCallSiteOrNoShapeIsSkippedNotScoredClean() {
        GroundednessDetector d = detector(Map.of(), List.of());
        assertFalse(d.detect(obs(null, "some input", "some output"), null).fired());
        assertFalse(
                d.detect(obs("cs-unknown", "some input", "some output"), null).fired());
    }

    @Test
    void blankInputOrOutputIsSkipped() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of());
        assertFalse(d.detect(obs("cs-1", null, "some output"), null).fired());
        assertFalse(d.detect(obs("cs-1", "some input", null), null).fired());
    }

    /**
     * The skipped row comes first, so the flagged row's place among the scored ones (0) differs from its place in
     * the batch (1): a firing written at the scored index would land on the skipped observation.
     */
    @Test
    void batchStaysIndexAlignedAndOnlyGatedRowsAreScored() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract", "cs-2", "draft"), List.of(0.99, 0.05));
        List<Detection> ds = d.detectBatch(
                List.of(
                        obs("cs-2", "write a poem", "roses are red"), // ungated -> skipped
                        obs("cs-1", "the doc says X", "the doc says Y"), // gated, unsupported -> fires
                        obs("cs-1", "the doc says X", "the doc says X")), // gated, supported -> quiet
                null);
        assertEquals(
                List.of(false, true, false), ds.stream().map(Detection::fired).toList());
    }

    @Test
    @DisplayName("document-in-prompt: the prompt (system AND user text) is the one passage, with no question")
    void promptPremiseIncludesSystemMessageContentAndIsSentAsOnePassage() throws Exception {
        List<Response> seen = new ArrayList<>();
        GroundingEvidenceReads evidence = (projectId, ids) -> Map.of();
        CallSiteShapeReads shapes = (projectId, ids) -> Map.of("cs-1", "extract");
        GroundednessDetector d =
                new GroundednessDetector(head(List.of(0.99), seen), shapes, evidence, assessments, mapper);
        String storedInput = "[{\"role\":\"system\",\"content\":\"here is the document: refunds take 5-7 days\"},"
                + "{\"role\":\"user\",\"content\":\"how long do refunds take?\"}]";
        SubstrateObservation o = new SubstrateObservation(
                "obs-1",
                "p",
                "t",
                "s",
                null,
                "cs-1",
                "llm",
                "chat",
                storedInput,
                assistantOutput("Refunds take 5-7 business days."),
                null,
                "2026-01-01T00:00:00Z");
        Detection got = d.detect(o, null);
        // One passage, the system and user text in message order a line apart; no separate question, since
        // the prompt already carries it.
        assertEquals(
                List.of(new Response(
                        List.of("here is the document: refunds take 5-7 days\nhow long do refunds take?"),
                        null,
                        "Refunds take 5-7 business days.")),
                seen);
        assertTrue(got.fired());
        assertEquals(
                false,
                mapper.readTree(assertNotNull2(got.evidenceJson()))
                        .get("premise_had_evidence")
                        .asBoolean(true),
                "checked against its prompt, not retrieved documents: the page and the RCA dossier say which");
    }

    @Test
    void configThresholdOverridesTheDefault() {
        String answer = "Refunds take 5-7 business days.";
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.9));
        assertFalse(
                d.detect(obs("cs-1", "how long do refunds take?", answer), null).fired());
        Detection lowered = detector(Map.of("cs-1", "extract"), List.of(0.9))
                .detect(obs("cs-1", "how long do refunds take?", answer), "{\"threshold\":0.85}");
        assertTrue(lowered.fired(), "0.9 clears a lowered 0.85 threshold");
        assertEquals(Detection.Confidence.HIGH, lowered.confidence());
    }

    @Test
    @DisplayName("an older blob's threshold_high is read when threshold is absent, and threshold wins over it")
    void anOlderBlobsThresholdHighIsStillRead() {
        GroundednessDetector d = detector(Map.of(), List.of());
        assertEquals(0.975, d.threshold(null), 1e-9);
        assertEquals(0.975, d.threshold("{\"arl_target\":50000}"), 1e-9, "a foreign key leaves the default");
        assertEquals(0.9, d.threshold("{\"threshold_high\":0.9,\"threshold_low\":0.5}"), 1e-9);
        assertEquals(0.95, d.threshold("{\"threshold\":0.95,\"threshold_high\":0.9}"), 1e-9);
    }

    @Test
    void everyScoredObservationWritesOneAssessmentRowFlaggedOrNot() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract", "cs-2", "extract"), List.of(0.99, 0.05));
        List<Detection> ds = d.sweepBatch(
                SIGNAL,
                List.of(
                        obs("obs-a", "cs-1", "the doc says X", "the doc says Y"),
                        obs("obs-b", "cs-2", "the doc says X", "the doc says X")),
                null);
        assertTrue(ds.get(0).fired());
        assertFalse(ds.get(1).fired());
        assertEquals(2, assessments.rows.size(), "a clean answer is a trial too");
        GroundednessAssessmentRepository.Assessment flagged = assessments.rows.get(0);
        GroundednessAssessmentRepository.Assessment clean = assessments.rows.get(1);
        assertEquals("obs-a", flagged.spanId());
        assertTrue(flagged.flagged());
        assertEquals(0.99, flagged.unsupported(), 1e-9);
        assertEquals("cs-1", flagged.callSiteId());
        assertEquals("cls-1", flagged.classifierId());
        assertEquals("t", flagged.traceId());
        assertEquals(GroundednessDetector.scorerVersion(0.975), flagged.scorerVersion());
        assertEquals("obs-b", clean.spanId());
        assertFalse(clean.flagged());
        assertEquals(0.05, clean.unsupported(), 1e-9);
    }

    @Test
    void detectBatchScoresWithoutWritingAnything() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.99));
        assertTrue(d.detectBatch(List.of(obs("cs-1", "the doc says X", "the doc says Y")), null)
                .get(0)
                .fired());
        assertTrue(assessments.rows.isEmpty(), "only the sweep records trials");
    }

    @Test
    @DisplayName("a skipped observation, or one the encoder refused, writes nothing: it is not a trial")
    void aSkippedObservationWritesNothing() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract", "cs-2", "draft"), List.of());
        d.sweepBatch(
                SIGNAL,
                List.of(
                        obs("obs-a", "cs-2", "write a poem", "roses are red"), // ungated shape
                        obs("obs-b", "cs-1", "some input", null), // blank answer
                        obs("obs-c", null, "some input", "some output"), // no call site
                        // nothing checkable
                        obs("obs-d", "cs-1", "thanks!", "You're welcome — anything else I can help with?")),
                null);
        assertTrue(assessments.rows.isEmpty(), String.valueOf(assessments.rows));

        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundingEvidenceReads blind =
                (projectId, ids) -> Map.of("obs-1", new GroundingEvidenceReads.Evidence(List.of(), true));
        new GroundednessDetector(never("an abstained turn must not reach the head"), rag, blind, assessments, mapper)
                .sweepBatch(SIGNAL, List.of(obs("cs-rag", "where is my order?", "It shipped on 3 March.")), null);
        assertTrue(assessments.rows.isEmpty(), "a turn with no readable evidence abstains");

        EncoderScorer refuses = new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                return List.of(ResponseScore.UNSCORED);
            }
        };
        CallSiteShapeReads extract = (projectId, ids) -> Map.of("cs-1", "extract");
        GroundingEvidenceReads none = (projectId, ids) -> Map.of();
        new GroundednessDetector(refuses, extract, none, assessments, mapper)
                .sweepBatch(SIGNAL, List.of(obs("cs-1", "the doc says X", "the doc says Y")), null);
        assertTrue(assessments.rows.isEmpty(), "an answer too long for the model has no verdict");
    }

    /**
     * Rows written under two thresholds must not mix in one rate. That a rescore under one threshold inserts once
     * is the unique index's job, covered against Postgres in {@code GroundednessAssessmentIntegrationTest}.
     */
    @Test
    void aNewThresholdGivesANewScorerVersion() {
        assertNotEquals(GroundednessDetector.scorerVersion(0.975), GroundednessDetector.scorerVersion(0.95));
    }

    @Test
    void flaggedSentencesListEverySentenceAtOrAboveTheThresholdWithOffsets() throws Exception {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        String s1 = "Refunds are issued within 5-7 business days. ";
        String s2 = "Extended Warranty KB-77 covers you. ";
        String s3 = "It lasts 24 months.";
        String answer = s1 + s2 + s3;
        int a = s1.length();
        int b = a + s2.length();
        EncoderScorer threeSentences = new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                // Out of order on purpose: the evidence lists them by start.
                return List.of(new ResponseScore(
                        0.99,
                        0.2,
                        List.of(
                                new Span(b, answer.length(), 0.975, 0.1),
                                new Span(0, a - 1, 0.02, 0.01),
                                new Span(a, b - 1, 0.99, 0.2))));
            }
        };
        GroundednessDetector d = new GroundednessDetector(
                threeSentences, rag, docs("Refunds are issued within 5-7 business days."), assessments, mapper);
        Detection got = d.detect(obs("cs-rag", "how long do refunds take?", answer), null);
        assertTrue(got.fired());
        JsonNode flagged = mapper.readTree(got.evidenceJson()).get("flagged_sentences");
        assertEquals(2, flagged.size(), "the supported first sentence is not listed: " + flagged);
        assertEquals(a, flagged.get(0).get("start").asInt());
        assertEquals(b - 1, flagged.get(0).get("end").asInt());
        assertEquals(0.99, flagged.get(0).get("unsupported").asDouble(), 1e-9);
        assertEquals(b, flagged.get(1).get("start").asInt());
        assertEquals(answer.length(), flagged.get(1).get("end").asInt());
        assertEquals(0.975, flagged.get(1).get("unsupported").asDouble(), 1e-9, "at the threshold is listed");
        assertEquals(
                "Extended Warranty KB-77 covers you.",
                answer.substring(
                        flagged.get(0).get("start").asInt(),
                        flagged.get(0).get("end").asInt()));
    }

    @Test
    @DisplayName("the model's code-point offsets are stored as the UTF-16 offsets Java and the browser index by")
    void flaggedSentenceOffsetsAreUtf16() throws Exception {
        CallSiteShapeReads extract = (projectId, ids) -> Map.of("cs-1", "extract");
        String first = "Great news \uD83C\uDF89 your refund was approved. ";
        String second = "It arrives in 2 days.";
        String answer = first + second;
        int firstCodePoints = first.codePointCount(0, first.length());
        int totalCodePoints = answer.codePointCount(0, answer.length());
        EncoderScorer emoji = new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                return List.of(
                        new ResponseScore(0.99, 0.1, List.of(new Span(firstCodePoints, totalCodePoints, 0.99, 0.1))));
            }
        };
        GroundingEvidenceReads none = (projectId, ids) -> Map.of();
        Detection got = new GroundednessDetector(emoji, extract, none, assessments, mapper)
                .detect(obs("cs-1", "refund status for order 42", answer), null);
        JsonNode sentence =
                mapper.readTree(got.evidenceJson()).get("flagged_sentences").get(0);
        assertEquals(
                second,
                answer.substring(
                        sentence.get("start").asInt(), sentence.get("end").asInt()),
                "the emoji is two UTF-16 units and one code point");
        assertEquals(second, mapper.readTree(got.evidenceJson()).get("claim").asText());
    }

    @Test
    @DisplayName("a turn that asserts nothing checkable never reaches the encoder")
    void unverifiableAnswerAbstains() {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundednessDetector d = new GroundednessDetector(
                never("a pleasantry has no claim to score"),
                rag,
                docs("Refunds take 5-7 business days."),
                assessments,
                mapper);
        assertFalse(d.detect(obs("cs-rag", "thanks!", "You're welcome — anything else I can help with?"), null)
                .fired());
    }

    @Test
    @DisplayName("a tool-backed turn with nothing readable abstains rather than scoring")
    void toolBackedTurnAbstains() {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundingEvidenceReads toolOnly =
                (projectId, ids) -> Map.of("obs-1", new GroundingEvidenceReads.Evidence(List.of(), true));
        GroundednessDetector d = new GroundednessDetector(
                never("a tool-backed turn with no readable evidence must not reach the head"),
                rag,
                toolOnly,
                assessments,
                mapper);
        assertFalse(d.detect(
                        obs("cs-rag", "where is my order?", "Your order shipped on 3 March and arrives Tuesday."), null)
                .fired());
    }

    @Test
    @DisplayName("a document-in-prompt shape keeps its prompt premise even when the trace called a tool")
    void blindAbstainDoesNotReachDocumentInPromptShapes() {
        CallSiteShapeReads extract = (projectId, ids) -> Map.of("cs-x", "extract");
        GroundingEvidenceReads blind =
                (projectId, ids) -> Map.of("obs-1", new GroundingEvidenceReads.Evidence(List.of(), true));
        List<Response> seen = new ArrayList<>();
        GroundednessDetector d =
                new GroundednessDetector(head(List.of(0.99), seen), extract, blind, assessments, mapper);
        Detection got = d.detect(
                obs("cs-x", "5 years of Python and AWS experience.", "The candidate knows Java and GCP."), null);
        assertEquals(1, seen.size(), "the prompt is still the premise for a document-in-prompt shape");
        assertTrue(got.fired());
    }

    @Test
    @DisplayName("the whole answer is sent once; the worst sentence by the head's offsets is named")
    void wholeAnswerIsScoredOnceAndTheWorstSentenceIsNamed() throws Exception {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        String answer = "Refunds are issued within 5-7 business days. "
                + "You are also covered by Extended Warranty KB-77 for 24 months.";
        List<Response> seen = new ArrayList<>();
        EncoderScorer perSentence = new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                seen.addAll(responses);
                int cut = answer.indexOf("You are");
                return List.of(new ResponseScore(
                        0.98,
                        0.12,
                        List.of(
                                new Span(0, cut - 1, 0.03, 0.01), // first sentence supported
                                new Span(cut, answer.length(), 0.98, 0.12)))); // second invented
            }
        };
        GroundednessDetector d = new GroundednessDetector(
                perSentence, rag, docs("Refunds are issued within 5-7 business days."), assessments, mapper);
        Detection got = d.detect(obs("cs-rag", "how long do refunds take?", answer), null);
        assertEquals(1, seen.size(), "one response per answer, not one request per sentence");
        assertEquals(answer, seen.get(0).answer(), "the head sees the whole answer, sentences included");
        assertTrue(got.fired(), "the answer is only as grounded as its worst sentence");
        assertEquals(Detection.Confidence.HIGH, got.confidence());
        int cut = answer.indexOf("You are");
        // The firing names the sentence that failed and not the one that passed.
        assertEquals(
                mapper.readTree("{\"head\":\"groundedness\",\"unsupported\":0.98,\"conflict\":0.12,"
                        + "\"premise_had_evidence\":true,"
                        + "\"flagged_sentences\":[{\"start\":" + cut + ",\"end\":" + answer.length()
                        + ",\"unsupported\":0.98}],"
                        + "\"claim\":\"You are also covered by Extended Warranty KB-77 for 24 months.\"}"),
                mapper.readTree(assertNotNull2(got.evidenceJson())));
    }

    @Test
    @DisplayName("every sentence supported stays quiet")
    void allSentencesSupportedIsQuiet() {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundednessDetector d = new GroundednessDetector(
                head(List.of(0.04), new ArrayList<>()),
                rag,
                docs("Refunds are issued within 5-7 business days.", "Store credit is instant."),
                assessments,
                mapper);
        assertFalse(d.detect(
                        obs(
                                "cs-rag",
                                "how long do refunds take?",
                                "Refunds are issued within 5-7 business days. Store credit is instant."),
                        null)
                .fired());
    }

    /** A head that answers every request with {@code score}. */
    private static EncoderScorer answering(ResponseScore score) {
        return new EncoderScorer() {
            @Override
            public List<Double> score(String head, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
                return List.of(score);
            }
        };
    }

    @Test
    @DisplayName("the claim echoed into a detection is the worst sentence's first 300 characters")
    void aLongWorstSentenceIsEchoedAsItsFirst300Characters() throws Exception {
        String first = "Refunds are issued within 5-7 business days. ";
        String worst = "Extended Warranty KB-77 covers " + "every part and every repair ".repeat(15) + "for 24 months.";
        String answer = first + worst;
        EncoderScorer head = answering(new ResponseScore(
                0.99,
                0.2,
                List.of(
                        new Span(0, first.length() - 1, 0.02, 0.01),
                        new Span(first.length(), answer.length(), 0.99, 0.2))));
        CallSiteShapeReads extract = (projectId, ids) -> Map.of("cs-1", "extract");
        GroundingEvidenceReads none = (projectId, ids) -> Map.of();

        Detection got = new GroundednessDetector(head, extract, none, assessments, mapper)
                .detect(obs("cs-1", "how long do refunds take?", answer), null);

        assertTrue(worst.length() > 300, "the sentence must be longer than the cap");
        assertEquals(
                worst.substring(0, 300),
                mapper.readTree(assertNotNull2(got.evidenceJson())).get("claim").asText());
    }

    @Test
    @DisplayName("a flagged answer the head returned no sentences for echoes the whole answer as its claim")
    void aFlaggedAnswerWithNoSentencesEchoesTheWholeAnswer() throws Exception {
        String answer = "Extended Warranty KB-77 covers you for 24 months.";
        CallSiteShapeReads extract = (projectId, ids) -> Map.of("cs-1", "extract");
        GroundingEvidenceReads none = (projectId, ids) -> Map.of();

        Detection got = new GroundednessDetector(
                        answering(new ResponseScore(0.99, 0.2, List.of())), extract, none, assessments, mapper)
                .detect(obs("cs-1", "what does the warranty cover?", answer), null);

        JsonNode evidence = mapper.readTree(assertNotNull2(got.evidenceJson()));
        assertEquals(answer, evidence.get("claim").asText());
        assertEquals(mapper.readTree("[]"), evidence.get("flagged_sentences"));
    }

    private static String assertNotNull2(@Nullable String s) {
        assertNotNull(s);
        return s;
    }

    @Test
    @DisplayName("a rag_answer turn with no retrieved evidence is ABSTAINED, not fired")
    void evidenceBackedShapeWithNoEvidenceIsQuiet() {
        GroundingEvidenceReads none =
                (projectId, ids) -> Map.of("obs-1", new GroundingEvidenceReads.Evidence(List.of(), true));
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundednessDetector d = new GroundednessDetector(
                never("must not reach the encoder: there is no premise to score against"),
                rag,
                none,
                assessments,
                new ObjectMapper());
        assertFalse(d.detect(obs("cs-rag", "what is the excess?", "The excess is Rs 5,000."), null)
                .fired());
    }

    @Test
    @DisplayName("retrieved documents are the passages, one each, with the user's question; the firing says so")
    void evidenceIsSentAsPassagesWithTheQuestion() {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        List<Response> seen = new ArrayList<>();
        GroundednessDetector d = new GroundednessDetector(
                head(List.of(0.99), seen),
                rag,
                docs("The policy excess is Rs 5,000 per claim.", "Claims are settled within 30 days."),
                assessments,
                new ObjectMapper());
        Detection got = d.detect(obs("cs-rag", "what is the excess?", "The excess is Rs 50,000."), null);
        assertTrue(got.fired());
        assertEquals(1, seen.size());
        Response sent = seen.get(0);
        assertEquals(
                List.of("The policy excess is Rs 5,000 per claim.", "Claims are settled within 30 days."),
                sent.passages(),
                "each retrieved document is its own passage — never joined into one string");
        assertEquals("what is the excess?", sent.question(), "the user's question goes with the passages");
        assertTrue(
                String.valueOf(got.evidenceJson()).contains("\"premise_had_evidence\":true"),
                "a reader has to be able to tell what the answer was actually compared against");
    }
}
