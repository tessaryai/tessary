// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.config.AlertProperties;
import ai.tessary.fix.FixAgent.FixAnswer;
import ai.tessary.fix.FixDestination.Change;
import ai.tessary.fix.FixDestination.Delivery;
import ai.tessary.fix.FixDestination.Start;
import ai.tessary.fix.FixRunRepository.Finish;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.fix.FixRunRow.Outcome;
import ai.tessary.fix.FixRunRow.Reason;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * One fix run, from its report to a commit: where to start, the agent, the checks it reported, the patch, and the
 * delivery. A run the agent could not fix, or whose change failed a check, ends {@code no_fix} with its reason. When
 * the branch moved onto commits that touch the change, the agent runs once more on the new head; a second move throws
 * {@code GIT.BRANCH_MOVED}. Failures throw, for the worker to record.
 */
@Service
public class FixStep {

    private final FixRunRepository fixRuns;
    private final RcaReportRepository reports;
    private final CaseRepository cases;
    private final CaseEventRepository events;
    private final ProjectRepository projects;
    private final OrganizationRepository orgs;
    private final AlertProperties links;
    private final FixDestination destination;
    private final FixAgent agent;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public FixStep(
            FixRunRepository fixRuns,
            RcaReportRepository reports,
            CaseRepository cases,
            CaseEventRepository events,
            ProjectRepository projects,
            OrganizationRepository orgs,
            AlertProperties links,
            FixDestination destination,
            FixAgent agent,
            ObjectMapper mapper) {
        this(fixRuns, reports, cases, events, projects, orgs, links, destination, agent, mapper, Clock.systemUTC());
    }

    FixStep(
            FixRunRepository fixRuns,
            RcaReportRepository reports,
            CaseRepository cases,
            CaseEventRepository events,
            ProjectRepository projects,
            OrganizationRepository orgs,
            AlertProperties links,
            FixDestination destination,
            FixAgent agent,
            ObjectMapper mapper,
            Clock clock) {
        this.fixRuns = fixRuns;
        this.reports = reports;
        this.cases = cases;
        this.events = events;
        this.projects = projects;
        this.orgs = orgs;
        this.links = links;
        this.destination = destination;
        this.agent = agent;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void run(FixRunRow run, AgentSessionRunner.StepSink steps) {
        String projectId = run.projectId();
        RcaReportRow report = reports.findById(projectId, run.rcaReportId()).orElseThrow();
        CaseRow caseRow = cases.findById(projectId, run.caseId()).orElseThrow();
        List<FixableCause> causes =
                FixableCause.of(RcaDtos.causesOf(mapper, report.reportKind(), report.causes(), report.hypotheses()));
        Start start = destination.start(projectId, caseRow.id(), causes);
        if (start instanceof Start.Stop stop) {
            fixRuns.finish(
                    run.id(),
                    new Finish(stop.outcome(), stop.reason(), null, stop.fixPrId(), List.of(), 0),
                    clock.instant());
            return;
        }
        Start.Go go = (Start.Go) start;
        fixRuns.recordCauses(run.id(), FixableCause.keys(go.causes()), clock.instant());
        Delivery delivery = attempt(run, report, caseRow, go, steps);
        if (delivery instanceof Delivery.Moved) delivery = attempt(run, report, caseRow, go, steps);
        if (delivery instanceof Delivery.Moved) {
            throw new TessaryException(GitError.BRANCH_MOVED, go.branch(), "the repository");
        }
    }

    /** Null when the run finished without a delivery. */
    private @Nullable Delivery attempt(
            FixRunRow run, RcaReportRow report, CaseRow caseRow, Start.Go go, AgentSessionRunner.StepSink steps) {
        FixPrRow open = go.openPr();
        List<String> earlier = open == null ? List.of() : open.causes();
        FixAgent.Result result = agent.run(
                new FixAgent.Request(
                        run.projectId(),
                        run.id(),
                        caseRow.reference(),
                        report.summary(),
                        go.repo(),
                        go.branch(),
                        go.causes(),
                        earlier),
                steps);
        FixAnswer answer = result.answer();
        if (!answer.fixed()) {
            noFix(run, Reason.AGENT_DECLINED, answer.noFixReason(), answer.checks());
            return null;
        }
        FixCheck failed = answer.checks().stream()
                .filter(c -> FixCheck.FAILED.equals(c.result()))
                .findFirst()
                .orElse(null);
        if (failed != null) {
            noFix(run, Reason.CHECK_FAILED, failed.command(), answer.checks());
            return null;
        }
        FixPatch.Verdict patch = FixPatch.check(result.patch());
        if (patch.reason() != null) {
            noFix(run, patch.reason(), answer.why(), answer.checks());
            return null;
        }
        List<FixableCause> addressed = go.causes().stream()
                .filter(c -> answer.causesAddressed().contains(c.key()))
                .toList();
        // The agent changed files for this run's causes even when it named none of their keys back.
        if (addressed.isEmpty()) addressed = go.causes();
        FixPrText.Facts facts = new FixPrText.Facts(
                caseRow.reference(),
                caseRow.title(),
                caseUrl(run.projectId(), caseRow.id()),
                report.summary(),
                addressed,
                earlier,
                answer);
        Delivery delivery = destination.deliver(
                go,
                new Change(
                        patch.files(),
                        Objects.requireNonNull(result.patch()).baseSha(),
                        FixableCause.keys(addressed),
                        FixPrText.title(facts),
                        FixPrText.body(facts),
                        FixPrText.commitMessage(facts)));
        if (delivery instanceof Delivery.Delivered done) {
            fixRuns.finish(
                    run.id(),
                    new Finish(
                            done.outcome(),
                            null,
                            answer.why(),
                            done.fixPrId(),
                            answer.checks(),
                            patch.files().size()),
                    clock.instant());
            appendEvent(run, done, addressed.size());
        }
        return delivery;
    }

    private void noFix(FixRunRow run, String reason, @Nullable String note, List<FixCheck> checks) {
        fixRuns.finish(run.id(), new Finish(Outcome.NO_FIX, reason, note, null, checks, 0), clock.instant());
    }

    private void appendEvent(FixRunRow run, Delivery.Delivered done, int causes) {
        String noun = causes == 1 ? "cause" : "causes";
        boolean opened = Outcome.PR_OPENED.equals(done.outcome());
        String summary = opened
                ? "Opened pull request #" + done.prNumber() + " to fix " + causes + " " + noun + "."
                : "Added a commit to pull request #" + done.prNumber() + " for " + causes + " new " + noun + ".";
        events.append(
                run.projectId(),
                run.caseId(),
                opened ? CaseEventRow.Kind.FIX_PR_OPENED : CaseEventRow.Kind.FIX_PR_UPDATED,
                null,
                summary,
                null,
                clock.instant());
    }

    /** Null rather than a guess: a pull request with no link is better than one with a broken link. */
    private @Nullable String caseUrl(String projectId, String caseId) {
        String base = links.appBaseUrl();
        if (base.isBlank()) return null;
        Project project = projects.findById(projectId).orElseThrow();
        String org = orgs.findById(project.orgId()).orElseThrow().slug();
        return base.replaceAll("/+$", "") + "/orgs/" + encode(org) + "/projects/" + encode(project.slug()) + "/cases/"
                + encode(caseId);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
