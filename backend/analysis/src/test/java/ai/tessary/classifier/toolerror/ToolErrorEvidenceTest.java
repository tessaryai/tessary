// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.toolerror.ToolErrorEvidence.PatternShift;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.Read;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The readers of a tool-error finding's evidence blob, on a hand-written blob in tool-error.md §7's shape: the
 * detail surface draws the pattern breakdown from it, and a blob the readers cannot parse must read as
 * unavailable rather than 500 the finding page or show zeroed numbers as the finding's own.
 */
class ToolErrorEvidenceTest {

    private static final String BLOB = """
            {"measure":"tool_error_rate","bucket":{"kind":"tool","key":"tool:search_docs"},
             "direction":"up","statistic":7.25,"threshold":5.5,"criticality":42.0,"effect_size":0.31,
             "delta_pp":12.5,"counts_basis":"onset","rate":{"ref":0.02,"cur":0.145},
             "n_ref":900,"n_cur":200,"failures":{"cur":29},
             "patterns":[{"signature":"timeout","source":"status","ref":3,"cur":21},
                         {"signature":"404","source":"http","ref":15,"cur":8}],
             "patterns_truncated":true,
             "failing_traces":["t-1","","t-2"],
             "onset_at":"2026-06-01T10:00:00Z",
             "window":{"opened_at":"2026-06-01T00:00:00Z","closed_at":"2026-06-02T00:00:00Z","kind":"recomputed"}}
            """;

    /** Each failure signature comes back with its own counts, in written order: the chart of which failure took over. */
    @Test
    void detail_readsEveryPatternShiftAndTheWindow() {
        assertEquals(
                new RateDetail(
                        "tool:search_docs",
                        0.02,
                        0.145,
                        12.5,
                        900,
                        200,
                        29,
                        List.of(new PatternShift("timeout", "status", 3, 21), new PatternShift("404", "http", 15, 8)),
                        true,
                        List.of("t-1", "t-2"),
                        "2026-06-01T10:00:00Z",
                        "2026-06-01T00:00:00Z",
                        "2026-06-02T00:00:00Z",
                        "up",
                        7.25,
                        5.5,
                        0.31,
                        42.0),
                ToolErrorEvidence.detail(BLOB));
    }

    /** The headline counts the case and absorption read: the pattern count, and whether the counts span the onset run. */
    @Test
    void read_takesTheHeadlineAndTheCountsBasis() {
        assertEquals(
                new Read("tool:search_docs", 0.02, 0.145, 12.5, 2, 200, 29, true, 42.0), ToolErrorEvidence.read(BLOB));
        Read legacy = ToolErrorEvidence.read("{\"bucket\":{\"key\":\"tool:x\"},\"n_cur\":5}");
        assertEquals(
                new Read("tool:x", 0, 0, 0, 0, 5, 0, false, 0),
                legacy,
                "a blob with no counts_basis predates the onset rework, so absorption must not pin from it");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "{not json", "{\"bucket\":{}}"})
    void anUnreadableOrKeylessBlobIsUnavailable(@Nullable String blob) {
        assertNull(ToolErrorEvidence.read(blob));
        assertNull(ToolErrorEvidence.detail(blob));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "{not json", "{\"bucket\":{\"key\":\"tool:x\"}}"})
    void failingTraces_isEmptyWhenTheBlobIsUnreadableOrPredatesTheField(@Nullable String blob) {
        assertEquals(List.of(), ToolErrorEvidence.failingTraces(blob));
    }
}
