// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.config.RcaProperties;
import ai.tessary.config.TessaryProperties;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when this instance has a real domain and is still sealing its data with one of
 * the placeholder keys {@code docker-compose.yml} ships.
 *
 * <p><b>Why placeholder keys exist at all.</b> Before this, a self-hoster could not run
 * {@code docker compose up -d} without first writing a {@code .env} holding two generated keys, so
 * the ten-minute quickstart did not start with the command it is named after. Langfuse and Supabase
 * ship fixed placeholder secrets with a change-before-production banner; Appsmith, n8n and Gitea
 * generate them into a mounted volume. We took the first, because a generated key that lives only
 * on a volume adds a failure mode we would otherwise not have: a {@code pg_dump} restored onto a new
 * host carries the ciphertext and not the key, and every sealed credential in it becomes unreadable
 * with no signal at all. A documented placeholder keeps backup and restore whole, and keeps the key
 * visible to the operator who owns it.
 *
 * <p><b>This class is what makes that defensible.</b> The three defaults below are published in our
 * own repository, so an instance still running them is not "weakly configured", it is open: anyone
 * can forge a session cookie against the first, decrypt every stored provider API key with the
 * second, and drive the sandbox launcher with the third. That is an acceptable state on the
 * localhost box the quickstart describes and nowhere else, so the boundary this guard draws is
 * exactly that: a placeholder is permitted while the instance has no domain, and fatal the moment
 * it has one.
 *
 * <p><b>Not gated on the {@code production} profile</b>, unlike {@link AuthRequiredInProdGuard}. The
 * exposure is a property of being reachable, not of which profile booted, and a non-production boot
 * on a real hostname is exactly as exposed.
 *
 * <p><b>Why throwing here is Spring-context-safe.</b> {@code AgenticRcaEngine#validateSandboxConfig}
 * records why a blank {@code tessary.rca.agentic.mcp-base-url} is not made fatal at bean
 * construction: nothing but compose sets it, so a bare {@code mvn test} boot would fail context
 * refresh on that line alone. This guard inverts that shape. It throws only when {@code SITE_DOMAIN}
 * IS set, and its default is blank, so every non-compose boot passes through silently.
 *
 * <p>The warning branch is the other half, and is the one thing the Langfuse pattern does badly:
 * running on a published key is never invisible, even on localhost.
 */
@Component
public class PlaceholderSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderSecretGuard.class);

    /**
     * Base64 of the 32-byte ASCII string {@code CHANGE-ME-insecure-default-cooki}. Exactly 32 bytes
     * is not cosmetic: {@link SessionCipher} rejects any other length, so a shorter placeholder
     * would boot green and fail at first sign-in instead of being caught here. Kept distinct from
     * the sealing key below so a warning can name which one is still default. The only other copy
     * of this literal is {@code docker-compose.yml}'s backend service.
     */
    static final String COOKIE_PLACEHOLDER = "Q0hBTkdFLU1FLWluc2VjdXJlLWRlZmF1bHQtY29va2k=";

    /** Base64 of the 32-byte ASCII string {@code CHANGE-ME-insecure-default-seal!}. */
    static final String SEALING_PLACEHOLDER = "Q0hBTkdFLU1FLWluc2VjdXJlLWRlZmF1bHQtc2VhbCE=";

    /**
     * Base64 of {@code CHANGE-ME-insecure-default-launch}, the bearer the backend presents to the
     * sandbox launcher and the launcher checks it against.
     *
     * <p>It became a placeholder for the same reason the two above are: compose used to default both
     * halves to the empty string, and {@code server.js} rejects every request when its own key is
     * empty — so an install that set nothing did not get "no auth", it got a 401 on every triage and
     * RCA run. A shipped default makes the out-of-the-box install work; naming it here is what keeps
     * "works by default" from quietly meaning "ships a public secret to production", since anyone
     * holding this value can drive the launcher into spawning containers on the host.
     */
    static final String LAUNCHER_PLACEHOLDER = "Q0hBTkdFLU1FLWluc2VjdXJlLWRlZmF1bHQtbGF1bmNo";

    /**
     * The inert loopback address the static Caddyfile once needed as its {@code SITE_DOMAIN}
     * default, before {@code frontend/caddy/render.sh} made a blank domain mean "no site".
     * Nothing passes it any more; tolerated so a {@code .env} that still carries the
     * literal reads as "no domain" rather than as a hostname.
     */
    private static final String NO_DOMAIN_SENTINEL = "http://127.0.0.1:9443";

    private final TessaryProperties tessary;
    private final AuthProperties auth;
    private final RcaProperties rca;

    public PlaceholderSecretGuard(TessaryProperties tessary, AuthProperties auth, RcaProperties rca) {
        this.tessary = tessary;
        this.auth = auth;
        this.rca = rca;
    }

    @PostConstruct
    void verify() {
        List<String> stillDefault = new ArrayList<>();
        if (COOKIE_PLACEHOLDER.equals(auth.getCookiePassword())) {
            stillDefault.add("TESSARY_AUTH_COOKIE_PASSWORD");
        }
        if (SEALING_PLACEHOLDER.equals(tessary.getSecretKey())) {
            stillDefault.add("TESSARY_SECRET_KEY");
        }
        if (LAUNCHER_PLACEHOLDER.equals(rca.getAgentic().getLauncherApiKey())) {
            stillDefault.add("TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY");
        }
        if (stillDefault.isEmpty()) {
            return;
        }

        String domain = tessary.getSiteDomain();
        boolean hasDomain = domain != null && !domain.isBlank() && !NO_DOMAIN_SENTINEL.equals(domain.trim());

        if (hasDomain) {
            throw new IllegalStateException("Refusing to start: this instance is served on " + domain.trim()
                    + ", and " + String.join(" and ", stillDefault) + " still hold the placeholder value "
                    + "docker-compose.yml ships. Those defaults are published in Tessary's own repository, so "
                    + "anyone can forge a session against this instance and read every credential it has sealed. "
                    + "Generate a replacement for each with `openssl rand -base64 32` and set it in .env, or "
                    + "unset SITE_DOMAIN to run on localhost only.");
        }
        log.warn(
                "Running on the shipped placeholder value for {}. Those defaults are public, so this instance "
                        + "is safe only while it stays on localhost and holds nothing real. Generate a replacement "
                        + "for each with `openssl rand -base64 32` before it does. Setting SITE_DOMAIN with any of "
                        + "them still in place refuses the boot.",
                String.join(" and ", stillDefault));
    }
}
