// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.tessary.evals.classifier.substrate.ConversationThreadRenderer;
import ai.tessary.evals.classifier.substrate.ConversationThreadRenderer.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Train/serve parity for the context contract (v2): the Java {@link ConversationThreadRenderer} must
 * reproduce every {@code contract_v2} and {@code reduction} case in {@code
 * classifiers/framework/fixtures/context_contract.json} byte-for-byte — the same shared golden fixture
 * the Python side ({@code framework/context.py}) is pinned to. If this test fails, serving would render
 * the frustration head an input format it never trained on. Do not "fix" it by editing the rendered
 * strings; the fixture is the contract.
 */
class ConversationThreadContractParityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void javaRendererReproducesEveryContractV2GoldenCase() throws IOException {
        JsonNode root = mapper.readTree(Files.readString(contractFixture()));
        JsonNode cases = root.get("contract_v2");
        assertTrue(cases != null && cases.isArray() && !cases.isEmpty(), "contract_v2 cases present in the fixture");

        for (JsonNode c : cases) {
            String name = c.path("name").asText();
            List<Turn> turns = new ArrayList<>();
            for (JsonNode t : c.get("turns")) {
                turns.add(new Turn(t.get(0).asText(), content(t.get(1))));
            }
            String finalText = content(c.get("final"));

            String context = ConversationThreadRenderer.renderContext(turns);
            assertEquals(c.path("rendered_context").asText(), context, "rendered_context mismatch for case " + name);
            assertEquals(
                    c.path("rendered_input").asText(),
                    ConversationThreadRenderer.renderInput(context, finalText),
                    "rendered_input mismatch for case " + name);
        }
    }

    @Test
    void javaReducerReproducesEveryReductionGoldenCase() throws IOException {
        JsonNode root = mapper.readTree(Files.readString(contractFixture()));
        JsonNode cases = root.get("reduction");
        assertTrue(cases != null && cases.isArray() && !cases.isEmpty(), "reduction cases present in the fixture");

        for (JsonNode c : cases) {
            String name = c.path("name").asText();
            List<Turn> context = new ArrayList<>();
            for (JsonNode t : c.get("context")) {
                context.add(turn(t));
            }
            Turn finalTurn = turn(c.get("final"));
            String reduced = ConversationThreadRenderer.reduceThread(
                    context,
                    finalTurn,
                    c.path("budget").asInt(),
                    c.path("recent_k").asInt());
            assertEquals(c.path("reduced").asText(), reduced, "reduced mismatch for case " + name);
        }
    }

    @Test
    void javaNarrowingReproducesEveryGoldenCase() throws IOException {
        JsonNode root = mapper.readTree(Files.readString(contractFixture()));
        JsonNode cases = root.get("narrowing");
        assertTrue(cases != null && cases.isArray() && !cases.isEmpty(), "narrowing cases present in the fixture");

        for (JsonNode c : cases) {
            String name = c.path("name").asText();
            List<Turn> context = new ArrayList<>();
            for (JsonNode t : c.get("context")) {
                context.add(turn(t));
            }
            int userTurns = c.path("user_turns").asInt();
            List<Turn> shaped =
                    userTurns < 0 ? context : ConversationThreadRenderer.windowByUserTurns(context, userTurns);
            if (c.path("stub_assistant").asBoolean()) {
                shaped = ConversationThreadRenderer.stubAssistants(shaped);
            }
            String narrowed = ConversationThreadRenderer.reduceThread(
                    shaped, Turn.user(c.path("final").asText()), 100000, 3);
            assertEquals(c.path("narrowed").asText(), narrowed, "narrowed mismatch for case " + name);
        }
    }

    @Test
    void windowCountsUserTurnsNotBlocksSoToolsCannotEvictTheirUserTurn() {
        List<Turn> ctx = List.of(
                Turn.user("old ask"),
                Turn.assistant("old reply"),
                Turn.user("check the preauth"),
                Turn.tool("a", null),
                Turn.tool("b", null),
                Turn.tool("c", null));
        assertEquals(ctx.subList(2, ctx.size()), ConversationThreadRenderer.windowByUserTurns(ctx, 1));
    }

    @Test
    void windowZeroEmptiesAndOversizedWindowIsIdentity() {
        List<Turn> ctx = List.of(Turn.user("q"), Turn.assistant("a"));
        assertEquals(List.of(), ConversationThreadRenderer.windowByUserTurns(ctx, 0));
        assertEquals(ctx, ConversationThreadRenderer.windowByUserTurns(ctx, 99));
        assertEquals(List.of(), ConversationThreadRenderer.windowByUserTurns(List.of(), 1));
    }

    @Test
    void stubReplacesOnlyAssistantProseAndPreservesOrder() {
        List<Turn> ctx = List.of(Turn.user("u1"), Turn.assistant("a long agent reply"), Turn.tool("lookup", "boom"));
        assertEquals(
                List.of(
                        Turn.user("u1"),
                        Turn.assistant(ConversationThreadRenderer.ASSISTANT_STUB),
                        Turn.tool("lookup", "boom")),
                ConversationThreadRenderer.stubAssistants(ctx));
    }

    /** A reduction fixture turn: {@code [speaker, content]}, or {@code ["tool", name, error]}. */
    private static Turn turn(JsonNode node) {
        String speaker = node.get(0).asText();
        if ("tool".equals(speaker)) {
            JsonNode err = node.size() > 2 ? node.get(2) : null;
            return Turn.tool(node.get(1).asText(), err == null || err.isNull() ? null : err.asText());
        }
        return new Turn(speaker, content(node.get(1)));
    }

    /** A fixture turn/final content node: a bare string verbatim, or a multimodal parts array. */
    private static String content(JsonNode node) {
        return node.isArray() ? ConversationThreadRenderer.renderParts(node) : node.asText();
    }

    /**
     * The cross-language fixture, resolved from THIS checkout's root and nowhere else. {@code classifiers/}
     * is a delete row on the export denylist, so in the public export the fixture is absent by design and
     * the test is skipped with that reason rather than failed, or, worse, satisfied by a private copy in a
     * checkout that happens to contain the export directory.
     */
    private static Path contractFixture() {
        Path rel = Path.of("classifiers", "framework", "fixtures", "context_contract.json");
        Path candidate = CheckoutRoot.locate().resolve(rel);
        assumeTrue(
                Files.exists(candidate),
                "skipped: " + rel + " is not in this checkout (classifiers/ is private; the public export removes it)");
        return candidate;
    }
}
