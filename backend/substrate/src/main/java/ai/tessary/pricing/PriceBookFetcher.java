// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import ai.tessary.config.PricingProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.telemetry.HomeTessaryClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls the price book home.tessary.ai publishes, when it is newer than what this install holds.
 *
 * <p><b>Called only from the telemetry heartbeat's tick, after its enabled-gate.</b> This class does not check
 * {@code TESSARY_TELEMETRY_ENABLED} itself and must never be scheduled on its own: the gate living in one place
 * is what keeps the contract's "zero outbound calls when disabled" true. An opted-out install prices from the
 * book bundled in its jar, exactly as before.
 *
 * <p><b>The manifest is the hash check.</b> {@code GET /v1/pricing/manifest.json} is a few hundred bytes naming
 * the current book's sha256. When a book with that digest is already imported, which is almost every tick,
 * nothing else is requested. Only a new digest costs the ~2 MB {@code GET /v1/pricing/<digest>.json}, and
 * home publishes tessary's vendored LiteLLM file byte for byte, so the downloaded bytes parse exactly as the
 * bundled file does and a book that is already in the jar is recognised by digest without a download.
 *
 * <p><b>Nothing home returns is trusted past what it can prove.</b> The artifact URL must be home's own path for
 * that digest, the body must hash to the digest it was fetched by, and it must be under a size limit and parse
 * to at least one priced model. Anything else is logged and skipped, and the install keeps the book it has.
 *
 * <p><b>Never throws.</b> A failed fetch is the next tick's retry. Pricing must never depend on home being up.
 */
@Component
public class PriceBookFetcher {

    private static final Logger log = LoggerFactory.getLogger(PriceBookFetcher.class);

    static final String MANIFEST_PATH = "/v1/pricing/manifest.json";

    /** The manifest shape this client parses: {@code {schema, digest, url, published_at}}. */
    public static final int SUPPORTED_SCHEMA = 1;

    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    /** The file is about 2 MB. Anything past this is not the file. */
    static final int MAX_BOOK_BYTES = 32 * 1024 * 1024;

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** What one refresh did, for the tick's log and for tests. */
    public enum Outcome {
        DISABLED,
        UNREACHABLE,
        BAD_MANIFEST,
        UNSUPPORTED_SCHEMA,
        ALREADY_HELD,
        BAD_BOOK,
        IMPORTED
    }

    private record Manifest(String digest, Instant publishedAt) {}

    private final PricingProperties props;
    private final HomeTessaryClient client;
    private final PriceBookRepository books;
    private final ObjectMapper mapper;

    public PriceBookFetcher(
            PricingProperties props, HomeTessaryClient client, PriceBookRepository books, ObjectMapper mapper) {
        this.props = props;
        this.client = client;
        this.books = books;
        this.mapper = mapper;
    }

    /** Check home's manifest and import its book if this install does not hold it yet. */
    public Outcome refresh() {
        if (!props.isEnabled()) return Outcome.DISABLED;
        Instant started = Instant.now();
        try {
            HomeTessaryClient.Fetched raw = client.getBytes(MANIFEST_PATH, MAX_MANIFEST_BYTES);
            if (raw.status() != 200) {
                log.debug("price book manifest answered HTTP {}", raw.status());
                return Outcome.UNREACHABLE;
            }
            JsonNode node = parseJson(raw.body());
            if (node == null) {
                log.debug("price book manifest is not JSON");
                return Outcome.BAD_MANIFEST;
            }
            if (node.path("schema").asInt(-1) != SUPPORTED_SCHEMA) {
                log.debug("price book manifest schema {} is not {}", node.path("schema"), SUPPORTED_SCHEMA);
                return Outcome.UNSUPPORTED_SCHEMA;
            }
            Optional<Manifest> manifest = manifest(node);
            if (manifest.isEmpty()) return Outcome.BAD_MANIFEST;
            String digest = manifest.get().digest();

            if (books.hasDigest(digest)) return Outcome.ALREADY_HELD;

            HomeTessaryClient.Fetched body = client.getBytes(artifactPath(digest), MAX_BOOK_BYTES);
            if (body.status() != 200) {
                log.debug("price book {} answered HTTP {}", digest, body.status());
                return Outcome.UNREACHABLE;
            }
            String actual = PriceSnapshot.sha256Hex(body.body());
            // Constant-time: the digest is public, but SpotBugs holds every hash comparison to it and a
            // timing-safe compare costs nothing here.
            if (!MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII))) {
                log.warn("price book fetched as {} hashes to {}; keeping the book in force", digest, actual);
                return Outcome.BAD_BOOK;
            }
            Optional<PriceSnapshot> parsed =
                    PriceSnapshot.parse(mapper, PriceBook.SOURCE_LITELLM, body.body(), artifactPath(digest));
            if (parsed.isEmpty() || parsed.get().models().isEmpty()) {
                log.warn("price book {} priced no models; keeping the book in force", digest);
                return Outcome.BAD_BOOK;
            }

            PriceSnapshot snapshot = parsed.get();
            PriceBookRepository.Imported imported =
                    books.importBook(snapshot, manifest.get().publishedAt());
            StructuredLog.info(log, Markers.OPS, "pricing.book.fetched")
                    .message(
                            "fetched price book %s from home.tessary.ai: %d rates, %d models we had not seen before",
                            snapshot.version(), imported.rates(), imported.newModels())
                    .field("version", snapshot.version())
                    .field("digest", digest)
                    .field("publishedAt", manifest.get().publishedAt().toString())
                    .field("applied", imported.applied())
                    .field("models", snapshot.models().size())
                    .field("newModels", imported.newModels())
                    .field("rates", imported.rates())
                    .durationMs(started)
                    .log();
            return Outcome.IMPORTED;
        } catch (IOException | RuntimeException e) {
            log.debug("price book fetch failed", e);
            return Outcome.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.UNREACHABLE;
        }
    }

    /** home's own path for a digest, which is the only URL a manifest may name. */
    static String artifactPath(String digest) {
        return "/v1/pricing/" + digest + ".json";
    }

    private Optional<Manifest> manifest(JsonNode node) {
        String digest = node.path("digest").asText("");
        if (!SHA256_HEX.matcher(digest).matches()) {
            log.debug("price book manifest digest is not a sha256: {}", digest);
            return Optional.empty();
        }
        String url = node.path("url").asText("");
        if (!url.equals(HomeTessaryClient.BASE_URL + artifactPath(digest))) {
            log.warn("price book manifest names {}, not home's own path for {}; ignoring it", url, digest);
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new Manifest(digest, Instant.parse(node.path("published_at").asText(""))));
        } catch (DateTimeParseException e) {
            log.debug("price book manifest published_at is not an instant", e);
            return Optional.empty();
        }
    }

    private @Nullable JsonNode parseJson(byte[] bytes) {
        try {
            JsonNode node = mapper.readTree(bytes);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }
}
