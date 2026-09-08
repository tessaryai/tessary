// SPDX-License-Identifier: Apache-2.0
/**
 * The open API-contract module (Phase 3). Holds the checked-in canonical OpenAPI spec — the single
 * source of truth that ships in the open jar — and, in later slices, the generated Spring API interfaces
 * the app's controllers implement (contract-first boundary, §A2 layer 6).
 *
 * <p>Open/closed boundary: this package depends only on {@code shared} + jspecify/jackson, never on
 * the app or a closed module (enforced by the maven-enforcer banned-dependencies rule in this module's
 * POM).
 */
@NullMarked
package ai.tessary.evals.open.contract;

import org.jspecify.annotations.NullMarked;
