// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

/**
 * A request path that authenticates itself and so must bypass {@link AuthFilter}'s cookie/bearer
 * session entirely — the open port {@code AuthFilter} consults through
 * {@code ObjectProvider<SelfAuthenticatingPath>.orderedStream()}, on the same
 * {@code classifier/conformance/FitReportSource} / {@code classifier/finding/ProfileSource} /
 * {@code slack/SlackMentionSource} inversion used everywhere else open code needs a paid answer:
 * {@code orderedStream()} over an empty classpath is an empty stream, not an unsatisfied dependency,
 * so the open edition boots with zero implementations and simply bypasses nothing extra.
 *
 * <p><b>Why this exists now.</b> #920 replaces {@code AuthFilter}'s hard-coded
 * {@code "/internal/slack/mention".equals(path)} line with this seam, because the shared-key compare
 * the bypass exists for lives inside {@code SlackMentionController} — which moves to
 * {@code tessary-paid/slack} in the same change (reason 1 for keeping it open, a checked-in spec path,
 * dissolved with #917/#944). A hard-coded literal in open {@code AuthFilter} naming a path whose
 * credential check now lives behind the paid boundary would leave the open filter carrying an
 * unauthenticated exemption for a route nothing in the open edition serves — a strictly worse posture
 * than the one this port replaces.
 *
 * <p><b>Generalises later, not now (tessary-paid/OPEN-CORE.md issue 24; rule 6).</b> {@code /git/github/callback}
 * and the actuator prefix are today's hand-written, open-forever bypasses in
 * {@code AuthFilter#shouldNotFilter} and are candidates to move onto this same port — but neither is
 * paid-contributed today, so folding them in here would be scope this issue was not asked to do.
 * ({@code /webhooks/git/} was a third until Track A removed the controller behind it.)
 *
 * <p>Implementations MUST authenticate the path some other way before answering it — this interface
 * only says "the session filter should stand aside," never "let this path through unchecked." A
 * {@code bypasses} implementation that always returns {@code true} with no credential check of its own
 * would silently fail every request on that path open; that is exactly the failure mode
 * {@link AuthFilter}'s comment at the call site warns about.
 */
public interface SelfAuthenticatingPath {

    /**
     * Whether this path authenticates itself and so should skip {@code AuthFilter} entirely.
     *
     * @param path the normalised request path, as produced by {@code AuthFilter#normalisedPath}
     */
    boolean bypasses(String path);
}
