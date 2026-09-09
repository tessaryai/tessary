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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Makes {@code SITE_DOMAIN} the one variable a self-hoster sets to put this instance on a real
 * hostname. Before this, the domain lived in three variables that had to agree
 * ({@code SITE_DOMAIN}, {@code TESSARY_AUTH_FRONTEND_URL}, {@code WORKOS_REDIRECT_URI}) and nothing
 * checked that they did: set only the first and the stack booted clean, served TLS, and bounced
 * every sign-in to {@code http://localhost/}.
 *
 * <p>With a domain set this guard (1) requires it to be a bare hostname, because compose builds the
 * agentic lanes' callback origin by prefixing {@code https://} and a scheme-prefixed value would
 * silently become {@code https://https://...}; (2) derives the sign-in landing origin and the WorkOS
 * callback from it whenever they are still on a {@code localhost} default, and refuses to start when
 * either was set explicitly to a different host, naming both keys; (3) requires the Let's Encrypt
 * address in {@code acme} mode, so an operator's certificate account is never registered to an
 * address that is not theirs; and (4) rejects an unknown {@code TLS_MODE} here rather than in the
 * frontend container's own log.
 *
 * <p>Inert when {@code SITE_DOMAIN} is blank, for the same reason {@link PlaceholderSecretGuard}
 * is: every non-compose boot, tests included, has no domain and must pass through untouched.
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

    private final TessaryProperties tessary;
    private final AuthProperties auth;
    private final WorkOsProperties workos;
    private final Environment env;

    public PublicOriginGuard(TessaryProperties tessary, AuthProperties auth, WorkOsProperties workos, Environment env) {
        this.tessary = tessary;
        this.auth = auth;
        this.workos = workos;
        this.env = env;
    }

    @PostConstruct
    void verify() {
        // BEFORE the no-domain early return: the value this catches is built precisely when
        // SITE_DOMAIN is blank, so a check that runs only under a domain never sees it.
        rejectHostlessMcpOrigin();
        String raw =
                tessary.getSiteDomain() == null ? "" : tessary.getSiteDomain().trim();
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

        String mode = tessary.getTlsMode() == null
                ? "acme"
                : tessary.getTlsMode().trim().toLowerCase(Locale.ROOT);
        if (!TLS_MODES.contains(mode)) {
            throw new IllegalStateException(
                    "Refusing to start: TLS_MODE must be one of acme, owncert or upstream, not '" + tessary.getTlsMode()
                            + "'.");
        }
        if ("acme".equals(mode)
                && (tessary.getAcmeEmail() == null || tessary.getAcmeEmail().isBlank())) {
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
            // An internal service name is not a competing public origin, it is the compose default —
            // and since agent containers share the backend's network unless an operator opts into
            // SANDBOX_NETWORK_ISOLATION, it stays the right value on a domain'd install too. Only a
            // value naming a DIFFERENT PUBLIC host is the mistake this check was written for.
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

    /** The configured value when it already names the domain; the derived one when it is blank or local; otherwise a refusal naming both keys. */
    private static String reconcile(String key, @Nullable String configured, String domain, String derived) {
        if (configured == null) {
            return derived;
        }
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
     * A value with a scheme and no host at all — {@code https://} and nothing else.
     *
     * <p>This is what compose used to build for an install that never set SITE_DOMAIN, by
     * concatenating {@code https://} with an empty domain. It is not blank, so every downstream
     * blank-check passed it, and both agent lanes were handed a URL with nowhere to connect: the
     * agent's only route to the evidence it reasons about, pointing at nothing. Blank stays legal —
     * an install that runs no agentic lane sets neither variable, and the engines already refuse a
     * blank one at run time with {@code NO_EVIDENCE_DOOR}. Only the half-built value is fatal.
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
     * A container-network service name ({@code backend}): a single dotless label the compose network
     * resolves, reachable from a sibling container even though it is meaningless outside.
     *
     * <p>{@code localhost} is deliberately NOT one of these, despite also being dotless. It is the
     * one host a sandbox container can never reach the backend on — inside a container it names the
     * container itself — so a value pointing there is the misconfiguration this check exists to
     * catch, not the compose default it exists to allow.
     */
    private static boolean isInternal(String host) {
        return !isLocal(host) && !host.contains(".") && !host.contains(":");
    }

    /** The hosts a compose default or a developer's own machine answers on: not a deployment origin. */
    private static boolean isLocal(String host) {
        if ("localhost".equals(host)) return true;
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!IP_LITERAL.matcher(bare).matches()) return false;
        try {
            return InetAddress.getByName(bare).isLoopbackAddress();
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
