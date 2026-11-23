// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which RCA causes the fix step may act on, and the key that follows a cause from one report to the next. The bugs: a
 * medium-confidence guess turned into a pull request, a traffic or model cause handed to an agent that can only edit
 * code, a cause with no file, and a key that changes when the RCA rewords a cause, which would re-fix it on every
 * rerun and bring a declined cause back.
 */
class FixableCauseTest {

    private static Cause cause(String title, String confidence, String type, @Nullable String path) {
        return new Cause(
                title,
                confidence,
                "standing",
                type,
                null,
                null,
                null,
                path == null ? null : new Attribution(null, path, null, null),
                List.of(),
                List.of(),
                0,
                null);
    }

    @Test
    void onlyHighConfidenceCausesOfAFixableTypeWithAFileAreFixable() {
        Cause high = cause("Search is never retried", Cause.HIGH, "code", "src/agent.py");
        List<Cause> causes = List.of(
                high,
                cause("Maybe the prompt", Cause.MEDIUM, "prompt", "prompts/a.md"),
                cause("A model change", Cause.HIGH, "model", "config/models.yaml"),
                cause("No file", Cause.HIGH, "tool", null),
                cause("Blank file", Cause.HIGH, "tool", "  "));

        assertEquals(List.of(new FixableCause("code:src/agent.py", "src/agent.py", high)), FixableCause.of(causes));
    }

    @Test
    void theKeyIsTheTypeAndFileSoARewordedCauseKeepsItsKey() {
        List<FixableCause> first =
                FixableCause.of(List.of(cause("Search is never retried", Cause.HIGH, "Code", "src/agent.py")));
        List<FixableCause> second = FixableCause.of(
                List.of(cause("The search tool has no retry", Cause.HIGH, "code", "./src/agent.py:42")));

        assertEquals(FixableCause.keys(first), FixableCause.keys(second));
    }

    @Test
    void twoCausesOnOneFileOfOneTypeAreOneFix() {
        Cause a = cause("Search is never retried", Cause.HIGH, "code", "src/agent.py");
        Cause b = cause("Search times out too soon", Cause.HIGH, "code", "src/agent.py#L10-L12");

        assertEquals(List.of("code:src/agent.py"), FixableCause.keys(FixableCause.of(List.of(a, b))));
    }

    @ParameterizedTest
    @CsvSource({
        "src/agent.py, src/agent.py",
        "./src/agent.py, src/agent.py",
        "/src/agent.py, src/agent.py",
        "src/agent.py:42, src/agent.py",
        "src/agent.py:42-50, src/agent.py",
        "src/agent.py#L42, src/agent.py",
        "src/agent.py#L42-L50, src/agent.py",
        "' src/agent.py ', src/agent.py"
    })
    void aLineOrALeadingSlashDoesNotMakeANewFile(String raw, String file) {
        assertEquals(file, FixableCause.normalize(raw));
    }
}
