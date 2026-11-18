// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Deployment bounds on prompt-playground chat calls ({@code tessary.playground.*}). */
@ConfigurationProperties(prefix = "tessary.playground")
public record ChatProperties(

        /** Per attempt. A timeout is not retried: a person is waiting at a button. */
        @DefaultValue("60000") long timeoutMs,

        /** Including the first, on a 429, a 5xx or a transport failure. */
        @DefaultValue("2") int maxAttempts,

        /** Runs in flight at once; one more is {@code PLAYGROUND.BUSY}. */
        @DefaultValue("4") int concurrency,

        /** The ceiling on {@code max_tokens}, and the value sent to Anthropic, which requires one. */
        @DefaultValue("4096") int maxOutputTokens) {}
