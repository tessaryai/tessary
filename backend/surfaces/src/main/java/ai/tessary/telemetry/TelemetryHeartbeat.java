// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import ai.tessary.cases.CaseRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.config.AppVersion;
import ai.tessary.edition.Edition;
import ai.tessary.pricing.PriceBook;
import ai.tessary.pricing.PriceBookFetcher;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.UsageUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The {@code home.tessary.ai} heartbeat: {@code POST /v1/ping} (devdocs/reference/telemetry-contract.md §1),
 * then the price book check ({@link PriceBookFetcher}), on one 6-hour tick. Replaces the Mixpanel per-event
 * stream. Backend-only per the contract's §4 scope note — there is no
 * frontend leg, and none is added here.
 *
 * <p>Lives in {@code surfaces}, next to {@link ai.tessary.metering.MeteringWorker} — the existing
 * precedent for exactly this shape: a plain {@code @Scheduled} heartbeat with no dedicated executor
 * bean (this ping has no per-request latency to protect, unlike the old {@code AnalyticsService}'s
 * fire-and-forget virtual-thread pool, deleted with it). {@code core} cannot host it: the counts it sends
 * come from {@code tenancy}, {@code analysis} and {@code substrate}.
 *
 * <p><b>The enabled-gate is checked FIRST, before {@link InstanceIdRepository}, {@link HomeTessaryClient} or
 * {@link PriceBookFetcher} are touched at all.</b> One gate covers both calls to home, so an opted-out install
 * neither pings nor fetches prices, and prices from the book bundled in its jar. This is the single choke point that makes the contract's §3 guarantee true —
 * "zero outbound calls, including DNS resolution, when disabled" — and what a future {@code
 * check-open-boot.sh} deny-list (named explicitly in the contract, not yet built) will
 * point at.
 */
@Component
public class TelemetryHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TelemetryHeartbeat.class);

    static final String PATH = "/v1/ping";

    /** home rejects the whole ping when a string field is over its schema's {@code maxLength}. */
    private static final int MAX_VERSION_CHARS = 64;

    private static final int MAX_PLATFORM_CHARS = 32;

    private final TelemetryProperties props;
    private final InstanceIdRepository instanceIds;
    private final HomeTessaryClient client;
    private final ProjectRepository projects;
    private final FindingRepository findings;
    private final CaseRepository cases;
    private final MetricRollupRepository rollups;
    private final PriceBookRepository priceBooks;
    private final PriceBookFetcher priceBookFetcher;
    private final ObjectMapper mapper;
    private final Edition edition;

    public TelemetryHeartbeat(
            TelemetryProperties props,
            InstanceIdRepository instanceIds,
            HomeTessaryClient client,
            ProjectRepository projects,
            FindingRepository findings,
            CaseRepository cases,
            MetricRollupRepository rollups,
            PriceBookRepository priceBooks,
            PriceBookFetcher priceBookFetcher,
            ObjectMapper mapper,
            Edition edition) {
        this.props = props;
        this.instanceIds = instanceIds;
        this.client = client;
        this.projects = projects;
        this.findings = findings;
        this.cases = cases;
        this.rollups = rollups;
        this.priceBooks = priceBooks;
        this.priceBookFetcher = priceBookFetcher;
        this.mapper = mapper;
        this.edition = edition;
    }

    /**
     * Fires once shortly after startup, then every 6h (contract §1 Frequency). A random 0-25s jitter on
     * {@code initialDelay} spreads the startup send across a self-hosted fleet booted together (a Kubernetes rollout, a
     * docker-compose stack) so they do not all POST in the same instant — negligible against the 6h
     * period, so it only needs to apply once, at {@code initialDelay}. {@code fixedDelay} (not
     * {@code fixedRate}) is measured from each run's completion, so a slow or failed send does not
     * compound into a tighter loop.
     */
    @Scheduled(
            initialDelayString =
                    "#{T(java.time.Duration).ofSeconds(5).toMillis() + T(java.lang.Math).round(T(java.lang.Math).random() * 25000)}",
            fixedDelayString = "#{T(java.time.Duration).ofHours(6).toMillis()}")
    public void tick() {
        if (!props.isEnabled()) {
            return; // The whole choke point — see class javadoc. Nothing below this line may run.
        }
        try {
            send();
        } catch (RuntimeException e) {
            // Telemetry must never fail, retry-storm, or otherwise make itself visible to an operator
            // who has not opted out — log and wait for the next heartbeat, exactly like MeteringWorker's
            // own per-tick failure handling.
            log.debug("telemetry heartbeat failed", e);
        }
        // Independent of the ping: a failed ping must not cost this tick its price check, or the reverse.
        try {
            PriceBookFetcher.Outcome outcome = priceBookFetcher.refresh();
            log.debug("price book check: {}", outcome);
        } catch (RuntimeException e) {
            log.debug("price book check failed", e);
        }
    }

    private void send() {
        String instanceId = instanceIds.get();
        long pingSeq = instanceIds.nextPingSeq();
        ObjectNode ping = payload(instanceId, pingSeq, Instant.now(), counts(), heldPriceBookDigest());

        try {
            int status = client.postJson(PATH, mapper.writeValueAsString(ping));
            if (status < 200 || status >= 300) {
                // home answers 400 with a reason for a payload it will not accept. Debug, not warn: the
                // operator can do nothing about it, and a loud log would make the ping visible.
                log.debug("telemetry ping answered HTTP {}", status);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("telemetry ping send failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The install's usage totals (contract §1), or null when any of them could not be read. A failed count
     * drops the whole object rather than failing the ping: the ping still says the install is alive, and
     * a partial object would read as zero for whatever was missing.
     */
    @Nullable
    ObjectNode counts() {
        try {
            ObjectNode counts = mapper.createObjectNode();
            counts.put("projects", projects.countAll());
            counts.put("spans", rollups.installLifetimeTotal(UsageUnit.INGESTED_SPANS));
            counts.put("findings", findings.countAll());
            counts.put("cases", cases.countAll());
            counts.put("l1", rollups.installLifetimeTotal(UsageUnit.L1_EVALS));
            return counts;
        } catch (RuntimeException e) {
            log.debug("telemetry counts unavailable; sending the ping without them", e);
            return null;
        }
    }

    /**
     * The sha256 of the price book this install prices from, or null when there is none to report (pricing
     * disabled, no book imported, or a book from before digests were recorded).
     */
    @Nullable
    String heldPriceBookDigest() {
        try {
            return priceBooks.currentDigest(PriceBook.SOURCE_LITELLM);
        } catch (RuntimeException e) {
            log.debug("held price book digest unavailable", e);
            return null;
        }
    }

    /**
     * The ping body, to home's {@code ping.v1} schema: the five fields it requires ({@code contract_version},
     * {@code instance_id}, {@code ping_seq}, {@code sent_at}, {@code app_version}), the optional
     * {@code edition}, {@code os} and {@code arch}, {@code counts} when they could be read, and {@code price_book}.
     * {@code price_book.schema_max} is the newest manifest schema {@link PriceBookFetcher} parses; its
     * {@code digest} is omitted when this install holds none.
     */
    ObjectNode payload(
            String instanceId,
            long pingSeq,
            Instant sentAt,
            @Nullable ObjectNode counts,
            @Nullable String priceBookDigest) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("contract_version", 1);
        payload.put("instance_id", instanceId);
        payload.put("ping_seq", pingSeq);
        payload.put("sent_at", sentAt.toString());
        payload.put("app_version", cap(appVersion(), MAX_VERSION_CHARS));
        payload.put("edition", edition.wire());
        payload.put("os", cap(osFamily(), MAX_PLATFORM_CHARS));
        payload.put("arch", cap(System.getProperty("os.arch", "unknown"), MAX_PLATFORM_CHARS));
        if (counts != null) payload.set("counts", counts);
        ObjectNode priceBook = payload.putObject("price_book");
        if (priceBookDigest != null) priceBook.put("digest", priceBookDigest);
        priceBook.put("schema_max", PriceBookFetcher.SUPPORTED_SCHEMA);
        return payload;
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The running app's version ({@link AppVersion#current()}): {@code "dev"} outside a packaged jar
     *  rather than a null field the contract does not mark optional. */
    private static String appVersion() {
        return AppVersion.current();
    }

    /** A coarse host OS family, not the full {@code os.name} string (which carries version numbers,
     *  e.g. "Windows 11") — the contract's example is the bare family ({@code "linux"}). */
    private static String osFamily() {
        return osFamily(System.getProperty("os.name", "unknown"));
    }

    /** {@link #osFamily()} over a given {@code os.name} value. */
    static String osFamily(String osName) {
        String raw = osName.toLowerCase(Locale.ROOT);
        // mac/darwin first: "darwin" contains "win".
        if (raw.contains("mac") || raw.contains("darwin")) return "macos";
        if (raw.contains("win")) return "windows";
        if (raw.contains("linux")) return "linux";
        return raw.isEmpty() ? "unknown" : raw;
    }
}
