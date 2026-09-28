// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import ai.tessary.config.RetentionProperties;
import ai.tessary.ops.RetentionPolicyRepository;
import ai.tessary.ops.RetentionPolicyRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * What a project's retention actually is: the platform default for each data class, overridden by any
 * {@code retention_policy} row the project has, unless {@link FixedRetention} fixes it for the project.
 *
 * <p>One resolver rather than a lookup in each caller, because the sweep and the customer-facing answer have
 * to be the same number. A data-handling page that states 90 days while the sweeper runs on 30 is worse than
 * no page — it is a written commitment the system does not keep.
 */
@Service
public class RetentionResolver {

    /** The data classes {@code retention_policy} defines, in the order a reader wants them. */
    public enum DataClass {
        TRACES(RetentionPolicyRow.DataClass.TRACES),
        DETECTIONS(RetentionPolicyRow.DataClass.DETECTIONS);

        private final String wire;

        DataClass(String wire) {
            this.wire = wire;
        }

        /** The value stored in {@code retention_policy.data_class}. */
        public String wire() {
            return wire;
        }
    }

    /**
     * One resolved class. {@code ttlDays} of {@code 0} means kept indefinitely, and {@code fromPolicy} says
     * whether the number came from the project's own row or from the platform default — the distinction a
     * partner asking "did we agree to this?" needs.
     */
    public record EffectiveRetention(DataClass dataClass, int ttlDays, boolean fromPolicy) {

        /** Whether this class is ever deleted. */
        public boolean bounded() {
            return ttlDays > 0;
        }
    }

    private final RetentionPolicyRepository policies;
    private final RetentionProperties props;
    private final FixedRetention fixed;

    public RetentionResolver(RetentionPolicyRepository policies, RetentionProperties props, FixedRetention fixed) {
        this.policies = policies;
        this.props = props;
        this.fixed = fixed;
    }

    /** Every data class's effective retention for one project, in declaration order. */
    public List<EffectiveRetention> resolve(String projectId) {
        Map<String, Integer> overrides = new java.util.HashMap<>();
        for (RetentionPolicyRow row : policies.listByProject(projectId)) {
            overrides.put(row.dataClass(), row.ttlDays());
        }
        List<EffectiveRetention> out = new ArrayList<>(DataClass.values().length);
        for (DataClass dataClass : DataClass.values()) {
            int fixedDays = fixedTtlDays(projectId, dataClass);
            Integer override = overrides.get(dataClass.wire());
            if (fixedDays > 0) {
                out.add(new EffectiveRetention(dataClass, fixedDays, false));
            } else {
                out.add(new EffectiveRetention(
                        dataClass, override != null ? override : platformDefault(dataClass), override != null));
            }
        }
        return out;
    }

    /** The fixed retention for one class, or {@code 0} when this project's own settings apply. */
    public int fixedTtlDays(String projectId, DataClass dataClass) {
        return Math.max(0, fixed.fixedTtlDays(projectId, dataClass));
    }

    public int platformDefault(DataClass dataClass) {
        return switch (dataClass) {
            case TRACES -> props.getTraceTtlDays();
            case DETECTIONS -> props.getDetectionTtlDays();
        };
    }
}
