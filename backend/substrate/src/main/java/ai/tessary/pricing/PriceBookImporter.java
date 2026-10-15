// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import ai.tessary.config.PricingProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Imports the jar's bundled rate file on every boot, rather than seeding by migration, since rates change and a
 * changeset is immutable. Idempotent by content: the version is a hash of the bytes. Dated by the jar's build time, not
 * boot time, so a restart cannot put an older bundled book in force over a newer fetched one.
 */
@Component
public class PriceBookImporter {

    private static final Logger log = LoggerFactory.getLogger(PriceBookImporter.class);

    private final PriceBookRepository books;
    private final PricingProperties props;
    private final ObjectMapper mapper;
    private final ObjectProvider<BuildProperties> build;

    public PriceBookImporter(
            PriceBookRepository books,
            PricingProperties props,
            ObjectMapper mapper,
            ObjectProvider<BuildProperties> build) {
        this.books = books;
        this.props = props;
        this.mapper = mapper;
        this.build = build;
    }

    /**
     * Never fatal: a throwing {@code ApplicationReadyEvent} listener fails the context, and a missing rate already
     * reads as unpriced.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importOnBoot() {
        try {
            importSnapshots();
        } catch (RuntimeException e) {
            log.warn("price book import failed on boot; models will read as unpriced until the next boot", e);
        }
    }

    /** What happened is in the log. */
    public void importSnapshots() {
        if (!props.enabled()) return;
        importSnapshot(PriceBook.SOURCE_LITELLM, PriceSnapshot.LITELLM_RESOURCE);
    }

    /** The jar's {@code build-info} time, or now outside a packaged build. */
    Instant bundledPublishedAt() {
        BuildProperties info = build.getIfAvailable();
        if (info == null || info.getTime() == null) return Instant.now();
        return info.getTime();
    }

    private void importSnapshot(String source, String resource) {
        Instant started = Instant.now();
        Optional<PriceSnapshot> parsed = PriceSnapshot.load(mapper, source, resource);
        if (parsed.isEmpty()) return;
        PriceSnapshot snapshot = parsed.get();
        Instant publishedAt = bundledPublishedAt();

        if (books.hasBook(snapshot.version())) {
            // See PriceBookRepository#reconcile.
            books.reconcile(snapshot.version(), snapshot.digest(), publishedAt);
            log.debug(
                    "price book {} already imported, {} models unchanged",
                    snapshot.version(),
                    snapshot.models().size());
            return;
        }
        PriceBookRepository.Imported imported = books.importBook(snapshot, publishedAt);
        if (!imported.applied()) {
            log.debug("price book {} was imported concurrently by another instance", snapshot.version());
            return;
        }
        StructuredLog.info(log, Markers.OPS, "pricing.book.imported")
                .message(
                        "imported price book %s: %d rates, %d models we had not seen before",
                        snapshot.version(), imported.rates(), imported.newModels())
                .field("version", snapshot.version())
                .field("source", source)
                .field("publishedAt", publishedAt.toString())
                .field("models", snapshot.models().size())
                .field("newModels", imported.newModels())
                .field("rates", imported.rates())
                .durationMs(started)
                .log();
    }
}
