// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.EncoderScorer.Response;
import ai.tessary.classifier.detector.EncoderScorer.ResponseScore;
import ai.tessary.classifier.detector.EncoderScorer.Span;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the Groundedness built-in under the v5 "unsupported" contract: fires when the
 * token head finds the answer unsupported by its evidence, only on call sites gated in by shape, and
 * skips (never scores clean) blank fields, call sites with no/ungrounded shape, answers that assert
 * nothing checkable, and rag_answer turns whose conversation reached outside but captured nothing
 * readable. The encoder is faked at {@link EncoderScorer#scoreResponses}: what the detector SENDS
 * (passages as a list, the question, the whole answer) is asserted as carefully as what it does with
 * the score, because the head's input layout is part of the model.
 *
 * <p>{@code ai.tessary.testsupport.ClassifierObservations}'s two envelope builders are inlined below
 * as {@link #userInput} / {@link #assistantOutput}: this module's tests do not depend on {@code app},
 * and two one-line JSON builders are no reason to start.
 */
class GroundednessDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Set<String>> shapeLookups = new ArrayList<>();

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
        return new GroundednessDetector(head(unsupportedScores, new ArrayList<>()), shapes, evidence, mapper);
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

    private static GroundingEvidenceReads docs(String... documents) {
        return (projectId, ids) -> Map.of("obs-1", new GroundingEvidenceReads.Evidence(List.of(documents), true));
    }

    @Test
    void highUnsupportedFiresOnAGroundedShape() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.99));
        Detection detection = d.detect(
                obs("cs-1", "5 years of Python and AWS experience.", "The candidate knows Java and GCP."), null);
        assertTrue(detection.fired());
        assertEquals(Detection.Severity.WARN, detection.severity());
        assertEquals(Detection.Confidence.HIGH, detection.confidence(), "0.99 is past the 0.975 high bar");
        String evidence = assertNotNull2(detection.evidenceJson());
        assertTrue(evidence.contains("groundedness"));
        assertTrue(evidence.contains("\"unsupported\":0.99"), evidence);
        assertTrue(evidence.contains("\"conflict\":0.495"), "the narrower score rides along: " + evidence);
    }

    @Test
    void lowUnsupportedStaysQuiet() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.02));
        Detection detection =
                d.detect(obs("cs-1", "5 years of Python and AWS experience.", "The candidate knows Python."), null);
        assertFalse(detection.fired());
    }

    @Test
    void betweenTheBandsFiresLowConfidence() {
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.7));
        Detection detection =
                d.detect(obs("cs-1", "5 years of Python and AWS experience.", "The candidate knows Java."), null);
        assertTrue(detection.fired(), "0.7 clears the 0.5 low bar");
        assertEquals(Detection.Confidence.LOW, detection.confidence(), "0.7 is under the 0.975 high bar");
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

    @Test
    void batchStaysIndexAlignedAndOnlyGatedRowsAreScored() {
        GroundednessDetector d =
                detector(Map.of("cs-1", "extract", "cs-2", "draft", "cs-3", "rag_answer"), List.of(0.99, 0.05));
        List<Detection> ds = d.detectBatch(
                List.of(
                        obs("cs-1", "the doc says X", "the doc says Y"), // gated, unsupported -> fires
                        obs("cs-2", "write a poem", "roses are red"), // ungated -> skipped
                        obs("cs-3", "the doc says X", "the doc says X")), // gated, supported -> quiet
                null);
        assertTrue(ds.get(0).fired());
        assertFalse(ds.get(1).fired());
        assertFalse(ds.get(2).fired());
    }

    @Test
    @DisplayName("document-in-prompt: the prompt (system AND user text) is the one passage, with no question")
    void promptPremiseIncludesSystemMessageContentAndIsSentAsOnePassage() {
        List<Response> seen = new ArrayList<>();
        GroundingEvidenceReads evidence = (projectId, ids) -> Map.of();
        CallSiteShapeReads shapes = (projectId, ids) -> Map.of("cs-1", "extract");
        GroundednessDetector d = new GroundednessDetector(head(List.of(0.05), seen), shapes, evidence, mapper);
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
        d.detect(o, null);
        assertEquals(1, seen.size());
        Response sent = seen.get(0);
        assertEquals(1, sent.passages().size(), "the prompt is ONE passage: " + sent.passages());
        assertTrue(sent.passages().get(0).contains("refunds take 5-7 days"), "system document content: " + sent);
        assertTrue(sent.passages().get(0).contains("how long do refunds take"), "and the user message: " + sent);
        assertNull(sent.question(), "no separate question: the prompt already carries it");
        assertEquals("Refunds take 5-7 business days.", sent.answer());
    }

    @Test
    void configOverridesThresholds() {
        String answer = "Refunds take 5-7 business days.";
        GroundednessDetector d = detector(Map.of("cs-1", "extract"), List.of(0.4));
        assertFalse(
                d.detect(obs("cs-1", "how long do refunds take?", answer), null).fired(),
                "0.4 unsupported is under the default 0.5 low bar");
        GroundednessDetector d2 = detector(Map.of("cs-1", "extract"), List.of(0.4));
        Detection lowered = d2.detect(
                obs("cs-1", "how long do refunds take?", answer), "{\"threshold_low\":0.3,\"threshold_high\":0.9}");
        assertTrue(lowered.fired(), "0.4 clears a lowered 0.3 low bar");
        assertEquals(Detection.Confidence.LOW, lowered.confidence(), "0.4 is under the 0.9 high bar");
    }

    @Test
    @DisplayName("a turn that asserts nothing checkable never reaches the encoder")
    void unverifiableAnswerAbstains() {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundednessDetector d = new GroundednessDetector(
                never("a pleasantry has no claim to score"), rag, docs("Refunds take 5-7 business days."), mapper);
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
                never("a tool-backed turn with no readable evidence must not reach the head"), rag, toolOnly, mapper);
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
        GroundednessDetector d = new GroundednessDetector(head(List.of(0.99), seen), extract, blind, mapper);
        Detection got = d.detect(
                obs("cs-x", "5 years of Python and AWS experience.", "The candidate knows Java and GCP."), null);
        assertEquals(1, seen.size(), "the prompt is still the premise for a document-in-prompt shape");
        assertTrue(got.fired());
    }

    @Test
    @DisplayName("the whole answer is sent once; the worst sentence by the head's offsets is named")
    void wholeAnswerIsScoredOnceAndTheWorstSentenceIsNamed() {
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
                perSentence, rag, docs("Refunds are issued within 5-7 business days."), mapper);
        Detection got = d.detect(obs("cs-rag", "how long do refunds take?", answer), null);
        assertEquals(1, seen.size(), "one response per answer, not one request per sentence");
        assertEquals(answer, seen.get(0).answer(), "the head sees the whole answer, sentences included");
        assertTrue(got.fired(), "the answer is only as grounded as its worst sentence");
        assertEquals(Detection.Confidence.HIGH, got.confidence());
        String json = String.valueOf(got.evidenceJson());
        assertTrue(json.contains("\"sentences\":2"), json);
        assertTrue(json.contains("KB-77"), "the firing names the sentence that failed: " + json);
        assertFalse(json.contains("5-7 business days"), "and not the one that passed: " + json);
        assertTrue(json.contains("\"unsupported\":0.98"), json);
    }

    @Test
    @DisplayName("every sentence supported stays quiet")
    void allSentencesSupportedIsQuiet() {
        CallSiteShapeReads rag = (projectId, ids) -> Map.of("cs-rag", "rag_answer");
        GroundednessDetector d = new GroundednessDetector(
                head(List.of(0.04), new ArrayList<>()),
                rag,
                docs("Refunds are issued within 5-7 business days.", "Store credit is instant."),
                mapper);
        assertFalse(d.detect(
                        obs(
                                "cs-rag",
                                "how long do refunds take?",
                                "Refunds are issued within 5-7 business days. Store credit is instant."),
                        null)
                .fired());
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
