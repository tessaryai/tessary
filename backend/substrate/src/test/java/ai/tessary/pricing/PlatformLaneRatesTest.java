// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The standing form of the pre-flip parity check: what the PLATFORM's own calls are billed at, pinned per
 * model name against the checked-in vendored rate file.
 *
 * <p><b>Why a pinned table and not a property.</b> Before the platform lane moved onto the price book its
 * rates came from a hand-maintained catalog compiled into the jar, and comparing the two at the flip found
 * seven disagreements on ten models. A hand-maintained {@code manual-overrides.json} carried those
 * corrections forward until it was retired: five were stale carryovers upstream had since
 * corrected, the mantle GPT-5.6 rows are now resolved by {@code BedrockModelProfile.MANTLE_ROUTE_PREFIX}
 * reporting the id LiteLLM actually prices (see {@link #mantleIdsResolveToTheirOwnRoute}), and Nova's
 * cache-write bucket — which the snapshot has never carried, for any generation — is now unpriced rather
 * than an unverified guess. This test states the answers directly: a snapshot refresh that moves a
 * platform rate fails here and gets read by a human, instead of restating last month's bill.
 *
 * <p>Update a row here only together with the snapshot change that moves it, and say in the commit which
 * published price page the new number came from.
 */
class PlatformLaneRatesTest {

    /**
     * Every model string the platform lane can stamp on an {@code llm_call} row: the Bedrock
     * inference-profile ids {@code BedrockModelProfile} sends (the platform-funded lanes), and the BYO
     * catalog ids a project can pin. Held as literals rather than read from {@code llm-runtime} because
     * that module sits ABOVE this one — and because the point is to pin what those ids resolve to, which a
     * shared constant could quietly change on both sides at once.
     */
    private static final Map<String, String[]> EXPECTED = expected();

    private static Map<String, String[]> expected() {
        Map<String, String[]> m = new LinkedHashMap<>();
        // model name as reported → {input, output, cacheRead, cacheWrite} per MTok, "-" for never billed.
        m.put("global.anthropic.claude-haiku-4-5-20251001-v1:0", new String[] {"1", "5", "0.1", "1.25"});
        m.put("global.anthropic.claude-sonnet-5", new String[] {"2", "10", "0.2", "2.5"});
        m.put("global.anthropic.claude-sonnet-4-6", new String[] {"3", "15", "0.3", "3.75"});
        m.put("global.amazon.nova-2-lite-v1:0", new String[] {"0.3", "2.5", "0.075", "-"});
        // The route-prefixed spelling BedrockModelProfile now reports for mantle — see
        // BedrockModelProfile.MANTLE_ROUTE_PREFIX and mantleIdsResolveToTheirOwnRoute below.
        m.put("bedrock_mantle/openai.gpt-5.6-luna", new String[] {"0.22", "1.32", "0.022", "0.275"});
        m.put("bedrock_mantle/openai.gpt-5.6-terra", new String[] {"2.2", "13.2", "0.22", "2.75"});
        m.put("anthropic.claude-sonnet-5", new String[] {"2", "10", "0.2", "2.5"});
        m.put("anthropic.claude-sonnet-4-6", new String[] {"3", "15", "0.3", "3.75"});
        m.put("anthropic.claude-opus-4-7", new String[] {"5", "25", "0.5", "6.25"});
        m.put("anthropic.claude-haiku-4-5", new String[] {"1", "5", "0.1", "1.25"});
        m.put("gpt-5.5", new String[] {"5", "30", "0.5", "-"});
        m.put("gpt-5.4-mini", new String[] {"0.75", "4.5", "0.075", "-"});
        m.put("gpt-5.4-nano", new String[] {"0.2", "1.25", "0.02", "-"});
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

    @Test
    @DisplayName("BedrockModelProfile's route prefix is what makes the mantle ids resolve to mantle rates")
    void mantleIdsResolveToTheirOwnRoute() {
        // BedrockModelProfile.MANTLE_ROUTE_PREFIX makes the platform report
        // bedrock_mantle/openai.gpt-5.6-luna, which the vendored snapshot carries verbatim as mantle's own
        // priced route — no override needed. ModelResolver's exact-match leg is untouched.
        Books books = Books.load(mapper);
        String resolved = books.resolve("bedrock_mantle/openai.gpt-5.6-luna");
        assertEquals("bedrock_mantle/openai.gpt-5.6-luna", resolved);
        ModelRates rates = books.rates(resolved);
        assertNotNull(rates);
        assertEquals(0, requireRate(rates.inputPerMtok()).compareTo(new BigDecimal("0.22")));
    }

    @Test
    @DisplayName("the bare id a producer regression would report still falls through to OpenAI-direct")
    void bareMantleIdWouldStillFallThroughToOpenAiDirect() {
        // The regression BedrockModelProfile.MANTLE_ROUTE_PREFIX exists to prevent, kept as a standing
        // check: if a future change ever reports the bare id again, ModelResolver has no scope prefix to
        // strip and falls through the vendor strip onto OpenAI's own gpt-5.6-luna row — a different
        // product at a different price (0.20 direct vs 0.22 mantle, as of the 2026-09-02 refresh; direction
        // isn't the invariant, a price DIFFERENCE is).
        Books books = Books.load(mapper);
        String fallthrough = books.resolve("openai.gpt-5.6-luna");
        assertEquals("gpt-5.6-luna", fallthrough, "the bare id has no scope prefix, so it falls to the vendor strip");
        ModelRates wrongRates = books.rates("gpt-5.6-luna");
        assertNotNull(wrongRates);
        assertTrue(
                requireRate(wrongRates.inputPerMtok()).compareTo(new BigDecimal("0.22")) != 0,
                "OpenAI-direct must price differently from mantle's real rate, or this regression is silent");
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

    private static BigDecimal requireRate(@Nullable BigDecimal rate) {
        assertNotNull(rate, "expected a rate");
        return rate;
    }

    /**
     * The checked-in vendored rate file, driving the real {@link ModelResolver} through a stub repository
     * — so this test exercises the production resolution legs without a database.
     */
    private static final class Books {

        private final Map<String, ModelRates> rates;
        private final ModelResolver resolver;

        private Books(Map<String, ModelRates> rates) {
            this.rates = rates;
            // The production resolver, driven through a repository that answers from the file rather than
            // from Postgres — so the exact → region-strip → vendor-strip legs under test are the real ones.
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
