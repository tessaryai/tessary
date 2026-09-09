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
 * Turns cases that opened into candidate firings — one per case (launch requirement I2).
 *
 * <p>Side-effect-free apart from its read, exactly like {@link AlertEvaluator} and
 * {@link AlertAssembler}: it returns unpersisted rows and the worker owns the idempotent write and the
 * event. What it adds over those two is the PAYLOAD, and the payload is the point of the segment — a
 * message someone can act on without opening the product has to carry the detector's own account of why
 * the case crossed, who ruled it real, and a link back.
 *
 * <p><b>Detectors the org no longer has are dropped here rather than at delivery.</b> A case opened by a
 * withdrawn classifier stays in Triage as history (segment D's decision), and the whole argument for
 * leaving it there is that a screen someone chose to open is different from a page at 3am. Firing about
 * one would collapse that distinction.
 */
@Component
public class CaseAlertEvaluator {

    /**
     * How many cases one tick will fire about. A partner coming back from a long quiet window, or a
     * project whose first sweep triages a backlog, should not produce a hundred Slack messages in one
     * second — past a handful the notification stops being a page and becomes a wall nobody reads. The
     * remainder is not lost: the anchor advances only to the last case delivered, so the next tick picks
     * up where this one stopped.
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
     * The firings due for one case-opened rule at {@code now}, oldest first.
     *
     * @param since the rule's anchor — the {@code opened_at} of the last case it delivered, or the rule's
     *     creation instant. A rule created today never pages about last month's backlog.
     * @param unavailableDetectors detector kinds this org's flag layer is withholding.
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
                // The window IS the case's own opening instant. A case-opened firing is keyed on
                // (rule, case_id) rather than on the window, so these two carry meaning rather than
                // identity: they say when the thing happened, which is what a delayed message needs.
                c.openedAt(),
                c.openedAt(),
                null,
                null,
                payload(c),
                c.id(),
                now.toString(),
                now.toString());
    }

    /**
     * Everything the message renders from, resolved once at fire time.
     *
     * <p>Baked into the row rather than joined at delivery because delivery is retried: a channel that
     * fails and is retried an hour later must send the message that was true when the case opened, not a
     * re-read of a case someone has since resolved.
     */
    private @Nullable String payload(OpenedCase c) {
        ObjectNode root = mapper.createObjectNode();
        root.put("case_id", c.id());
        root.put("case_reference", c.reference());
        root.put("detector", c.detector());
        root.put("title", c.title());
        root.put("basis", c.basis());
        root.put("opened_at", c.openedAt());
        String callSite = c.callSiteId();
        // __unattributed__ is not a call site, it is the pile of turns nothing could be attributed to.
        // Printing it in a Slack message is the same mistake as printing it as a chip on the case page.
        if (callSite != null && !callSite.isBlank() && !BehaviorSubstrateRepository.UNATTRIBUTED.equals(callSite)) {
            root.put("call_site_id", callSite);
        }
        root.put("ruled_by", ruledBy(c));
        String summary = c.rulingSummary();
        if (summary != null && !summary.isBlank() && !c.ruledByHuman()) root.put("ruling_summary", summary);
        String url = caseUrl(c);
        if (url != null) root.put("url", url);
        try {
            return mapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null; // the body is a convenience; a serialization slip must not withhold the alert
        }
    }

    /** Who said this was real, in the words the case page and the finding row already use. */
    private static String ruledBy(OpenedCase c) {
        if (c.ruledByHuman()) return "a person";
        return c.rulingVerdict() == null ? "a Layer-2 run" : "a triage run";
    }

    /**
     * A link back to the case, or null when no public base URL is configured.
     *
     * <p>Null rather than a relative path or a localhost guess: a Slack message carrying a link that goes
     * nowhere is worse than one carrying none, because the reader spends the click finding that out. The
     * message is written to stand on its own either way — that is what I2 asks for.
     */
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
