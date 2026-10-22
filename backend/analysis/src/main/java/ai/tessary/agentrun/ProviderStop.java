// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;

/**
 * A reply the model provider stopped: a key it rejected, a request it refused, or the provider being down. Each lane
 * maps {@link #kind()} to its own code, since a rejected key holds a triage job and stops an RCA.
 */
public final class ProviderStop extends TessaryException {

    private final String kind;

    /** @param kind {@code provider_rejected}, {@code request_refused} or {@code provider_unavailable} */
    public ProviderStop(String kind, String detail) {
        super(AgentRunError.RUN_FAILED, kind + ": " + detail);
        this.kind = kind;
    }

    public String kind() {
        return kind;
    }
}
