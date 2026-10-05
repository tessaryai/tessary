// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.Backlog;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.ShadowRow;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.Summary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Against a mocked {@link HttpClient}, as the decision client's tests are. */
class FrustrationShadowSweepTest {

    private static final String REQUEST =
            "{\"model\":\"~typesafe/jev-latest\",\"state\":{\"current_user_message\":\"again??\",\"earlier_messages\":[]},"
                    + "\"questions\":{\"user_stance\":{\"type\":\"choice\",\"instructions\":\"x\",\"criteria\":{}}}}";
    private static final String ANSWER_HIGH =
            "{\"answers\":{\"user_stance\":{\"type\":\"choice\",\"choice\":\"unhappy_with_assistant\","
                    + "\"probabilities\":{\"unhappy_with_assistant\":0.91,\"unhappy_other_cause\":0.04,\"neutral_or_positive\":0.05}}}}";
    private static final String ANSWER_LOW =
            "{\"answers\":{\"user_stance\":{\"type\":\"choice\",\"choice\":\"neutral_or_positive\","
                    + "\"probabilities\":{\"unhappy_with_assistant\":0.12,\"unhappy_other_cause\":0.03,\"neutral_or_positive\":0.85}}}}";

    private final HttpClient http = mock(HttpClient.class);
    private final FrustrationShadowRepository shadows = mock(FrustrationShadowRepository.class);
    private final ClassifierRepository classifiers = mock(ClassifierRepository.class);
    private final FrustrationShadowProperties props = new FrustrationShadowProperties();
    private final List<ShadowRow> written = new ArrayList<>();

    private FrustrationShadowSweep sweep() {
        props.setUrl("http://10.2.1.9:8000/");
        props.setBatch(2);
        when(shadows.summary()).thenReturn(new Summary(0, 0, 0, 0, 0));
        when(shadows.insert(any())).thenAnswer(inv -> written.add(inv.getArgument(0)));
        when(classifiers.findById(any(), any())).thenReturn(Optional.empty());
        return new FrustrationShadowSweep(
                shadows, classifiers, props, new ObjectMapper(), http, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static Backlog turn(String id, boolean referenceFlag) {
        return new Backlog(id, "p1", "c1", "t-" + id, "v1", REQUEST, referenceFlag);
    }

    /** A plain response, not a mock: building a mock inside a stubbing would leave it unfinished. */
    private static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder(java.net.URI.create("http://10.2.1.9:8000/"))
                        .build();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
            }

            @Override
            public Optional<javax.net.ssl.SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public java.net.URI uri() {
                return request().uri();
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static boolean isHealth(@org.jspecify.annotations.Nullable HttpRequest r) {
        return r != null && r.uri().getPath().endsWith("/health");
    }

    @Test
    void doesNothingWithoutAUrl() {
        FrustrationShadowSweep s =
                new FrustrationShadowSweep(shadows, classifiers, props, new ObjectMapper(), http, Clock.systemUTC());
        FrustrationShadowSweep.Run run = s.sweep();
        assertEquals(0, run.sent());
        verify(shadows, never()).backlog(anyInt());
    }

    @Test
    void leavesTheBacklogWhileTheServerIsAsleep() throws Exception {
        FrustrationShadowSweep s = sweep();
        when(http.<String>send(any(), any())).thenThrow(new ConnectException("refused"));
        FrustrationShadowSweep.Run run = s.sweep();
        assertFalse(run.serverUp());
        verify(shadows, never()).backlog(anyInt());
    }

    @Test
    void drainsTheBacklogInBatchesAndFlagsAgainstTheThreshold() throws Exception {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2))
                .thenReturn(List.of(turn("a1", true), turn("a2", false)))
                .thenReturn(List.of(turn("a3", true)))
                .thenReturn(List.of());
        when(http.<String>send(argThat(FrustrationShadowSweepTest::isHealth), any()))
                .thenReturn(response(200, "{\"ok\":true}"));
        when(http.<String>send(argThat(r -> !isHealth(r)), any()))
                .thenReturn(response(200, ANSWER_HIGH))
                .thenReturn(response(200, ANSWER_LOW))
                .thenReturn(response(200, ANSWER_LOW));

        FrustrationShadowSweep.Run run = s.sweep();

        assertEquals(3, run.sent());
        assertEquals(3, run.scored());
        assertEquals(0, run.refused());
        assertTrue(run.serverUp());
        assertEquals(3, written.size());
        ShadowRow a1 = written.get(0);
        assertEquals("a1", a1.assessmentId());
        assertEquals(new java.math.BigDecimal("0.91"), a1.score());
        assertEquals(Boolean.TRUE, a1.frustrated()); // 0.91 > the default 0.40
        assertTrue(a1.referenceFrustrated());
        assertEquals("decider", a1.shadowModel());
        ShadowRow a3 = written.get(2);
        assertEquals(Boolean.FALSE, a3.frustrated()); // 0.12 < 0.40: the shadow disagrees with the reference
        assertTrue(a3.referenceFrustrated());
    }

    @Test
    void sendsTheStoredBodyWithOnlyTheModelReplaced() throws Exception {
        FrustrationShadowSweep s = sweep();
        props.setModel("eikos");
        props.setApiKey("k");
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false))).thenReturn(List.of());
        when(http.<String>send(argThat(FrustrationShadowSweepTest::isHealth), any()))
                .thenReturn(response(200, "{}"));
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        when(http.<String>send(argThat(r -> !isHealth(r)), any())).thenReturn(response(200, ANSWER_LOW));

        s.sweep();

        verify(http, org.mockito.Mockito.times(2)).send(sent.capture(), any());
        HttpRequest post = sent.getAllValues().get(1);
        assertEquals("http://10.2.1.9:8000/v1/systemone", post.uri().toString());
        assertEquals(Optional.of("Bearer k"), post.headers().firstValue("Authorization"));
        String body = bodyOf(post);
        assertTrue(body.contains("\"model\":\"eikos\""), body);
        assertTrue(body.contains("\"current_user_message\":\"again??\""), body);
        assertFalse(body.contains("jev-latest"), body);
    }

    @Test
    void aFourHundredIsRecordedAsARefusalSoTheTurnIsNotResent() throws Exception {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false))).thenReturn(List.of());
        when(http.<String>send(argThat(FrustrationShadowSweepTest::isHealth), any()))
                .thenReturn(response(200, "{}"));
        when(http.<String>send(argThat(r -> !isHealth(r)), any()))
                .thenReturn(response(400, "{\"error\":{\"message\":\"too many options\"}}"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertEquals(1, run.refused());
        assertEquals(0, run.scored());
        assertNull(written.get(0).score());
        assertNull(written.get(0).frustrated());
        assertTrue(written.get(0).responseJson().contains("HTTP 400"));
    }

    @Test
    void aFiveHundredEndsTheRunWithoutARow() throws Exception {
        FrustrationShadowSweep s = sweep();
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", false), turn("a2", false)));
        when(http.<String>send(argThat(FrustrationShadowSweepTest::isHealth), any()))
                .thenReturn(response(200, "{}"));
        when(http.<String>send(argThat(r -> !isHealth(r)), any())).thenReturn(response(503, "going down"));

        FrustrationShadowSweep.Run run = s.sweep();

        assertFalse(run.serverUp());
        assertEquals(0, run.sent());
        assertTrue(written.isEmpty());
    }

    @Test
    void usesTheClassifiersOwnThreshold() throws Exception {
        FrustrationShadowSweep s = sweep();
        ClassifierRow row = new ClassifierRow(
                "c1",
                "p1",
                "frustration",
                "Frustration",
                null,
                "frustration",
                "{\"threshold\":0.95}",
                true,
                1,
                true,
                "tracking",
                "2026-10-01T00:00:00Z",
                "2026-10-01T00:00:00Z");
        when(classifiers.findById("p1", "c1")).thenReturn(Optional.of(row));
        when(shadows.backlog(2)).thenReturn(List.of(turn("a1", true))).thenReturn(List.of());
        when(http.<String>send(argThat(FrustrationShadowSweepTest::isHealth), any()))
                .thenReturn(response(200, "{}"));
        when(http.<String>send(argThat(r -> !isHealth(r)), any())).thenReturn(response(200, ANSWER_HIGH));

        s.sweep();

        assertEquals(Boolean.FALSE, written.get(0).frustrated()); // 0.91 < 0.95
    }

    private static String bodyOf(HttpRequest request) throws IOException {
        var publisher = request.bodyPublisher().orElseThrow();
        var out = new java.io.ByteArrayOutputStream();
        var latch = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] b = new byte[item.remaining()];
                item.get(b);
                out.writeBytes(b);
            }

            @Override
            public void onError(Throwable t) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
