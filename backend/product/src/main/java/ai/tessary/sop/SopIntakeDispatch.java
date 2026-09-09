// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sop;

import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.pipeline.BundleAssembler.NamedBody;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The always-present bean both import paths call, holding the optional {@link SopIntake}.
 *
 * <p>The multipart upload path and the observer's auto-pull path must store SOPs identically, so
 * they share one resolved holder instead of each taking its own {@code ObjectProvider}: that keeps
 * both callers' constructors honest, taking a required bean that always exists rather than a
 * nullable field and a null check.
 *
 * <p>The provider is resolved once, in the constructor, with {@code orderedStream().findFirst()}
 * rather than {@code getIfAvailable()}, which is declared to throw {@code BeansException} and so
 * fails SpotBugs' {@code CT_CONSTRUCTOR_THROW} check.
 */
@Component
public class SopIntakeDispatch {

    private static final Logger log = LoggerFactory.getLogger(SopIntakeDispatch.class);

    private final @Nullable SopIntake intake;

    /**
     * Latches the "no intake" log line to once per process. {@code /import} runs on every push a
     * busy project makes, so unlatched this would be one line per import forever for a steady state
     * an operator can only act on once. INFO rather than WARN: unlike a queue that fills and never
     * drains, an absent intake leaves nothing behind to go wrong.
     */
    private final AtomicBoolean loggedNoIntake = new AtomicBoolean();

    public SopIntakeDispatch(ObjectProvider<SopIntake> intake) {
        this.intake = intake.orderedStream().findFirst().orElse(null);
    }

    /**
     * Stores the bundle's SOP documents, or does nothing if no intake is available. Returns the
     * number stored, 0 in the absent case.
     *
     * <p>{@code @Transactional} sits here as well as on the implementation so the absent case has
     * the same transactional shape as the present one: both callers already run inside a
     * transaction, and a propagation mismatch is the kind of thing that only shows up under load.
     */
    @Transactional
    public int importSops(String projectId, List<NamedBody> files, @Nullable String sourceCommitSha) {
        SopIntake present = intake;
        if (present == null) {
            if (loggedNoIntake.compareAndSet(false, true)) {
                StructuredLog.info(log, Markers.OPS, "sop.intake.absent")
                        .message("no SopIntake on the classpath; bundle imports store no SOP documents"
                                + " (logged once per process)")
                        .log();
            }
            return 0;
        }
        return present.importSops(projectId, files, sourceCommitSha);
    }
}
