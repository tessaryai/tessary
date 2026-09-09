// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.tessary.classifier.CheckoutRoot;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository.TraceAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Train/serve parity for the trajectory reduction: {@link TrajectoryAssembler#reduce} must reproduce
 * every case in the shared golden fixture the Python reference is pinned to.
 *
 * <p>Sibling of {@link BehaviorDriftParityTest}, which pins what the detector fires on. This pins what
 * it is fired on, since the two implementations have already drifted apart once without a red test.
 *
 * <p>If this fails, the two have drifted again. Do not "fix" it by regenerating the fixture.
 */
class TrajectoryAssemblerParityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void javaReductionReproducesEveryPinnedCase() throws IOException {
        JsonNode cases = mapper.readTree(Files.readString(fixture())).get("cases");
        assertTrue(cases != null && cases.isArray() && !cases.isEmpty(), "fixture carries cases");

        for (JsonNode c : cases) {
            String name = c.path("name").asText();
            assertEquals(
                    strings(c.get("symbols")),
                    TrajectoryAssembler.reduce(actions(c.get("actions")), rare(c.get("rare_symbols"))),
                    c.path("why").asText() + " [" + name + "]");
        }
    }

    /**
     * The fan-out grammar is the reason §2.4 exists, so assert the fixture still exercises it. A
     * fixture that quietly lost its fork cases would stay green while checking nothing.
     */
    @Test
    void theFixtureStillCoversTheFanOutGrammar() throws IOException {
        JsonNode cases = mapper.readTree(Files.readString(fixture())).get("cases");
        List<String> all = new ArrayList<>();
        for (JsonNode c : cases) all.addAll(strings(c.get("symbols")));

        assertTrue(all.stream().anyMatch(s -> s.startsWith(ActionSymbol.FORK_PREFIX)), "a fork is pinned");
        assertTrue(all.contains(ActionSymbol.JOIN), "a join is pinned");
        assertTrue(all.contains(ActionSymbol.LLM_ANSWER), "an answering LLM call is pinned");
    }

    /**
     * The fixture, resolved from this checkout's root only. Skips rather than fails when it is
     * absent, never climbing past the checkout into a parent that might hold a copy.
     */
    private static Path fixture() {
        Path rel = Path.of("classifiers", "behavior_drift", "fixtures", "reduction_contract.json");
        Path candidate = CheckoutRoot.locate().resolve(rel);
        assumeTrue(
                Files.exists(candidate),
                "skipped: " + rel + " is not in this checkout (classifiers/ is private; the public export removes it)");
        return candidate;
    }

    private static List<TraceAction> actions(JsonNode spec) {
        List<TraceAction> out = new ArrayList<>();
        for (JsonNode a : spec) {
            out.add(new TraceAction(
                    "fixture-trace",
                    text(a, "kind"),
                    text(a, "name"),
                    a.path("is_error").asBoolean(false),
                    text(a, "observation_id"),
                    text(a, "parent_id")));
        }
        return out;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** The epoch's rare-symbol set for a case, empty when the case does not exercise the floor. */
    private static Set<String> rare(@Nullable JsonNode array) {
        return array == null ? Set.of() : Set.copyOf(strings(array));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }
}
