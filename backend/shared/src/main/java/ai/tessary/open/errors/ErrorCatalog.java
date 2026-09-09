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
 * Fail-fast startup validator: scans the registered error enums and refuses
 * to boot if two codes collide. Within-domain duplicates are already
 * impossible (enum constants can't share a name); this catches the rarer
 * case of two enum classes resolving to the same domain prefix.
 *
 * <p>To register a new error enum, add it to {@link #REGISTERED}.
 */
@Component
public class ErrorCatalog {

    private static final Logger log = LoggerFactory.getLogger(ErrorCatalog.class);

    private static final List<Class<? extends ErrorCode>> REGISTERED = List.of(
            CommonError.class,
            JudgeError.class,
            IngestError.class,
            ModelConfigError.class,
            VersionError.class,
            GitError.class,
            PipelineError.class,
            ClassifierError.class,
            AlertError.class,
            PreDeployError.class,
            QueryError.class,
            RedactionError.class,
            MeteringError.class,
            CapabilityError.class,
            RcaError.class,
            SlackError.class,
            CaseError.class,
            AuthError.class);

    /**
     * The registered enums, for a caller outside this module that needs to check codes against them.
     * Returns the same immutable {@code List.of} the validator walks, so the two can never drift.
     */
    public static List<Class<? extends ErrorCode>> registered() {
        return REGISTERED;
    }

    @PostConstruct
    public void validate() {
        Map<String, ErrorCode> byCode = new HashMap<>();
        for (Class<? extends ErrorCode> cls : REGISTERED) {
            if (!cls.isEnum()) {
                throw new IllegalStateException("ErrorCode impls must be enums: " + cls.getName());
            }
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
        log.info("error catalog: {} unique codes across {} domains", byCode.size(), REGISTERED.size());
    }
}
