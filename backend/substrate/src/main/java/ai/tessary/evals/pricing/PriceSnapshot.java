// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * A checked-in rate file, parsed: the models it prices and the version those rates will be stored under.
 *
 * <p><b>The version is the content, hashed.</b> A snapshot file carries no date of its own — {@code
 * scripts/refresh-model-prices.sh} vendors the upstream LiteLLM file verbatim, and upstream stamps it with
 * nothing — so the version is {@code <source>-<first 12 hex of the file's sha256>}. That is
 * what makes {@link PriceBookImporter} idempotent for free: the same bytes always compute the same
 * version, so a boot that finds that version already present has nothing to do, and a refreshed snapshot
 * is a different version and therefore a new book. A date would have been weaker on both counts — two
 * different files can carry one date, and one file re-imported on two days would look like two books.
 *
 * <p><b>Rates are converted to per-million-token on the way in.</b> The files are per-token because that
 * is LiteLLM's shape; the schema is per MTok because that is how a rate is published and read. Converting
 * once here means no reader ever has to know which unit it is holding, and the conversion is exact —
 * {@link BigDecimal#movePointRight} shifts the decimal point rather than multiplying through a double.
 */
public record PriceSnapshot(String source, String version, List<Model> models) {

    private static final Logger log = LoggerFactory.getLogger(PriceSnapshot.class);

    /** The vendored LiteLLM snapshot, refreshed by {@code scripts/refresh-model-prices.sh}. */
    public static final String LITELLM_RESOURCE = "pricing/litellm-model-prices.json";

    private static final int VERSION_HASH_CHARS = 12;

    /** Per-token → per-MTok. */
    private static final int PER_MTOK_SHIFT = 6;

    /**
     * One model the file prices. {@code id} is lowercased because every lookup is; {@code displayName}
     * keeps the key exactly as the file spells it.
     */
    public record Model(
            String id, String displayName, @Nullable String provider, ModelRates rates) {}

    /**
     * Parse a rate file from the classpath, or empty when it cannot be read.
     *
     * <p>Never throws. A missing or corrupt book must not stop the app booting: every model then holds no
     * rate, which the substrate records honestly as unpriced and counts. Failing the boot instead would
     * turn a stale vendored file into an outage.
     */
    public static Optional<PriceSnapshot> load(ObjectMapper mapper, String source, String resource) {
        byte[] bytes;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("price snapshot unreadable at {}; its models will hold no rate", resource, e);
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException e) {
            log.warn("price snapshot at {} is not parseable JSON; its models will hold no rate", resource, e);
            return Optional.empty();
        }
        Map<String, Model> models = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
            Model parsed = model(id, entry.getKey(), entry.getValue());
            if (!pricesTokens(parsed.rates())) return;
            models.putIfAbsent(id, parsed);
        });
        return Optional.of(new PriceSnapshot(source, source + "-" + digest(bytes), List.copyOf(models.values())));
    }

    /**
     * Whether an entry prices tokens at all, and therefore belongs in a price book.
     *
     * <p>An entry carrying neither an input nor an output rate prices nothing. Imported anyway it becomes
     * a {@code model_price} row whose every bucket is null, which is not "unpriced" — it is a model the
     * book claims to know, costing zero. A reader cannot tell that from a genuinely free model, and the
     * unpriced count that exists to surface the gap stops counting it.
     *
     * <p>Upstream carries about 740 of these out of 3,500: image and audio models billed per image or per
     * second rather than per token, plus LiteLLM's own {@code sample_spec} documentation stub. Before
     * 2026-09-02 they never got this far, because {@code scripts/refresh-model-prices.sh} dropped them
     * while trimming the vendored file down to four fields. That script now vendors upstream verbatim, so
     * the rule lives here — a property of what a price book means, not of how the file was produced.
     * {@link ai.tessary.evals.vitals.TokenPriceBook} applies the identical rule on the in-memory read
     * path and the two must not drift: a model imported here but skipped there (or the reverse) reads as
     * priced in one surface and unpriced in the other, which is the exact disagreement the layered-book
     * design exists to avoid.
     *
     * <p>Note this filters the models, never the hash: {@link #load} digests the file's raw bytes, so the
     * version still identifies exactly what was vendored.
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

    /**
     * LiteLLM's own provider-route prefix ({@code vertex_ai/claude-sonnet-5} → {@code vertex_ai}), and
     * null for a bare key. Deliberately not guessed from anything else: a vendor-dotted Bedrock id names
     * the model's maker rather than who serves it, and inventing a provider for it would put a value in
     * the column that no file actually claims.
     */
    private static @Nullable String provider(String id) {
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : null;
    }

    private static @Nullable BigDecimal perMtok(JsonNode spec, String field) {
        JsonNode v = spec.get(field);
        if (v == null || v.isNull()) return null;
        return new BigDecimal(v.asText()).movePointRight(PER_MTOK_SHIFT);
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash).substring(0, VERSION_HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
