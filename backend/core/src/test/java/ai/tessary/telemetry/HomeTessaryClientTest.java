// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The {@code x-amz-content-sha256} value home's edge checks: lowercase hex SHA-256 of the exact bytes
 * posted. A wrong encoding (uppercase, base64) is refused 403 as surely as a missing header.
 */
class HomeTessaryClientTest {

    @Test
    void sha256HexOfEmptyBody() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HomeTessaryClient.sha256Hex(new byte[0]));
    }

    @Test
    void sha256HexHashesUtf8BytesNotChars() {
        assertEquals(
                "edf6128bc98e616a5fb48c47c3fb2deddd40ca6231aaa7c616a84ee034cbec8c",
                HomeTessaryClient.sha256Hex("{\"os\":\"lin\u00fcx\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
