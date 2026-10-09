// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.config.OtlpReceiverProperties;
import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The HTTP front door's buffer-pressure gate: a push that arrives while the write buffer is past its refuse
 * fraction is answered {@code 503 + Retry-After} before its body is decoded, so a full buffer tells exporters
 * to come back instead of decoding batches it will only shed.
 */
@ExtendWith(MockitoExtension.class)
class OtlpTraceControllerTest {

    @Mock
    SubstrateWriter substrateWriter;

    /** A project-scoped write key, the only caller the controller admits as far as the gate. */
    private static final TenantContext WRITE_TOKEN =
            new TenantContext("user-1", null, "org-1", "proj-http", "member", "tok-1");

    private OtlpIngestService ingest() {
        SubstrateProperties substrate = new SubstrateProperties();
        substrate.setRefuseAboveQueueFraction(0.8);
        return new OtlpIngestService(
                new OtlpReceiverProperties(),
                substrate,
                substrateWriter,
                new OtlpSpanMapper(new ObjectMapper()),
                projectId -> {});
    }

    @Test
    void aPushWhileTheBufferIsUnderPressureIsRefusedRetryableBeforeItsBodyIsDecoded() {
        when(substrateWriter.queueBytesUsedFraction()).thenReturn(0.9);
        OtlpIngestService ingest = ingest();
        OtlpTraceController controller = new OtlpTraceController(new OtlpReceiverProperties(), ingest);
        // Not a protobuf message: decoding it would throw OTLP_MALFORMED_BODY, so a 503 proves the gate ran first.
        byte[] undecodable = "not a protobuf body".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> response = controller.export(WRITE_TOKEN, undecodable);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                List.of("1"), response.getHeaders().get(HttpHeaders.RETRY_AFTER), "exporters are told when to retry");
        assertEquals(1, ingest.refusedBatches());
        verify(substrateWriter, never()).enqueue(anyString(), any());
    }

    @Test
    void aPushWhileTheBufferHasRoomIsAccepted() {
        when(substrateWriter.queueBytesUsedFraction()).thenReturn(0.5);
        when(substrateWriter.enqueue(eq("proj-http"), any())).thenReturn(true);
        OtlpTraceController controller = new OtlpTraceController(new OtlpReceiverProperties(), ingest());

        ResponseEntity<byte[]> response = controller.export(
                WRITE_TOKEN, ExportTraceServiceRequest.getDefaultInstance().toByteArray());

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void aGrpcOnlyDeploymentServesNoHttpRoute() {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setTransport(OtlpReceiverProperties.Transport.GRPC);
        OtlpTraceController controller = new OtlpTraceController(props, ingest());

        TessaryException e = assertThrows(TessaryException.class, () -> controller.export(WRITE_TOKEN, new byte[0]));
        assertEquals(IngestError.OTLP_DISABLED, e.error());
    }

    static Stream<TenantContext> callersWithoutAProjectScopedToken() {
        return Stream.of(
                // A browser session: a project in hand, but no ingest token.
                new TenantContext("user-1", null, "org-1", "proj-http", "member", null),
                // A token that resolved to no project.
                new TenantContext("user-1", null, "org-1", null, "member", "tok-1"));
    }

    /** Only a project-scoped ingest token may push; anything else never reaches the decoder or the buffer. */
    @ParameterizedTest
    @MethodSource("callersWithoutAProjectScopedToken")
    void aPushWithoutAProjectScopedTokenIsRefused(TenantContext caller) {
        OtlpTraceController controller = new OtlpTraceController(new OtlpReceiverProperties(), ingest());

        TessaryException e = assertThrows(TessaryException.class, () -> controller.export(caller, new byte[0]));
        assertEquals(IngestError.OTLP_TOKEN_REQUIRED, e.error());
        verify(substrateWriter, never()).enqueue(anyString(), any());
    }

    @Test
    void aBodyOverTheByteCapIsRefusedBeforeItIsDecoded() {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setMaxBodyBytes(4);
        OtlpTraceController controller = new OtlpTraceController(props, ingest());

        TessaryException e = assertThrows(TessaryException.class, () -> controller.export(WRITE_TOKEN, new byte[5]));
        assertEquals(IngestError.OTLP_BODY_TOO_LARGE, e.error());
    }

    /** A batch the buffer sheds at enqueue is answered retryable, never with an OK for spans not kept. */
    @Test
    void aBatchShedAtEnqueueIsRefusedRetryable() {
        when(substrateWriter.enqueue(eq("proj-http"), any())).thenReturn(false);
        OtlpTraceController controller = new OtlpTraceController(new OtlpReceiverProperties(), ingest());

        ResponseEntity<byte[]> response = controller.export(
                WRITE_TOKEN, ExportTraceServiceRequest.getDefaultInstance().toByteArray());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(List.of("1"), response.getHeaders().get(HttpHeaders.RETRY_AFTER));
    }
}
