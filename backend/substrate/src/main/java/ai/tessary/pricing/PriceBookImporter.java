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
 * Gets the rate file bundled in the jar into the database, on every boot.
 *
 * <p><b>Why an importer rather than a seed migration.</b> Rates change; a changeset is immutable and runs
 * once. Seeding rates in SQL would mean every refreshed snapshot needs a new migration, and a mistake in
 * one could only be corrected by another. Here a refresh is a reviewed diff to the vendored file, and the
 * deploy that carries it imports it — no schema change, and the same code path repairs a book that failed
 * to import on a previous boot.
 *
 * <p><b>Boot only.</b> The bundled file is inside the jar, so it cannot change while the process runs and there
 * is nothing for a timer to pick up. A newer book reaches a running install from home.tessary.ai instead
 * ({@link PriceBookFetcher}, on the telemetry heartbeat's tick).
 *
 * <p><b>Idempotent by content, not by bookkeeping.</b> A snapshot's version is a hash of its bytes
 * ({@link PriceSnapshot}), so re-importing the same file is a no-op that costs one indexed lookup, and a
 * changed file is a new version and therefore a new book rather than an edit to the old one. Rows already
 * priced keep pointing at the book they were priced under, which is the whole point of versioning them.
 *
 * <p><b>Dated by the build, not the boot.</b> The newest {@code published_at} is the book in force, and a book
 * fetched from home is dated by when home published it. Dating the bundled book by import time would let any
 * restart put an older bundled file back in force over a newer fetched one. The jar's build time is when its
 * file was last taken from {@code main}: a book home published after that came from a {@code main} at least as
 * new (or is the same bytes, which share a version), and one published before it is at least as old.
 *
 * <p><b>One book.</b> The vendored LiteLLM file is imported as {@code source='litellm'}. A
 * hand-maintained {@code source='manual'} file used to layer corrections over it; every row it carried is now
 * either reconciled upstream or a genuine gap this platform prices as unpriced rather than guessed.
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
     * On boot, and never fatally. A listener on {@code ApplicationReadyEvent} that throws fails the whole
     * context, and rates the substrate can already survive not having must not be able to stop the platform
     * starting — a model with no rate reads as unpriced, which every surface renders honestly.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importOnBoot() {
        try {
            importSnapshots();
        } catch (RuntimeException e) {
            log.warn("price book import failed on boot; models will read as unpriced until the next boot", e);
        }
    }

    /** Import the vendored rate file. Returns nothing: what happened is in the log, where an operator reads it. */
    public void importSnapshots() {
        if (!props.isEnabled()) return;
        importSnapshot(PriceBook.SOURCE_LITELLM, PriceSnapshot.LITELLM_RESOURCE);
    }

    /**
     * When the bundled file was taken from {@code main}: the jar's build time from {@code build-info}, or now
     * outside a packaged build (an IDE run), where there is no build to date it by.
     */
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
            // Fills a digest an earlier boot could not record, and dates the row by the build if an earlier
            // boot dated it later. See PriceBookRepository#reconcile.
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
