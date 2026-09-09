// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

/**
 * One model a provider's own API reported, live — the unit {@link ProviderModelLister}s produce and
 * {@link ai.tessary.llm.ModelCatalog#mergeLive} overlays onto the static capability table.
 *
 * @param modelName the provider's own wire id for this model (what a {@code ChatModelFactory} build
 *     call must send back to reach it)
 * @param displayName a human-readable name — the provider's own {@code display_name}/{@code name}
 *     field when it has one, else the same value as {@code modelName}
 * @param vendor the model's maker, matching {@link ai.tessary.llm.ModelCatalog.CatalogEntry}'s
 *     existing {@code vendor} field convention (a display string, not the {@link SupportedMaker}
 *     enum itself — the enum is the filter, this is what the UI groups by)
 */
public record ProviderModel(String modelName, String displayName, String vendor) {}
