// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import static ai.tessary.ingest.otlp.OtlpRequests.chatSpan;
import static ai.tessary.ingest.otlp.OtlpRequests.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import ai.tessary.config.OtlpReceiverProperties;
import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.IngestQuotaGate;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void clampsOverLimitBatch_enqueuesClampedAndReportsPartialSuccess() {
        OtlpIngestService svc = service(1); // clamp to a single span
        Mockito.when(substrateWriter.enqueue(eq("proj-2"), Mockito.anyList())).thenReturn(true);

        OtlpIngestService.IngestOutcome outcome = svc.ingest("proj-2", request(chatSpan((byte) 1), chatSpan((byte) 2)));

        assertTrue(outcome.accepted(), "a clamp is not a shed — the surviving span was taken");
        assertTrue(outcome.response().hasPartialSuccess(), "an over-limit batch reports partial success");
        assertEquals(1, outcome.response().getPartialSuccess().getRejectedSpans(), "one span over the limit of 1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(substrateWriter).enqueue(eq("proj-2"), captor.capture());
        assertEquals(1, captor.getValue().size(), "only the clamped span count is enqueued");
    }
}
