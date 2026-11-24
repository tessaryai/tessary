// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shape key and account cluster of spec 3.3, saved before the ID text is deleted. A shape that differs from the
 * reference puts IDs in the wrong group, and a cluster that forms too easily splits issue estimates by account.
 */
class ShapeSignatureTest {

    @Test
    void shapeSignature() {
        assertAll(
                () -> assertEquals(new ShapeSignature("usr", 10), ShapeSignature.of("usr_7Hq2kD")),
                () -> assertEquals("usr:10", ShapeSignature.of("usr_7Hq2kD").wire()),
                () -> assertEquals(new ShapeSignature("usr", 10), ShapeSignature.parse("usr:10")),
                () -> assertEquals(new ShapeSignature("", 5), ShapeSignature.parse(":5")),
                () -> assertEquals(new ShapeSignature("", 5), ShapeSignature.of("12345")),
                () -> assertEquals(new ShapeSignature("", 3), ShapeSignature.of("abc"), "no separator, no prefix"),
                () -> assertEquals(new ShapeSignature("", 5), ShapeSignature.of("ab1_x"), "letters only"),
                () -> assertEquals(new ShapeSignature("a", 3), ShapeSignature.of("a.b")),
                () -> assertEquals(new ShapeSignature("", 3), ShapeSignature.of("😀_x"), "length in code points"),
                () -> assertEquals("acme", ShapeSignature.clusterOf("acme:u1")),
                () -> assertEquals("org/team", ShapeSignature.clusterOf("org/team|u1")),
                () -> assertNull(ShapeSignature.clusterOf(":u1"), "an empty cluster is none"),
                () -> assertNull(ShapeSignature.clusterOf("usr_1")),
                () -> assertEquals(
                        Set.of("acme", "beta", "gamma"),
                        ShapeSignature.accountClusters(Map.of("acme", 5, "beta", 5, "gamma", 6, "solo", 1))),
                () -> assertEquals(
                        Set.of(),
                        ShapeSignature.accountClusters(Map.of("acme", 4, "beta", 5, "gamma", 5)),
                        "acme has 4 IDs, so only 2 clusters qualify and none counts"),
                () -> assertEquals(
                        Set.of("beta", "gamma", "delta"),
                        ShapeSignature.accountClusters(Map.of("acme", 4, "beta", 5, "gamma", 5, "delta", 5))),
                () -> assertEquals(
                        "c9a7e476bacbbf87e60c30ba2612595b",
                        HexFormat.of().formatHex(ShapeSignature.clusterHash(new byte[32], "acme"))),
                () -> assertEquals(
                        "c9a7e476bacbbf87e60c30ba2612595b",
                        HexFormat.of().formatHex(ShapeSignature.clusterHash(new byte[32], "acme ")),
                        "the cluster of \"acme :u1\" is the account of \"acme:u2\""));
    }
}
