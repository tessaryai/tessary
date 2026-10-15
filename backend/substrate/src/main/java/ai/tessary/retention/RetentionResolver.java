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
 * A project's effective retention: the platform default per data class, overridden by {@code retention_policy} rows
 * unless {@link FixedRetention} fixes it. One resolver so the sweeper and the customer-facing answer can never state
 * different numbers.
 */
@Service
public class RetentionResolver {

    public enum DataClass {
        TRACES(RetentionPolicyRow.DataClass.TRACES),
        DETECTIONS(RetentionPolicyRow.DataClass.DETECTIONS);

        private final String wire;

        DataClass(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * {@code ttlDays} 0 means kept indefinitely; {@code fromPolicy} says whether it came from the project's own row.
     */
    public record EffectiveRetention(DataClass dataClass, int ttlDays, boolean fromPolicy) {

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

    /** In declaration order. */
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

    /** 0 when this project's own settings apply. */
    public int fixedTtlDays(String projectId, DataClass dataClass) {
        return Math.max(0, fixed.fixedTtlDays(projectId, dataClass));
    }

    public int platformDefault(DataClass dataClass) {
        return switch (dataClass) {
            case TRACES -> props.traceTtlDays();
            case DETECTIONS -> props.detectionTtlDays();
        };
    }
}
