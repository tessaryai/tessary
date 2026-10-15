// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.media;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded best-effort PDF text extraction. Never throws: every PDFBox failure collapses to {@link Optional#empty()}. A
 * PDF is untrusted input, so pages, characters and wall-clock time are capped; tripping a cap truncates rather than
 * fails.
 */
public final class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /**
     * Persisted instead of an empty string so a reader can tell "extraction failed" from "extracted to nothing"; one
     * shared literal for every writer and reader.
     */
    public static final String DOCUMENT_TEXT_UNAVAILABLE_MARKER = "[document text unavailable]";

    private static final int MAX_PAGES = 200;

    /** Characters kept before extraction stops (a PDF-scale, not a judge-prompt-scale, cap — the
     *  judge boundary's own fencing/truncation happens downstream of this). */
    private static final int MAX_CHARS = 200_000;

    /** A pathological PDF must not stall an ingest batch. */
    private static final Duration MAX_DURATION = Duration.ofSeconds(20);

    private PdfTextExtractor() {}

    /** Empty on any failure, including a blank text layer or a cap tripping before any character was read. */
    public static Optional<String> extract(byte @Nullable [] pdfBytes) {
        return extract(pdfBytes, Clock.systemUTC());
    }

    static Optional<String> extract(byte @Nullable [] pdfBytes, Clock clock) {
        if (pdfBytes == null || pdfBytes.length == 0) return Optional.empty();
        Instant deadline = clock.instant().plus(MAX_DURATION);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.isEncrypted()) {
                log.info("pdf text extraction: encrypted document, no text layer available");
                return Optional.empty();
            }
            PDFTextStripper stripper = new BoundedTextStripper(clock, deadline);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), MAX_PAGES));
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) return Optional.empty();
            return Optional.of(text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
        } catch (DeadlineExceeded e) {
            log.info("pdf text extraction: exceeded {} wall-clock cap — returning partial/none", MAX_DURATION);
            return Optional.empty();
        } catch (IOException e) {
            log.info("pdf text extraction failed ({} bytes): {}", pdfBytes.length, e.toString());
            return Optional.empty();
        } catch (RuntimeException e) {
            // PDFBox also raises unchecked exceptions on malformed input.
            log.info("pdf text extraction failed unexpectedly ({} bytes): {}", pdfBytes.length, e.toString());
            return Optional.empty();
        }
    }

    /** Unwinds past PDFBox once the wall-clock cap trips; never escapes this class. */
    private static final class DeadlineExceeded extends RuntimeException {
        DeadlineExceeded() {
            super(null, null, false, false); // no message/cause/stack — pure control flow
        }
    }

    /** Checked per line written, the finest hook PDFTextStripper exposes. */
    private static final class BoundedTextStripper extends PDFTextStripper {
        private final Clock clock;
        private final Instant deadline;

        BoundedTextStripper(Clock clock, Instant deadline) throws IOException {
            this.clock = clock;
            this.deadline = deadline;
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            if (clock.instant().isAfter(deadline)) throw new DeadlineExceeded();
            super.writeLineSeparator();
        }
    }
}
