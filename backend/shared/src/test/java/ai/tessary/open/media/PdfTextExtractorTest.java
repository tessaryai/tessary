// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link PdfTextExtractor} against real, in-memory-generated PDFs (built with PDFBox itself, so this
 * test needs no checked-in binary fixture) plus the failure modes a hostile/malformed ingest payload
 * can produce.
 */
class PdfTextExtractorTest {

    @Test
    void encryptedPdf_returnsEmpty() throws Exception {
        byte[] pdf = encryptedOnePagePdf("secret contents");
        assertEquals(Optional.empty(), PdfTextExtractor.extract(pdf), "an encrypted PDF has no readable text layer");
    }

    @Test
    void corruptBytes_returnsEmpty_neverThrows() {
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        assertEquals(Optional.empty(), PdfTextExtractor.extract(garbage), "corrupt/truncated bytes must not throw");
    }

    @Test
    void truncatedPdfHeader_returnsEmpty_neverThrows() {
        // A real %PDF- header with nothing valid behind it — closer to a real truncated upload than
        // arbitrary garbage bytes, and still must not throw.
        byte[] truncated = "%PDF-1.7\n%âãÏÓ\n1 0 obj".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(Optional.empty(), PdfTextExtractor.extract(truncated));
    }

    @Test
    void nullOrEmptyBytes_returnsEmpty() {
        assertEquals(Optional.empty(), PdfTextExtractor.extract(null));
        assertEquals(Optional.empty(), PdfTextExtractor.extract(new byte[0]));
    }

    @Test
    void blankTextLayer_treatedAsEmpty() throws IOException {
        // A PDF page with no text drawn on it at all (e.g. a scanned image with no OCR text layer) —
        // PDFTextStripper extracts blank/whitespace, which this class treats as "no text", not "".
        byte[] pdf = blankPagePdf();
        assertEquals(Optional.empty(), PdfTextExtractor.extract(pdf), "a blank text layer must be treated as empty");
    }

    /**
     * The bug: an encrypted PDF anyone can open (owner password only) has its text lifted and handed to
     * the judge anyway. {@code isEncrypted()} is the gate; the user-password fixture above never reaches
     * it because PDFBox refuses to load that one at all.
     */
    @Test
    void encryptedPdfWithNoUserPassword_returnsEmpty() throws Exception {
        byte[] pdf = encryptedOnePagePdf("secret contents", "");
        assertEquals(Optional.empty(), PdfTextExtractor.extract(pdf), "an encrypted PDF is never extracted");
    }

    /**
     * The bug: the bounded stripper swallows the line break it checks the clock on, so consecutive lines
     * run together ("foxjumps") in the text the judge reads.
     */
    @Test
    void multiLinePdf_keepsItsLineBreaks() throws IOException {
        byte[] pdf = twoLinePdf("The quick brown fox", "jumps over the lazy dog");

        assertEquals(
                List.of("The quick brown fox", "jumps over the lazy dog"),
                PdfTextExtractor.extract(pdf).orElseThrow().lines().toList());
    }

    /**
     * The bug: a PDF that keeps PDFBox busy past the wall-clock cap stalls the ingest batch or the grading
     * call it arrived in. The clock reads past the cap by the first line break the stripper writes.
     */
    @Test
    void extractionPastTheWallClockCap_returnsEmpty() throws IOException {
        byte[] pdf = twoLinePdf("The quick brown fox", "jumps over the lazy dog");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = new SteppingClock(start, start.plusSeconds(21), null);

        assertEquals(Optional.empty(), PdfTextExtractor.extract(pdf, clock));
    }

    /**
     * The bug: an unchecked failure raised mid-extraction escapes this best-effort extractor and fails
     * the whole ingest batch over one document. The failure is injected from inside the text stripper.
     */
    @Test
    void anUncheckedFailureDuringExtraction_returnsEmpty() throws IOException {
        byte[] pdf = twoLinePdf("The quick brown fox", "jumps over the lazy dog");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = new SteppingClock(start, start, new IllegalStateException("font table overflow"));

        assertEquals(Optional.empty(), PdfTextExtractor.extract(pdf, clock));
    }

    /** Answers {@code first} once, then {@code after} (or throws {@code failure} when one is set). */
    private static final class SteppingClock extends Clock {
        private final Instant first;
        private final Instant after;
        private final @Nullable RuntimeException failure;
        private boolean started;

        SteppingClock(Instant first, Instant after, @Nullable RuntimeException failure) {
            this.first = first;
            this.after = after;
            this.failure = failure;
        }

        @Override
        public Instant instant() {
            if (!started) {
                started = true;
                return first;
            }
            if (failure != null) {
                throw failure;
            }
            return after;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Two text lines, so the stripper writes a line separator (where the wall-clock cap is checked). */
    private static byte[] twoLinePdf(String first, String second) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(first);
                cs.newLineAtOffset(0, -20);
                cs.showText(second);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] blankPagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] encryptedOnePagePdf(String content) throws Exception {
        return encryptedOnePagePdf(content, "user-pw");
    }

    private static byte[] encryptedOnePagePdf(String content, String userPassword) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(content);
                cs.endText();
            }
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner-pw", userPassword, ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            fail("failed to build the encrypted PDF fixture itself (test setup, not the extractor): " + e, e);
            throw e; // unreachable — fail() always throws
        }
    }
}
