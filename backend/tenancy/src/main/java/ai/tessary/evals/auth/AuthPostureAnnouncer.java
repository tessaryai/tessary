// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Says the instance's authentication posture out loud at startup.
 *
 * <p>Sibling of {@link AuthRequiredInProdGuard}, and the same shape: a bean whose only job is to
 * look at the resolved configuration once and tell the operator what it means. The guard refuses
 * the boot in the one state that must never reach production; this one narrates the states that
 * are allowed but worth knowing about.
 *
 * <p><b>Why not inside {@link AuthFilter}.</b> It first lived there, on the reasoning that the fact
 * belongs next to the behaviour it describes. That put a one-shot startup announcement inside a
 * per-request filter, which is a different lifecycle and a different job — and it made
 * {@code AuthFilter} the answer to two questions instead of one. Splitting it also lets this bean
 * be read on its own: the whole posture table is in one twenty-line file.
 *
 * <p><b>Why warn at all in the enforced case.</b> Silence is what made the pre-#924 behaviour
 * dangerous rather than merely permissive: nothing anywhere told you the door was open. Saying the
 * posture out loud at startup turns a confusing incident into a log line.
 *
 * <p><b>Re-decided by #852/#996: {@code evals.auth.disabled} is now authoritative on its own,
 * regardless of which {@link AuthProvider} is active.</b> Before #852, {@code provider.isEnabled()}
 * being {@code true} meant enforcement no matter what the flag said — that branch was this class's
 * ordinary, no-warning case. #852 added {@link PasswordAuthProvider}, unconditionally enabled,
 * which made {@code provider.isEnabled()} true in essentially every real deployment and broke that
 * branch's premise: it kept announcing "enforced" even when {@code evals.auth.disabled=true} was
 * ALSO set and {@link AuthFilter#shouldNotFilter} was actually bypassing everything — the one
 * operator-facing safety signal for exactly that risk was saying the opposite of the truth (caught
 * by #996's crew review, not by any test). The flag alone now decides which branch this announces.
 */
@Component
public class AuthPostureAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(AuthPostureAnnouncer.class);

    private final AuthProvider provider;
    private final AuthProperties auth;

    public AuthPostureAnnouncer(AuthProvider provider, AuthProperties auth) {
        this.provider = provider;
        this.auth = auth;
    }

    @PostConstruct
    void announce() {
        if (auth.isDisabled()) {
            log.warn(
                    "AUTH IS DISABLED: evals.auth.disabled=true, so every /api/** and /mcp request is served "
                            + "unauthenticated, regardless of which identity provider ({}) is configured. This is "
                            + "correct for local development and the test suite, and is never correct on a reachable "
                            + "host.",
                    provider.getClass().getSimpleName());
        } else if (provider.isEnabled()) {
            log.info(
                    "Auth: {} active; /api/** and /mcp are enforced.",
                    provider.getClass().getSimpleName());
        } else {
            // Only WorkOsClient can ever be unenabled here — PasswordAuthProvider (#852) has no
            // external config to be missing, so it is unconditionally enabled. This branch is
            // therefore dead in the open edition today, but stays: a future third AuthProvider
            // could reintroduce a real "misconfigured, no provider works" state.
            log.warn("No identity provider is configured, so /api/** and /mcp will answer 401. Configure one, "
                    + "or set evals.auth.disabled=true to serve this instance unauthenticated on purpose.");
        }
    }
}
