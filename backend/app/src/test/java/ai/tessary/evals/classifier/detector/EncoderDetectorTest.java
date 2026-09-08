// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.ClassifierField;
import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import ai.tessary.evals.classifier.substrate.SubstrateReadRepository;
import ai.tessary.evals.config.ClassifierProperties;
import ai.tessary.evals.testsupport.ClassifierObservations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit coverage for the encoder tier's score banding and batch mechanics: HIGH at/above the
 * high threshold, LOW between the thresholds, quiet below; blank texts never reach the scorer;
 * results stay index-aligned around skipped entries; config_json overrides the operating point.
 * Observations carry the real {@code gen_ai} envelope shape, and the scorer is asserted to receive
 * the unwrapped thread text — the regression for the envelope-scored false positives found in E2E.
 * Each observation is its own single-turn session (mocked substrate returns no prior), so the
 * assembled thread degrades to the bare scored user message.
 */
class EncoderDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<List<String>> scoredBatches = new ArrayList<>();

    private EncoderDetector detector(double... scores) {
        EncoderScorer scorer = (head, texts) -> {
            scoredBatches.add(texts);
            List<Double> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) out.add(scores[i]);
            return out;
        };
        SubstrateReadRepository substrate = Mockito.mock(SubstrateReadRepository.class);
        Mockito.when(substrate.conversationObservationsUpTo(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt()))
                .thenReturn(List.of());
        ConversationThreadAssembler assembler = new ConversationThreadAssembler(substrate, new ClassifierProperties());
        return new EncoderDetector(
                BuiltInDetector.Kind.FRUSTRATION,
                "frustration",
                ClassifierField.INPUT,
                Detection.Severity.WARN,
                scorer,
                mapper,
                assembler);
    }

    /** As {@link #detector}, but the substrate returns two prior exchanges so context is non-empty. */
    private EncoderDetector detectorWithPrior(double... scores) {
        EncoderScorer scorer = (head, texts) -> {
            scoredBatches.add(texts);
            List<Double> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) out.add(scores[i]);
            return out;
        };
        SubstrateReadRepository substrate = Mockito.mock(SubstrateReadRepository.class);
        // Newest-first, as the repository returns it; the scored observation leads.
        Mockito.when(substrate.conversationObservationsUpTo(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt()))
                .thenReturn(List.of(
                        prior("obs-1", "tu", "2026-01-01T00:00:00Z", null, null),
                        prior(
                                "p2",
                                "t2",
                                "2026-01-01T00:00:00Z",
                                "policy SHJ-1, name A B",
                                "Verified. It lapsed 2 July."),
                        prior(
                                "p1",
                                "t1",
                                "2026-01-01T00:00:00Z",
                                "is my policy active?",
                                "Let me check that for you.")));
        return new EncoderDetector(
                BuiltInDetector.Kind.FRUSTRATION,
                "frustration",
                ClassifierField.INPUT,
                Detection.Severity.WARN,
                scorer,
                mapper,
                new ConversationThreadAssembler(substrate, new ClassifierProperties()));
    }

    private static SubstrateObservation prior(
            String id, String turnId, String ts, @Nullable String user, @Nullable String assistant) {
        return new SubstrateObservation(
                id,
                "p",
                // The TRACE is the turn now, so the fixture varies the trace id where it used to vary a
                // turn-context id: two spans of one trace are two contributions to one turn.
                turnId,
                "s",
                null,
                null,
                "llm",
                "chat",
                user == null ? null : ClassifierObservations.userInput(user),
                assistant == null ? null : "[{\"role\":\"assistant\",\"content\":\"" + assistant + "\"}]",
                null,
                ts);
    }

    /** An observation whose input is stored as the real gen_ai user envelope (as ingest writes it). */
    private static SubstrateObservation obs(@Nullable String input) {
        String stored = input == null ? null : ClassifierObservations.userInput(input);
        return new SubstrateObservation(
                "obs-1", "p", "t", "s", null, null, "llm", "chat", stored, null, null, "2026-01-01T00:00:00Z");
    }

    @Test
    void scoresBandIntoHighLowQuiet() {
        List<Detection> ds =
                detector(0.95, 0.75, 0.2).detectBatch(List.of(obs("furious"), obs("annoyed"), obs("fine")), null);
        assertTrue(ds.get(0).fired());
        assertEquals(Detection.Confidence.HIGH, ds.get(0).confidence(), "≥ high threshold fires HIGH");
        assertTrue(ds.get(1).fired());
        assertEquals(Detection.Confidence.LOW, ds.get(1).confidence(), "between thresholds fires LOW");
        assertFalse(ds.get(2).fired(), "below the low threshold stays quiet");
        assertEquals(Detection.Severity.WARN, ds.get(0).severity());
        assertTrue(ds.get(0).evidenceJson() != null && ds.get(0).evidenceJson().contains("\"score\""));
    }

    @Test
    void blankTextsAreSkippedWithoutScoringAndAlignmentHolds() {
        List<Detection> ds = detector(0.95).detectBatch(List.of(obs(null), obs("furious"), obs("   ")), null);
        assertEquals(1, scoredBatches.size(), "one serving call for the batch");
        assertEquals(List.of("furious"), scoredBatches.get(0), "only the non-blank text was scored");
        assertFalse(ds.get(0).fired());
        assertTrue(ds.get(1).fired(), "the score lands on the right observation");
        assertFalse(ds.get(2).fired());
    }

    @Test
    void allBlankBatchNeverCallsTheScorer() {
        List<Detection> ds = detector().detectBatch(List.of(obs(null), obs("")), null);
        assertTrue(scoredBatches.isEmpty(), "no serving call for an all-blank batch");
        assertFalse(ds.get(0).fired());
        assertFalse(ds.get(1).fired());
    }

    @Test
    void configOverridesThresholds() {
        String config = "{\"threshold_high\":0.6,\"threshold_low\":0.4}";
        List<Detection> ds = detector(0.65, 0.45, 0.3).detectBatch(List.of(obs("a"), obs("b"), obs("c")), config);
        assertEquals(Detection.Confidence.HIGH, ds.get(0).confidence());
        assertEquals(Detection.Confidence.LOW, ds.get(1).confidence());
        assertFalse(ds.get(2).fired());
    }

    @Test
    void configContextKeysReachTheAssemblerAndNarrowTheScoredText() {
        // The wiring regression: thresholds already flowed from config_json, the context shape did not.
        // Without the keys the head must still see the WHOLE thread — so this also pins that a signal
        // configured only with thresholds is not silently narrowed.
        String scored = "ok fine, noted the ticket number";
        String full = "[user] is my policy active?\n"
                + "[assistant] Let me check that for you.\n"
                + "[user] policy SHJ-1, name A B\n"
                + "[assistant] Verified. It lapsed 2 July.\n"
                + "[user] " + scored;
        String narrowed = "[user] policy SHJ-1, name A B\n[assistant] [reply]\n[user] " + scored;

        detectorWithPrior(0.95).detectBatch(List.of(obs(scored)), "{\"threshold_low\":0.67}");
        assertEquals(List.of(full), scoredBatches.get(0), "no context keys -> the whole thread, unchanged");

        scoredBatches.clear();
        detectorWithPrior(0.95)
                .detectBatch(
                        List.of(obs(scored)),
                        "{\"threshold_low\":0.67,\"context_user_turns\":1,\"context_stub_assistant\":true}");
        assertEquals(List.of(narrowed), scoredBatches.get(0), "context keys -> last exchange, assistant stubbed");
    }

    @Test
    void configMinPriorUserTurnsReachesTheAssemblerAndSuppressesTheOpener() {
        // Same bug class #639 shipped: `ignoreUnknown = true` plus a swallowed parse error means a
        // MISBOUND key degrades silently to gate-off — the head keeps scoring, nothing errors, and every
        // integration fixture still passes because their preamble scores below the band either way. Only
        // an assertion on the scorer's actual input can tell a working binding from a dead one.
        String opener = "hi, is my policy active?";
        String cfg = "{\"threshold_low\":0.66,\"context_user_turns\":1,\"context_stub_assistant\":true,"
                + "\"context_min_prior_user_turns\":1}";

        // detector() mocks an EMPTY prior thread — the observation IS the conversation opener.
        detector(0.95).detectBatch(List.of(obs(opener)), cfg);
        assertTrue(scoredBatches.isEmpty(), "an opener is never sent to the head at all");

        // Same config, same head, but now the turn has a prior exchange: it must be scored.
        scoredBatches.clear();
        List<Detection> ds = detectorWithPrior(0.95).detectBatch(List.of(obs("ok fine, noted")), cfg);
        assertEquals(1, scoredBatches.size(), "a turn with a prior exchange is scored");
        assertTrue(ds.get(0).fired());

        // And without the key the opener is scored, so the gate is genuinely the cause above.
        scoredBatches.clear();
        detector(0.95).detectBatch(List.of(obs(opener)), "{\"threshold_low\":0.66}");
        assertEquals(List.of(opener), scoredBatches.get(0), "no gate key -> the opener is scored as before");
    }

    @Test
    void singleDetectDelegatesToTheBatchPath() {
        Detection d = detector(0.95).detect(obs("furious"), null);
        assertTrue(d.fired());
        assertEquals(Detection.Confidence.HIGH, d.confidence());
    }

    @Test
    void scorerReceivesUnwrappedTextNotTheEnvelopeJson() {
        // The regression: obs() stores the gen_ai envelope, and the scorer must be handed the clean
        // user text — never the [{"role":"user","content":…}] structure, which a prompt-injection head
        // reads as an injection and fires on benign traffic (found in E2E).
        detector(0.95).detectBatch(List.of(obs("How much is 100 USD in EUR?")), null);
        assertEquals(1, scoredBatches.size());
        assertEquals(
                List.of("How much is 100 USD in EUR?"),
                scoredBatches.get(0),
                "the head scores the flattened message text, not the role-tagged envelope JSON");
    }

    // --- the attribution gate ------------------------------------------------------------------

    /** Records every (head, texts) call so a test can assert WHICH head saw WHAT. */
    private final List<String> gateHeads = new ArrayList<>();

    private final List<List<String>> gateTexts = new ArrayList<>();

    /**
     * A detector whose scorer answers per head: {@code frustration} returns {@code emotion}, the
     * attribution head returns {@code attribution}. Substrate returns two prior exchanges, so the
     * narrowed and full context actually differ.
     */
    private EncoderDetector gatedDetector(double emotion, double attribution) {
        EncoderScorer scorer = (head, texts) -> {
            gateHeads.add(head);
            gateTexts.add(texts);
            List<Double> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add("frustration".equals(head) ? emotion : attribution);
            }
            return out;
        };
        SubstrateReadRepository substrate = Mockito.mock(SubstrateReadRepository.class);
        Mockito.when(substrate.conversationObservationsUpTo(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt()))
                .thenReturn(List.of(
                        prior("obs-1", "tu", "2026-01-01T00:00:00Z", null, null),
                        prior("p1", "t1", "2026-01-01T00:00:00Z", "where is my order?", "SENTINEL_AGENT_PROSE")));
        return new EncoderDetector(
                BuiltInDetector.Kind.FRUSTRATION,
                "frustration",
                ClassifierField.INPUT,
                Detection.Severity.WARN,
                scorer,
                mapper,
                new ConversationThreadAssembler(substrate, new ClassifierProperties()));
    }

    private static final String GATE_CONFIG = "{\"threshold_high\":0.9,\"threshold_low\":0.66,"
            + "\"context_user_turns\":1,\"context_stub_assistant\":true,"
            + "\"attribution_head\":\"attribution\",\"attribution_threshold\":0.5}";

    @Test
    void theGateDemotesAHighFireTheSecondHeadCannotAttributeToTheAgent() {
        List<Detection> ds = gatedDetector(0.95, 0.10).detectBatch(List.of(obs("this is ridiculous")), GATE_CONFIG);
        assertTrue(ds.get(0).fired(), "the row is still written — the gate DEMOTES, it does not drop");
        assertEquals(
                Detection.Confidence.LOW,
                ds.get(0).confidence(),
                "real affect, but not the agent's doing: HIGH -> LOW so tracking mode drops it and "
                        + "discovery mode still sees it");
    }

    @Test
    void theGateLetsThroughWhatBothHeadsAgreeOn() {
        List<Detection> ds =
                gatedDetector(0.95, 0.90).detectBatch(List.of(obs("you still haven't fixed it")), GATE_CONFIG);
        assertEquals(Detection.Confidence.HIGH, ds.get(0).confidence());
    }

    @Test
    void withoutTheConfigKeysTheGateIsOffAndOnlyOneHeadIsScored() {
        // The regression that matters: a new config_json key that never reaches its target is a
        // silent no-op, and this repo has shipped that bug before. If the keys were ignored, the
        // demotion test above would pass for the wrong reason.
        List<Detection> ds = gatedDetector(0.95, 0.10)
                .detectBatch(List.of(obs("this is ridiculous")), "{\"threshold_high\":0.9,\"threshold_low\":0.66}");
        assertEquals(Detection.Confidence.HIGH, ds.get(0).confidence(), "ungated, HIGH stays HIGH");
        assertEquals(List.of("frustration"), gateHeads, "the attribution head is never called");
    }

    @Test
    void theGateScoresOnlyHighCandidates() {
        // A LOW fire is already outside the tracking queue, so gating it buys nothing and costs an
        // inference. Asserted because the cheap implementation scores the whole batch.
        gatedDetector(0.70, 0.10).detectBatch(List.of(obs("mildly annoyed")), GATE_CONFIG);
        assertEquals(List.of("frustration"), gateHeads, "no attribution call for a LOW-band turn");
    }

    @Test
    void theGateReadsTheFullThreadNotTheNarrowedOneTheEmotionHeadGets() {
        // THE parity assertion. The emotion head is configured to stub assistant turns to "[reply]";
        // the gate must NOT inherit that, because it is judging what the agent did and a stubbed
        // thread contains none of that evidence. Feeding it the emotion head's string would read as
        // a calibration problem rather than the wiring bug it is.
        gatedDetector(0.95, 0.90).detectBatch(List.of(obs("this is ridiculous")), GATE_CONFIG);
        assertEquals(2, gateTexts.size(), "both heads were called");
        String emotionInput = gateTexts.get(0).get(0);
        String attributionInput = gateTexts.get(1).get(0);
        assertFalse(
                emotionInput.contains("SENTINEL_AGENT_PROSE"), "the emotion head sees the assistant turn stubbed away");
        assertTrue(
                attributionInput.contains("SENTINEL_AGENT_PROSE"),
                "the attribution head sees the agent's actual words");
    }

    @Test
    void evidenceCarriesBothScoresSoTheQueueCanBeOrderedWithoutAMigration() {
        List<Detection> ds = gatedDetector(0.95, 0.90).detectBatch(List.of(obs("this is ridiculous")), GATE_CONFIG);
        String evidence = ds.get(0).evidenceJson();
        assertTrue(evidence != null && evidence.contains("\"score\""), "the emotion score is kept");
        assertTrue(evidence != null && evidence.contains("\"attribution_score\""), "and the gate's score");
    }
}
