// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.debug;

import ai.tessary.evals.classifier.debug.ClassifierDebugDtos.BehaviorProfileDebugView;
import java.util.List;

/**
 * One classifier family's contribution to the debug bundle — the fitted state only that family holds,
 * projected into this package's own wire shape.
 *
 * <p><b>Why a seam for a read this small.</b> {@code ClassifierDebugService} used to inject
 * {@code BehaviorProfileRepository} directly, which made an otherwise generic debug surface the only
 * thing outside the drift feature holding its epoch store. #840 took behaviour drift to
 * {@code tessary-paid/}, and that one field is what would have turned a package rename into a redesign
 * of this class under time pressure. The implementation is now off this module's classpath entirely and
 * arrives, or does not, on the runtime one.
 *
 * <p><b>The DTO stays here, in the consumer's package.</b> Folding this read into the findings feature's
 * {@code ProfileSource} was considered and rejected: it would make {@code classifier/finding/} depend on
 * {@code ClassifierDebugDtos} and couple two packages that have nothing else to say to each other. Each
 * consumer keeps its own view record; the adapter fills whichever one it is asked for.
 *
 * <p><b>Absence is a supported state, and it is quiet.</b> With no contributor registered for a family,
 * the block is {@code null} — exactly what the debug view already renders for every family that has no
 * profile block at all. {@code GET /classifiers/{id}/debug} and {@code BehaviorProfileDebugView} do not
 * change shape either way, which is what keeps the checked-in OpenAPI spec byte-identical.
 */
public interface ClassifierDebugContributor {

    /**
     * The {@code ClassifierDebugView.Family} this contributor fills the profile block for, so the service
     * routes on the same string it already computed rather than on bean order.
     */
    String family();

    /**
     * The epochs this classifier holds for the project, narrowed to the one classifier row being
     * debugged. Empty is a real answer — a classifier that has been enabled but never swept has no
     * epochs — and is deliberately distinguishable from an ABSENT contributor, which reads as null.
     */
    List<BehaviorProfileDebugView> profiles(String projectId, String classifierId);
}
