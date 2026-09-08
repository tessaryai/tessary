// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * {@link PdfTextExtractor} against real, in-memory-generated PDFs (built with PDFBox itself, so this
 * test needs no checked-in binary fixture) plus the failure modes a hostile/malformed ingest payload
 * can produce.
 */
class PdfTextExtractorTest {

    @Test
    void normalPdf_extractsRealText() throws IOException {
        byte[] pdf = onePagePdf("The quick brown fox jumps over the lazy dog");
        Optional<String> text = PdfTextExtractor.extract(pdf);
        assertTrue(text.isPresent(), "a normal text-layer PDF must extract");
        assertTrue(text.get().contains("The quick brown fox"), "extracted text must contain the page's content");
    }

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

    private static byte[] onePagePdf(String content) throws IOException {
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
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner-pw", "user-pw", ap);
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
