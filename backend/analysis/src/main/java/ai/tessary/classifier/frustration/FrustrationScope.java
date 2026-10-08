// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierDtos.FrustrationScopeView;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads and sets the call sites the Frustration classifier scores ({@link FrustrationScopeRepository}). A new pick
 * applies to turns the sweep reads from then on: turns it already read are not sent again, since that would spend
 * the org's provider credit on history.
 */
@Service
public class FrustrationScope {

    private static final Logger log = LoggerFactory.getLogger(FrustrationScope.class);

    private final FrustrationScopeRepository scopes;

    public FrustrationScope(FrustrationScopeRepository scopes) {
        this.scopes = scopes;
    }

    public FrustrationScopeView view(String projectId, ClassifierRow signal) {
        requireFrustration(signal);
        return new FrustrationScopeView(List.copyOf(scopes.callSites(projectId, signal.id())));
    }

    public FrustrationScopeView set(String projectId, ClassifierRow signal, List<String> callSiteIds) {
        requireFrustration(signal);
        Set<String> picked = new TreeSet<>();
        for (String id : callSiteIds) picked.add(id.strip());
        scopes.replace(projectId, signal.id(), picked);
        StructuredLog.info(log, Markers.OPS, "frustration.scope")
                .message("frustration scope updated: %d call site(s) picked", picked.size())
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("callSites", picked.size())
                .log();
        return new FrustrationScopeView(List.copyOf(picked));
    }

    private static void requireFrustration(ClassifierRow signal) {
        if (!BuiltInDetector.Kind.FRUSTRATION.equals(signal.detector())) {
            throw new TessaryException(ClassifierError.NOT_FRUSTRATION, signal.classifierKey());
        }
    }
}
