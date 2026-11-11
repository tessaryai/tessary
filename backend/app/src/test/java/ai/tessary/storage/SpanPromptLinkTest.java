// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The span's prompt link through the real schema: the upsert stores it, a later write replaces it as every other
 * producer field is replaced, the fixture copy keeps it, and ingest writes it from the span's attributes.
 */
@SpringBootTest
record SpanPromptLinkTest(SubstrateV2Fixtures fx, SpanRepository spans, SpanBatchWriter writer, TenantService tenants) {

    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    private record Link(@Nullable String name, @Nullable String version) {}

    @Test
    void upsertStoresAndReadsThePromptLink() {
        // Bug: the upsert drops the two columns, or the read maps them from the wrong column.
        String pid = project("span-prompt-store");
        SpanRow row = fx.spanSeed(pid).prompt("summarize", "4").at(T0).write();

        assertEquals(new Link("summarize", "4"), link(pid, row.traceId(), row.id()));
    }

    @Test
    void laterWriteWithoutTheLinkClearsIt() {
        // Bug: the SET list leaves the link out, so a completed span keeps the partial's prompt after the producer
        // stopped sending it.
        String pid = project("span-prompt-lww");
        SpanRow partial = fx.spanSeed(pid).prompt("summarize", "4").at(T0).write();
        fx.spanSeed(pid)
                .traceId(partial.traceId())
                .spanId(partial.id())
                .at(T0)
                .endedAt(T0.plusSeconds(5))
                .write();

        assertEquals(new Link(null, null), link(pid, partial.traceId(), partial.id()));
    }

    @Test
    void fixtureCopyKeepsThePromptLink() {
        // Bug: a fixture re-write (usage, cost, previews) silently clears the link, so usage tests read no prompt.
        String pid = project("span-prompt-copy");
        SpanRow row = fx.withPrompt(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), T0), "p", "1");
        fx.withUsage(row, 10L, 5L);

        assertEquals(new Link("p", "1"), link(pid, row.traceId(), row.id()));
    }

    @Test
    void ingestStoresThePromptLinkFromTheAttributes() {
        // Bug: the write path never reads tessary.prompt.*, so no ingested span is ever linked to its prompt.
        String pid = project("span-prompt-ingest");
        writer.write(
                pid,
                List.of(llm(
                        "tr-prompt-ingest",
                        "sp-linked",
                        Map.of(GenAiAttributes.PROMPT_NAME, "summarize", GenAiAttributes.PROMPT_VERSION, 4L))));

        assertEquals(new Link("summarize", "4"), link(pid, "tr-prompt-ingest", "sp-linked"));
    }

    @Test
    void oversizedNameSpanIsStillWritten() {
        // Bug: a prompt name too long for the index drops the whole span instead of only the link.
        String pid = project("span-prompt-oversized");
        char[] name = new char[600];
        Arrays.fill(name, 'n');
        writer.write(
                pid, List.of(llm("tr-prompt-long", "sp-long", Map.of(GenAiAttributes.PROMPT_NAME, new String(name)))));

        assertEquals(new Link(null, null), link(pid, "tr-prompt-long", "sp-long"));
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private Link link(String pid, String traceId, String spanId) {
        SpanRow read = spans.listByTrace(pid, traceId).stream()
                .filter(s -> s.id().equals(spanId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span " + spanId + " was not written"));
        return new Link(read.promptName(), read.promptVersion());
    }

    private static RawEntry llm(String traceId, String spanId, Map<String, Object> attributes) {
        return new RawEntry(
                spanId,
                "chat",
                "in",
                "out",
                null,
                attributes,
                null,
                traceId,
                T0.toString(),
                KindNormalizer.LLM,
                T0.plusSeconds(1).toString(),
                null,
                null);
    }
}
