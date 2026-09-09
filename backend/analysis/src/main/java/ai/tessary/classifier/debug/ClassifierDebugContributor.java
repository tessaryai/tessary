// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import ai.tessary.classifier.debug.ClassifierDebugDtos.BehaviorProfileDebugView;
import java.util.List;

/**
 * One classifier family's contribution to the debug bundle: the fitted state that family holds,
 * projected into this package's own wire shape.
 *
 * <p>A family with no registered contributor here renders a null profile block, the same shape the
 * debug view already uses for a family with no profile block at all, so {@code GET
 * /classifiers/{id}/debug} stays stable either way.
 */
public interface ClassifierDebugContributor {

    /**
     * The family this contributor fills the profile block for.
     */
    String family();

    /**
     * The epochs this classifier holds for the project, narrowed to the classifier row being
     * debugged. Empty is a real answer, distinct from a family with no contributor at all, which
     * renders null.
     */
    List<BehaviorProfileDebugView> profiles(String projectId, String classifierId);
}
