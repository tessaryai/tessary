// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot if two registered error enums resolve to the same domain. Register new enums in {@link #REGISTERED}.
 */
@Component
public class ErrorCatalog {

    private static final Logger log = LoggerFactory.getLogger(ErrorCatalog.class);

    private static final List<Class<? extends ErrorCode>> REGISTERED = List.of(
            CommonError.class,
            IngestError.class,
            ModelConfigError.class,
            GitError.class,
            PipelineError.class,
            ClassifierError.class,
            AlertError.class,
            QueryError.class,
            RedactionError.class,
            MeteringError.class,
            CapabilityError.class,
            RcaError.class,
            CaseError.class,
            AuthError.class,
            DecisionError.class,
            RetentionError.class,
            AgentRunError.class,
            MetricsError.class,
            PromptsError.class,
            PlaygroundError.class);

    private final List<Class<? extends ErrorCode>> registered;

    public ErrorCatalog() {
        this(REGISTERED);
    }

    /** For a test to build a collision. */
    ErrorCatalog(List<Class<? extends ErrorCode>> registered) {
        this.registered = registered;
    }

    @PostConstruct
    public void validate() {
        Map<String, ErrorCode> byCode = new HashMap<>();
        for (Class<? extends ErrorCode> cls : registered) {
            ErrorCode[] constants = cls.getEnumConstants();
            for (ErrorCode ec : constants) {
                ErrorCode prev = byCode.put(ec.code(), ec);
                if (prev != null) {
                    throw new IllegalStateException(String.format(
                            Locale.ROOT,
                            "Duplicate error code '%s' declared by both %s and %s",
                            ec.code(),
                            prev.getClass().getName(),
                            ec.getClass().getName()));
                }
            }
        }
        log.info("error catalog: {} unique codes across {} domains", byCode.size(), registered.size());
    }
}
