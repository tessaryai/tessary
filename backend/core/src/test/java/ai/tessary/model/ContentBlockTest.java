// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContentBlockTest {

    /**
     * The bugs: the trace export labels a PDF block as an image (or the reverse), or treats a media block
     * as text and inlines its bytes. Each of the seven block types is exactly one of text, image or
     * document.
     */
    @ParameterizedTest
    @CsvSource({
        "text, false, false",
        "image_url, true, false",
        "image_b64, true, false",
        "image_ref, true, false",
        "document_b64, false, true",
        "document_ref, false, true",
        "document_url, false, true"
    })
    void eachTypeIsExactlyOneOfTextImageOrDocument(String type, boolean image, boolean document) {
        ContentBlock block = new ContentBlock(type, null, null, null, null);

        assertEquals(
                List.of(image, document, image || document),
                List.of(block.isImage(), block.isDocument(), block.isMedia()));
    }
}
