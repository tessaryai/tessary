// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.tessary.evals.config.OtlpReceiverProperties;
import ai.tessary.evals.config.SubstrateProperties;
import ai.tessary.evals.ingest.GenAiAttributes;
import ai.tessary.evals.ingest.IngestQuotaGate;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.ingest.substrate.SubstrateWriter;
import ai.tessary.evals.open.errors.CapabilityError;
import ai.tessary.evals.open.errors.EvalsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for the shared, transport-agnostic ingest core: both the HTTP receiver and the gRPC
 * receiver delegate here, so this exercises the one mapping → span-count clamp → {@code enqueue} → OTLP
 * response path. {@link SubstrateWriter} is mocked (the enqueue seam itself is covered by its own
 * integration test); these assert the contract the transports rely on.
 */
class OtlpIngestServiceTest {

    private final SubstrateWriter substrateWriter = Mockito.mock(SubstrateWriter.class);

    private OtlpIngestService service(int maxSpansPerRequest) {
        return service(maxSpansPerRequest, projectId -> {});
    }

    private OtlpIngestService service(int maxSpansPerRequest, IngestQuotaGate quotaGate) {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setMaxSpansPerRequest(maxSpansPerRequest);
        OtlpSpanMapper mapper = new OtlpSpanMapper(new ObjectMapper());
        return new OtlpIngestService(props, new SubstrateProperties(), substrateWriter, mapper, quotaGate);
    }

    private static Span span(byte id) {
        return Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {id}))
                .addAttributes(KeyValue.newBuilder()
                        .setKey(GenAiAttributes.OPERATION_NAME)
                        .setValue(AnyValue.newBuilder()
                                .setStringValue(GenAiAttributes.OP_CHAT)
                                .build())
                        .build())
                .build();
    }

    private static ExportTraceServiceRequest request(Span... spans) {
        ScopeSpans.Builder scope = ScopeSpans.newBuilder();
        for (Span s : spans) scope.addSpans(s);
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                        ResourceSpans.newBuilder().addScopeSpans(scope).build())
                .build();
    }

    @Test
    void mapsAndEnqueuesUnderProjectId_fullAcceptance_noPartialSuccess() {
        OtlpIngestService svc = service(2000);
        Mockito.when(substrateWriter.enqueue(eq("proj-1"), Mockito.anyList())).thenReturn(true);

        OtlpIngestService.IngestOutcome outcome = svc.ingest("proj-1", request(span((byte) 1), span((byte) 2)));

        assertTrue(outcome.accepted(), "a batch the write buffer took is accepted");
        assertFalse(outcome.response().hasPartialSuccess(), "a within-limit batch is fully accepted");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(substrateWriter).enqueue(eq("proj-1"), captor.capture());
        assertEquals(2, captor.getValue().size(), "both spans enqueued under the resolved project");
    }

    @Test
    void clampsOverLimitBatch_enqueuesClampedAndReportsPartialSuccess() {
        OtlpIngestService svc = service(1); // clamp to a single span
        Mockito.when(substrateWriter.enqueue(eq("proj-2"), Mockito.anyList())).thenReturn(true);

        OtlpIngestService.IngestOutcome outcome = svc.ingest("proj-2", request(span((byte) 1), span((byte) 2)));

        assertTrue(outcome.accepted(), "a clamp is not a shed — the surviving span was taken");
        assertTrue(outcome.response().hasPartialSuccess(), "an over-limit batch reports partial success");
        assertEquals(1, outcome.response().getPartialSuccess().getRejectedSpans(), "one span over the limit of 1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(substrateWriter).enqueue(eq("proj-2"), captor.capture());
        assertEquals(1, captor.getValue().size(), "only the clamped span count is enqueued");
    }

    @Test
    void exceededQuota_refusesTheWholeBatch_andNeverReachesTheSubstrate() {
        EvalsException exceeded =
                new EvalsException(CapabilityError.QUOTA_EXCEEDED, "ingested_spans_monthly", 9130L, 0L);
        OtlpIngestService svc = service(2000, projectId -> {
            throw exceeded;
        });

        EvalsException thrown =
                assertThrows(EvalsException.class, () -> svc.ingest("proj-3", request(span((byte) 1), span((byte) 2))));

        assertEquals(CapabilityError.QUOTA_EXCEEDED, thrown.error());
        // The gap this covers: the quota read as exceeded and the spans were written anyway.
        verify(substrateWriter, never()).enqueue(anyString(), anyList());
    }
}
