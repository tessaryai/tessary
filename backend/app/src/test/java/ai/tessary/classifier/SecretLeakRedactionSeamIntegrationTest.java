// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.otlp.OtlpIngestService;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.plan.Capability;
import ai.tessary.redaction.RedactionService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The seam #1044 found untested: an OTLP batch carrying a credential goes in through the ingest
 * service, the project's default redaction rules strip it on the write path, and the {@code
 * secret_leak} classifier still produces a detection from what was persisted. Both halves had their
 * own tests; this is the one that crosses the edge, with redaction ON and nothing seeded directly
 * into the substrate.
 */
@SpringBootTest
class SecretLeakRedactionSeamIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    OtlpIngestService otlp;

    @Autowired
    SubstrateWriter writer;

    @Autowired
    RedactionService redaction;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierWorker worker;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    JdbcClient jdbc;

    private static final String CANARY = "AKIABOOTCHECKCANARY1";

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build();
    }

    private static ExportTraceServiceRequest batchWithLeakedKey() {
        SecureRandom rnd = new SecureRandom();
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        rnd.nextBytes(traceId);
        rnd.nextBytes(spanId);
        long now = Instant.now().toEpochMilli() * 1_000_000L;
        Span span = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(traceId))
                .setSpanId(ByteString.copyFrom(spanId))
                .setName("chat")
                .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(now - 1_000_000_000L)
                .setEndTimeUnixNano(now)
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, "chat"))
                .addAttributes(kv(GenAiAttributes.TESSARY_CALL_SITE_ID, "seam-test"))
                .addAttributes(kv(
                        GenAiAttributes.OUTPUT_MESSAGES,
                        "[{\"role\":\"assistant\",\"content\":\"Sure, your aws_access_key_id=" + CANARY
                                + " is set for us-east-1.\"}]"))
                .build();
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(span))
                        .build())
                .build();
    }

    @Test
    void aRedactedCredentialStillProducesASecretLeakDetection() throws InterruptedException {
        String pid = TenantFixture.bootstrap(
                        tenants, "secret-leak-seam", org -> capabilities.grant(org.id(), Capability.SECRET_LEAK))
                .project()
                .id();
        assertFalse(redaction.listRules(pid).isEmpty(), "the built-in redaction rules are seeded and ON");
        classifiers.seedBuiltIns(pid);

        otlp.ingest(pid, batchWithLeakedKey());
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "the ingest write path drained");

        String persisted = jdbc.sql("SELECT output FROM span_payload WHERE project_id = :pid LIMIT 1")
                .param("pid", pid)
                .query(String.class)
                .single();
        assertFalse(persisted.contains(CANARY), "the raw credential never reaches the substrate");
        assertTrue(persisted.contains("[REDACTED_API_KEY]"), "redaction left its marker in the persisted output");

        worker.tick();
        long detections = 0;
        for (int i = 0; i < 100 && detections == 0; i++) {
            detections = jdbc.sql("SELECT COUNT(*) FROM secret_leak_detection WHERE project_id = :pid")
                    .param("pid", pid)
                    .query(Long.class)
                    .single();
            if (detections == 0) sleep(100);
        }
        assertEquals(1, detections, "one secret_leak detection for the redacted credential");
        Map<String, Object> row = jdbc.sql(
                        "SELECT severity, confidence, evidence FROM secret_leak_detection WHERE project_id = :pid")
                .param("pid", pid)
                .query()
                .singleRow();
        assertEquals("critical", row.get("severity"));
        assertEquals("high", row.get("confidence"));
        String evidence = String.valueOf(row.get("evidence"));
        assertTrue(evidence.contains("redacted-api-key"), "the evidence names the redaction marker's rule");
        assertFalse(evidence.contains(CANARY), "the evidence never carries the credential");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
