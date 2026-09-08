// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.sop;

import ai.tessary.evals.pipeline.BundleAssembler.NamedBody;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The open port both bundle-import paths store SOP documents through.
 *
 * <p><b>Why a port for one method.</b> #842 took SOP intake and its compile queue to
 * {@code tessary-paid/sop}. The two CALLERS could not go with it: {@code pipeline/ImportController}
 * serves the open route {@code POST /api/orgs/{orgSlug}/projects/{projectSlug}/import}, whose path and
 * schemas are in the checked-in OpenAPI spec, and {@code observer/BundleImportService} is a required
 * dependency of {@code ObserverProcessor}, i.e. the auto-import critical path — and it lives in
 * {@code analysis}, one module further out. Left as direct references, both would name a paid package:
 * the {@code enforce-open-to-paid-direction} enforcer fails at {@code validate}, and
 * {@code check-open-boundary.sh} rule 1 fails on the package name as plain text.
 *
 * <p>So this is the third instance of the inversion #839 authored as {@code ProfileSource} and #841
 * repeated as {@code FitReportSource}: the port is owned by the open module, the implementation
 * ({@code SopIntakeService}) is paid, and nothing open names the overlay.
 *
 * <p><b>Absence answers exactly as withholding does.</b> An edition with no implementation stores
 * nothing and enqueues nothing — byte-for-byte what an org WITHOUT {@code Capability.SOP_CONFORMANCE}
 * already got from the paid implementation, which returns 0 at its first line. So {@code /import}
 * behaves in the open edition exactly as it does today for the overwhelming majority of orgs: the
 * bundle commits, graders sync, and the SOP files in it are simply not a thing this edition does. That
 * is why callers must go through {@link SopIntakeDispatch} rather than injecting this directly — a
 * required constructor parameter with no candidate bean is an UNSATISFIED dependency in Spring, not a
 * null, so the open edition would fail to BOOT rather than degrade.
 */
public interface SopIntake {

    /**
     * Store every {@code sops/*.yaml} document in {@code files} that carries new content, enqueuing one
     * compile job per stored row. Returns the number of documents stored; 0 means nothing new — and
     * also means "this edition, or this org, does not do SOP conformance", which the caller neither
     * distinguishes nor needs to.
     */
    int importSops(String projectId, List<NamedBody> files, @Nullable String sourceCommitSha);
}
