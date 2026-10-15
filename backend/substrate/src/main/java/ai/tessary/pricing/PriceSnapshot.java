// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import ai.tessary.open.hash.Sha256;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * The version is {@code <source>-<first 12 hex of sha256>} of the vendored bytes, which makes import idempotent and
 * matches home.tessary.ai's digest. Rates convert to per-MTok on the way in with {@link BigDecimal#movePointRight},
 * exactly.
 */
public record PriceSnapshot(String source, String version, String digest, List<Model> models) {

    private static final Logger log = LoggerFactory.getLogger(PriceSnapshot.class);

    public static final String LITELLM_RESOURCE = "pricing/litellm-model-prices.json";

    private static final int VERSION_HASH_CHARS = 12;

    private static final int PER_MTOK_SHIFT = 6;

    /** {@code id} is lowercased because every lookup is. */
    public record Model(
            String id, String displayName, @Nullable String provider, ModelRates rates) {}

    /** Never throws: a missing or corrupt book must not stop boot; every model is then unpriced. */
    public static Optional<PriceSnapshot> load(ObjectMapper mapper, String source, String resource) {
        byte[] bytes;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("price snapshot unreadable at {}; its models will hold no rate", resource, e);
            return Optional.empty();
        }
        return parse(mapper, source, bytes, resource);
    }

    /** The digest is taken over exactly these bytes, never a re-serialization. */
    public static Optional<PriceSnapshot> parse(ObjectMapper mapper, String source, byte[] bytes, String origin) {
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException e) {
            log.warn("price snapshot at {} is not parseable JSON; its models will hold no rate", origin, e);
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            log.warn("price snapshot at {} is not a JSON object; its models will hold no rate", origin);
            return Optional.empty();
        }
        Map<String, Model> models = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
            Model parsed = model(id, entry.getKey(), entry.getValue());
            if (!pricesTokens(parsed.rates())) return;
            models.putIfAbsent(id, parsed);
        });
        String sha256 = sha256Hex(bytes);
        return Optional.of(new PriceSnapshot(
                source, source + "-" + sha256.substring(0, VERSION_HASH_CHARS), sha256, List.copyOf(models.values())));
    }

    /**
     * An entry with neither input nor output rate would import as a model costing zero, indistinguishable from free and
     * hidden from the unpriced count. About 740 upstream entries are per-image or per-second. The file hash is
     * unaffected.
     */
    private static boolean pricesTokens(ModelRates rates) {
        return isRate(rates.inputPerMtok()) || isRate(rates.outputPerMtok());
    }

    private static boolean isRate(@Nullable BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    private static Model model(String id, String key, JsonNode spec) {
        return new Model(
                id,
                key,
                provider(id),
                new ModelRates(
                        perMtok(spec, "input_cost_per_token"),
                        perMtok(spec, "output_cost_per_token"),
                        perMtok(spec, "cache_read_input_token_cost"),
                        perMtok(spec, "cache_creation_input_token_cost")));
    }

    /** Not guessed otherwise: a vendor-dotted Bedrock id names the maker, not who serves it. */
    private static @Nullable String provider(String id) {
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : null;
    }

    private static @Nullable BigDecimal perMtok(JsonNode spec, String field) {
        JsonNode v = spec.get(field);
        if (v == null || v.isNull()) return null;
        return new BigDecimal(v.asText()).movePointRight(PER_MTOK_SHIFT);
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(Sha256.digest(bytes));
    }
}
