// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorRepository.RawFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** One refresh of a tool that has degraded, with every repository stubbed: what the finding's evidence says. */
class ToolErrorServiceTest {

    private static final String PROJECT = "proj-1";
    private static final String TOOL = "tool:search_docs";
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolErrorRepository repo = mock(ToolErrorRepository.class);
    private final ClassifierRepository classifiers = mock(ClassifierRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);

    /**
     * The bug: a failing call whose stored result body does not parse aborts the whole refresh, so a tool
     * that is visibly broken files no finding at all. The body is only one way to describe a failure; the
     * call is still counted, classified from its span status alone.
     */
    @Test
    void aFailureWhoseResultBodyDoesNotParseIsStillCountedFromItsSpanStatus() throws Exception {
        when(classifiers.listByProject(PROJECT)).thenReturn(List.of(toolError()));
        List<HourlyToolTally> tallies = new ArrayList<>();
        hours(tallies, 0, 20, 0.01); // 4,000 calls of reference at 1%
        hours(tallies, 20, 30, 0.05); // then 6,000 at 5%
        when(repo.newestEventAt(PROJECT)).thenReturn(Optional.of(START.plus(Duration.ofHours(50))));
        when(repo.hourlyTallies(eq(PROJECT), any())).thenReturn(tallies);
        when(repo.namesByToolKey(eq(PROJECT), any())).thenReturn(Map.of(TOOL, List.of("search_docs")));
        when(repo.failuresFor(eq(PROJECT), eq(List.of("search_docs")), any(), any(), anyInt()))
                .thenReturn(List.of(new RawFailure(null, true, null, null, "{", "trace-1")));
        ToolErrorService service = new ToolErrorService(
                repo,
                mock(ToolErrorReferenceRepository.class),
                mock(ToolErrorStateRepository.class),
                findings,
                mock(FindingEvidenceRepository.class),
                classifiers,
                mapper);

        assertEquals(1, service.refresh(PROJECT));

        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(findings)
                .recordRecomputedCause(
                        anyString(),
                        eq(PROJECT),
                        anyString(),
                        anyLong(),
                        any(),
                        any(),
                        evidence.capture(),
                        anyString(),
                        anyString(),
                        anyString());
        JsonNode payload = mapper.readTree(evidence.getValue());
        JsonNode pattern = payload.path("patterns").get(0);
        assertEquals(ToolFailure.UNDESCRIBED, pattern.path("signature").asText());
        assertEquals("span_status", pattern.path("source").asText());
        assertEquals(1, pattern.path("cur").asLong());
        assertEquals("trace-1", payload.path("failing_traces").get(0).asText());
    }

    private static void hours(List<HourlyToolTally> into, int fromHour, int hours, double rate) {
        for (int h = 0; h < hours; h++) {
            into.add(new HourlyToolTally(
                    START.plus(Duration.ofHours(fromHour + h)).toString(), TOOL, 200, Math.round(200 * rate)));
        }
    }

    private static ClassifierRow toolError() {
        return new ClassifierRow(
                "cls-1",
                PROJECT,
                "tool_error",
                "Tool errors",
                null,
                BuiltInDetector.Kind.TOOL_ERROR,
                "{}",
                true,
                8,
                true,
                ClassifierRow.Mode.TRACKING,
                "now",
                "now");
    }
}
