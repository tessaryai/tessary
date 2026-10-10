// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.ModelsDevProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Against a mocked {@link HttpClient} fed canned answers. Pins the order rates come from (live, then the
 * bundled copy), that a models.dev outage costs one fetch per retry window rather than one per run, and
 * that a model with no token rate reads as unpriced rather than free.
 */
class ModelsDevRatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://models.dev/api.json";

    private static final ModelsDevRates.ModelCost BUNDLED_GROK =
            new ModelsDevRates.ModelCost(new BigDecimal("2"), new BigDecimal("6"), new BigDecimal("0.5"), null);
    private static final Map<String, ModelsDevRates.ModelCost> BUNDLED = Map.of("xai/grok-4.6", BUNDLED_GROK);

    private final HttpClient http = mock(HttpClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T12:00:00Z"));

    private ModelsDevRates rates() {
        ModelsDevProperties props = new ModelsDevProperties();
        props.setUrl(URL);
        return new ModelsDevRates(http, MAPPER, props, clock, BUNDLED);
    }

    @SuppressWarnings("unchecked")
    private void answer(int status, String body) throws Exception {
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp);
    }

    @SuppressWarnings("unchecked")
    private void fetches(int count) throws Exception {
        verify(http, times(count)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void theLiveFileWinsOverTheBundledCopy() throws Exception {
        answer(200, "{\"xai\":{\"models\":{\"grok-4.6\":{\"cost\":{\"input\":3,\"output\":9,\"cache_read\":0.75}}}}}");

        assertEquals(
                Optional.of(new ModelsDevRates.ModelCost(
                        new BigDecimal("3"), new BigDecimal("9"), new BigDecimal("0.75"), null)),
                rates().cost("xai/grok-4.6"));
    }

    @Test
    void aModelTheLiveFileLacksIsAnsweredFromTheBundledCopy() throws Exception {
        answer(200, "{\"zai\":{\"models\":{\"glm-5.3\":{\"cost\":{\"input\":1.4,\"output\":4.4}}}}}");

        assertEquals(Optional.of(BUNDLED_GROK), rates().cost("xai/grok-4.6"));
    }

    @Test
    void aModelsDevOutageFallsBackToTheBundledCopyAndIsNotRetriedOnEveryRun() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connect timed out"));
        ModelsDevRates rates = rates();

        assertEquals(Optional.of(BUNDLED_GROK), rates.cost("xai/grok-4.6"));
        assertEquals(Optional.of(BUNDLED_GROK), rates.cost("xai/grok-4.6"));
        fetches(1);

        clock.advance(ModelsDevRates.RETRY_AFTER);
        rates.cost("xai/grok-4.6");
        fetches(2);
    }

    @Test
    void anErrorStatusIsAnOutageToo() throws Exception {
        answer(503, "upstream unavailable");

        assertEquals(Optional.of(BUNDLED_GROK), rates().cost("xai/grok-4.6"));
    }

    @Test
    void aFetchedFileIsServedUntilTheRefreshIntervalThenFetchedAgain() throws Exception {
        answer(200, "{\"xai\":{\"models\":{\"grok-4.6\":{\"cost\":{\"input\":3,\"output\":9}}}}}");
        ModelsDevRates rates = rates();

        rates.cost("xai/grok-4.6");
        clock.advance(Duration.ofMinutes(59));
        rates.cost("xai/grok-4.6");
        fetches(1);

        clock.advance(Duration.ofMinutes(1));
        rates.cost("xai/grok-4.6");
        fetches(2);
    }

    @Test
    void aBlankUrlNeverFetches() throws Exception {
        ModelsDevProperties props = new ModelsDevProperties();
        props.setUrl("");
        ModelsDevRates rates = new ModelsDevRates(http, MAPPER, props, clock, BUNDLED);

        assertEquals(Optional.of(BUNDLED_GROK), rates.cost("xai/grok-4.6"));
        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /** A model priced per image or per request has no token rate; OpenCode would bill it at $0, so it is unpriced. */
    @Test
    void aModelWithNeitherAnInputNorAnOutputRateIsUnpriced() throws Exception {
        Map<String, ModelsDevRates.ModelCost> parsed = ModelsDevRates.parse(
                MAPPER,
                ("{\"openai\":{\"models\":{\"dall-e-3\":{\"cost\":{\"cache_read\":0.1}},\"gpt-6-sol\":{\"cost\":"
                                + "{\"input\":2,\"output\":10,\"cache_read\":0.2,\"cache_write\":2.5}},"
                                + "\"whisper\":{}}}}")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(
                Map.of(
                        "openai/gpt-6-sol",
                        new ModelsDevRates.ModelCost(
                                new BigDecimal("2"),
                                new BigDecimal("10"),
                                new BigDecimal("0.2"),
                                new BigDecimal("2.5"))),
                parsed);
    }

    /** The launcher declares exactly these keys to OpenCode, which reads them as USD per million tokens. */
    @Test
    void aCostIsWrittenInOpencodesOwnShapeWithAbsentBucketsLeftOut() throws Exception {
        assertEquals(
                MAPPER.readTree("{\"input\":2,\"output\":6,\"cache_read\":0.5}"),
                MAPPER.readTree(MAPPER.writeValueAsString(BUNDLED_GROK.toOpencodeCost(MAPPER))));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
