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
 * The always-present bean the two open import paths call, holding the optional {@link SopIntake}.
 *
 * <p><b>Why a dispatcher rather than an {@code ObjectProvider} at each call site.</b> There are two
 * import paths — the multipart upload and the observer's auto-pull — and they must store SOPs
 * identically or a repo imported one way carries policy the other way does not. One resolved holder is
 * how that stays true; two providers is how the paths diverge the first time one of them is edited.
 * It is also what keeps both callers' constructors honest: they take a required bean that always
 * exists, so neither has to carry a nullable field and a null check that reads like a bug.
 *
 * <p>The provider is resolved ONCE, in the constructor, with {@code orderedStream().findFirst()} rather
 * than {@code getIfAvailable()}: the latter is declared to throw {@code BeansException}, and SpotBugs'
 * {@code CT_CONSTRUCTOR_THROW} fails a constructor that can. Same read, same shape
 * {@code ConformanceController} and {@code SopCompileWorker} use.
 */
@Component
public class SopIntakeDispatch {

    private static final Logger log = LoggerFactory.getLogger(SopIntakeDispatch.class);

    private final @Nullable SopIntake intake;

    /**
     * Latches the "no intake" INFO to ONE line per process.
     *
     * <p>This is not an error path, it is the open edition: no {@link SopIntake} ships, and
     * {@code /import} is a per-REQUEST path a busy project hits on every push. Unlatched, a steady
     * state that an operator can act on exactly once would be one egressed line per import, forever.
     * {@code backend/AGENTS.md} names that shape under "log OUTCOMES and COST, not intent" — the
     * 2026-07-31 incident where two sweep events were 77% of production log volume and said nothing
     * actionable. Same idiom, same reason, as {@code SopCompileWorker}'s {@code sop.compile.no-compiler}
     * one module over; INFO rather than WARN because, unlike a queue that fills and never drains, an
     * absent intake leaves nothing behind to go wrong.
     */
    private final AtomicBoolean loggedNoIntake = new AtomicBoolean();

    public SopIntakeDispatch(ObjectProvider<SopIntake> intake) {
        this.intake = intake.orderedStream().findFirst().orElse(null);
    }

    /**
     * Store the bundle's SOP documents, or do nothing at all in an edition that has no intake.
     * Returns the number stored — 0 in the absent case, which is the same answer the paid
     * implementation gives an org without {@code SOP_CONFORMANCE}.
     *
     * <p>{@code @Transactional} sits here as well as on the implementation so the absent case has the
     * same transactional shape as the present one: both callers already run inside a transaction, and
     * a propagation difference between editions is the kind of thing that only shows up under load.
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
