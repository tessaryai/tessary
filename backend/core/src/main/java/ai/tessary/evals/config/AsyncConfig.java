// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import ai.tessary.evals.open.obs.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Application task executors.
 *
 * <p>Project Loom posture: the I/O-bound pools (the classifier sweep, the two agentic lanes) run
 * on <b>virtual threads</b> via
 * {@link SimpleAsyncTaskExecutor} with {@code setVirtualThreads(true)}, so a
 * blocking judge/HTTP/E2B call parks cheaply instead of pinning a platform
 * thread. Every pool keeps its previous concurrency <b>bound</b> via
 * {@link SimpleAsyncTaskExecutor#setConcurrencyLimit(int)} (a built-in semaphore
 * throttle) — virtual threads are cheap, but the bounds are deliberate
 * backpressure against Bedrock/Anthropic account RPM/TPM, concurrent E2B
 * microVMs, and the bounded HikariCP pool (production profile:
 * {@code maximum-pool-size: 10}).
 *
 * <p>Paced-provider (OpenRouter/Moonshot — Ollama was dropped by #939 D6's maker filter) RPM/TPM
 * limiting is owned entirely by {@code
 * LlmPacer}'s own synchronized sliding window — it's correct under any number of concurrent
 * callers, so no executor here needs to single-thread to protect it.
 *
 * <p>Track A removed four pools with the workers they drained: {@code observerTaskExecutor},
 * {@code synthTaskExecutor}, {@code graderRunTaskExecutor} and {@code datasetRunTaskExecutor}, plus
 * {@code compileKickoffExecutor}. A {@code SimpleAsyncTaskExecutor} bean with no injector is inert
 * rather than harmful, which is exactly why they are deleted here instead of left: nothing would
 * have failed to tell us.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Pool for the alert-destination fan-out. The {@code AlertDeliveryListener} hands a fired
     * alert off here (via {@code AlertDeliveryDispatcher.deliverAll}) so the N blocking outbound HTTP
     * POSTs — each up to the 30s {@code ChannelHttp} timeout — never run inline on the single
     * {@code @Scheduled} AlertWorker thread that publishes the event (a slow/hanging upstream would
     * otherwise stall the whole alert heartbeat and every other firing in the batch). Each delivery is
     * a blocking HTTP call, so it runs on a virtual thread; the concurrency limit (2) is deliberate
     * backpressure against the rate-limited upstreams (Slack/Sentry/Linear/PagerDuty) and the bounded
     * HikariCP pool, while the per-(event,destination) claim makes concurrent dispatch safe.
     */
    @Bean(name = "alertDeliveryExecutor")
    public SimpleAsyncTaskExecutor alertDeliveryExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("alert-deliver-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(2); // bound against rate-limited destination upstreams + the JDBC pool
        ex.setTaskDecorator(new MdcTaskDecorator("alert-deliver"));
        return ex;
    }

    /**
     * Pool for the Slack surface's outbound work — today, {@code SlackBriefPublisher} posting a digest,
     * brief or case opening to a Slack workspace channel through {@code slack-service}.
     *
     * <p>The ~3s Slack ack deadline that originally justified this pool now belongs to the adapter, which
     * ACKs an {@code app_mention} itself and answers on its own background task, and the mention reply on
     * this side is a fixed sentence rather than the LLM diagnosis it used to be. What remains here is the
     * channel posts. Kept separate from every other pool for the original reason: a Slack workspace that
     * has gone slow must never steal the classifier sweep's or an agentic lane's slot. Each task blocks on HTTP, so it
     * runs on a virtual thread; the concurrency limit (2) is deliberate backpressure against Slack's own
     * rate limits and the bounded HikariCP pool.
     *
     * <p><b>This bean has no consumer in the open build, and it stays anyway.</b> #842 took
     * {@code SlackBriefPublisher} to {@code tessary-paid/slack}, and it resolves this executor BY STRING
     * from its {@code @Async("slackTaskExecutor")} — so the reference is invisible to the compiler, to
     * the enforcer and to {@code check-open-boundary.sh}, and a dead-code sweep that removed this bean
     * would leave the publisher silently running on Spring's default executor, unbounded, against a
     * rate-limited API. The name is the contract; do not rename or delete it without changing that
     * annotation in the same commit.
     */
    @Bean(name = "slackTaskExecutor")
    public SimpleAsyncTaskExecutor slackTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("slack-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(2); // bound against the rate-limited model + Slack Web API and the JDBC pool
        ex.setTaskDecorator(new MdcTaskDecorator("slack"));
        return ex;
    }

    /**
     * Pool for the async signal-detection sweep. Separate from every other pool so a
     * substrate sweep never steals an agentic lane's slot. Built-in detectors are mostly
     * structural/heuristic (no LLM call, DB-IO-bound); the encoder-tier detectors, however,
     * issue a scoring HTTP call per observation on the sweep against the standalone
     * classify-service {@code /classify}, so a sweep is network-IO-bound against that service too.
     * The concurrency limit (2) bounds concurrent sweeps against the bounded HikariCP pool and the
     * classify-service call while the {@code FOR UPDATE SKIP LOCKED} job queue spreads the rest
     * across instances. Runs on virtual threads.
     */
    @Bean(name = "signalTaskExecutor")
    public SimpleAsyncTaskExecutor signalTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("signal-");
        ex.setVirtualThreads(true);
        // mixed IO: DB-IO-bound built-ins + classify-service-IO-bound encoder tier; SKIP-LOCKED spreads the rest
        ex.setConcurrencyLimit(2);
        ex.setTaskDecorator(new MdcTaskDecorator("signal"));
        return ex;
    }

    /**
     * Pool for the RCA worker ({@code RcaWorker}): each task runs one root-cause analysis — several
     * window reads, up to nine trace hydrations, and one synthesis LLM call — so it is IO/LLM-bound
     * like {@link #signalTaskExecutor}. Separate pool so a burst of "Run RCA" clicks can't steal
     * the escalation path's or the signal sweep's slot. The concurrency limit (2) bounds concurrent
     * synthesis calls against account RPM/TPM and the bounded HikariCP pool; user-triggered RCAs are
     * rare and have no latency SLA.
     */
    @Bean(name = "rcaTaskExecutor")
    public SimpleAsyncTaskExecutor rcaTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("rca-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(2); // synthesis-LLM-IO-bound; SKIP-LOCKED spreads the rest
        ex.setTaskDecorator(new MdcTaskDecorator("rca"));
        return ex;
    }

    /**
     * Pool for the behaviour-drift triage worker ({@code BehaviorTriageWorker}): each task
     * is one agent session in an E2B microVM over a repo clone, budgeted up to 20 minutes. It
     * gets its own pool for the same reason {@link #rcaTaskExecutor} does — sharing
     * {@code signalTaskExecutor} (limit 2, sized for classify-service and JDBC work) would let two
     * triages occupy it outright and stall the classifier sweep for the whole agent timeout.
     * The limit (2) bounds concurrent microVMs; triage is per-CAUSE, not per-trace, and has no
     * latency SLA — nothing downstream waits on the verdict.
     */
    @Bean(name = "behaviorTriageTaskExecutor")
    public SimpleAsyncTaskExecutor behaviorTriageTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("behavior-triage-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(2); // one E2B microVM each; SKIP-LOCKED spreads the rest
        ex.setTaskDecorator(new MdcTaskDecorator("behavior-triage"));
        return ex;
    }
}
