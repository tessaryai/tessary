// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.Map;
import java.util.Optional;

/**
 * Provider-specific webhook handling: signature verification and push-event
 * normalisation. Keeps the generic {@code GitWebhookController} free of any
 * one provider's header names or payload shape.
 */
public interface GitWebhookAdapter {

    GitProvider provider();

    /** The platform-held shared secret for this provider's webhooks (from config). */
    String webhookSecret();

    /** Constant-time verification of the provider's signature header over the raw body. */
    boolean verifySignature(Map<String, String> headers, byte[] rawBody);

    /** The provider's unique delivery id header, for idempotency. */
    String deliveryId(Map<String, String> headers);

    String eventType(Map<String, String> headers);

    /** Parse a push event, or empty if this delivery isn't a branch push we act on. */
    Optional<NormalizedPushEvent> parsePush(Map<String, String> headers, String rawBody);

    record NormalizedPushEvent(String repoOwner, String repoName, String ref, String baseSha, String headSha) {}
}
