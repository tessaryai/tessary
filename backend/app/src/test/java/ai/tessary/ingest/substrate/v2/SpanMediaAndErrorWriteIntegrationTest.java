// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Covers what the write path records for a span's images and its failure: an externalized image
 * gets a {@code media_ref} row (hung off the payload, not the span, so it cascades with the
 * payload's text), and a failing span splits its class into {@code error_type} from its prose
 * into {@code error_message}.
 *
 * <p>Shares {@link SpanBatchWriterIntegrationTest}'s property fingerprint (same context, same
 * database, no second Liquibase run).
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
class SpanMediaAndErrorWriteIntegrationTest {

    @Autowired
    TenantService tenants;

    @Autowired
    SpanBatchWriter writer;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("the same image in input and output is one reference, not two")
    void duplicateImage_isOneRef() {
        Project p = project("media-ref-dedupe");
        String traceId = "trace-dupe";
        String b64 = oneRedPixel();
        writer.write(
                p.id(),
                List.of(new RawEntry(
                        "span-dupe",
                        "span-dupe",
                        anthropicImagePayload(b64),
                        anthropicImagePayload(b64),
                        null,
                        Map.of(),
                        null,
                        traceId,
                        Instant.now().toString(),
                        KindNormalizer.LLM,
                        Instant.now().toString(),
                        null,
                        null)));

        String mediaId = jdbc.sql("SELECT id FROM media_object WHERE project_id = :pid")
                .param("pid", p.id())
                .query(String.class)
                .single();
        assertEquals(1L, mediaRefs(p, mediaId), "identical bytes dedupe to one media_object and one referrer");
    }

    @Test
    @DisplayName("a failing span files the class in error_type and the prose in error_message")
    void errorSpan_splitsClassFromProse() {
        Project p = project("error-split-span");
        String prose = "The upstream order service timed out after 30014ms.\n\n"
                + "```\nstack frame one\nstack frame two\n```\nRetry 3/3 abandoned.";

        writer.write(
                p.id(), List.of(errorEntry("span-err", "trace-err", KindNormalizer.LLM, "OrderServiceTimeout", prose)));

        Map<String, Object> row = jdbc.sql(
                        "SELECT error_type, error_message FROM span WHERE project_id = :pid AND id = 'span-err'")
                .param("pid", p.id())
                .query()
                .singleRow();
        assertEquals("OrderServiceTimeout", row.get("error_type"), "the producer's own class, verbatim");
        assertEquals(prose, row.get("error_message"), "and the message it described the failure with");
    }

    @Test
    @DisplayName("a failing tool call splits the same way")
    void errorToolCall_splitsClassFromProse() {
        Project p = project("error-split-tool");
        String prose = "search_docs returned 502 from the index after 4 attempts";

        writer.write(p.id(), List.of(errorEntry("span-tool", "trace-tool", KindNormalizer.TOOL, "IndexError", prose)));

        Map<String, Object> row = jdbc.sql("SELECT error_type, error_message, is_error FROM tool_call"
                        + " WHERE project_id = :pid AND span_id = 'span-tool'")
                .param("pid", p.id())
                .query()
                .singleRow();
        assertEquals("IndexError", row.get("error_type"));
        assertEquals(prose, row.get("error_message"));
        assertEquals(Boolean.TRUE, row.get("is_error"), "the flag the classifier keys off is unchanged");
    }

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    private long mediaRefs(Project p, String mediaId) {
        Long n = jdbc.sql("SELECT count(*) FROM media_ref WHERE project_id = :pid AND media_id = :mid")
                .param("pid", p.id())
                .param("mid", mediaId)
                .query(Long.class)
                .single();
        return n == null ? 0L : n;
    }

    /** A 1x1 PNG, base64: small enough to inline, real enough to decode. */
    private static String oneRedPixel() {
        return Base64.getEncoder()
                .encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
    }

    private static String anthropicImagePayload(String b64) {
        return "[{\"type\":\"text\",\"text\":\"what is this\"},"
                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""
                + b64 + "\"}}]";
    }

    private static RawEntry errorEntry(
            String spanId, String traceId, String kind, @Nullable String declaredType, String statusMessage) {
        Map<String, Object> attrs = declaredType == null
                ? Map.of("level", "ERROR", GenAiAttributes.STATUS_MESSAGE, statusMessage)
                : Map.of(
                        "level",
                        "ERROR",
                        GenAiAttributes.ERROR_TYPE,
                        declaredType,
                        GenAiAttributes.STATUS_MESSAGE,
                        statusMessage);
        return new RawEntry(
                spanId,
                spanId,
                "in-" + spanId,
                "out-" + spanId,
                null,
                attrs,
                null,
                traceId,
                Instant.now().toString(),
                kind,
                Instant.now().toString(),
                null,
                null);
    }
}
