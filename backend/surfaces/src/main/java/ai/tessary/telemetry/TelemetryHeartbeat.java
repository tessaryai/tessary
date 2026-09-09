// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import ai.tessary.edition.Edition;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.telemetry.TelemetryBuckets.CountBucket;
import ai.tessary.telemetry.TelemetryBuckets.VolumeBucket;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The {@code home.tessary.ai} heartbeat ping (devdocs/reference/telemetry-contract.md §1), replacing the
 * Mixpanel per-event stream (#858). Backend-only per the contract's §4 scope note — there is no
 * frontend leg, and none is added here.
 *
 * <p>Lives in {@code surfaces}, next to {@link ai.tessary.metering.MeteringWorker} — the existing
 * precedent for exactly this shape: a plain {@code @Scheduled} heartbeat with no dedicated executor
 * bean (this ping has no per-request latency to protect, unlike the old {@code AnalyticsService}'s
 * fire-and-forget virtual-thread pool, deleted with it). {@code core} cannot host this class itself: it
 * has no dependency on {@code tenancy} or {@code substrate}, and this heartbeat needs both
 * ({@link OrganizationRepository}/{@link ProjectRepository} and {@link TraceV2Repository}).
 *
 * <p><b>The enabled-gate is checked FIRST, before {@link InstallIdRepository} or {@link HomeTessaryClient}
 * are touched at all.</b> This is the single choke point that makes the contract's §3 guarantee true —
 * "zero outbound calls, including DNS resolution, when disabled" — and what a future {@code
 * check-open-boot.sh} deny-list (#878, named explicitly in the contract, not built by this issue) will
 * point at.
 */
@Component
public class TelemetryHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TelemetryHeartbeat.class);

    private static final Duration INTERVAL = Duration.ofHours(24);

    private final TelemetryProperties props;
    private final InstallIdRepository installIds;
    private final HomeTessaryClient client;
    private final OrganizationRepository orgs;
    private final ProjectRepository projects;
    private final TraceV2Repository traces;
    private final ObjectMapper mapper;
    private final Edition edition;

    public TelemetryHeartbeat(
            TelemetryProperties props,
            InstallIdRepository installIds,
            HomeTessaryClient client,
            OrganizationRepository orgs,
            ProjectRepository projects,
            TraceV2Repository traces,
            ObjectMapper mapper,
            Edition edition) {
        this.props = props;
        this.installIds = installIds;
        this.client = client;
        this.orgs = orgs;
        this.projects = projects;
        this.traces = traces;
        this.mapper = mapper;
        this.edition = edition;
    }

    /**
     * Fires once shortly after startup, then every 24h (contract §1 Frequency). A random 0-25s jitter on
     * {@code initialDelay} spreads the startup send across a self-hosted fleet booted together (a Kubernetes rollout, a
     * docker-compose stack) so they do not all POST in the same instant — negligible against the 24h
     * period, so it only needs to apply once, at {@code initialDelay}. {@code fixedDelay} (not
     * {@code fixedRate}) is measured from each run's completion, so a slow or failed send does not
     * compound into a tighter loop.
     */
    @Scheduled(
            initialDelayString =
                    "#{T(java.time.Duration).ofSeconds(5).toMillis() + T(java.lang.Math).round(T(java.lang.Math).random() * 25000)}",
            fixedDelayString = "#{T(java.time.Duration).ofHours(24).toMillis()}")
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
    }

    private void send() {
        String installId = installIds.get();
        long orgCount = orgs.countAll();
        long projectCount = projects.countAll();
        long traceCount = traces.countStartedSince(Instant.now().minus(INTERVAL));

        ObjectNode payload = mapper.createObjectNode();
        payload.put("contract_version", 1);
        payload.put("install_id", installId);
        payload.put("edition", edition.wire());
        payload.put("app_version", appVersion());
        payload.put("os", osFamily());
        payload.put("arch", System.getProperty("os.arch", "unknown"));
        payload.put("org_count_bucket", CountBucket.forCount(orgCount).wire());
        payload.put("project_count_bucket", CountBucket.forCount(projectCount).wire());
        payload.put("trace_volume_bucket", VolumeBucket.forCount(traceCount).wire());
        payload.put("timestamp", Instant.now().toString());

        try {
            client.postJson("/ping", mapper.writeValueAsString(payload));
        } catch (IOException | RuntimeException e) {
            log.debug("telemetry ping send failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The running app's version, from the packaged jar's manifest ({@code Implementation-Version},
     *  set from {@code ${project.version}} by the Spring Boot repackage). Null outside a packaged
     *  jar (an IDE run, a test) — {@code "dev"} covers that case rather than sending a null field the
     *  contract does not mark optional. */
    private static String appVersion() {
        String v = TelemetryHeartbeat.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }

    /** A coarse host OS family, not the full {@code os.name} string (which carries version numbers,
     *  e.g. "Windows 11") — the contract's example is the bare family ({@code "linux"}). */
    private static String osFamily() {
        String raw = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (raw.contains("win")) return "windows";
        if (raw.contains("mac") || raw.contains("darwin")) return "macos";
        if (raw.contains("linux")) return "linux";
        return raw;
    }
}
