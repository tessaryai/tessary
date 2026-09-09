// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * What the write path now records about a span's images and about its failure — the two halves of the
 * same batch write, and the two things that were being thrown away.
 *
 * <p><b>Images (#761).</b> Externalizing an inline base64 image stores bytes in {@code media_object} and
 * leaves an {@code image_ref} string in the payload JSON. That string is the whole reference: no FK, no
 * join, no cascade could see it, so nothing could tell whether an image was still in use and retention
 * had no media step at all. The {@code media_ref} rows asserted here are that reference made visible, and
 * the cascade test is the reason they hang off the PAYLOAD rather than the span.
 *
 * <p><b>Failure (#762).</b> {@code error_type} used to be written from the producer's status message, so
 * the column every facet and {@code GROUP BY} treats as a type held prose. Here the class goes to
 * {@code error_type} and the prose to {@code error_message}, at both grains.
 *
 * <p>It shares {@link SpanBatchWriterIntegrationTest}'s property fingerprint deliberately: same context,
 * same database, no second Liquibase run — and, less obviously, the writer enqueues an embedding job per
 * span it writes, which in the default context would be claimed out from under
 * {@code JobRepositoryTest}'s globally-scoped claim assertion.
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
    @DisplayName("an externalized image leaves a media_ref row naming the span whose payload references it")
    void externalizedImage_writesMediaRef() {
        Project p = project("media-ref-write");
        String traceId = "trace-media";

        writer.write(p.id(), List.of(withImage("span-media", traceId, oneRedPixel())));

        String mediaId = jdbc.sql("SELECT id FROM media_object WHERE project_id = :pid")
                .param("pid", p.id())
                .query(String.class)
                .single();
        assertNotNull(mediaId, "the bytes are stored");

        String payload = jdbc.sql("SELECT input FROM span_payload WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", p.id())
                .param("tid", traceId)
                .query(String.class)
                .single();
        assertNotNull(payload);
        assertTrue(payload.contains(mediaId), "the payload references the image by id");
        assertFalse(payload.contains(oneRedPixel()), "and no base64 survives into the substrate");

        assertEquals(
                1L,
                mediaRefs(p, mediaId),
                "the reference the payload carries as a string must also exist as a row, or nothing can"
                        + " tell whether these bytes are still in use (#761)");
    }

    @Test
    @DisplayName("the media_ref rows cascade with the payload that referenced the image")
    void payloadDelete_cascadesMediaRefs() {
        Project p = project("media-ref-cascade");
        String traceId = "trace-cascade";
        writer.write(p.id(), List.of(withImage("span-cascade", traceId, oneRedPixel())));
        String mediaId = jdbc.sql("SELECT id FROM media_object WHERE project_id = :pid")
                .param("pid", p.id())
                .query(String.class)
                .single();
        assertEquals(1L, mediaRefs(p, mediaId));

        // Exactly what retention's first tier does — payloads age ahead of spans.
        jdbc.sql("DELETE FROM span_payload WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", p.id())
                .param("tid", traceId)
                .update();

        assertEquals(
                0L,
                mediaRefs(p, mediaId),
                "media's lifetime is the payload's: once the text that names the image is gone, the image"
                        + " is unreachable, and the reference must go with it so the collector can see that");
    }

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
                        null,
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
    @DisplayName("a producer that names no error.type still gets a short, groupable error_type")
    void errorSpan_withoutDeclaredType_signaturesTheMessage() {
        Project p = project("error-split-fallback");
        String prose = "HTTP 500 upstream from https://api.example.com/v1/charges/ch_3Ox9aB after 30014ms";

        writer.write(p.id(), List.of(errorEntry("span-sig", "trace-sig", KindNormalizer.LLM, null, prose)));

        Map<String, Object> row = jdbc.sql(
                        "SELECT error_type, error_message FROM span WHERE project_id = :pid AND id = 'span-sig'")
                .param("pid", p.id())
                .query()
                .singleRow();
        String type = (String) row.get("error_type");
        assertNotNull(type);
        assertNotEquals(prose, type, "the fallback is a signature, not the message itself");
        assertTrue(type.contains("<url>") && type.contains("<num>"), "ids and URLs are placeholders: " + type);
        assertTrue(type.length() <= 121, "and it is bounded, because this column is a facet key");
        assertEquals(prose, row.get("error_message"));
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

    // ---- fixtures ------------------------------------------------------------------------------

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

    /** A 1×1 PNG, base64 — small enough to inline here, real enough to decode. */
    private static String oneRedPixel() {
        return Base64.getEncoder()
                .encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
    }

    private static String anthropicImagePayload(String b64) {
        return "[{\"type\":\"text\",\"text\":\"what is this\"},"
                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""
                + b64 + "\"}}]";
    }

    private static RawEntry withImage(String spanId, String traceId, String b64) {
        return new RawEntry(
                spanId,
                null,
                spanId,
                anthropicImagePayload(b64),
                "looks like a pixel",
                null,
                Map.of(),
                null,
                traceId,
                Instant.now().toString(),
                KindNormalizer.LLM,
                Instant.now().toString(),
                null,
                null);
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
                null,
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
