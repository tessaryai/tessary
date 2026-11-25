// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Picks the one rule the sources give a finding, else {@link HitRule#DEFAULT}. */
@Component
public class HitRules {

    private final List<EndUserHitSource> sources;

    @Autowired
    public HitRules(ObjectProvider<EndUserHitSource> sources) {
        this(sources.orderedStream().toList());
    }

    HitRules(List<EndUserHitSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /** Two sources with a rule for one finding fail loud: the rule must not depend on classpath order. */
    public HitRule ruleFor(HitFinding finding) {
        HitRule found = null;
        for (EndUserHitSource source : sources) {
            Optional<HitRule> rule = source.ruleFor(finding);
            if (rule.isEmpty()) continue;
            if (found != null) {
                throw new IllegalStateException("two hit sources have a rule for " + finding.classifierKey() + ": "
                        + found.name() + " and " + rule.get().name());
            }
            found = rule.get();
        }
        return found == null ? HitRule.DEFAULT : found;
    }
}
