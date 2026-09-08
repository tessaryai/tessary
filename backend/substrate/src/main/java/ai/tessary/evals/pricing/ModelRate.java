// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pricing;

/**
 * A model's rates together with the book they came from.
 *
 * <p>The version travels with the rates because a writer must stamp it on the row it prices — a cost is a
 * fact about what a call was billed at, and it is only auditable if the book that produced it is named.
 * With a manual override book layered over the vendored one, the answer to "which book priced this" is not
 * deducible from the model id alone, so it is returned rather than inferred.
 */
public record ModelRate(String priceBookVersion, ModelRates rates) {}
