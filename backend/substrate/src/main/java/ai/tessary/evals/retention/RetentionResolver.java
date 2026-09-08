// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.retention;

import ai.tessary.evals.config.RetentionProperties;
import ai.tessary.evals.ops.RetentionPolicyRepository;
import ai.tessary.evals.ops.RetentionPolicyRow;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * What a project's retention actually is: the platform default for each data class, overridden by any
 * {@code retention_policy} row the project has.
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

    public RetentionResolver(RetentionPolicyRepository policies, RetentionProperties props) {
        this.policies = policies;
        this.props = props;
    }

    /** Every data class's effective retention for one project, in declaration order. */
    public List<EffectiveRetention> resolve(String projectId) {
        Map<String, Integer> overrides = new java.util.HashMap<>();
        for (RetentionPolicyRow row : policies.listByProject(projectId)) {
            overrides.put(row.dataClass(), row.ttlDays());
        }
        List<EffectiveRetention> out = new ArrayList<>(DataClass.values().length);
        for (DataClass dataClass : DataClass.values()) {
            Integer override = overrides.get(dataClass.wire());
            out.add(new EffectiveRetention(
                    dataClass, override != null ? override : platformDefault(dataClass), override != null));
        }
        return out;
    }

    /** The same answer keyed for a caller that wants one class. */
    public Map<DataClass, EffectiveRetention> resolveByDataClass(String projectId) {
        Map<DataClass, EffectiveRetention> out = new EnumMap<>(DataClass.class);
        for (EffectiveRetention e : resolve(projectId)) out.put(e.dataClass(), e);
        return out;
    }

    public int platformDefault(DataClass dataClass) {
        return switch (dataClass) {
            case TRACES -> props.getTraceTtlDays();
            case DETECTIONS -> props.getDetectionTtlDays();
        };
    }
}
