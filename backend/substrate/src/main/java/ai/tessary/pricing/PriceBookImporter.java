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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gets the checked-in rate files into the database, on every boot and once a day.
 *
 * <p><b>Why an importer rather than a seed migration.</b> Rates change; a changeset is immutable and runs
 * once. Seeding rates in SQL would mean every refreshed snapshot needs a new migration, and a mistake in
 * one could only be corrected by another. Here a refresh is a reviewed diff to the vendored file, and the
 * deploy that carries it imports it — no schema change, and the same code path repairs a book that failed
 * to import on a previous boot.
 *
 * <p><b>Idempotent by content, not by bookkeeping.</b> A snapshot's version is a hash of its bytes
 * ({@link PriceSnapshot}), so re-importing the same file is a no-op that costs one indexed lookup, and a
 * changed file is a new version and therefore a new book rather than an edit to the old one. Rows already
 * priced keep pointing at the book they were priced under, which is the whole point of versioning them.
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

    public PriceBookImporter(PriceBookRepository books, PricingProperties props, ObjectMapper mapper) {
        this.books = books;
        this.props = props;
        this.mapper = mapper;
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
            log.warn("price book import failed on boot; models will read as unpriced until the next tick", e);
        }
    }

    /**
     * Daily, so a long-lived instance picks up a snapshot it did not boot with. Deployments are the usual
     * path; this is the one that covers an instance that has been up longer than the release cadence.
     */
    @Scheduled(fixedDelayString = "${tessary.pricing.import-interval-ms:86400000}", initialDelay = 86_400_000)
    public void importDaily() {
        importSnapshots();
    }

    /** Import the vendored rate file. Returns nothing: what happened is in the log, where an operator reads it. */
    public void importSnapshots() {
        if (!props.isEnabled()) return;
        importSnapshot(PriceBook.SOURCE_LITELLM, PriceSnapshot.LITELLM_RESOURCE);
    }

    private void importSnapshot(String source, String resource) {
        Instant started = Instant.now();
        Optional<PriceSnapshot> parsed = PriceSnapshot.load(mapper, source, resource);
        if (parsed.isEmpty()) return;
        PriceSnapshot snapshot = parsed.get();

        if (books.hasBook(snapshot.version())) {
            log.debug(
                    "price book {} already imported, {} models unchanged",
                    snapshot.version(),
                    snapshot.models().size());
            return;
        }
        PriceBookRepository.Imported imported = books.importBook(snapshot, Instant.now());
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
                .field("models", snapshot.models().size())
                .field("newModels", imported.newModels())
                .field("rates", imported.rates())
                .durationMs(started)
                .log();
    }
}
