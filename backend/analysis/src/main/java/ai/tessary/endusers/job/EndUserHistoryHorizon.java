// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import java.util.OptionalInt;

/**
 * How many days of end-user facts a project keeps (plan 3.2). {@link TraceTtlHistoryHorizon} is this build's rule;
 * another build replaces it with its own bean. It is not a retention data class: the trace TTL can be fixed for every
 * data class, and the facts must still outlive the traces.
 */
public interface EndUserHistoryHorizon {

    /** Days kept, counted back from the job's day; empty when facts are kept for good. */
    OptionalInt days(String projectId);
}
