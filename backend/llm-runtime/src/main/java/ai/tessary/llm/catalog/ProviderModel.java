// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

/**
 * One model a provider's API reported, overlaid by {@link ai.tessary.llm.ModelCatalog#mergeLive} onto the static table.
 * @param modelName the wire id a call must send @param displayName the provider's display name, else {@code modelName}
 * @param vendor a display string matching {@link ai.tessary.llm.ModelCatalog.CatalogEntry}'s convention, not the
 * {@link SupportedMaker} enum
 */
public record ProviderModel(String modelName, String displayName, String vendor) {}
