// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the backend reaches {@code compile-service/}, bound from {@code evals.sop.compile.*}.
 *
 * <p>The compile service fits a project's SOP-conformance bundle by running the Python engine's
 * {@code fit} + {@code export} over that project's traffic. The fitting code is sklearn and cannot
 * move into the JVM, so this is an address, a key and a fit-window bound — nothing about scoring,
 * which is the Java port's job and stays here.
 *
 * <p><b>Default-OFF, and it means the worker fails loudly rather than silently.</b> With no base URL
 * configured, {@code SopCompileWorker} dead-letters each claimed job with a message naming this
 * property. That is deliberate: a stored SOP that is never compiled is a project whose conformance
 * sweep is a permanent no-op, and the previous stub drained the queue while looking healthy. An
 * unconfigured deploy should be visible in the dead-letter count, not in a silence.
 */
@Component
@ConfigurationProperties(prefix = "evals.sop.compile")
public class SopCompileProperties {

    /** Where {@code compile-service} is reachable, e.g. {@code http://compile:8100}. Blank → OFF. */
    private @Nullable String url;

    /**
     * The shared key the backend presents on {@code POST /compile}; env-injected, never committed.
     * Same symmetric-secret posture as {@code classify-service} and {@code slack-service}: one
     * private link between two of our own processes, one secret to rotate.
     */
    private @Nullable String apiKey;

    /**
     * Which frozen encoder the fit embeds with — {@code gte} or {@code minilm}, the engine's two
     * ({@code experiments.engine.run ENCODERS}). It decides the checkpoint the produced bundle
     * declares, so it must be one the classify-service's {@code embedders.json} actually serves, or
     * the fit fails loudly at the first {@code /embed} call rather than scoring on a substitute.
     */
    private String encoder = "gte";

    /**
     * How many of the project's most recent conversations form the reference window one fit trains
     * on.
     *
     * <p><b>A bound on the WINDOW, never on any turn's content.</b> Whole conversations are loaded
     * and shipped verbatim — the engine's conversation-cumulative tool view is undefined over a
     * conversation cut in half (frozen decision 2), and clipping trace text would change what the
     * encoder reads. So the knob counts conversations, and everything inside the chosen ones goes.
     */
    private int fitWindowConversations = 2_000;

    /**
     * How long to wait on a fit before giving up. Generous: a fit is thousands of embedding calls
     * plus a logistic head per rule, and the service is bounded to one at a time, so a queued
     * request legitimately waits. The classifier worker's lease and attempt budget are the real
     * retry loop; there are deliberately no in-client retries.
     */
    private long timeoutSeconds = 900;

    public @Nullable String getUrl() {
        return url;
    }

    public void setUrl(@Nullable String url) {
        this.url = url;
    }

    public @Nullable String getApiKey() {
        return apiKey;
    }

    public void setApiKey(@Nullable String apiKey) {
        this.apiKey = apiKey;
    }

    public String getEncoder() {
        return encoder;
    }

    public void setEncoder(String encoder) {
        this.encoder = encoder;
    }

    public int getFitWindowConversations() {
        return fitWindowConversations;
    }

    public void setFitWindowConversations(int fitWindowConversations) {
        this.fitWindowConversations = fitWindowConversations;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Whether a compile can even be attempted: an address and a key. */
    public boolean isConfigured() {
        return url != null && !url.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
