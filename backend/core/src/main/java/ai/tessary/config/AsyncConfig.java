// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import ai.tessary.open.obs.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * I/O-bound pools run on virtual threads, but every pool keeps a concurrency limit as backpressure against provider
 * RPM/TPM, concurrent E2B microVMs, and the bounded HikariCP pool.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Keeps blocking outbound alert POSTs (up to the 30s {@code ChannelHttp} timeout) off the single AlertWorker
     * thread. The per-(event, destination) claim makes concurrent dispatch safe.
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
     * Separate so a sweep never steals an agentic lane's slot. Encoder-tier detectors make an HTTP call per
     * observation, so this is network-bound too; {@code FOR UPDATE SKIP LOCKED} spreads the rest across instances.
     */
    @Bean(name = "signalTaskExecutor")
    public SimpleAsyncTaskExecutor signalTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("signal-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(2);
        ex.setTaskDecorator(new MdcTaskDecorator("signal"));
        return ex;
    }

    /** Separate so a burst of "Run RCA" clicks can't steal the signal sweep's slot. */
    @Bean(name = "rcaTaskExecutor")
    public SimpleAsyncTaskExecutor rcaTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("rca-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(2); // synthesis-LLM-IO-bound; SKIP-LOCKED spreads the rest
        ex.setTaskDecorator(new MdcTaskDecorator("rca"));
        return ex;
    }

    /**
     * Separate because two 20-minute triages in {@code signalTaskExecutor} would stall the classifier sweep for the
     * whole agent timeout.
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
