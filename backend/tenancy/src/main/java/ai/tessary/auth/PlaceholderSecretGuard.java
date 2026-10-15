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
 * The compose placeholder keys are published, so an instance running them is open: anyone can forge sessions, decrypt
 * stored provider keys and drive the launcher. Allowed only while the instance has no domain, fatal once it has one,
 * and never silent. Placeholders over generated keys because a key living only on a volume breaks restore from
 * {@code pg_dump}. Not gated on the production profile: exposure follows reachability. Context-safe because it throws
 * only when {@code SITE_DOMAIN} is set.
 */
@Component
public class PlaceholderSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderSecretGuard.class);

    /**
     * Exactly 32 bytes: {@link SessionCipher} rejects any other length, so a shorter placeholder would fail at first
     * sign-in instead of here. The only other copy is in {@code docker-compose.yml}.
     */
    static final String COOKIE_PLACEHOLDER = "Q0hBTkdFLU1FLWluc2VjdXJlLWRlZmF1bHQtY29va2k=";

    /** Base64 of the 32-byte ASCII string {@code CHANGE-ME-insecure-default-seal!}. */
    static final String SEALING_PLACEHOLDER = "Q0hBTkdFLU1FLWluc2VjdXJlLWRlZmF1bHQtc2VhbCE=";

    /**
     * A shipped default so an unconfigured install's triage and RCA work; anyone holding it can make the launcher spawn
     * containers on the host.
     */
    static final String LAUNCHER_PLACEHOLDER = "Q0hBTkdFLU1FLWluc2VjdXJlLWRlZmF1bHQtbGF1bmNo";

    /** Tolerated so an old {@code .env} carrying this literal reads as "no domain". */
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
        if (LAUNCHER_PLACEHOLDER.equals(rca.agentic().launcherApiKey())) {
            stillDefault.add("TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY");
        }
        if (stillDefault.isEmpty()) {
            return;
        }

        String domain = tessary.getSiteDomain();
        boolean hasDomain = !domain.isBlank() && !NO_DOMAIN_SENTINEL.equals(domain.trim());

        if (hasDomain) {
            throw new IllegalStateException("Refusing to start: this instance is served on " + domain.trim()
                    + ", and " + String.join(" and ", stillDefault) + " still hold the placeholder value "
                    + "docker-compose.yml ships. Those defaults are published in Tessary's own repository, so "
                    + "an instance still running them is exposed to anyone who reaches it. "
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
