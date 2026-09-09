// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.tessary.config.ObserverProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live integration test for the encoder scoring seam: the REAL {@link LauncherEncoderScorer}
 * (auth header, chunking, response-shape guards) against a REAL classify-service instance.
 * Opt-in — skipped unless {@code ENCODER_LIVE_URL} is set — so it runs on demand against a
 * local container or, post-rollout, the Fargate endpoint (from a host inside the VPC):
 *
 * <pre>
 *   docker run -d --name classify-live -p 18081:8080 -e CLASSIFY_API_KEY=localtest tessary-classify:test
 *   ENCODER_LIVE_URL=http://localhost:18081 ENCODER_LIVE_API_KEY=localtest \
 *     mvn test -pl app -Dtest=LauncherEncoderScorerLiveIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "ENCODER_LIVE_URL", matches = ".+")
class LauncherEncoderScorerLiveIT {

    private LauncherEncoderScorer scorer(String apiKey) {
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl(System.getenv("ENCODER_LIVE_URL"));
        props.getEncoder().setApiKey(apiKey);
        return new LauncherEncoderScorer(props, new ObjectMapper());
    }

    private LauncherEncoderScorer scorer() {
        return scorer(System.getenv("ENCODER_LIVE_API_KEY"));
    }

    @Test
    void everyBuiltInHeadDiscriminates() {
        record Case(String head, String positive, String negative) {}
        List<Case> cases = List.of(new Case(
                "frustration",
                "This is infuriating, nothing works and I am so annoyed!",
                "Thanks, that worked perfectly!"));
        for (Case c : cases) {
            List<Double> scores = scorer().score(c.head(), List.of(c.positive(), c.negative()));
            assertThat(scores).as(c.head()).hasSize(2);
            assertThat(scores.get(0)).as(c.head() + " positive").isGreaterThan(0.5);
            assertThat(scores.get(1)).as(c.head() + " negative").isLessThan(0.5);
        }
    }

    @Test
    void groundednessPairHeadDiscriminates() {
        List<EncoderScorer.Pair> pairs = List.of(
                new EncoderScorer.Pair(
                        "The candidate has 5 years of professional Python experience.", "The candidate knows Python."),
                new EncoderScorer.Pair(
                        "The candidate has 5 years of professional Python experience.",
                        "The candidate is an expert in Rust and GCP."));
        List<Double> scores = scorer().scorePairs("groundedness", pairs);
        assertThat(scores).hasSize(2);
        assertThat(scores.get(0)).as("supported claim").isGreaterThan(0.5);
        assertThat(scores.get(1)).as("unsupported claim").isLessThan(0.5);
    }

    @Test
    void chunksBatchesLargerThanOneRequest() {
        // 230 texts forces 3 sequential /classify requests (MAX_TEXTS_PER_REQUEST = 100);
        // the scorer must reassemble them index-aligned.
        List<String> texts = Collections.nCopies(230, "This is infuriating, nothing works!");
        List<Double> scores = scorer().score("frustration", texts);
        assertThat(scores).hasSize(230);
        assertThat(scores).allSatisfy(s -> assertThat(s).isBetween(0.5, 1.0));
    }

    @Test
    void wrongApiKeyFailsLoudly() {
        assertThatThrownBy(() -> scorer("wrong-key").score("frustration", List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void unknownHeadFailsLoudly() {
        assertThatThrownBy(() -> scorer().score("not-a-head", List.of("x"))).isInstanceOf(IllegalStateException.class);
    }
}
