// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.config.TessaryProperties;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Makes {@code SITE_DOMAIN} the one variable for a real hostname: requires a bare hostname (compose prefixes
 * {@code https://}), derives the frontend and WorkOS callback origins when still on localhost and refuses a conflicting
 * explicit one, requires the ACME email in {@code acme} mode, and rejects an unknown {@code TLS_MODE}. Inert when
 * {@code SITE_DOMAIN} is blank, as every non-compose boot is.
 */
@Component
public class PublicOriginGuard {

    private static final Logger log = LoggerFactory.getLogger(PublicOriginGuard.class);
    private static final Pattern HOSTNAME =
            Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))*$");
    private static final Set<String> TLS_MODES = Set.of("acme", "owncert", "upstream");
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]+$");
    /** {@code frontend}'s former inert loopback key, still tolerated in a {@code .env} that carries it. */
    private static final String NO_DOMAIN_SENTINEL = "http://127.0.0.1:9443";

    /** The agentic lanes' callback origins: compose derives both from SITE_DOMAIN, so an explicit value must agree. */
    private static final String[] MCP_ORIGIN_KEYS = {
        "tessary.rca.agentic.mcp-base-url", "tessary.classifier.triage-mcp-base-url"
    };

    private static final String[] MCP_ORIGIN_ENV = {
        "TESSARY_RCA_AGENTIC_MCP_BASE_URL", "TESSARY_CLASSIFIER_TRIAGE_MCP_BASE_URL"
    };

    /** The seam a test uses to make a lookup fail. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress resolve(String host) throws UnknownHostException;
    }

    private final TessaryProperties tessary;
    private final AuthProperties auth;
    private final WorkOsProperties workos;
    private final Environment env;
    private final HostResolver resolver;

    @Autowired
    public PublicOriginGuard(TessaryProperties tessary, AuthProperties auth, WorkOsProperties workos, Environment env) {
        this(tessary, auth, workos, env, InetAddress::getByName);
    }

    PublicOriginGuard(
            TessaryProperties tessary,
            AuthProperties auth,
            WorkOsProperties workos,
            Environment env,
            HostResolver resolver) {
        this.tessary = tessary;
        this.auth = auth;
        this.workos = workos;
        this.env = env;
        this.resolver = resolver;
    }

    @PostConstruct
    void verify() {
        // Before the no-domain return: this value is built precisely when SITE_DOMAIN is blank.
        rejectHostlessMcpOrigin();
        String raw = tessary.getSiteDomain().trim();
        if (raw.isEmpty() || NO_DOMAIN_SENTINEL.equals(raw)) {
            return;
        }
        String domain = raw.toLowerCase(Locale.ROOT);
        if (!HOSTNAME.matcher(domain).matches()) {
            throw new IllegalStateException("Refusing to start: SITE_DOMAIN must be a bare hostname such as "
                    + "tessary.acme-corp.com, not '" + raw + "'. No scheme, port or path: the stack prefixes "
                    + "https:// itself wherever it needs the full origin.");
        }
        tessary.setSiteDomain(domain);

        String mode = tessary.getTlsMode().trim().toLowerCase(Locale.ROOT);
        if (!TLS_MODES.contains(mode)) {
            throw new IllegalStateException(
                    "Refusing to start: TLS_MODE must be one of acme, owncert or upstream, not '" + tessary.getTlsMode()
                            + "'.");
        }
        if ("acme".equals(mode) && tessary.getAcmeEmail().isBlank()) {
            throw new IllegalStateException("Refusing to start: ACME_EMAIL is required when SITE_DOMAIN is set and "
                    + "TLS_MODE is acme. Let's Encrypt registers the certificate account to that address, so it must "
                    + "be yours; set TLS_MODE=owncert or TLS_MODE=upstream to bring your own certificate or "
                    + "terminator instead.");
        }

        String origin = "https://" + domain + "/";
        String frontend = reconcile("TESSARY_AUTH_FRONTEND_URL", auth.getFrontendUrl(), domain, origin);
        auth.setFrontendUrl(frontend);
        boolean workosOn = workos.isEnabled();
        String callback = origin + "auth/callback";
        String redirect = workos.getRedirectUri();
        String redirectHost = hostOf(redirect);
        if (workosOn || redirectHost == null || isLocal(redirectHost)) {
            redirect = reconcile("WORKOS_REDIRECT_URI", redirect, domain, callback);
            workos.setRedirectUri(redirect);
        }
        for (int i = 0; i < MCP_ORIGIN_KEYS.length; i++) {
            String value = env.getProperty(MCP_ORIGIN_KEYS[i], "");
            String host = hostOf(value);
            // An internal service name is the compose default, still right on a domain'd install since agent containers
            // share the backend's network. Only a different public host is the mistake.
            if (host != null && !host.equals(domain) && !isInternal(host)) {
                throw new IllegalStateException("Refusing to start: SITE_DOMAIN is " + domain + " but "
                        + MCP_ORIGIN_ENV[i] + " is " + value
                        + ", which names a different host. Unset it to fall back to the internal service address, "
                        + "set it to https://" + domain
                        + ", or make the two agree; under SANDBOX_NETWORK_ISOLATION the agentic lanes call back into "
                        + "this origin from outside.");
            }
        }
        log.info(
                "public origin {} from SITE_DOMAIN={} (TLS_MODE={}, TESSARY_AUTH_FRONTEND_URL={}, WORKOS_REDIRECT_URI={})",
                origin,
                domain,
                mode,
                frontend,
                redirect);
    }

    private String reconcile(String key, String configured, String domain, String derived) {
        String host = hostOf(configured);
        if (host == null || isLocal(host)) {
            return derived;
        }
        if (!host.equals(domain)) {
            throw new IllegalStateException("Refusing to start: SITE_DOMAIN is " + domain + " but " + key + " is "
                    + configured + ", which names a different host. Unset " + key
                    + " to derive it from SITE_DOMAIN, or make the two agree.");
        }
        return configured;
    }

    /**
     * {@code https://} with no host, which compose built for a blank SITE_DOMAIN: not blank, so blank-checks passed it.
     * Blank stays legal; the engines refuse it at run time with {@code NO_EVIDENCE_DOOR}.
     */
    private void rejectHostlessMcpOrigin() {
        for (int i = 0; i < MCP_ORIGIN_KEYS.length; i++) {
            String value = env.getProperty(MCP_ORIGIN_KEYS[i], "").trim();
            if (value.isEmpty() || hostOf(value) != null) continue;
            throw new IllegalStateException("Refusing to start: " + MCP_ORIGIN_ENV[i] + " is '" + value
                    + "', which carries no host for the agent to connect to. The agentic RCA and triage lanes read "
                    + "every trace they reason about through that address. Unset it to fall back to the internal "
                    + "service address, or — if this install sets SANDBOX_NETWORK_ISOLATION or SANDBOX_BACKEND=e2b, "
                    + "where the agent runs off this network — set SITE_DOMAIN and point this at that public origin.");
        }
    }

    /**
     * A single dotless compose service name. {@code localhost} is excluded: inside a sandbox container it names the
     * container itself.
     */
    private boolean isInternal(String host) {
        return !isLocal(host) && !host.contains(".") && !host.contains(":");
    }

    /** The hosts a compose default or a developer's own machine answers on: not a deployment origin. */
    private boolean isLocal(String host) {
        if ("localhost".equals(host)) return true;
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!IP_LITERAL.matcher(bare).matches()) return false;
        try {
            return resolver.resolve(bare).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static @Nullable String hostOf(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
