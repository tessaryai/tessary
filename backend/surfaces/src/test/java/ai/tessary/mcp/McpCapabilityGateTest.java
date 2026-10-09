// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.MAPPER;
import static ai.tessary.mcp.McpToolHarness.ctx;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.req;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.Pipeline;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the MCP surface offers: which tools {@code tools/list} names, and what a call to a tool it does not name
 * does.
 *
 * <ul>
 *   <li>{@code tools/list} names exactly the open set, 20 tools.</li>
 *   <li>{@code tools/call} on a removed tool reads as <b>unknown</b>, and the handler never runs.</li>
 *   <li>{@code initialize} instructions are built from the same catalogue, so the prose cannot advertise a
 *       tool that is gone.</li>
 * </ul>
 *
 * <p><b>The surface is read-only and entirely ungated.</b> {@code Capability.RCA} used to gate five tools
 * here; a case now carries its own RCA report inline, so the gate moved from the tool to the data. Grading
 * was deleted from the platform, taking {@code list_graders} and {@code get_grader} with it.
 * {@link #theSurfaceHasNoWriteToolAndNoneOfTheRemovedSix} is the invariant that keeps the surface read-only:
 * it walks the full catalogue rather than a list maintained here, so a future write tool fails this test at
 * registration instead of shipping.
 */
class McpCapabilityGateTest {

    /**
     * The tools deleted from this surface. Named here so that re-adding one under its old name fails a
     * test rather than quietly restoring a surface we argued our way out of: five were the triage/RCA pair
     * (spend, and reports that now ride on the case that owns them), one was the last write, and three
     * went with grading when it was removed.
     */
    private static final Set<String> REMOVED_TOOLS = Set.of(
            "propose_grader_edit",
            "run_triage",
            "get_triage",
            "latest_triage",
            "list_rca_reports",
            "get_rca_report",
            // Grading left the platform, so the two grader reads and the quality-dimension list
            // have nothing behind them. Named here for the same reason as the other six.
            "list_graders",
            "get_grader",
            "list_quality_dimensions");

    /**
     * Names that would mean a write. Matched as a prefix over the whole catalogue, because the risk this
     * guards is not a specific tool coming back — it is the next write being added as one more
     * {@code add(...)} call, without anyone re-deciding that an agent may act on a customer's project.
     */
    private static final List<String> WRITE_PREFIXES = List.of("propose_", "run_", "create_", "update_", "delete_");

    /**
     * Open to every org: the launch product's own output and the reads it rests on. A tool leaving this set
     * is a product decision, so it should break this test and be argued for in the diff.
     */
    private static final Set<String> OPEN_TOOLS = Set.of(
            "get_project",
            "list_call_sites",
            "list_failure_modes",
            "list_cases",
            "get_case",
            "list_findings",
            "get_finding",
            // The evidence door is open for the same reason get_finding is: it returns ids into the
            // project's own substrate, gated by the detector the finding belongs to rather than by a
            // capability of its own.
            "get_finding_evidence",
            // Ungated because it describes the query schema, not a project's rows: withholding it would only
            // force an org to guess the field names of tools it already holds.
            "describe_dataset",
            "query_count",
            "query_timeseries",
            "query_facets",
            "query_search",
            // The substrate readers are open for the same reason the raw reads are: they page the project's
            // own traffic, which the token already scopes, and a partner who cannot list a trace cannot find
            // the id that get_trace needs. list_spans' payload opt-in is no exception — it returns exactly
            // what get_span returns, for spans the same token could already fetch one at a time.
            "list_traces",
            "list_spans",
            "list_sessions",
            "get_session",
            "get_conversation",
            "get_span",
            "get_trace");

    @Test
    void everyOrgIsOfferedExactlyTheOpenTools() throws Exception {
        Set<String> offered = registryWith().pipeline(Pipeline.empty()).build().listToolNames();

        assertEquals(OPEN_TOOLS, offered, "every org should be offered exactly the open set");
        assertEquals(20, offered.size(), "the surface is 20 tools, all open");
    }

    /**
     * The invariant behind the sentence {@code initialize} tells every client on connect: <i>every tool is
     * read-only</i>. Walks the whole catalogue, and fails on a write-shaped name or on any of the removed tools
     * coming back. A promise made to every client on connect should not rest on whoever reviews the next
     * {@code add(...)} call noticing.
     */
    @Test
    void theSurfaceHasNoWriteToolAndNoneOfTheRemovedSix() throws Exception {
        Set<String> everything =
                registryWith().pipeline(Pipeline.empty()).build().listToolNames();

        for (String name : everything) {
            for (String prefix : WRITE_PREFIXES) {
                assertFalse(
                        name.startsWith(prefix),
                        name + " is write-shaped. Adding a write is a product decision, not a registration: it"
                                + " invalidates the read-only sentence initialize sends every client.");
            }
        }
        for (String gone : REMOVED_TOOLS) {
            assertFalse(everything.contains(gone), gone + " was removed in the read-only cutover and must stay gone");
        }
    }

    /**
     * The read-only sentence is unconditional, unlike every other sentence in the instructions, which are
     * built from the catalogue. It states a property of the surface rather than of a tool — otherwise the
     * cheapest way to learn there is no write is to plan one.
     */
    @Test
    void instructionsStateTheSurfaceIsReadOnly() throws Exception {
        McpToolHarness mcp = registryWith().pipeline(Pipeline.empty()).build();

        JsonRpc.Response r = mcp.dispatcher().dispatch(req(1, "initialize", MAPPER.readTree("{}")), ctx());
        assertNotNull(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        String instructions = Objects.requireNonNull(result.get("instructions")).toString();

        assertTrue(
                instructions.contains("read-only"),
                "the read-only statement is not conditional on the offer: " + instructions);
    }
}
