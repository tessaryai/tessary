// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sop;

import ai.tessary.pipeline.BundleAssembler.NamedBody;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The port both bundle-import paths store SOP documents through.
 *
 * <p>This build ships no implementation, so nothing here stores anything or enqueues a compile job:
 * {@code /import} still commits the bundle and syncs graders exactly as it does today, since SOP
 * files simply aren't something this build acts on. Callers must go through {@link SopIntakeDispatch}
 * rather than injecting this port directly, since a required constructor parameter with no candidate
 * bean is an unsatisfied dependency in Spring, not a null: injecting it directly would fail the boot
 * rather than degrade.
 */
public interface SopIntake {

    /**
     * Store every {@code sops/*.yaml} document in {@code files} that carries new content, enqueuing one
     * compile job per stored row. Returns the number of documents stored; 0 covers both "nothing new"
     * and "this build does not do SOP conformance", which the caller doesn't need to distinguish.
     */
    int importSops(String projectId, List<NamedBody> files, @Nullable String sourceCommitSha);
}
