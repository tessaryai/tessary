// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.substrate.SubstrateObservation;
import org.jspecify.annotations.Nullable;

public final class TestObservations {

    private TestObservations() {}

    public static SubstrateObservation llm(
            String id,
            String projectId,
            String traceId,
            @Nullable String sessionId,
            @Nullable String callSiteId,
            @Nullable String input,
            @Nullable String output,
            String createdAt) {
        return new SubstrateObservation(
                id,
                projectId,
                traceId,
                sessionId,
                null,
                callSiteId,
                "llm",
                "chat",
                input,
                output,
                null,
                createdAt,
                null);
    }
}
