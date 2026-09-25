// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.version.CommitLineageService.NodeKind;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommitLineageServiceTest {

    static Stream<Arguments> pathTokens() {
        return Stream.of(
                Arguments.of("a blank token is unknown, not a 500", "  ", Optional.empty()),
                Arguments.of("a removed kind answers 400, not 500", "verdict", Optional.empty()),
                Arguments.of("case and padding do not matter", " Trace ", Optional.of(NodeKind.TRACE)),
                Arguments.of("the legacy turn spelling still resolves", "turn", Optional.of(NodeKind.TURN)));
    }

    /** The bugs, one per row: the path token of {@code /versions/lineage/{nodeKind}} is misread. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pathTokens")
    void parseReadsTheLineagePathToken(String bug, String raw, Optional<NodeKind> expected) {
        assertEquals(expected, NodeKind.parse(raw), bug);
    }
}
