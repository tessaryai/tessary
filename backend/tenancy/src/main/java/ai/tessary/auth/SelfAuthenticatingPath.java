// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

/**
 * A request path that authenticates itself and so must bypass {@link AuthFilter}'s cookie/bearer
 * session entirely. {@code AuthFilter} consults every implementation through {@code
 * ObjectProvider<SelfAuthenticatingPath>.orderedStream()}, so a build with no implementations on
 * the classpath simply bypasses nothing extra.
 *
 * <p>{@code /git/github/callback} and the actuator prefix are today's hand-written, open-forever
 * bypasses in {@code AuthFilter#shouldNotFilter} and are candidates to move onto this same port,
 * but doing so is out of scope here.
 *
 * <p>Implementations must authenticate the path some other way before answering it: this interface
 * only says "the session filter should stand aside," never "let this path through unchecked." A
 * {@code bypasses} implementation that always returns {@code true} with no credential check of its
 * own would silently leave every request on that path open, which is exactly the failure mode
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
