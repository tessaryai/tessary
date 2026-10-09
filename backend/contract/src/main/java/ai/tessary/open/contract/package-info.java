// SPDX-License-Identifier: Apache-2.0
/**
 * The open API-contract module. Holds the checked-in canonical OpenAPI spec — the single source of
 * truth that ships in the open jar.
 *
 * <p>Open/closed boundary: this package depends only on jspecify, never on
 * the app or a closed module (enforced by the maven-enforcer banned-dependencies rule in this module's
 * POM).
 */
@NullMarked
package ai.tessary.open.contract;

import org.jspecify.annotations.NullMarked;
