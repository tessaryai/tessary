// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.model.ContentBlock;
import ai.tessary.storage.RetrievedDocRow;
import ai.tessary.storage.SessionRow;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.jspecify.annotations.Nullable;

/**
 * Generates the sample project's "AI customer-support agent" showcase: ~500+ support-ticket
 * traces over a 14-day window, threaded through a 7-call-site pipeline, with six of them carrying a
 * real cost/duration/error-rate drift visible when the generated spans are aggregated by day and call
 * site. Pure and deterministic (seeded {@link Random}, no clock reads beyond one {@code now} anchor
 * passed in); no LLM calls, no I/O; {@link SampleProjectSeedListener} does all the writing.
 *
 * <p>Every trace is a flat, sequentially nested pipeline:
 * {@code assemble_ticket_context -> classify_intent -> kb_search -> generate_response ->}
 * optionally {@code refund_api}/{@code ticket_escalation} {@code -> } optionally
 * {@code export_ticket}, chosen per scenario category, so a trace reads as one coherent ticket
 * handled end to end rather than unrelated spans sharing a trace id.
 *
 * <p>Some tickets are threads: the customer writes back twice, each reply its own trace in one session.
 * Those threads are what the Frustration classifier scores, and the replies {@code generate_response}
 * writes from its knowledge-base articles are what the Groundedness classifier scores. From
 * {@link #UNGROUNDED_ONSET_DAY} the replies start promising things the articles never say, and the
 * customers they were promised to come back annoyed, so the two findings tell one story.
 */
final class SampleShowcase {

    private SampleShowcase() {}

    // ---- call sites --------------------------------------------------------------------------

    static final String ASSEMBLE = "assemble_ticket_context";
    static final String CLASSIFY = "classify_intent";
    static final String KB_SEARCH = "kb_search";
    static final String GENERATE = "generate_response";
    static final String REFUND = "refund_api";
    static final String ESCALATE = "ticket_escalation";
    static final String EXPORT = "export_ticket";

    static final List<String> CALL_SITES = List.of(ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE, REFUND, ESCALATE, EXPORT);

    static final int WINDOW_DAYS = 14;

    // ---- scenario taxonomy --------------------------------------------------------------------

    /**
     * Which vocabulary a template's {@code {product\}} placeholder draws from.
     *
     * <p>Templates used to share ONE flat pool of "products", which is how the sample project ended up
     * asking "How do I add teammates to Storage upgrade?" and "How to cancel API credits subscription".
     * A support ticket names a plan, an integration, a screen or a line item, and those four are not
     * interchangeable in a sentence, so each template declares which one its wording needs.
     */
    private enum Noun {
        /** A subscription tier: "upgrade to …", "cancel …", "our workspace is on …". */
        PLAN,
        /** A third-party system data flows to: "our … integration", "webhooks to …". */
        INTEGRATION,
        /** A screen or feature inside the product: "… crashes", "settings in …". */
        AREA,
        /** A billed line item: "charged $40 for …", "refund …". */
        ITEM
    }

    private record Template(String category, String intent, Noun noun, String subject, String body) {}

    private record KbArticle(String title, String snippet) {}

    private static final Map<String, List<KbArticle>> KB_ARTICLES = Map.of(
            "refund_request",
            List.of(
                    new KbArticle(
                            "Refund Policy & Timelines",
                            "Refunds post to the original payment method and typically land within 3-5 business"
                                    + " days."),
                    new KbArticle(
                            "Duplicate Charges",
                            "A duplicate charge for the same order is reversed automatically once verified.")),
            "account_access",
            List.of(
                    new KbArticle(
                            "Password Reset Troubleshooting",
                            "If the reset email doesn't arrive in 10 minutes, check spam or request a new"
                                    + " SSO-backed link."),
                    new KbArticle(
                            "Two-Factor Recovery",
                            "Use a backup code from your authenticator app, or contact support to reset 2FA.")),
            "export_failure",
            List.of(
                    new KbArticle(
                            "Export Job Failures",
                            "A stuck export usually means a stale API token; reconnecting the integration"
                                    + " resolves it."),
                    new KbArticle(
                            "CSV/PDF Export Limits",
                            "Exports over 50k rows are split into multiple files" + " automatically.")),
            "how_to_question",
            List.of(
                    new KbArticle(
                            "Plan Upgrades",
                            "Upgrade from Settings > Billing at any time; charges are prorated to the day."),
                    new KbArticle(
                            "Inviting Teammates", "Workspace owners can invite teammates from Settings > Members.")),
            "bug_report",
            List.of(
                    new KbArticle(
                            "Known Dashboard Issues",
                            "Active incidents are tracked on the status page; most render glitches clear after"
                                    + " a hard refresh."),
                    new KbArticle(
                            "Reporting a Bug",
                            "Include the order id and a screenshot so engineering can reproduce it.")),
            "escalation_request",
            List.of(
                    new KbArticle(
                            "Escalation Path",
                            "Tickets unresolved after 48 hours are automatically routed to a senior specialist."),
                    new KbArticle(
                            "Priority Support SLA", "Priority-plan customers get a 4-hour first-response guarantee.")));

    private static final Map<String, String> RESOLUTION_TEMPLATE = Map.of(
            "billing_refund",
            "I've reviewed order {orderId} and processed a refund of {amount} to your original payment"
                    + " method for {customer} — it should post within 3-5 business days.",
            "login_access",
            "I've reset access on {customer}'s account and sent a fresh verification link — that should"
                    + " get them signed back in right away.",
            "export_integration",
            "I've re-run the export for order {orderId} and confirmed {product} is receiving data again;"
                    + " a PDF copy is attached below.",
            "how_to",
            "Here's how to do that on {product}: it's a couple of clicks in Settings, and I've written out"
                    + " the exact steps for {customer} above.",
            "bug_report",
            "I've reproduced the issue in {product} on order {orderId} and filed it with engineering; I'll"
                    + " follow up with {customer} once the fix ships.",
            "escalation",
            "I've escalated {customer}'s case on order {orderId} to a senior specialist given the impact on"
                    + " {product} — they'll take it from here.");

    private static final List<Template> TEMPLATES = List.of(
            // billing_refund: {product} is the line item that was billed
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Refund not received for order {orderId}",
                    "Hi, I'm {customer}. I was charged {amount} for {product} on {date} and requested a"
                            + " refund but haven't seen it land. Order {orderId}."),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Double charged for {product}",
                    "I'm {customer} and it looks like I was billed twice for {product} around {date} — order"
                            + " {orderId}, {amount} each time."),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Refund request — I cancelled before this renewed",
                    "I cancelled {product} on {date} but was still charged {amount}. Please refund order"
                            + " {orderId}. — {customer}"),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Wrong amount charged on order {orderId}",
                    "{customer} here — I expected less than {amount} for {product}, but order {orderId} on"
                            + " {date} shows the full price."),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Refund for order {orderId} still pending",
                    "It's been over a week since I requested a refund for {product} (order {orderId},"
                            + " {amount}). Any update? — {customer}"),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Billing dispute on the {date} charge",
                    "I don't recognize a {amount} charge for {product} on {date}. Can you look into order"
                            + " {orderId}? — {customer}"),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Charged for the wrong tier on order {orderId}",
                    "{customer} again — order {orderId} was meant to be a different tier, but I was charged"
                            + " {amount} for {product}. Requesting a refund."),
            new Template(
                    "billing_refund",
                    "refund_request",
                    Noun.ITEM,
                    "Accidental purchase on {date}",
                    "I accidentally purchased {product} on {date} for {amount}. Please refund order"
                            + " {orderId}. Thanks, {customer}"),
            // login_access: {product} is the plan the locked-out workspace is on
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Can't log into my account since {date}",
                    "{customer} here — I haven't been able to sign in since {date}. Our workspace is on"
                            + " {product}, order {orderId}."),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Locked out after a password reset",
                    "I reset my password on {date} and now I'm locked out entirely. {customer}, order"
                            + " {orderId}, on {product}."),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Two-factor code never arrives",
                    "The 2FA code never reaches my phone when I try to log in. — {customer}, on {product}"),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Account shows an 'access denied' error",
                    "{customer} — I've had an access-denied error since {date} on {product}, order" + " {orderId}."),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Forgot-password link isn't working",
                    "The password reset link 404s for me every time. Can you help? — {customer}, on" + " {product}"),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Session keeps expiring immediately",
                    "I get logged out within seconds of signing in, since around {date}. — {customer}, on"
                            + " {product}"),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "SSO login fails for our whole team",
                    "SSO fails with an unknown error for everyone on our team. {customer}, {product}, order"
                            + " {orderId}."),
            new Template(
                    "login_access",
                    "account_access",
                    Noun.PLAN,
                    "Account access revoked unexpectedly",
                    "My access was revoked without warning on {date}. {customer} here, on {product}, order"
                            + " {orderId}."),
            // export_integration: {product} is the third-party system on the other end
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "Data export for order {orderId} keeps failing",
                    "{customer} — every export of our {product} data on order {orderId} has failed since" + " {date}."),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "{product} integration broken since {date}",
                    "Our {product} integration stopped syncing on {date}. Order {orderId}. — {customer}"),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "CSV export returns an empty file",
                    "{customer} here — exporting our {product} records for order {orderId} gives an empty" + " CSV."),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "Webhooks stopped firing to {product}",
                    "Webhooks to {product} stopped arriving around {date}, order {orderId}. — {customer}"),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "API export request times out",
                    "{customer} — the export API times out on order {orderId} every time we pull for" + " {product}."),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "Export job stuck at 0% for order {orderId}",
                    "The {product} export job for order {orderId} has been stuck at 0% since {date}."
                            + " — {customer}"),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "{product} sync is showing stale data",
                    "{customer} — {product} hasn't received fresh data since {date}, order {orderId}."),
            new Template(
                    "export_integration",
                    "export_failure",
                    Noun.INTEGRATION,
                    "PDF export is missing line items",
                    "The PDF export for order {orderId} is missing line items, so {product} is out of"
                            + " balance. — {customer}"),
            // how_to: {product} is the plan the answer depends on
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I upgrade to {product}?",
                    "{customer} here, trying to work out how to move up to {product} from where we are" + " now."),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I change the billing email?",
                    "Where do I update the billing email on {product}, order {orderId}? — {customer}"),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "Where can I find my invoice from {date}?",
                    "{customer} — I need the invoice for {product} from {date}, order {orderId}."),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I add teammates to my workspace?",
                    "Trying to add teammates to our workspace on {product} — what's the flow? — {customer}"),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I cancel before the next renewal?",
                    "{customer} here, looking to cancel {product} (order {orderId}) before it renews."),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I set up SSO?",
                    "What's the setup flow for SSO on {product}? — {customer}"),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I download a usage report?",
                    "{customer} — where can I download the usage report for {product}, order {orderId}?"),
            new Template(
                    "how_to",
                    "how_to_question",
                    Noun.PLAN,
                    "How do I set up notifications?",
                    "Can you walk me through notification setup on {product}? — {customer}"),
            // bug_report: {product} is the screen or feature that misbehaves
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Crash every time I open {product}",
                    "{customer} — the app crashes every time I open {product}, order {orderId}, since" + " {date}."),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Order {orderId} shows the wrong status",
                    "Order {orderId} has shown the wrong status in {product} since {date}. — {customer}"),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Search returns no results at all",
                    "{customer} — search inside {product} has returned nothing since {date}."),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Duplicate charge appearing in billing history",
                    "There's a phantom duplicate of {amount} in my billing history under {product}, order"
                            + " {orderId}. — {customer}"),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Notifications stopped arriving on {date}",
                    "{customer} — notifications from {product} stopped arriving on {date}, order" + " {orderId}."),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Settings in {product} won't save",
                    "Every time I change a setting in {product} it reverts. — {customer}, order {orderId}"),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Broken layout on mobile for order {orderId}",
                    "{product} renders badly on mobile for order {orderId}, since {date}. — {customer}"),
            new Template(
                    "bug_report",
                    "bug_report",
                    Noun.AREA,
                    "Error 500 when submitting the support form",
                    "{customer} — submitting the support form from {product} throws a 500, order" + " {orderId}."),
            // escalation: {product} is the plan whose service level is being invoked
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Third ticket about order {orderId} — I need a manager",
                    "{customer} again. This is my third message about order {orderId} on {product}, and I'd"
                            + " like a manager."),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Extremely unhappy — please escalate this",
                    "{customer} — we've been unusable since {date} on {product} and I need this escalated,"
                            + " order {orderId}."),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "No response in 5 days on a refund for {amount}",
                    "Still no response after 5 days on my {amount} refund, order {orderId}, and we pay for"
                            + " {product}. — {customer}"),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Considering cancelling over this issue",
                    "{customer} — I'm considering cancelling {product} over the ongoing issue on order"
                            + " {orderId}."),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Urgent — we're down for the whole team",
                    "We've been down for our entire team since {date}, order {orderId}, on {product}."
                            + " Urgent. — {customer}"),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Escalating: the previous agent didn't resolve this",
                    "{customer} — the agent who helped on {date} didn't resolve this. Escalating order"
                            + " {orderId}, we're on {product}."),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Billing discrepancy on order {orderId} — needs resolving today",
                    "{customer} — there's a real discrepancy on order {orderId} ({amount}) and we're on"
                            + " {product}. This needs resolving today."),
            new Template(
                    "escalation",
                    "escalation_request",
                    Noun.PLAN,
                    "Repeated failures — we're considering switching",
                    "{customer} — repeated failures on order {orderId} have us considering switching"
                            + " providers off {product}."));

    private static final String[] FIRST_NAMES = {
        "Maya", "Diego", "Priya", "Ethan", "Aiko", "Lucas", "Fatima", "Noah", "Elena", "Sam", "Zara", "Miguel",
        "Ingrid", "Kofi", "Yuki"
    };
    private static final String[] LAST_NAMES = {
        "Chen",
        "Alvarez",
        "Patel",
        "Kowalski",
        "Nakamura",
        "Okafor",
        "Rossi",
        "Larsen",
        "Haddad",
        "Silva",
        "Novak",
        "Reyes",
        "Iwasaki",
        "Brennan",
        "Mensah"
    };
    private static final String[] PLANS = {
        "the Starter plan", "the Pro plan", "the Team plan", "the Business plan", "the Enterprise plan"
    };
    private static final String[] INTEGRATIONS = {
        "Salesforce", "Slack", "HubSpot", "Zapier", "Snowflake", "Google Sheets", "Segment", "Notion"
    };
    private static final String[] AREAS = {
        "Dashboard", "Reports", "Billing", "Search", "Notifications", "Exports", "Audit log", "Team settings"
    };
    private static final String[] ITEMS = {
        "the Pro plan renewal",
        "500,000 API credits",
        "5 extra seats",
        "the Analytics add-on",
        "extra storage",
        "the annual Business plan",
        "the Priority Support add-on",
        "a custom domain"
    };

    private static String[] vocabulary(Noun noun) {
        return switch (noun) {
            case PLAN -> PLANS;
            case INTEGRATION -> INTEGRATIONS;
            case AREA -> AREAS;
            case ITEM -> ITEMS;
        };
    }

    /**
     * Openers a customer's earlier messages on the same thread might have carried. Real thread history
     * is not one sentence repeated N times, and the point of this list is what {@code
     * assemble_ticket_context} is shown to be forwarding once the drift starts: a reader who opens one
     * of those spans has to see something that reads like a conversation.
     */
    private static final String[] PRIOR_MESSAGES = {
        "Following up on this, still not resolved.",
        "Adding the screenshot I mentioned.",
        "Any update? This is blocking us.",
        "Your colleague asked for the order id — it's below.",
        "Reposting since the last thread was closed without a reply.",
        "We tried the workaround in the docs and it didn't help.",
        "Confirming this is still happening today.",
        "Looping in our account owner on this one."
    };

    /**
     * What a reply adds that its knowledge-base articles never support, per category that searches the
     * knowledge base, and what the customer writes back once it turns out to be false.
     *
     * @param contradicts whether the promise contradicts an article, rather than going beyond them
     */
    private record Promise(String sentence, String complaint, boolean contradicts) {}

    private static final Map<String, Promise> PROMISES = Map.of(
            "billing_refund",
            new Promise(
                    "Your refund will be back on your card within 24 hours.",
                    "You told me the refund would be back on my card within 24 hours. It's been three days and"
                            + " nothing has arrived. Why did you say that?",
                    true),
            "login_access",
            new Promise(
                    "I've also added a free month to your plan for the trouble.",
                    "Where is the free month you said you added? My invoice shows the full price again. That"
                            + " isn't what you told me.",
                    false),
            "export_integration",
            new Promise(
                    "Exports no longer have a row limit, so this won't happen again.",
                    "You said exports had no row limit now. It split the file again and our import broke."
                            + " Please stop guessing.",
                    true),
            "how_to",
            new Promise(
                    "Plan upgrades are free for the first 30 days.",
                    "You said upgrades were free for 30 days. I was just charged the full amount. That was" + " wrong.",
                    true),
            "bug_report",
            new Promise(
                    "Engineering already shipped a fix for this, so it should work now.",
                    "You said engineering already shipped a fix. It's still broken. Did anyone actually check?",
                    false));

    /** The customer's first reply on a thread, before anything has gone wrong. */
    private static final String[] FOLLOW_UPS = {
        "Thanks. Is there anything I need to do on my side?",
        "Okay. Can you confirm this is tied to order {orderId}?",
        "Got it. How will I know when it's done?",
        "Thanks for the quick reply. Should I keep this ticket open?"
    };

    private static final String FOLLOW_UP_REPLY =
            "Nothing else is needed from you, {customer}. I'll keep this ticket open until it's confirmed on your"
                    + " side.";

    /** The customer's last reply when the ticket went fine. */
    private static final String[] CALM_CLOSERS = {
        "That fixed it, thanks.", "All good now, I appreciate the help.", "Great, I'll reply here if it happens again."
    };

    /** The customer's last reply when they are annoyed with the agent for a reason other than a promise. */
    private static final String[] ANNOYED_CLOSERS = {
        "This is the third time I've explained this. Please read what I wrote before replying.",
        "You keep sending me the same answer and it doesn't help."
    };

    private static final String CALM_REPLY = "Glad to hear it, {customer}. I'll close the ticket. Reply here any time.";

    private static final String APOLOGY_REPLY =
            "I'm sorry, {customer}. I've passed order {orderId} to a senior specialist to check what went wrong.";

    // ---- baseline operating point per call site ------------------------------------------------

    static final double CLASSIFY_BASE_INPUT_TOKENS = 190;
    private static final double CLASSIFY_BASE_OUTPUT_TOKENS = 25;
    private static final double CLASSIFY_PRICE_IN = 0.00000015;
    private static final double CLASSIFY_PRICE_OUT = 0.0000006;
    private static final long CLASSIFY_BASE_LATENCY_MS = 420;

    private static final double GENERATE_BASE_INPUT_TOKENS = 820;
    private static final double GENERATE_BASE_OUTPUT_TOKENS = 230;
    private static final double GENERATE_PRICE_IN = 0.0000025;
    private static final double GENERATE_PRICE_OUT = 0.00001;
    private static final long GENERATE_BASE_LATENCY_MS = 1400;

    private static final long ASSEMBLE_BASE_LATENCY_MS = 130;
    private static final long KB_BASE_LATENCY_MS = 280;
    private static final long REFUND_BASE_LATENCY_MS = 380;
    private static final long ESCALATE_BASE_LATENCY_MS = 420;
    private static final long EXPORT_BASE_LATENCY_MS = 340;

    /**
     * What {@code refund_api} and {@code ticket_escalation} fail at when nothing is wrong: the
     * reference the drift is measured against, so it has to be a rate the reference window can
     * actually EXHIBIT. At 1% it could not: 38 pre-onset refund calls drew zero failures, and the
     * finding then claimed a move "from 0.0%", which is not a rate a tool has, it is a window too
     * small to have seen one.
     */
    private static final double TOOL_BASELINE_ERROR_RATE = 0.04;

    // ---- drift shapes (design spec) --------------------------------------------------------

    /** Day (1..14, 14 = today) the classify_intent cost stepped, per the design spec. */
    private static final int CLASSIFY_STEP_DAY = 10;

    /**
     * Where {@code generate_response}'s smooth cost ramp is cut into a before and an after. Unlike the
     * other five rows there is no step to find here: the ramp is continuous by design, one call site
     * that is drifting rather than one that broke, so this is a reporting split, not an onset.
     */
    private static final int GENERATE_SPLIT_DAY = 8;

    private static final int DURATION_RAMP_START_DAY = 10;
    private static final int TOOL_ERROR_RAMP_START_DAY = 11;

    /** Day the replies start carrying unsupported promises: the onset of both the groundedness and the
     *  frustration finding. */
    static final int UNGROUNDED_ONSET_DAY = 11;

    /** Share of knowledge-base replies carrying a promise before and from {@link #UNGROUNDED_ONSET_DAY}. */
    private static final double BASELINE_PROMISE_RATE = 0.03;

    private static final double DRIFT_PROMISE_RATE = 0.22;

    /**
     * Share of tickets the customer writes back on. Higher after a promise, because a customer promised
     * something is the one who checks. Sized so the frustration reference holds about 50 threads: short of
     * the 200 the live classifier learns over, which a 14-day sample cannot reach without swamping the rest.
     */
    private static final double THREAD_SHARE = 0.17;

    private static final double THREAD_SHARE_PROMISED = 0.6;

    private static final double FRUSTRATED_IF_PROMISED = 0.75;
    private static final double FRUSTRATED_OTHERWISE = 0.04;

    /** Turns in a thread: the third user message is the first one with four messages before it, the
     *  earliest turn Frustration scores. */
    private static final int TURNS_PER_THREAD = 3;

    /** A customer's reply is answered straight from the thread, with no fresh search or tool call. */
    private static final String[] FOLLOW_UP_CHAIN = {ASSEMBLE, CLASSIFY, GENERATE};

    /** The groundedness flag cutoff; a flagged answer scores at or above it, a clean one well below. */
    private static final double GROUNDEDNESS_THRESHOLD = 0.975;

    /** The frustration flag cutoff; a turn is flagged above it. */
    private static final double FRUSTRATION_THRESHOLD = 0.40;

    private static double costMultiplier(String callSite, int day) {
        if (CLASSIFY.equals(callSite)) {
            return day < CLASSIFY_STEP_DAY ? 1.0 : 1.95;
        }
        return 1.0 + (day - 1) / (double) (WINDOW_DAYS - 1) * 1.3;
    }

    private static double durationMultiplier(String callSite, int day) {
        if (day >= DURATION_RAMP_START_DAY) {
            double fraction = Math.min(1.0, (day - (DURATION_RAMP_START_DAY - 1)) / 5.0);
            return 1.0 + fraction * (KB_SEARCH.equals(callSite) ? 1.2 : 1.1);
        }
        return 1.0;
    }

    private static double errorRate(int day) {
        if (day >= TOOL_ERROR_RAMP_START_DAY) {
            double fraction = Math.min(1.0, (day - (TOOL_ERROR_RAMP_START_DAY - 1)) / 4.0);
            return TOOL_BASELINE_ERROR_RATE + fraction * 0.20;
        }
        return TOOL_BASELINE_ERROR_RATE;
    }

    // ---- output shape ---------------------------------------------------------------------

    record MediaAttach(String traceId, String spanId, String mediaId) {}

    /** One drift row's real, generated-data statistics, in the design spec's array order. */
    record DriftStat(
            String classifierKey,
            String callSiteId,
            String subjectId,
            String subjectLabel,
            String measure,
            String direction,
            String reference,
            double meanPre,
            double meanPost,
            long nPre,
            long nPost,
            String onsetAt,
            String lastSeenAt,
            List<String> sampleTraceIdsPost,
            /**
             * The finding's evidence, in the three roles {@code RcaAnalysisService.sides} splits on.
             * {@code baseline} is what the drift was measured against (the days before onset);
             * {@code member} is the population being complained about; {@code witness} is the failing
             * subset of it, which only {@code tool_error} draws; a metric-drift finding leaves it
             * empty and its members ARE the flagged side.
             *
             * <p>Without these a seeded finding has no {@code finding_evidence} rows, and RCA on it
             * fails before it starts: the sample project's whole point is a Run RCA press that works.
             */
            List<String> baselineTraceIds,
            List<String> memberTraceIds,
            List<String> witnessTraceIds) {

        double ratio() {
            return meanPre <= 0 ? 1.0 : meanPost / meanPre;
        }
    }

    /**
     * One {@code generate_response} answer the groundedness model scored, as its assessment and, when
     * flagged, its detection need it.
     *
     * @param flagged the unsupported sentence, in UTF-16 offsets into the answer; null when the answer passed
     */
    record ScoredAnswer(
            String traceId,
            String spanId,
            @Nullable String sessionId,
            Instant startedAt,
            double unsupported,
            double conflict,
            @Nullable FlaggedSentence flagged) {}

    record FlaggedSentence(int start, int end, String text) {}

    /** One thread's third user turn, the one the frustration model scored, with the state it was sent. */
    record ScoredTurn(
            String conversationId,
            String traceId,
            String rootSpanId,
            Instant startedAt,
            double score,
            boolean frustrated,
            FrustrationTurnBuilder.TurnState state) {}

    /**
     * One rate test's two windows, counted from the generated rows: trials before the onset (the learned
     * reference) and since it, with the failures in each, and the evidence refs the live service writes.
     *
     * @param lastBucket the hour of the newest trial, the spell's last folded hour
     */
    record RateStat(
            String callSiteId,
            Instant onset,
            Instant lastBucket,
            long baselineTrials,
            long baselineFailures,
            long trialsSinceOnset,
            long failuresSinceOnset,
            List<FindingEvidenceRepository.Ref> members,
            List<FindingEvidenceRepository.Ref> witnesses) {}

    record Dataset(
            List<TraceV2Row> traces,
            List<SpanRow> spans,
            List<SpanPayloadRow> payloads,
            List<MediaAttach> mediaAttachments,
            List<DriftStat> driftStats,
            List<SessionRow> sessions,
            List<RetrievedDocRow> retrievedDocs,
            List<ScoredAnswer> answers,
            List<ScoredTurn> turns,
            RateStat groundedness,
            RateStat frustration) {}

    // ---- generation -------------------------------------------------------------------------

    /**
     * @param exportPdfMediaId the already-stored media id every {@code export_ticket} span's
     *     {@code document_ref} block points at: one real {@code media_object} row for the whole
     *     dataset (content-addressed dedup makes this the correct outcome even if it were stored
     *     per-trace: every export carries the same placeholder PDF).
     * @param screenshotPngMediaId likewise for the {@code image_ref} block on every
     *     {@code assemble_ticket_context} span: the screenshot a customer attached to their ticket.
     */
    static Dataset generate(
            String projectId, long seed, Instant now, String exportPdfMediaId, String screenshotPngMediaId) {
        Random rnd = new Random(seed);
        ObjectMapper mapper = new ObjectMapper();
        Instant todayMidnight = now.truncatedTo(ChronoUnit.DAYS);
        DateTimeFormatter dateFmt =
                DateTimeFormatter.ofPattern("MMM d", Locale.ROOT).withZone(ZoneOffset.UTC);

        List<TraceV2Row> traces = new ArrayList<>();
        List<SpanRow> spans = new ArrayList<>();
        List<SpanPayloadRow> payloads = new ArrayList<>();
        List<MediaAttach> mediaAttachments = new ArrayList<>();

        var classifyCost = new DayAccumulator();
        var generateCost = new DayAccumulator();
        var kbDuration = new DayAccumulator();
        var refundDuration = new DayAccumulator();
        var refundErrors = new DayErrorAccumulator();
        var escalateErrors = new DayErrorAccumulator();
        var refundFailures = new RateSchedule();
        var escalateFailures = new RateSchedule();
        List<String> classifyPostStepTraceIds = new ArrayList<>();
        List<SessionRow> sessions = new ArrayList<>();
        List<RetrievedDocRow> retrievedDocs = new ArrayList<>();
        List<ScoredAnswer> answers = new ArrayList<>();
        List<ScoredTurn> turnsScored = new ArrayList<>();

        for (int day = 1; day <= WINDOW_DAYS; day++) {
            int daysAgo = WINDOW_DAYS - day;
            Instant dayStart = todayMidnight.minus(daysAgo, ChronoUnit.DAYS);
            long capSeconds = daysAgo == 0 ? Math.max(60, now.getEpochSecond() - dayStart.getEpochSecond()) : 86_400;
            int tracesThisDay = day * 5;

            for (int i = 0; i < tracesThisDay; i++) {
                Template template = TEMPLATES.get(rnd.nextInt(TEMPLATES.size()));
                String customer =
                        FIRST_NAMES[rnd.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[rnd.nextInt(LAST_NAMES.length)];
                String[] vocabulary = vocabulary(template.noun());
                String product = vocabulary[rnd.nextInt(vocabulary.length)];
                String orderId = "ORD-" + (10000 + rnd.nextInt(89999));
                double amountValue = 12 + rnd.nextDouble() * 468;
                String amount = String.format(Locale.ROOT, "$%.2f", amountValue);
                long offsetSeconds = (long) (rnd.nextDouble() * capSeconds);
                Instant ticketStart = dayStart.plusSeconds(offsetSeconds);
                // The date a ticket REFERS to is always before the ticket itself. Formatting the trace's
                // own start here made every ticket say "I was charged on <today>, and it still hasn't
                // been refunded", a complaint about something that had not happened yet.
                String date = dateFmt.format(ticketStart.minus(2 + rnd.nextInt(9), ChronoUnit.DAYS));

                String ticketSubject = fill(template.subject(), customer, orderId, product, amount, date);
                String ticketBody = fill(template.body(), customer, orderId, product, amount, date);

                String[] ticketChain = chainFor(template.category(), rnd);

                Promise promise = PROMISES.get(template.category());
                boolean searchesKb = List.of(ticketChain).contains(KB_SEARCH);
                boolean promised = promise != null
                        && searchesKb
                        && rnd.nextDouble() < (day < UNGROUNDED_ONSET_DAY ? BASELINE_PROMISE_RATE : DRIFT_PROMISE_RATE);
                boolean threaded = rnd.nextDouble() < (promised ? THREAD_SHARE_PROMISED : THREAD_SHARE);
                boolean frustrated =
                        threaded && rnd.nextDouble() < (promised ? FRUSTRATED_IF_PROMISED : FRUSTRATED_OTHERWISE);
                @Nullable String sessionId = threaded ? Ids.ulid() : null;
                int turns = threaded ? TURNS_PER_THREAD : 1;
                boolean threadRetrieved = false;
                List<FrustrationTurnBuilder.EarlierMessage> history = new ArrayList<>();
                Instant turnStart = ticketStart;
                Instant lastActivity = ticketStart;

                for (int turn = 0; turn < turns; turn++) {
                    // A reply that would land after now is one the customer has not sent yet.
                    if (turn > 0 && turnStart.plusSeconds(30).isAfter(now)) break;
                    Instant traceStart = turnStart;
                    String subject = turn == 0 ? ticketSubject : "Re: " + ticketSubject;
                    String[] chain = turn == 0 ? ticketChain : FOLLOW_UP_CHAIN;
                    String body;
                    String reply;
                    if (turn == 0) {
                        body = ticketBody;
                        reply = fill(
                                RESOLUTION_TEMPLATE.getOrDefault(
                                        template.category(), "Thanks for reaching out, {customer}."),
                                customer,
                                orderId,
                                product,
                                amount,
                                date);
                        if (promised) reply = reply + " " + promise.sentence();
                    } else if (turn == 1) {
                        body = fill(
                                FOLLOW_UPS[rnd.nextInt(FOLLOW_UPS.length)], customer, orderId, product, amount, date);
                        reply = fill(FOLLOW_UP_REPLY, customer, orderId, product, amount, date);
                    } else {
                        body = !frustrated
                                ? CALM_CLOSERS[rnd.nextInt(CALM_CLOSERS.length)]
                                : promised ? promise.complaint() : ANNOYED_CLOSERS[rnd.nextInt(ANNOYED_CLOSERS.length)];
                        reply = fill(frustrated ? APOLOGY_REPLY : CALM_REPLY, customer, orderId, product, amount, date);
                    }
                    @Nullable Promise flaggedPromise = turn == 0 && promised ? promise : null;
                    @Nullable String rootSpanId = null;

                    String traceId = Ids.ulid();
                    Instant cursor = traceStart;
                    @Nullable String parentSpanId = null;
                    @Nullable String parentPath = null;
                    @Nullable String firstInputPreview = null;
                    @Nullable String lastOutputPreview = null;
                    int errorCount = 0;
                    long totalInputTokens = 0;
                    long totalOutputTokens = 0;
                    BigDecimal totalInputCost = BigDecimal.ZERO;
                    BigDecimal totalOutputCost = BigDecimal.ZERO;
                    boolean anyPriced = false;

                    String assembleOutput = null;
                    String classifyOutput = null;
                    String intentLabel = template.intent();
                    String kbOutput = null;

                    for (int stepIdx = 0; stepIdx < chain.length; stepIdx++) {
                        String callSite = chain[stepIdx];
                        String spanId = Ids.ulid();
                        if (stepIdx == 0) rootSpanId = spanId;
                        String path = parentPath == null ? spanId : parentPath + "." + spanId;

                        String kind;
                        String status = "ok";
                        String errorType = null;
                        String errorMessage = null;
                        String model = null;
                        Long inputTokens = null;
                        Long outputTokens = null;
                        String inputCost = null;
                        String outputCost = null;
                        String costSource = SpanRow.CostSource.UNPRICED;
                        long latencyMs;
                        String input;
                        String output;
                        // What the trace and span LISTS show. Defaulted to the payload itself, and
                        // overridden by the two steps whose payload is a wire-shaped JSON message array:
                        // truncating that to 240 characters put `[{"role":"user","content":[{"type":"te`
                        // in the column a reader scans to find a ticket.
                        @Nullable String inputPreview = null;
                        @Nullable String outputPreview = null;
                        @Nullable String attachedMediaId = null;

                        switch (callSite) {
                            case ASSEMBLE -> {
                                kind = KindNormalizer.TOOL;
                                latencyMs = jitter(rnd, ASSEMBLE_BASE_LATENCY_MS, 0.2);
                                int priorMessages = day < CLASSIFY_STEP_DAY ? 1 : 3 + rnd.nextInt(5);
                                // An image_ref against a real stored media object, not a dead URL: a support
                                // ticket usually does arrive with a screenshot, and this is the only place
                                // the sample project exercises the image viewer at all (export_ticket
                                // demonstrates document_ref; nothing else demonstrates the image path).
                                // Nothing is fetched over the network, so there is no broken chip to render.
                                input = toMessageJson(
                                        mapper,
                                        "user",
                                        List.of(
                                                ContentBlock.text(body),
                                                ContentBlock.imageRef(screenshotPngMediaId, "image/png")));
                                // Set whether or not the image stays: a lone text block is still a wire
                                // message ARRAY, so the trace list showed `[{"role":"user","content":...`
                                // either way.
                                inputPreview = body;
                                attachedMediaId = screenshotPngMediaId;
                                StringBuilder threadHistory = new StringBuilder();
                                for (int m = 0; m < priorMessages; m++) {
                                    threadHistory
                                            .append("[")
                                            .append(dateFmt.format(
                                                    traceStart.minus(priorMessages - m, ChronoUnit.DAYS)))
                                            .append("] ")
                                            .append(customer)
                                            .append(": ")
                                            .append(PRIOR_MESSAGES[rnd.nextInt(PRIOR_MESSAGES.length)])
                                            .append(" ");
                                }
                                output = "Assembled context for " + customer + " · order " + orderId + " · "
                                        + priorMessages
                                        + (priorMessages == 1 ? " prior thread message: " : " prior thread messages: ")
                                        + threadHistory.toString().strip();
                                assembleOutput = output;
                            }
                            case CLASSIFY -> {
                                kind = KindNormalizer.LLM;
                                model = "gpt-4o-mini";
                                double mult = costMultiplier(CLASSIFY, day);
                                double inTok = jitterD(rnd, CLASSIFY_BASE_INPUT_TOKENS * mult, 0.12);
                                double outTok = jitterD(rnd, CLASSIFY_BASE_OUTPUT_TOKENS, 0.12);
                                inputTokens = Math.round(inTok);
                                outputTokens = Math.round(outTok);
                                BigDecimal inC = money(inputTokens * CLASSIFY_PRICE_IN);
                                BigDecimal outC = money(outputTokens * CLASSIFY_PRICE_OUT);
                                inputCost = inC.toPlainString();
                                outputCost = outC.toPlainString();
                                costSource = SpanRow.CostSource.PROVIDED;
                                latencyMs = jitter(rnd, CLASSIFY_BASE_LATENCY_MS, 0.15);
                                input = "Ticket: " + subject + "\n\n"
                                        + (assembleOutput == null ? body : assembleOutput);
                                classifyOutput = "intent: " + intentLabel + " (confidence "
                                        + String.format(Locale.ROOT, "%.2f", 0.86 + rnd.nextDouble() * 0.12) + ")";
                                output = classifyOutput;
                                classifyCost.add(
                                        day,
                                        inputTokens * CLASSIFY_PRICE_IN + outputTokens * CLASSIFY_PRICE_OUT,
                                        traceId);
                                if (day >= CLASSIFY_STEP_DAY && classifyPostStepTraceIds.size() < 6) {
                                    classifyPostStepTraceIds.add(traceId);
                                }
                                totalInputTokens += inputTokens;
                                totalOutputTokens += outputTokens;
                                totalInputCost = totalInputCost.add(inC);
                                totalOutputCost = totalOutputCost.add(outC);
                                anyPriced = true;
                            }
                            case KB_SEARCH -> {
                                kind = KindNormalizer.RETRIEVAL;
                                double mult = durationMultiplier(KB_SEARCH, day);
                                latencyMs = Math.round(jitterD(rnd, KB_BASE_LATENCY_MS * mult, 0.15));
                                kbDuration.add(day, latencyMs, traceId);
                                List<KbArticle> articles = KB_ARTICLES.getOrDefault(intentLabel, List.of());
                                input = "query: " + intentLabel.replace('_', ' ') + " " + product;
                                StringBuilder sb = new StringBuilder();
                                for (KbArticle a : articles) {
                                    sb.append(a.title())
                                            .append(" — ")
                                            .append(a.snippet())
                                            .append(" ");
                                }
                                kbOutput = sb.toString();
                                output = kbOutput;
                            }
                            case GENERATE -> {
                                kind = KindNormalizer.LLM;
                                model = "gpt-4o";
                                double mult = costMultiplier(GENERATE, day);
                                double inTok = jitterD(rnd, GENERATE_BASE_INPUT_TOKENS * mult, 0.15);
                                double outTok = jitterD(rnd, GENERATE_BASE_OUTPUT_TOKENS, 0.15);
                                inputTokens = Math.round(inTok);
                                outputTokens = Math.round(outTok);
                                BigDecimal inC = money(inputTokens * GENERATE_PRICE_IN);
                                BigDecimal outC = money(outputTokens * GENERATE_PRICE_OUT);
                                inputCost = inC.toPlainString();
                                outputCost = outC.toPlainString();
                                costSource = SpanRow.CostSource.PROVIDED;
                                latencyMs = jitter(rnd, GENERATE_BASE_LATENCY_MS, 0.2);
                                // The instructions and the articles as the system message and the customer's
                                // text as the user message, so what a reader sees as the question the answer
                                // replied to is the customer's words, not the prompt around them.
                                input = toMessagesJson(
                                        mapper,
                                        List.of(
                                                new WireMessage(
                                                        "system",
                                                        List.of(ContentBlock.text("Classified intent: " + intentLabel
                                                                + "\n\nRelevant KB:\n"
                                                                + (kbOutput == null ? "(none)" : kbOutput)))),
                                                new WireMessage("user", List.of(ContentBlock.text(body)))));
                                inputPreview = body;
                                output = reply;
                                generateCost.add(
                                        day,
                                        inputTokens * GENERATE_PRICE_IN + outputTokens * GENERATE_PRICE_OUT,
                                        traceId);
                                totalInputTokens += inputTokens;
                                totalOutputTokens += outputTokens;
                                totalInputCost = totalInputCost.add(inC);
                                totalOutputCost = totalOutputCost.add(outC);
                                anyPriced = true;
                            }
                            case REFUND -> {
                                kind = KindNormalizer.TOOL;
                                double mult = durationMultiplier(REFUND, day);
                                latencyMs = Math.round(jitterD(rnd, REFUND_BASE_LATENCY_MS * mult, 0.15));
                                refundDuration.add(day, latencyMs, traceId);
                                boolean failed = refundFailures.next(errorRate(day));
                                refundErrors.add(day, failed, traceId);
                                input = "order_id=" + orderId + " amount=" + amount + " customer=\"" + customer + "\"";
                                if (failed) {
                                    status = "error";
                                    errorType = "validation_error";
                                    errorMessage = "amount exceeds refund window for order " + orderId;
                                    output = "status=error code=validation_error message=\"" + errorMessage + "\"";
                                    errorCount++;
                                } else {
                                    output = "status=approved refund_id=RFND-" + (100000 + rnd.nextInt(899999));
                                }
                            }
                            case ESCALATE -> {
                                kind = KindNormalizer.TOOL;
                                latencyMs = jitter(rnd, ESCALATE_BASE_LATENCY_MS, 0.15);
                                boolean failed = escalateFailures.next(errorRate(day));
                                escalateErrors.add(day, failed, traceId);
                                input = "ticket_id=" + orderId + " priority=high reason=" + intentLabel;
                                if (failed) {
                                    status = "error";
                                    errorType = "timeout";
                                    errorMessage = "ticketing system did not respond within 10s for order " + orderId;
                                    output = "status=error code=timeout message=\"" + errorMessage + "\"";
                                    errorCount++;
                                } else {
                                    output = "status=escalated assigned_to=tier2 escalation_id=ESC-"
                                            + (100000 + rnd.nextInt(899999));
                                }
                            }
                            default -> {
                                kind = KindNormalizer.TOOL;
                                latencyMs = jitter(rnd, EXPORT_BASE_LATENCY_MS, 0.15);
                                input = "export order " + orderId + " as pdf";
                                String extracted = "Ticket Export — Order " + orderId + "\nCustomer: " + customer
                                        + "\nProduct: " + product + "\nStatus: resolved";
                                output = toMessageJson(
                                        mapper,
                                        "assistant",
                                        List.of(
                                                ContentBlock.text("Export ready for order " + orderId + "."),
                                                new ContentBlock(
                                                        ContentBlock.TYPE_DOCUMENT_REF,
                                                        extracted,
                                                        null,
                                                        exportPdfMediaId,
                                                        "application/pdf")));
                                outputPreview = "Export ready for order " + orderId + ". (PDF attached)";
                                attachedMediaId = exportPdfMediaId;
                            }
                        }

                        Instant spanStart = cursor;
                        Instant spanEnd = spanStart.plusMillis(latencyMs);
                        cursor = spanEnd.plusMillis(30 + rnd.nextInt(120));

                        spans.add(new SpanRow(
                                projectId,
                                traceId,
                                spanId,
                                parentSpanId,
                                path,
                                sessionId,
                                null,
                                null,
                                callSite,
                                subject,
                                kind,
                                callSite,
                                stepIdx == 0,
                                status,
                                null,
                                errorType,
                                errorMessage,
                                spanStart.toString(),
                                spanEnd.toString(),
                                latencyMs,
                                null,
                                model,
                                null,
                                inputTokens,
                                outputTokens,
                                null,
                                null,
                                null,
                                inputCost,
                                outputCost,
                                null,
                                null,
                                costSource,
                                null,
                                preview(inputPreview == null ? input : inputPreview),
                                preview(outputPreview == null ? output : outputPreview),
                                SpanRow.ResolverState.NONE,
                                SpanRow.ResolverState.RESOLVED,
                                spanStart.toString(),
                                false,
                                null,
                                null,
                                null,
                                null));

                        payloads.add(new SpanPayloadRow(
                                projectId, traceId, spanId, input, output, null, null, spanStart.toString()));

                        if (KB_SEARCH.equals(callSite)) {
                            List<KbArticle> articles = KB_ARTICLES.getOrDefault(intentLabel, List.of());
                            for (int a = 0; a < articles.size(); a++) {
                                retrievedDocs.add(new RetrievedDocRow(
                                        Ids.ulid(),
                                        projectId,
                                        a,
                                        RetrievedDocRow.ListRole.RESULT,
                                        a + 1,
                                        "kb-" + intentLabel + "-" + (a + 1),
                                        articles.get(a).title(),
                                        articles.get(a).snippet(),
                                        0.92 - a * 0.11,
                                        null,
                                        null,
                                        null,
                                        spanId,
                                        spanStart.toString(),
                                        false,
                                        spanStart.toString(),
                                        traceId,
                                        spanId));
                            }
                            threadRetrieved = true;
                        }
                        if (GENERATE.equals(callSite) && threadRetrieved) {
                            answers.add(
                                    scoredAnswer(rnd, traceId, spanId, sessionId, spanStart, output, flaggedPromise));
                        }

                        if (attachedMediaId != null) {
                            mediaAttachments.add(new MediaAttach(traceId, spanId, attachedMediaId));
                        }
                        if (firstInputPreview == null) {
                            firstInputPreview = preview(inputPreview == null ? input : inputPreview);
                        }
                        lastOutputPreview = preview(outputPreview == null ? output : outputPreview);

                        parentSpanId = spanId;
                        parentPath = path;
                    }

                    Instant traceEnd = cursor;
                    traces.add(new TraceV2Row(
                            projectId,
                            traceId,
                            sessionId,
                            null,
                            null,
                            subject,
                            null,
                            null,
                            // status. Left null, a trace carrying a failed refund_api span rendered with no
                            // status at all next to the error count that contradicts it.
                            errorCount > 0 ? "error" : "ok",
                            traceStart.toString(),
                            traceEnd.toString(),
                            null,
                            chain.length,
                            errorCount,
                            anyPriced ? totalInputTokens : null,
                            anyPriced ? totalOutputTokens : null,
                            null,
                            null,
                            null,
                            anyPriced ? totalInputTokens + totalOutputTokens : null,
                            anyPriced ? totalInputCost.toPlainString() : null,
                            anyPriced ? totalOutputCost.toPlainString() : null,
                            anyPriced ? totalInputCost.add(totalOutputCost).toPlainString() : null,
                            0,
                            firstInputPreview,
                            lastOutputPreview,
                            ASSEMBLE,
                            null,
                            now.toString(),
                            traceEnd.toString(),
                            true,
                            true,
                            traceStart.toString(),
                            false));

                    if (sessionId != null) {
                        if (turn == TURNS_PER_THREAD - 1 && rootSpanId != null) {
                            double score = frustrated ? 0.55 + rnd.nextDouble() * 0.4 : 0.01 + rnd.nextDouble() * 0.27;
                            turnsScored.add(new ScoredTurn(
                                    sessionId,
                                    traceId,
                                    rootSpanId,
                                    traceStart,
                                    score,
                                    score > FRUSTRATION_THRESHOLD,
                                    new FrustrationTurnBuilder.TurnState(body, history)));
                        }
                        history.add(new FrustrationTurnBuilder.EarlierMessage("user", body));
                        history.add(new FrustrationTurnBuilder.EarlierMessage("assistant", reply));
                    }
                    turnStart = traceEnd.plus(4 + rnd.nextInt(37), ChronoUnit.MINUTES);
                    lastActivity = traceEnd;
                }
                if (sessionId != null) {
                    sessions.add(new SessionRow(
                            projectId,
                            sessionId,
                            null,
                            ticketStart.toString(),
                            lastActivity.toString(),
                            ticketStart.toString(),
                            false));
                }
            }
        }

        List<DriftStat> driftStats = List.of(
                classifyCost.stat(
                        "cost_drift",
                        CLASSIFY,
                        "cost",
                        "previous",
                        CLASSIFY_STEP_DAY,
                        todayMidnight,
                        now,
                        classifyPostStepTraceIds),
                // "previous", like every other row here, because it has to be TRUE: the reference this
                // seeder actually computes is the call site's own earlier days, and the baseline row it
                // writes is armed with nothing pinned. Claiming "pinned" put the words "versus its own
                // recent window (pinned reference)" into the finding's basis, a sentence contradicting
                // itself, over a comparison the UI could not have shown.
                generateCost.stat(
                        "cost_drift", GENERATE, "cost", "previous", GENERATE_SPLIT_DAY, todayMidnight, now, List.of()),
                kbDuration.stat(
                        "duration_drift",
                        KB_SEARCH,
                        "turn_duration",
                        "previous",
                        DURATION_RAMP_START_DAY,
                        todayMidnight,
                        now,
                        List.of()),
                refundDuration.stat(
                        "duration_drift",
                        REFUND,
                        "turn_duration",
                        "previous",
                        DURATION_RAMP_START_DAY,
                        todayMidnight,
                        now,
                        List.of()),
                refundErrors.stat(REFUND, TOOL_ERROR_RAMP_START_DAY, todayMidnight, now),
                escalateErrors.stat(ESCALATE, TOOL_ERROR_RAMP_START_DAY, todayMidnight, now));

        Instant ungroundedOnset = todayMidnight.minus(WINDOW_DAYS - UNGROUNDED_ONSET_DAY, ChronoUnit.DAYS);
        return new Dataset(
                traces,
                spans,
                payloads,
                mediaAttachments,
                driftStats,
                sessions,
                retrievedDocs,
                answers,
                turnsScored,
                groundednessRate(answers, ungroundedOnset),
                frustrationRate(turnsScored, ungroundedOnset));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Which call sites a ticket of this category runs through, end to end.
     *
     * <p>Every branch reaches {@code generate_response}: a trace that stopped after
     * {@code classify_intent} with an {@code ok} status on every span and no reply to the customer
     * is not an abandoned conversation a reader can interpret, it is a trace that looks like the
     * seed ran out halfway. If this dataset ever wants to show an abandoned ticket, it has to carry
     * the error or the cancellation that says so.
     */
    private static String[] chainFor(String category, Random rnd) {
        double roll = rnd.nextDouble();
        return switch (category) {
            case "billing_refund" ->
                roll < 0.7
                        ? new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE, REFUND}
                        : new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE};
            case "login_access" ->
                roll < 0.75
                        ? new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE}
                        : new String[] {ASSEMBLE, CLASSIFY, GENERATE};
            case "export_integration" -> new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE, EXPORT};
            case "how_to" ->
                roll < 0.7
                        ? new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE}
                        : new String[] {ASSEMBLE, CLASSIFY, GENERATE};
            case "bug_report" ->
                roll < 0.6
                        ? new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE, ESCALATE}
                        : new String[] {ASSEMBLE, CLASSIFY, KB_SEARCH, GENERATE};
            default ->
                roll < 0.7
                        ? new String[] {ASSEMBLE, CLASSIFY, GENERATE, ESCALATE}
                        : new String[] {ASSEMBLE, CLASSIFY, ESCALATE};
        };
    }

    /**
     * What the groundedness model makes of one answer: a score at or above the threshold on the promise
     * sentence when there is one, else a score well below it. Clean answers sit low with a long tail, as
     * the model's do on replies that restate their articles.
     */
    private static ScoredAnswer scoredAnswer(
            Random rnd,
            String traceId,
            String spanId,
            @Nullable String sessionId,
            Instant startedAt,
            String answer,
            @Nullable Promise promise) {
        if (promise == null) {
            double unsupported = Math.pow(rnd.nextDouble(), 3) * 0.9;
            return new ScoredAnswer(traceId, spanId, sessionId, startedAt, unsupported, unsupported * 0.3, null);
        }
        int start = answer.indexOf(promise.sentence());
        double unsupported = GROUNDEDNESS_THRESHOLD + 0.001 + rnd.nextDouble() * 0.022;
        double conflict = promise.contradicts() ? 0.7 + rnd.nextDouble() * 0.25 : 0.05 + rnd.nextDouble() * 0.1;
        return new ScoredAnswer(
                traceId,
                spanId,
                sessionId,
                startedAt,
                unsupported,
                conflict,
                new FlaggedSentence(start, start + promise.sentence().length(), promise.sentence()));
    }

    /**
     * Groundedness's rate test over the generated answers, as its replay counts it: a trace is one trial on
     * {@code generate_response}, bucketed at its first scored answer, and fails when any answer in it was
     * flagged. Members are every trial since onset, newest first; witnesses are each failed trial followed by
     * its flagged answers.
     */
    private static RateStat groundednessRate(List<ScoredAnswer> answers, Instant onset) {
        Map<String, List<ScoredAnswer>> byTrace = new LinkedHashMap<>();
        for (ScoredAnswer a : answers)
            byTrace.computeIfAbsent(a.traceId(), k -> new ArrayList<>()).add(a);
        List<Trial> trials = new ArrayList<>();
        for (Map.Entry<String, List<ScoredAnswer>> e : byTrace.entrySet()) {
            List<FindingEvidenceRepository.Ref> witness = new ArrayList<>();
            Instant first = null;
            for (ScoredAnswer a : e.getValue()) {
                if (first == null || a.startedAt().isBefore(first)) first = a.startedAt();
                if (a.flagged() != null) witness.add(FindingEvidenceRepository.Ref.span(a.traceId(), a.spanId()));
            }
            if (!witness.isEmpty()) witness.add(0, FindingEvidenceRepository.Ref.trace(e.getKey()));
            trials.add(new Trial(first, FindingEvidenceRepository.Ref.trace(e.getKey()), witness));
        }
        return rate(GENERATE, trials, onset);
    }

    /**
     * Frustration's rate test over the scored turns: a thread is one trial on the call site of its scored
     * turn's root span, and fails when that turn was flagged. Members are the sessions, witnesses each
     * frustrated session followed by the turn that fired.
     */
    private static RateStat frustrationRate(List<ScoredTurn> turns, Instant onset) {
        List<Trial> trials = new ArrayList<>();
        for (ScoredTurn t : turns) {
            List<FindingEvidenceRepository.Ref> witness = t.frustrated()
                    ? List.of(
                            FindingEvidenceRepository.Ref.session(t.conversationId()),
                            FindingEvidenceRepository.Ref.trace(t.traceId()))
                    : List.of();
            trials.add(new Trial(t.startedAt(), FindingEvidenceRepository.Ref.session(t.conversationId()), witness));
        }
        return rate(ASSEMBLE, trials, onset);
    }

    /** One trial of a rate test: when it counts, its member ref, and its witness refs when it failed. */
    private record Trial(
            Instant at, FindingEvidenceRepository.Ref member, List<FindingEvidenceRepository.Ref> witness) {}

    private static RateStat rate(String callSiteId, List<Trial> trials, Instant onset) {
        long baselineTrials = 0;
        long baselineFailures = 0;
        Instant newest = onset;
        List<Trial> since = new ArrayList<>();
        for (Trial t : trials) {
            if (t.at().isBefore(onset)) {
                baselineTrials++;
                if (!t.witness().isEmpty()) baselineFailures++;
            } else {
                since.add(t);
            }
            if (t.at().isAfter(newest)) newest = t.at();
        }
        since.sort((a, b) -> b.at().compareTo(a.at()));
        List<FindingEvidenceRepository.Ref> members = new ArrayList<>();
        List<FindingEvidenceRepository.Ref> witnesses = new ArrayList<>();
        long failures = 0;
        for (Trial t : since) {
            members.add(t.member());
            if (!t.witness().isEmpty()) {
                failures++;
                witnesses.addAll(t.witness());
            }
        }
        return new RateStat(
                callSiteId,
                onset,
                newest.truncatedTo(ChronoUnit.HOURS),
                baselineTrials,
                baselineFailures,
                since.size(),
                failures,
                members,
                witnesses);
    }

    private static String fill(
            String template, String customer, String orderId, String product, String amount, String date) {
        return template.replace("{customer}", customer)
                .replace("{orderId}", orderId)
                .replace("{product}", product)
                .replace("{amount}", amount)
                .replace("{date}", date);
    }

    private static long jitter(Random rnd, long base, double fraction) {
        return Math.max(1, Math.round(jitterD(rnd, base, fraction)));
    }

    private static double jitterD(Random rnd, double base, double fraction) {
        double noise = 1.0 + rnd.nextGaussian() * fraction;
        return base * Math.max(0.35, noise);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(Math.max(0, value)).setScale(10, RoundingMode.HALF_UP);
    }

    private static final int PREVIEW_CHARS = 240;

    /** Trimmed on a word boundary and marked as trimmed: a hard cut at 240 left previews ending
     *  mid-word, which reads as corrupted data rather than as a summary. */
    private static @Nullable String preview(@Nullable String text) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= PREVIEW_CHARS) return flat;
        String cut = flat.substring(0, PREVIEW_CHARS - 1);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > PREVIEW_CHARS / 2 ? cut.substring(0, lastSpace) : cut.strip()) + "…";
    }

    private record WireMessage(String role, Object content) {}

    private static String toMessageJson(ObjectMapper mapper, String role, List<ContentBlock> blocks) {
        return toMessagesJson(mapper, List.of(new WireMessage(role, blocks)));
    }

    private static String toMessagesJson(ObjectMapper mapper, List<WireMessage> messages) {
        return mapper.valueToTree(messages).toString();
    }

    /**
     * A real, decodable PNG: the "screenshot" every ticket arrives with. Hand-encoded (one IHDR, one
     * zlib-wrapped IDAT, one IEND, each with its own CRC) rather than pulled through ImageIO, which
     * this module does not otherwise depend on and which is headless-hostile in a container.
     *
     * <p>A 320×180 slate panel with a lighter band across the top, so a reader who opens the image
     * viewer sees a deliberate placeholder rather than a broken-image icon or a 1×1 pixel.
     */
    static byte[] minimalScreenshotPng() {
        final int width = 320;
        final int height = 180;
        byte[] raw = new byte[height * (1 + width * 3)];
        int p = 0;
        for (int y = 0; y < height; y++) {
            raw[p++] = 0; // filter type 0 (None), one per scanline, as the PNG spec requires
            boolean band = y < 28;
            for (int x = 0; x < width; x++) {
                raw[p++] = (byte) (band ? 0x33 : 0x1B);
                raw[p++] = (byte) (band ? 0x38 : 0x1F);
                raw[p++] = (byte) (band ? 0x42 : 0x26);
            }
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (Deflater deflater = new Deflater(Deflater.BEST_SPEED)) {
            deflater.setInput(raw);
            deflater.finish();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                compressed.write(chunk, 0, deflater.deflate(chunk));
            }
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.writeBytes(new byte[] {8, 2, 0, 0, 0}); // 8-bit, truecolour RGB, no interlace
        writeChunk(png, "IHDR", ihdr.toByteArray());
        writeChunk(png, "IDAT", compressed.toByteArray());
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] body) {
        writeInt(out, body.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(body);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(body);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /** A minimal, correctly cross-referenced single-page PDF: real bytes for the {@code export_ticket}
     *  document_ref demo, not a placeholder that would fail to open. */
    static byte[] minimalExportPdf() {
        String streamContent = "BT /F1 14 Tf 20 100 Td (Tessary Support -- Ticket Export) Tj ET";
        List<String> objects = List.of(
                "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n",
                "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n",
                "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 150]/Contents 4 0 R"
                        + "/Resources<</Font<</F1 5 0 R>>>>>>endobj\n",
                "4 0 obj<</Length " + streamContent.length() + ">>stream\n" + streamContent + "\nendstream\nendobj\n",
                "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = pdf.length();
            pdf.append(objects.get(i));
        }
        int xrefStart = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        pdf.append("trailer<</Size ")
                .append(objects.size() + 1)
                .append("/Root 1 0 R>>\nstartxref\n")
                .append(xrefStart)
                .append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * How many trace ids a seeded finding cites per evidence role.
     *
     * <p>Production writes the whole population uncapped ({@code FindingEvidenceRepository}'s class
     * comment argues why: a claim about a population cannot be audited against somebody else's
     * undisclosed sample). A seeder is not making an auditable claim; it is furnishing a sample
     * project, so it takes a bounded slice, sized to what a real sweep on this dataset produced,
     * and keeps the seed insert a predictable size.
     */
    private static final int EVIDENCE_PER_ROLE = 100;

    /** Per-(callSite) running sums of a value (cost or duration) by day, for real drift statistics. */
    private static final class DayAccumulator {
        private final double[] sum = new double[WINDOW_DAYS + 1];
        private final long[] count = new long[WINDOW_DAYS + 1];
        /** Which traces landed on which day, so `stat` can split them on the same boundary as the means. */
        private final Map<Integer, List<String>> traceIdsByDay = new LinkedHashMap<>();

        void add(int day, double value, String traceId) {
            sum[day] += value;
            count[day]++;
            traceIdsByDay.computeIfAbsent(day, d -> new ArrayList<>()).add(traceId);
        }

        DriftStat stat(
                String classifierKey,
                String callSiteId,
                String measure,
                String reference,
                int triggerDay,
                Instant todayMidnight,
                Instant now,
                List<String> sampleTraceIdsPost) {
            double preSum = 0;
            long preN = 0;
            double postSum = 0;
            long postN = 0;
            for (int d = 1; d <= WINDOW_DAYS; d++) {
                if (d < triggerDay) {
                    preSum += sum[d];
                    preN += count[d];
                } else {
                    postSum += sum[d];
                    postN += count[d];
                }
            }
            double meanPre = preN == 0 ? 0 : preSum / preN;
            double meanPost = postN == 0 ? meanPre : postSum / postN;
            Instant onset = todayMidnight.minus(WINDOW_DAYS - triggerDay, ChronoUnit.DAYS);
            // The same boundary the means were split on, so the evidence and the numbers describe
            // the same two windows: a baseline trace the agent reads must be one the "before" mean
            // was actually computed from.
            List<String> baseline = sliceTraces(traceIdsByDay, 1, triggerDay - 1);
            List<String> members = sliceTraces(traceIdsByDay, triggerDay, WINDOW_DAYS);
            return new DriftStat(
                    classifierKey,
                    callSiteId,
                    callSiteId,
                    callSiteId,
                    measure,
                    meanPost >= meanPre ? "up" : "down",
                    reference,
                    meanPre,
                    meanPost,
                    preN,
                    postN,
                    onset.toString(),
                    now.toString(),
                    // The citation list the finding's own basis text quotes, unchanged. Only one of
                    // the four metric stats is given one; the evidence sides below are populated for
                    // all of them, which is what RCA reads.
                    sampleTraceIdsPost,
                    baseline,
                    members,
                    // A metric drift draws no narrower subset: the whole post-onset population IS
                    // what it is complaining about, so `sides` reads the members as the flagged side.
                    List.of());
        }
    }

    /**
     * Turns a target failure rate into actual failures, deterministically rather than by coin flip.
     *
     * <p>A Bernoulli draw per call is the obvious thing and the wrong one here: the reference window
     * is only a few dozen calls wide, so whether the demo's tool-error finding compares against 0%,
     * 2.6% or 5.3% came down to which way the RNG fell for one project id. Accumulating the rate and
     * failing whenever a whole call's worth has built up spreads failures evenly and makes the
     * observed rate converge on the target one from the first handful of calls.
     */
    private static final class RateSchedule {
        private double credit;

        boolean next(double rate) {
            credit += rate;
            if (credit >= 1.0) {
                credit -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** Per-(callSite) running failure counts by day, for a real tool-error rate stat. */
    private static final class DayErrorAccumulator {
        private final long[] calls = new long[WINDOW_DAYS + 1];
        private final long[] failures = new long[WINDOW_DAYS + 1];
        /** Every call, and the failing subset: the two halves of the fraction this detector claims. */
        private final Map<Integer, List<String>> traceIdsByDay = new LinkedHashMap<>();

        private final Map<Integer, List<String>> failedTraceIdsByDay = new LinkedHashMap<>();

        void add(int day, boolean failed, String traceId) {
            calls[day]++;
            traceIdsByDay.computeIfAbsent(day, d -> new ArrayList<>()).add(traceId);
            if (failed) {
                failures[day]++;
                failedTraceIdsByDay.computeIfAbsent(day, d -> new ArrayList<>()).add(traceId);
            }
        }

        DriftStat stat(String callSiteId, int triggerDay, Instant todayMidnight, Instant now) {
            long preCalls = 0;
            long preFailures = 0;
            long postCalls = 0;
            long postFailures = 0;
            for (int d = 1; d <= WINDOW_DAYS; d++) {
                if (d < triggerDay) {
                    preCalls += calls[d];
                    preFailures += failures[d];
                } else {
                    postCalls += calls[d];
                    postFailures += failures[d];
                }
            }
            double meanPre = preCalls == 0 ? 0 : (double) preFailures / preCalls;
            double meanPost = postCalls == 0 ? meanPre : (double) postFailures / postCalls;
            Instant onset = todayMidnight.minus(WINDOW_DAYS - triggerDay, ChronoUnit.DAYS);
            String bucketKey = "tool:" + callSiteId;
            return new DriftStat(
                    "tool_error",
                    callSiteId,
                    bucketKey,
                    callSiteId,
                    "tool_error_rate",
                    meanPost >= meanPre ? "up" : "down",
                    "previous",
                    meanPre,
                    meanPost,
                    preCalls,
                    postCalls,
                    onset.toString(),
                    now.toString(),
                    List.of(),
                    sliceTraces(traceIdsByDay, 1, triggerDay - 1),
                    // Both halves of the fraction, which is what tool_error writes in production and
                    // what `failingCohortShape` needs kept apart: members are every call in the
                    // window, healthy ones included, and the witnesses are the failing subset. Hand
                    // it only the members and the check describes ordinary traffic.
                    sliceTraces(traceIdsByDay, triggerDay, WINDOW_DAYS),
                    sliceTraces(failedTraceIdsByDay, triggerDay, WINDOW_DAYS));
        }
    }

    /** The first {@link #EVIDENCE_PER_ROLE} distinct trace ids recorded on days {@code from..to}. */
    private static List<String> sliceTraces(Map<Integer, List<String>> byDay, int from, int to) {
        Set<String> out = new LinkedHashSet<>();
        for (int d = from; d <= to && out.size() < EVIDENCE_PER_ROLE; d++) {
            for (String traceId : byDay.getOrDefault(d, List.of())) {
                if (out.size() >= EVIDENCE_PER_ROLE) break;
                out.add(traceId);
            }
        }
        return List.copyOf(out);
    }
}
