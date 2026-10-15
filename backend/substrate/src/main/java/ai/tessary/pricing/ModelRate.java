// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

/**
 * Rates with the book they came from, which a writer stamps on the row so a cost is auditable. With an override book
 * layered over the vendored one, the book is not deducible from the model id.
 */
public record ModelRate(String priceBookVersion, ModelRates rates) {}
