// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.cases.CaseOpener;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.crypto.SecretBox;
import ai.tessary.fix.FakeFixSessions.Reply;
import ai.tessary.fix.FakeGithub.Call;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.git.github.GithubClients;
import ai.tessary.git.github.GithubTokenService;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.rca.AgenticRcaEngine;
import ai.tessary.rca.RcaAutoTrigger;
import ai.tessary.rca.RcaDtos;
import ai.tessary.rca.RcaJobRepository;
import ai.tessary.rca.RcaJobRow;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.rca.RcaWorker;
import ai.tessary.rca.RcaWorkerRuns;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * One case from its first positive window to its second pull request, through the real triggers, workers, repositories
 * and GitHub client against real Postgres. Three edges are fakes: the RCA agent, the fix agent's sandbox session (its
 * answer and patch) and GitHub behind its REST paths. The path: automatic RCA on the first window, the fix gates, a pull
 * request opened from the default branch; a second window that adds a commit to that pull request on top of a person's
 * commit; and, after a person merges it, a third window that opens a new one.
 *
 * <p>Its own context, since its fakes replace the session client and the GitHub transport for every bean. The fix
 * destination's clock is the test's, so the branch name, which carries the second it was made, is known.
 */
@SpringBootTest
@TestPropertySource(properties = {"tessary.rca.batch-size=0", "tessary.rca.heartbeat-ms=3600000"})
@MockitoBean(types = AgenticRcaEngine.class)
@Import(FixPrEndToEndTest.Fakes.class)
record FixPrEndToEndTest(
        TenantService tenants,
        SubstrateV2Fixtures fx,
        FindingRepository findings,
        FindingEvidenceRepository evidence,
        CaseOpener caseOpener,
        CaseService caseService,
        CaseEventRepository caseEvents,
        RcaAutoTrigger autoRca,
        RcaJobRepository rcaJobs,
        RcaReportRepository reports,
        RcaWorker rcaWorker,
        AgenticRcaEngine rcaAgent,
        FixRunRepository fixRuns,
        FixPrRepository fixPrs,
        FixWorker fixWorker,
        GitIntegrationRepository integrations,
        ProviderCredentialRepository credentials,
        SecretBox box,
        FakeGithub github,
        FakeFixSessions sessions,
        TestClock fixClock,
        JdbcClient jdbc) {

    private static final String CAUSE_KEY = "search-gives-up";
    private static final String SEARCH = "src/agent/search.py";
    private static final String PROMPT = "agent/system.md";
    private static final String SEARCH_KEY = "code:" + SEARCH;
    private static final String PROMPT_KEY = "prompt:" + PROMPT;
    private static final Instant FIRST_PR_AT = Instant.parse("2026-11-19T09:00:00Z");
    private static final Instant SECOND_PR_AT = Instant.parse("2026-11-21T09:00:00Z");

    @TestConfiguration
    static class Fakes {

        @Bean
        FakeGithub fakeGithub() {
            return new FakeGithub();
        }

        @Bean
        @Primary
        FakeFixSessions fakeFixSessions(FakeGithub github) {
            return new FakeFixSessions(github);
        }

        @Bean
        @Primary
        GitProviderFactory fakeGithubProviders(GithubTokenService tokens, ObjectMapper mapper, FakeGithub github) {
            return new GitProviderFactory(List.of(GithubClients.over(tokens, mapper, github)), List.of(tokens));
        }

        @Bean
        TestClock fixClock() {
            return new TestClock(FIRST_PR_AT);
        }

        @Bean
        @Primary
        FixDestination clockedPrDestination(
                GitIntegrationRepository integrations,
                GitIntegrationService integrationService,
                GitProviderFactory providers,
                FixPrRepository prs,
                TestClock fixClock) {
            return new GithubPrDestination(integrations, integrationService, providers, prs, fixClock);
        }
    }

    static final class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * The bugs this walks past: a window that never reaches automatic RCA, a finished RCA that never reaches the fix
     * step, a fix PR opened as a draft or off another base, a rerun that opens a second PR or force-pushes over a
     * person's commit instead of adding one on top, a rerun that redoes a cause the PR already fixes, and a merged PR
     * that blocks the next fix or reuses its branch.
     */
    @Test
    void aCaseGetsAPullRequestThenACommitOnItThenANewOneAfterTheMerge() {
        // The queue is global: a run another test left queued would be claimed first.
        jdbc.sql("UPDATE fix_run SET status = 'failed' WHERE status IN ('queued', 'running')")
                .update();
        var tenant = TenantFixture.bootstrap(tenants, "fix-e2e");
        String pid = tenant.project().id();
        connectRepository(pid);
        credentials.insert(anthropicKey(tenant.org().id()));
        String root = github.head(FakeGithub.DEFAULT_BRANCH);

        // ---- 1. A positive window opens the case; automatic RCA runs; the fix step opens a pull request.
        Window first = window(pid);
        String caseId = first.caseId();

        RcaJobRow firstRca = automaticRcaJob(pid);
        assertEquals(tenant.user().id(), firstRca.createdBy(), "an automatic RCA acts as the org's owner");
        CaseEventRow requested = only(caseId, pid, CaseEventRow.Kind.RCA_REQUESTED);
        assertNull(requested.actor(), "no person asked for it");

        finishRca(firstRca, cause(SEARCH, "code", first.traceId()));
        FixRunRow queued = fixRuns.latestByCase(pid, caseId).orElseThrow();
        assertEquals("queued", queued.status(), "every gate is open, so the run waits for the worker");
        assertEquals(List.of(SEARCH_KEY), queued.causes());

        fixClock.set(FIRST_PR_AT);
        sessions.willReply(new Reply(fixed("Retry the search once", SEARCH_KEY), Map.of(SEARCH, "retry = 1\n")));
        FixRunRow opened = runFix(pid);

        assertEquals("done", opened.status());
        assertEquals("pr_opened", opened.outcome(), String.valueOf(opened.reason()));
        assertEquals(1, opened.patchFiles());
        AgentSessionClient.Start firstSession = sessions.starts().get(0);
        assertEquals(ModelLane.FIX, firstSession.lane());
        AgentSessionClient.Clone firstClone = Objects.requireNonNull(firstSession.repo());
        assertEquals(root, firstClone.headSha(), "a first fix starts from the default branch's head");
        assertNull(firstClone.branch());

        String firstBranch = "tessary/fix-" + caseId.toLowerCase(Locale.ROOT) + "-" + FIRST_PR_AT.getEpochSecond();
        assertEquals(
                List.of("POST /git/blobs", "POST /git/trees", "POST /git/commits", "POST /git/refs", "POST /pulls"),
                names(github.writes()));
        String firstCommit = github.head(firstBranch);
        assertEquals(List.of(root), github.commit(firstCommit).parents());
        assertEquals("retry = 1\n", github.blobText(SEARCH, firstCommit));
        Call pull = github.writes("POST", "/pulls").get(0);
        assertEquals(firstBranch, pull.body().path("head").asText());
        assertEquals(FakeGithub.DEFAULT_BRANCH, pull.body().path("base").asText());
        assertEquals(false, pull.body().path("draft").asBoolean(true), "ready for review, never a draft");

        FixPrRow pr1 = fixPrs.listByCase(pid, caseId).get(0);
        assertEquals(1, pr1.prNumber());
        assertEquals("open", pr1.state());
        assertEquals(firstBranch, pr1.branch());
        assertEquals(firstCommit, pr1.headSha());
        assertEquals(List.of(SEARCH_KEY), pr1.causes());
        assertEquals(
                "Opened pull request #1 to fix 1 cause.",
                only(caseId, pid, CaseEventRow.Kind.FIX_PR_OPENED).summary());
        CaseDetailView afterFirst = caseService.detail(pid, caseId);
        FixDtos.FixPrView shown = Objects.requireNonNull(afterFirst.fix()).pr();
        assertEquals(
                "https://github.com/acme/agent/pull/1",
                Objects.requireNonNull(shown).url());
        assertEquals(0, afterFirst.caseView().windowsSinceRca());

        // ---- 2. A person pushes to the PR branch; a second window joins; the rerun adds a commit on top.
        String human = github.push(firstBranch, "README.md", "Note the retry in the readme");
        Window second = window(pid);
        assertEquals(caseId, second.caseId(), "the case stays live after its RCA");
        assertEquals(1, caseService.detail(pid, caseId).caseView().windowsSinceRca());

        autoRca.tick();
        assertEquals(List.of(), claim(pid), "the case had an RCA under six hours ago");
        ranHoursAgo(pid, 7);
        RcaJobRow secondRca = automaticRcaJob(pid);
        finishRca(secondRca, cause(SEARCH, "code", second.traceId()), cause(PROMPT, "prompt", second.traceId()));
        assertEquals(0, caseService.detail(pid, caseId).caseView().windowsSinceRca());

        int writesBefore = github.writes().size();
        sessions.willReply(new Reply(fixed("Tell the agent to retry", PROMPT_KEY), Map.of(PROMPT, "Retry once.\n")));
        FixRunRow added = runFix(pid);

        assertEquals("commit_added", added.outcome(), String.valueOf(added.reason()));
        assertEquals(List.of(PROMPT_KEY), added.causes(), "the cause the PR already fixes is not redone");
        AgentSessionClient.Clone secondClone =
                Objects.requireNonNull(sessions.starts().get(1).repo());
        assertEquals(firstBranch, secondClone.branch(), "a rerun starts from the open PR's branch");
        assertNull(secondClone.headSha());
        assertEquals(
                List.of(
                        "POST /git/blobs",
                        "POST /git/trees",
                        "POST /git/commits",
                        "PATCH /git/refs/heads/" + firstBranch,
                        "PATCH /pulls/1"),
                names(github.writes().subList(writesBefore, github.writes().size())));
        String secondCommit = github.head(firstBranch);
        assertEquals(List.of(human), github.commit(secondCommit).parents(), "built on the person's commit");
        assertEquals(
                false,
                github.writes("PATCH", "/git/refs/heads/" + firstBranch)
                        .get(0)
                        .body()
                        .path("force")
                        .asBoolean(true));
        FixPrRow pr1Updated = fixPrs.listByCase(pid, caseId).get(0);
        assertEquals(secondCommit, pr1Updated.headSha());
        assertEquals(List.of(SEARCH_KEY, PROMPT_KEY), pr1Updated.causes());
        assertEquals(
                "Added a commit to pull request #1 for 1 new cause.",
                only(caseId, pid, CaseEventRow.Kind.FIX_PR_UPDATED).summary());

        // ---- 3. A person merges the PR; the failure comes back; the next fix opens a new PR from the new head.
        String merged = github.merge(1);
        Window third = window(pid);
        assertEquals(caseId, third.caseId());
        ranHoursAgo(pid, 7);
        finishRca(automaticRcaJob(pid), cause(SEARCH, "code", third.traceId()));

        fixClock.set(SECOND_PR_AT);
        writesBefore = github.writes().size();
        sessions.willReply(new Reply(fixed("Retry the search twice", SEARCH_KEY), Map.of(SEARCH, "retry = 2\n")));
        FixRunRow reopened = runFix(pid);

        assertEquals("pr_opened", reopened.outcome(), String.valueOf(reopened.reason()));
        assertEquals(
                merged, Objects.requireNonNull(sessions.starts().get(2).repo()).headSha());
        String secondBranch = "tessary/fix-" + caseId.toLowerCase(Locale.ROOT) + "-" + SECOND_PR_AT.getEpochSecond();
        assertEquals(
                List.of("POST /git/blobs", "POST /git/trees", "POST /git/commits", "POST /git/refs", "POST /pulls"),
                names(github.writes().subList(writesBefore, github.writes().size())));
        assertEquals(List.of(merged), github.commit(github.head(secondBranch)).parents());
        assertEquals(2, github.writes("POST", "/pulls").size(), "one pull request per case at a time");
        assertEquals("open", github.pr(2).state());
        List<FixPrRow> prs = fixPrs.listByCase(pid, caseId);
        assertEquals(
                Set.of("1:merged", "2:open"),
                Set.copyOf(prs.stream().map(p -> p.prNumber() + ":" + p.state()).toList()));
        assertEquals(
                2,
                caseEvents.listByCase(pid, caseId).stream()
                        .filter(e -> CaseEventRow.Kind.FIX_PR_OPENED.equals(e.kind()))
                        .count());
        FixDtos.FixPrView newest = Objects.requireNonNull(
                Objects.requireNonNull(caseService.detail(pid, caseId).fix()).pr());
        assertEquals(2, newest.number());
        assertEquals(secondBranch, newest.branch());
    }

    // ---- the windows and the RCA -----------------------------------------------------------------------

    private record Window(String findingId, String traceId, String caseId) {}

    /** A window the classifier flagged and triage ruled positive, joined to its case as the triage path does. */
    private Window window(String pid) {
        Instant now = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.session(pid, sessionId, now);
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, sessionId, now);
        String at = now.toString();
        String findingId = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        pid,
                        BuiltInDetector.Kind.REGEX,
                        "clf-e2e",
                        CAUSE_KEY,
                        1,
                        "cs-search",
                        "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}",
                        at,
                        at,
                        at,
                        at))
                .findingId();
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.EXEMPLAR,
                List.of(FindingEvidenceRepository.Ref.trace(traceId)),
                at);
        findings.recordTriage(pid, findingId, FindingRow.TriageVerdict.POSITIVE, "The search gave up.", null, at);
        CaseRow row = caseOpener.ensureCaseFor(pid, findingId, null).orElseThrow();
        return new Window(findingId, traceId, row.id());
    }

    /** The automatic RCA tick requests one run, which the test claims as the drain would. */
    private RcaJobRow automaticRcaJob(String pid) {
        autoRca.tick();
        List<RcaJobRow> claimed = claim(pid);
        assertEquals(1, claimed.size(), "the tick requests one RCA for the case");
        return claimed.get(0);
    }

    private List<RcaJobRow> claim(String pid) {
        return rcaJobs.claimBatch("fix-e2e", 10_000, 60, 5).stream()
                .filter(j -> j.projectId().equals(pid))
                .toList();
    }

    private void finishRca(RcaJobRow job, RcaDtos.Cause... causes) {
        when(rcaAgent.run(any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "The search gives up after one empty result.",
                        List.of(causes),
                        List.of(),
                        "## Investigation",
                        true,
                        null));
        RcaWorkerRuns.run(rcaWorker, job);
        assertEquals(
                "done",
                reports.findByJobId(job.projectId(), job.id()).orElseThrow().status());
    }

    /** Time passes: the case's RCAs ran this many hours ago, so the six-hour spacing allows the next one. */
    private void ranHoursAgo(String pid, int hours) {
        jdbc.sql("UPDATE rca_report SET created_at = :at WHERE project_id = :pid")
                .param("at", Instant.now().minus(Duration.ofHours(hours)).toString())
                .param("pid", pid)
                .update();
    }

    private static RcaDtos.Cause cause(String path, String type, String traceId) {
        return new RcaDtos.Cause(
                "The " + type + " in " + path + " gives up",
                RcaDtos.Cause.HIGH,
                "standing",
                type,
                "It stops after one empty result.",
                "Every flagged trace stopped there.",
                "Retry once.",
                new RcaDtos.Attribution(null, path, null, null),
                List.of(traceId),
                List.of(),
                1,
                1);
    }

    // ---- the fix run ---------------------------------------------------------------------------------

    /** The worker claims the queued run and runs it on this thread. */
    private FixRunRow runFix(String pid) {
        FixRunRow run = fixRuns.claimNext(Instant.now()).orElseThrow();
        assertEquals(pid, run.projectId());
        fixWorker.run(run);
        return fixRuns.findById(pid, run.id()).orElseThrow();
    }

    private static String fixed(String title, String causeKey) {
        return "{\"outcome\":\"fixed\",\"title\":\"" + title + "\",\"why\":\"" + title + ".\","
                + "\"causes_addressed\":[\"" + causeKey + "\"],"
                + "\"checks\":[{\"command\":\"pytest\",\"result\":\"passed\",\"note\":null}],"
                + "\"no_fix_reason\":null}";
    }

    private void connectRepository(String pid) {
        String now = Instant.now().toString();
        integrations.insert(new GitIntegrationRow(
                Ids.ulid(),
                pid,
                "github",
                FakeGithub.HOST,
                FakeGithub.OWNER,
                FakeGithub.REPO,
                FakeGithub.DEFAULT_BRANCH,
                box.seal("{\"token\":\"ghp_e2e\"}"),
                now,
                now));
    }

    private static ProviderCredential anthropicKey(String orgId) {
        return new ProviderCredential(
                Ids.ulid(),
                orgId,
                null,
                ModelProvider.ANTHROPIC,
                null,
                "sealed",
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "t0",
                "t0");
    }

    private CaseEventRow only(String caseId, String pid, String kind) {
        List<CaseEventRow> matching = caseEvents.listByCase(pid, caseId).stream()
                .filter(e -> kind.equals(e.kind()))
                .toList();
        assertEquals(1, matching.size(), kind + ": " + matching);
        return matching.get(0);
    }

    private static List<String> names(List<Call> calls) {
        return calls.stream().map(c -> c.method() + " " + c.path()).toList();
    }
}
