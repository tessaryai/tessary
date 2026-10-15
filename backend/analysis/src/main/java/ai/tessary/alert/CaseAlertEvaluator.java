// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.alert.AlertQueryRepository.OpenedCase;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.config.AlertProperties;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns opened cases into unpersisted firings, one per case; the worker owns the write. Cases from detectors the org no
 * longer has are dropped here: they stay in Triage as history, but must not page anyone.
 */
@Component
public class CaseAlertEvaluator {

    /**
     * Past a handful a notification becomes a wall nobody reads. The anchor advances only to the last case delivered,
     * so the remainder goes next tick.
     */
    static final int PER_TICK_CAP = 10;

    private final AlertQueryRepository queries;
    private final AlertProperties props;
    private final ObjectMapper mapper;

    public CaseAlertEvaluator(AlertQueryRepository queries, AlertProperties props, ObjectMapper mapper) {
        this.queries = queries;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * @param since the rule's anchor, or its creation instant, so a new rule never pages about an old backlog @param
     * unavailableDetectors detector kinds this org's flag layer is withholding
     */
    public List<AlertEventRow> due(
            AlertRuleRow rule, Instant since, Instant now, java.util.Set<String> unavailableDetectors) {
        List<AlertEventRow> out = new java.util.ArrayList<>();
        for (OpenedCase c :
                queries.casesOpenedBetween(rule.projectId(), since.toString(), now.toString(), PER_TICK_CAP)) {
            if (unavailableDetectors.contains(c.detector())) continue;
            out.add(firing(rule, c, now));
        }
        return out;
    }

    private AlertEventRow firing(AlertRuleRow rule, OpenedCase c, Instant now) {
        return new AlertEventRow(
                Ids.ulid(),
                rule.projectId(),
                rule.id(),
                null, // a case is not one classifier's detection; its detector is in the payload
                AlertRuleRow.RuleType.CASE_OPENED,
                null, // not a threshold breach — no counting basis
                AlertEventRow.State.FIRING,
                // Keyed on (rule, case_id), so the window says when it happened rather than carrying identity.
                c.openedAt(),
                c.openedAt(),
                null,
                null,
                payload(c),
                c.id(),
                now.toString(),
                now.toString());
    }

    /** Baked into the row because delivery is retried: a retry must send what was true when the case opened. */
    private String payload(OpenedCase c) {
        ObjectNode root = mapper.createObjectNode();
        root.put("case_id", c.id());
        root.put("case_reference", c.reference());
        root.put("detector", c.detector());
        root.put("title", c.title());
        root.put("basis", c.basis());
        root.put("opened_at", c.openedAt());
        String callSite = c.callSiteId();
        // __unattributed__ is the pile of unattributable turns, not a call site.
        if (callSite != null && !callSite.isBlank() && !BehaviorSubstrateRepository.UNATTRIBUTED.equals(callSite)) {
            root.put("call_site_id", callSite);
        }
        root.put("ruled_by", ruledBy(c));
        String summary = c.rulingSummary();
        if (summary != null && !summary.isBlank() && !c.ruledByHuman()) root.put("ruling_summary", summary);
        String url = caseUrl(c);
        if (url != null) root.put("url", url);
        return root.toString();
    }

    private static String ruledBy(OpenedCase c) {
        if (c.ruledByHuman()) return "a person";
        return c.rulingVerdict() == null ? "a Layer-2 run" : "a triage run";
    }

    /** Null rather than a relative or localhost guess: a link that goes nowhere is worse than none. */
    private @Nullable String caseUrl(OpenedCase c) {
        String base = props.getAppBaseUrl();
        if (base == null || base.isBlank()) return null;
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/orgs/" + encode(c.orgSlug()) + "/projects/" + encode(c.projectSlug()) + "/cases/"
                + encode(c.id());
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
