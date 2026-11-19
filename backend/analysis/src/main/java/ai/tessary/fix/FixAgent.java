// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.JsonAnswer;
import ai.tessary.agentrun.SessionEvent;
import ai.tessary.agentrun.StepWords;
import ai.tessary.config.FixProperties;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.prompt.PromptCraft;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * One fix session on the {@code fix} lane: the causes and the report as dossier files, the repository cloned at the
 * start point, no MCP server. Its cost is booked on the fix run. Every failure throws, as the session runner does.
 */
@Service
public class FixAgent {

    private static final String FIX = "fix";

    static final String SYSTEM_PROMPT = PromptCraft.text(FIX, "system_prompt.md");
    static final String PROMPT = PromptCraft.text(FIX, "prompt.md");
    static final String START_DEFAULT =
            PromptCraft.text(FIX, "start_default.md").stripTrailing();
    static final String START_BRANCH = PromptCraft.text(FIX, "start_branch.md").stripTrailing();
    static final String JSON_SCHEMA = PromptCraft.text(FIX, "response_schema.json");

    /** The ledger subject a fix session's spend is booked on. */
    static final String SUBJECT_KIND = "fix_run";

    /** The turns the agent keeps for writing its answer once its tool calls run out. */
    static final int ANSWER_TURNS = 2;

    private final FixProperties props;
    private final AgentSessionRunner runner;
    private final ObjectMapper mapper;

    public FixAgent(FixProperties props, AgentSessionRunner runner, ObjectMapper mapper) {
        this.props = props;
        this.runner = runner;
        this.mapper = mapper;
    }

    /**
     * @param branch where the clone starts: the default branch, or the open pull request's branch when
     * {@code addressed} is not empty @param addressed the keys the open pull request's commits already fix
     */
    public record Request(
            String projectId,
            String runId,
            String caseReference,
            @Nullable String reportSummary,
            AgentSessionClient.Clone repo,
            String branch,
            List<FixableCause> causes,
            List<String> addressed) {}

    /** @param patch what the agent changed; null when its answering reply sent none */
    public record Result(FixAnswer answer, SessionEvent.@Nullable Patch patch) {}

    /** The agent's answer; keys and checks are as it gave them. */
    public record FixAnswer(
            boolean fixed,
            String title,
            String why,
            List<String> causesAddressed,
            List<FixCheck> checks,
            @Nullable String noFixReason) {}

    public Result run(Request req, AgentSessionRunner.StepSink steps) {
        AgentSessionRunner.Answer answer = runner.run(
                new AgentSessionRunner.Task(
                        req.projectId(),
                        ModelLane.FIX,
                        new LlmUsageAccountant.Subject(SUBJECT_KIND, req.runId()),
                        SYSTEM_PROMPT,
                        Map.of("causes.md", causesFile(req.causes()), "report.md", reportFile(req)),
                        List.of(),
                        req.repo(),
                        prompt(req, timeBudgetMinutes(props.timeoutMs())),
                        JSON_SCHEMA,
                        Duration.ofMillis(props.timeoutMs()),
                        Math.max(1, props.maxTurns() - ANSWER_TURNS),
                        new StepWords.Context(Map.of(), Map.of())),
                steps);
        // The runner returns only an answer that parsed and carries every required key.
        JsonNode json = JsonAnswer.extract(mapper, answer.json()).orElseThrow();
        return new Result(parse(json), answer.patch());
    }

    static FixAnswer parse(JsonNode json) {
        List<String> addressed = new ArrayList<>();
        for (JsonNode key : json.path("causes_addressed")) addressed.add(key.asText());
        List<FixCheck> checks = new ArrayList<>();
        for (JsonNode c : json.path("checks")) {
            JsonNode note = c.path("note");
            checks.add(new FixCheck(
                    c.path("command").asText(), c.path("result").asText(), note.isTextual() ? note.textValue() : null));
        }
        JsonNode reason = json.path("no_fix_reason");
        return new FixAnswer(
                "fixed".equals(json.path("outcome").asText()),
                json.path("title").asText().strip(),
                json.path("why").asText().strip(),
                List.copyOf(addressed),
                List.copyOf(checks),
                reason.isTextual() ? reason.textValue() : null);
    }

    /** Boot and clone spend part of the timeout, so the agent is told four fifths of it. */
    static long timeBudgetMinutes(long timeoutMs) {
        return Math.max(1, timeoutMs * 4 / 5 / 60_000);
    }

    static String prompt(Request req, long timeBudgetMinutes) {
        String start = req.addressed().isEmpty()
                ? START_DEFAULT.replace("{branch}", req.branch())
                : START_BRANCH.replace("{branch}", req.branch()).replace("{addressed}", ticked(req.addressed()));
        return PROMPT.replace("{case_reference}", req.caseReference())
                .replace("{cause_keys}", ticked(FixableCause.keys(req.causes())))
                .replace("{time_budget_minutes}", Long.toString(timeBudgetMinutes))
                .replace("{start}", start);
    }

    private static String ticked(List<String> keys) {
        return keys.stream().map(k -> "`" + k + "`").collect(Collectors.joining(", "));
    }

    /** Data, not instructions: each cause as the RCA wrote it. */
    static String causesFile(List<FixableCause> causes) {
        StringBuilder out = new StringBuilder("# Causes to fix\n");
        for (FixableCause fc : causes) {
            Cause c = fc.cause();
            out.append("\n## `").append(fc.key()).append("`\n\n");
            out.append("- title: ").append(c.title()).append('\n');
            out.append("- file: `").append(fc.path()).append("`\n");
            line(out, "what happens", c.whatChanged());
            line(out, "how it caused the failures", c.howItCausedThis());
            line(out, "what to fix", c.nextStep());
            String excerpt = c.attribution() == null ? null : c.attribution().excerpt();
            if (excerpt != null && !excerpt.isBlank()) {
                out.append("- excerpt:\n\n```\n").append(excerpt.strip()).append("\n```\n");
            }
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String label, @Nullable String value) {
        if (value != null && !value.isBlank())
            out.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    static String reportFile(Request req) {
        String summary = req.reportSummary();
        return "# RCA report for case " + req.caseReference() + "\n\n"
                + (summary == null || summary.isBlank() ? "The report has no summary." : summary.strip()) + "\n";
    }
}
