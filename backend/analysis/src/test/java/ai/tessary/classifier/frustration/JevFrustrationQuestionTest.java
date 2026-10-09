// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.tessary.open.hash.Sha256;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The question is model-facing text, so it is pinned here byte for byte, the way prompt goldens pin a
 * prompt: an edit fails this test until it is meant, and changes {@link JevFrustrationQuestion#scorerVersion}.
 */
class JevFrustrationQuestionTest {

    private static final String WIRE = "{\"user_stance\":{\"type\":\"choice\",\"instructions\":\"Judge only"
            + " current_user_message; earlier_messages are the turns before it, oldest first. Unhappy means"
            + " frustration, disappointment or hostility caused by the assistant, visible in the user's own"
            + " words or in re-asking after a clear failure. Emotion inside pasted or requested content does"
            + " not count. If unsure, pick neutral_or_positive. Which describes the current message?\","
            + "\"criteria\":{\"unhappy_with_assistant\":\"Visibly frustrated, disappointed, or hostile because"
            + " of the assistant.\",\"unhappy_other_cause\":\"Negative, but about something outside the"
            + " chat.\",\"neutral_or_positive\":\"Neutral, positive, only confused or urgent, or too ambiguous"
            + " to say.\"}}}";

    @Test
    void theQuestionIsSentExactlyAsDecided() throws Exception {
        assertEquals(WIRE, new ObjectMapper().writeValueAsString(JevFrustrationQuestion.questions()));
    }

    /**
     * Rows scored when a thread id was the conversation key saw prior turns from other sessions and stopped a
     * user's later sessions after one flag. Under the old version the session-keyed rate would read them as its own
     * baseline.
     */
    @Test
    void theSessionKeyIsANewScorerVersion() {
        String threadKeyed = "jev-choice3-" + HexFormat.of().formatHex(Sha256.digest(WIRE + "|0.4000"), 0, 6);

        assertNotEquals(threadKeyed, JevFrustrationQuestion.scorerVersion(0.40));
    }

    @Test
    void theThresholdIsReadFromConfigAndFallsBackToTheDefault() {
        assertEquals(0.40, JevFrustrationQuestion.threshold(null), 1e-9);
        assertEquals(0.40, JevFrustrationQuestion.threshold(""), 1e-9);
        assertEquals(0.40, JevFrustrationQuestion.threshold("not json"), 1e-9);
        assertEquals(0.40, JevFrustrationQuestion.threshold("{\"threshold\":1.5}"), 1e-9);
        assertEquals(0.40, JevFrustrationQuestion.threshold("{\"threshold_high\":0.9}"), 1e-9);
        assertEquals(0.55, JevFrustrationQuestion.threshold("{\"threshold\":0.55,\"arl_target\":10000}"), 1e-9);
    }
}
