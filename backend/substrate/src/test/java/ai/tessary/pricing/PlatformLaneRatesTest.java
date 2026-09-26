// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the platform's own calls are billed at, pinned per model against the vendored rate file.
 *
 * <p>A pinned table, not a property: the flip to the price book found seven disagreements on ten models, since
 * resolved (mantle via {@code BedrockModelProfile.MANTLE_ROUTE_PREFIX}; Nova's cache-write now unpriced). A snapshot
 * refresh that moves a platform rate fails here for a human to read. Update a row only with the snapshot change,
 * citing the published price page in the commit.
 */
class PlatformLaneRatesTest {

    /**
     * Every id the platform lane prices under: {@code BedrockModelProfile}'s inference-profile ids and the BYO
     * catalog ids ({@code ModelCatalog#pricingId}'s output for route-prefixed providers). Literals, since {@code llm-
     * runtime} sits above this module and a shared constant could change both sides at once.
     */
    private static final Map<String, String[]> EXPECTED = expected();

    private static Map<String, String[]> expected() {
        Map<String, String[]> m = new LinkedHashMap<>();
        // model name as reported → {input, output, cacheRead, cacheWrite} per MTok, "-" for never billed.
        m.put("global.anthropic.claude-haiku-4-5-20251001-v1:0", new String[] {"1", "5", "0.1", "1.25"});
        m.put("global.anthropic.claude-sonnet-5", new String[] {"2", "10", "0.2", "2.5"});
        m.put("global.anthropic.claude-sonnet-4-6", new String[] {"3", "15", "0.3", "3.75"});
        m.put("global.amazon.nova-2-lite-v1:0", new String[] {"0.3", "2.5", "0.075", "-"});
        // The route-prefixed spelling for mantle (BedrockModelProfile.MANTLE_ROUTE_PREFIX).
        m.put("bedrock_mantle/openai.gpt-5.6-luna", new String[] {"0.22", "1.32", "0.022", "0.275"});
        m.put("bedrock_mantle/openai.gpt-5.6-terra", new String[] {"2.2", "13.2", "0.22", "2.75"});
        m.put("anthropic.claude-sonnet-5", new String[] {"2", "10", "0.2", "2.5"});
        m.put("anthropic.claude-sonnet-4-6", new String[] {"3", "15", "0.3", "3.75"});
        m.put("anthropic.claude-opus-4-7", new String[] {"5", "25", "0.5", "6.25"});
        m.put("anthropic.claude-haiku-4-5", new String[] {"1", "5", "0.1", "1.25"});
        m.put("gpt-5.5", new String[] {"5", "30", "0.5", "-"});
        m.put("gpt-5.4-mini", new String[] {"0.75", "4.5", "0.075", "-"});
        m.put("gpt-5.4-nano", new String[] {"0.2", "1.25", "0.02", "-"});
        // Decision 19: without the scope prefix these ids resolved nowhere and every agentic run recorded no cost.
        m.put("xai/grok-4.6", new String[] {"2", "6", "0.5", "-"});
        m.put("xai/grok-code-fast-1", new String[] {"1", "2", "0.2", "-"});
        m.put("zai/glm-5.3", new String[] {"1.4", "4.4", "0.26", "0"});
        m.put("zai/glm-5.3-flash", new String[] {"0.15", "0.5", "0.03", "0"});
        m.put("moonshot/kimi-k2.6", new String[] {"0.95", "4", "0.16", "-"});
        m.put("openrouter/openai/gpt-5.6-terra", new String[] {"2", "12", "0.2", "2.5"});
        m.put("openrouter/openai/gpt-5.6-luna", new String[] {"0.2", "1.2", "0.02", "0.25"});
        return Map.copyOf(m);
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("every model the platform calls resolves in the books, at the rate it has been billed at")
    void platformModelsPriceAtTheirPinnedRates() {
        Books books = Books.load(mapper);
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : EXPECTED.entrySet()) {
            String resolved = books.resolve(entry.getKey());
            if (resolved == null) {
                wrong.add(entry.getKey() + " resolves to no model at all");
                continue;
            }
            ModelRates actual = books.rates(resolved);
            assertNotNull(actual, entry.getKey() + " resolved to " + resolved + " with no rates");
            String[] want = entry.getValue();
            if (!matches(actual, want)) {
                wrong.add(entry.getKey() + " → " + resolved + " expected " + String.join("/", want) + " got "
                        + render(actual));
            }
        }
        assertTrue(wrong.isEmpty(), "platform lane rates moved:\n  " + String.join("\n  ", wrong));
    }

    private static boolean matches(ModelRates actual, String[] want) {
        return same(actual.inputPerMtok(), want[0])
                && same(actual.outputPerMtok(), want[1])
                && same(actual.cacheReadPerMtok(), want[2])
                && same(actual.cacheWritePerMtok(), want[3]);
    }

    private static boolean same(@Nullable BigDecimal actual, String want) {
        if ("-".equals(want)) return actual == null;
        return actual != null && actual.compareTo(new BigDecimal(want)) == 0;
    }

    private static String render(ModelRates r) {
        return one(r.inputPerMtok()) + "/" + one(r.outputPerMtok()) + "/" + one(r.cacheReadPerMtok()) + "/"
                + one(r.cacheWritePerMtok());
    }

    private static String one(@Nullable BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    /** The vendored rate file driving the real {@link ModelResolver} through a stub repository. */
    private static final class Books {

        private final Map<String, ModelRates> rates;
        private final ModelResolver resolver;

        private Books(Map<String, ModelRates> rates) {
            this.rates = rates;
            // Answers from the file, so the exact, region-strip, and vendor-strip legs are the real ones.
            this.resolver = new ModelResolver(new PriceBookRepository(null, null) {
                @Override
                public boolean hasModel(String modelId) {
                    return rates(modelId) != null;
                }

                @Override
                public Optional<ModelRate> rateFor(String modelId) {
                    ModelRates r = rates(modelId);
                    return r == null ? Optional.empty() : Optional.of(new ModelRate("stub", r));
                }
            });
        }

        static Books load(ObjectMapper mapper) {
            PriceSnapshot snapshot = PriceSnapshot.load(
                            mapper, PriceBook.SOURCE_LITELLM, PriceSnapshot.LITELLM_RESOURCE)
                    .orElseThrow(() -> new AssertionError(PriceSnapshot.LITELLM_RESOURCE + " must load"));
            Map<String, ModelRates> out = new LinkedHashMap<>();
            for (PriceSnapshot.Model m : snapshot.models()) {
                out.put(m.id(), m.rates());
            }
            return new Books(out);
        }

        @Nullable
        String resolve(String modelName) {
            return resolver.resolve(modelName).orElse(null);
        }

        @Nullable
        ModelRates rates(String modelId) {
            return rates.get(modelId);
        }
    }
}
